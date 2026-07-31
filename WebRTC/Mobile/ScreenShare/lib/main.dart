import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import 'pages/home_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ScreenShareApp());
}

class ScreenShareApp extends StatelessWidget {
  const ScreenShareApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ScreenShare',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: const Color(0xFF0F1115),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF30A46C),
          brightness: Brightness.dark,
        ),
      ),
      home: const _Bootstrap(),
    );
  }
}

/// Xin quyền cần thiết một lần rồi mở màn hình chính.
/// - notification: cho thông báo của foreground service (Android 13+).
/// - camera: cho chức năng chụp ảnh trước/sau khi đóng vai client.
/// Quyền quay màn hình KHÔNG xin ở đây — Android bắt buộc hỏi bằng hộp thoại
/// riêng mỗi lần bắt đầu chia sẻ.
class _Bootstrap extends StatefulWidget {
  const _Bootstrap();

  @override
  State<_Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<_Bootstrap> {
  @override
  void initState() {
    super.initState();
    _requestPermissions();
  }

  Future<void> _requestPermissions() async {
    await [
      Permission.notification,
      Permission.camera,
    ].request();
  }

  @override
  Widget build(BuildContext context) => const HomePage();
}
