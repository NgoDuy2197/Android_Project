// Basic smoke test: the app builds and shows its title.

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:noti_forward/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('noti_forward/native'),
      (call) async {
        switch (call.method) {
          case 'isPermissionGranted':
          case 'isIgnoringBattery':
            return false;
          case 'applyBackground':
          case 'requestPermission':
          case 'requestIgnoreBattery':
            return null;
          case 'ttsInfo':
            return {'languages': <String>[], 'voices': <Map>[]};
          case 'listInstalledApps':
            return <Map>[];
          default:
            return null;
        }
      },
    );
  });

  testWidgets('App renders the Noti Forward screen', (WidgetTester tester) async {
    await tester.pumpWidget(const NotiApp());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    // The app bar title should be present.
    expect(find.text('Noti Forward'), findsWidgets);
    expect(find.text('Thiết lập nhanh'), findsOneWidget);
    expect(find.text('App được forward'), findsOneWidget);
  });
}
