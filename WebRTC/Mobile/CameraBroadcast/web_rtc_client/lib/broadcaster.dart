import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

enum BroadcastStatus { idle, connecting, connected, streaming, error }

typedef StatusCallback = void Function(BroadcastStatus status, String message);

/// Owns the camera and streams it to every viewer through the signaling
/// server. One [RTCPeerConnection] is created per viewer; the phone is always
/// the offerer.
class Broadcaster {
  // Native foreground service (keeps the camera alive when app is backgrounded).
  static const _service = MethodChannel('webrtc/foreground');

  static const _iceConfig = {
    'iceServers': [
      {'urls': 'stun:stun.l.google.com:19302'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  StatusCallback? onStatus;

  WebSocketChannel? _channel;
  StreamSubscription? _wsSub;
  MediaStream? _localStream;
  final Map<int, RTCPeerConnection> _peers = {};

  String _address = '';
  String _facing = 'environment'; // 'environment' = back, 'user' = front
  bool _stopped = false;
  bool _serviceRunning = false;

  bool get _hasViewers => _peers.isNotEmpty;

  Future<void> start(String address) async {
    _stopped = false;
    _address = address;
    _notify(BroadcastStatus.connecting, 'Đang mở camera…');

    try {
      _localStream ??= await _openCamera();
    } catch (e) {
      _notify(BroadcastStatus.error, 'Không mở được camera: $e');
      return;
    }

    await _startForegroundService();
    _connectSignaling();
  }

  Future<MediaStream> _openCamera() {
    return navigator.mediaDevices.getUserMedia({
      'audio': true,
      'video': {
        'facingMode': _facing,
        'width': {'ideal': 1280},
        'height': {'ideal': 720},
        'frameRate': {'ideal': 30},
      },
    });
  }

  void _connectSignaling() {
    final url = _normalizeWsUrl(_address);
    _notify(BroadcastStatus.connecting, 'Đang kết nối máy chủ…');
    try {
      _channel = WebSocketChannel.connect(Uri.parse(url));
    } catch (e) {
      _notify(BroadcastStatus.error, 'Địa chỉ máy chủ không hợp lệ: $e');
      return;
    }

    _channel!.sink.add(jsonEncode({'type': 'role', 'role': 'broadcaster'}));
    _notify(BroadcastStatus.connected, 'Đã kết nối, chờ người xem…');

    _wsSub = _channel!.stream.listen(
      _onSignal,
      onDone: _onWsClosed,
      onError: (_) => _onWsClosed(),
      cancelOnError: true,
    );
  }

  Future<void> _onSignal(dynamic raw) async {
    final msg = jsonDecode(raw as String) as Map<String, dynamic>;
    switch (msg['type']) {
      case 'viewer-join':
        await _createOfferFor(msg['viewerId'] as int);
        break;
      case 'answer':
        await _peers[msg['viewerId']]
            ?.setRemoteDescription(RTCSessionDescription(msg['sdp'], 'answer'));
        break;
      case 'candidate':
        final c = msg['candidate'];
        await _peers[msg['viewerId']]?.addCandidate(
          RTCIceCandidate(c['candidate'], c['sdpMid'], c['sdpMLineIndex']),
        );
        break;
      case 'viewer-leave':
        await _closePeer(msg['viewerId'] as int);
        break;
      case 'command':
        if (msg['action'] == 'switch-camera') await switchCamera();
        break;
    }
  }

  Future<void> _createOfferFor(int viewerId) async {
    await _closePeer(viewerId);
    final pc = await createPeerConnection(_iceConfig);
    _peers[viewerId] = pc;

    // Add all local tracks (video + audio) to this viewer's connection.
    for (final track in _localStream!.getTracks()) {
      await pc.addTrack(track, _localStream!);
    }

    pc.onIceCandidate = (candidate) {
      _send({
        'type': 'candidate',
        'viewerId': viewerId,
        'candidate': candidate.toMap(),
      });
    };
    pc.onConnectionState = (state) {
      if (state == RTCPeerConnectionState.RTCPeerConnectionStateConnected) {
        _notify(BroadcastStatus.streaming, 'Đang truyền hình ảnh tới người xem.');
      }
    };

    final offer = await pc.createOffer({
      'offerToReceiveAudio': false,
      'offerToReceiveVideo': false,
    });
    await pc.setLocalDescription(offer);
    _send({'type': 'offer', 'viewerId': viewerId, 'sdp': offer.sdp});
  }

  /// Flip between front and back camera. Called locally or via viewer command.
  Future<void> switchCamera() async {
    final videoTrack = _localStream?.getVideoTracks().firstOrNull;
    if (videoTrack == null) return;
    try {
      await Helper.switchCamera(videoTrack);
      _facing = _facing == 'environment' ? 'user' : 'environment';
    } catch (_) {
      // Some devices only expose one camera; ignore.
    }
  }

  Future<void> _closePeer(int viewerId) async {
    final pc = _peers.remove(viewerId);
    if (pc != null) await pc.close();
    if (!_hasViewers && !_stopped) {
      _notify(BroadcastStatus.connected, 'Đã kết nối, chờ người xem…');
    }
  }

  void _onWsClosed() {
    if (_stopped) return;
    _notify(BroadcastStatus.connecting, 'Mất kết nối máy chủ, thử lại…');
    for (final id in _peers.keys.toList()) {
      _closePeer(id);
    }
    // Auto-reconnect so a dropped Wi-Fi link recovers by itself.
    Future.delayed(const Duration(seconds: 2), () {
      if (!_stopped) _connectSignaling();
    });
  }

  void _send(Map<String, dynamic> msg) {
    _channel?.sink.add(jsonEncode(msg));
  }

  Future<void> stop() async {
    _stopped = true;
    await _wsSub?.cancel();
    _wsSub = null;
    await _channel?.sink.close();
    _channel = null;
    for (final pc in _peers.values) {
      await pc.close();
    }
    _peers.clear();
    for (final track in _localStream?.getTracks() ?? <MediaStreamTrack>[]) {
      await track.stop();
    }
    await _localStream?.dispose();
    _localStream = null;
    await _stopForegroundService();
    _notify(BroadcastStatus.idle, '');
  }

  void dispose() {
    stop();
  }

  Future<void> _startForegroundService() async {
    if (_serviceRunning) return;
    try {
      await _service.invokeMethod('start');
      _serviceRunning = true;
    } on PlatformException {
      // Not Android / service unavailable - streaming still works in foreground.
    } on MissingPluginException {
      // Ignore on platforms without the native side.
    }
  }

  Future<void> _stopForegroundService() async {
    if (!_serviceRunning) return;
    try {
      await _service.invokeMethod('stop');
    } catch (_) {}
    _serviceRunning = false;
  }

  void _notify(BroadcastStatus status, String message) {
    onStatus?.call(status, message);
  }

  static String _normalizeWsUrl(String address) {
    var a = address.trim();
    if (a.startsWith('ws://') || a.startsWith('wss://')) return a;
    if (a.startsWith('http://')) return 'ws://${a.substring(7)}';
    if (a.startsWith('https://')) return 'wss://${a.substring(8)}';
    return 'ws://$a';
  }
}
