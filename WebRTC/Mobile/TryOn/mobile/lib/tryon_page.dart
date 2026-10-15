import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:gal/gal.dart';
import 'package:google_mlkit_pose_detection/google_mlkit_pose_detection.dart';
import 'package:google_mlkit_selfie_segmentation/google_mlkit_selfie_segmentation.dart';

import 'backdrop_sheet.dart';
import 'cart_page.dart';
import 'image_tools.dart';
import 'models.dart';
import 'outfit_painter.dart';
import 'part_bar.dart';
import 'part_sheet.dart';
import 'photo_page.dart';
import 'video_recorder.dart';
import 'widgets.dart';

/// Last analysed camera frame together with the body points found in it,
/// so a photo is composed exactly like the live preview.
class _Frame {
  _Frame(this.bytes, this.width, this.height, this.stride, this.rotation, this.mirror, this.body, this.mask);
  final Uint8List bytes;
  final int width, height, stride, rotation;
  final bool mirror;
  final BodyPoints body;
  final PersonMask? mask;
}

class TryOnPage extends StatefulWidget {
  const TryOnPage({super.key, required this.store});
  final CartStore store;

  @override
  State<TryOnPage> createState() => _TryOnPageState();
}

class _TryOnPageState extends State<TryOnPage> with WidgetsBindingObserver, TickerProviderStateMixin {
  CartStore get store => widget.store;

  List<CameraDescription> _cams = [];
  int _camIndex = 0;
  CameraController? _ctrl;
  String? _error;

  final _detector = PoseDetector(
    options: PoseDetectorOptions(mode: PoseDetectionMode.stream, model: PoseDetectionModel.base),
  );
  final _segmenter = SelfieSegmenter(mode: SegmenterMode.stream, enableRawSizeMask: true);
  PersonMask? _mask;
  ui.Image? _backdrop;
  final _tracker = PoseTracker();
  bool _busy = false;
  BodyPoints _body = BodyPoints({}, Size.zero);
  _Frame? _lastFrame;

  final Map<String, ui.Image> _imgCache = {};
  Map<BodyPart, ui.Image> _wornImages = {};
  BodyPart? _focus;

  late final AnimationController _pulse = AnimationController(vsync: this, duration: const Duration(milliseconds: 900))
    ..repeat(reverse: true);
  late final AnimationController _flash = AnimationController(vsync: this, duration: const Duration(milliseconds: 350));
  bool _saving = false;
  Uint8List? _lastShot;

  bool _videoMode = false;
  OutfitVideoRecorder? _rec;
  Timer? _recTicker;

  /// True while the static-photo page is open: keep the camera released.
  bool _suspended = false;

  /// Hides every control so only the dressed-up camera view remains.
  bool _hideUi = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    store.addListener(_onStore);
    _onStore();
    _initCameras();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    store.removeListener(_onStore);
    _recTicker?.cancel();
    _rec?.stop();
    _ctrl?.dispose();
    _detector.close();
    _segmenter.close();
    _pulse.dispose();
    _flash.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Only release on `paused`: `inactive` also fires for the runtime
    // permission dialog, and reopening then races the first open.
    final c = _ctrl;
    if (state == AppLifecycleState.paused || state == AppLifecycleState.hidden) {
      if (_rec != null) _stopRecording();
      if (c != null) {
        _ctrl = null;
        setState(() {});
        c.dispose();
      }
    } else if (state == AppLifecycleState.resumed && _ctrl == null && _cams.isNotEmpty && !_starting && !_suspended) {
      _startCamera(_camIndex);
    }
  }

  // ---------------------------------------------------------------- items

  Future<void> _onStore() async {
    final next = <BodyPart, ui.Image>{};
    for (final part in BodyPart.values) {
      final p = store.wornItem(part);
      if (p == null) continue;
      var image = _imgCache[p.path];
      if (image == null) {
        try {
          final codec = await ui.instantiateImageCodec(await File(p.path).readAsBytes());
          image = (await codec.getNextFrame()).image;
          _imgCache[p.path] = image;
        } catch (_) {
          continue;
        }
      }
      next[part] = image;
    }
    final bd = store.backdrop;
    ui.Image? backdrop;
    if (bd != null) {
      backdrop = _imgCache[bd.path];
      if (backdrop == null) {
        try {
          final codec = await ui.instantiateImageCodec(await File(bd.path).readAsBytes());
          backdrop = (await codec.getNextFrame()).image;
          _imgCache[bd.path] = backdrop;
        } catch (_) {}
      }
    }
    // Drop decoded images of deleted products/backdrops.
    _imgCache.removeWhere(
      (path, _) => !store.products.any((p) => p.path == path) && !store.backdrops.any((b) => b.path == path),
    );
    if (mounted) {
      setState(() {
        _wornImages = next;
        _backdrop = backdrop;
        if (!store.needsSegmentation) _mask = null;
      });
    }
  }

  // --------------------------------------------------------------- camera

  Future<void> _initCameras() async {
    try {
      _cams = await availableCameras();
    } catch (e) {
      setState(() => _error = 'Không truy cập được camera: $e');
      return;
    }
    if (_cams.isEmpty) {
      setState(() => _error = 'Thiết bị không có camera');
      return;
    }
    // Default to the selfie camera: easiest way to try things on.
    final front = _cams.indexWhere((c) => c.lensDirection == CameraLensDirection.front);
    await _startCamera(front >= 0 ? front : 0);
  }

  bool _starting = false;

  Future<void> _startCamera(int index) async {
    if (_starting) return;
    _starting = true;
    try {
      await _openCamera(index);
    } finally {
      _starting = false;
    }
  }

  Future<void> _openCamera(int index) async {
    final old = _ctrl;
    _ctrl = null;
    if (mounted) setState(() {});
    await old?.dispose();
    _tracker.reset();
    _lastFrame = null;
    CameraException? failure;
    // Some devices cannot stream 1080p alongside preview + capture: step down.
    for (final preset in [ResolutionPreset.veryHigh, ResolutionPreset.high, ResolutionPreset.medium]) {
      final ctrl = CameraController(
        _cams[index],
        preset,
        enableAudio: false,
        imageFormatGroup: Platform.isAndroid ? ImageFormatGroup.nv21 : ImageFormatGroup.bgra8888,
      );
      try {
        await ctrl.initialize();
        await ctrl.lockCaptureOrientation(DeviceOrientation.portraitUp);
        await ctrl.startImageStream(_onFrame);
      } on CameraException catch (e) {
        await ctrl.dispose();
        failure = e;
        if (e.code.contains('AccessDenied')) break;
        continue;
      }
      if (!mounted) {
        await ctrl.dispose();
        return;
      }
      setState(() {
        _camIndex = index;
        _ctrl = ctrl;
        _error = null;
        _body = BodyPoints({}, Size.zero);
      });
      return;
    }
    if (mounted) {
      setState(
        () => _error = failure != null && failure.code.contains('AccessDenied')
            ? 'Cần cấp quyền Camera để thử đồ.\nVào Cài đặt > Ứng dụng > TryOn > Quyền.'
            : 'Không mở được camera (${failure?.code ?? 'unknown'}).',
      );
    }
  }

  bool get _isFront => _cams.isNotEmpty && _cams[_camIndex].lensDirection == CameraLensDirection.front;
  bool get _mirror => _isFront != store.mirrorFix;

  int _rotation(CameraController ctrl) {
    const comp = {
      DeviceOrientation.portraitUp: 0,
      DeviceOrientation.landscapeLeft: 90,
      DeviceOrientation.portraitDown: 180,
      DeviceOrientation.landscapeRight: 270,
    };
    final sensor = ctrl.description.sensorOrientation;
    final c = comp[ctrl.value.deviceOrientation] ?? 0;
    return _isFront ? (sensor + c) % 360 : (sensor - c + 360) % 360;
  }

  Future<void> _onFrame(CameraImage image) async {
    final ctrl = _ctrl;
    // Every frame goes to the video encoder; only idle ones to pose detection.
    _rec?.pushFrame(image);
    if (_busy || ctrl == null || image.planes.isEmpty) return;
    _busy = true;
    try {
      final rotation = _rotation(ctrl);
      final plane = image.planes.first;
      final bytes = image.planes.length == 1
          ? plane.bytes
          : Uint8List.fromList([for (final p in image.planes) ...p.bytes]);
      final format =
          InputImageFormatValue.fromRawValue(image.format.raw as int) ??
          (Platform.isAndroid ? InputImageFormat.nv21 : InputImageFormat.bgra8888);
      final input = InputImage.fromBytes(
        bytes: bytes,
        metadata: InputImageMetadata(
          size: Size(image.width.toDouble(), image.height.toDouble()),
          rotation: InputImageRotationValue.fromRawValue(rotation) ?? InputImageRotation.rotation0deg,
          format: format,
          bytesPerRow: plane.bytesPerRow,
        ),
      );
      final segFuture = store.needsSegmentation ? _segmenter.processImage(input) : Future.value(null);
      final poses = await _detector.processImage(input);
      final seg = await segFuture;
      final mask = seg == null ? null : PersonMask(await maskImage(seg.confidences, seg.width, seg.height), mirror: _mirror);
      final upright = rotation == 90 || rotation == 270
          ? Size(image.height.toDouble(), image.width.toDouble())
          : Size(image.width.toDouble(), image.height.toDouble());
      _tracker.update(poses.isEmpty ? null : poses.first, upright, _mirror);
      final body = _tracker.snapshot();
      if (format == InputImageFormat.nv21) {
        final stride = bytes.length >= plane.bytesPerRow * image.height * 3 ~/ 2 ? plane.bytesPerRow : image.width;
        _lastFrame = _Frame(bytes, image.width, image.height, stride, rotation, _mirror, body, mask);
      }
      _rec?.updateOverlay(body, _wornImages, store.adjust, mask: mask, backdrop: _backdrop);
      if (mounted) {
        setState(() {
          _body = body;
          _mask = mask;
        });
      }
    } catch (e) {
      debugPrint('pose error: $e');
    } finally {
      _busy = false;
    }
  }

  // -------------------------------------------------------------- capture

  Future<void> _capture() async {
    final f = _lastFrame;
    if (f == null || _saving) {
      toast(context, 'Camera chưa sẵn sàng');
      return;
    }
    HapticFeedback.mediumImpact();
    _flash.forward(from: 0);
    setState(() => _saving = true);
    try {
      final rgba = await compute(
        nv21ToUprightRgba,
        FrameArgs(
          bytes: f.bytes,
          width: f.width,
          height: f.height,
          stride: f.stride,
          rotation: f.rotation,
          mirror: f.mirror,
        ),
      );
      final upright = f.rotation == 90 || f.rotation == 270;
      final w = upright ? f.height : f.width;
      final h = upright ? f.width : f.height;
      final photo = await _decodeRgba(rgba, w, h);

      final rec = ui.PictureRecorder();
      final canvas = Canvas(rec);
      canvas.drawImage(photo, Offset.zero, Paint());
      paintOutfit(canvas, f.body, _wornImages, store.adjust, mask: f.mask, backdrop: _backdrop);
      final composed = await rec.endRecording().toImage(w, h);
      final raw = await composed.toByteData(format: ui.ImageByteFormat.rawRgba);
      photo.dispose();
      composed.dispose();
      final jpg = await compute(encodeJpgRgba, (raw!.buffer.asUint8List(), w, h));

      if (!await Gal.hasAccess(toAlbum: true)) await Gal.requestAccess(toAlbum: true);
      await Gal.putImageBytes(jpg, album: 'TryOn', name: 'tryon_${DateTime.now().millisecondsSinceEpoch}');
      if (!mounted) return;
      setState(() => _lastShot = jpg);
      toast(context, 'Đã lưu ảnh vào Thư viện (album TryOn)');
    } catch (e) {
      if (mounted) toast(context, 'Lưu ảnh thất bại: $e');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  // ---------------------------------------------------------------- video

  Future<void> _toggleRecording() async {
    if (_rec != null) return _stopRecording();
    final f = _lastFrame;
    if (f == null || _saving) {
      toast(context, 'Camera chưa sẵn sàng');
      return;
    }
    try {
      final rec = await OutfitVideoRecorder.start(
        width: f.width,
        height: f.height,
        rotation: f.rotation,
        mirror: f.mirror,
      );
      HapticFeedback.mediumImpact();
      setState(() => _rec = rec);
      _recTicker = Timer.periodic(const Duration(milliseconds: 500), (_) {
        if (mounted) setState(() {});
      });
    } catch (e) {
      if (mounted) toast(context, 'Không bắt đầu quay được: $e');
    }
  }

  Future<void> _stopRecording() async {
    final rec = _rec;
    if (rec == null) return;
    _recTicker?.cancel();
    HapticFeedback.mediumImpact();
    setState(() {
      _rec = null;
      _saving = true;
    });
    try {
      final path = await rec.stop();
      if (path == null) {
        if (mounted) toast(context, 'Video quá ngắn, chưa lưu');
        return;
      }
      if (!await Gal.hasAccess(toAlbum: true)) await Gal.requestAccess(toAlbum: true);
      await Gal.putVideo(path, album: 'TryOn');
      await File(path).delete();
      if (mounted) toast(context, 'Đã lưu video vào Thư viện (album TryOn)');
    } catch (e) {
      if (mounted) toast(context, 'Lưu video thất bại: $e');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  static String _clock(Duration d) =>
      '${d.inMinutes.toString().padLeft(2, '0')}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';

  static Future<ui.Image> _decodeRgba(Uint8List rgba, int w, int h) {
    final c = Completer<ui.Image>();
    ui.decodeImageFromPixels(rgba, w, h, ui.PixelFormat.rgba8888, c.complete);
    return c.future;
  }

  // ------------------------------------------------------------------- UI

  Future<void> _openPart(BodyPart part) async {
    setState(() => _focus = part);
    await showPartSheet(context, store, part);
    if (mounted) setState(() => _focus = null);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          _preview(),
          IgnorePointer(
            child: FadeTransition(
              opacity: Tween(begin: 0.8, end: 0.0).animate(_flash),
              child: AnimatedBuilder(
                animation: _flash,
                builder: (_, _) => _flash.isAnimating ? Container(color: Colors.white) : const SizedBox(),
              ),
            ),
          ),
          if (_hideUi) ...[
            // Clean view: tap anywhere (or the eye button) to bring the UI back.
            Positioned.fill(
              child: GestureDetector(
                behavior: HitTestBehavior.translucent,
                onTap: () => setState(() => _hideUi = false),
              ),
            ),
            Align(
              alignment: Alignment.topRight,
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: ShowUiButton(recording: _rec != null, onTap: () => setState(() => _hideUi = false)),
                ),
              ),
            ),
          ] else ...[
            Align(
              alignment: Alignment.topCenter,
              child: SafeArea(child: _topBar()),
            ),
            if (_ctrl != null && _error == null && _body.isEmpty)
              const Align(
                alignment: Alignment(0, -0.72),
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 24),
                  child: Glass(
                    padding: EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                    child: Text(
                      'Đang tìm người… lùi ra xa để camera thấy cả người',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.white),
                    ),
                  ),
                ),
              ),
            Align(
              alignment: Alignment.bottomCenter,
              child: SafeArea(child: _bottomBar()),
            ),
          ],
        ],
      ),
    );
  }

  Widget _preview() {
    final ctrl = _ctrl;
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                _error!,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white),
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () {
                  setState(() => _error = null);
                  _initCameras();
                },
                child: const Text('Thử lại'),
              ),
            ],
          ),
        ),
      );
    }
    if (ctrl == null || !ctrl.value.isInitialized || ctrl.value.previewSize == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final ps = ctrl.value.previewSize!;
    final pw = ps.shortestSide, ph = ps.longestSide;
    return ClipRect(
      child: FittedBox(
        fit: BoxFit.cover,
        child: SizedBox(
          width: pw,
          height: ph,
          child: Stack(
            fit: StackFit.expand,
            children: [
              CameraPreview(ctrl),
              RepaintBoundary(
                child: CustomPaint(
                  painter: OutfitOverlayPainter(
                    body: _body,
                    items: _wornImages,
                    adjust: store.adjust,
                    focus: _focus,
                    pulse: _pulse,
                    revision: store.revision,
                    mask: _mask,
                    backdrop: _backdrop,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _topBar() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        children: [
          if (_rec != null)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
              decoration: BoxDecoration(color: const Color(0xFFFF3B30), borderRadius: BorderRadius.circular(22)),
              child: Row(
                children: [
                  FadeTransition(
                    opacity: _pulse,
                    child: const Icon(Icons.circle, size: 10, color: Colors.white),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    _clock(_rec!.elapsed.elapsed),
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w700,
                      fontSize: 16,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
            )
          else
            Glass(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
              child: Row(
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _body.isEmpty ? Colors.orangeAccent : Colors.greenAccent,
                    ),
                  ),
                  const SizedBox(width: 8),
                  const Text(
                    'TryOn',
                    style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 16),
                  ),
                ],
              ),
            ),
          const Spacer(),
          _roundButton(
            icon: Icons.shopping_bag_outlined,
            badge: store.products.length,
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CartPage(store: store))),
          ),
          const SizedBox(width: 10),
          _roundButton(icon: Icons.visibility_off_outlined, onTap: () => setState(() => _hideUi = true)),
          const SizedBox(width: 10),
          _roundButton(icon: Icons.more_horiz, onTap: _showMenu),
          const SizedBox(width: 10),
          _roundButton(
            icon: Icons.cameraswitch_outlined,
            onTap: _cams.length < 2 || _rec != null ? null : () => _startCamera((_camIndex + 1) % _cams.length),
          ),
        ],
      ),
    );
  }

  void _showMenu() {
    showModalBottomSheet<void>(
      context: context,
      builder: (c) => SafeArea(
        child: StatefulBuilder(
          builder: (c, set) => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SwitchListTile(
                secondary: const Icon(Icons.flip),
                title: const Text('Đảo trái ↔ phải lớp đồ'),
                subtitle: const Text('Bật nếu đồ bị lệch ngược chiều khi bạn di chuyển'),
                value: store.mirrorFix,
                onChanged: (v) {
                  store.mirrorFix = v;
                  store.changed();
                  _tracker.reset();
                  set(() {});
                },
              ),
              ListTile(
                leading: const Icon(Icons.layers_clear_outlined),
                title: const Text('Gỡ tất cả đồ đang mặc'),
                onTap: () {
                  store.worn.clear();
                  store.changed();
                  Navigator.pop(c);
                },
              ),
              ListTile(
                leading: const Icon(Icons.photo_library_outlined),
                title: const Text('Mở thư viện ảnh'),
                onTap: () {
                  Navigator.pop(c);
                  Gal.open();
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _roundButton({required IconData icon, VoidCallback? onTap, int badge = 0}) {
    return GestureDetector(
      onTap: onTap,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Glass(
            radius: 22,
            child: SizedBox(
              width: 44,
              height: 44,
              child: Icon(icon, color: onTap == null ? Colors.white38 : Colors.white),
            ),
          ),
          if (badge > 0)
            Positioned(
              right: -4,
              top: -4,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(color: kPink, borderRadius: BorderRadius.circular(10)),
                child: Text(
                  '$badge',
                  style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w700),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _bottomBar() {
    final recording = _rec != null;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (!recording) _modeSelector(),
          const SizedBox(height: 10),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              SizedBox(
                width: 64,
                child: _lastShot == null || recording
                    ? null
                    : GestureDetector(
                        onTap: Gal.open,
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(14),
                          child: Container(
                            decoration: BoxDecoration(
                              border: Border.all(color: Colors.white, width: 2),
                              borderRadius: BorderRadius.circular(14),
                            ),
                            child: Image.memory(_lastShot!, width: 56, height: 56, fit: BoxFit.cover, cacheWidth: 160),
                          ),
                        ),
                      ),
              ),
              _shutter(),
              SizedBox(
                width: 64,
                child: recording ? null : _backdropButton(),
              ),
            ],
          ),
          const SizedBox(height: 14),
          PartBar(store: store, focus: _focus, onTap: _openPart),
        ],
      ),
    );
  }

  /// iOS-camera style mode labels: VIDEO · ẢNH · ẢNH TĨNH.
  Widget _modeSelector() {
    Widget item(String label, bool selected, VoidCallback onTap) => GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? const Color(0xFFFFD60A) : Colors.white,
            fontWeight: FontWeight.w700,
            fontSize: 13,
            letterSpacing: 1.2,
            shadows: const [Shadow(blurRadius: 6, color: Colors.black54)],
          ),
        ),
      ),
    );
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        item('VIDEO', _videoMode, () => setState(() => _videoMode = true)),
        item('ẢNH', !_videoMode, () => setState(() => _videoMode = false)),
        item('ẢNH TĨNH', false, _openStaticPhoto),
      ],
    );
  }

  Future<void> _openStaticPhoto() async {
    // Release the camera so the system camera app can take the photo.
    _suspended = true;
    final c = _ctrl;
    _ctrl = null;
    setState(() {});
    await c?.dispose();
    if (!mounted) return;
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => PhotoPage(store: store)));
    _suspended = false;
    if (mounted && _ctrl == null) _startCamera(_camIndex);
  }

  Widget _backdropButton() {
    final bd = store.backdrop;
    return GestureDetector(
      onTap: () => showBackdropSheet(context, store),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 48,
            height: 48,
            padding: const EdgeInsets.all(2.5),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              gradient: bd != null ? const LinearGradient(colors: [kPink, kAccent, kAccent2]) : null,
              color: bd != null ? null : Colors.white24,
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: bd != null
                  ? Image.file(File(bd.path), fit: BoxFit.cover, cacheWidth: 120, gaplessPlayback: true)
                  : const ColoredBox(color: Colors.black26, child: Icon(Icons.wallpaper, color: Colors.white)),
            ),
          ),
          const SizedBox(height: 4),
          const Text('Nền', style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }

  Widget _shutter() {
    final recording = _rec != null;
    final Widget inner;
    if (_saving) {
      inner = Container(
        decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.white54),
        padding: const EdgeInsets.all(18),
        child: const CircularProgressIndicator(strokeWidth: 3, color: kAccent),
      );
    } else if (_videoMode) {
      inner = Center(
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          width: recording ? 30 : 64,
          height: recording ? 30 : 64,
          decoration: BoxDecoration(
            color: const Color(0xFFFF3B30),
            borderRadius: BorderRadius.circular(recording ? 8 : 32),
          ),
        ),
      );
    } else {
      inner = Container(
        decoration: const BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Colors.white, Color(0xFFE9E6FF)],
          ),
        ),
      );
    }
    return GestureDetector(
      onTap: _saving ? null : (_videoMode ? _toggleRecording : _capture),
      child: Container(
        width: 78,
        height: 78,
        padding: const EdgeInsets.all(5),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: Border.all(color: Colors.white, width: 4),
        ),
        child: inner,
      ),
    );
  }
}
