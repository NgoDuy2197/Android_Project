import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// Languages a line can be in; `tts` is the Android TTS locale ('' = not spoken).
class Lang {
  const Lang(this.code, this.label, this.tts, this.flag);
  final String code;
  final String label;
  final String tts;
  final String flag;

  static const all = [
    Lang('zh', 'Tiếng Trung', 'zh-CN', '🇨🇳'),
    Lang('en', 'Tiếng Anh', 'en-US', '🇬🇧'),
    Lang('ja', 'Tiếng Nhật', 'ja-JP', '🇯🇵'),
    Lang('vi', 'Tiếng Việt', 'vi-VN', '🇻🇳'),
    Lang('', 'Khác (phiên âm…)', '', '🔤'),
  ];

  static Lang of(String code) => all.firstWhere((l) => l.code == code, orElse: () => all.last);
}

/// One line of a word card: a name ("Nghĩa", "Pinyin"…) and its language.
class Field {
  Field(this.name, this.lang);
  String name;
  String lang;

  Map<String, dynamic> toJson() => {'name': name, 'lang': lang};
  static Field fromJson(Map<String, dynamic> j) => Field(j['name'] as String? ?? '', j['lang'] as String? ?? '');
}

class Word {
  Word({required this.id, required this.values, this.learned = false});
  final String id;
  List<String> values;
  bool learned;

  Map<String, dynamic> toJson() => {'id': id, 'values': values, 'learned': learned};
  static Word fromJson(Map<String, dynamic> j) => Word(
    id: j['id'] as String? ?? newId(),
    values: (j['values'] as List? ?? []).map((e) => '$e').toList(),
    learned: j['learned'] as bool? ?? false,
  );
}

class Collection {
  Collection({required this.id, required this.name, required this.fields, List<Word>? words})
    : words = words ?? [];
  final String id;
  String name;

  /// 1–3 lines shown on cards and widgets, in order.
  List<Field> fields;
  List<Word> words;

  int get learnedCount => words.where((w) => w.learned).length;

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'fields': fields.map((f) => f.toJson()).toList(),
    'words': words.map((w) => w.toJson()).toList(),
  };

  static Collection fromJson(Map<String, dynamic> j) => Collection(
    id: j['id'] as String? ?? newId(),
    name: j['name'] as String? ?? 'Bộ từ',
    fields: (j['fields'] as List? ?? []).map((e) => Field.fromJson(e as Map<String, dynamic>)).toList(),
    words: (j['words'] as List? ?? []).map((e) => Word.fromJson(e as Map<String, dynamic>)).toList(),
  );
}

final _rand = Random();
String newId() => '${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}${_rand.nextInt(1 << 20).toRadixString(36)}';

/// All collections, persisted as `collections.json` in the app files dir.
/// The native widget reads the same file and flips `learned` flags in it,
/// so the app reloads it whenever it comes back to the foreground.
class VocabStore extends ChangeNotifier {
  final List<Collection> collections = [];
  late File _file;

  static const exportFormat = 'vocab-collections';

  Future<void> load() async {
    final dir = await getApplicationSupportDirectory();
    _file = File('${dir.path}/collections.json');
    collections.clear();
    if (await _file.exists()) {
      try {
        collections.addAll(_parse(await _file.readAsString()));
      } catch (_) {}
    } else {
      collections.addAll(sampleCollections());
      await save();
    }
    notifyListeners();
  }

  static List<Collection> _parse(String text) {
    final j = jsonDecode(text);
    final list = j is Map ? (j['collections'] as List? ?? []) : (j as List);
    return list.map((e) => Collection.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<void> save() async {
    final tmp = File('${_file.path}.tmp');
    await tmp.writeAsString(exportJson(collections));
    await tmp.rename(_file.path);
    notifyListeners();
  }

  static String exportJson(List<Collection> list) => const JsonEncoder.withIndent(' ').convert({
    'format': exportFormat,
    'version': 1,
    'exportedAt': DateTime.now().toIso8601String(),
    'collections': list.map((c) => c.toJson()).toList(),
  });

  /// Imports collections from JSON text. Same id = restore (replace),
  /// new id = added. Returns (added, replaced).
  Future<(int, int)> importJson(String text) async {
    final incoming = _parse(text);
    var added = 0, replaced = 0;
    for (final c in incoming) {
      final i = collections.indexWhere((e) => e.id == c.id);
      if (i >= 0) {
        collections[i] = c;
        replaced++;
      } else {
        collections.add(c);
        added++;
      }
    }
    await save();
    return (added, replaced);
  }

  Collection? byId(String id) {
    for (final c in collections) {
      if (c.id == id) return c;
    }
    return null;
  }
}

List<Collection> sampleCollections() {
  Collection make(String name, List<Field> fields, List<List<String>> rows) => Collection(
    id: newId(),
    name: name,
    fields: fields,
    words: [for (final r in rows) Word(id: newId(), values: r)],
  );
  return [
    make('Chào hỏi 3 thứ tiếng (mẫu)', [Field('English', 'en'), Field('中文', 'zh'), Field('日本語', 'ja')], [
      ['Hello', '你好', 'こんにちは'],
      ['Thank you', '谢谢', 'ありがとう'],
      ['Good morning', '早上好', 'おはようございます'],
      ['Good night', '晚安', 'おやすみなさい'],
      ['Sorry', '对不起', 'ごめんなさい'],
      ['Goodbye', '再见', 'さようなら'],
      ['Yes', '是', 'はい'],
      ['No', '不是', 'いいえ'],
      ['Water', '水', '水 (みず)'],
      ['Friend', '朋友', '友達 (ともだち)'],
    ]),
    make('HSK 1 (mẫu)', [Field('Hán tự', 'zh'), Field('Pinyin', ''), Field('Nghĩa', 'vi')], [
      ['爱', 'ài', 'yêu'],
      ['八', 'bā', 'số tám'],
      ['爸爸', 'bàba', 'bố'],
      ['杯子', 'bēizi', 'cái cốc'],
      ['北京', 'Běijīng', 'Bắc Kinh'],
      ['本', 'běn', 'quyển (lượng từ)'],
      ['不客气', 'bú kèqi', 'không có gì'],
      ['菜', 'cài', 'món ăn, rau'],
      ['茶', 'chá', 'trà'],
      ['吃', 'chī', 'ăn'],
    ]),
    make('English basics (mẫu)', [Field('English', 'en'), Field('Nghĩa', 'vi')], [
      ['apple', 'quả táo'],
      ['beautiful', 'đẹp'],
      ['challenge', 'thử thách'],
      ['decision', 'quyết định'],
      ['environment', 'môi trường'],
      ['frequently', 'thường xuyên'],
      ['generous', 'hào phóng'],
      ['improve', 'cải thiện'],
    ]),
    make('日本語 N5 (mẫu)', [Field('日本語', 'ja'), Field('Romaji', ''), Field('Nghĩa', 'vi')], [
      ['猫', 'neko', 'con mèo'],
      ['犬', 'inu', 'con chó'],
      ['学校', 'gakkou', 'trường học'],
      ['先生', 'sensei', 'giáo viên'],
      ['食べる', 'taberu', 'ăn'],
      ['飲む', 'nomu', 'uống'],
      ['本', 'hon', 'sách'],
      ['電車', 'densha', 'tàu điện'],
    ]),
  ];
}
