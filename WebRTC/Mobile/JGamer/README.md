# JGamer — NES / SNES / PICO-8 emulator (Android)

A native Kotlin Android app that plays **NES**, **SNES** and **PICO-8** games
using [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) (the engine
behind Lemuroid) with standard **libretro cores**:

| System  | Core (bundled, arm64-v8a)          | ROMs / carts        |
|---------|------------------------------------|---------------------|
| NES     | `fceumm_libretro_android.so`       | `.nes` `.fds` `.unf`|
| SNES    | `snes9x_libretro_android.so`       | `.sfc` `.smc` `.swc`|
| PICO-8  | `retro8_libretro_android.so`       | `.p8` `.p8.png`     |

## Flow
Home has tabs **Recent / NES / SNES / PICO-8**. Import ROMs with **+** (system
picker uses the current tab), tap a game to play. In-game **☰** menu → save/load
state (3 slots), reset, quit. Settings → **Resume last game on launch** +
**Auto save-state on exit** so opening the app drops you straight back in.

## Features
- Per-system on-screen gamepad (NES: B/A; SNES: Y/X/B/A + L/R; PICO-8: O/X), D-pad, Start/Select, menu.
- Save/Load **state slots** + automatic **SRAM** (battery-save) persistence per game.
- **Auto-resume**: snapshot on exit, restore on next launch.
- Hardware gamepad support (via LibretroDroid), vibration, immersive fullscreen.
- Dark JGamer theme.

## Build & install (Windows)
```bat
build.bat            :: builds JGamer.apk into this folder
build.bat install    :: build + adb install to a connected device
build.bat clean
```
`build.bat` auto-detects Android Studio's JDK (JBR) and your SDK, and copies the
finished APK to `JGamer.apk` in this folder.

Requirements: JDK 17+ (Android Studio's JBR is fine), Android SDK, NDK not needed
(cores are prebuilt). Target ABI: **arm64-v8a** (all modern phones).

## Important notes
- **ROMs/carts are NOT included** — copy in your own legally-owned files via the
  **+** button. For PICO-8, `.p8` / `.p8.png` carts you own or made.
- PICO-8 uses the open-source **retro8** core (a reimplementation); compatibility
  is good but not 100% of carts.
- LibretroDroid is **GPLv3**, so this app is GPLv3.
- **Not yet runtime-tested on a device here** — the APK builds cleanly; please
  install and try a ROM per system. Report anything that misbehaves.

## Where cores come from
Prebuilt from the libretro buildbot / LibretroDroid sample, in
`app/src/main/jniLibs/arm64-v8a/`. To add more systems later, drop the matching
`*_libretro_android.so` there and add an entry in `model/GameSystem.kt`.
