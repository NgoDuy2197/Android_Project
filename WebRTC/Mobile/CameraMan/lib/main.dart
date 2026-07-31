import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import 'app_lock.dart';
import 'config_store.dart';
import 'native_bridge.dart';
import 'pages/capture_page.dart';
import 'pages/config_page.dart';
import 'pages/gallery_page.dart';
import 'pages/remote_page.dart';
import 'theme_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Load the saved accent colour before the first frame so there's no flash.
  try {
    final config = ConfigStore();
    await config.load();
    themeColorNotifier.value = Color(config.themeColor);
  } catch (_) {}
  runApp(const CameraManApp());
}

class CameraManApp extends StatelessWidget {
  const CameraManApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Color>(
      valueListenable: themeColorNotifier,
      builder: (context, seed, _) {
        return MaterialApp(
          title: 'CameraMan',
          debugShowCheckedModeBanner: false,
          theme: ThemeData.dark(useMaterial3: true).copyWith(
            scaffoldBackgroundColor: const Color(0xFF0F1115),
            colorScheme: ColorScheme.fromSeed(
              seedColor: seed,
              brightness: Brightness.dark,
            ),
          ),
          home: const AppLock(child: HomeShell()),
        );
      },
    );
  }
}

/// Bottom-nav shell holding the three tabs: capture, gallery, config. Tabs are
/// kept alive in an [IndexedStack] so switching doesn't rebuild/reload them.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  static const _pages = [
    CapturePage(),
    GalleryPage(),
    RemotePage(),
    ConfigPage(),
  ];

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await _requestPermissions();
    // Restore the saved state: if motion detection was left on, resume it so
    // the user never has to re-enable it after reopening the app.
    final config = ConfigStore();
    await config.load();
    if (config.motionEnabled) {
      final running = await NativeBridge.instance.isMotionRunning();
      if (!running) await NativeBridge.instance.startMotion();
    }
  }

  // Camera + mic are needed to capture; notifications for the foreground
  // service banner on Android 13+. Requested up front so the widget and motion
  // detection work later without the app being open.
  Future<void> _requestPermissions() async {
    await [
      Permission.camera,
      Permission.microphone,
      Permission.notification,
    ].request();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(index: _index, children: _pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.camera_alt_outlined),
            selectedIcon: Icon(Icons.camera_alt),
            label: 'Chụp/Quay',
          ),
          NavigationDestination(
            icon: Icon(Icons.photo_library_outlined),
            selectedIcon: Icon(Icons.photo_library),
            label: 'Thư viện',
          ),
          NavigationDestination(
            icon: Icon(Icons.devices_other_outlined),
            selectedIcon: Icon(Icons.devices_other),
            label: 'Từ xa',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
            label: 'Cấu hình',
          ),
        ],
      ),
    );
  }
}
