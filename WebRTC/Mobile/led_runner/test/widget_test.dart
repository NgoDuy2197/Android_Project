// Basic smoke test: the app builds and shows the config screen.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:led_runner/main.dart';

void main() {
  testWidgets('LedApp builds', (WidgetTester tester) async {
    await tester.pumpWidget(const LedApp());
    await tester.pump();
    expect(find.byType(MaterialApp), findsOneWidget);
  });
}
