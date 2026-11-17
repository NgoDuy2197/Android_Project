# RemoteLink — Desktop viewer

Python/PySide6 client for the RemoteLink Android app. Full docs (architecture, protocols,
permissions, test checklist) are in `../../Mobile/RemoteLink/README.md`.

- `run.bat` — create `.venv`, install deps, launch the GUI.
- `build_exe.bat` — package `dist\RemoteLink.exe` and copy to `WebRTC\__Result`.

Three connection modes: **Stream (H.264)**, **VNC**, **ADB (no phone app)**.

Files: `app.py` (UI) · `clients.py` (StreamClient / VncClient / AdbClient) ·
`protocol.py` (wire format, keep in sync with `net/StreamServer.kt`) · `make_icon.py` (icon).
