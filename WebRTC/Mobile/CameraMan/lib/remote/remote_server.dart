import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

/// One connected client, from the server's point of view.
class RemoteClientConn {
  RemoteClientConn(this.id, this.name, this.socket);

  final int id;
  String name;
  final WebSocket socket;
  bool recording = false;
  bool audioRecording = false;
  String facing = 'back';
  String lastMessage = '';
}

/// Runs on the "Server" device: a small HTTP + WebSocket server that clients
/// connect to. It tracks connected clients, sends them control commands, and
/// (for one client at a time) acts as a WebRTC *viewer* to show their live
/// camera. All socket / WebRTC work is wrapped so a misbehaving client can
/// never crash the app.
class RemoteServer extends ChangeNotifier {
  static const _iceConfig = {
    'iceServers': [
      {'urls': 'stun:stun.l.google.com:19302'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  HttpServer? _httpServer;
  final Map<int, RemoteClientConn> _clients = {};
  int _nextId = 1;

  String? address; // "192.168.x.y:8080" for the QR / manual entry
  String? error;

  // Live view state.
  final RTCVideoRenderer renderer = RTCVideoRenderer();
  bool _rendererReady = false;
  RTCPeerConnection? _viewPc;
  int? viewingClientId;

  bool get running => _httpServer != null;
  List<RemoteClientConn> get clients => _clients.values.toList(growable: false);
  bool get hasLiveView => viewingClientId != null && _rendererReady;

  // --- Lifecycle ----------------------------------------------------------

  Future<void> start({int port = 8080}) async {
    if (running) return;
    error = null;
    try {
      if (!_rendererReady) {
        await renderer.initialize();
        _rendererReady = true;
      }
      final ip = await _lanIp();
      _httpServer = await HttpServer.bind(InternetAddress.anyIPv4, port, shared: true);
      address = ip == null ? null : '$ip:$port';
      _httpServer!.listen(
        _handleRequest,
        onError: (_) {},
        cancelOnError: false,
      );
    } catch (e) {
      error = 'Không khởi động được server: $e';
      await stop();
    }
    notifyListeners();
  }

  Future<void> stop() async {
    for (final c in _clients.values) {
      try {
        await c.socket.close();
      } catch (_) {}
    }
    _clients.clear();
    await _closeView();
    try {
      await _httpServer?.close(force: true);
    } catch (_) {}
    _httpServer = null;
    address = null;
    notifyListeners();
  }

  @override
  void dispose() {
    // Fire-and-forget async teardown; ChangeNotifier.dispose is synchronous.
    _shutdown();
    super.dispose();
  }

  Future<void> _shutdown() async {
    await stop();
    if (_rendererReady) {
      try {
        await renderer.dispose();
      } catch (_) {}
      _rendererReady = false;
    }
  }

  // --- HTTP / WebSocket ---------------------------------------------------

  Future<void> _handleRequest(HttpRequest req) async {
    try {
      if (req.uri.path == '/ws' && WebSocketTransformer.isUpgradeRequest(req)) {
        final ws = await WebSocketTransformer.upgrade(req);
        _onClientSocket(ws);
        return;
      }
      req.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType = ContentType.html
        ..write(_landingHtml());
      await req.response.close();
    } catch (_) {
      try {
        await req.response.close();
      } catch (_) {}
    }
  }

  void _onClientSocket(WebSocket ws) {
    RemoteClientConn? conn;
    ws.listen(
      (raw) {
        try {
          final msg = jsonDecode(raw as String) as Map<String, dynamic>;
          conn = _onMessage(conn, ws, msg);
        } catch (_) {
          // Ignore malformed frames rather than dropping the connection.
        }
      },
      onDone: () => _onClientGone(conn),
      onError: (_) => _onClientGone(conn),
      cancelOnError: true,
    );
  }

  RemoteClientConn? _onMessage(
    RemoteClientConn? conn,
    WebSocket ws,
    Map<String, dynamic> msg,
  ) {
    switch (msg['type']) {
      case 'hello':
        final id = _nextId++;
        final name = (msg['name'] as String?)?.trim();
        final c = RemoteClientConn(id, name?.isNotEmpty == true ? name! : 'Client $id', ws);
        _clients[id] = c;
        _sendTo(c, {'type': 'welcome', 'id': id});
        notifyListeners();
        return c;
      case 'result':
        if (conn != null) {
          conn.lastMessage = (msg['message'] as String?) ?? '';
          notifyListeners();
        }
        break;
      case 'status':
        if (conn != null) {
          conn.recording = msg['recording'] as bool? ?? conn.recording;
          conn.audioRecording = msg['audioRecording'] as bool? ?? conn.audioRecording;
          conn.facing = (msg['facing'] as String?) ?? conn.facing;
          notifyListeners();
        }
        break;
      case 'offer':
        _onOffer(conn, msg);
        break;
      case 'candidate':
        _onRemoteCandidate(conn, msg);
        break;
    }
    return conn;
  }

  void _onClientGone(RemoteClientConn? conn) {
    if (conn == null) return;
    _clients.remove(conn.id);
    if (viewingClientId == conn.id) _closeView();
    notifyListeners();
  }

  // --- Commands -----------------------------------------------------------

  void sendCommand(int clientId, String action) {
    final c = _clients[clientId];
    if (c == null) return;
    _sendTo(c, {'type': 'command', 'action': action});
  }

  void _sendTo(RemoteClientConn c, Map<String, dynamic> msg) {
    try {
      c.socket.add(jsonEncode(msg));
    } catch (_) {}
  }

  // --- Live view (server = WebRTC viewer) ---------------------------------

  Future<void> startLiveView(int clientId) async {
    if (!_clients.containsKey(clientId)) return;
    await _closeView();
    viewingClientId = clientId;
    notifyListeners();
    // Ask that client to begin offering its camera stream.
    sendCommand(clientId, 'stream-start');
  }

  Future<void> stopLiveView() async {
    final id = viewingClientId;
    if (id != null) sendCommand(id, 'stream-stop');
    await _closeView();
    notifyListeners();
  }

  Future<void> _onOffer(RemoteClientConn? conn, Map<String, dynamic> msg) async {
    if (conn == null || conn.id != viewingClientId) return;
    try {
      final pc = await createPeerConnection(_iceConfig);
      _viewPc = pc;
      pc.onTrack = (event) {
        if (event.streams.isNotEmpty) {
          renderer.srcObject = event.streams.first;
          notifyListeners();
        }
      };
      pc.onIceCandidate = (candidate) {
        _sendTo(conn, {'type': 'candidate', 'candidate': candidate.toMap()});
      };
      await pc.setRemoteDescription(
        RTCSessionDescription(msg['sdp'] as String?, 'offer'),
      );
      final answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      _sendTo(conn, {'type': 'answer', 'sdp': answer.sdp});
    } catch (e) {
      error = 'Xem trực tiếp lỗi: $e';
      await _closeView();
      notifyListeners();
    }
  }

  Future<void> _onRemoteCandidate(
    RemoteClientConn? conn,
    Map<String, dynamic> msg,
  ) async {
    if (_viewPc == null || conn?.id != viewingClientId) return;
    try {
      final c = msg['candidate'] as Map<String, dynamic>?;
      if (c == null) return;
      await _viewPc!.addCandidate(
        RTCIceCandidate(c['candidate'], c['sdpMid'], c['sdpMLineIndex']),
      );
    } catch (_) {}
  }

  Future<void> _closeView() async {
    viewingClientId = null;
    try {
      renderer.srcObject = null;
    } catch (_) {}
    final pc = _viewPc;
    _viewPc = null;
    if (pc != null) {
      try {
        await pc.close();
      } catch (_) {}
    }
  }

  // --- Helpers ------------------------------------------------------------

  Future<String?> _lanIp() async {
    try {
      final interfaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
        includeLinkLocal: false,
      );
      String? fallback;
      for (final ni in interfaces) {
        for (final addr in ni.addresses) {
          if (addr.isLoopback) continue;
          fallback ??= addr.address;
          // Prefer typical private LAN ranges.
          if (addr.address.startsWith('192.168.') ||
              addr.address.startsWith('10.') ||
              addr.address.startsWith('172.')) {
            return addr.address;
          }
        }
      }
      return fallback;
    } catch (_) {
      return null;
    }
  }

  String _landingHtml() =>
      '<!doctype html><meta charset="utf-8"><title>CameraMan</title>'
      '<body style="font-family:sans-serif;background:#0F1115;color:#fff;'
      'text-align:center;padding-top:40px">'
      '<h2>CameraMan Server</h2>'
      '<p>Mở ứng dụng CameraMan trên máy khác, chọn <b>Client</b> và quét mã QR '
      'để được điều khiển.</p></body>';
}
