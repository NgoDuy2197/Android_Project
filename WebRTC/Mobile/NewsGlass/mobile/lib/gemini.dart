import 'dart:convert';
import 'dart:io';

import 'models.dart';

/// Summarises news headlines with Google Gemini (REST, no extra package).
class Gemini {
  static const model = 'gemini-2.5-flash-lite';
  static const _endpoint = 'https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent';
  static const maxTitles = 200;

  /// Returns the summary text. Throws [GeminiException] with a readable message.
  static Future<String> summarize(String apiKey, List<NewsItem> items) async {
    final list = items.take(maxTitles).toList();
    final lines = [
      for (var i = 0; i < list.length; i++) '${i + 1}. ${list[i].title} (${list[i].source})',
    ].join('\n');
    final prompt =
        'Dưới đây là ${list.length} tiêu đề tin tức mới nhất. Hãy tóm tắt những thông tin chính bằng tiếng Việt:\n'
        '- Nhóm theo chủ đề (vd: Thế giới, Kinh tế, Xã hội, Thể thao…), mỗi chủ đề một tiêu đề in đậm dạng **Chủ đề**.\n'
        '- Dưới mỗi chủ đề là các gạch đầu dòng "- " ngắn gọn, gộp các tin trùng nhau.\n'
        '- Cuối cùng thêm mục **Đáng chú ý nhất** với 1–3 ý.\n'
        '- Chỉ dựa vào các tiêu đề, không bịa thêm chi tiết.\n\n$lines';

    final client = HttpClient()..connectionTimeout = const Duration(seconds: 20);
    try {
      final req = await client.postUrl(Uri.parse(_endpoint));
      req.headers
        ..contentType = ContentType.json
        ..set('x-goog-api-key', apiKey);
      req.add(utf8.encode(jsonEncode({
        'contents': [
          {
            'parts': [
              {'text': prompt},
            ],
          },
        ],
        'generationConfig': {'temperature': 0.3},
      })));
      final res = await req.close().timeout(const Duration(seconds: 60));
      final body = await res.transform(utf8.decoder).join();
      final j = jsonDecode(body) as Map<String, dynamic>;
      if (res.statusCode != 200) {
        final msg = (j['error'] as Map?)?['message'] ?? 'HTTP ${res.statusCode}';
        throw GeminiException(res.statusCode == 400 || res.statusCode == 403 ? 'API key không hợp lệ: $msg' : '$msg');
      }
      final parts = (((j['candidates'] as List?)?.firstOrNull as Map?)?['content'] as Map?)?['parts'] as List?;
      final text = parts?.map((p) => (p as Map)['text'] ?? '').join().trim() ?? '';
      if (text.isEmpty) throw GeminiException('AI không trả về nội dung');
      return text;
    } on SocketException {
      throw GeminiException('Không có mạng');
    } on HandshakeException {
      throw GeminiException('Lỗi kết nối bảo mật');
    } on FormatException {
      throw GeminiException('Phản hồi không hợp lệ');
    } finally {
      client.close();
    }
  }
}

class GeminiException implements Exception {
  GeminiException(this.message);
  final String message;
  @override
  String toString() => message;
}