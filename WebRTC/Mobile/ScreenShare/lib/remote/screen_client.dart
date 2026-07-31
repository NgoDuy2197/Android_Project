import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import '../native_bridge.dart';

enum ClientStatus { disconnected, connecting, connected, error }

/// Chạy trên máy "Client": kết nối tới server, chia sẻ *toàn bộ màn hình* qua
/// WebRTC, và tuân theo lệnh của server (bật/tắt chia sẻ, xem trực tiếp, chụp
/// ảnh trước/sau).
///
/// Mọi thao tác đều được bọc try/catch: một app không cho quay, người dùng từ
/// chối quyền, hay lệnh lỗi từ server đều KHÔNG làm app dừng đột ngột — thay
/// vào đó lỗi được báo về server và (khi hợp lý) client tự thử chia sẻ lại.
class ScreenClient extends ChangeNotifier {
  static const _iceConfig = {
    'iceServers': [
      {'urls': 'stun:stun.l.google.com:19302'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  static const _maxReshareAttempts = 3;
  static const _reshareDelay = Duration(seconds: 2);

  ClientStatus status = ClientStatus.disconnected;
  String message = '';
  bool sharing = false;
  int? serverAssignedId;

  WebSocketChannel? _channel;
  StreamSubscription? _wsSub;
  MediaStream? _screenStream;
  RTCPeerConnection? _pc;
  String? _name;
  bool _stopped = false;

  // Ý định của người dùng: có muốn đang chia sẻ hay không. Dùng để tự thử lại
  // khi luồng màn hình kết thúc ngoài ý muốn (vd hệ thống thu hồi quyền).
  bool _wantShare = false;
  int _reshareAttempts = 0;
  bool _viewerWaiting = false; // server đã yêu cầu xem, chờ có luồng để offer

  bool get connected => status == ClientStatus.connected;

  // --- Vòng đời -----------------------------------------------------------

  Future<void> connect(String address, String name) async {
    if (status == ClientStatus.connecting || connected) return;
    _stopped = false;
    _name = name;
    _connectWs(address);
  }

  void _connectWs(String address) {
    final url = _wsUrl(address);
    _set(ClientStatus.connecting, 'Đang kết nối máy chủ…');
    try {
      _channel = WebSocketChannel.connect(Uri.parse(url));
    } catch (e) {
      _set(ClientStatus.error, 'Địa chỉ không hợp lệ: $e');
      return;
    }
    _send({'type': 'hello', 'name': _name ?? ''});
    _wsSub = _channel!.stream.listen(
      _onSignal,
      onDone: _onWsClosed,
      onError: (_) => _onWsClosed(),
      cancelOnError: true,
    );
  }

  Future<void> _onSignal(dynamic raw) async {
    Map<String, dynamic> msg;
    try {
      msg = jsonDecode(raw as String) as Map<String, dynamic>;
    } catch (_) {
      return; // bỏ qua tin lỗi
    }
    try {
      switch (msg['type']) {
        case 'welcome':
          serverAssignedId = (msg['id'] as num?)?.toInt();
          _set(ClientStatus.connected, 'Đã kết nối, chờ lệnh…');
          _sendStatus();
          break;
        case 'command':
          await _onCommand(msg['action'] as String?);
          break;
        case 'answer':
          await _pc?.setRemoteDescription(
            RTCSessionDescription(msg['sdp'] as String?, 'answer'),
          );
          break;
        case 'candidate':
          final c = msg['candidate'] as Map<String, dynamic>?;
          if (c != null) {
            await _pc?.addCandidate(
              RTCIceCandidate(c['candidate'], c['sdpMid'], c['sdpMLineIndex']),
            );
          }
          break;
      }
    } catch (e) {
      _report('signal', false, 'Lỗi xử lý tín hiệu: $e');
    }
  }

  // --- Xử lý lệnh ---------------------------------------------------------

  Future<void> _onCommand(String? action) async {
    switch (action) {
      case 'share-start':
        await startShare();
        break;
      case 'share-stop':
        await stopShare();
        break;
      case 'view-start':
        await _startViewing();
        break;
      case 'view-stop':
        await _stopViewing();
        break;
      case 'photo-front':
        await _takePhoto(front: true);
        break;
      case 'photo-back':
        await _takePhoto(front: false);
        break;
    }
  }

  // --- Chia sẻ màn hình (client = bên phát WebRTC) ------------------------

  /// Bắt đầu quay màn hình. Hệ thống sẽ hiện hộp thoại xin phép (bắt buộc của
  /// Android). Có thể gọi từ nút bấm của client hoặc từ lệnh của server.
  Future<void> startShare() async {
    _wantShare = true;
    if (_screenStream != null) {
      _report('share-start', true, 'Đang chia sẻ màn hình.');
      return;
    }
    // Foreground service phải chạy trước getDisplayMedia (Android 14+).
    await NativeBridge.instance.startProjectionService();
    try {
      final stream = await navigator.mediaDevices.getDisplayMedia({
        'video': true,
        'audio': false,
      });
      _screenStream = stream;
      sharing = true;
      _reshareAttempts = 0;
      _watchScreenEnded(stream);
      _report('share-start', true, 'Đã bắt đầu chia sẻ màn hình.');
      _sendStatus();
      // Nếu server đang chờ xem thì phát luồng ngay.
      if (_viewerWaiting) await _startViewing();
    } catch (e) {
      // Người dùng từ chối hoặc thiết bị/app không cho quay — chấp nhận, không
      // chia sẻ, không crash.
      _wantShare = false;
      sharing = false;
      await NativeBridge.instance.stopProjectionService();
      _report('share-start', false, 'Không quay được màn hình: $e');
      _sendStatus();
    }
  }

  Future<void> stopShare() async {
    _wantShare = false;
    _viewerWaiting = false;
    await _stopViewing();
    await _disposeScreenStream();
    sharing = false;
    await NativeBridge.instance.stopProjectionService();
    _report('share-stop', true, 'Đã dừng chia sẻ màn hình.');
    _sendStatus();
  }

  /// Theo dõi luồng màn hình; nếu kết thúc ngoài ý muốn (hệ thống thu hồi quyền,
  /// nội dung được bảo vệ...) thì thử chia sẻ lại nếu người dùng vẫn muốn.
  void _watchScreenEnded(MediaStream stream) {
    for (final track in stream.getVideoTracks()) {
      track.onEnded = () {
        if (_stopped || !_wantShare) return;
        _handleScreenLost();
      };
    }
  }

  Future<void> _handleScreenLost() async {
    await _disposeScreenStream();
    sharing = false;
    _sendStatus();
    if (_reshareAttempts >= _maxReshareAttempts) {
      _wantShare = false;
      await NativeBridge.instance.stopProjectionService();
      _report('share', false,
          'Màn hình ngừng chia sẻ, đã thử lại nhưng không được. Tạm nghỉ.');
      return;
    }
    _reshareAttempts++;
    _report('share', false,
        'Mất chia sẻ màn hình, thử lại lần $_reshareAttempts…');
    Future.delayed(_reshareDelay, () {
      if (!_stopped && _wantShare && _screenStream == null) startShare();
    });
  }

  Future<void> _startViewing() async {
    _viewerWaiting = true;
    // Chưa có luồng màn hình → tự bật chia sẻ trước.
    if (_screenStream == null) {
      await startShare();
      return; // startShare sẽ gọi lại _startViewing khi có luồng
    }
    if (_pc != null) return; // đã đang phát
    final stream = _screenStream!;
    try {
      final pc = await createPeerConnection(_iceConfig);
      _pc = pc;
      for (final track in stream.getTracks()) {
        await pc.addTrack(track, stream);
      }
      pc.onIceCandidate = (candidate) {
        _send({'type': 'candidate', 'candidate': candidate.toMap()});
      };
      final offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      _send({'type': 'offer', 'sdp': offer.sdp});
    } catch (e) {
      await _closePc();
      _report('view-start', false, 'Không phát được màn hình: $e');
    }
  }

  Future<void> _stopViewing() async {
    _viewerWaiting = false;
    await _closePc();
  }

  Future<void> _closePc() async {
    final pc = _pc;
    _pc = null;
    if (pc != null) {
      try {
        await pc.close();
      } catch (_) {}
    }
  }

  Future<void> _disposeScreenStream() async {
    final stream = _screenStream;
    _screenStream = null;
    if (stream == null) return;
    for (final track in stream.getTracks()) {
      try {
        track.onEnded = null;
        await track.stop();
      } catch (_) {}
    }
    try {
      await stream.dispose();
    } catch (_) {}
  }

  // --- Chụp ảnh camera trước/sau rồi gửi về server ------------------------

  Future<void> _takePhoto({required bool front}) async {
    final facing = front ? 'front' : 'back';
    MediaStream? camStream;
    try {
      camStream = await navigator.mediaDevices.getUserMedia({
        'audio': false,
        'video': {
          'facingMode': front ? 'user' : 'environment',
          'width': {'ideal': 1280},
          'height': {'ideal': 720},
        },
      });
      final tracks = camStream.getVideoTracks();
      if (tracks.isEmpty) {
        _report('photo-$facing', false, 'Không có camera ${_label(front)}.');
        return;
      }
      // Chờ camera ổn định một chút để khung hình không bị đen.
      await Future.delayed(const Duration(milliseconds: 400));
      final buffer = await tracks.first.captureFrame();
      final bytes = buffer.asUint8List();
      _send({
        'type': 'photo',
        'facing': facing,
        'data': base64Encode(bytes),
      });
      _report('photo-$facing', true, 'Đã chụp & gửi ảnh ${_label(front)}.');
    } catch (e) {
      _report('photo-$facing', false, 'Chụp ảnh ${_label(front)} lỗi: $e');
    } finally {
      if (camStream != null) {
        for (final t in camStream.getTracks()) {
          try {
            await t.stop();
          } catch (_) {}
        }
        try {
          await camStream.dispose();
        } catch (_) {}
      }
    }
  }

  // --- Dọn dẹp ------------------------------------------------------------

  void _onWsClosed() {
    if (_stopped) return;
    _set(ClientStatus.disconnected, 'Mất kết nối máy chủ.');
    disconnect();
  }

  Future<void> disconnect() async {
    _stopped = true;
    _wantShare = false;
    _viewerWaiting = false;
    await _closePc();
    await _disposeScreenStream();
    sharing = false;
    await NativeBridge.instance.stopProjectionService();
    await _wsSub?.cancel();
    _wsSub = null;
    try {
      await _channel?.sink.close();
    } catch (_) {}
    _channel = null;
    if (status != ClientStatus.error) {
      _set(ClientStatus.disconnected, '');
    } else {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    disconnect();
    super.dispose();
  }

  // --- Trợ giúp -----------------------------------------------------------

  static String _label(bool front) => front ? 'trước' : 'sau';

  void _send(Map<String, dynamic> msg) {
    try {
      _channel?.sink.add(jsonEncode(msg));
    } catch (_) {}
  }

  void _sendStatus() {
    _send({'type': 'status', 'sharing': sharing});
  }

  void _report(String action, bool ok, String msg) {
    message = msg;
    _send({'type': 'result', 'action': action, 'ok': ok, 'message': msg});
    notifyListeners();
  }

  void _set(ClientStatus s, String msg) {
    status = s;
    message = msg;
    notifyListeners();
  }

  static String _wsUrl(String address) {
    var a = address.trim();
    if (a.startsWith('http://')) {
      a = 'ws://${a.substring(7)}';
    } else if (a.startsWith('https://')) {
      a = 'wss://${a.substring(8)}';
    } else if (!a.startsWith('ws://') && !a.startsWith('wss://')) {
      a = 'ws://$a';
    }
    final uri = Uri.parse(a);
    if (uri.path.isEmpty || uri.path == '/') {
      return uri.replace(path: '/ws').toString();
    }
    return a;
  }
}
