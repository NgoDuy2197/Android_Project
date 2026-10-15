import 'package:flutter/material.dart';

import 'import_page.dart';
import 'models.dart';
import 'widgets.dart';

/// Half-height glass sheet for one body part: pick / add / delete the item
/// worn there and fine-tune its size and position. The camera stays visible
/// above it so changes are seen live.
Future<void> showPartSheet(BuildContext context, CartStore store, BodyPart part) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black12,
    builder: (_) => PartSheet(store: store, part: part),
  );
}

class PartSheet extends StatelessWidget {
  const PartSheet({super.key, required this.store, required this.part});
  final CartStore store;
  final BodyPart part;

  @override
  Widget build(BuildContext context) {
    final maxH = MediaQuery.sizeOf(context).height * 0.52;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: maxH),
          child: Glass(
            radius: 28,
            opacity: 0.2,
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: ListenableBuilder(
              listenable: store,
              builder: (context, _) => _content(context),
            ),
          ),
        ),
      ),
    );
  }

  Widget _content(BuildContext context) {
    final items = store.itemsFor(part);
    final worn = store.wornItem(part);
    final adj = store.adjust[part]!;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 40,
          height: 5,
          margin: const EdgeInsets.only(bottom: 8),
          decoration: BoxDecoration(color: Colors.white54, borderRadius: BorderRadius.circular(3)),
        ),
        Row(
          children: [
            Icon(part.icon, color: Colors.white),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '${part.label} · ${part.itemLabel}',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: Colors.white),
              ),
            ),
            if (worn != null)
              TextButton.icon(
                onPressed: () => store.wear(part, null),
                icon: const Icon(Icons.layers_clear_outlined, color: Colors.white),
                label: const Text('Gỡ ra', style: TextStyle(color: Colors.white)),
              ),
          ],
        ),
        const SizedBox(height: 8),
        SizedBox(
          height: 96,
          child: ListView(
            scrollDirection: Axis.horizontal,
            children: [
              _AddTile(onTap: () => importProduct(context, store, part)),
              _NoneTile(selected: worn == null, onTap: () => store.wear(part, null)),
              for (final p in items)
                _ItemTile(
                  product: p,
                  selected: worn?.id == p.id,
                  onTap: () => store.wear(part, worn?.id == p.id ? null : p.id),
                  onDelete: () async {
                    if (await confirm(context, 'Xoá sản phẩm?', 'Xoá "${p.name}" khỏi giỏ hàng?')) {
                      await store.remove(p);
                    }
                  },
                ),
            ],
          ),
        ),
        if (items.isEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 6),
            child: Text(
              'Chưa có sản phẩm. Bấm "Thêm" để chọn ảnh.',
              style: TextStyle(color: Colors.white70),
            ),
          ),
        if (worn != null) ...[
          const Divider(color: Colors.white24, height: 20),
          Flexible(
            child: SingleChildScrollView(
              child: Column(
                children: [
                  _slider('Kích thước', adj.scale, 0.4, 2.5, (v) => adj.scale = v),
                  _slider('Độ rộng', adj.width, 0.5, 1.8, (v) => adj.width = v),
                  _slider('Độ dài', adj.height, 0.5, 1.8, (v) => adj.height = v),
                  _slider('Trái ↔ phải', adj.dx, -0.6, 0.6, (v) => adj.dx = v, percent: true),
                  _slider('Lên ↕ xuống', adj.dy, -0.6, 0.6, (v) => adj.dy = v, percent: true),
                  Row(
                    children: [
                      const Icon(Icons.flip, color: Colors.white70, size: 20),
                      const SizedBox(width: 8),
                      const Expanded(child: Text('Lật ngang ảnh', style: TextStyle(color: Colors.white))),
                      Switch(
                        value: adj.flip,
                        onChanged: (v) {
                          adj.flip = v;
                          store.changed();
                        },
                      ),
                    ],
                  ),
                  Align(
                    alignment: Alignment.centerRight,
                    child: TextButton.icon(
                      onPressed: adj.isDefault
                          ? null
                          : () {
                              adj.reset();
                              store.changed();
                            },
                      icon: const Icon(Icons.auto_fix_high, color: Colors.white),
                      label: const Text('Về size tự động', style: TextStyle(color: Colors.white)),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }

  Widget _slider(
    String label,
    double value,
    double min,
    double max,
    void Function(double) set, {
    bool percent = false,
  }) {
    final text = percent
        ? '${value >= 0 ? '+' : ''}${(value * 100).round()}%'
        : '${(value * 100).round()}%';
    return Row(
      children: [
        SizedBox(width: 104, child: Text(label, maxLines: 1, softWrap: false, style: const TextStyle(color: Colors.white))),
        Expanded(
          child: SliderTheme(
            data: const SliderThemeData(
              trackHeight: 4,
              activeTrackColor: Colors.white,
              inactiveTrackColor: Colors.white30,
              thumbColor: Colors.white,
              overlayShape: RoundSliderOverlayShape(overlayRadius: 14),
            ),
            child: Slider(
              min: min,
              max: max,
              value: value.clamp(min, max),
              onChanged: (v) {
                set(v);
                store.changed();
              },
            ),
          ),
        ),
        SizedBox(
          width: 48,
          child: Text(text, textAlign: TextAlign.right, style: const TextStyle(color: Colors.white70)),
        ),
      ],
    );
  }
}

class _AddTile extends StatelessWidget {
  const _AddTile({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 10, top: 8),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Container(
          width: 76,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            gradient: const LinearGradient(colors: [kAccent, kAccent2]),
          ),
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
    );
  }
}

/// "Nothing worn" choice for the part.
class _NoneTile extends StatelessWidget {
  const _NoneTile({required this.selected, required this.onTap});
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 10, top: 8),
      child: GestureDetector(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.all(3),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            gradient: selected ? const LinearGradient(colors: [kPink, kAccent, kAccent2]) : null,
            color: selected ? null : Colors.white24,
          ),
          child: Container(
            width: 70,
            decoration: BoxDecoration(
              color: Colors.black38,
              borderRadius: BorderRadius.circular(15),
            ),
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
      ),
    );
  }
}

class _ItemTile extends StatelessWidget {
  const _ItemTile({
    required this.product,
    required this.selected,
    required this.onTap,
    required this.onDelete,
  });

  final Product product;
  final bool selected;
  final VoidCallback onTap;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
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
              padding: const EdgeInsets.all(3),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(18),
                gradient: selected ? const LinearGradient(colors: [kPink, kAccent, kAccent2]) : null,
                color: selected ? null : Colors.white24,
              ),
              child: productThumb(product.path, size: 70),
            ),
          ),
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
          if (selected)
            const Positioned(
              bottom: 4,
              left: 4,
              child: Icon(Icons.check_circle, color: Colors.white, size: 18),
            ),
        ],
      ),
    );
  }
}
