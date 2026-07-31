import 'dart:async';
import 'dart:math';

import 'package:sensors_plus/sensors_plus.dart';

/// Turns raw phone motion into game gestures:
///  - **Punch**: a sharp linear acceleration spike (arm thrust), measured with
///    the gravity-removed accelerometer.
///  - **Block / gạt**: a fast wrist rotation, measured with the gyroscope.
class MotionDetector {
  StreamSubscription? _accSub;
  StreamSubscription? _gyroSub;

  void Function(double strength)? onPunch; // strength 0..1
  void Function()? onBlock;

  // Tunable sensitivity.
  double punchThreshold = 14.0; // m/s^2 (gravity removed)
  double blockThreshold = 5.0; // rad/s

  DateTime _lastPunch = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime _lastBlock = DateTime.fromMillisecondsSinceEpoch(0);

  bool _running = false;

  void start() {
    if (_running) return;
    _running = true;

    _accSub = userAccelerometerEventStream(
      samplingPeriod: SensorInterval.gameInterval,
    ).listen((e) {
      final m = sqrt(e.x * e.x + e.y * e.y + e.z * e.z);
      if (m < punchThreshold) return;
      final now = DateTime.now();
      if (now.difference(_lastPunch).inMilliseconds < 350) return;
      _lastPunch = now;
      final strength = ((m - punchThreshold) / punchThreshold).clamp(0.0, 1.0);
      onPunch?.call(strength);
    });

    _gyroSub = gyroscopeEventStream(
      samplingPeriod: SensorInterval.gameInterval,
    ).listen((e) {
      final m = sqrt(e.x * e.x + e.y * e.y + e.z * e.z);
      if (m < blockThreshold) return;
      final now = DateTime.now();
      if (now.difference(_lastBlock).inMilliseconds < 450) return;
      _lastBlock = now;
      onBlock?.call();
    });
  }

  void stop() {
    _running = false;
    _accSub?.cancel();
    _gyroSub?.cancel();
    _accSub = null;
    _gyroSub = null;
  }

  void dispose() => stop();
}
