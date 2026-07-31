import 'package:flutter/material.dart';

import 'config_store.dart';

/// Global accent (seed) color for the app theme. The Config tab updates it and
/// [CameraManApp] rebuilds its [MaterialApp] in response.
final ValueNotifier<Color> themeColorNotifier =
    ValueNotifier<Color>(const Color(ConfigStore.defaultThemeColor));

/// A small palette the user can pick from in the Config tab.
const List<int> kThemePalette = [
  0xFF30A46C, // green (default)
  0xFF3B6EF0, // blue
  0xFF8E4EC6, // purple
  0xFFE5484D, // red
  0xFFF5A623, // amber
  0xFF0EA5E9, // sky
  0xFFEC4899, // pink
  0xFF14B8A6, // teal
];
