import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:vibration/vibration.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import 'motion_detector.dart';
import 'sfx.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MotionBoxingApp());
}

class MotionBoxingApp extends StatelessWidget {
  const MotionBoxingApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Motion Boxing',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: const Color(0xFF14161C),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFE5484D),
          brightness: Brightness.dark,
        ),
      ),
      home: const GameScreen(),
    );
  }
}

enum GamePhase { menu, playing, gameOver }

class GameScreen extends StatefulWidget {
  const GameScreen({super.key});

  @override
  State<GameScreen> createState() => _GameScreenState();
}

class _GameScreenState extends State<GameScreen> {
  final _motion = MotionDetector();
  final _sfx = Sfx();
  final _rng = Random();

  GamePhase _phase = GamePhase.menu;
  bool _resting = false;
  bool _canVibrate = false;

  // Fight state
  int _round = 1;
  double _playerHp = 100;
  double _oppHp = 100;
  double _oppMaxHp = 100;

  int _combo = 0;

  // Opponent attack
  Timer? _attackTimer;
  Timer? _windowTimer;
  final List<Timer> _restTimers = [];
  bool _incoming = false;
  bool _blockedThisWindow = false;

  // Visual feedback
  bool _hitFlash = false;
  bool _hurtFlash = false;
  double _oppScale = 1.0;
  String _feedback = '';

  // --- Stats -----------------------------------------------------------------
  int _score = 0;
  int _bestCombo = 0;
  int _punches = 0;
  int _hits = 0;
  int _misses = 0;
  int _attacksFaced = 0;
  int _blocksOk = 0;
  int _hitsTaken = 0;
  int _kos = 0;
  double _dmgDealt = 0;
  double _dmgTaken = 0;
  DateTime? _startTime;
  DateTime? _endTime;

  @override
  void initState() {
    super.initState();
    _motion.onPunch = _onPunch;
    _motion.onBlock = _onBlock;
    _sfx.init();
    Vibration.hasVibrator().then((v) {
      if (mounted) _canVibrate = v == true;
    });
  }

  /// Strong vibration for hits/KO (falls back to a haptic tick if the device
  /// has no controllable vibrator).
  void _buzz(int ms, {int amplitude = 255}) {
    if (_canVibrate) {
      try {
        Vibration.vibrate(duration: ms, amplitude: amplitude);
        return;
      } catch (_) {}
    }
    HapticFeedback.heavyImpact();
  }

  void _buzzPattern(List<int> pattern, List<int> intensities) {
    if (_canVibrate) {
      try {
        Vibration.vibrate(pattern: pattern, intensities: intensities);
        return;
      } catch (_) {}
    }
    HapticFeedback.heavyImpact();
  }

  @override
  void dispose() {
    _motion.dispose();
    _cancelTimers();
    _sfx.dispose();
    WakelockPlus.disable();
    super.dispose();
  }

  void _cancelTimers() {
    _attackTimer?.cancel();
    _windowTimer?.cancel();
    for (final t in _restTimers) {
      t.cancel();
    }
    _restTimers.clear();
  }

  // --- Difficulty (scales endlessly with round) ------------------------------
  double _oppMaxHpFor(int r) => 90 + (r - 1) * 40; // grows -> longer fights
  int _attackDelayMs(int r) =>
      max(650, 2100 - r * 120) + _rng.nextInt(1300);
  int _blockWindowMs(int r) => max(400, 1000 - r * 55); // shrinks -> harder
  double _attackDmg(int r) => 8 + r * 2.5;
  double _missChance(int r) => min(0.30, (r - 1) * 0.03);

  // --- Flow ------------------------------------------------------------------
  void _startGame() {
    _cancelTimers();
    setState(() {
      _phase = GamePhase.playing;
      _resting = false;
      _round = 1;
      _playerHp = 100;
      _oppMaxHp = _oppMaxHpFor(1);
      _oppHp = _oppMaxHp;
      _combo = 0;
      _incoming = false;
      _score = 0;
      _bestCombo = 0;
      _punches = _hits = _misses = 0;
      _attacksFaced = _blocksOk = _hitsTaken = _kos = 0;
      _dmgDealt = _dmgTaken = 0;
      _feedback = 'Đấm đi!';
    });
    _startTime = DateTime.now();
    _endTime = null;
    WakelockPlus.enable();
    _motion.start();
    _sfx.roundStart();
    _scheduleAttack();
  }

  void _endGame() {
    _endTime = DateTime.now();
    _motion.stop();
    _cancelTimers();
    _incoming = false;
    _resting = false;
    _sfx.gameOver();
    WakelockPlus.disable();
    setState(() => _phase = GamePhase.gameOver);
  }

  // --- Player punch ----------------------------------------------------------
  void _onPunch(double strength) {
    if (_phase != GamePhase.playing || _resting) return;
    _punches++;
    // Punching while you should be blocking, or a difficulty-based whiff.
    final miss = _incoming || _rng.nextDouble() < _missChance(_round);
    if (miss) {
      _misses++;
      _sfx.miss();
      HapticFeedback.selectionClick();
      setState(() => _feedback = 'Trượt!');
      return;
    }
    final dmg = 7 + _combo * 0.6 + strength * 7;
    _hits++;
    _sfx.hit();
    // A real, punchy buzz on every landed hit; harder combos hit longer.
    _buzz(50 + min(_combo, 6) * 8);
    setState(() {
      _combo++;
      _bestCombo = max(_bestCombo, _combo);
      _score += dmg.round();
      _dmgDealt += dmg;
      _oppHp -= dmg;
      _hitFlash = true;
      _oppScale = 0.86;
      _feedback = _combo >= 3 ? 'COMBO x$_combo! 🔥' : 'ĐẤM TRÚNG!';
    });
    _restTimers.add(Timer(const Duration(milliseconds: 90), () {
      if (mounted) setState(() => _oppScale = 1.0);
    }));
    _restTimers.add(Timer(const Duration(milliseconds: 120), () {
      if (mounted) setState(() => _hitFlash = false);
    }));
    if (_oppHp <= 0) _knockout();
  }

  // --- Player block ----------------------------------------------------------
  void _onBlock() {
    if (_phase != GamePhase.playing || _resting) return;
    if (_incoming && !_blockedThisWindow) {
      _blockedThisWindow = true;
      _blocksOk++;
      _sfx.block();
      HapticFeedback.mediumImpact();
      _windowTimer?.cancel();
      setState(() {
        _score += 15;
        _feedback = 'ĐỠ CHUẨN! 🛡️';
        _incoming = false;
      });
      _scheduleAttack();
    }
  }

  // --- Knockout -> rest -> next (harder) round -------------------------------
  void _knockout() {
    _kos++;
    _attackTimer?.cancel();
    _windowTimer?.cancel();
    // KO thud + a strong celebratory buzz, then a clear victory fanfare.
    _sfx.knockout();
    _sfx.roundEnd();
    _buzzPattern(const [0, 90, 60, 90, 60, 200], const [0, 255, 0, 255, 0, 255]);
    setState(() {
      _oppHp = 0;
      _resting = true;
      _incoming = false;
      _feedback = 'HẠ GỤC! 🥊';
    });
    _restTimers.add(Timer(const Duration(milliseconds: 260), () {
      if (_phase != GamePhase.playing) return;
      _sfx.victory(); // rõ ràng: nhạc chiến thắng
      if (mounted) setState(() => _feedback = 'CHIẾN THẮNG! 🏆');
    }));
    // Rest a moment after the victory music finishes.
    _restTimers.add(Timer(const Duration(milliseconds: 2250), () {
      if (_phase != GamePhase.playing) return;
      if (mounted) setState(() => _feedback = 'Nghỉ hiệp…');
    }));
    // Then a clear "new round" cue and the fight resumes, harder.
    _restTimers.add(Timer(const Duration(milliseconds: 3000), () {
      if (_phase != GamePhase.playing) return;
      setState(() {
        _round++;
        _oppMaxHp = _oppMaxHpFor(_round);
        _oppHp = _oppMaxHp;
        _playerHp = (_playerHp + 15).clamp(0, 100);
        _resting = false;
        _feedback = 'HIỆP $_round!';
      });
      _sfx.newRound(); // rõ ràng: nhạc bắt đầu hiệp mới
      _scheduleAttack();
    }));
  }

  // --- Opponent attacks ------------------------------------------------------
  void _scheduleAttack() {
    if (_phase != GamePhase.playing || _resting) return;
    _attackTimer?.cancel();
    _attackTimer =
        Timer(Duration(milliseconds: _attackDelayMs(_round)), _launchAttack);
  }

  void _launchAttack() {
    if (_phase != GamePhase.playing || _resting) return;
    _attacksFaced++;
    _sfx.alert();
    HapticFeedback.lightImpact();
    setState(() {
      _incoming = true;
      _blockedThisWindow = false;
      _feedback = 'GẠT ĐỠ! (xoay cổ tay)';
    });
    _windowTimer =
        Timer(Duration(milliseconds: _blockWindowMs(_round)), () {
      if (_phase != GamePhase.playing || _resting) return;
      if (_incoming && !_blockedThisWindow) {
        final dmg = _attackDmg(_round);
        _hitsTaken++;
        _sfx.hurt();
        HapticFeedback.vibrate();
        setState(() {
          _playerHp -= dmg;
          _dmgTaken += dmg;
          _combo = 0;
          _incoming = false;
          _hurtFlash = true;
          _feedback = 'Trúng đòn! -${dmg.round()}';
        });
        _restTimers.add(Timer(const Duration(milliseconds: 220), () {
          if (mounted) setState(() => _hurtFlash = false);
        }));
        if (_playerHp <= 0) {
          _playerHp = 0;
          _endGame();
          return;
        }
      }
      _scheduleAttack();
    });
  }

  // --- UI --------------------------------------------------------------------
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: switch (_phase) {
          GamePhase.menu => _menuView(),
          GamePhase.playing => _playView(),
          GamePhase.gameOver => _summaryView(),
        },
      ),
    );
  }

  Widget _menuView() {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text('🥊', style: TextStyle(fontSize: 92)),
            const SizedBox(height: 8),
            const Text('MOTION BOXING',
                style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800)),
            const SizedBox(height: 6),
            const Text('Chế độ Endless — đấm tới khi hết máu!',
                style: TextStyle(color: Colors.white54)),
            const SizedBox(height: 16),
            const _HowTo(),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _startGame,
              icon: const Icon(Icons.play_arrow),
              label: const Text('Bắt đầu'),
              style: FilledButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 40, vertical: 16),
                textStyle:
                    const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _playView() {
    final oppFrac = (_oppHp / _oppMaxHp).clamp(0.0, 1.0);
    final playerFrac = (_playerHp / 100).clamp(0.0, 1.0);
    return AnimatedContainer(
      duration: const Duration(milliseconds: 120),
      color: _hurtFlash
          ? const Color(0x55E5484D)
          : (_hitFlash ? const Color(0x22FFFFFF) : Colors.transparent),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 0),
            child: Row(
              children: [
                _chip('Hiệp $_round'),
                const SizedBox(width: 8),
                _chip('KO $_kos'),
                const Spacer(),
                _chip('Điểm $_score'),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 32),
                  child:
                      _healthBar(oppFrac, const Color(0xFFE5484D), 'Đối thủ'),
                ),
                const SizedBox(height: 18),
                AnimatedScale(
                  scale: _oppScale,
                  duration: const Duration(milliseconds: 90),
                  child: Text(_resting ? '😵' : _oppFace(oppFrac),
                      style: const TextStyle(fontSize: 128)),
                ),
                const SizedBox(height: 14),
                if (_incoming)
                  _banner('⚠  GẠT ĐỠ NGAY!', const Color(0xFFE5484D))
                else if (_resting)
                  _banner('🔔  Nghỉ hiệp', const Color(0xFFF5A623))
                else
                  Text(_feedback,
                      style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                          color: _combo >= 3
                              ? const Color(0xFFF5A623)
                              : Colors.white70)),
                if (_combo >= 2 && !_incoming && !_resting)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text('COMBO x$_combo',
                        style: const TextStyle(
                            fontSize: 15, color: Color(0xFFF5A623))),
                  ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(32, 0, 32, 8),
            child: _healthBar(playerFrac, const Color(0xFF30A46C), 'Bạn'),
          ),
          const Padding(
            padding: EdgeInsets.only(bottom: 10),
            child: Text('Vung tay = ĐẤM   •   Xoay cổ tay = GẠT ĐỠ',
                style: TextStyle(fontSize: 12, color: Colors.white38)),
          ),
        ],
      ),
    );
  }

  Widget _summaryView() {
    final dur = (_endTime ?? DateTime.now())
        .difference(_startTime ?? DateTime.now());
    String two(int n) => n.toString().padLeft(2, '0');
    final durText = '${two(dur.inMinutes)}:${two(dur.inSeconds % 60)}';
    final acc = _punches == 0 ? 0 : (_hits * 100 / _punches).round();
    final blockRate =
        _attacksFaced == 0 ? 0 : (_blocksOk * 100 / _attacksFaced).round();

    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('😵', style: TextStyle(fontSize: 80)),
            const Text('KẾT THÚC TRẬN',
                style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800)),
            const SizedBox(height: 4),
            Text('Điểm: $_score',
                style: const TextStyle(
                    fontSize: 20,
                    color: Color(0xFFF5A623),
                    fontWeight: FontWeight.bold)),
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.05),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Column(
                children: [
                  _statRow('⏱️', 'Thời gian trụ được', durText),
                  _statRow('🏁', 'Tới hiệp', '$_round'),
                  _statRow('💥', 'Số lần hạ gục (KO)', '$_kos'),
                  const Divider(height: 20),
                  _statRow('👊', 'Cú đấm tung ra', '$_punches'),
                  _statRow('🎯', 'Đấm trúng', '$_hits'),
                  _statRow('💨', 'Đấm trượt', '$_misses'),
                  _statRow('📊', 'Độ chính xác', '$acc%'),
                  _statRow('⚔️', 'Tổng sát thương gây ra',
                      _dmgDealt.round().toString()),
                  const Divider(height: 20),
                  _statRow('🛡️', 'Đòn đối thủ tung', '$_attacksFaced'),
                  _statRow('✅', 'Đỡ thành công', '$_blocksOk'),
                  _statRow('❌', 'Bị trúng đòn', '$_hitsTaken'),
                  _statRow('📈', 'Tỉ lệ đỡ', '$blockRate%'),
                  _statRow('🩸', 'Sát thương phải nhận',
                      _dmgTaken.round().toString()),
                  const Divider(height: 20),
                  _statRow('🔥', 'Combo cao nhất', 'x$_bestCombo'),
                ],
              ),
            ),
            const SizedBox(height: 20),
            FilledButton.icon(
              onPressed: _startGame,
              icon: const Icon(Icons.replay),
              label: const Text('Chơi lại'),
              style: FilledButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 36, vertical: 15),
                textStyle:
                    const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
              ),
            ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  // --- Small widgets ---------------------------------------------------------
  String _oppFace(double frac) {
    if (frac > 0.66) return '😀';
    if (frac > 0.33) return '😠';
    if (frac > 0.0) return '😫';
    return '😵';
  }

  Widget _banner(String text, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(30),
      ),
      child: Text(text,
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
    );
  }

  Widget _chip(String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.white10,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(text,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
    );
  }

  Widget _statRow(String icon, String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Text(icon, style: const TextStyle(fontSize: 16)),
          const SizedBox(width: 10),
          Expanded(
              child: Text(label,
                  style: const TextStyle(fontSize: 14, color: Colors.white70))),
          Text(value,
              style:
                  const TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }

  Widget _healthBar(double frac, Color color, String label) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label,
            style: const TextStyle(fontSize: 12, color: Colors.white54)),
        const SizedBox(height: 4),
        ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: Stack(
            children: [
              Container(height: 16, color: Colors.white12),
              AnimatedFractionallySizedBox(
                duration: const Duration(milliseconds: 200),
                widthFactor: frac,
                child: Container(height: 16, color: color),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _HowTo extends StatelessWidget {
  const _HowTo();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(14),
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Cách chơi (nghe tiếng là chính)',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
          SizedBox(height: 10),
          _Line(icon: '👊', text: 'Vung mạnh tay = ĐẤM. Trúng có tiếng "thụp".'),
          SizedBox(height: 6),
          _Line(icon: '💨', text: 'Nghe tiếng "vút" là đấm trượt (đừng đấm khi bị tấn công).'),
          SizedBox(height: 6),
          _Line(
              icon: '🔔',
              text: 'Nghe tiếng bíp gấp = xoay cổ tay để GẠT ĐỠ ngay.'),
          SizedBox(height: 6),
          _Line(icon: '🥊', text: 'Hạ gục đối thủ → chuông nghỉ hiệp → hiệp mới khó hơn.'),
          SizedBox(height: 6),
          _Line(icon: '♾️', text: 'Endless: càng lâu đối thủ càng nhiều máu & đánh nhanh hơn.'),
          SizedBox(height: 10),
          Text('Bật loa to. Cầm chắc máy, cẩn thận xung quanh!',
              style: TextStyle(fontSize: 12, color: Colors.white38)),
        ],
      ),
    );
  }
}

class _Line extends StatelessWidget {
  final String icon;
  final String text;
  const _Line({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(icon, style: const TextStyle(fontSize: 18)),
        const SizedBox(width: 10),
        Expanded(child: Text(text, style: const TextStyle(fontSize: 13))),
      ],
    );
  }
}
