import 'package:flutter/material.dart';

import 'import_page.dart';
import 'models.dart';
import 'widgets.dart';

/// Local cart: every imported product grouped by body part.
class CartPage extends StatelessWidget {
  const CartPage({super.key, required this.store});
  final CartStore store;

  Future<void> _add(BuildContext context) async {
    final part = await showModalBottomSheet<BodyPart>(
      context: context,
      builder: (c) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text('Sản phẩm mặc ở đâu?', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
            ),
            for (final p in BodyPart.values)
              ListTile(
                leading: Icon(p.icon),
                title: Text('${p.label} · ${p.itemLabel}'),
                onTap: () => Navigator.pop(c, p),
              ),
          ],
        ),
      ),
    );
    if (part != null && context.mounted) await importProduct(context, store, part);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Giỏ hàng')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _add(context),
        icon: const Icon(Icons.add_photo_alternate_outlined),
        label: const Text('Thêm sản phẩm'),
      ),
      body: ListenableBuilder(
        listenable: store,
        builder: (context, _) {
          if (store.products.isEmpty) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Text(
                  'Giỏ hàng trống.\nBấm "Thêm sản phẩm" để chọn ảnh mũ, kính, khẩu trang, áo, quần, giày…',
                  textAlign: TextAlign.center,
                ),
              ),
            );
          }
          return ListView(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 96),
            children: [
              for (final part in BodyPart.values)
                if (store.itemsFor(part).isNotEmpty) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(4, 16, 4, 6),
                    child: Row(
                      children: [
                        Icon(part.icon, size: 20),
                        const SizedBox(width: 8),
                        Text('${part.label} · ${part.itemLabel}',
                            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                      ],
                    ),
                  ),
                  for (final p in store.itemsFor(part)) _row(context, p),
                ],
            ],
          );
        },
      ),
    );
  }

  Widget _row(BuildContext context, Product p) {
    final wearing = store.worn[p.part] == p.id;
    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        leading: productThumb(p.path, size: 56),
        title: Text(p.name),
        subtitle: wearing
            ? const Text('Đang mặc', style: TextStyle(color: kAccent2, fontWeight: FontWeight.w600))
            : null,
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: wearing ? 'Gỡ ra' : 'Mặc thử',
              icon: Icon(wearing ? Icons.check_circle : Icons.checkroom),
              color: wearing ? kAccent2 : null,
              onPressed: () => store.wear(p.part, wearing ? null : p.id),
            ),
            IconButton(
              tooltip: 'Xoá',
              icon: const Icon(Icons.delete_outline, color: Colors.redAccent),
              onPressed: () async {
                if (await confirm(context, 'Xoá sản phẩm?', 'Xoá "${p.name}" khỏi giỏ hàng?')) {
                  await store.remove(p);
                }
              },
            ),
          ],
        ),
      ),
    );
  }
}
