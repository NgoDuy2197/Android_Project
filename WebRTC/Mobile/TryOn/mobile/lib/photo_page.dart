import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:gal/gal.dart';
import 'package:google_mlkit_pose_detection/google_mlkit_pose_detection.dart';
import 'package:google_mlkit_selfie_segmentation/google_mlkit_selfie_segmentation.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';

import 'backdrop_sheet.dart';
import 'image_tools.dart';
import 'models.dart';
import 'outfit_painter.dart';
import 'part_bar.dart';
import 'part_sheet.dart';
import 'widgets.dart';

/// Still-photo mode: take or pick a full-body photo, detect the pose once
/// with the accurate model and dress it with the worn items.
class PhotoPage extends StatefulWidget {
  const PhotoPage({super.key, required this.store});
  final CartStore store;

  @override
  State<PhotoPage> createState() => _PhotoPageState();
}

class _PhotoPageState extends State<PhotoPage> with SingleTickerProviderStateMixin {
  CartStore get store => widget.store;

  final _detector = PoseDetector(
    options: PoseDetectorOptions(mode: PoseDetectionMode.single, model: PoseDetectionModel.accurate),
  );
  final _segmenter = SelfieSegmenter(mode: SegmenterMode.single, enableRawSizeMask: true);
  PersonMask? _mask;
  ui.Image? _backdrop;
  late final AnimationController _pulse = AnimationController(vsync: this, duration: const Duration(milliseconds: 900))
    ..repeat(reverse: true);

  ui.Image? _photo;
  BodyPoints _body = BodyPoints({}, Size.zero);
  bool _busy = false;
  bool _saving = false;
  BodyPart? _focus;
  bool _hideUi = false;
  final Map<String, ui.Image> _imgCache = {};
  Map<BodyPart, ui.Image> _worn = {};

  @override
  void initState() {
    super.initState();
    store.addListener(_onStore);
    _onStore();
    WidgetsBinding.instance.addPostFrameCallback((_) => _pick(first: true));
  }

  @override
  void dispose() {
    store.removeListener(_onStore);
    _detector.close();
    _segmenter.close();
    _pulse.dispose();
    _photo?.dispose();
    super.dispose();
  }

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
    if (mounted) {
      setState(() {
        _worn = next;
        _backdrop = backdrop;
      });
    }
  }

  Future<void> _pick({bool first = false}) async {
    final source = await showModalBottomSheet<ImageSource>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (c) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Glass(
            opacity: 0.22,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Padding(
                  padding: EdgeInsets.fromLTRB(16, 14, 16, 4),
                  child: Text(
                    'Ảnh cần thấy rõ cả người (đầu đến chân) để gắn đủ đồ',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.white70),
                  ),
                ),
                ListTile(
                  leading: const Icon(Icons.photo_camera_outlined),
                  title: const Text('Chụp ảnh toàn thân'),
                  onTap: () => Navigator.pop(c, ImageSource.camera),
                ),
                ListTile(
                  leading: const Icon(Icons.photo_library_outlined),
                  title: const Text('Chọn ảnh có sẵn'),
                  onTap: () => Navigator.pop(c, ImageSource.gallery),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (source == null) {
      if (first && _photo == null && mounted) Navigator.pop(context);
      return;
    }
    final XFile? file;
    try {
      file = await ImagePicker().pickImage(source: source, preferredCameraDevice: CameraDevice.rear);
    } catch (e) {
      if (mounted) toast(context, 'Không mở được ảnh: $e');
      return;
    }
    if (file == null) {
      if (first && _photo == null && mounted) Navigator.pop(context);
      return;
    }
    await _load(await file.readAsBytes());
  }

  Future<void> _load(Uint8List bytes) async {
    setState(() => _busy = true);
    try {
      final norm = await compute(normalizePhoto, bytes);
      if (norm == null) throw 'Không đọc được ảnh';
      final (jpg, w, h) = norm;
      final dir = await getTemporaryDirectory();
      final path = '${dir.path}/tryon_still.jpg';
      await File(path).writeAsBytes(jpg);
      final input = InputImage.fromFilePath(path);
      final poses = await _detector.processImage(input);
      // Always segment stills (cheap, one image): backdrop + wings behind the body.
      final seg = await _segmenter.processImage(input);
      final mask = seg == null ? null : PersonMask(await maskImage(seg.confidences, seg.width, seg.height));
      final codec = await ui.instantiateImageCodec(jpg);
      final photo = (await codec.getNextFrame()).image;
      final size = Size(w.toDouble(), h.toDouble());
      if (!mounted) return;
      setState(() {
        _photo?.dispose();
        _photo = photo;
        _body = poses.isEmpty ? BodyPoints({}, size) : BodyPoints.fromPose(poses.first, size);
        _mask = mask;
      });
      if (poses.isEmpty) toast(context, 'Không tìm thấy người trong ảnh');
    } catch (e) {
      if (mounted) toast(context, 'Lỗi xử lý ảnh: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _save() async {
    final photo = _photo;
    if (photo == null || _saving) return;
    setState(() => _saving = true);
    HapticFeedback.mediumImpact();
    try {
      final w = photo.width, h = photo.height;
      final rec = ui.PictureRecorder();
      final canvas = Canvas(rec);
      canvas.drawImage(photo, Offset.zero, Paint());
      paintOutfit(canvas, _body, _worn, store.adjust, mask: _mask, backdrop: _backdrop);
      final composed = await rec.endRecording().toImage(w, h);
      final raw = await composed.toByteData(format: ui.ImageByteFormat.rawRgba);
      composed.dispose();
      final jpg = await compute(encodeJpgRgba, (raw!.buffer.asUint8List(), w, h));
      if (!await Gal.hasAccess(toAlbum: true)) await Gal.requestAccess(toAlbum: true);
      await Gal.putImageBytes(jpg, album: 'TryOn', name: 'tryon_still_${DateTime.now().millisecondsSinceEpoch}');
      if (mounted) toast(context, 'Đã lưu ảnh vào Thư viện (album TryOn)');
    } catch (e) {
      if (mounted) toast(context, 'Lưu ảnh thất bại: $e');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _openPart(BodyPart part) async {
    setState(() => _focus = part);
    await showPartSheet(context, store, part);
    if (mounted) setState(() => _focus = null);
  }

  @override
  Widget build(BuildContext context) {
    final photo = _photo;
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (photo != null)
            Center(
              child: FittedBox(
                fit: BoxFit.contain,
                child: SizedBox(
                  width: photo.width.toDouble(),
                  height: photo.height.toDouble(),
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      RawImage(image: photo, fit: BoxFit.fill),
                      CustomPaint(
                        painter: OutfitOverlayPainter(
                          body: _body,
                          items: _worn,
                          adjust: store.adjust,
                          focus: _focus,
                          pulse: _pulse,
                          revision: store.revision,
                          mask: _mask,
                          backdrop: _backdrop,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          if (_busy)
            const Center(
              child: Glass(
                padding: EdgeInsets.all(20),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    CircularProgressIndicator(color: Colors.white),
                    SizedBox(height: 12),
                    Text('Đang nhận diện cơ thể…', style: TextStyle(color: Colors.white)),
                  ],
                ),
              ),
            )
          else if (photo != null && _body.isEmpty)
            const Align(
              alignment: Alignment(0, -0.7),
              child: Padding(
                padding: EdgeInsets.symmetric(horizontal: 24),
                child: Glass(
                  padding: EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  child: Text(
                    'Không tìm thấy người. Hãy chọn ảnh thấy rõ cả người.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.white),
                  ),
                ),
              ),
            ),
          if (_hideUi) ...[
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
                  child: ShowUiButton(onTap: () => setState(() => _hideUi = false)),
                ),
              ),
            ),
          ] else ...[
            Align(
              alignment: Alignment.topCenter,
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  child: Row(
                    children: [
                      _glassButton(Icons.arrow_back_ios_new, () => Navigator.pop(context)),
                      const SizedBox(width: 10),
                      const Glass(
                        padding: EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                        child: Text(
                          'Ảnh tĩnh',
                          style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 16),
                        ),
                      ),
                      const Spacer(),
                      _glassButton(Icons.wallpaper, () => showBackdropSheet(context, store)),
                    const SizedBox(width: 10),
                    _glassButton(Icons.visibility_off_outlined, () => setState(() => _hideUi = true)),
                      const SizedBox(width: 10),
                      _glassButton(Icons.add_photo_alternate_outlined, _busy ? null : () => _pick()),
                    ],
                  ),
                ),
              ),
            ),
            Align(
              alignment: Alignment.bottomCenter,
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      FilledButton.icon(
                        style: FilledButton.styleFrom(
                          backgroundColor: Colors.white,
                          foregroundColor: Colors.black,
                          minimumSize: const Size(200, 50),
                          shape: const StadiumBorder(),
                        ),
                        onPressed: photo == null || _busy || _saving ? null : _save,
                        icon: _saving
                            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(Icons.download_rounded),
                        label: const Text('Lưu ảnh', style: TextStyle(fontWeight: FontWeight.w700)),
                      ),
                      const SizedBox(height: 14),
                      ListenableBuilder(
                        listenable: store,
                        builder: (_, _) => PartBar(store: store, focus: _focus, onTap: _openPart),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _glassButton(IconData icon, VoidCallback? onTap) => GestureDetector(
    onTap: onTap,
    child: Glass(
      radius: 22,
      child: SizedBox(
        width: 44,
        height: 44,
        child: Icon(icon, color: onTap == null ? Colors.white38 : Colors.white, size: 20),
      ),
    ),
  );
}
