import 'package:audioplayers/audioplayers.dart';

/// Thinking-cue sounds so you know when the AI starts and is still processing.
class Sfx {
  final _one = AudioPlayer();
  final _loop = AudioPlayer();

  Future<void> init() async {
    try {
      await _loop.setReleaseMode(ReleaseMode.loop);
    } catch (_) {}
  }

  void _play(AudioPlayer p, String f) =>
      p.play(AssetSource('sounds/$f')).catchError((_) {});

  void thinkStart() => _play(_one, 'think_start.wav');
  void ready() => _play(_one, 'ready.wav');
  void error() => _play(_one, 'error.wav');

  Future<void> thinkLoopStart() async {
    try {
      await _loop.play(AssetSource('sounds/think_loop.wav'));
    } catch (_) {}
  }

  Future<void> thinkLoopStop() async {
    try {
      await _loop.stop();
    } catch (_) {}
  }

  Future<void> dispose() async {
    await _one.dispose();
    await _loop.dispose();
  }
}
