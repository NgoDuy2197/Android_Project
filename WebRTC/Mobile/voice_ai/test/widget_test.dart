// Basic smoke test: the app builds.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:voice_ai/main.dart';

void main() {
  testWidgets('VoiceAiApp builds', (WidgetTester tester) async {
    await tester.pumpWidget(VoiceAiApp(theme: ThemeController(AppTheme.light)));
    await tester.pump();
    expect(find.byType(MaterialApp), findsOneWidget);
  });
}
