import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'models.dart';
import 'tryon_page.dart';
import 'widgets.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Overlay maths assume a portrait preview.
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      systemNavigationBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
    ),
  );
  final store = CartStore();
  await store.load();
  runApp(TryOnApp(store: store));
}

class TryOnApp extends StatelessWidget {
  const TryOnApp({super.key, required this.store});
  final CartStore store;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'TryOn',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(seedColor: kAccent, brightness: Brightness.dark),
        useMaterial3: true,
      ),
      home: TryOnPage(store: store),
    );
  }
}
