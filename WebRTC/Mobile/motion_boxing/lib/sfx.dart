import 'package:audioplayers/audioplayers.dart';

/// Sound-effects engine. Since the player usually can't see the screen while
/// punching, audio is the main feedback channel. Uses a small round-robin pool
/// of low-latency players so rapid punches can overlap.
class Sfx {
  final List<AudioPlayer> _pool = List.generate(6, (_) => AudioPlayer());
  int _idx = 0;
  bool enabled = true;

  Future<void> init() async {
    for (final p in _pool) {
      try {
        await p.setReleaseMode(ReleaseMode.stop);
        await p.setPlayerMode(PlayerMode.lowLatency);
      } catch (_) {}
    }
  }

  void _play(String file, {double volume = 1.0}) {
    if (!enabled) return;
    final p = _pool[_idx];
    _idx = (_idx + 1) % _pool.length;
    // Fire and forget; round-robin lets sounds overlap.
    p.play(AssetSource('sounds/$file'), volume: volume).catchError((_) {});
  }

  void hit() => _play('hit.wav');
  void miss() => _play('miss.wav', volume: 0.85);
  void alert() => _play('alert.wav');
  void block() => _play('block.wav');
  void hurt() => _play('hurt.wav');
  void knockout() => _play('knockout.wav');
  void roundStart() => _play('bell_start.wav');
  void roundEnd() => _play('bell_end.wav');
  void gameOver() => _play('gameover.wav');

  /// Clear triumphant fanfare when the opponent is knocked out.
  void victory() => _play('victory.wav');

  /// Clear "new round begins" cue after the rest.
  void newRound() => _play('new_round.wav');

  Future<void> dispose() async {
    for (final p in _pool) {
      try {
        await p.dispose();
      } catch (_) {}
    }
  }
}
