import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:path_provider/path_provider.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

enum RemoteClientStatus { disconnected, connecting, connected, error }

/// Runs on a "Client" device: connects to a server, opens the camera/mic, and
/// obeys control commands (photo, video, audio, switch camera, focus, live
/// stream). Captures are saved locally on this device. Every command is guarded
/// so a bad message from the server can't crash the app; failures are reported
/// back over the socket instead.
class RemoteClient extends ChangeNotifier {
  static const _iceConfig = {
    'iceServers': [
      {'urls': 'stun:stun.l.google.com:19302'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  RemoteClientStatus status = RemoteClientStatus.disconnected;
  String message = '';
  String facing = 'back'; // 'back' | 'front'
  bool recording = false;
  bool audioRecording = false;
  int? serverAssignedId;

  WebSocketChannel? _channel;
  StreamSubscription? _wsSub;
  MediaStream? _stream;
  RTCPeerConnection? _pc;
  MediaRecorder? _videoRecorder;
  MediaRecorder? _audioRecorder;
  String? _name;
  bool _stopped = false;

  bool get connected => status == RemoteClientStatus.connected;

  // --- Lifecycle ----------------------------------------------------------

  Future<void> connect(String address, String name) async {
    if (status == RemoteClientStatus.connecting || connected) return;
    _stopped = false;
    _name = name;
    _set(RemoteClientStatus.connecting, 'Đang mở camera…');
    try {
      _stream ??= await _openCamera();
    } catch (e) {
      _set(RemoteClientStatus.error, 'Không mở được camera/mic: $e');
      return;
    }
    _connectWs(address);
  }

  Future<MediaStream> _openCamera() {
    return navigator.mediaDevices.getUserMedia({
      'audio': true,
      'video': {
        'facingMode': facing == 'back' ? 'environment' : 'user',
        'width': {'ideal': 1280},
        'height': {'ideal': 720},
      },
    });
  }

  void _connectWs(String address) {
    final url = _wsUrl(address);
    _set(RemoteClientStatus.connecting, 'Đang kết nối máy chủ…');
    try {
      _channel = WebSocketChannel.connect(Uri.parse(url));
    } catch (e) {
      _set(RemoteClientStatus.error, 'Địa chỉ không hợp lệ: $e');
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
      return; // ignore malformed
    }
    try {
      switch (msg['type']) {
        case 'welcome':
          serverAssignedId = (msg['id'] as num?)?.toInt();
          _set(RemoteClientStatus.connected, 'Đã kết nối, chờ lệnh…');
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

  // --- Command handling ---------------------------------------------------

  Future<void> _onCommand(String? action) async {
    switch (action) {
      case 'photo':
        await _takePhoto();
        break;
      case 'video-start':
        await _startVideo();
        break;
      case 'video-stop':
        await _stopVideo();
        break;
      case 'audio-start':
        await _startAudio();
        break;
      case 'audio-stop':
        await _stopAudio();
        break;
      case 'switch-camera':
        await _switchCamera();
        break;
      case 'focus':
        await _focus();
        break;
      case 'stream-start':
        await _startStream();
        break;
      case 'stream-stop':
        await _stopStream();
        break;
    }
  }

  MediaStreamTrack? get _videoTrack {
    final tracks = _stream?.getVideoTracks();
    return (tracks != null && tracks.isNotEmpty) ? tracks.first : null;
  }

  Future<void> _takePhoto() async {
    final track = _videoTrack;
    if (track == null) return _report('photo', false, 'Không có camera');
    try {
      final buffer = await track.captureFrame();
      final path = await _newFilePath('IMG', 'png');
      await File(path).writeAsBytes(buffer.asUint8List());
      _report('photo', true, 'Đã chụp ảnh');
    } catch (e) {
      _report('photo', false, 'Chụp ảnh lỗi: $e');
    }
  }

  Future<void> _startVideo() async {
    if (recording) return _report('video-start', false, 'Đang quay rồi');
    final track = _videoTrack;
    if (track == null) return _report('video-start', false, 'Không có camera');
    try {
      final path = await _newFilePath('VID', 'mp4');
      _videoRecorder = MediaRecorder();
      await _videoRecorder!.start(
        path,
        videoTrack: track,
        audioChannel: RecorderAudioChannel.INPUT,
      );
      recording = true;
      _report('video-start', true, 'Bắt đầu quay');
      _sendStatus();
    } catch (e) {
      _videoRecorder = null;
      _report('video-start', false, 'Không quay được: $e');
    }
  }

  Future<void> _stopVideo() async {
    if (!recording) return _report('video-stop', false, 'Chưa quay');
    try {
      await _videoRecorder?.stop();
    } catch (e) {
      _report('video-stop', false, 'Dừng quay lỗi: $e');
    } finally {
      _videoRecorder = null;
      recording = false;
      _sendStatus();
    }
    _report('video-stop', true, 'Đã lưu video');
  }

  Future<void> _startAudio() async {
    if (audioRecording) return _report('audio-start', false, 'Đang ghi âm rồi');
    try {
      final path = await _newFilePath('AUD', 'm4a');
      _audioRecorder = MediaRecorder();
      await _audioRecorder!.start(path, audioChannel: RecorderAudioChannel.INPUT);
      audioRecording = true;
      _report('audio-start', true, 'Bắt đầu ghi âm');
      _sendStatus();
    } catch (e) {
      _audioRecorder = null;
      _report('audio-start', false, 'Không ghi âm được: $e');
    }
  }

  Future<void> _stopAudio() async {
    if (!audioRecording) return _report('audio-stop', false, 'Chưa ghi âm');
    try {
      await _audioRecorder?.stop();
    } catch (e) {
      _report('audio-stop', false, 'Dừng ghi âm lỗi: $e');
    } finally {
      _audioRecorder = null;
      audioRecording = false;
      _sendStatus();
    }
    _report('audio-stop', true, 'Đã lưu ghi âm');
  }

  Future<void> _switchCamera() async {
    final track = _videoTrack;
    if (track == null) return _report('switch-camera', false, 'Không có camera');
    try {
      await Helper.switchCamera(track);
      facing = facing == 'back' ? 'front' : 'back';
      _report('switch-camera', true, 'Đã đổi sang camera ${facing == 'back' ? 'sau' : 'trước'}');
      _sendStatus();
    } catch (e) {
      _report('switch-camera', false, 'Đổi camera lỗi: $e');
    }
  }

  Future<void> _focus() async {
    final track = _videoTrack;
    if (track == null) return _report('focus', false, 'Không có camera');
    try {
      // Re-trigger continuous auto-focus, resetting any locked point.
      await Helper.setFocusPoint(track, null);
      await Helper.setFocusMode(track, CameraFocusMode.auto);
      _report('focus', true, 'Đã lấy nét (tự động)');
    } catch (e) {
      _report('focus', false, 'Thiết bị không hỗ trợ lấy nét: $e');
    }
  }

  // --- Live stream (client = WebRTC broadcaster) --------------------------

  Future<void> _startStream() async {
    if (_pc != null) return;
    final stream = _stream;
    if (stream == null) return _report('stream-start', false, 'Chưa có camera');
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
      await _stopStream();
      _report('stream-start', false, 'Không phát được: $e');
    }
  }

  Future<void> _stopStream() async {
    final pc = _pc;
    _pc = null;
    if (pc != null) {
      try {
        await pc.close();
      } catch (_) {}
    }
  }

  // --- Teardown -----------------------------------------------------------

  void _onWsClosed() {
    if (_stopped) return;
    _set(RemoteClientStatus.disconnected, 'Mất kết nối máy chủ.');
    disconnect();
  }

  Future<void> disconnect() async {
    _stopped = true;
    try {
      await _videoRecorder?.stop();
    } catch (_) {}
    try {
      await _audioRecorder?.stop();
    } catch (_) {}
    _videoRecorder = null;
    _audioRecorder = null;
    recording = false;
    audioRecording = false;
    await _stopStream();
    await _wsSub?.cancel();
    _wsSub = null;
    try {
      await _channel?.sink.close();
    } catch (_) {}
    _channel = null;
    for (final track in _stream?.getTracks() ?? const <MediaStreamTrack>[]) {
      try {
        await track.stop();
      } catch (_) {}
    }
    try {
      await _stream?.dispose();
    } catch (_) {}
    _stream = null;
    if (status != RemoteClientStatus.error) {
      _set(RemoteClientStatus.disconnected, '');
    } else {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    disconnect();
    super.dispose();
  }

  // --- Helpers ------------------------------------------------------------

  void _send(Map<String, dynamic> msg) {
    try {
      _channel?.sink.add(jsonEncode(msg));
    } catch (_) {}
  }

  void _sendStatus() {
    _send({
      'type': 'status',
      'recording': recording,
      'audioRecording': audioRecording,
      'facing': facing,
    });
  }

  void _report(String action, bool ok, String msg) {
    message = msg;
    _send({'type': 'result', 'action': action, 'ok': ok, 'message': msg});
    notifyListeners();
  }

  void _set(RemoteClientStatus s, String msg) {
    status = s;
    message = msg;
    notifyListeners();
  }

  Future<String> _newFilePath(String prefix, String ext) async {
    Directory? base;
    try {
      base = await getExternalStorageDirectory();
    } catch (_) {}
    base ??= await getApplicationDocumentsDirectory();
    final dir = Directory('${base.path}/Remote');
    if (!await dir.exists()) await dir.create(recursive: true);
    final ts = DateTime.now().millisecondsSinceEpoch;
    return '${dir.path}/${prefix}_$ts.$ext';
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
