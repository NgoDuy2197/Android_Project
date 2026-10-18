import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

class Feed {
  Feed({required this.id, required this.name, required this.url, this.enabled = true});
  final String id;
  String name;
  String url;
  bool enabled;

  Map<String, dynamic> toJson() => {'id': id, 'name': name, 'url': url, 'enabled': enabled};
  static Feed fromJson(Map<String, dynamic> j) => Feed(
    id: j['id'] as String,
    name: j['name'] as String? ?? '',
    url: j['url'] as String? ?? '',
    enabled: j['enabled'] as bool? ?? true,
  );
}

List<Feed> defaultFeeds() => [
  Feed(id: 'vne_top', name: 'VnExpress · Xem nhiều', url: 'https://vnexpress.net/rss/tin-xem-nhieu.rss'),
  Feed(id: 'vne_world', name: 'VnExpress · Thế giới', url: 'https://vnexpress.net/rss/the-gioi.rss'),
];

/// Every user setting. Saved to `config.json` in the files dir on each
/// change; the native fetcher reads the feed list and interval from it.
class AppConfig extends ChangeNotifier {
  List<Feed> feeds = defaultFeeds();
  String theme = 'yahoo';
  String? bgImage;
  double bgBlur = 0;
  bool showAll = false; // default filter: unread only
  bool notify = true;
  int intervalMin = 60;
  String geminiKey = ''; // Gemini API key for the AI summary (stored on the phone only)

  late File _file;

  Future<void> load() async {
    final dir = await getApplicationSupportDirectory();
    _file = File('${dir.path}/config.json');
    if (!await _file.exists()) {
      await save();
      return;
    }
    try {
      final j = jsonDecode(await _file.readAsString()) as Map<String, dynamic>;
      feeds = (j['feeds'] as List? ?? []).map((e) => Feed.fromJson(e as Map<String, dynamic>)).toList();
      theme = j['theme'] as String? ?? 'yahoo';
      bgImage = j['bgImage'] as String?;
      if (bgImage != null && !File(bgImage!).existsSync()) bgImage = null;
      bgBlur = (j['bgBlur'] as num?)?.toDouble() ?? 0;
      showAll = j['showAll'] as bool? ?? false;
      notify = j['notify'] as bool? ?? true;
      intervalMin = j['intervalMin'] as int? ?? 60;
      geminiKey = j['geminiKey'] as String? ?? '';
    } catch (_) {}
  }

  Future<void> save() async {
    final tmp = File('${_file.path}.tmp');
    await tmp.writeAsString(jsonEncode({
      'feeds': feeds.map((f) => f.toJson()).toList(),
      'theme': theme,
      'bgImage': bgImage,
      'bgBlur': bgBlur,
      'showAll': showAll,
      'notify': notify,
      'intervalMin': intervalMin,
      'geminiKey': geminiKey,
    }));
    await tmp.rename(_file.path);
    notifyListeners();
  }

  NewsTheme get palette => NewsTheme.byId(theme);
}

class NewsItem {
  NewsItem.fromJson(Map<String, dynamic> j)
    : id = j['id'] as String,
      title = j['title'] as String? ?? '',
      link = j['link'] as String? ?? '',
      summary = j['summary'] as String? ?? '',
      image = j['image'] as String? ?? '',
      source = j['source'] as String? ?? '',
      feedId = j['feedId'] as String? ?? '',
      published = DateTime.fromMillisecondsSinceEpoch((j['published'] as num?)?.toInt() ?? 0),
      read = j['read'] as bool? ?? false;

  final String id;
  final String title;
  final String link;
  final String summary;
  final String image;
  final String source;
  final String feedId;
  final DateTime published;
  bool read;
}

/// Reads `news.json` written by the native fetcher (single writer).
class NewsStore {
  static Future<(List<NewsItem>, DateTime?)> load() async {
    final dir = await getApplicationSupportDirectory();
    final f = File('${dir.path}/news.json');
    if (!await f.exists()) return (<NewsItem>[], null);
    try {
      final j = jsonDecode(await f.readAsString()) as Map<String, dynamic>;
      final items = (j['items'] as List? ?? []).map((e) => NewsItem.fromJson(e as Map<String, dynamic>)).toList()
        ..sort((a, b) => b.published.compareTo(a.published));
      final updated = (j['updatedAt'] as num?)?.toInt();
      return (items, updated == null || updated == 0 ? null : DateTime.fromMillisecondsSinceEpoch(updated));
    } catch (_) {
      return (<NewsItem>[], null);
    }
  }
}

class NewsTheme {
  const NewsTheme({
    required this.id,
    required this.name,
    required this.bg,
    required this.accent,
    required this.text,
    required this.sub,
    required this.dark,
    this.yahoo = false,
  });

  final String id;
  final String name;
  final List<Color> bg;
  final Color accent;
  final Color text;
  final Color sub;
  final bool dark;

  /// Classic Yahoo! look: purple header band and blue-violet headlines.
  final bool yahoo;

  Color get card => dark ? Colors.white.withValues(alpha: 0.09) : Colors.white.withValues(alpha: 0.66);
  Color get border => dark ? Colors.white.withValues(alpha: 0.14) : Colors.white.withValues(alpha: 0.9);

  static const all = [
    NewsTheme(
      id: 'yahoo',
      name: 'Yahoo cổ điển',
      bg: [Color(0xFFF4EFFF), Color(0xFFE6DCFF)],
      accent: Color(0xFF6001D2),
      text: Color(0xFF2B0A6B),
      sub: Color(0xFF6B6587),
      dark: false,
      yahoo: true,
    ),
    NewsTheme(
      id: 'rosy',
      name: 'Rosy bloom',
      bg: [Color(0xFFFFE6EE), Color(0xFFFFC7DA), Color(0xFFFFE9F1)],
      accent: Color(0xFFE0457B),
      text: Color(0xFF4A1028),
      sub: Color(0xFF8C5A6E),
      dark: false,
    ),
    NewsTheme(
      id: 'ocean',
      name: 'Ocean breeze',
      bg: [Color(0xFFE0F7FF), Color(0xFFB9E6FF), Color(0xFFE6FFFA)],
      accent: Color(0xFF0284C7),
      text: Color(0xFF0B2A3D),
      sub: Color(0xFF4B6B80),
      dark: false,
    ),
    NewsTheme(
      id: 'midnight',
      name: 'Midnight',
      bg: [Color(0xFF0F172A), Color(0xFF1E1B4B), Color(0xFF0B1022)],
      accent: Color(0xFFA78BFA),
      text: Color(0xFFF1F5F9),
      sub: Color(0xFF94A3B8),
      dark: true,
    ),
  ];

  static NewsTheme byId(String id) => all.firstWhere((t) => t.id == id, orElse: () => all.first);
}

String timeAgo(DateTime t) {
  final d = DateTime.now().difference(t);
  if (t.millisecondsSinceEpoch == 0) return '';
  if (d.inMinutes < 1) return 'vừa xong';
  if (d.inMinutes < 60) return '${d.inMinutes} phút trước';
  if (d.inHours < 24) return '${d.inHours} giờ trước';
  if (d.inDays < 7) return '${d.inDays} ngày trước';
  return '${t.day}/${t.month}/${t.year}';
}
