import 'package:flutter/material.dart';

import 'config_store.dart';

/// Holds the current theme mode and persists changes.
class ThemeController extends ChangeNotifier {
  final ConfigStore _store;
  ThemeMode mode;
  ThemeController(this._store, this.mode);

  bool get isDark => mode == ThemeMode.dark;

  Future<void> toggle() async {
    mode = isDark ? ThemeMode.light : ThemeMode.dark;
    await _store.saveTheme(isDark ? 'dark' : 'light');
    notifyListeners();
  }
}

/// Reusable light/dark toggle used in app bars.
class ThemeToggleButton extends StatelessWidget {
  final ThemeController theme;
  const ThemeToggleButton({super.key, required this.theme});

  @override
  Widget build(BuildContext context) {
    return IconButton(
      tooltip: 'Đổi giao diện sáng/tối',
      icon: Icon(theme.isDark ? Icons.light_mode : Icons.dark_mode),
      onPressed: theme.toggle,
    );
  }
}
