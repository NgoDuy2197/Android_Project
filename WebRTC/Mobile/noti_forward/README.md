# Noti Forward

Flutter + Android app that listens to system notifications and forwards them by:

- reading them aloud with TTS, and/or
- posting them to a Discord webhook

All forwarding runs in a native `NotificationListenerService`, so it keeps working even when the Flutter UI is closed.

## Features

- **App picker** — choose which apps to forward (allowlist / denylist / all)
- **Discord webhook** — configurable username + test button
- **TTS** — language/voice picker, speech rate, read title or full content
- **Keep-alive** — foreground service + battery-optimisation checklist
- **Filters** — keyword filter, skip ongoing notifications, anti-repeat gap
- **Setup checklist** — notification access, battery, keep-alive, apps, webhook

## Setup

1. Open the project in Android Studio / VS Code with Flutter.
2. Run on a physical Android device (`minSdk 24+`).
3. Grant **Notification access** for Noti Forward.
4. Pick apps (or leave “Tất cả”), choose mode, configure Discord/TTS as needed.
5. Optionally disable battery optimisation and enable keep-alive for reliability.

```bash
cd WebRTC/Mobile/noti_forward
flutter pub get
flutter run
```

Build APK:

```bash
flutter build apk --release
# or Windows: build_apk.bat
```

## Project layout

- `lib/` — Flutter settings UI (app picker, Discord, TTS, logs)
- `android/.../NotiForwardService.kt` — notification listener + forward logic
- `android/.../AppConfig.kt` — reads Flutter `shared_preferences` from native code
