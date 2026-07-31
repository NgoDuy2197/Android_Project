import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../config_store.dart';
import '../native_bridge.dart';
import '../remote/screen_client.dart';
import '../remote/screen_server.dart';

enum _Role { none, server, client }

const _green = Color(0xFF30A46C);
const _blue = Color(0xFF3B6EF0);
const _red = Color(0xFFE5484D);
const _amber = Color(0xFFF5A623);

/// Màn hình chính: chọn vai trò cho máy này.
/// - **Server**: máy nhận. Hiện QR để client quét, xem màn hình các client.
/// - **Client**: máy chia sẻ màn hình của mình về server.
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _config = ConfigStore();
  final _nameCtrl = TextEditingController();
  final _addressCtrl = TextEditingController();

  _Role _role = _Role.none;
  ScreenServer? _server;
  ScreenClient? _client;
  bool _ready = false;
  String _saveLabel = 'Thư viện ảnh của máy';

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await _config.load();
    var name = _config.clientName;
    if (name.isEmpty) name = await NativeBridge.instance.deviceName();
    _nameCtrl.text = name;
    _addressCtrl.text = _config.serverAddress;
    if (mounted) setState(() => _ready = true);
  }

  @override
  void dispose() {
    _server?.dispose();
    _client?.dispose();
    _nameCtrl.dispose();
    _addressCtrl.dispose();
    super.dispose();
  }

  Future<void> _enterServer() async {
    await _client?.disconnect();
    _client = null;
    final server = ScreenServer();
    server.saveTreeUri = _config.serverSaveTreeUri;
    _server = server;
    setState(() => _role = _Role.server);
    await server.start();
    await _refreshSaveLabel();
  }

  Future<void> _refreshSaveLabel() async {
    final label = await NativeBridge.instance
        .saveLocationLabel(_config.serverSaveTreeUri);
    if (mounted) setState(() => _saveLabel = label);
  }

  Future<void> _pickSaveFolder() async {
    final uri = await NativeBridge.instance.pickFolder();
    if (uri == null || uri.isEmpty) return;
    await _config.setServerSaveTreeUri(uri);
    _server?.saveTreeUri = uri;
    await _refreshSaveLabel();
    _toast('Đã chọn thư mục lưu ảnh.');
  }

  Future<void> _useGalleryDefault() async {
    await _config.clearServerSaveTreeUri();
    _server?.saveTreeUri = '';
    await _refreshSaveLabel();
    _toast('Sẽ lưu ảnh vào thư viện ảnh của máy.');
  }

  Widget _saveLocationCard() {
    final usingFolder = _config.serverSaveTreeUri.isNotEmpty;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.save_alt, size: 18, color: _green),
                const SizedBox(width: 8),
                const Text('Nơi lưu ảnh nhận được',
                    style: TextStyle(fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 6),
            Text(_saveLabel,
                style: const TextStyle(fontSize: 12, color: Colors.white70)),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: _pickSaveFolder,
                  icon: const Icon(Icons.folder_open, size: 18),
                  label: const Text('Chọn thư mục'),
                ),
                if (usingFolder)
                  OutlinedButton.icon(
                    onPressed: _useGalleryDefault,
                    icon: const Icon(Icons.photo_library, size: 18),
                    label: const Text('Dùng thư viện ảnh'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _enterClient() {
    _server?.dispose();
    _server = null;
    _client ??= ScreenClient();
    setState(() => _role = _Role.client);
  }

  Future<void> _leaveRole() async {
    await _server?.stop();
    _server?.dispose();
    _server = null;
    await _client?.disconnect();
    _client = null;
    if (mounted) setState(() => _role = _Role.none);
  }

  void _toast(String msg, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(msg),
        backgroundColor: error ? _red : _green,
      ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ScreenShare'),
        centerTitle: true,
        leading: _role == _Role.none
            ? null
            : IconButton(
                icon: const Icon(Icons.arrow_back), onPressed: _leaveRole),
      ),
      body: SafeArea(
        child: !_ready
            ? const Center(child: CircularProgressIndicator())
            : switch (_role) {
                _Role.none => _roleChooser(),
                _Role.server => _serverView(),
                _Role.client => _clientView(),
              },
      ),
    );
  }

  // --- Chọn vai trò -------------------------------------------------------

  Widget _roleChooser() {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.screen_share, size: 64, color: _green),
          const SizedBox(height: 12),
          const Text('Chọn vai trò cho máy này',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
          const SizedBox(height: 24),
          _roleButton(
            icon: Icons.dns,
            color: _green,
            title: 'Server (máy nhận)',
            subtitle:
                'Hiển thị QR để máy khác quét, xem màn hình các máy đã kết nối.',
            onTap: _enterServer,
          ),
          const SizedBox(height: 14),
          _roleButton(
            icon: Icons.smartphone,
            color: _blue,
            title: 'Client (máy chia sẻ)',
            subtitle: 'Quét QR và chia sẻ toàn bộ màn hình của máy này.',
            onTap: _enterClient,
          ),
        ],
      ),
    );
  }

  Widget _roleButton({
    required IconData icon,
    required Color color,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return Material(
      color: color.withValues(alpha: 0.15),
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(icon, color: color, size: 36),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.bold,
                            color: color)),
                    const SizedBox(height: 4),
                    Text(subtitle,
                        style: const TextStyle(
                            fontSize: 12, color: Colors.white70)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // --- Giao diện Server ---------------------------------------------------

  Widget _serverView() {
    final server = _server;
    if (server == null) return const SizedBox.shrink();
    return ListenableBuilder(
      listenable: server,
      builder: (context, _) {
        if (server.error != null) {
          final err = server.error!;
          server.error = null; // hiện một lần, không lặp khi rebuild
          WidgetsBinding.instance.addPostFrameCallback((_) {
            _toast(err, error: true);
          });
        }
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    if (server.address != null) ...[
                      Container(
                        padding: const EdgeInsets.all(10),
                        color: Colors.white,
                        child: QrImageView(
                          data: server.address!,
                          version: QrVersions.auto,
                          size: 180,
                        ),
                      ),
                      const SizedBox(height: 12),
                      SelectableText(
                        server.address!,
                        style: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.bold),
                      ),
                      const Text(
                        'Trên máy Client: chọn Client → quét QR hoặc nhập địa chỉ này.',
                        style: TextStyle(fontSize: 12, color: Colors.white54),
                        textAlign: TextAlign.center,
                      ),
                    ] else
                      const Padding(
                        padding: EdgeInsets.all(8),
                        child: Text(
                          'Không lấy được địa chỉ IP LAN. Hãy bật Wi-Fi và thử lại.',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: _red),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            _saveLocationCard(),
            const SizedBox(height: 12),
            Row(
              children: [
                const Text('Máy đã kết nối',
                    style:
                        TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
                const Spacer(),
                Text('${server.clients.length}',
                    style: const TextStyle(color: Colors.white54)),
              ],
            ),
            const SizedBox(height: 8),
            if (server.clients.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Text('Chưa có máy nào kết nối.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.white54)),
              )
            else
              ...server.clients.map((c) => Card(
                    child: ListTile(
                      leading: Icon(
                        c.sharing ? Icons.screen_share : Icons.smartphone,
                        color: c.sharing ? _green : Colors.white54,
                      ),
                      title: Text(c.name),
                      subtitle: Text(
                        c.sharing ? '● đang chia sẻ màn hình' : 'đã kết nối',
                        style: const TextStyle(fontSize: 12),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => _ClientControlScreen(
                              server: server, clientId: c.id),
                        ),
                      ),
                    ),
                  )),
          ],
        );
      },
    );
  }

  // --- Giao diện Client ---------------------------------------------------

  Widget _clientView() {
    final client = _client;
    if (client == null) return const SizedBox.shrink();
    return ListenableBuilder(
      listenable: client,
      builder: (context, _) {
        final connecting = client.status == ClientStatus.connecting;
        final connected = client.connected;
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const SizedBox(height: 8),
            Icon(
              client.sharing
                  ? Icons.screen_share
                  : (connected ? Icons.link : Icons.smartphone),
              size: 56,
              color: client.sharing || connected ? _green : Colors.white54,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _nameCtrl,
              enabled: !connected && !connecting,
              decoration: const InputDecoration(
                labelText: 'Tên máy này (hiện ở Server sau khi kết nối)',
                prefixIcon: Icon(Icons.badge_outlined),
                border: OutlineInputBorder(),
              ),
              onChanged: (v) => _config.setClientName(v),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _addressCtrl,
              enabled: !connected && !connecting,
              keyboardType: TextInputType.url,
              autocorrect: false,
              decoration: InputDecoration(
                labelText: 'Địa chỉ máy Server (IP:PORT)',
                hintText: '192.168.1.10:8080',
                prefixIcon: const Icon(Icons.dns_outlined),
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.qr_code_scanner),
                  onPressed: (connected || connecting) ? null : _scanQr,
                ),
              ),
            ),
            const SizedBox(height: 16),
            if (!connected)
              FilledButton.icon(
                onPressed: connecting ? null : _connectClient,
                icon: connecting
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.link),
                label: Text(connecting ? 'Đang kết nối…' : 'Kết nối'),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 15),
                ),
              )
            else ...[
              FilledButton.icon(
                onPressed: () => client.sharing
                    ? client.stopShare()
                    : client.startShare(),
                icon: Icon(client.sharing
                    ? Icons.stop_screen_share
                    : Icons.screen_share),
                label: Text(client.sharing
                    ? 'Dừng chia sẻ màn hình'
                    : 'Bắt đầu chia sẻ màn hình'),
                style: FilledButton.styleFrom(
                  backgroundColor: client.sharing ? _red : _green,
                  padding: const EdgeInsets.symmetric(vertical: 15),
                ),
              ),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                onPressed: () => client.disconnect(),
                icon: const Icon(Icons.link_off),
                label: const Text('Ngắt kết nối'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: _red,
                  padding: const EdgeInsets.symmetric(vertical: 15),
                ),
              ),
              const SizedBox(height: 16),
              Text(
                client.sharing
                    ? 'Đang chia sẻ màn hình về Server.'
                    : 'Đã kết nối. Chờ lệnh hoặc bấm nút để chia sẻ.',
                textAlign: TextAlign.center,
                style: const TextStyle(color: _green),
              ),
            ],
            if (client.message.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  client.message,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: client.status == ClientStatus.error
                        ? _red
                        : Colors.white54,
                    fontSize: 13,
                  ),
                ),
              ),
          ],
        );
      },
    );
  }

  Future<void> _connectClient() async {
    final address = _addressCtrl.text.trim();
    final name = _nameCtrl.text.trim();
    if (address.isEmpty) {
      _toast('Hãy nhập hoặc quét địa chỉ máy Server.', error: true);
      return;
    }
    await _config.setServerAddress(address);
    await _config.setClientName(name);
    await _client?.connect(address, name.isEmpty ? 'Client' : name);
  }

  Future<void> _scanQr() async {
    final code = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const _QrScanScreen()),
    );
    if (code != null && code.trim().isNotEmpty) {
      _addressCtrl.text = code.trim();
      await _connectClient();
    }
  }
}

/// Bảng điều khiển cho một client cụ thể: xem màn hình trực tiếp, kích hoạt
/// chia sẻ, chụp ảnh trước/sau.
class _ClientControlScreen extends StatelessWidget {
  const _ClientControlScreen({required this.server, required this.clientId});

  final ScreenServer server;
  final int clientId;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: server,
      builder: (context, _) {
        RemoteClientConn? conn;
        for (final c in server.clients) {
          if (c.id == clientId) conn = c;
        }
        if (conn == null) {
          // Client đã ngắt kết nối trong lúc đang điều khiển.
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (Navigator.of(context).canPop()) Navigator.of(context).pop();
          });
          return const Scaffold(
            body: Center(child: Text('Máy đã ngắt kết nối.')),
          );
        }
        final c = conn;
        final viewing = server.viewingClientId == clientId;
        return Scaffold(
          appBar: AppBar(title: Text(c.name)),
          body: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              // Khu vực xem màn hình trực tiếp.
              AspectRatio(
                aspectRatio: 9 / 16,
                child: Container(
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: viewing && server.hasLiveView
                      ? RTCVideoView(
                          server.renderer,
                          objectFit: RTCVideoViewObjectFit
                              .RTCVideoViewObjectFitContain,
                        )
                      : Center(
                          child: Text(
                            viewing
                                ? 'Đang chờ hình ảnh…'
                                : 'Chưa xem trực tiếp',
                            style: const TextStyle(color: Colors.white38),
                          ),
                        ),
                ),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: () => viewing
                    ? server.stopLiveView()
                    : server.startLiveView(clientId),
                icon: Icon(viewing ? Icons.stop : Icons.live_tv),
                label: Text(viewing ? 'Dừng xem' : 'Xem màn hình'),
              ),
              const SizedBox(height: 16),
              GridView.count(
                crossAxisCount: 2,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                mainAxisSpacing: 10,
                crossAxisSpacing: 10,
                childAspectRatio: 2.4,
                children: [
                  _cmd(
                    c.sharing ? Icons.stop_screen_share : Icons.screen_share,
                    c.sharing ? 'Dừng chia sẻ' : 'Kích hoạt chia sẻ',
                    c.sharing ? _red : _green,
                    () => c.sharing
                        ? server.requestShareStop(clientId)
                        : server.requestShareStart(clientId),
                  ),
                  _cmd(Icons.camera_front, 'Chụp ảnh trước', _blue,
                      () => server.requestPhoto(clientId, front: true)),
                  _cmd(Icons.camera_rear, 'Chụp ảnh sau', _amber,
                      () => server.requestPhoto(clientId, front: false)),
                  _cmd(Icons.image, 'Xem ảnh đã nhận', const Color(0xFF8E4EC6),
                      () => _openPhoto(context, c)),
                ],
              ),
              const SizedBox(height: 16),
              if (c.lastPhoto != null)
                _PhotoThumb(conn: c, onTap: () => _openPhoto(context, c)),
              if (c.lastMessage.isNotEmpty)
                Container(
                  margin: const EdgeInsets.only(top: 12),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.white10,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.info_outline,
                          size: 18, color: Colors.white54),
                      const SizedBox(width: 8),
                      Expanded(
                          child: Text(c.lastMessage,
                              style: const TextStyle(fontSize: 13))),
                    ],
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  void _openPhoto(BuildContext context, RemoteClientConn c) {
    if (c.lastPhoto == null) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('Chưa có ảnh nào.')));
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => _PhotoViewScreen(conn: c),
      ),
    );
  }

  Widget _cmd(IconData icon, String label, Color color, VoidCallback onTap) {
    return Material(
      color: color,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: Colors.white, size: 22),
            const SizedBox(width: 8),
            Flexible(
              child: Text(label,
                  style: const TextStyle(
                      color: Colors.white, fontWeight: FontWeight.w600),
                  overflow: TextOverflow.ellipsis),
            ),
          ],
        ),
      ),
    );
  }
}

/// Ảnh thu nhỏ hiển thị trong bảng điều khiển.
class _PhotoThumb extends StatelessWidget {
  const _PhotoThumb({required this.conn, required this.onTap});

  final RemoteClientConn conn;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: Image.memory(
          conn.lastPhoto!,
          height: 160,
          width: double.infinity,
          fit: BoxFit.cover,
          gaplessPlayback: true,
        ),
      ),
    );
  }
}

/// Xem ảnh nhận được ở chế độ toàn màn hình, có thể phóng to.
class _PhotoViewScreen extends StatelessWidget {
  const _PhotoViewScreen({required this.conn});

  final RemoteClientConn conn;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Ảnh ${conn.lastPhotoFacing == 'front' ? 'trước' : 'sau'}'
            ' — ${conn.name}'),
      ),
      backgroundColor: Colors.black,
      body: Center(
        child: InteractiveViewer(
          maxScale: 5,
          child: Image.memory(conn.lastPhoto!, gaplessPlayback: true),
        ),
      ),
    );
  }
}

/// Máy quét QR toàn màn hình để đọc địa chỉ máy Server.
class _QrScanScreen extends StatefulWidget {
  const _QrScanScreen();

  @override
  State<_QrScanScreen> createState() => _QrScanScreenState();
}

class _QrScanScreenState extends State<_QrScanScreen> {
  final _controller = MobileScannerController();
  bool _handled = false;

  void _onDetect(BarcodeCapture capture) {
    if (_handled) return;
    final value =
        capture.barcodes.isNotEmpty ? capture.barcodes.first.rawValue : null;
    if (value == null || value.trim().isEmpty) return;
    _handled = true;
    Navigator.of(context).pop(value);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Quét QR máy Server')),
      body: Stack(
        alignment: Alignment.center,
        children: [
          MobileScanner(controller: _controller, onDetect: _onDetect),
          Container(
            width: 240,
            height: 240,
            decoration: BoxDecoration(
              border: Border.all(color: Colors.white, width: 3),
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ],
      ),
    );
  }
}
