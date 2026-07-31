import 'package:flutter/material.dart';

/// Curated list of const [IconData] the user can pick for a button.
///
/// Buttons store the INDEX into this list (not a raw codepoint), so all icons
/// stay const and Flutter's icon tree-shaking keeps working.
const List<IconData> kButtonIcons = [
  Icons.campaign,
  Icons.volume_up,
  Icons.notifications_active,
  Icons.alarm,
  Icons.music_note,
  Icons.mic,
  Icons.record_voice_over,
  Icons.warning_amber,
  Icons.check_circle,
  Icons.cancel,
  Icons.thumb_up,
  Icons.thumb_down,
  Icons.pets,
  Icons.directions_car,
  Icons.restaurant,
  Icons.local_cafe,
  Icons.sports_soccer,
  Icons.celebration,
  Icons.favorite,
  Icons.star,
  Icons.bolt,
  Icons.pan_tool,
  Icons.waving_hand,
  Icons.sentiment_very_satisfied,
  Icons.sentiment_very_dissatisfied,
  Icons.emoji_emotions,
  Icons.phone,
  Icons.doorbell,
  Icons.directions_run,
  Icons.directions_walk,
  Icons.front_hand,
  Icons.priority_high,
  Icons.help,
  Icons.info,
  Icons.power_settings_new,
  Icons.play_arrow,
  Icons.stop,
  Icons.flag,
  Icons.lightbulb,
  Icons.water_drop,
];

IconData iconAt(int index) {
  if (index < 0 || index >= kButtonIcons.length) return kButtonIcons.first;
  return kButtonIcons[index];
}
