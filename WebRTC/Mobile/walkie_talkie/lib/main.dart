import 'package:flutter/material.dart';

import 'config_store.dart';
import 'theme_controller.dart';
import 'speaker_screen.dart';
import 'remoter_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final store = ConfigStore();
  final savedTheme = await store.loadTheme();
  final theme = ThemeController(
    store,
    savedTheme == 'light' ? ThemeMode.light : ThemeMode.dark,
  );
  runApp(WalkieApp(store: store, theme: theme));
}

class WalkieApp extends StatelessWidget {
  final ConfigStore store;
  final ThemeController theme;
  const WalkieApp({super.key, required this.store, required this.theme});

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF3B82F6);
    return ListenableBuilder(
      listenable: theme,
      builder: (context, _) => MaterialApp(
        title: 'Walkie Talkie',
        debugShowCheckedModeBanner: false,
        themeMode: theme.mode,
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(seedColor: seed),
        ),
        darkTheme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(
              seedColor: seed, brightness: Brightness.dark),
        ),
        home: ConnectScreen(store: store, theme: theme),
      ),
    );
  }
}

/// Role picker. Speaker hosts the session; Remoter connects to it.
class ConnectScreen extends StatelessWidget {
  final ConfigStore store;
  final ThemeController theme;
  const ConnectScreen({super.key, required this.store, required this.theme});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Walkie Talkie'),
        actions: [ThemeToggleButton(theme: theme)],
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('Chọn vai trò cho máy này',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
                const SizedBox(height: 6),
                Text(
                  'Hai máy cùng mạng Wi-Fi. Bên bị điều khiển (phát tiếng) chọn Speaker; bên bấm nút chọn Remoter.',
                  textAlign: TextAlign.center,
                  style:
                      TextStyle(color: Theme.of(context).hintColor, fontSize: 13),
                ),
                const SizedBox(height: 24),
                _RoleCard(
                  icon: Icons.speaker,
                  title: 'Speaker (bị điều khiển)',
                  subtitle: 'Máy này phát ra âm thanh. Tạo phòng và hiện mã QR.',
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                        builder: (_) =>
                            SpeakerScreen(store: store, theme: theme)),
                  ),
                ),
                const SizedBox(height: 14),
                _RoleCard(
                  icon: Icons.gamepad,
                  title: 'Remoter (điều khiển)',
                  subtitle: 'Máy này có các nút. Quét QR / nhập IP để kết nối.',
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                        builder: (_) =>
                            RemoterScreen(store: store, theme: theme)),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RoleCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  const _RoleCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      elevation: 0,
      color: cs.surfaceContainerHighest,
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Row(
            children: [
              CircleAvatar(
                radius: 26,
                backgroundColor: cs.primaryContainer,
                child: Icon(icon, size: 28, color: cs.onPrimaryContainer),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.w600)),
                    const SizedBox(height: 4),
                    Text(subtitle,
                        style: TextStyle(
                            fontSize: 12.5,
                            color: Theme.of(context).hintColor)),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right),
            ],
          ),
        ),
      ),
    );
  }
}
