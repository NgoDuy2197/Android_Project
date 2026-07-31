import 'dart:convert';
import 'package:http/http.dart' as http;

import 'logger.dart';

/// Talks to an AI backend. Two providers:
///  - **local**: any OpenAI-compatible server (Ollama / LM Studio / llama.cpp)
///    at a configurable URL + HTTP method.
///  - **gemini**: Google Gemini via its native Generative Language API using an
///    API key (token).
class AiClient {
  String provider; // 'local' | 'gemini'
  String endpoint; // local: e.g. http://192.168.1.10:11434/v1/chat/completions
  String model; // local: qwen2.5:0.5b   gemini: gemini-1.5-flash
  String apiKey; // local: optional bearer   gemini: the API token
  String systemPrompt;
  String method; // local HTTP method

  AiClient({
    this.provider = 'local',
    this.endpoint = '',
    this.model = 'qwen2.5:0.5b',
    this.apiKey = '',
    this.systemPrompt =
        'Bạn là trợ lý vui tính, trả lời ngắn gọn bằng tiếng Việt.',
    this.method = 'POST',
  });

  static const methods = ['POST', 'GET', 'PUT', 'PATCH'];
  static const providers = ['local', 'gemini'];

  bool get isGemini => provider == 'gemini';

  bool get configured =>
      isGemini ? apiKey.trim().isNotEmpty : endpoint.trim().startsWith('http');

  String get _geminiModel {
    var m = model.trim();
    // Strip a pasted "models/" prefix and any stray/invisible characters
    // (spaces, colons, zero-width…) that break the model-name URL format.
    if (m.startsWith('models/')) m = m.substring('models/'.length);
    m = m.replaceAll(RegExp(r'[^A-Za-z0-9.\-]'), '');
    return m.isEmpty ? 'gemini-2.0-flash' : m;
  }

  /// Human-readable target (key redacted) for the diagnostics screen.
  String get resolvedUrl {
    if (isGemini) {
      return 'https://generativelanguage.googleapis.com/v1beta/models/'
          '$_geminiModel:generateContent?key=***';
    }
    var e = endpoint.trim();
    if (e.startsWith('http:') && !e.startsWith('http://')) {
      e = 'http://${e.substring('http:'.length)}';
    } else if (e.startsWith('https:') && !e.startsWith('https://')) {
      e = 'https://${e.substring('https:'.length)}';
    }
    e = e.replaceAll(RegExp(r'/+$'), '');
    final u = Uri.tryParse(e);
    final hasPath = u != null && u.path.isNotEmpty && u.path != '/';
    if (!hasPath) e = '$e/v1/chat/completions';
    return e;
  }

  /// Reachability probe. Returns (ok, human-readable detail).
  Future<(bool, String)> ping() async {
    if (!configured) return (false, 'Chưa cấu hình');
    try {
      if (isGemini) {
        final url = Uri.parse(
            'https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}');
        final res = await http.get(url).timeout(const Duration(seconds: 8));
        AppLog.instance.log('Gemini ping -> ${res.statusCode}');
        if (res.statusCode == 200) return (true, 'Gemini OK (key hợp lệ)');
        if (res.statusCode == 400 || res.statusCode == 403) {
          return (false, 'API key sai/không đủ quyền (HTTP ${res.statusCode})');
        }
        return (res.statusCode < 500, 'HTTP ${res.statusCode}');
      }
      final u = Uri.parse(resolvedUrl);
      final base = Uri(
          scheme: u.scheme, host: u.host, port: u.hasPort ? u.port : null);
      final res = await http.get(base).timeout(const Duration(seconds: 6));
      AppLog.instance.log('Ping $base -> ${res.statusCode}');
      return (res.statusCode < 500, 'HTTP ${res.statusCode} @ $base');
    } catch (e) {
      AppLog.instance.log('Ping lỗi: $e');
      return (false, 'Không kết nối được: $e');
    }
  }

  Future<String> ask(String question) async {
    if (!configured) {
      throw isGemini
          ? 'Chưa nhập API key Gemini.'
          : 'Chưa cấu hình địa chỉ máy chủ AI.';
    }
    return isGemini ? _askGemini(question) : _askOpenAi(question);
  }

  // --- Local / OpenAI-compatible --------------------------------------------
  Future<String> _askOpenAi(String question) async {
    final url = resolvedUrl;
    final m = (method.trim().isEmpty ? 'POST' : method.trim().toUpperCase());
    AppLog.instance.log('AI $m $url (model=$model)');
    final req = http.Request(m, Uri.parse(url));
    req.headers['Content-Type'] = 'application/json';
    if (apiKey.trim().isNotEmpty) {
      req.headers['Authorization'] = 'Bearer ${apiKey.trim()}';
    }
    req.body = jsonEncode({
      'model': model,
      'stream': false,
      'temperature': 0.7,
      'messages': [
        {'role': 'system', 'content': systemPrompt},
        {'role': 'user', 'content': question},
      ],
    });
    final streamed = await req.send().timeout(const Duration(seconds: 180));
    final res = await http.Response.fromStream(streamed);
    AppLog.instance.log('AI HTTP ${res.statusCode}');
    final bodyText = utf8.decode(res.bodyBytes);
    if (res.statusCode != 200) {
      throw 'Máy chủ trả lỗi ${res.statusCode}: ${_snippet(bodyText)}';
    }

    // Some OpenAI-compatible servers stream even with stream:false, sending
    // "data: {…}" SSE lines instead of one JSON object. Decode the last data
    // chunk in that case so we don't fail on an otherwise-successful response.
    final Map<String, dynamic> j;
    try {
      j = _decodeBody(bodyText);
    } catch (e) {
      AppLog.instance.log('AI body không phải JSON hợp lệ: ${_snippet(bodyText)}');
      throw 'Máy chủ trả về dữ liệu không đọc được (không phải JSON). Xem Log.';
    }

    // A 200 with an embedded error object (some proxies / models do this).
    final err = j['error'];
    if (err != null) {
      throw 'Máy chủ báo lỗi: ${err is Map ? (err['message'] ?? err) : err}';
    }

    final answer = _extractAnswer(j);
    if (answer != null && answer.trim().isNotEmpty) return answer.trim();

    // 200 but nothing usable — log the raw body so the cause is visible.
    AppLog.instance.log('Không trích được nội dung. Body: ${_snippet(bodyText, 600)}');
    throw 'Máy chủ trả lời nhưng không có nội dung. '
        'Kiểm tra tên model "$model" và xem Log để biết chi tiết.';
  }

  static String _snippet(String s, [int max = 200]) =>
      s.length > max ? '${s.substring(0, max)}…' : s;

  /// Decode a JSON body, tolerating SSE ("data: {…}") streaming responses by
  /// taking the last non-`[DONE]` data line.
  Map<String, dynamic> _decodeBody(String body) {
    final trimmed = body.trimLeft();
    if (trimmed.startsWith('{')) {
      return jsonDecode(body) as Map<String, dynamic>;
    }
    if (trimmed.startsWith('data:')) {
      Map<String, dynamic>? last;
      for (final raw in body.split('\n')) {
        final line = raw.trim();
        if (!line.startsWith('data:')) continue;
        final payload = line.substring('data:'.length).trim();
        if (payload.isEmpty || payload == '[DONE]') continue;
        try {
          last = jsonDecode(payload) as Map<String, dynamic>;
        } catch (_) {
          // Skip malformed chunks.
        }
      }
      if (last != null) return last;
    }
    // Fall back to a strict parse (will throw and be handled by the caller).
    return jsonDecode(body) as Map<String, dynamic>;
  }

  /// Pull the assistant text out of the many shapes real servers return:
  /// OpenAI chat (content String or content-parts list), reasoning models
  /// (reasoning_content), completion-style (choices[].text), streaming deltas,
  /// and Ollama's native /api/chat + /api/generate shapes.
  String? _extractAnswer(Map<String, dynamic> j) {
    final choices = j['choices'];
    if (choices is List && choices.isNotEmpty) {
      final first = choices[0];
      if (first is Map) {
        final msg = first['message'];
        if (msg is Map) {
          final c = _asText(msg['content']);
          if (c != null && c.trim().isNotEmpty) return c;
          final r = _asText(msg['reasoning_content']) ?? _asText(msg['reasoning']);
          if (r != null && r.trim().isNotEmpty) return r;
        }
        final t = _asText(first['text']);
        if (t != null && t.trim().isNotEmpty) return t;
        final delta = first['delta'];
        if (delta is Map) {
          final dc = _asText(delta['content']);
          if (dc != null && dc.trim().isNotEmpty) return dc;
        }
      }
    }
    // Ollama native /api/chat.
    final m = j['message'];
    if (m is Map) {
      final c = _asText(m['content']);
      if (c != null && c.trim().isNotEmpty) return c;
    }
    // Ollama /api/generate, or a plain top-level field.
    return _asText(j['response']) ?? _asText(j['content']) ?? _asText(j['text']);
  }

  /// Coerce a content value to text. Handles String and the OpenAI
  /// "content parts" list ([{type:text, text:…}, …]).
  String? _asText(dynamic v) {
    if (v is String) return v;
    if (v is List) {
      final sb = StringBuffer();
      for (final p in v) {
        if (p is String) {
          sb.write(p);
        } else if (p is Map) {
          final t = p['text'] ?? p['content'];
          if (t is String) sb.write(t);
        }
      }
      final s = sb.toString();
      return s.isEmpty ? null : s;
    }
    return null;
  }

  // --- Gemini native API -----------------------------------------------------
  Future<String> _askGemini(String question) async {
    final url = Uri.parse(
        'https://generativelanguage.googleapis.com/v1beta/models/'
        '$_geminiModel:generateContent?key=${apiKey.trim()}');
    AppLog.instance.log('Gemini POST model=$_geminiModel');
    final res = await http
        .post(
          url,
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({
            'systemInstruction': {
              'parts': [
                {'text': systemPrompt}
              ]
            },
            'contents': [
              {
                'role': 'user',
                'parts': [
                  {'text': question}
                ]
              }
            ],
            'generationConfig': {'temperature': 0.7},
          }),
        )
        .timeout(const Duration(seconds: 180));
    AppLog.instance.log('Gemini HTTP ${res.statusCode}');
    if (res.statusCode != 200) {
      throw 'Gemini lỗi ${res.statusCode}: ${res.body.length > 200 ? res.body.substring(0, 200) : res.body}';
    }
    final j = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
    final candidates = j['candidates'];
    if (candidates is List && candidates.isNotEmpty) {
      final parts = candidates[0]['content']?['parts'];
      if (parts is List) {
        final text = parts
            .map((p) => (p is Map && p['text'] is String) ? p['text'] : '')
            .join('');
        if (text.toString().trim().isNotEmpty) return text.toString().trim();
      }
      final reason = candidates[0]['finishReason'];
      if (reason != null) throw 'Gemini dừng: $reason';
    }
    final block = j['promptFeedback']?['blockReason'];
    if (block != null) throw 'Gemini chặn nội dung: $block';
    throw 'Không đọc được câu trả lời từ Gemini.';
  }
}
