import 'dart:async';
import 'dart:io';

import 'package:audioplayers/audioplayers.dart';

import 'sound_settings.dart';

/// Sound-effects engine. Since the player usually can't see the screen while
/// punching, audio is the main feedback channel.
///
/// - Short, rapidly repeated sounds (hits, misses, blocks...) go through a
///   round-robin pool of low-latency players so they can overlap.
/// - Longer cues (bells, fanfares) use a small pool of regular players, which
///   also handles long custom files that the low-latency backend can't.
/// - Every event follows [SoundSettings]: bundled default, off, or a custom
///   file. A missing/broken custom file falls back to the default sound.
class Sfx {
  Sfx(this.settings);

  final SoundSettings settings;

  final List<AudioPlayer> _fast = List.generate(8, (_) => AudioPlayer());
  final List<AudioPlayer> _long = List.generate(3, (_) => AudioPlayer());
  final AudioPlayer _preview = AudioPlayer();
  int _fastIdx = 0;
  int _longIdx = 0;

  /// Custom files that failed to play this session -> use the default.
  final Set<String> _broken = {};

  bool enabled = true;

  Future<void> init() async {
    for (final p in _fast) {
      try {
        await p.setReleaseMode(ReleaseMode.stop);
        await p.setPlayerMode(PlayerMode.lowLatency);
      } catch (_) {}
    }
    for (final p in [..._long, _preview]) {
      try {
        await p.setReleaseMode(ReleaseMode.stop);
      } catch (_) {}
    }
    await settings.load();
    // Warm the asset cache so the first punch isn't delayed.
    try {
      await AudioCache.instance.loadAll(
          SfxEvent.values.map((e) => 'sounds/${e.asset}').toList());
    } catch (_) {}
  }

  String? _customPath(SfxEvent e) {
    final c = settings.of(e);
    final path = c.path;
    if (c.mode != SoundMode.custom || path == null) return null;
    if (_broken.contains(path)) return null;
    try {
      if (!File(path).existsSync()) return null;
    } catch (_) {
      return null;
    }
    return path;
  }

  void _start(AudioPlayer p, SfxEvent e, double volume) {
    final custom = _customPath(e);
    final Source src = custom != null
        ? DeviceFileSource(custom)
        : AssetSource('sounds/${e.asset}');
    p
        .play(src, volume: volume)
        .timeout(const Duration(seconds: 4))
        .catchError((Object _) {
      if (custom == null) return;
      // Broken custom file -> remember it and play the bundled sound.
      _broken.add(custom);
      p
          .play(AssetSource('sounds/${e.asset}'), volume: volume)
          .catchError((Object _) {});
    });
  }

  void play(SfxEvent e) {
    if (!enabled || settings.muted) return;
    final c = settings.of(e);
    if (c.mode == SoundMode.off) return;
    final vol = (c.volume * settings.masterVolume).clamp(0.0, 1.0);
    if (vol <= 0) return;
    final AudioPlayer p;
    if (e.fast) {
      p = _fast[_fastIdx];
      _fastIdx = (_fastIdx + 1) % _fast.length;
    } else {
      p = _long[_longIdx];
      _longIdx = (_longIdx + 1) % _long.length;
    }
    _start(p, e, vol);
  }

  /// Plays the current choice for [e] from the settings screen (ignores mute,
  /// keeps the volumes so the user hears what the game will sound like).
  Future<void> preview(SfxEvent e) async {
    final c = settings.of(e);
    if (c.mode == SoundMode.off) return;
    try {
      await _preview.stop();
    } catch (_) {}
    final vol = (c.volume * settings.masterVolume).clamp(0.0, 1.0);
    _start(_preview, e, vol);
  }

  /// Forget a failed custom file (e.g. after the user picked a new one).
  void clearFallbacks() => _broken.clear();

  bool isFallingBack(SfxEvent e) {
    final c = settings.of(e);
    return c.mode == SoundMode.custom && _customPath(e) == null;
  }

  void hit() => play(SfxEvent.hit);
  void miss() => play(SfxEvent.miss);
  void combo() => play(SfxEvent.combo);
  void alert() => play(SfxEvent.alert);
  void block() => play(SfxEvent.block);
  void hurt() => play(SfxEvent.hurt);
  void lowHp() => play(SfxEvent.lowHp);
  void knockout() => play(SfxEvent.knockout);
  void roundStart() => play(SfxEvent.roundStart);
  void roundEnd() => play(SfxEvent.roundEnd);
  void gameOver() => play(SfxEvent.gameOver);
  void victory() => play(SfxEvent.victory);
  void newRound() => play(SfxEvent.newRound);
  void tap() => play(SfxEvent.tap);

  Future<void> dispose() async {
    for (final p in [..._fast, ..._long, _preview]) {
      try {
        await p.dispose();
      } catch (_) {}
    }
  }
}
