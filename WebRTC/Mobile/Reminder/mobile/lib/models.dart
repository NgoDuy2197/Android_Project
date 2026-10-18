import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

enum RType { countdown, repeat, once }

enum RUnit { minute, hour, day, week, month }

const unitLabel = {
  RUnit.minute: 'phút',
  RUnit.hour: 'giờ',
  RUnit.day: 'ngày',
  RUnit.week: 'tuần',
  RUnit.month: 'tháng',
};

class Reminder {
  Reminder({
    required this.id,
    this.title = '',
    this.note = '',
    this.type = RType.countdown,
    this.durationSec = 25 * 60,
    this.startedAt = 0,
    this.every = 1,
    this.unit = RUnit.day,
    this.anchor = 0,
    this.at = 0,
    this.enabled = true,
    this.useRecording = false,
    this.recording = '',
    this.boostVolume = true,
    this.vibrate = true,
    this.ringSeconds = 60,
    this.nextFire = 0,
    this.emoji = '⏰',
    this.bgImage = '',
  });

  final String id;
  String title;
  String note;
  RType type;

  /// countdown: length and when it was (re)started (ms).
  int durationSec;
  int startedAt;

  /// repeat: every [every] [unit], first time at [anchor] (ms).
  int every;
  RUnit unit;
  int anchor;

  /// once: exact date-time (ms).
  int at;

  bool enabled;
  bool useRecording;
  String recording; // path of the recorded .m4a
  bool boostVolume; // raise alarm volume when the phone is muted / low
  bool vibrate;
  int ringSeconds;

  /// Computed by the native scheduler (0 = nothing scheduled).
  int nextFire;
  /// Icon shown in the list, the notifications and the ringing screen.
  String emoji;
  /// Photo behind the ringing screen ('' = default gradient).
  String bgImage;

  String describe() {
    switch (type) {
      case RType.countdown:
        return 'Đếm ngược ${fmtDuration(Duration(seconds: durationSec))}';
      case RType.repeat:
        final a = DateTime.fromMillisecondsSinceEpoch(anchor);
        final when = switch (unit) {
          RUnit.minute || RUnit.hour => 'từ ${fmtTime(a)}',
          RUnit.day => 'lúc ${fmtTime(a)}',
          RUnit.week => 'lúc ${fmtTime(a)} ${weekday(a)}',
          RUnit.month => 'lúc ${fmtTime(a)} ngày ${a.day}',
        };
        return 'Mỗi ${every == 1 ? '' : '$every '}${unitLabel[unit]} $when';
      case RType.once:
        return 'Lúc ${fmtDateTime(DateTime.fromMillisecondsSinceEpoch(at))}';
    }
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'title': title,
    'note': note,
    'type': type.name,
    'durationSec': durationSec,
    'startedAt': startedAt,
    'every': every,
    'unit': unit.name,
    'anchor': anchor,
    'at': at,
    'enabled': enabled,
    'useRecording': useRecording,
    'recording': recording,
    'boostVolume': boostVolume,
    'vibrate': vibrate,
    'ringSeconds': ringSeconds,
    'nextFire': nextFire,
    'emoji': emoji,
    'bgImage': bgImage,
  };

  static Reminder fromJson(Map<String, dynamic> j) => Reminder(
    id: j['id'] as String,
    title: j['title'] as String? ?? '',
    note: j['note'] as String? ?? '',
    type: RType.values.asNameMap()[j['type']] ?? RType.countdown,
    durationSec: (j['durationSec'] as num?)?.toInt() ?? 1500,
    startedAt: (j['startedAt'] as num?)?.toInt() ?? 0,
    every: (j['every'] as num?)?.toInt() ?? 1,
    unit: RUnit.values.asNameMap()[j['unit']] ?? RUnit.day,
    anchor: (j['anchor'] as num?)?.toInt() ?? 0,
    at: (j['at'] as num?)?.toInt() ?? 0,
    enabled: j['enabled'] as bool? ?? true,
    useRecording: j['useRecording'] as bool? ?? false,
    recording: j['recording'] as String? ?? '',
    boostVolume: j['boostVolume'] as bool? ?? true,
    vibrate: j['vibrate'] as bool? ?? true,
    ringSeconds: (j['ringSeconds'] as num?)?.toInt() ?? 60,
    nextFire: (j['nextFire'] as num?)?.toInt() ?? 0,
    emoji: (j['emoji'] as String?)?.isNotEmpty == true ? j['emoji'] as String : '⏰',
    bgImage: j['bgImage'] as String? ?? '',
  );

  Reminder copy() => Reminder.fromJson(toJson());
}

/// reminders.json in the files dir, shared with the native scheduler.
class ReminderStore {
  static Future<File> _file() async => File('${(await getApplicationSupportDirectory()).path}/reminders.json');

  static Future<Directory> recordingsDir() async {
    final d = Directory('${(await getApplicationSupportDirectory()).path}/recordings');
    await d.create(recursive: true);
    return d;
  }

  static Future<Directory> backgroundsDir() async {
    final d = Directory('${(await getApplicationSupportDirectory()).path}/backgrounds');
    await d.create(recursive: true);
    return d;
  }

  static Future<File> _tipFile() async => File('${(await getApplicationSupportDirectory()).path}/tip_done');
  static Future<bool> tipDone() async => (await _tipFile()).exists();
  static Future<void> setTipDone() async => (await _tipFile()).writeAsString('1');

  static Future<List<Reminder>> load() async {
    final f = await _file();
    if (!await f.exists()) return [];
    try {
      final j = jsonDecode(await f.readAsString()) as Map<String, dynamic>;
      return (j['reminders'] as List? ?? []).map((e) => Reminder.fromJson(e as Map<String, dynamic>)).toList();
    } catch (_) {
      return [];
    }
  }

  static Future<void> save(List<Reminder> list) async {
    final f = await _file();
    final tmp = File('${f.path}.tmp');
    await tmp.writeAsString(jsonEncode({'reminders': list.map((r) => r.toJson()).toList()}));
    await tmp.rename(f.path);
  }
}

String two(int v) => v.toString().padLeft(2, '0');
String fmtTime(DateTime t) => '${two(t.hour)}:${two(t.minute)}';
String fmtDateTime(DateTime t) => '${fmtTime(t)} ${two(t.day)}/${two(t.month)}/${t.year}';
String weekday(DateTime t) => const ['', 'thứ 2', 'thứ 3', 'thứ 4', 'thứ 5', 'thứ 6', 'thứ 7', 'chủ nhật'][t.weekday];

String fmtDuration(Duration d) {
  final h = d.inHours, m = d.inMinutes % 60, s = d.inSeconds % 60;
  if (d.inDays >= 1) return '${d.inDays} ngày ${two(h % 24)}:${two(m)}:${two(s)}';
  return h > 0 ? '$h:${two(m)}:${two(s)}' : '${two(m)}:${two(s)}';
}
