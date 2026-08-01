import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:path_provider/path_provider.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'ai_client.dart';
import 'logger.dart';
import 'sfx.dart';

/// Bridge to native code (settings screens + native SpeechRecognizer).
const _nativeChannel = MethodChannel('voice_ai/native');

/// Stream of native speech-to-text events: {type: partial|final|ready|error, …}.
const _sttEvents = EventChannel('voice_ai/stt');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Surface any Dart-side error into the in-app log ("Xem log").
  FlutterError.onError = (d) {
    AppLog.instance.log('FlutterError: ${d.exceptionAsString()}');
    FlutterError.presentError(d);
  };
  WidgetsBinding.instance.platformDispatcher.onError = (e, s) {
    AppLog.instance.log('Uncaught: $e');
    return true;
  };
  final prefs = await SharedPreferences.getInstance();
  final idx = (prefs.getInt('appTheme') ?? AppTheme.light.index)
      .clamp(0, AppTheme.values.length - 1);
  runApp(VoiceAiApp(theme: ThemeController(AppTheme.values[idx])));
}

enum AppTheme { dark, light, win7, amoled, rose }

const appThemeNames = <AppTheme, String>{
  AppTheme.dark: 'Tối',
  AppTheme.light: 'Sáng (Facebook)',
  AppTheme.win7: 'Windows 7 / Yahoo',
  AppTheme.amoled: 'AMOLED đen',
  AppTheme.rose: 'Hồng pastel',
};

class ThemeController extends ChangeNotifier {
  AppTheme theme;
  ThemeController(this.theme);
  bool get isWin7 => theme == AppTheme.win7;
  Future<void> set(AppTheme t) async {
    theme = t;
    final p = await SharedPreferences.getInstance();
    await p.setInt('appTheme', t.index);
    notifyListeners();
  }
}

ThemeData appThemeData(AppTheme t) {
  switch (t) {
    case AppTheme.dark:
      return ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: const Color(0xFF0F1115),
        colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xFF8E4EC6), brightness: Brightness.dark),
        filledButtonTheme: _pillFilled(),
        elevatedButtonTheme: _pillElevated(),
        outlinedButtonTheme: _pillOutlined(),
      );
    case AppTheme.light:
      // Facebook style: brand blue on a light grey background, white surfaces.
      const fb = Color(0xFF1877F2);
      return ThemeData(
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF0F2F5),
        colorScheme: ColorScheme.fromSeed(
          seedColor: fb,
          brightness: Brightness.light,
        ).copyWith(
          primary: fb,
          surface: Colors.white,
          surfaceContainerHighest: const Color(0xFFE9EBEE),
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: fb,
          foregroundColor: Colors.white,
        ),
        filledButtonTheme: _pillFilled(),
        elevatedButtonTheme: _pillElevated(),
        outlinedButtonTheme: _pillOutlined(),
      );
    case AppTheme.amoled:
      // Pure black (OLED) with a neon cyan accent.
      const neon = Color(0xFF00E5C7);
      return ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: Colors.black,
        colorScheme: ColorScheme.fromSeed(
          seedColor: neon,
          brightness: Brightness.dark,
        ).copyWith(
          primary: neon,
          surface: const Color(0xFF0A0A0A),
          surfaceContainerHighest: const Color(0xFF161616),
        ),
        appBarTheme: const AppBarTheme(backgroundColor: Colors.black),
        filledButtonTheme: _pillFilled(),
        elevatedButtonTheme: _pillElevated(),
        outlinedButtonTheme: _pillOutlined(),
      );
    case AppTheme.rose:
      // Soft pastel pink, light.
      const rose = Color(0xFFE84C88);
      return ThemeData(
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFFFF1F6),
        colorScheme: ColorScheme.fromSeed(
          seedColor: rose,
          brightness: Brightness.light,
        ).copyWith(primary: rose, surface: Colors.white),
        appBarTheme: const AppBarTheme(
          backgroundColor: rose,
          foregroundColor: Colors.white,
        ),
        filledButtonTheme: _pillFilled(),
        elevatedButtonTheme: _pillElevated(),
        outlinedButtonTheme: _pillOutlined(),
      );
    case AppTheme.win7:
      // Classic Windows 7 Aero / Yahoo Messenger: Luna desktop blue-green,
      // glossy title-bar chrome, beveled window frames, pill (stadium) buttons.
      const aero = Color(0xFF1F6FD6);
      const yahoo = Color(0xFF6B2FA0);
      const frame = Color(0xFF24558C);
      const edge = Color(0xFF7BA3CC);
      const pill = StadiumBorder();
      return ThemeData(
        useMaterial3: false,
        brightness: Brightness.light,
        // Default Win7 wallpaper-ish teal-blue desktop.
        scaffoldBackgroundColor: const Color(0xFF8EB8D8),
        primaryColor: aero,
        colorScheme: const ColorScheme.light(
          primary: aero,
          secondary: yahoo,
          tertiary: Color(0xFF3D8B40),
          surface: Color(0xFFF5F9FD),
          onPrimary: Colors.white,
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: aero,
          foregroundColor: Colors.white,
          elevation: 4,
          centerTitle: false,
          titleTextStyle: TextStyle(
            color: Colors.white,
            fontSize: 17,
            fontWeight: FontWeight.w600,
            shadows: [Shadow(color: Colors.black38, blurRadius: 2)],
          ),
        ),
        cardTheme: const CardThemeData(
          color: Color(0xFFF8FBFF),
          elevation: 3,
          shape: RoundedRectangleBorder(
            side: BorderSide(color: Color(0xFF5A8ABB), width: 1.4),
            borderRadius: BorderRadius.all(Radius.circular(6)),
          ),
        ),
        dialogTheme: const DialogThemeData(
          backgroundColor: Color(0xFFEFF5FC),
          shape: RoundedRectangleBorder(
            side: BorderSide(color: frame, width: 1.6),
            borderRadius: BorderRadius.all(Radius.circular(8)),
          ),
        ),
        inputDecorationTheme: const InputDecorationTheme(
          filled: true,
          fillColor: Colors.white,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.all(Radius.circular(4)),
            borderSide: BorderSide(color: edge),
          ),
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: aero,
            foregroundColor: Colors.white,
            elevation: 2,
            shape: pill,
            side: const BorderSide(color: frame),
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
          ),
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            backgroundColor: aero,
            foregroundColor: Colors.white,
            shape: pill,
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            foregroundColor: aero,
            shape: pill,
            side: const BorderSide(color: edge),
          ),
        ),
        textButtonTheme: TextButtonThemeData(
          style: TextButton.styleFrom(foregroundColor: aero, shape: pill),
        ),
        dividerColor: const Color(0xFFAFC4DE),
      );
  }
}

FilledButtonThemeData _pillFilled() => FilledButtonThemeData(
      style: FilledButton.styleFrom(shape: const StadiumBorder()),
    );
ElevatedButtonThemeData _pillElevated() => ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(shape: const StadiumBorder()),
    );
OutlinedButtonThemeData _pillOutlined() => OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(shape: const StadiumBorder()),
    );

/// Accent colour + icon for the "thả tim" button, per theme.
({IconData icon, Color color, Color light}) themeHeartStyle(AppTheme t) {
  switch (t) {
    case AppTheme.dark:
      return (
        icon: Icons.favorite_rounded,
        color: const Color(0xFF9B5DE5),
        light: const Color(0xFFC9A7F5),
      );
    case AppTheme.light:
      // Facebook-style like.
      return (
        icon: Icons.thumb_up_alt_rounded,
        color: const Color(0xFF1877F2),
        light: const Color(0xFF5AA8FF),
      );
    case AppTheme.win7:
      // Classic Yahoo purple heart.
      return (
        icon: Icons.favorite_rounded,
        color: const Color(0xFF6B2FA0),
        light: const Color(0xFFC080E8),
      );
    case AppTheme.amoled:
      return (
        icon: Icons.favorite_rounded,
        color: const Color(0xFF00E5C7),
        light: const Color(0xFF7AFFF0),
      );
    case AppTheme.rose:
      return (
        icon: Icons.favorite_rounded,
        color: const Color(0xFFE84C88),
        light: const Color(0xFFFF9AC1),
      );
  }
}

/// Compact corner toast that pops in, then flies upward and fades out —
/// instead of the full-width black SnackBar bar at the bottom.
void showAppToast(BuildContext context, String message) {
  final overlay = Overlay.maybeOf(context);
  if (overlay == null) return;
  late OverlayEntry entry;
  entry = OverlayEntry(
    builder: (_) => _FlyingToast(
      message: message,
      onDone: () => entry.remove(),
    ),
  );
  overlay.insert(entry);
}

class VoiceAiApp extends StatelessWidget {
  final ThemeController theme;
  const VoiceAiApp({super.key, required this.theme});
  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: theme,
      builder: (context, _) => MaterialApp(
        title: 'Voice AI',
        debugShowCheckedModeBanner: false,
        theme: appThemeData(theme.theme),
        home: HomeScreen(theme: theme),
      ),
    );
  }
}

/// Strip Vietnamese diacritics + non-alphanumerics for lenient stop-word match.
String normalize(String s) {
  const from =
      'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ';
  const to =
      'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd';
  final buf = StringBuffer();
  for (final ch in s.toLowerCase().runes) {
    final c = String.fromCharCode(ch);
    final i = from.indexOf(c);
    final base = i >= 0 ? to[i] : c;
    if (RegExp(r'[a-z0-9]').hasMatch(base)) buf.write(base);
  }
  return buf.toString();
}

/// A readable name for a BCP-47 / locale id the recognizer reports (it only
/// gives raw tags like "vi-VN"). Falls back to the tag itself.
String sttLangName(String id) {
  final k = id.toLowerCase().replaceAll('-', '_');
  const names = {
    'vi_vn': 'Tiếng Việt (Việt Nam)',
    'vi': 'Tiếng Việt',
    'en_us': 'English (US)',
    'en_gb': 'English (UK)',
    'zh_cn': '中文 (简体, 中国)',
    'zh_tw': '中文 (繁體, 台灣)',
    'zh_hk': '中文 (香港)',
    'ja_jp': '日本語',
    'ko_kr': '한국어',
    'fr_fr': 'Français',
    'es_es': 'Español',
    'de_de': 'Deutsch',
    'th_th': 'ไทย',
  };
  return names[k] ?? id;
}

enum AiState { idle, listening, thinking, speaking }

enum DiagStatus { ok, warn, fail }

/// One row in the readiness self-check.
class DiagItem {
  final String label;
  final DiagStatus status;
  final String detail;
  const DiagItem(this.label, this.status, this.detail);
}

/// One turn shown in the conversation window.
class ChatMsg {
  final bool fromUser;
  String text;
  bool live; // still updating (user speaking / AI thinking)
  ChatMsg({required this.fromUser, this.text = '', this.live = false});
}

class HomeScreen extends StatefulWidget {
  final ThemeController theme;
  const HomeScreen({super.key, required this.theme});
  @override
  State<HomeScreen> createState() => HomeScreenState();
}

class HomeScreenState extends State<HomeScreen> {
  final _tts = FlutterTts();
  final _sfx = Sfx();
  final _ai = AiClient();
  final _rnd = Random();

  AiState _state = AiState.idle;
  String _committed = '';
  String _live = '';

  // Native speech-to-text event stream (SpeechRecognizer runs on the native
  // side, which handles continuous re-listening and engine fallback).
  StreamSubscription? _sttSub;

  // Auto-dialogue (loop) state.
  bool _loopActive = false;
  bool _loopSeeding = false; // waiting for the user's topic command
  int _loopTurn = 0;
  /// Spoken AI lines in the auto-dialogue. `second == false` → A (voice 1).
  final List<({bool second, String text})> _loopLines = [];
  /// Topic / tone set by the user's first command when starting auto-dialogue.
  /// Injected into every system prompt so both AIs stay on the same trend.
  String _loopTopic = '';
  // Clears buffered speech after configured silence (see silenceClearSec).
  Timer? _silenceTimer;

  // Config (accessed by SettingsScreen — same library)
  String stopWord = 'AI';
  // Optional "wake" word: when non-empty, capture only starts after it is heard,
  // so the question is the speech *between* the start word and the stop word.
  String startWord = '';
  String locale = 'vi_VN';
  double ttsRate = 0.6;
  String ttsEngine = ''; // '' = tự chọn (ưu tiên Google nếu có)
  String ttsLang = ''; // '' = mặc định của máy
  String ttsVoiceName = '';
  String ttsVoiceLocale = '';
  // Optional 2nd voice (used for the auto-dialogue's other speaker). Empty =
  // reuse voice 1.
  String ttsVoice2Name = '';
  String ttsVoice2Locale = '';
  // Auto-dialogue speaking styles — system prompt per speaker (A = voice 1,
  // B = voice 2). Always injected every turn to force the AI to comply.
  String loopPromptA =
      'Bạn là NGƯỜI A. Cách nói: vui vẻ, thân mật, câu ngắn bằng tiếng Việt. '
      'Mỗi lượt chỉ nói một câu.';
  String loopPromptB =
      'Bạn là NGƯỜI B. Cách nói: dí dỏm, hay nối ý, câu ngắn bằng tiếng Việt. '
      'Mỗi lượt chỉ nói một câu.';
  int maxMessages = 20;
  // Auto-dialogue: how many of the *opponent's* recent sentences to feed as
  // the user-message input (not mixed with own lines). 1 = only the last
  // opponent sentence.
  int loopContextCount = 1;
  // If no start-word is set: after this many seconds of silence without the
  // stop-word, drop what was heard (0 = off).
  int silenceClearSec = 0;

  // Conversation history shown in the chat window.
  final List<ChatMsg> _history = [];
  final ScrollController _chatScroll = ScrollController();

  ChatMsg? get _liveUser =>
      (_history.isNotEmpty && _history.last.fromUser && _history.last.live)
          ? _history.last
          : null;

  void _updateLiveUser(String text) {
    final m = _liveUser;
    if (m != null) {
      m.text = text;
    } else {
      _history.add(ChatMsg(fromUser: true, text: text, live: true));
    }
    _scrollChat();
  }

  void _finalizeLiveUser(String text) {
    final m = _liveUser;
    if (m != null) {
      m.text = text;
      m.live = false;
    } else {
      _history.add(ChatMsg(fromUser: true, text: text));
    }
  }

  void _removeLiveUser() {
    if (_liveUser != null) _history.removeLast();
  }

  void _scrollChat() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_chatScroll.hasClients) {
        _chatScroll.jumpTo(_chatScroll.position.maxScrollExtent);
      }
    });
  }

  @override
  void initState() {
    super.initState();
    _boot();
  }

  Future<void> _boot() async {
    await _sfx.init();
    final p = await SharedPreferences.getInstance();
    stopWord = p.getString('stopWord') ?? 'AI';
    startWord = p.getString('startWord') ?? '';
    locale = p.getString('locale') ?? 'vi_VN';
    ttsRate = p.getDouble('ttsRate') ?? 0.6;
    // Nudge the old too-slow default (0.5) up to the middle once.
    if ((ttsRate - 0.5).abs() < 0.0001) {
      ttsRate = 0.6;
      await p.setDouble('ttsRate', ttsRate);
    }
    ttsEngine = p.getString('ttsEngine') ?? '';
    ttsLang = p.getString('ttsLang') ?? '';
    ttsVoiceName = p.getString('ttsVoiceName') ?? '';
    ttsVoiceLocale = p.getString('ttsVoiceLocale') ?? '';
    ttsVoice2Name = p.getString('ttsVoice2Name') ?? '';
    ttsVoice2Locale = p.getString('ttsVoice2Locale') ?? '';
    loopPromptA = p.getString('loopPromptA') ?? loopPromptA;
    loopPromptB = p.getString('loopPromptB') ?? loopPromptB;
    maxMessages = p.getInt('maxMessages') ?? 20;
    loopContextCount = p.getInt('loopContextCount') ?? 1;
    silenceClearSec = p.getInt('silenceClearSec') ?? 0;
    _ai.endpoint = p.getString('endpoint') ?? '';
    _ai.provider = p.getString('provider') ?? 'local';
    _ai.model = p.getString('model') ?? 'qwen2.5:0.5b';
    _ai.apiKey = p.getString('apiKey') ?? '';
    _ai.method = p.getString('method') ?? 'POST';
    _ai.systemPrompt = p.getString('prompt') ??
        'Bạn là trợ lý vui tính, trả lời ngắn gọn bằng tiếng Việt.';
    await _tts.awaitSpeakCompletion(true);
    await applyTts();
    _sttSub = _sttEvents.receiveBroadcastStream().listen(_onNativeStt);
    AppLog.instance.log(
        'Boot: endpoint="${_ai.endpoint}" model="${_ai.model}" stopWord="$stopWord" locale="$locale"');
    if (mounted) setState(() {});
  }

  Future<void> saveCfg() async {
    final p = await SharedPreferences.getInstance();
    await p.setString('stopWord', stopWord);
    await p.setString('startWord', startWord);
    await p.setString('locale', locale);
    await p.setDouble('ttsRate', ttsRate);
    await p.setString('endpoint', _ai.endpoint);
    await p.setString('provider', _ai.provider);
    await p.setString('model', _ai.model);
    await p.setString('apiKey', _ai.apiKey);
    await p.setString('method', _ai.method);
    await p.setString('prompt', _ai.systemPrompt);
    await p.setString('ttsEngine', ttsEngine);
    await p.setString('ttsLang', ttsLang);
    await p.setString('ttsVoiceName', ttsVoiceName);
    await p.setString('ttsVoiceLocale', ttsVoiceLocale);
    await p.setString('ttsVoice2Name', ttsVoice2Name);
    await p.setString('ttsVoice2Locale', ttsVoice2Locale);
    await p.setString('loopPromptA', loopPromptA);
    await p.setString('loopPromptB', loopPromptB);
    await p.setInt('maxMessages', maxMessages);
    await p.setInt('loopContextCount', loopContextCount);
    await p.setInt('silenceClearSec', silenceClearSec);
    await applyTts();
    if (mounted) _trimHistory();
  }

  /// The TTS engine to actually use: the user's pick, else Google's if it's
  /// installed (its voices include Vietnamese, unlike many China-ROM default
  /// engines), else the system default.
  Future<String> _effectiveEngine() async {
    if (ttsEngine.isNotEmpty) return ttsEngine;
    try {
      final engines =
          (await _tts.getEngines as List).map((e) => e.toString()).toList();
      const preferred = 'com.google.android.tts';
      if (engines.contains(preferred)) return preferred;
    } catch (_) {}
    return '';
  }

  /// Apply the chosen TTS engine / language / voice + rate (voice 1).
  Future<void> applyTts() async {
    final eng = await _effectiveEngine();
    if (eng.isNotEmpty) {
      try {
        await _tts.setEngine(eng);
      } catch (_) {}
    }
    try {
      await _tts.setSpeechRate(ttsRate);
    } catch (_) {}
    await _selectVoice(second: false);
  }

  /// Set language + voice for a given side (voice 1, or voice 2 for the loop's
  /// other speaker). Voice 2 falls back to voice 1 when not chosen.
  Future<void> _selectVoice({required bool second}) async {
    final name = second && ttsVoice2Name.isNotEmpty ? ttsVoice2Name : ttsVoiceName;
    final vloc = second && ttsVoice2Name.isNotEmpty ? ttsVoice2Locale : ttsVoiceLocale;
    if (name.isNotEmpty) {
      try {
        await _tts.setVoice({'name': name, 'locale': vloc});
        return;
      } catch (_) {}
    }
    if (ttsLang.isNotEmpty) {
      try {
        await _tts.setLanguage(ttsLang);
      } catch (_) {}
    }
  }

  /// Speak [text] with voice 1 (default) or voice 2 (auto-dialogue's 2nd side).
  Future<void> _speak(String text, {bool second = false}) async {
    try {
      await _tts.setSpeechRate(ttsRate);
    } catch (_) {}
    await _selectVoice(second: second);
    await _tts.speak(text);
  }

  /// Speak a short sample with a specific voice (for the "nghe thử" buttons).
  /// Empty [name] = fall back to the chosen language/auto.
  Future<void> speakSampleVoice(String name, String locale) async {
    final eng = await _effectiveEngine();
    if (eng.isNotEmpty) {
      try {
        await _tts.setEngine(eng);
      } catch (_) {}
    }
    try {
      await _tts.setSpeechRate(ttsRate);
    } catch (_) {}
    if (name.isNotEmpty) {
      try {
        await _tts.setVoice({'name': name, 'locale': locale});
      } catch (_) {}
    } else if (ttsLang.isNotEmpty) {
      try {
        await _tts.setLanguage(ttsLang);
      } catch (_) {}
    }
    try {
      await _tts.stop();
    } catch (_) {}
    await _tts.speak('Xin chào, đây là giọng đọc thử.');
  }

  /// Keep only the most recent [maxMessages] bubbles on screen.
  void _trimHistory() {
    if (maxMessages <= 0) return;
    while (_history.length > maxMessages) {
      _history.removeAt(0);
    }
  }

  // ---------- Config export / import / profiles ----------

  Map<String, dynamic> exportConfig() => {
        'stopWord': stopWord,
        'startWord': startWord,
        'locale': locale,
        'ttsRate': ttsRate,
        'ttsEngine': ttsEngine,
        'ttsLang': ttsLang,
        'ttsVoiceName': ttsVoiceName,
        'ttsVoiceLocale': ttsVoiceLocale,
        'ttsVoice2Name': ttsVoice2Name,
        'ttsVoice2Locale': ttsVoice2Locale,
        'loopPromptA': loopPromptA,
        'loopPromptB': loopPromptB,
        'maxMessages': maxMessages,
        'loopContextCount': loopContextCount,
        'silenceClearSec': silenceClearSec,
        'endpoint': _ai.endpoint,
        'provider': _ai.provider,
        'model': _ai.model,
        'apiKey': _ai.apiKey,
        'method': _ai.method,
        'prompt': _ai.systemPrompt,
        'appTheme': widget.theme.theme.index,
      };

  /// Load a config map into the live app (used by profiles / import).
  Future<void> applyConfig(Map<String, dynamic> d) async {
    String s(String k, String def) => (d[k] ?? def).toString();
    int i(String k, int def) => d[k] is num ? (d[k] as num).toInt() : def;
    stopWord = s('stopWord', stopWord);
    startWord = s('startWord', startWord);
    locale = s('locale', locale);
    ttsRate = d['ttsRate'] is num ? (d['ttsRate'] as num).toDouble() : ttsRate;
    ttsEngine = s('ttsEngine', ttsEngine);
    ttsLang = s('ttsLang', ttsLang);
    ttsVoiceName = s('ttsVoiceName', ttsVoiceName);
    ttsVoiceLocale = s('ttsVoiceLocale', ttsVoiceLocale);
    ttsVoice2Name = s('ttsVoice2Name', ttsVoice2Name);
    ttsVoice2Locale = s('ttsVoice2Locale', ttsVoice2Locale);
    loopPromptA = s('loopPromptA', loopPromptA);
    loopPromptB = s('loopPromptB', loopPromptB);
    maxMessages = i('maxMessages', maxMessages);
    loopContextCount = i('loopContextCount', loopContextCount);
    silenceClearSec = i('silenceClearSec', silenceClearSec);
    _ai.endpoint = s('endpoint', _ai.endpoint);
    _ai.provider = s('provider', _ai.provider);
    _ai.model = s('model', _ai.model);
    _ai.apiKey = s('apiKey', _ai.apiKey);
    _ai.method = s('method', _ai.method);
    _ai.systemPrompt = s('prompt', _ai.systemPrompt);
    if (d['appTheme'] is int) {
      final idx = (d['appTheme'] as int).clamp(0, AppTheme.values.length - 1);
      await widget.theme.set(AppTheme.values[idx]);
    }
    await saveCfg();
    if (mounted) setState(() {});
  }

  Future<Map<String, dynamic>> loadProfiles() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString('configProfiles');
    if (raw == null) return {};
    try {
      return Map<String, dynamic>.from(jsonDecode(raw) as Map);
    } catch (_) {
      return {};
    }
  }

  Future<void> saveProfile(String name, Map<String, dynamic> cfg) async {
    final all = await loadProfiles();
    all[name] = cfg;
    final p = await SharedPreferences.getInstance();
    await p.setString('configProfiles', jsonEncode(all));
  }

  Future<void> deleteProfile(String name) async {
    final all = await loadProfiles();
    all.remove(name);
    final p = await SharedPreferences.getInstance();
    await p.setString('configProfiles', jsonEncode(all));
  }

  /// TTS engines installed on the device (package ids).
  Future<List<String>> ttsEngines() async {
    try {
      return (await _tts.getEngines as List).map((e) => e.toString()).toList();
    } catch (_) {
      return [];
    }
  }

  /// TTS languages + voices for the settings pickers, read from the EFFECTIVE
  /// engine (so Vietnamese shows even when the default engine lacks it).
  Future<
      ({
        List<String> languages,
        List<({String name, String locale})> voices,
        String engine,
      })> ttsInfo() async {
    final eng = await _effectiveEngine();
    if (eng.isNotEmpty) {
      try {
        await _tts.setEngine(eng);
        // Give the newly-selected engine a moment to initialise before we read
        // its language/voice lists (setEngine can return before it's ready).
        await Future.delayed(const Duration(milliseconds: 350));
      } catch (_) {}
    }
    List<String> langs = [];
    List<({String name, String locale})> voices = [];
    try {
      langs = (await _tts.getLanguages as List).map((e) => e.toString()).toList()
        ..sort();
    } catch (e) {
      AppLog.instance.log('ttsLanguages lỗi: $e');
    }
    try {
      final vs = await _tts.getVoices as List;
      voices = vs
          .map((v) {
            final m = Map<String, dynamic>.from(v as Map);
            return (
              name: (m['name'] ?? '').toString(),
              locale: (m['locale'] ?? '').toString(),
            );
          })
          .where((v) => v.name.isNotEmpty)
          .toList()
        ..sort((a, b) => a.locale.compareTo(b.locale));
    } catch (e) {
      AppLog.instance.log('ttsVoices lỗi: $e');
    }

    // Also ask the native engine directly (availableLanguages / voices) and
    // union it in — this surfaces installed voices the plugin can miss.
    try {
      final r = await _nativeChannel
          .invokeMapMethod<String, dynamic>('ttsInfo', {'engine': eng});
      final nativeLangs =
          (r?['languages'] as List?)?.map((e) => e.toString()) ?? const [];
      final have = langs.map((e) => e.toLowerCase()).toSet();
      for (final l in nativeLangs) {
        if (have.add(l.toLowerCase())) langs.add(l);
      }
      langs.sort();

      final nativeVoices = ((r?['voices'] as List?) ?? []).map((v) {
        final m = Map<String, dynamic>.from(v as Map);
        return (
          name: (m['name'] ?? '').toString(),
          locale: (m['locale'] ?? '').toString(),
        );
      }).where((v) => v.name.isNotEmpty);
      final haveV = voices.map((v) => v.name).toSet();
      for (final v in nativeVoices) {
        if (haveV.add(v.name)) voices.add(v);
      }
      voices.sort((a, b) => a.locale.compareTo(b.locale));
      AppLog.instance.log('TTS (engine="$eng"): ${langs.length} ngôn ngữ, '
          '${voices.length} giọng');
    } catch (e) {
      AppLog.instance.log('ttsInfo native lỗi: $e');
    }

    return (languages: langs, voices: voices, engine: eng);
  }

  /// Whether the device has a usable speech recogniser and the languages it
  /// supports. Merges two sources: the speech_to_text plugin's `locales()` and
  /// the native `sttDetails` (which asks the recognizer directly via
  /// ACTION_GET_LANGUAGE_DETAILS). The native path often finds languages —
  /// including Vietnamese — when the plugin reports nothing. On China-ROM phones
  /// without Google, both come back empty and [recognizers] shows what (if
  /// anything) is installed.
  Future<
      ({
        bool available,
        bool onDevice,
        List<({String id, String name})> locales,
        List<String> recognizers,
      })> sttInfo() async {
    bool available = false;
    bool onDevice = false;
    final map = <String, ({String id, String name})>{};

    // Native recognizer details (the authoritative Android API). We treat the
    // recogniser as usable when ANY engine is installed — even if the system has
    // no *default* recogniser set — because our native STT targets an engine
    // explicitly (that's why other apps work while the plugin reported "none").
    List<String> recognizers = [];
    try {
      final r = await _nativeChannel
          .invokeMapMethod<String, dynamic>('sttDetails')
          .timeout(const Duration(seconds: 5));
      if (r != null) {
        onDevice = r['onDevice'] == true;
        final langs =
            (r['languages'] as List?)?.map((e) => e.toString()) ?? const [];
        for (final t in langs) {
          final id = t.replaceAll('-', '_');
          map.putIfAbsent(
              id.toLowerCase(), () => (id: id, name: sttLangName(id)));
        }
        recognizers = ((r['recognizers'] as List?) ?? [])
            .map((e) {
              final m = Map<String, dynamic>.from(e as Map);
              return (m['label'] ?? m['package'] ?? '').toString();
            })
            .where((s) => s.isNotEmpty)
            .toList();
        available = r['available'] == true || recognizers.isNotEmpty;
        // Diagnostics — shows exactly what checkRecognitionSupport returned.
        AppLog.instance.log(
            'STT support: dùng được=${(r['languages'] as List?)?.length ?? 0} '
            '| đã cài=${(r['installed'] as List?) ?? []} '
            '| online=${((r['online'] as List?) ?? []).length} '
            '| tải được=${((r['downloadable'] as List?) ?? []).length} '
            '| lỗi=${r['supportError'] ?? '-'} '
            '| engine=${recognizers.join(",")}');
      }
    } catch (e) {
      AppLog.instance.log('sttDetails native lỗi: $e');
    }

    final out = map.values.toList()
      ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    return (
      available: available,
      onDevice: onDevice,
      locales: out,
      recognizers: recognizers,
    );
  }

  /// Full device probe for the Detect screen: what speech recognition + TTS the
  /// phone actually has. Each part is isolated so one failure doesn't hide the
  /// rest.
  Future<Map<String, dynamic>> detectDevice() async {
    final out = <String, dynamic>{};

    try {
      final info = await sttInfo();
      out['sttAvailable'] = info.available;
      out['sttOnDevice'] = info.onDevice;
      out['sttLocales'] =
          info.locales.map((l) => '${l.name} (${l.id})').toList()..sort();
      out['sttRecognizers'] = info.recognizers;
    } catch (e) {
      out['sttAvailable'] = false;
      out['sttLocales'] = <String>[];
      out['sttLocalesError'] = '$e';
      out['sttRecognizers'] = <String>[];
    }

    try {
      final langs = await _tts.getLanguages;
      out['ttsLanguages'] =
          (langs as List).map((e) => e.toString()).toList()..sort();
    } catch (e) {
      out['ttsLanguages'] = <String>[];
      out['ttsError'] = '$e';
    }

    AppLog.instance.log('Detect: sttAvailable=${out['sttAvailable']} '
        'sttLocales=${(out['sttLocales'] as List).length} '
        'ttsLangs=${(out['ttsLanguages'] as List).length}');
    return out;
  }

  /// Start/stop the keep-alive foreground service (background + screen-off).
  Future<void> _setBackground(bool on) async {
    try {
      await _nativeChannel
          .invokeMethod(on ? 'startBackground' : 'stopBackground');
    } on PlatformException catch (_) {}
  }

  Future<void> _start() async {
    final granted =
        await _nativeChannel.invokeMethod<bool>('requestMic') ?? false;
    if (!granted) {
      _setStatus('Chưa cấp quyền micro — hãy cấp quyền Micro cho app.');
      return;
    }
    _committed = '';
    _live = '';
    setState(() => _state = AiState.listening);
    await _setBackground(true);
    _setStatus('Đang nghe… (nói "$stopWord" khi hỏi xong)');
    await _sttEngineStart();
  }

  Future<void> _stop() async {
    setState(() => _state = AiState.idle);
    await _sttEngineStop();
    await _tts.stop();
    await _sfx.thinkLoopStop();
    await _setBackground(false);
    _setStatus('Đã dừng');
    AppLog.instance.log('Stopped by user');
  }

  Future<void> _sttEngineStart() async {
    try {
      await _nativeChannel.invokeMethod('sttStart', {'locale': locale});
    } on PlatformException catch (e) {
      AppLog.instance.log('sttStart lỗi: $e');
      _setStatus('Không mở được micro trên máy này.');
    }
  }

  Future<void> _sttEngineStop() async {
    try {
      await _nativeChannel.invokeMethod('sttStop');
    } on PlatformException catch (_) {}
  }

  void _snack(String s) {
    if (!mounted) return;
    showAppToast(context, s);
  }

  /// Trigger the system to download the on-device Vietnamese recognition pack
  /// (Android 13+). This is the fix for STT error 13 (ngôn ngữ chưa tải).
  Future<void> downloadSttModel() =>
      _nativeChannel.invokeMethod('sttDownloadModel', {'locale': locale});

  /// Ask the AI with a typed question (the "nhắn chữ" button). Stops listening,
  /// shows the question, then runs the normal ask+speak flow.
  Future<void> askTyped(String question) async {
    final q = question.trim();
    if (q.isEmpty) return;
    if (_state == AiState.listening) await _sttEngineStop();
    _finalizeLiveUser(q);
    setState(() => _state = AiState.thinking);
    await _ask(q);
  }

  /// Native STT events. The native side re-listens continuously, so here we
  /// only turn partial/final transcripts into the on-screen text + stop-word
  /// detection; there's no fragile restart loop to manage.
  void _onNativeStt(dynamic event) {
    if (event is! Map) return;
    final type = (event['type'] ?? '').toString();
    if (type == 'error') {
      final code = event['code'] is int ? event['code'] as int : -1;
      AppLog.instance.log('STT error code=$code (${_sttErrName(code)})');
      // Language pack missing — the common case here: TTS vi exists but the
      // on-device *recognition* vi pack was never downloaded.
      if (code == 12 || code == 13) {
        if (_state == AiState.listening) _stop();
        _snack('Thiếu gói NHẬN GIỌNG tiếng Việt (khác giọng đọc). '
            'Vào Cài đặt → "Tải gói ngay trong app".');
      } else if (code == 9) {
        if (_state == AiState.listening) _stop();
        _setStatus('Chưa cấp quyền micro.');
      }
      return;
    }
    if (type == 'engine') {
      AppLog.instance.log('STT engine: ${event['message']}');
      return;
    }
    if (type == 'dl') {
      AppLog.instance.log('STT tải gói: ${event['message']}');
      return;
    }
    if (type == 'ready') {
      AppLog.instance.log('STT: sẵn sàng nghe');
      return;
    }
    if (type != 'partial' && type != 'final') return;
    if (_state != AiState.listening) return;
    if (type == 'final') AppLog.instance.log('STT final: "${event['text']}"');
    _processTranscript((event['text'] ?? '').toString(), type == 'final');
  }

  String _sttErrName(int code) => switch (code) {
        1 => 'network timeout',
        2 => 'network',
        3 => 'audio',
        4 => 'server',
        5 => 'client',
        6 => 'speech timeout',
        7 => 'no match',
        8 => 'busy',
        9 => 'thiếu quyền micro',
        11 => 'server disconnected',
        12 => 'ngôn ngữ chưa hỗ trợ',
        13 => 'ngôn ngữ chưa tải',
        _ => 'khác',
      };

  void _processTranscript(String text, bool isFinal) {
    _armSilenceClear(); // fresh speech → reset the silence-to-clear timer
    _live = text;
    final combined = '$_committed $_live'.trim();

    // Honour the optional start word: the "body" is the speech we actually
    // treat as the question. Null = a start word is set but not heard yet.
    final body = _activeBody(combined);
    if (body == null) {
      if (_liveUser != null) _removeLiveUser();
      setState(() {});
      if (isFinal) {
        _committed = combined;
        _live = '';
      }
      return;
    }

    if (body.isNotEmpty) {
      _updateLiveUser(body);
    } else if (_liveUser != null) {
      _removeLiveUser();
    }
    setState(() {});

    final question = _extractQuestionIfStop(body);
    if (question != null) {
      final q = question.trim();
      if (q.isNotEmpty) {
        _silenceTimer?.cancel();
        _finalizeLiveUser(q);
        // In loop seeding, the first finished utterance sets the topic/trend.
        if (_loopSeeding) {
          _seedLoop(q);
          return;
        }
        setState(() => _state = AiState.thinking);
        AppLog.instance.log('Stop word detected. Q="$q"');
        _ask(q);
      } else {
        _removeLiveUser();
        _committed = '';
        _live = '';
        setState(() {});
      }
      return;
    }

    if (isFinal) {
      _committed = combined;
      _live = '';
    }
  }

  /// The portion of [combined] to treat as the (in-progress) question. With no
  /// start word it's the whole transcript; with a start word it's everything
  /// after the first whole-word occurrence of it, or null if not heard yet.
  String? _activeBody(String combined) {
    final normStart = normalize(startWord);
    if (normStart.isEmpty) return combined;
    final words = _words(combined);
    for (int i = 0; i < words.length; i++) {
      for (int k = 1; i + k <= words.length; k++) {
        final seg = normalize(words.sublist(i, i + k).join(''));
        if (seg == normStart) return words.sublist(i + k).join(' ');
        if (seg.length > normStart.length) break;
      }
    }
    return null;
  }

  /// If [body] ends with the stop word as one or more *whole* trailing words,
  /// returns [body] without them (the question). Returns null otherwise — this
  /// is what stops words that merely end in the same letters (e.g. "Lại",
  /// "Thoại" vs stop word "AI") from being mistaken for the stop word.
  String? _extractQuestionIfStop(String body) {
    final normStop = normalize(stopWord);
    if (normStop.isEmpty) return null;
    final words = _words(body);
    if (words.isEmpty) return null;
    for (int k = 1; k <= words.length; k++) {
      final tail = normalize(words.sublist(words.length - k).join(''));
      if (tail == normStop) return words.sublist(0, words.length - k).join(' ');
      if (tail.length > normStop.length) break;
    }
    return null;
  }

  List<String> _words(String s) =>
      s.trim().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();

  Future<void> _ask(String question) async {
    final aiMsg = ChatMsg(fromUser: false, live: true, text: '');
    setState(() {
      _state = AiState.thinking;
      _committed = '';
      _live = '';
      _history.add(aiMsg);
      _trimHistory();
    });
    _scrollChat();
    // Free the mic while thinking + speaking so it isn't captured back.
    await _sttEngineStop();
    _setStatus('Đang suy nghĩ…');
    _sfx.thinkStart();
    await _sfx.thinkLoopStart();
    try {
      final ans = await _ai.ask(question);
      await _sfx.thinkLoopStop();
      AppLog.instance.log('AI answer (${ans.length} chars)');
      setState(() {
        aiMsg.text = ans;
        aiMsg.live = false;
        _state = AiState.speaking;
      });
      _scrollChat();
      _setStatus('Đang trả lời…');
      await _speak(ans);
    } catch (e) {
      await _sfx.thinkLoopStop();
      _sfx.error();
      AppLog.instance.log('AI error: $e');
      setState(() {
        aiMsg.text = 'Lỗi: $e';
        aiMsg.live = false;
      });
      _setStatus('Lỗi khi hỏi AI');
      await _speak('Xin lỗi, có lỗi khi kết nối trợ lý.');
    }
    if (_state != AiState.idle) {
      setState(() => _state = AiState.listening);
      _committed = '';
      _live = '';
      _setStatus('Đang nghe… (nói "$stopWord" khi hỏi xong)');
      await _sttEngineStart();
    }
  }

  // ---------- Auto-dialogue loop ----------

  /// Start/stop the self-running conversation. Starting listens for the user's
  /// topic command (ended with the stop word). That command sets the topic /
  /// speaking trend for both AIs; then A and B alternate, each turn using its
  /// own speaking-style system prompt + the opponent's recent sentences as
  /// input, until stopped.
  Future<void> _toggleLoop() async {
    if (_loopActive) {
      _stopLoop();
      return;
    }
    _loopActive = true;
    _loopSeeding = true;
    _loopLines.clear();
    _loopTopic = '';
    _loopTurn = 0;
    _committed = '';
    _live = '';
    setState(() => _state = AiState.listening);
    await _setBackground(true);
    final granted =
        await _nativeChannel.invokeMethod<bool>('requestMic') ?? false;
    if (granted) {
      _setStatus('Nói chủ đề/xu hướng rồi nói "$stopWord"…');
      _snack('Tự thoại: nói chủ đề cho 2 bên, kết thúc bằng "$stopWord".');
      await _sttEngineStart();
    } else {
      _snack('Chưa có quyền mic — AI sẽ tự mở đầu (không có chủ đề).');
      _seedLoop('');
    }
  }

  void _stopLoop() {
    _loopActive = false;
    _loopSeeding = false;
    _loopTopic = '';
    _silenceTimer?.cancel();
    _sttEngineStop();
    _tts.stop();
    _sfx.thinkLoopStop();
    _setBackground(false);
    setState(() => _state = AiState.idle);
    _setStatus('Đã dừng tự thoại');
  }

  /// Capture the user's first command as the shared topic/trend (not as a
  /// spoken dialogue line), then let speaker A open.
  void _seedLoop(String seed) {
    _loopSeeding = false;
    _sttEngineStop();
    final s = seed.trim();
    _loopTopic = s;
    _loopTurn = 0; // A speaks first after the topic is set
    if (s.isNotEmpty) {
      setState(() {
        _history.add(ChatMsg(fromUser: true, text: 'Chủ đề: $s'));
        _trimHistory();
      });
      _scrollChat();
    }
    _loopStep();
  }

  /// Build the system prompt that is forced on every auto-dialogue reply:
  /// speaking style (config) + topic/trend (first user command).
  String _loopSystemPrompt(bool second) {
    final style = (second ? loopPromptB : loopPromptA).trim();
    final who = second ? 'B' : 'A';
    final buf = StringBuffer();
    if (style.isNotEmpty) {
      buf.writeln(style);
    } else {
      buf.writeln('Bạn là NGƯỜI $who trong cuộc trò chuyện tiếng Việt.');
    }
    buf.writeln();
    buf.writeln(
        'BẮT BUỘC: luôn tuân thủ cách nói / phong cách ở trên trong mọi câu '
        'trả lời. Chỉ nói nội dung thoại, không giải thích meta, không ghi '
        'tên người nói.');
    final topic = _loopTopic.trim();
    if (topic.isNotEmpty) {
      buf.writeln();
      buf.writeln('Chủ đề / xu hướng cuộc trò chuyện (bám theo): $topic');
    }
    return buf.toString().trim();
  }

  /// User-message input for one turn: only the opponent's recent sentences
  /// (count = [loopContextCount]), never own lines or the topic command.
  String _loopUserInput(bool second) {
    final n = loopContextCount < 1 ? 1 : loopContextCount;
    // Opponent of the speaker about to talk.
    final opponentIsSecond = !second;
    final opponent = _loopLines
        .where((l) => l.second == opponentIsSecond)
        .map((l) => l.text)
        .toList();
    final recent = opponent.length > n
        ? opponent.sublist(opponent.length - n)
        : opponent;

    if (recent.isEmpty) {
      final topic = _loopTopic.trim();
      if (topic.isNotEmpty) {
        return 'Bắt đầu cuộc trò chuyện theo chủ đề đã cho. '
            'Nói câu mở đầu (1 câu ngắn, tiếng Việt):';
      }
      return 'Bắt đầu một cuộc trò chuyện. '
          'Nói câu mở đầu (1 câu ngắn, tiếng Việt):';
    }
    if (recent.length == 1) {
      return 'Đối phương vừa nói: "${recent.first}"\n\n'
          'Dựa vào câu đó, nói lượt tiếp theo (1 câu ngắn, tiếng Việt, '
          'KHÔNG ghi tên người nói):';
    }
    final bullets = recent.map((t) => '- "$t"').join('\n');
    return 'Đối phương vừa nói (${recent.length} câu gần nhất):\n$bullets\n\n'
        'Dựa vào các câu đó, nói lượt tiếp theo (1 câu ngắn, tiếng Việt, '
        'KHÔNG ghi tên người nói):';
  }

  Future<String> _loopGenerate(String context, bool second) async {
    final saved = _ai.systemPrompt;
    // Always overwrite system prompt so the speaking style is enforced.
    _ai.systemPrompt = _loopSystemPrompt(second);
    try {
      return await _ai.ask(context);
    } finally {
      _ai.systemPrompt = saved;
    }
  }

  Future<void> _loopStep() async {
    if (!_loopActive) return;
    setState(() => _state = AiState.thinking);
    // No "beep" during auto-dialogue.
    final second = _loopTurn.isOdd; // alternate speaker/voice each turn
    final ctx = _loopUserInput(second);
    String line;
    try {
      line = (await _loopGenerate(ctx, second)).trim();
    } catch (e) {
      if (!_loopActive) return;
      AppLog.instance.log('Loop AI lỗi: $e');
      _snack('Lỗi AI khi tự thoại: $e');
      _stopLoop();
      return;
    }
    if (!_loopActive) return;
    if (line.isEmpty) line = '…';
    setState(() {
      _history.add(ChatMsg(fromUser: second, text: line));
      _trimHistory();
      _state = AiState.speaking;
    });
    _scrollChat();
    _loopLines.add((second: second, text: line));
    if (_loopLines.length > 40) _loopLines.removeAt(0);
    _loopTurn++;
    await _speak(line, second: second);
    if (!_loopActive) return;
    await Future.delayed(const Duration(milliseconds: 350));
    if (_loopActive) _loopStep();
  }

  /// (Re)arm the silence-to-clear timer: when no start word is set and the user
  /// goes quiet for [silenceClearSec]s without the stop word, drop what was
  /// heard so stray speech doesn't pile up.
  void _armSilenceClear() {
    _silenceTimer?.cancel();
    if (silenceClearSec <= 0 || startWord.trim().isNotEmpty || _loopSeeding) {
      return;
    }
    _silenceTimer = Timer(Duration(seconds: silenceClearSec), () {
      if (_state == AiState.listening &&
          (_committed.isNotEmpty || _live.isNotEmpty)) {
        _committed = '';
        _live = '';
        if (_liveUser != null) _removeLiveUser();
        setState(() {});
        AppLog.instance.log('Im lặng ${silenceClearSec}s — đã xoá nội dung nghe.');
      }
    });
  }

  void _setStatus(String s) {
    AppLog.instance.log('Trạng thái: $s');
  }

  /// Readiness self-check as discrete steps. The Diagnostics screen runs them
  /// one at a time so it can show the current step + how long it's taking.
  List<(String, Future<DiagItem> Function())> diagSteps() => [
        ('Nhận diện giọng nói', _diagStt),
        ('Quyền micro', _diagMic),
        ('Ngôn ngữ nghe ($locale)', _diagLocale),
        ('Giọng đọc (TTS) vi-VN', _diagTts),
        ('Cấu hình máy chủ AI', _diagAiConfig),
        ('Kết nối máy chủ AI', _diagAiPing),
      ];

  Future<DiagItem> _diagStt() async {
    try {
      final info = await sttInfo();
      final detail = info.recognizers.isEmpty
          ? 'Không có engine nhận giọng nào (cài "Speech Services by Google")'
          : 'Dùng engine: ${info.recognizers.first}';
      return DiagItem('Nhận diện giọng nói',
          info.available ? DiagStatus.ok : DiagStatus.fail, detail);
    } catch (e) {
      return DiagItem('Nhận diện giọng nói', DiagStatus.fail, '$e');
    }
  }

  Future<DiagItem> _diagMic() async {
    try {
      final has = await _nativeChannel.invokeMethod<bool>('micGranted') ?? false;
      return DiagItem(
        'Quyền micro',
        has ? DiagStatus.ok : DiagStatus.fail,
        has ? 'Đã cấp' : 'Chưa cấp — bấm Bắt đầu để cấp, hoặc vào Cài đặt ứng dụng',
      );
    } catch (e) {
      return DiagItem('Quyền micro', DiagStatus.warn, '$e');
    }
  }

  Future<DiagItem> _diagLocale() async {
    try {
      // Uses the merged detection (plugin + native ACTION_GET_LANGUAGE_DETAILS),
      // so a language the plugin misses but the recognizer supports still counts.
      final info = await sttInfo();
      final want = locale.toLowerCase().replaceAll('-', '_');
      final ids =
          info.locales.map((l) => l.id.toLowerCase().replaceAll('-', '_'));
      final exact = ids.any((id) => id == want);
      final anyVi = ids.any((id) => id.startsWith('vi'));
      if (exact) {
        return DiagItem('Ngôn ngữ nghe ($locale)', DiagStatus.ok, 'Máy hỗ trợ');
      } else if (anyVi) {
        return DiagItem('Ngôn ngữ nghe ($locale)', DiagStatus.warn,
            'Không thấy đúng "$locale" nhưng máy có tiếng Việt khác');
      }
      final hint = info.recognizers.isEmpty
          ? 'Máy không có bộ nhận giọng nói (máy nội địa Trung thiếu Google). Cài "Speech Services by Google".'
          : 'Chưa có gói tiếng Việt. Mở Cài đặt hệ thống để tải, hoặc cài "Speech Services by Google".';
      return DiagItem('Ngôn ngữ nghe ($locale)', DiagStatus.fail, hint);
    } catch (e) {
      return DiagItem('Ngôn ngữ nghe ($locale)', DiagStatus.warn, '$e');
    }
  }

  Future<DiagItem> _diagTts() async {
    try {
      final r = await _tts.isLanguageAvailable('vi-VN');
      final ok = r == true || r == 1 || r.toString() == 'true';
      return DiagItem(
        'Giọng đọc (TTS) vi-VN',
        ok ? DiagStatus.ok : DiagStatus.warn,
        ok ? 'Khả dụng' : 'Có thể chưa có giọng tiếng Việt trên máy',
      );
    } catch (e) {
      return DiagItem('Giọng đọc (TTS) vi-VN', DiagStatus.warn, '$e');
    }
  }

  Future<DiagItem> _diagAiConfig() async {
    if (!_ai.configured) {
      return const DiagItem(
          'Cấu hình máy chủ AI', DiagStatus.fail, 'Chưa nhập địa chỉ máy chủ');
    }
    return DiagItem('Cấu hình máy chủ AI', DiagStatus.ok, _ai.resolvedUrl);
  }

  Future<DiagItem> _diagAiPing() async {
    if (!_ai.configured) {
      return const DiagItem('Kết nối máy chủ AI', DiagStatus.warn,
          'Bỏ qua — chưa cấu hình địa chỉ');
    }
    final (ok, detail) = await _ai.ping();
    return DiagItem(
        'Kết nối máy chủ AI', ok ? DiagStatus.ok : DiagStatus.fail, detail);
  }

  @override
  void dispose() {
    _sttSub?.cancel();
    _silenceTimer?.cancel();
    _loopActive = false;
    _chatScroll.dispose();
    _nativeChannel.invokeMethod('sttStop').catchError((_) => null);
    _nativeChannel.invokeMethod('stopBackground').catchError((_) => null);
    _tts.stop();
    _sfx.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final running = _state != AiState.idle;
    final cs = Theme.of(context).colorScheme;
    final win7 = widget.theme.isWin7;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Voice AI'),
        flexibleSpace: widget.theme.isWin7
            ? const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    // Classic Aero title-bar: bright glass → deep Luna blue.
                    colors: [
                      Color(0xFFA8D4F5),
                      Color(0xFF4A9BE0),
                      Color(0xFF1A5EB5),
                    ],
                    stops: [0.0, 0.45, 1.0],
                  ),
                ),
              )
            : null,
        actions: [
          IconButton(
            tooltip: 'QR code',
            icon: const Icon(Icons.qr_code_2),
            onPressed: _showQrTool,
          ),
          if (_history.isNotEmpty)
            IconButton(
              tooltip: 'Xoá hội thoại',
              icon: const Icon(Icons.delete_sweep_outlined),
              onPressed: () => setState(_history.clear),
            ),
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: running ? null : _openSettings,
          ),
        ],
      ),
      body: Container(
        decoration: win7
            ? const BoxDecoration(
                // Classic Win7 desktop wash.
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0xFFB7D4EA),
                    Color(0xFF7EAFD4),
                    Color(0xFF5E9AC4),
                  ],
                ),
              )
            : null,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
          child: Column(
            children: [
              // Conversation window — the whole screen above the toggle.
              Expanded(
                child: Container(
                  width: double.infinity,
                  decoration: win7
                      ? BoxDecoration(
                          color: const Color(0xFFF8FBFF),
                          borderRadius: BorderRadius.circular(6),
                          border: Border.all(
                              color: const Color(0xFF3A6EA5), width: 2),
                          boxShadow: const [
                            BoxShadow(
                              color: Color(0x55000000),
                              blurRadius: 8,
                              offset: Offset(0, 3),
                            ),
                          ],
                        )
                      : BoxDecoration(
                          color: cs.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(color: cs.outlineVariant),
                        ),
                  clipBehavior: Clip.antiAlias,
                  child: Column(
                    children: [
                      if (win7)
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.symmetric(
                              horizontal: 10, vertical: 6),
                          decoration: const BoxDecoration(
                            gradient: LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [
                                Color(0xFF9ECFF3),
                                Color(0xFF3A86D0),
                                Color(0xFF1F5EAE),
                              ],
                            ),
                          ),
                          child: const Text(
                            'Hội thoại',
                            style: TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                              shadows: [
                                Shadow(color: Colors.black38, blurRadius: 1)
                              ],
                            ),
                          ),
                        ),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 8, vertical: 8),
                          child: _history.isEmpty
                              ? Center(
                                  child: Text('Hội thoại sẽ hiện ở đây…',
                                      style: TextStyle(
                                          color: cs.onSurfaceVariant)),
                                )
                              : ListView.builder(
                                  controller: _chatScroll,
                                  itemCount: _history.length,
                                  itemBuilder: (_, i) => _bubble(_history[i]),
                                ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 10),
              // Two controls side by side: record (mic) and type (opens a popup).
              Row(
                children: [
                  Expanded(
                    child: _controlButton(
                      onTap: _loopActive
                          ? null
                          : (running ? _stop : _start),
                      icon: running ? Icons.stop_rounded : Icons.mic_rounded,
                      label: running ? 'Dừng' : 'Nói',
                      color: running ? const Color(0xFFE5484D) : cs.primary,
                      win7: win7,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _controlButton(
                      onTap: _loopActive ? null : _showTextDialog,
                      icon: Icons.keyboard_rounded,
                      label: 'Nhắn chữ',
                      color: win7 ? const Color(0xFF5B7CA6) : cs.secondary,
                      win7: win7,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              // Auto-talk (shrunk) + heart side-by-side so they don't overlap.
              Row(
                children: [
                  Expanded(
                    child: _controlButton(
                      onTap: _toggleLoop,
                      icon: _loopActive
                          ? Icons.stop_rounded
                          : Icons.forum_rounded,
                      label: _loopActive ? 'Dừng tự thoại' : 'Tự thoại',
                      color: _loopActive
                          ? const Color(0xFFE5484D)
                          : (win7 ? const Color(0xFF6B2FA0) : cs.tertiary),
                      win7: win7,
                      height: 54,
                    ),
                  ),
                  const SizedBox(width: 10),
                  _heartButton(),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// One of the big bottom controls — pill-shaped (stadium) for every theme.
  /// Win7 keeps the classic hard gloss split + blue border. A null [onTap]
  /// renders it dimmed & disabled. Springs down when pressed.
  Widget _controlButton({
    required VoidCallback? onTap,
    required IconData icon,
    required String label,
    required Color color,
    required bool win7,
    double height = 58,
  }) {
    final light = Color.lerp(color, Colors.white, win7 ? 0.42 : 0.35)!;
    final dark = Color.lerp(color, Colors.black, 0.22)!;
    // Fully pill / "viên thuốc" rounding on every theme.
    final radius = BorderRadius.circular(height);
    return _PressPop(
      onTap: onTap,
      radius: radius,
      builder: (pressed) => DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: radius,
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            // Hard 50/50 gloss split for the retro win7 look; smooth otherwise.
            colors: win7 ? [light, light, color, color] : [light, color, dark],
            stops: win7 ? const [0.0, 0.5, 0.5, 1.0] : const [0.0, 0.55, 1.0],
          ),
          border: win7
              ? Border.all(color: const Color(0xFF24558C), width: 1.5)
              : Border.all(color: Colors.white.withValues(alpha: 0.25)),
          boxShadow: win7
              ? [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: pressed ? 0.18 : 0.28),
                    blurRadius: pressed ? 2 : 5,
                    offset: Offset(0, pressed ? 1 : 3),
                  ),
                ]
              : [
                  BoxShadow(
                    color: color.withValues(alpha: pressed ? 0.25 : 0.45),
                    blurRadius: pressed ? 6 : 16,
                    spreadRadius: pressed ? -2 : 0,
                    offset: Offset(0, pressed ? 2 : 6),
                  ),
                ],
        ),
        child: SizedBox(
          height: height,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: Colors.white, size: 24),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  label,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      color: Colors.white,
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                      shadows: [
                        Shadow(color: Colors.black26, blurRadius: 2)
                      ]),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Theme-coloured reaction button — every tap pops that same icon flying up.
  Widget _heartButton() {
    final style = themeHeartStyle(widget.theme.theme);
    return _PressPop(
      onTap: _popHeart,
      radius: BorderRadius.circular(40),
      child: Container(
        width: 54,
        height: 54,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [style.light, style.color],
          ),
          border: Border.all(color: Colors.white.withValues(alpha: 0.6), width: 2),
          boxShadow: [
            BoxShadow(
                color: style.color.withValues(alpha: 0.55),
                blurRadius: 14,
                offset: const Offset(0, 5)),
          ],
        ),
        child: Icon(style.icon, color: Colors.white, size: 26),
      ),
    );
  }

  /// Spawns one floating icon (matching the heart button) via the overlay.
  void _popHeart() {
    final overlay = Overlay.of(context);
    final media = MediaQuery.of(context);
    final style = themeHeartStyle(widget.theme.theme);
    // Start near the bottom-right (where the button lives), with a little spread.
    final startX = media.size.width -
        70 -
        _rnd.nextDouble() * 40 +
        (_rnd.nextDouble() - 0.5) * 20;
    late OverlayEntry entry;
    entry = OverlayEntry(
      builder: (_) => _FloatingHeart(
        startX: startX,
        screenHeight: media.size.height,
        seed: _rnd.nextInt(1 << 31),
        icon: style.icon,
        baseColor: style.color,
        onDone: () => entry.remove(),
      ),
    );
    overlay.insert(entry);
  }

  /// QR tool popup: type text → "QRcode" shows the QR; "Quét QRcode" opens the
  /// camera scanner and shows the result (short preview + Copy for the full text).
  Future<void> _showQrTool() async {
    final ctrl = TextEditingController();
    await showDialog<void>(
      context: context,
      builder: (ctx) {
        String? qrData; // showing a generated QR
        String? scanned; // full scanned text
        return StatefulBuilder(builder: (ctx, setD) {
          Widget content;
          if (qrData != null) {
            content = Column(mainAxisSize: MainAxisSize.min, children: [
              Container(
                color: Colors.white,
                padding: const EdgeInsets.all(10),
                child: QrImageView(data: qrData!, size: 232),
              ),
              const SizedBox(height: 8),
              Text(qrData!,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12.5)),
            ]);
          } else if (scanned != null) {
            final full = scanned!;
            final short = full.length > 20 ? '${full.substring(0, 20)}...' : full;
            content = Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Nội dung quét được:'),
                const SizedBox(height: 6),
                SelectableText(short,
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 10),
                FilledButton.icon(
                  onPressed: () async {
                    await Clipboard.setData(ClipboardData(text: full));
                    _snack('Đã copy toàn bộ nội dung');
                  },
                  icon: const Icon(Icons.copy),
                  label: const Text('Copy'),
                ),
              ],
            );
          } else {
            content = TextField(
              controller: ctrl,
              minLines: 2,
              maxLines: 6,
              autofocus: true,
              decoration: const InputDecoration(
                hintText: 'Nhập text để tạo QR…',
                border: OutlineInputBorder(),
                contentPadding:
                    EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              ),
            );
          }
          final showActions = qrData == null && scanned == null;
          final screenW = MediaQuery.of(ctx).size.width;
          return AlertDialog(
            insetPadding:
                const EdgeInsets.symmetric(horizontal: 12, vertical: 24),
            title: const Text('QR code'),
            content: SizedBox(
              width: screenW * 0.92,
              child: SingleChildScrollView(child: content),
            ),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(ctx),
                  child: const Text('Đóng')),
              if (!showActions)
                TextButton(
                  onPressed: () => setD(() {
                    qrData = null;
                    scanned = null;
                  }),
                  child: const Text('Lại'),
                ),
              if (showActions) ...[
                OutlinedButton.icon(
                  onPressed: () async {
                    final t = await _scanQr();
                    if (t != null) setD(() => scanned = t);
                  },
                  icon: const Icon(Icons.qr_code_scanner),
                  label: const Text('Quét QRcode'),
                ),
                FilledButton.icon(
                  onPressed: () {
                    final t = ctrl.text.trim();
                    if (t.isEmpty) {
                      _snack('Nhập text trước đã.');
                      return;
                    }
                    setD(() => qrData = t);
                  },
                  icon: const Icon(Icons.qr_code_2),
                  label: const Text('QRcode'),
                ),
              ],
            ],
          );
        });
      },
    );
    ctrl.dispose();
  }

  /// Full-screen camera scanner; returns the first decoded QR text (or null).
  /// Requests CAMERA via the native bridge first — mobile_scanner alone often
  /// reports "permission denied" even after the user already granted it.
  Future<String?> _scanQr() async {
    try {
      final granted =
          await _nativeChannel.invokeMethod<bool>('requestCamera') ?? false;
      if (!granted) {
        _snack('Chưa cấp quyền Camera — mở Cài đặt ứng dụng để bật.');
        return null;
      }
    } catch (e) {
      AppLog.instance.log('requestCamera lỗi: $e');
    }
    if (!mounted) return null;
    return Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const QrScannerPage()),
    );
  }

  /// Popup to type a question (the "Nhắn chữ" button) with its own Send button.
  Future<void> _showTextDialog() async {
    final ctrl = TextEditingController();
    final win7 = widget.theme.isWin7;
    final screenW = MediaQuery.of(context).size.width;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        // Wider dialog so the text field uses most of the screen width.
        insetPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 24),
        shape: win7
            ? const RoundedRectangleBorder(
                borderRadius: BorderRadius.all(Radius.circular(10)))
            : null,
        title: const Text('Nhắn chữ cho AI'),
        content: SizedBox(
          width: screenW * 0.92,
          child: TextField(
            controller: ctrl,
            autofocus: true,
            minLines: 2,
            maxLines: 8,
            textInputAction: TextInputAction.send,
            decoration: const InputDecoration(
              hintText: 'Nhập nội dung…',
              border: OutlineInputBorder(),
              contentPadding:
                  EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            ),
            onSubmitted: (_) {
              final t = ctrl.text;
              Navigator.pop(ctx);
              _submitTyped(t);
            },
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Huỷ')),
          FilledButton.icon(
            onPressed: () {
              final t = ctrl.text;
              Navigator.pop(ctx);
              _submitTyped(t);
            },
            icon: const Icon(Icons.send_rounded),
            label: const Text('Gửi'),
          ),
        ],
      ),
    );
    ctrl.dispose();
  }

  void _submitTyped(String t) {
    if (t.trim().isEmpty) return;
    askTyped(t);
  }

  Widget _bubble(ChatMsg m) {
    final cs = Theme.of(context).colorScheme;
    final isUser = m.fromUser;
    final thinking = !isUser && m.live && m.text.isEmpty;
    final win7 = widget.theme.isWin7;

    // Win7/Yahoo: glossy blue user bubbles, white bordered AI bubbles.
    final Color bg = win7
        ? (isUser ? const Color(0xFF2E86DE) : Colors.white)
        : (isUser ? cs.primary : cs.secondaryContainer);
    final Color fg = win7
        ? (isUser ? Colors.white : const Color(0xFF15324B))
        : (isUser ? cs.onPrimary : cs.onSecondaryContainer);
    final BoxDecoration deco = win7
        ? BoxDecoration(
            gradient: isUser
                ? const LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0xFF5AA7EA), Color(0xFF1F6FD6)],
                  )
                : const LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0xFFFFFFFF), Color(0xFFEAF3FB)],
                  ),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
                color: isUser
                    ? const Color(0xFF1C5FB0)
                    : const Color(0xFF9CC3E8)),
          )
        : BoxDecoration(color: bg, borderRadius: BorderRadius.circular(14));

    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        constraints:
            BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.78),
        decoration: deco,
        child: thinking
            ? Row(mainAxisSize: MainAxisSize.min, children: [
                const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2)),
                const SizedBox(width: 10),
                Text('Đang suy nghĩ…', style: TextStyle(color: fg)),
              ])
            : Text(
                m.text,
                style: TextStyle(
                  fontSize: 16,
                  color: fg,
                  fontStyle: m.live ? FontStyle.italic : FontStyle.normal,
                ),
              ),
      ),
    );
  }

  Future<void> _openSettings() async {
    await Navigator.push(
      context,
      MaterialPageRoute(
          builder: (_) =>
              SettingsScreen(ai: _ai, home: this, theme: widget.theme)),
    );
    await saveCfg();
    if (mounted) setState(() {});
  }
}


class SettingsScreen extends StatefulWidget {
  final AiClient ai;
  final HomeScreenState home;
  final ThemeController theme;
  const SettingsScreen(
      {super.key, required this.ai, required this.home, required this.theme});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final _endpoint = TextEditingController(text: widget.ai.endpoint);
  late final _model = TextEditingController(text: widget.ai.model);
  late final _apiKey = TextEditingController(text: widget.ai.apiKey);
  late final _prompt = TextEditingController(text: widget.ai.systemPrompt);
  late final _stop = TextEditingController(text: widget.home.stopWord);
  late final _startw = TextEditingController(text: widget.home.startWord);
  late final _locale = TextEditingController(text: widget.home.locale);
  late final _loopPromptA =
      TextEditingController(text: widget.home.loopPromptA);
  late final _loopPromptB =
      TextEditingController(text: widget.home.loopPromptB);
  late final _maxMsg =
      TextEditingController(text: widget.home.maxMessages.toString());
  late final _loopCtx =
      TextEditingController(text: widget.home.loopContextCount.toString());
  late final _silence =
      TextEditingController(text: widget.home.silenceClearSec.toString());
  late String _ttsVoice2Sel = widget.home.ttsVoice2Name;
  late String _method = AiClient.methods.contains(widget.ai.method.toUpperCase())
      ? widget.ai.method.toUpperCase()
      : 'POST';
  late String _provider =
      AiClient.providers.contains(widget.ai.provider) ? widget.ai.provider : 'local';

  // Common languages always offered even if the device reports none.
  static const _presets = <({String id, String name})>[
    (id: 'en_US', name: 'English (US)'),
    (id: 'en_GB', name: 'English (UK)'),
    (id: 'vi_VN', name: 'Tiếng Việt'),
    (id: 'zh_CN', name: '中文 (简体)'),
    (id: 'zh_TW', name: '中文 (繁體)'),
    (id: 'ja_JP', name: '日本語'),
    (id: 'ko_KR', name: '한국어'),
    (id: 'fr_FR', name: 'Français'),
    (id: 'es_ES', name: 'Español'),
    (id: 'de_DE', name: 'Deutsch'),
  ];

  List<({String id, String name, bool onDevice})> _locales = [];
  bool _loadingLocales = true;
  bool _customLocale = false;
  late String _localeSel = widget.home.locale;
  List<String> _profileNames = [];
  bool _dlBusy = false; // downloading the on-device recognition pack

  // TTS (giọng đọc) — detected from the device.
  List<String> _ttsLangs = [];
  List<({String name, String locale})> _ttsVoices = [];
  List<String> _ttsEngineList = [];
  bool _loadingTts = true;
  late String _ttsEngineSel = widget.home.ttsEngine; // '' = auto (ưu tiên Google)
  late String _ttsLangSel = widget.home.ttsLang; // '' = auto
  late String _ttsVoiceSel = widget.home.ttsVoiceName; // '' = auto
  late AppTheme _themeSel = widget.theme.theme;

  // Running the "test listening language" probe.
  bool _testing = false;

  /// Voices belonging to the selected "Ngôn ngữ đọc" (empty selection = all).
  /// Matches the exact locale or the same base language (e.g. vi-VN ~ vi).
  List<({String name, String locale})> get _voicesForLang {
    if (_ttsLangSel.isEmpty) return _ttsVoices;
    final want = _ttsLangSel.toLowerCase().replaceAll('_', '-');
    final base = want.split('-').first;
    return _ttsVoices.where((v) {
      final loc = v.locale.toLowerCase().replaceAll('_', '-');
      return loc == want || loc.split('-').first == base;
    }).toList();
  }

  @override
  void initState() {
    super.initState();
    _loadLocales();
    _loadTts();
    _loadProfileNames();
  }

  /// Ask the system to download the on-device Vietnamese recognition pack
  /// (Android 13+ `triggerModelDownload`) — the fix for error 13.
  Future<void> _downloadRecognitionPack() async {
    setState(() => _dlBusy = true);
    try {
      await widget.home.downloadSttModel();
      _snack('Đã yêu cầu tải gói nhận giọng tiếng Việt. '
          'Chờ ~1–2 phút cho máy tải xong rồi thử nói lại.');
    } catch (e) {
      _snack('Máy không hỗ trợ tải trong app ($e) — dùng nút "Ra cài đặt".');
    } finally {
      if (mounted) setState(() => _dlBusy = false);
    }
  }

  /// Probe whether the selected listening language is actually usable on this
  /// device; if not, offer to open the screen where the pack is installed.
  Future<void> _testLocale() async {
    final id = (_customLocale ? _locale.text.trim() : _localeSel);
    if (id.isEmpty || id == '__custom__') {
      _snack('Hãy chọn hoặc nhập ngôn ngữ nghe trước.');
      return;
    }
    setState(() => _testing = true);
    final info = await widget.home.sttInfo();
    if (!mounted) return;
    setState(() => _testing = false);

    final want = id.toLowerCase().replaceAll('-', '_');
    final wantBase = want.split('_').first;
    final exact = info.locales
        .any((l) => l.id.toLowerCase().replaceAll('-', '_') == want);
    final sameBase = info.locales.any(
        (l) => l.id.toLowerCase().split(RegExp('[_-]')).first == wantBase);

    if (info.available && exact) {
      _resultDialog(true, 'Ngôn ngữ nghe "$id" đã sẵn sàng trên máy.');
    } else if (info.available && sameBase) {
      _resultDialog(false,
          'Máy có ngôn ngữ gần giống nhưng không đúng "$id". Nên cài thêm gói đúng để nhận tốt hơn.');
    } else if (info.available) {
      _resultDialog(false, 'Máy chưa có gói ngôn ngữ "$id" để nhận giọng nói.');
    } else {
      _resultDialog(false,
          'Máy chưa có bộ nhận giọng nói khả dụng (thường do ROM thiếu Google).');
    }
  }

  void _resultDialog(bool ok, String msg) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Row(children: [
          Icon(ok ? Icons.check_circle : Icons.warning_amber_rounded,
              color:
                  ok ? const Color(0xFF30A46C) : const Color(0xFFF5A623)),
          const SizedBox(width: 8),
          Text(ok ? 'Khả dụng' : 'Chưa cài đủ'),
        ]),
        content: ok
            ? Text(msg)
            : SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('$msg\n\nMở màn hình cài đặt để tải ngôn ngữ về máy. '
                        'Nếu nút "CH Play" dẫn sai, hãy tự tìm trên CH Play bằng '
                        'từ khoá dưới đây (chạm để copy):'),
                    const SizedBox(height: 10),
                    _copyRow('Speech Services by Google'),
                    _copyRow('com.google.android.tts'),
                    const SizedBox(height: 6),
                    const Text(
                      'Mở app "Speech Services by Google" → Cài đặt → tải gói '
                      'ngôn ngữ offline bạn cần.',
                      style: TextStyle(fontSize: 12.5, color: Colors.white54),
                    ),
                  ],
                ),
              ),
        actions: ok
            ? [
                TextButton(
                    onPressed: () => Navigator.pop(ctx),
                    child: const Text('OK')),
              ]
            : [
                TextButton(
                    onPressed: () => Navigator.pop(ctx),
                    child: const Text('Đóng')),
                TextButton(
                  onPressed: () {
                    Navigator.pop(ctx);
                    _openInstall('openSpeechServicesStore');
                  },
                  child: const Text('CH Play'),
                ),
                FilledButton(
                  onPressed: () {
                    Navigator.pop(ctx);
                    _openInstall('openSttSettings');
                  },
                  child: const Text('Mở cài đặt'),
                ),
              ],
      ),
    );
  }

  /// A tappable pill that copies its text to the clipboard — so the user can
  /// paste the exact search term into the Play Store manually.
  Widget _copyRow(String text) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: InkWell(
        onTap: () async {
          await Clipboard.setData(ClipboardData(text: text));
          _snack('Đã copy: $text');
        },
        borderRadius: BorderRadius.circular(6),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          decoration: BoxDecoration(
            color: const Color(0x22808080),
            borderRadius: BorderRadius.circular(6),
          ),
          child: Row(
            children: [
              Expanded(
                child: SelectableText(text,
                    style: const TextStyle(
                        fontFamily: 'monospace', fontSize: 13.5)),
              ),
              const SizedBox(width: 8),
              const Icon(Icons.copy, size: 16),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _openInstall(String method) async {
    bool opened = false;
    try {
      opened = (await _nativeChannel.invokeMethod<bool>(method)) ?? false;
    } catch (e) {
      AppLog.instance.log('Mở cài đặt lỗi: $e');
    }
    if (!opened) _snack('Không mở được màn hình cài đặt trên máy này.');
  }

  void _snack(String s) {
    if (!mounted) return;
    showAppToast(context, s);
  }

  /// Small speaker button to hear a voice sample.
  Widget _previewBtn(VoidCallback onTap) => Padding(
        padding: const EdgeInsets.only(left: 6, bottom: 18),
        child: IconButton.filledTonal(
          tooltip: 'Nghe thử',
          icon: const Icon(Icons.volume_up),
          onPressed: onTap,
        ),
      );

  void _previewVoice(String name) {
    final match = _ttsVoices.where((v) => v.name == name).toList();
    final loc = match.isNotEmpty ? match.first.locale : '';
    widget.home.speakSampleVoice(name, loc);
  }

  String _engineLabel(String pkg) {
    const known = {
      'com.google.android.tts': 'Google (Speech Services)',
      'com.samsung.SMT': 'Samsung TTS',
      'com.iflytek.speechcloud': 'iFlytek (讯飞)',
      'com.baidu.duersdk.opensdk': 'Baidu',
    };
    return known[pkg] ?? pkg;
  }

  Future<void> _loadLocales() async {
    final info = await widget.home.sttInfo();
    if (!mounted) return;
    final merged = <({String id, String name, bool onDevice})>[];
    final seen = <String>{};
    for (final l in info.locales) {
      seen.add(l.id.toLowerCase());
      merged.add((id: l.id, name: l.name, onDevice: true));
    }
    for (final p in _presets) {
      if (seen.add(p.id.toLowerCase())) {
        merged.add((id: p.id, name: p.name, onDevice: false));
      }
    }
    final want = widget.home.locale.toLowerCase();
    final match = merged.where((l) => l.id.toLowerCase() == want).toList();
    setState(() {
      _locales = merged;
      _loadingLocales = false;
      if (match.isNotEmpty) {
        _customLocale = false;
        _localeSel = match.first.id;
      } else {
        _customLocale = true;
        _localeSel = '__custom__';
      }
    });
  }

  Future<void> _loadTts() async {
    setState(() => _loadingTts = true);
    final engines = await widget.home.ttsEngines();
    final info = await widget.home.ttsInfo();
    if (!mounted) return;
    final seenV = <String>{};
    setState(() {
      _ttsEngineList = engines;
      _ttsLangs = info.languages;
      // Dedup by name so dropdown values stay unique.
      _ttsVoices = info.voices.where((v) => seenV.add(v.name)).toList();
      _loadingTts = false;
      // Keep selection only if still present; else fall back to auto.
      if (_ttsLangSel.isNotEmpty && !_ttsLangs.contains(_ttsLangSel)) {
        _ttsLangSel = '';
      }
      if (_ttsVoiceSel.isNotEmpty &&
          !_ttsVoices.any((v) => v.name == _ttsVoiceSel)) {
        _ttsVoiceSel = '';
      }
    });
  }

  @override
  void dispose() {
    for (final c in [
      _endpoint, _model, _apiKey, _prompt, _stop, _startw, _locale,
      _loopPromptA, _loopPromptB, _maxMsg, _loopCtx, _silence,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  /// Copy the form into the live config (no navigation).
  void _applyToHome() {
    widget.ai.provider = _provider;
    widget.ai.endpoint = _endpoint.text.trim();
    widget.ai.model = _model.text.trim();
    widget.ai.apiKey = _apiKey.text.trim();
    widget.ai.method = _method;
    widget.ai.systemPrompt = _prompt.text.trim();
    widget.home.stopWord =
        _stop.text.trim().isEmpty ? 'AI' : _stop.text.trim();
    widget.home.startWord = _startw.text.trim();
    widget.home.locale =
        _locale.text.trim().isEmpty ? 'vi_VN' : _locale.text.trim();
    widget.home.ttsEngine = _ttsEngineSel;
    widget.home.ttsLang = _ttsLangSel;
    final v = _ttsVoices.where((x) => x.name == _ttsVoiceSel).toList();
    widget.home.ttsVoiceName = v.isNotEmpty ? v.first.name : '';
    widget.home.ttsVoiceLocale = v.isNotEmpty ? v.first.locale : '';
    final v2 = _ttsVoices.where((x) => x.name == _ttsVoice2Sel).toList();
    widget.home.ttsVoice2Name = v2.isNotEmpty ? v2.first.name : '';
    widget.home.ttsVoice2Locale = v2.isNotEmpty ? v2.first.locale : '';
    widget.home.loopPromptA = _loopPromptA.text.trim();
    widget.home.loopPromptB = _loopPromptB.text.trim();
    widget.home.maxMessages =
        (int.tryParse(_maxMsg.text.trim()) ?? 20).clamp(2, 200);
    widget.home.loopContextCount =
        (int.tryParse(_loopCtx.text.trim()) ?? 1).clamp(1, 40);
    widget.home.silenceClearSec =
        (int.tryParse(_silence.text.trim()) ?? 0).clamp(0, 60);
    widget.theme.set(_themeSel);
  }

  void _apply() {
    _applyToHome();
    Navigator.pop(context);
  }

  // ---- Profiles / export / import ----

  Future<void> _loadProfileNames() async {
    final all = await widget.home.loadProfiles();
    if (mounted) setState(() => _profileNames = all.keys.toList()..sort());
  }

  Future<void> _doExport() async {
    _applyToHome();
    await widget.home.saveCfg();
    final json = const JsonEncoder.withIndent('  ')
        .convert(widget.home.exportConfig());
    await Clipboard.setData(ClipboardData(text: json));
    if (!mounted) return;
    final nameCtrl = TextEditingController(text: 'voice_ai');
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Export cấu hình'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: nameCtrl,
                decoration: const InputDecoration(
                  labelText: 'Tên profile (dùng cho tên file)',
                  isDense: true,
                ),
              ),
              const SizedBox(height: 12),
              SelectableText(json,
                  style:
                      const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Đóng')),
          TextButton.icon(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: json));
              _snack('Đã copy JSON cấu hình');
            },
            icon: const Icon(Icons.copy),
            label: const Text('Copy'),
          ),
          FilledButton.icon(
            onPressed: () => _saveConfigFile(nameCtrl.text, json),
            icon: const Icon(Icons.save_alt),
            label: const Text('Lưu file .json'),
          ),
        ],
      ),
    );
  }

  /// Writes the exported JSON to a file named
  /// `voiceai_<profile>_<yyyyMMdd_HHmmss>.json` and opens the system share
  /// sheet so the user can save it (Files/Drive/etc.).
  Future<void> _saveConfigFile(String profile, String json) async {
    try {
      final now = DateTime.now();
      String two(int n) => n.toString().padLeft(2, '0');
      final ts = '${now.year}${two(now.month)}${two(now.day)}_'
          '${two(now.hour)}${two(now.minute)}${two(now.second)}';
      var name = profile.trim();
      if (name.isEmpty) name = 'voice_ai';
      name = name.replaceAll(RegExp(r'[^A-Za-z0-9._\-]'), '_');
      final fileName = '${name}_$ts.json';
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/$fileName');
      await file.writeAsString(json);
      await Share.shareXFiles([XFile(file.path)],
          subject: 'Voice AI - $fileName');
      if (mounted) _snack('Đã tạo file: $fileName');
    } catch (e) {
      if (mounted) _snack('Lưu file lỗi: $e');
    }
  }

  Future<void> _doImport() async {
    final nameCtrl = TextEditingController();
    final jsonCtrl = TextEditingController();
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Import cấu hình → tạo profile'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: nameCtrl,
                decoration: const InputDecoration(
                  labelText: 'Tên profile',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 10),
              Align(
                alignment: Alignment.centerLeft,
                child: OutlinedButton.icon(
                  onPressed: () => _pickImportFile(nameCtrl, jsonCtrl),
                  icon: const Icon(Icons.folder_open),
                  label: const Text('Chọn file .json'),
                ),
              ),
              const SizedBox(height: 10),
              TextField(
                controller: jsonCtrl,
                minLines: 4,
                maxLines: 10,
                decoration: const InputDecoration(
                  labelText: 'Hoặc dán JSON cấu hình',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Huỷ')),
          FilledButton(
            onPressed: () async {
              final name = nameCtrl.text.trim();
              if (name.isEmpty) {
                _snack('Nhập tên profile.');
                return;
              }
              Map<String, dynamic> data;
              try {
                data = Map<String, dynamic>.from(
                    jsonDecode(jsonCtrl.text.trim()) as Map);
              } catch (e) {
                _snack('JSON không hợp lệ: $e');
                return;
              }
              await widget.home.saveProfile(name, data);
              await _loadProfileNames();
              if (ctx.mounted) Navigator.pop(ctx);
              _snack('Đã lưu profile "$name". Chọn nó ở ô Profile để áp dụng.');
            },
            child: const Text('Lưu profile'),
          ),
        ],
      ),
    );
    nameCtrl.dispose();
    jsonCtrl.dispose();
  }

  /// Lets the user pick a .json config file, loads its text into the import
  /// field, and pre-fills the profile name from the file name if empty.
  Future<void> _pickImportFile(
      TextEditingController nameCtrl, TextEditingController jsonCtrl) async {
    try {
      final res = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['json'],
        withData: true,
      );
      if (res == null || res.files.isEmpty) return;
      final f = res.files.first;
      String text;
      if (f.bytes != null) {
        text = utf8.decode(f.bytes!);
      } else if (f.path != null) {
        text = await File(f.path!).readAsString();
      } else {
        _snack('Không đọc được file.');
        return;
      }
      // Validate JSON early so bad files are caught here.
      jsonDecode(text);
      jsonCtrl.text = text;
      if (nameCtrl.text.trim().isEmpty) {
        var base = f.name;
        final dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        nameCtrl.text = base;
      }
      _snack('Đã nạp file "${f.name}". Bấm Lưu profile.');
    } catch (e) {
      _snack('Chọn file lỗi: $e');
    }
  }

  Future<void> _applyProfile(String name) async {
    final all = await widget.home.loadProfiles();
    final cfg = all[name];
    if (cfg is! Map) return;
    await widget.home.applyConfig(Map<String, dynamic>.from(cfg));
    if (!mounted) return;
    _snack('Đã áp dụng profile "$name".');
    Navigator.pop(context); // close settings; new config is live
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Cài đặt'),
        actions: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
            child: FilledButton.icon(
              onPressed: _apply,
              style: FilledButton.styleFrom(
                backgroundColor: Colors.white,
                foregroundColor: const Color(0xFF1F6FD6),
              ),
              icon: const Icon(Icons.save, size: 18),
              label: const Text('Lưu'),
            ),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Quick access to the log (top of the screen).
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: () => Navigator.push(context,
                  MaterialPageRoute(builder: (_) => const LogScreen())),
              icon: const Icon(Icons.article_outlined),
              label: const Text('Xem log'),
            ),
          ),
          // Config profiles + import/export.
          DropdownButtonFormField<String>(
            initialValue: null,
            isExpanded: true,
            decoration: const InputDecoration(
              labelText: 'Profile cấu hình (chọn để áp dụng)',
              border: OutlineInputBorder(),
            ),
            items: [
              const DropdownMenuItem<String>(
                  value: '', child: Text('— chọn profile —')),
              for (final n in _profileNames)
                DropdownMenuItem<String>(value: n, child: Text(n)),
            ],
            onChanged: (v) {
              if (v != null && v.isNotEmpty) _applyProfile(v);
            },
          ),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _doImport,
                icon: const Icon(Icons.file_download),
                label: const Text('Import'),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _doExport,
                icon: const Icon(Icons.file_upload),
                label: const Text('Export'),
              ),
            ),
          ]),
          const Divider(height: 24),
          _hdr('Cấu hình chung'),
          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: DropdownButtonFormField<AppTheme>(
              initialValue: _themeSel,
              decoration: const InputDecoration(
                labelText: 'Giao diện (theme)',
                border: OutlineInputBorder(),
              ),
              items: [
                for (final t in AppTheme.values)
                  DropdownMenuItem(value: t, child: Text(appThemeNames[t]!)),
              ],
              onChanged: (v) => setState(() => _themeSel = v ?? AppTheme.dark),
            ),
          ),
          _field(_maxMsg, 'Số tin tối đa hiện trên màn hình (chung)', '20'),
          const Divider(height: 24),
          _hdr('AI đối thoại (hỏi–đáp / chat)'),
          _field(_startw, 'Từ khoá bắt đầu (tuỳ chọn)', 'để trống nếu không dùng',
              helper:
                  'Nếu điền, chỉ tính câu hỏi phần nói SAU từ này (tới từ kết thúc).'),
          _field(_stop, 'Từ khoá kết thúc câu hỏi', 'AI'),
          _field(_silence, 'Xoá sau khi im lặng (giây, 0 = tắt)', '0',
              helper: 'Khi KHÔNG dùng "Từ khoá bắt đầu": im lặng quá lâu mà chưa '
                  'nói từ kết thúc thì xoá nội dung vừa nghe.'),
          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: DropdownButtonFormField<String>(
              initialValue: _provider,
              decoration: const InputDecoration(
                labelText: 'Nguồn AI',
                border: OutlineInputBorder(),
              ),
              items: const [
                DropdownMenuItem(
                    value: 'local',
                    child: Text('Local (Ollama / LM Studio…)')),
                DropdownMenuItem(
                    value: 'gemini', child: Text('Google Gemini (API key)')),
              ],
              onChanged: (v) => setState(() => _provider = v ?? 'local'),
            ),
          ),
          if (_provider == 'local') ...[
            _field(_endpoint, 'Địa chỉ máy chủ AI (OpenAI-compatible)',
                'http://192.168.1.10:11434/v1/chat/completions'),
            Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: DropdownButtonFormField<String>(
                initialValue: _method,
                decoration: const InputDecoration(
                  labelText: 'HTTP method',
                  helperText: 'Mặc định POST cho chat.',
                  border: OutlineInputBorder(),
                ),
                items: [
                  for (final m in AiClient.methods)
                    DropdownMenuItem(value: m, child: Text(m)),
                ],
                onChanged: (v) => setState(() => _method = v ?? 'POST'),
              ),
            ),
          ],
          _field(_model, _provider == 'gemini' ? 'Model Gemini' : 'Tên model',
              _provider == 'gemini' ? 'gemini-1.5-flash' : 'qwen2.5:0.5b'),
          _field(
              _apiKey,
              _provider == 'gemini'
                  ? 'Gemini API key (token)'
                  : 'API key (nếu cần)',
              _provider == 'gemini' ? 'AIza…' : 'để trống nếu chạy local'),
          _field(_prompt, 'Prompt tính cách (tạo không khí)',
              'Bạn là trợ lý vui tính…',
              maxLines: 4),
          if (_loadingLocales)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 14),
              child: Row(children: [
                SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2)),
                SizedBox(width: 10),
                Text('Đang dò ngôn ngữ trên máy…'),
              ]),
            )
          else ...[
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: DropdownButtonFormField<String>(
                initialValue: _localeSel,
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Ngôn ngữ nghe',
                  helperText:
                      'Dấu ⚠ = máy chưa báo hỗ trợ. Nếu vi báo "chưa tải", bấm "Tải gói ngay trong app" bên dưới.',
                  border: OutlineInputBorder(),
                ),
                items: [
                  for (final l in _locales)
                    DropdownMenuItem(
                      value: l.id,
                      child: Text(
                        '${l.onDevice ? '' : '⚠ '}${l.name} (${l.id})',
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  const DropdownMenuItem(
                      value: '__custom__', child: Text('Tùy chỉnh…')),
                ],
                onChanged: (v) {
                  if (v == null) return;
                  setState(() {
                    _localeSel = v;
                    if (v == '__custom__') {
                      _customLocale = true;
                    } else {
                      _customLocale = false;
                      _locale.text = v;
                    }
                  });
                },
              ),
            ),
            if (_customLocale) _field(_locale, 'Locale tùy chỉnh', 'vi_VN'),
            const SizedBox(height: 4),
            // Stacked vertically (one per line).
            Column(
              children: [
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton.icon(
                    onPressed: _dlBusy ? null : _downloadRecognitionPack,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFF1F6FD6),
                      foregroundColor: Colors.white,
                      disabledBackgroundColor: const Color(0xFF8FB4E0),
                      disabledForegroundColor: Colors.white,
                    ),
                    icon: _dlBusy
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(
                                strokeWidth: 2, color: Colors.white))
                        : const Icon(Icons.download),
                    label: Text(_dlBusy
                        ? 'Đang yêu cầu tải…'
                        : 'Tải gói ngay trong app'),
                  ),
                ),
                const SizedBox(height: 6),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: _testing ? null : _testLocale,
                    icon: _testing
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.spellcheck),
                    label: Text(_testing ? 'Đang kiểm tra…' : 'Thử tiếng Việt'),
                  ),
                ),
                const SizedBox(height: 6),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: () => _openInstall('openSttSettings'),
                    icon: const Icon(Icons.settings),
                    label: const Text('Ra cài đặt'),
                  ),
                ),
                const SizedBox(height: 6),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: () => _openInstall('openSpeechServicesStore'),
                    icon: const Icon(Icons.shop),
                    label: const Text('Speech Services (CH Play)'),
                  ),
                ),
              ],
            ),
          ],
          const Divider(height: 24),
          _hdr('AI tự thoại (AI nói với AI)'),
          const Text(
            'Câu mở đầu khi bấm Tự thoại = chủ đề/xu hướng cho cả 2 bên. '
            'Cách nói A/B luôn đưa vào system mỗi lượt. Input mỗi lượt = '
            'câu đối phương vừa nói.',
            style: TextStyle(color: Colors.white54, fontSize: 12.5),
          ),
          const SizedBox(height: 10),
          _field(_loopPromptA, 'Cách nói người A (system, giọng 1)',
              'VD: vui tính, thân mật, câu ngắn tiếng Việt…',
              maxLines: 3,
              helper: 'Luôn inject vào system để ép AI tuân thủ mỗi câu trả lời.'),
          _field(_loopPromptB, 'Cách nói người B (system, giọng 2)',
              'VD: hay phản biện, dí dỏm, câu ngắn tiếng Việt…',
              maxLines: 3,
              helper: 'Luôn inject vào system để ép AI tuân thủ mỗi câu trả lời.'),
          _field(_loopCtx, 'Số câu đối phương làm input', '1',
              helper: '1 = chỉ câu đối phương vừa nói. '
                  'Lớn hơn = lấy thêm các câu gần nhất của đối phương (không lấy câu của chính nó).'),
          const SizedBox(height: 4),
          const Text('Giọng đọc & tốc độ',
              style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          if (_loadingTts)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Row(children: [
                SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2)),
                SizedBox(width: 10),
                Text('Đang dò giọng đọc trên máy…'),
              ]),
            )
          else ...[
            if (_ttsEngineList.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: DropdownButtonFormField<String>(
                  initialValue:
                      _ttsEngineList.contains(_ttsEngineSel) ? _ttsEngineSel : '',
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Engine đọc',
                    helperText:
                        'Chọn Google nếu máy nội địa Trung để có tiếng Việt.',
                    border: OutlineInputBorder(),
                  ),
                  items: [
                    const DropdownMenuItem(
                        value: '', child: Text('Tự động (ưu tiên Google)')),
                    for (final e in _ttsEngineList)
                      DropdownMenuItem(
                          value: e,
                          child: Text(_engineLabel(e),
                              overflow: TextOverflow.ellipsis)),
                  ],
                  onChanged: (v) async {
                    setState(() {
                      _ttsEngineSel = v ?? '';
                      _ttsLangSel = '';
                      _ttsVoiceSel = '';
                    });
                    widget.home.ttsEngine = _ttsEngineSel;
                    await _loadTts(); // languages/voices differ per engine
                  },
                ),
              ),
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: DropdownButtonFormField<String>(
                initialValue: _ttsLangs.contains(_ttsLangSel) ? _ttsLangSel : '',
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: 'Ngôn ngữ đọc',
                  helperText: 'Máy có ${_ttsLangs.length} ngôn ngữ đọc.',
                  border: const OutlineInputBorder(),
                ),
                items: [
                  const DropdownMenuItem(
                      value: '', child: Text('Tự động (theo máy)')),
                  for (final l in _ttsLangs)
                    DropdownMenuItem(
                        value: l,
                        child: Text(l, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setState(() {
                  _ttsLangSel = v ?? '';
                  // Drop the voice if it no longer belongs to the new language.
                  if (!_voicesForLang.any((x) => x.name == _ttsVoiceSel)) {
                    _ttsVoiceSel = '';
                  }
                }),
              ),
            ),
            Builder(builder: (_) {
              final voices = _voicesForLang;
              return Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Row(children: [
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: voices.any((v) => v.name == _ttsVoiceSel)
                          ? _ttsVoiceSel
                          : '',
                      isExpanded: true,
                      decoration: InputDecoration(
                        labelText: 'Giọng đọc',
                        helperText: _ttsLangSel.isEmpty
                            ? 'Máy có ${_ttsVoices.length} giọng.'
                            : '${voices.length} giọng cho "$_ttsLangSel".',
                        border: const OutlineInputBorder(),
                      ),
                      items: [
                        const DropdownMenuItem(
                            value: '', child: Text('Tự động (theo ngôn ngữ)')),
                        for (final v in voices)
                          DropdownMenuItem(
                              value: v.name,
                              child: Text('${v.locale} — ${v.name}',
                                  overflow: TextOverflow.ellipsis)),
                      ],
                      onChanged: (v) => setState(() => _ttsVoiceSel = v ?? ''),
                    ),
                  ),
                  _previewBtn(() => _previewVoice(_ttsVoiceSel)),
                ]),
              );
            }),
            Builder(builder: (_) {
              final voices = _voicesForLang;
              return Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Row(children: [
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: voices.any((v) => v.name == _ttsVoice2Sel)
                          ? _ttsVoice2Sel
                          : '',
                      isExpanded: true,
                      decoration: const InputDecoration(
                        labelText: 'Giọng đọc thứ 2 (cho tự thoại)',
                        helperText: 'Để trống = dùng chung giọng 1.',
                        border: OutlineInputBorder(),
                      ),
                      items: [
                        const DropdownMenuItem(
                            value: '', child: Text('Giống giọng 1')),
                        for (final v in voices)
                          DropdownMenuItem(
                              value: v.name,
                              child: Text('${v.locale} — ${v.name}',
                                  overflow: TextOverflow.ellipsis)),
                      ],
                      onChanged: (v) => setState(() => _ttsVoice2Sel = v ?? ''),
                    ),
                  ),
                  _previewBtn(() => _previewVoice(
                      _ttsVoice2Sel.isNotEmpty ? _ttsVoice2Sel : _ttsVoiceSel)),
                ]),
              );
            }),
          ],
          Row(
            children: [
              const SizedBox(width: 120, child: Text('Tốc độ đọc')),
              Expanded(
                child: StatefulBuilder(
                  builder: (context, s) => Slider(
                    value: widget.home.ttsRate,
                    min: 0.2,
                    max: 1.0,
                    onChanged: (v) => s(() => widget.home.ttsRate = v),
                  ),
                ),
              ),
            ],
          ),
          const Divider(height: 24),
          FilledButton.tonalIcon(
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) => DiagnosticsScreen(home: widget.home),
              ),
            ),
            icon: const Icon(Icons.health_and_safety_outlined),
            label: const Text('Kiểm tra điều kiện hoạt động'),
          ),
          const SizedBox(height: 8),
          FilledButton.tonalIcon(
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) => DetectScreen(home: widget.home),
              ),
            ),
            icon: const Icon(Icons.travel_explore),
            label: const Text('Dò thiết bị (xem STT/TTS có sẵn)'),
          ),
          const SizedBox(height: 12),
          const Text(
            'Gợi ý: cài Ollama trên máy tính cùng Wi-Fi, chạy model nhỏ '
            '(vd "ollama run qwen2.5:0.5b"), đặt OLLAMA_HOST=0.0.0.0, rồi dán '
            'http://<IP-máy-tính>:11434/v1/chat/completions vào ô địa chỉ. '
            'Địa chỉ thiếu "//" hoặc thiếu đường dẫn sẽ được tự chuẩn hoá.',
            style: TextStyle(color: Colors.white54, fontSize: 12.5),
          ),
        ],
      ),
    );
  }

  Widget _hdr(String t) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(t,
            style: const TextStyle(
                fontWeight: FontWeight.bold, fontSize: 15)),
      );

  Widget _field(TextEditingController c, String label, String hint,
      {int maxLines = 1, String? helper}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: TextField(
        controller: c,
        maxLines: maxLines,
        autocorrect: false,
        decoration: InputDecoration(
          labelText: label,
          hintText: hint,
          helperText: helper,
          helperMaxLines: 3,
          border: const OutlineInputBorder(),
        ),
      ),
    );
  }
}

/// Readiness self-check window: runs [HomeScreenState.diagSteps] one at a time,
/// showing the current step, its elapsed time, and an OK / warn / fail badge.
class DiagnosticsScreen extends StatefulWidget {
  final HomeScreenState home;
  const DiagnosticsScreen({super.key, required this.home});

  @override
  State<DiagnosticsScreen> createState() => _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends State<DiagnosticsScreen>
    with SingleTickerProviderStateMixin {
  late final List<(String, Future<DiagItem> Function())> _steps =
      widget.home.diagSteps();
  late final List<DiagItem?> _results =
      List<DiagItem?>.filled(_steps.length, null);
  late final List<Duration?> _durations =
      List<Duration?>.filled(_steps.length, null);

  int _current = -1; // step being checked right now
  DateTime? _stepStart;
  bool _running = false;
  Ticker? _ticker;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker((_) {
      if (_running && mounted) setState(() {});
    });
    _run();
  }

  @override
  void dispose() {
    _ticker?.dispose();
    super.dispose();
  }

  Future<void> _run() async {
    setState(() {
      _running = true;
      for (int i = 0; i < _steps.length; i++) {
        _results[i] = null;
        _durations[i] = null;
      }
    });
    _ticker?.start();
    for (int i = 0; i < _steps.length; i++) {
      if (!mounted) return;
      setState(() {
        _current = i;
        _stepStart = DateTime.now();
      });
      final start = DateTime.now();
      DiagItem item;
      try {
        // Hard per-step timeout so a stuck platform call (e.g. locales()) can't
        // freeze the whole checklist.
        item = await _steps[i].$2().timeout(
          const Duration(seconds: 12),
          onTimeout: () => DiagItem(
              _steps[i].$1, DiagStatus.warn, 'Quá thời gian (12s), đã bỏ qua'),
        );
      } catch (e) {
        item = DiagItem(_steps[i].$1, DiagStatus.fail, '$e');
      }
      if (!mounted) return;
      setState(() {
        _results[i] = item;
        _durations[i] = DateTime.now().difference(start);
      });
    }
    _ticker?.stop();
    if (mounted) {
      setState(() {
        _running = false;
        _current = -1;
      });
    }
    AppLog.instance.log('Diagnostics done: '
        '${_results.whereType<DiagItem>().map((e) => '${e.label}=${e.status.name}').join(', ')}');
  }

  (IconData, Color) _badge(DiagStatus s) => switch (s) {
        DiagStatus.ok => (Icons.check_circle, const Color(0xFF30A46C)),
        DiagStatus.warn => (
            Icons.warning_amber_rounded,
            const Color(0xFFF5A623)
          ),
        DiagStatus.fail => (Icons.cancel, const Color(0xFFE5484D)),
      };

  String _fmt(Duration d) => '${(d.inMilliseconds / 1000).toStringAsFixed(1)}s';

  @override
  Widget build(BuildContext context) {
    final done = _results.whereType<DiagItem>().toList();
    final allOk = !_running && done.every((e) => e.status == DiagStatus.ok);
    final anyFail = done.any((e) => e.status == DiagStatus.fail);
    final progress = '${done.length}/${_steps.length}';

    return Scaffold(
      appBar: AppBar(
        title: const Text('Kiểm tra điều kiện'),
        actions: [
          IconButton(
            tooltip: 'Kiểm tra lại',
            icon: const Icon(Icons.refresh),
            onPressed: _running ? null : _run,
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            color: _running
                ? const Color(0x2230B0C7)
                : (anyFail
                    ? const Color(0x22E5484D)
                    : (allOk
                        ? const Color(0x2230A46C)
                        : const Color(0x22F5A623))),
            child: ListTile(
              leading: _running
                  ? const SizedBox(
                      width: 22,
                      height: 22,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : Icon(
                      anyFail
                          ? Icons.error_outline
                          : (allOk ? Icons.verified : Icons.info_outline),
                      color: anyFail
                          ? const Color(0xFFE5484D)
                          : (allOk
                              ? const Color(0xFF30A46C)
                              : const Color(0xFFF5A623)),
                    ),
              title: Text(_running
                  ? 'Đang kiểm tra… ($progress)'
                  : (anyFail
                      ? 'Có điều kiện chưa đạt'
                      : (allOk
                          ? 'Sẵn sàng hoạt động'
                          : 'Hoạt động được, vài cảnh báo'))),
              subtitle: Text(_running
                  ? 'Đang chạy: ${_steps[_current].$1}'
                  : 'Chạm nút làm mới để kiểm tra lại.'),
            ),
          ),
          const SizedBox(height: 8),
          for (int i = 0; i < _steps.length; i++) _stepTile(i),
        ],
      ),
    );
  }

  Widget _stepTile(int i) {
    final label = _steps[i].$1;
    final result = _results[i];
    final duration = _durations[i];

    // Done → badge + detail + how long it took.
    if (result != null) {
      final (icon, color) = _badge(result.status);
      return Card(
        child: ListTile(
          leading: Icon(icon, color: color),
          title: Text(label),
          subtitle: Text(result.detail),
          trailing: duration == null
              ? null
              : Text(_fmt(duration),
                  style: const TextStyle(color: Colors.white54, fontSize: 12)),
          isThreeLine: result.detail.length > 40,
        ),
      );
    }

    // Currently running → spinner + live elapsed time.
    if (i == _current && _running) {
      final elapsed =
          _stepStart == null ? Duration.zero : DateTime.now().difference(_stepStart!);
      return Card(
        color: const Color(0x1130B0C7),
        child: ListTile(
          leading: const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2)),
          title: Text(label),
          subtitle: const Text('Đang kiểm tra…'),
          trailing: Text(_fmt(elapsed),
              style: const TextStyle(
                  color: Color(0xFF30B0C7), fontWeight: FontWeight.bold)),
        ),
      );
    }

    // Pending.
    return Card(
      child: ListTile(
        leading: const Icon(Icons.schedule, color: Colors.white30),
        title: Text(label, style: const TextStyle(color: Colors.white54)),
        subtitle: const Text('Chờ…'),
      ),
    );
  }
}

/// Probes the phone and lists exactly what speech recognition + TTS it has —
/// so you can see whether e.g. Chinese is available. Reachable from Settings.
class DetectScreen extends StatefulWidget {
  final HomeScreenState home;
  const DetectScreen({super.key, required this.home});
  @override
  State<DetectScreen> createState() => _DetectScreenState();
}

class _DetectScreenState extends State<DetectScreen> {
  Map<String, dynamic>? _d;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _run();
  }

  Future<void> _run() async {
    setState(() => _loading = true);
    final d = await widget.home.detectDevice();
    if (mounted) setState(() { _d = d; _loading = false; });
  }

  bool _hl(String s) {
    final l = s.toLowerCase();
    return l.startsWith('zh') ||
        l.startsWith('vi') ||
        l.contains('(zh') ||
        l.contains('(vi') ||
        l.contains('中') ||
        l.contains('việt');
  }

  @override
  Widget build(BuildContext context) {
    final d = _d;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Dò thiết bị'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loading ? null : _run,
          ),
        ],
      ),
      body: _loading || d == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _statusCard(d['sttAvailable'] == true),
                const SizedBox(height: 8),
                _section('Nhận diện giọng nói (STT)'),
                _kv('Khả dụng', d['sttAvailable'] == true ? 'CÓ' : 'KHÔNG'),
                if (d['sttError'] != null) _kv('Lỗi', '${d['sttError']}'),
                _kv('Ngôn ngữ hệ thống', '${d['sttSystem'] ?? '—'}'),
                _listBlock('Engine nhận giọng đã cài',
                    (d['sttRecognizers'] as List?) ?? const [], null),
                _listBlock('Ngôn ngữ STT (plugin + native)',
                    d['sttLocales'] as List, d['sttLocalesError']),
                const SizedBox(height: 12),
                _section('Giọng đọc (TTS)'),
                _listBlock('Ngôn ngữ TTS thiết bị báo có',
                    d['ttsLanguages'] as List, d['ttsError']),
                const SizedBox(height: 16),
                const Text(
                  'Dòng bôi vàng là tiếng Trung/tiếng Việt. Nếu STT trống hoặc '
                  'báo lỗi/timeout, máy không nhận giọng nói bằng cách hiện tại; '
                  'khi đó nên dùng nhận giọng nói qua Gemini (đám mây).',
                  style: TextStyle(color: Colors.white54, fontSize: 12.5),
                ),
              ],
            ),
    );
  }

  Widget _statusCard(bool ok) => Card(
        color: ok ? const Color(0x2230A46C) : const Color(0x22E5484D),
        child: ListTile(
          leading: Icon(ok ? Icons.check_circle : Icons.error_outline,
              color: ok ? const Color(0xFF30A46C) : const Color(0xFFE5484D)),
          title: Text(ok
              ? 'Máy CÓ bộ nhận diện giọng nói'
              : 'Máy KHÔNG có bộ nhận diện giọng nói khả dụng'),
          subtitle: const Text('Chạm nút làm mới để dò lại.'),
        ),
      );

  Widget _section(String t) => Padding(
        padding: const EdgeInsets.only(top: 6, bottom: 6),
        child: Text(t,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
      );

  Widget _kv(String k, String v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
                width: 150,
                child: Text(k,
                    style: const TextStyle(color: Colors.white54, fontSize: 13))),
            Expanded(child: SelectableText(v, style: const TextStyle(fontSize: 13))),
          ],
        ),
      );

  Widget _listBlock(String title, List items, Object? error) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('$title (${items.length})',
              style: const TextStyle(color: Colors.white70, fontSize: 13)),
          const SizedBox(height: 4),
          if (error != null)
            Text('Lỗi: $error',
                style: const TextStyle(color: Color(0xFFE5484D), fontSize: 12.5)),
          if (items.isEmpty && error == null)
            const Text('— (trống, thiết bị không báo có)',
                style: TextStyle(color: Colors.white38, fontSize: 12.5)),
          for (final it in items)
            Container(
              margin: const EdgeInsets.symmetric(vertical: 2),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: _hl('$it')
                    ? const Color(0x33F5A623)
                    : Colors.white10,
                borderRadius: BorderRadius.circular(6),
              ),
              child: SelectableText('$it', style: const TextStyle(fontSize: 13)),
            ),
        ],
      ),
    );
  }
}

/// Shows the in-memory event log (newest first). Useful for diagnosing speech /
/// AI-connection issues. Reachable from Settings → "Xem log".
class LogScreen extends StatelessWidget {
  const LogScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final log = AppLog.instance;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Log'),
        actions: [
          IconButton(
            tooltip: 'Sao chép',
            icon: const Icon(Icons.copy_all),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: log.dump()));
              if (context.mounted) {
                showAppToast(context, 'Đã sao chép log');
              }
            },
          ),
          IconButton(
            tooltip: 'Xoá log',
            icon: const Icon(Icons.delete_outline),
            onPressed: log.clear,
          ),
        ],
      ),
      body: ValueListenableBuilder<int>(
        valueListenable: log.revision,
        builder: (context, _, _) {
          final lines = log.lines.reversed.toList();
          if (lines.isEmpty) {
            return const Center(
              child: Text('Chưa có log.', style: TextStyle(color: Colors.white54)),
            );
          }
          return ListView.builder(
            padding: const EdgeInsets.all(12),
            itemCount: lines.length,
            itemBuilder: (_, i) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: SelectableText(
                lines[i],
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 12.5,
                  height: 1.3,
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// A tap target that springs down (scale + optional pressed state) for a
/// satisfying, tactile press. Provide either [child] or a [builder] that gets
/// the current pressed state. A null [onTap] dims and disables it.
class _PressPop extends StatefulWidget {
  final VoidCallback? onTap;
  final BorderRadius radius;
  final Widget? child;
  final Widget Function(bool pressed)? builder;
  const _PressPop({
    required this.onTap,
    required this.radius,
    this.child,
    this.builder,
  });

  @override
  State<_PressPop> createState() => _PressPopState();
}

class _PressPopState extends State<_PressPop> {
  bool _down = false;

  void _set(bool v) {
    if (widget.onTap == null) return;
    if (_down != v) setState(() => _down = v);
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onTap != null;
    final visual = widget.builder?.call(_down) ?? widget.child!;
    return Opacity(
      opacity: enabled ? 1.0 : 0.45,
      // Listener (not GestureDetector) drives the press state so it never
      // competes with the InkWell / real tap in the gesture arena.
      child: Listener(
        onPointerDown: (_) => _set(true),
        onPointerUp: (_) => _set(false),
        onPointerCancel: (_) => _set(false),
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: widget.onTap,
          child: AnimatedScale(
            scale: _down ? 0.93 : 1.0,
            duration: const Duration(milliseconds: 110),
            curve: Curves.easeOut,
            child: Material(
              color: Colors.transparent,
              borderRadius: widget.radius,
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                borderRadius: widget.radius,
                onTap: widget.onTap,
                splashColor: Colors.white24,
                highlightColor: Colors.white10,
                child: visual,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A single reaction icon that rises from the bottom, sways gently, scales in,
/// fades out near the top, then calls [onDone] to remove itself.
class _FloatingHeart extends StatefulWidget {
  final double startX;
  final double screenHeight;
  final int seed;
  final IconData icon;
  final Color baseColor;
  final VoidCallback onDone;
  const _FloatingHeart({
    required this.startX,
    required this.screenHeight,
    required this.seed,
    required this.icon,
    required this.baseColor,
    required this.onDone,
  });

  @override
  State<_FloatingHeart> createState() => _FloatingHeartState();
}

class _FloatingHeartState extends State<_FloatingHeart>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c;
  late final double _sway;
  late final double _size;
  late final double _swayPhase;
  late final Color _color;

  @override
  void initState() {
    super.initState();
    final r = Random(widget.seed);
    _sway = (r.nextDouble() * 60) + 20;
    _size = 30 + r.nextDouble() * 24;
    _swayPhase = r.nextDouble() * pi * 2;
    // Slight shade variants of the theme colour so bursts feel lively.
    final variants = [
      widget.baseColor,
      Color.lerp(widget.baseColor, Colors.white, 0.25)!,
      Color.lerp(widget.baseColor, Colors.pinkAccent, 0.2)!,
      Color.lerp(widget.baseColor, Colors.white, 0.1)!,
    ];
    _color = variants[r.nextInt(variants.length)];
    _c = AnimationController(
      vsync: this,
      duration: Duration(milliseconds: 1500 + r.nextInt(700)),
    )
      ..addStatusListener((s) {
        if (s == AnimationStatus.completed) widget.onDone();
      })
      ..forward();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (_, _) {
        final t = _c.value;
        final rise = widget.screenHeight * 0.75;
        final y =
            (widget.screenHeight - 140) - rise * Curves.easeOut.transform(t);
        final x = widget.startX + sin(_swayPhase + t * pi * 3) * _sway;
        // Fade in fast, hold, fade out over the last third.
        final opacity = t < 0.12
            ? t / 0.12
            : (t > 0.66 ? (1 - (t - 0.66) / 0.34) : 1.0);
        final scale =
            0.5 + 0.5 * Curves.easeOutBack.transform(min(1.0, t * 5));
        return Positioned(
          left: x,
          top: y,
          child: IgnorePointer(
            child: Opacity(
              opacity: opacity.clamp(0.0, 1.0),
              child: Transform.scale(
                scale: scale,
                child: Icon(
                  widget.icon,
                  color: _color,
                  size: _size,
                  shadows: [
                    Shadow(
                        color: _color.withValues(alpha: 0.5), blurRadius: 10),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Corner popup toast: slides in at the top-right, holds, then flies upward
/// while fading — no full-width black bottom bar.
class _FlyingToast extends StatefulWidget {
  final String message;
  final VoidCallback onDone;
  const _FlyingToast({required this.message, required this.onDone});

  @override
  State<_FlyingToast> createState() => _FlyingToastState();
}

class _FlyingToastState extends State<_FlyingToast>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c;

  @override
  void initState() {
    super.initState();
    _c = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2400),
    )
      ..addStatusListener((s) {
        if (s == AnimationStatus.completed) widget.onDone();
      })
      ..forward();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final topPad = MediaQuery.of(context).padding.top + 12;
    return AnimatedBuilder(
      animation: _c,
      builder: (_, _) {
        final t = _c.value;
        // 0–0.12: pop in from slightly below; 0.12–0.62: hold; 0.62–1: fly up + fade.
        double opacity;
        double dy;
        if (t < 0.12) {
          final p = Curves.easeOut.transform(t / 0.12);
          opacity = p;
          dy = 18 * (1 - p);
        } else if (t < 0.62) {
          opacity = 1;
          dy = 0;
        } else {
          final p = Curves.easeIn.transform((t - 0.62) / 0.38);
          opacity = 1 - p;
          dy = -56 * p;
        }
        return Positioned(
          top: topPad + dy,
          right: 12,
          child: IgnorePointer(
            child: Opacity(
              opacity: opacity.clamp(0.0, 1.0),
              child: Material(
                elevation: 6,
                borderRadius: BorderRadius.circular(14),
                color: const Color(0xEE2B2F36),
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    maxWidth: MediaQuery.of(context).size.width * 0.78,
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 14, vertical: 10),
                    child: Text(
                      widget.message,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 13.5,
                        height: 1.25,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Full-screen QR scanner. Pops with the first decoded value.
/// Starts the camera only after the page is mounted (and after the native
/// permission grant in [HomeScreenState._scanQr]) to avoid a false
/// "permission denied" from auto-start racing the permission check.
class QrScannerPage extends StatefulWidget {
  const QrScannerPage({super.key});
  @override
  State<QrScannerPage> createState() => _QrScannerPageState();
}

class _QrScannerPageState extends State<QrScannerPage> {
  late final MobileScannerController _controller;
  bool _done = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _controller = MobileScannerController(
      autoStart: false,
      detectionSpeed: DetectionSpeed.noDuplicates,
      facing: CameraFacing.back,
      formats: const [BarcodeFormat.qrCode],
    );
    WidgetsBinding.instance.addPostFrameCallback((_) => _startCam());
  }

  Future<void> _startCam() async {
    try {
      await _controller.start();
      if (mounted) setState(() => _error = null);
    } catch (e) {
      AppLog.instance.log('QR camera start lỗi: $e');
      if (mounted) {
        setState(() => _error =
            'Không mở được camera.\nThử đóng app rồi mở lại, hoặc kiểm tra quyền Camera.');
      }
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Quét QR code'),
        actions: [
          IconButton(
            tooltip: 'Đèn',
            icon: const Icon(Icons.flashlight_on),
            onPressed: () => _controller.toggleTorch(),
          ),
        ],
      ),
      body: _error != null
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(_error!, textAlign: TextAlign.center),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: () {
                        setState(() => _error = null);
                        _startCam();
                      },
                      child: const Text('Thử lại'),
                    ),
                  ],
                ),
              ),
            )
          : MobileScanner(
              controller: _controller,
              errorBuilder: (context, error, child) {
                final code = error.errorCode.toString();
                final isPerm = code.toLowerCase().contains('permission');
                return Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          isPerm
                              ? 'Camera chưa sẵn sàng.\nNếu đã cấp quyền, bấm Thử lại.'
                              : 'Lỗi camera: ${error.errorCode}',
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: 16),
                        FilledButton(
                          onPressed: _startCam,
                          child: const Text('Thử lại'),
                        ),
                      ],
                    ),
                  ),
                );
              },
              onDetect: (capture) {
                if (_done) return;
                final codes = capture.barcodes;
                final v = codes.isNotEmpty ? codes.first.rawValue : null;
                if (v != null && v.isNotEmpty) {
                  _done = true;
                  Navigator.of(context).pop(v);
                }
              },
            ),
    );
  }
}
