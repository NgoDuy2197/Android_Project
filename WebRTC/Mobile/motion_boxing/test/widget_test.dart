// Basic smoke test: the app builds and shows the menu.
import 'package:flutter_test/flutter_test.dart';

import 'package:motion_boxing/main.dart';

void main() {
  testWidgets('MotionBoxingApp builds', (WidgetTester tester) async {
    await tester.pumpWidget(const MotionBoxingApp());
    await tester.pump();
    expect(find.text('MOTION BOXING'), findsOneWidget);
  });
}
