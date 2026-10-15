import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import 'image_tools.dart';
import 'models.dart';
import 'widgets.dart';

/// Glass sheet to pick the background that replaces everything but the
/// person ("Không" keeps the real scene).
Future<void> showBackdropSheet(BuildContext context, CartStore store) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black12,
    builder: (_) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
        child: Glass(
          radius: 28,
          opacity: 0.2,
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 14),
          child: ListenableBuilder(listenable: store, builder: (context, _) => _content(context, store)),
        ),
      ),
    ),
  );
}

Widget _content(BuildContext context, CartStore store) {
  final selected = store.backdropId;
  return Column(
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Center(
        child: Container(
          width: 40,
          height: 5,
          margin: const EdgeInsets.only(bottom: 8),
          decoration: BoxDecoration(color: Colors.white54, borderRadius: BorderRadius.circular(3)),
        ),
      ),
      const Row(
        children: [
          Icon(Icons.wallpaper, color: Colors.white),
          SizedBox(width: 8),
          Text('Background', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: Colors.white)),
        ],
      ),
      const Padding(
        padding: EdgeInsets.only(top: 4, bottom: 10),
        child: Text('Chỉ giữ lại người, thay phần còn lại bằng ảnh nền',
            style: TextStyle(color: Colors.white70)),
      ),
      SizedBox(
        height: 132,
        child: ListView(
          scrollDirection: Axis.horizontal,
          children: [
            _tile(
              selected: false,
              onTap: () => _import(context, store),
              child: Container(
                decoration: const BoxDecoration(gradient: LinearGradient(colors: [kAccent, kAccent2])),
                child: const Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.add_photo_alternate_outlined, color: Colors.white, size: 30),
                    SizedBox(height: 4),
                    Text('Thêm', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w600)),
                  ],
                ),
              ),
            ),
            _tile(
              selected: selected == null,
              onTap: () => store.selectBackdrop(null),
              child: Container(
                color: Colors.black38,
                child: const Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.block, color: Colors.white, size: 28),
                    SizedBox(height: 4),
                    Text('Không', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w600)),
                  ],
                ),
              ),
            ),
            for (final b in store.backdrops)
              _tile(
                selected: selected == b.id,
                onTap: () => store.selectBackdrop(b.id),
                onDelete: () async {
                  if (await confirm(context, 'Xoá background?', 'Xoá "${b.name}"?')) {
                    await store.removeBackdrop(b);
                  }
                },
                child: Image.file(File(b.path), fit: BoxFit.cover, cacheWidth: 220, gaplessPlayback: true),
              ),
          ],
        ),
      ),
    ],
  );
}

Widget _tile({required bool selected, required VoidCallback onTap, VoidCallback? onDelete, required Widget child}) {
  return Padding(
    padding: const EdgeInsets.only(right: 10, top: 8),
    child: Stack(
      clipBehavior: Clip.none,
      children: [
        GestureDetector(
          onTap: onTap,
          onLongPress: onDelete,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            width: 74,
            height: 118,
            padding: const EdgeInsets.all(3),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              gradient: selected ? const LinearGradient(colors: [kPink, kAccent, kAccent2]) : null,
              color: selected ? null : Colors.white24,
            ),
            child: ClipRRect(borderRadius: BorderRadius.circular(13), child: SizedBox.expand(child: child)),
          ),
        ),
        if (onDelete != null)
          Positioned(
            top: -8,
            right: -8,
            child: GestureDetector(
              onTap: onDelete,
              child: Container(
                padding: const EdgeInsets.all(4),
                decoration: const BoxDecoration(color: Colors.redAccent, shape: BoxShape.circle),
                child: const Icon(Icons.close, size: 14, color: Colors.white),
              ),
            ),
          ),
      ],
    ),
  );
}

Future<void> _import(BuildContext context, CartStore store) async {
  final XFile? file;
  try {
    file = await ImagePicker().pickImage(source: ImageSource.gallery);
  } catch (e) {
    if (context.mounted) toast(context, 'Không mở được ảnh: $e');
    return;
  }
  if (file == null) return;
  final norm = await compute(normalizePhoto, await file.readAsBytes());
  if (norm == null) {
    if (context.mounted) toast(context, 'Không đọc được ảnh');
    return;
  }
  final id = 'bg_${DateTime.now().microsecondsSinceEpoch}';
  final out = File('${store.productDir.path}/$id.jpg');
  await out.writeAsBytes(norm.$1);
  store.addBackdrop(Backdrop(id: id, name: 'Ảnh của tôi', path: out.path));
}
