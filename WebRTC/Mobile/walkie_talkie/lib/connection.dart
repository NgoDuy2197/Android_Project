import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

/// Fixed port for the in-app WebSocket server (the Speaker hosts it).
const int kWalkiePort = 8787;

/// Peer-to-peer LAN link between the two phones.
///
/// The Speaker calls [host] (runs a WebSocket server, shows its IP/QR); the
/// Remoter calls [connect]. Once linked, both use the same API. All messages
/// are JSON: soundboard control, talk state, and relayed WebRTC signaling for
/// the live audio call.
class WalkieConnection {
  HttpServer? _server;
  WebSocket? _socket;
  StreamSubscription? _sub;

  // Callbacks
  void Function(bool connected)? onConnectionChanged;
  void Function(String id)? onPlay;
  void Function(String id, String ext, Uint8List bytes)? onSound;
  void Function(bool on)? onRemoteTalk;
  void Function(Map<String, dynamic> signal)? onSignal; // WebRTC signaling
  void Function(String name)? onMissingSoundRequest; // speaker asks for a file id
  void Function(List<String> ids)? onHave; // speaker lists cached sound ids
  void Function()? onClearAudio; // remoter asks speaker to wipe audio cache
  void Function(String message)? onError;

  bool get isConnected => _socket != null;

  // --- Speaker: host a server -----------------------------------------------
  Future<void> host() async {
    await close();
    _server = await HttpServer.bind(InternetAddress.anyIPv4, kWalkiePort);
    _server!.listen((HttpRequest req) async {
      if (WebSocketTransformer.isUpgradeRequest(req)) {
        final ws = await WebSocketTransformer.upgrade(req);
        // Only one active peer; replace any previous.
        _bind(ws);
      } else {
        req.response.statusCode = HttpStatus.badRequest;
        await req.response.close();
      }
    }, onError: (e) => onError?.call('$e'));
  }

  // --- Remoter: connect to the speaker --------------------------------------
  Future<void> connect(String address) async {
    await close();
    final url = _normalize(address);
    final ws = await WebSocket.connect(url).timeout(const Duration(seconds: 8));
    _bind(ws);
  }

  void _bind(WebSocket ws) {
    _sub?.cancel();
    _socket = ws;
    onConnectionChanged?.call(true);
    _sub = ws.listen(
      _onData,
      onDone: () {
        if (identical(_socket, ws)) {
          _socket = null;
          onConnectionChanged?.call(false);
        }
      },
      onError: (_) {
        if (identical(_socket, ws)) {
          _socket = null;
          onConnectionChanged?.call(false);
        }
      },
      cancelOnError: true,
    );
  }

  void _onData(dynamic data) {
    if (data is String) _onText(data);
  }

  void _onText(String raw) {
    Map<String, dynamic> m;
    try {
      m = jsonDecode(raw) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    switch (m['t']) {
      case 'play':
        onPlay?.call(m['id'] as String);
        break;
      case 'sound':
        onSound?.call(
          m['id'] as String,
          (m['ext'] ?? 'm4a') as String,
          base64Decode(m['data'] as String),
        );
        break;
      case 'talk':
        onRemoteTalk?.call(m['on'] == true);
        break;
      case 'need-sound':
        onMissingSoundRequest?.call(m['id'] as String);
        break;
      case 'sig':
        onSignal?.call((m['data'] as Map).cast<String, dynamic>());
        break;
      case 'have':
        onHave?.call(
            ((m['ids'] ?? []) as List).map((e) => e.toString()).toList());
        break;
      case 'clear-audio':
        onClearAudio?.call();
        break;
    }
  }

  // --- Send helpers ----------------------------------------------------------
  void _sendJson(Map<String, dynamic> m) => _socket?.add(jsonEncode(m));

  void sendPlay(String id) => _sendJson({'t': 'play', 'id': id});

  void sendSound(String id, String ext, Uint8List bytes) =>
      _sendJson({'t': 'sound', 'id': id, 'ext': ext, 'data': base64Encode(bytes)});

  void sendTalk(bool on) => _sendJson({'t': 'talk', 'on': on});

  void requestSound(String id) => _sendJson({'t': 'need-sound', 'id': id});

  /// Relay a WebRTC signaling payload (offer/answer/ice/ready) to the peer.
  void sendSignal(Map<String, dynamic> data) =>
      _sendJson({'t': 'sig', 'data': data});

  /// Speaker -> remoter: which sound ids are already cached (skip re-sending).
  void sendHave(List<String> ids) => _sendJson({'t': 'have', 'ids': ids});

  /// Remoter -> speaker: wipe the cached audio.
  void sendClearAudio() => _sendJson({'t': 'clear-audio'});

  Future<void> close() async {
    await _sub?.cancel();
    _sub = null;
    try {
      await _socket?.close();
    } catch (_) {}
    _socket = null;
    try {
      await _server?.close(force: true);
    } catch (_) {}
    _server = null;
  }

  /// Non-internal IPv4 addresses of this device (to display for the remoter).
  static Future<List<String>> localIps() async {
    final ips = <String>[];
    try {
      for (final ni in await NetworkInterface.list(
          type: InternetAddressType.IPv4, includeLoopback: false)) {
        for (final addr in ni.addresses) {
          if (!addr.isLoopback) ips.add(addr.address);
        }
      }
    } catch (_) {}
    return ips;
  }

  static String _normalize(String address) {
    var a = address.trim();
    if (a.startsWith('ws://') || a.startsWith('wss://')) return a;
    if (!a.contains(':')) a = '$a:$kWalkiePort';
    return 'ws://$a';
  }
}
