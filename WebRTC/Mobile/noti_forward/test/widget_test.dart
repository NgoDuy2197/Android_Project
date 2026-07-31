// Basic smoke test: the app builds and shows its title.

import 'package:flutter_test/flutter_test.dart';

import 'package:noti_forward/main.dart';

void main() {
  testWidgets('App renders the Noti Forward screen', (WidgetTester tester) async {
    await tester.pumpWidget(const NotiApp());
    await tester.pump();

    // The app bar title should be present.
    expect(find.text('Noti Forward'), findsWidgets);
  });
}
