import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import 'models.dart';
import 'native.dart';

/// Native-rendered preview of a frame (identical to the widget), re-rendered
/// shortly after every change. [sizeDp] simulates a widget size.
class FramePreview extends StatefulWidget {
  const FramePreview({super.key, required this.frame, required this.sizeDp, this.revision = 0, this.offset = 0});
  final Frame frame;
  final Size sizeDp;
  final int revision; // bump to re-render after edits
  final int offset; // show another batch of photos

  @override
  State<FramePreview> createState() => _FramePreviewState();
}

class _FramePreviewState extends State<FramePreview> {
  Uint8List? _png;
  Timer? _debounce;
  int _job = 0;

  @override
  void initState() {
    super.initState();
    _schedule(Duration.zero);
  }

  @override
  void didUpdateWidget(FramePreview old) {
    super.didUpdateWidget(old);
    if (old.revision != widget.revision || old.sizeDp != widget.sizeDp || old.offset != widget.offset || old.frame != widget.frame) {
      _schedule(const Duration(milliseconds: 120));
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  void _schedule(Duration d) {
    _debounce?.cancel();
    _debounce = Timer(d, _render);
  }

  Future<void> _render() async {
    final job = ++_job;
    final ratio = MediaQuery.devicePixelRatioOf(context);
    var w = widget.sizeDp.width * ratio, h = widget.sizeDp.height * ratio;
    final s = 900 / (w > h ? w : h);
    if (s < 1) {
      w *= s;
      h *= s;
    }
    final png = await Native.render(widget.frame, w.round(), h.round(), offset: widget.offset);
    if (mounted && job == _job && png != null) setState(() => _png = png);
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: widget.sizeDp.width,
      height: widget.sizeDp.height,
      child: _png == null
          ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
          : Image.memory(_png!, gaplessPlayback: true, fit: BoxFit.contain),
    );
  }
}
