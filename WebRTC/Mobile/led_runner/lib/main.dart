import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LedApp());
}

class LedApp extends StatelessWidget {
  const LedApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'LED Runner',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: const Color(0xFF101216),
      ),
      home: const ConfigScreen(),
    );
  }
}

enum ScrollDir { horizontal, verticalUp }

/// Extra visual styles for the scrolling text. Rendered in [_LedPainter].
enum FxStyle { none, pixel, wave, glitch, shake, neon }

const _fxNames = <FxStyle, String>{
  FxStyle.none: 'Không',
  FxStyle.pixel: 'Pixel (ô vuông)',
  FxStyle.wave: 'Sóng nhún',
  FxStyle.glitch: 'Glitch (nhiễu)',
  FxStyle.shake: 'Rung lắc',
  FxStyle.neon: 'Neon nhấp nháy',
};

/// All display settings, persisted as JSON.
class LedConfig {
  String text;
  int textColor;
  int bgColor;
  double speed; // px per second
  double fontSize;
  bool bold;
  ScrollDir dir;
  bool blink;
  double blinkHz;
  bool glow;
  bool rainbow;
  bool uppercase;
  bool mirror;
  String? fontFamily;
  FxStyle fx;

  LedConfig({
    this.text = 'XIN CHÀO',
    this.textColor = 0xFFFF3B30,
    this.bgColor = 0xFF000000,
    this.speed = 160,
    this.fontSize = 160,
    this.bold = true,
    this.dir = ScrollDir.horizontal,
    this.blink = false,
    this.blinkHz = 2,
    this.glow = true,
    this.rainbow = false,
    this.uppercase = false,
    this.mirror = false,
    this.fontFamily,
    this.fx = FxStyle.none,
  });

  Map<String, dynamic> toJson() => {
        'text': text,
        'textColor': textColor,
        'bgColor': bgColor,
        'speed': speed,
        'fontSize': fontSize,
        'bold': bold,
        'dir': dir.index,
        'blink': blink,
        'blinkHz': blinkHz,
        'glow': glow,
        'rainbow': rainbow,
        'uppercase': uppercase,
        'mirror': mirror,
        'fontFamily': fontFamily,
        'fx': fx.index,
      };

  static LedConfig fromJson(Map<String, dynamic> j) => LedConfig(
        text: j['text'] ?? 'XIN CHÀO',
        textColor: j['textColor'] ?? 0xFFFF3B30,
        bgColor: j['bgColor'] ?? 0xFF000000,
        speed: (j['speed'] ?? 160).toDouble(),
        fontSize: (j['fontSize'] ?? 160).toDouble(),
        bold: j['bold'] ?? true,
        dir: ScrollDir.values[(j['dir'] ?? 0)],
        blink: j['blink'] ?? false,
        blinkHz: (j['blinkHz'] ?? 2).toDouble(),
        glow: j['glow'] ?? true,
        rainbow: j['rainbow'] ?? false,
        uppercase: j['uppercase'] ?? false,
        mirror: j['mirror'] ?? false,
        fontFamily: j['fontFamily'],
        fx: FxStyle.values[(j['fx'] ?? 0).clamp(0, FxStyle.values.length - 1)],
      );
}

const _palette = [
  0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE,
  0xFF30B0C7, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55,
  0xFFFFFFFF, 0xFF000000, 0xFFFF6AD5, 0xFF00FF00, 0xFF00E5FF,
];
const _fonts = <String, String?>{
  'Mặc định': null,
  'Monospace': 'monospace',
  'Serif': 'serif',
};

class ConfigScreen extends StatefulWidget {
  const ConfigScreen({super.key});
  @override
  State<ConfigScreen> createState() => _ConfigScreenState();
}

class _ConfigScreenState extends State<ConfigScreen> {
  static const _key = 'led_config';
  LedConfig _cfg = LedConfig();
  final _textCtrl = TextEditingController();
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final s = prefs.getString(_key);
    if (s != null) {
      try {
        _cfg = LedConfig.fromJson(jsonDecode(s));
      } catch (_) {}
    }
    _textCtrl.text = _cfg.text;
    setState(() => _ready = true);
  }

  Future<void> _save() async {
    _cfg.text = _textCtrl.text;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, jsonEncode(_cfg.toJson()));
  }

  void _run() {
    _save();
    Navigator.push(context,
        MaterialPageRoute(builder: (_) => DisplayScreen(cfg: _cfg)));
  }

  @override
  void dispose() {
    _textCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_ready) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return Scaffold(
      appBar: AppBar(title: const Text('LED Runner')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _run,
        icon: const Icon(Icons.play_arrow),
        label: const Text('CHẠY'),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 90),
        children: [
          TextField(
            controller: _textCtrl,
            maxLines: 2,
            decoration: const InputDecoration(
              labelText: 'Nội dung chạy',
              border: OutlineInputBorder(),
            ),
            onChanged: (v) => setState(() => _cfg.text = v),
          ),
          const SizedBox(height: 16),
          _colorRow('Màu chữ', _cfg.textColor,
              (c) => setState(() => _cfg.textColor = c)),
          const SizedBox(height: 10),
          _colorRow('Màu nền', _cfg.bgColor,
              (c) => setState(() => _cfg.bgColor = c)),
          const Divider(height: 28),
          _dropdownDir(),
          const SizedBox(height: 8),
          _fontDropdown(),
          const SizedBox(height: 8),
          _fxDropdown(),
          const SizedBox(height: 8),
          _slider('Tốc độ', _cfg.speed, 40, 600,
              (v) => setState(() => _cfg.speed = v)),
          _slider('Cỡ chữ', _cfg.fontSize, 40, 320,
              (v) => setState(() => _cfg.fontSize = v)),
          _switch('Chữ đậm', _cfg.bold, (v) => setState(() => _cfg.bold = v)),
          _switch('VIẾT HOA', _cfg.uppercase,
              (v) => setState(() => _cfg.uppercase = v)),
          _switch('Phát sáng (kiểu LED)', _cfg.glow,
              (v) => setState(() => _cfg.glow = v)),
          _switch('Đổi màu cầu vồng', _cfg.rainbow,
              (v) => setState(() => _cfg.rainbow = v)),
          _switch('Lật gương (soi kính)', _cfg.mirror,
              (v) => setState(() => _cfg.mirror = v)),
          _switch(
              'Nhấp nháy', _cfg.blink, (v) => setState(() => _cfg.blink = v)),
          if (_cfg.blink)
            _slider('Tần suất nháy (lần/giây)', _cfg.blinkHz, 0.5, 8,
                (v) => setState(() => _cfg.blinkHz = v)),
          const SizedBox(height: 12),
          _previewBox(),
        ],
      ),
    );
  }

  Widget _previewBox() {
    final t = (_cfg.uppercase ? _cfg.text.toUpperCase() : _cfg.text);
    return Container(
      height: 90,
      decoration: BoxDecoration(
        color: Color(_cfg.bgColor),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.white24),
      ),
      alignment: Alignment.center,
      child: Text(
        t.isEmpty ? ' ' : t,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: Color(_cfg.textColor),
          fontSize: 34,
          fontFamily: _cfg.fontFamily,
          fontWeight: _cfg.bold ? FontWeight.w900 : FontWeight.normal,
          shadows: _cfg.glow
              ? [
                  Shadow(color: Color(_cfg.textColor), blurRadius: 16),
                  Shadow(color: Color(_cfg.textColor), blurRadius: 30),
                ]
              : null,
        ),
      ),
    );
  }

  Widget _colorRow(String label, int current, ValueChanged<int> onPick) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label,
            style: const TextStyle(fontSize: 13, color: Colors.white70)),
        const SizedBox(height: 6),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final c in _palette)
              GestureDetector(
                onTap: () => onPick(c),
                child: Container(
                  width: 34,
                  height: 34,
                  decoration: BoxDecoration(
                    color: Color(c),
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: current == c ? Colors.white : Colors.white24,
                      width: current == c ? 3 : 1,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }

  Widget _dropdownDir() {
    return Row(
      children: [
        const Text('Hướng chạy'),
        const Spacer(),
        DropdownButton<ScrollDir>(
          value: _cfg.dir,
          onChanged: (v) => setState(() => _cfg.dir = v!),
          items: const [
            DropdownMenuItem(
                value: ScrollDir.horizontal, child: Text('Ngang →')),
            DropdownMenuItem(
                value: ScrollDir.verticalUp, child: Text('Dọc lên ↑')),
          ],
        ),
      ],
    );
  }

  Widget _fontDropdown() {
    return Row(
      children: [
        const Text('Font chữ'),
        const Spacer(),
        DropdownButton<String?>(
          value: _cfg.fontFamily,
          onChanged: (v) => setState(() => _cfg.fontFamily = v),
          items: [
            for (final e in _fonts.entries)
              DropdownMenuItem(value: e.value, child: Text(e.key)),
          ],
        ),
      ],
    );
  }

  Widget _fxDropdown() {
    return Row(
      children: [
        const Text('Hiệu ứng'),
        const Spacer(),
        DropdownButton<FxStyle>(
          value: _cfg.fx,
          onChanged: (v) => setState(() => _cfg.fx = v!),
          items: [
            for (final e in _fxNames.entries)
              DropdownMenuItem(value: e.key, child: Text(e.value)),
          ],
        ),
      ],
    );
  }

  Widget _slider(String label, double v, double min, double max,
      ValueChanged<double> onCh) {
    return Row(
      children: [
        SizedBox(width: 160, child: Text('$label: ${v.round()}')),
        Expanded(
          child: Slider(
              value: v.clamp(min, max), min: min, max: max, onChanged: onCh),
        ),
      ],
    );
  }

  Widget _switch(String label, bool v, ValueChanged<bool> onCh) {
    return SwitchListTile(
      contentPadding: EdgeInsets.zero,
      title: Text(label),
      value: v,
      onChanged: onCh,
    );
  }
}

/// Fullscreen scrolling display.
class DisplayScreen extends StatefulWidget {
  final LedConfig cfg;
  const DisplayScreen({super.key, required this.cfg});
  @override
  State<DisplayScreen> createState() => _DisplayScreenState();
}

class _DisplayScreenState extends State<DisplayScreen>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  // Time in seconds, as a Listenable so only the CustomPaint repaints each
  // frame — the widget tree is NOT rebuilt, and the text is laid out only once.
  final ValueNotifier<double> _time = ValueNotifier(0);

  late final TextPainter _tp; // laid out once (fixes emoji jank)
  double _textW = 0;
  double _textH = 0;
  ui.Image? _pixelImage; // low-res raster for the "pixel" effect

  @override
  void initState() {
    super.initState();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    SystemChrome.setPreferredOrientations(DeviceOrientation.values);
    WakelockPlus.enable();

    final cfg = widget.cfg;
    final text = cfg.uppercase ? cfg.text.toUpperCase() : cfg.text;
    final display = text.isEmpty ? ' ' : text;

    // Base painter is WHITE; the real colour (and rainbow / glitch tints) is
    // applied cheaply per-frame with a colour filter, so we never re-layout —
    // laying out emoji every frame was the source of the stutter.
    final baseStyle = TextStyle(
      color: const Color(0xFFFFFFFF),
      fontSize: cfg.fontSize,
      fontFamily: cfg.fontFamily,
      fontWeight: cfg.bold ? FontWeight.w900 : FontWeight.normal,
      height: 1.1,
      shadows: cfg.glow
          ? const [
              Shadow(color: Color(0xFFFFFFFF), blurRadius: 18),
              Shadow(color: Color(0xFFFFFFFF), blurRadius: 36),
            ]
          : null,
    );
    _tp = TextPainter(
      text: TextSpan(text: display, style: baseStyle),
      textDirection: TextDirection.ltr,
      maxLines: 1,
    )..layout();
    _textW = _tp.width;
    _textH = _tp.height;

    if (cfg.fx == FxStyle.pixel) {
      _buildPixelImage(display, baseStyle);
    }

    _ticker = createTicker((d) => _time.value = d.inMicroseconds / 1e6);
    _ticker.start();
  }

  Future<void> _buildPixelImage(String text, TextStyle baseStyle) async {
    try {
      const scale = 0.18; // lower = chunkier pixels
      // Render without glow so the blocks stay crisp.
      final plain = TextPainter(
        text: TextSpan(
          text: text,
          style: baseStyle.copyWith(shadows: const []),
        ),
        textDirection: TextDirection.ltr,
        maxLines: 1,
      )..layout();
      final w = math.max(1, (plain.width * scale).round());
      final h = math.max(1, (plain.height * scale).round());
      final recorder = ui.PictureRecorder();
      final canvas = Canvas(recorder);
      canvas.scale(scale);
      plain.paint(canvas, Offset.zero);
      final img = await recorder.endRecording().toImage(w, h);
      if (mounted) setState(() => _pixelImage = img);
    } catch (_) {
      // If rasterisation fails, the painter falls back to normal text.
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    _time.dispose();
    _pixelImage?.dispose();
    WakelockPlus.disable();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    SystemChrome.setPreferredOrientations(DeviceOrientation.values);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cfg = widget.cfg;
    return Scaffold(
      backgroundColor: Color(cfg.bgColor),
      body: GestureDetector(
        onTap: () => Navigator.pop(context),
        child: SizedBox.expand(
          child: AnimatedBuilder(
            animation: _time,
            builder: (_, _) => CustomPaint(
              painter: _LedPainter(
                cfg: cfg,
                tp: _tp,
                textW: _textW,
                textH: _textH,
                pixelImage: _pixelImage,
                t: _time.value,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Paints the scrolling text each frame from a pre-laid-out [TextPainter],
/// applying the selected [FxStyle]. Because layout is cached, painting stays
/// smooth even with emoji.
class _LedPainter extends CustomPainter {
  _LedPainter({
    required this.cfg,
    required this.tp,
    required this.textW,
    required this.textH,
    required this.pixelImage,
    required this.t,
  });

  final LedConfig cfg;
  final TextPainter tp;
  final double textW;
  final double textH;
  final ui.Image? pixelImage;
  final double t;

  @override
  void paint(Canvas canvas, Size size) {
    // Blink.
    if (cfg.blink) {
      final period = 1000 / cfg.blinkHz;
      if (((t * 1000) / period).floor() % 2 != 0) return;
    }

    // Colour (static / rainbow) and neon pulse alpha.
    Color color = Color(cfg.textColor);
    if (cfg.rainbow) {
      color = HSVColor.fromAHSV(1, (t * 90) % 360, 1, 1).toColor();
    }
    double alpha = 1;
    if (cfg.fx == FxStyle.neon) {
      alpha = 0.5 + 0.5 * (0.5 + 0.5 * math.sin(t * 8));
    }

    // Scroll position.
    double x, y;
    if (cfg.dir == ScrollDir.horizontal) {
      final travel = size.width + textW;
      x = size.width - ((t * cfg.speed) % travel);
      y = (size.height - textH) / 2;
    } else {
      final travel = size.height + textH;
      y = size.height - ((t * cfg.speed) % travel);
      x = (size.width - textW) / 2;
    }

    // Per-effect offset.
    double dx = 0, dy = 0;
    switch (cfg.fx) {
      case FxStyle.wave:
        dy += math.sin(t * 3 + x * 0.02) * (textH * 0.18);
        break;
      case FxStyle.shake:
        dx += (math.sin(t * 57) + math.sin(t * 91)) * (textH * 0.03);
        dy += (math.sin(t * 47) + math.sin(t * 73)) * (textH * 0.03);
        break;
      default:
        break;
    }

    canvas.save();
    if (cfg.mirror) {
      canvas.translate(size.width, 0);
      canvas.scale(-1, 1);
    }
    final pos = Offset(x + dx, y + dy);

    if (cfg.fx == FxStyle.glitch) {
      final g = (math.sin(t * 40).abs() > 0.7) ? 10.0 : 3.0;
      _blit(canvas, pos + Offset(-g, 0), const Color(0xFFFF0040).withAlpha(150));
      _blit(canvas, pos + Offset(g, 0), const Color(0xFF00FFFF).withAlpha(150));
    }
    _blit(canvas, pos, color.withAlpha((alpha * 255).round().clamp(0, 255)));
    canvas.restore();
  }

  /// Draws the cached text at [offset] recoloured to [color] without any
  /// re-layout. Uses the pixel raster when the pixel effect is active.
  void _blit(Canvas canvas, Offset offset, Color color) {
    final img = pixelImage;
    if (cfg.fx == FxStyle.pixel && img != null) {
      final paint = Paint()
        ..filterQuality = FilterQuality.none
        ..colorFilter = ColorFilter.mode(color, BlendMode.srcIn);
      canvas.drawImageRect(
        img,
        Rect.fromLTWH(0, 0, img.width.toDouble(), img.height.toDouble()),
        Rect.fromLTWH(offset.dx, offset.dy, textW, textH),
        paint,
      );
    } else {
      final bounds =
          Rect.fromLTWH(offset.dx - 80, offset.dy - 80, textW + 160, textH + 160);
      canvas.saveLayer(
        bounds,
        Paint()..colorFilter = ColorFilter.mode(color, BlendMode.srcIn),
      );
      tp.paint(canvas, offset);
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(_LedPainter old) =>
      old.t != t || old.pixelImage != pixelImage || old.cfg != cfg;
}
