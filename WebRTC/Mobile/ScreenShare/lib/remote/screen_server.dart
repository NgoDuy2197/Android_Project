import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import '../native_bridge.dart';

/// Một client đang kết nối, nhìn từ phía server.
///
/// Chỉ những client đã gửi `hello` (tức đã kết nối thật) mới được tạo đối tượng
/// này và mới hiện trong danh sách của server — đúng yêu cầu "chỉ hiển thị ở
/// server nếu đã kết nối".
class RemoteClientConn {
  RemoteClientConn(this.id, this.name, this.socket);

  final int id;
  String name;
  final WebSocket socket;

  /// Client có đang chia sẻ màn hình hay không (theo báo cáo của client).
  bool sharing = false;

  /// Thông báo kết quả gần nhất do client gửi lên (vd "Đã chụp ảnh").
  String lastMessage = '';

  /// Ảnh chụp gần nhất client gửi về (JPEG/PNG bytes) và camera đã dùng.
  Uint8List? lastPhoto;
  String lastPhotoFacing = '';
}

/// Chạy trên máy "Server": một HTTP + WebSocket server nhỏ để client kết nối.
/// Nó theo dõi các client, gửi lệnh điều khiển, nhận ảnh chụp, và (cho một
/// client tại một thời điểm) đóng vai *người xem* WebRTC để hiển thị màn hình
/// đang chia sẻ.
///
/// Mọi thao tác socket/WebRTC đều được bọc try/catch để một client lỗi không
/// bao giờ làm app server crash.
class ScreenServer extends ChangeNotifier {
  static const _iceConfig = {
    'iceServers': [
      {'urls': 'stun:stun.l.google.com:19302'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  HttpServer? _httpServer;
  final Map<int, RemoteClientConn> _clients = {};
  int _nextId = 1;

  String? address; // "192.168.x.y:8080" cho mã QR / nhập tay
  String? error;

  /// Thư mục lưu ảnh (SAF tree uri). Rỗng = lưu vào thư viện ảnh hệ thống.
  /// Do UI đặt từ [ConfigStore].
  String saveTreeUri = '';

  // Trạng thái xem trực tiếp.
  final RTCVideoRenderer renderer = RTCVideoRenderer();
  bool _rendererReady = false;
  RTCPeerConnection? _viewPc;
  int? viewingClientId;

  bool get running => _httpServer != null;
  List<RemoteClientConn> get clients =>
      _clients.values.toList(growable: false);
  bool get hasLiveView => viewingClientId != null && _rendererReady;

  // --- Vòng đời -----------------------------------------------------------

  Future<void> start({int port = 8080}) async {
    if (running) return;
    error = null;
    try {
      if (!_rendererReady) {
        await renderer.initialize();
        _rendererReady = true;
      }
      final ip = await _lanIp();
      _httpServer =
          await HttpServer.bind(InternetAddress.anyIPv4, port, shared: true);
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
    // ChangeNotifier.dispose là đồng bộ; dọn dẹp async kiểu fire-and-forget.
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
      if (req.uri.path == '/ws' &&
          WebSocketTransformer.isUpgradeRequest(req)) {
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
          // Bỏ qua khung tin lỗi thay vì đóng kết nối.
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
        final c = RemoteClientConn(
            id, name?.isNotEmpty == true ? name! : 'Client $id', ws);
        _clients[id] = c;
        _sendTo(c, {'type': 'welcome', 'id': id});
        notifyListeners();
        return c;
      case 'status':
        if (conn != null) {
          conn.sharing = msg['sharing'] as bool? ?? conn.sharing;
          notifyListeners();
        }
        break;
      case 'result':
        if (conn != null) {
          conn.lastMessage = (msg['message'] as String?) ?? '';
          notifyListeners();
        }
        break;
      case 'photo':
        if (conn != null) {
          _onPhoto(conn, msg);
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

  void _onPhoto(RemoteClientConn conn, Map<String, dynamic> msg) {
    try {
      final b64 = msg['data'] as String?;
      if (b64 == null || b64.isEmpty) return;
      final bytes = base64Decode(b64);
      conn.lastPhoto = bytes;
      conn.lastPhotoFacing = (msg['facing'] as String?) ?? '';
      conn.lastMessage = 'Đã nhận ảnh (${_facingLabel(conn.lastPhotoFacing)})';
      notifyListeners();
      // Tự lưu ảnh nhận được vào thư viện / thư mục đã cấu hình.
      _savePhoto(conn, bytes);
    } catch (_) {
      // Ảnh hỏng — bỏ qua, không crash.
    }
  }

  Future<void> _savePhoto(RemoteClientConn conn, Uint8List bytes) async {
    final safeName = _sanitize(conn.name);
    final ts = DateTime.now();
    final name = 'SS_${safeName}_${_facingLabel(conn.lastPhotoFacing)}_'
        '${ts.year}${_two(ts.month)}${_two(ts.day)}_'
        '${_two(ts.hour)}${_two(ts.minute)}${_two(ts.second)}';
    final res = await NativeBridge.instance
        .saveImage(bytes, treeUri: saveTreeUri, suggestedName: name);
    conn.lastMessage = res.ok
        ? 'Đã lưu ảnh: ${res.location}'
        : 'Nhận được ảnh nhưng lưu lỗi: ${res.location}';
    notifyListeners();
  }

  static String _sanitize(String s) {
    final cleaned = s.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_');
    return cleaned.isEmpty ? 'client' : cleaned;
  }

  static String _two(int n) => n.toString().padLeft(2, '0');

  // --- Lệnh ----------------------------------------------------------------

  void sendCommand(int clientId, String action) {
    final c = _clients[clientId];
    if (c == null) return;
    _sendTo(c, {'type': 'command', 'action': action});
  }

  /// Yêu cầu client bắt đầu chia sẻ màn hình (client sẽ hiện hộp thoại xin phép
  /// của hệ thống — đây là yêu cầu bảo mật của Android, không thể bỏ qua).
  void requestShareStart(int clientId) => sendCommand(clientId, 'share-start');
  void requestShareStop(int clientId) => sendCommand(clientId, 'share-stop');

  /// Yêu cầu client chụp ảnh camera trước/sau rồi gửi về server.
  void requestPhoto(int clientId, {required bool front}) =>
      sendCommand(clientId, front ? 'photo-front' : 'photo-back');

  void _sendTo(RemoteClientConn c, Map<String, dynamic> msg) {
    try {
      c.socket.add(jsonEncode(msg));
    } catch (_) {}
  }

  // --- Xem trực tiếp (server = người xem WebRTC) --------------------------

  Future<void> startLiveView(int clientId) async {
    if (!_clients.containsKey(clientId)) return;
    await _closeView();
    viewingClientId = clientId;
    notifyListeners();
    // Yêu cầu client bắt đầu tạo offer chia sẻ màn hình cho mình.
    sendCommand(clientId, 'view-start');
  }

  Future<void> stopLiveView() async {
    final id = viewingClientId;
    if (id != null) sendCommand(id, 'view-stop');
    await _closeView();
    notifyListeners();
  }

  Future<void> _onOffer(
      RemoteClientConn? conn, Map<String, dynamic> msg) async {
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

  // --- Trợ giúp -----------------------------------------------------------

  static String _facingLabel(String facing) =>
      facing == 'front' ? 'trước' : 'sau';

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
          // Ưu tiên dải LAN riêng thường gặp.
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
      '<!doctype html><meta charset="utf-8"><title>ScreenShare</title>'
      '<body style="font-family:sans-serif;background:#0F1115;color:#fff;'
      'text-align:center;padding-top:40px">'
      '<h2>ScreenShare Server</h2>'
      '<p>Mở app ScreenShare trên máy khác, chọn <b>Client</b> và quét mã QR '
      'để chia sẻ màn hình về máy này.</p></body>';
}
