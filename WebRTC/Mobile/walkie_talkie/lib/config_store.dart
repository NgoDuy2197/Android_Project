import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter_file_dialog/flutter_file_dialog.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'models.dart';

/// Persists profiles + theme + last address, manages the sounds directory, and
/// exports/imports a profile as a .zip the user picks a location for (Android
/// Storage Access Framework via flutter_file_dialog).
class ConfigStore {
  static const _kProfiles = 'walkie_profiles';
  static const _kConfigLegacy = 'walkie_config'; // single-config migration
  static const _kTheme = 'walkie_theme';
  static const _kAddress = 'walkie_address';

  Future<Directory> soundsDir() async {
    final base = await getApplicationDocumentsDirectory();
    final d = Directory('${base.path}/sounds');
    if (!await d.exists()) await d.create(recursive: true);
    return d;
  }

  Future<File> soundFile(String name) async =>
      File('${(await soundsDir()).path}/$name');

  /// Names of sound files currently cached on this device.
  Future<List<String>> cachedSoundNames() async {
    final dir = await soundsDir();
    final names = <String>[];
    await for (final e in dir.list()) {
      if (e is File) names.add(e.uri.pathSegments.last);
    }
    return names;
  }

  Future<void> clearSounds() async {
    final dir = await soundsDir();
    await for (final e in dir.list()) {
      if (e is File) {
        try {
          await e.delete();
        } catch (_) {}
      }
    }
  }

  // --- Profiles --------------------------------------------------------------
  Future<ProfilesData> loadProfiles() async {
    final prefs = await SharedPreferences.getInstance();
    final s = prefs.getString(_kProfiles);
    if (s != null) {
      try {
        return ProfilesData.fromJson(jsonDecode(s) as Map<String, dynamic>);
      } catch (_) {}
    }
    // Migrate a legacy single config into a default profile.
    final legacy = prefs.getString(_kConfigLegacy);
    if (legacy != null) {
      try {
        final cfg = AppConfig.fromJson(jsonDecode(legacy) as Map<String, dynamic>);
        final data = ProfilesData(profiles: {'Mặc định': cfg}, current: 'Mặc định');
        await saveProfiles(data);
        return data;
      } catch (_) {}
    }
    final data = ProfilesData.initial();
    await saveProfiles(data);
    return data;
  }

  Future<void> saveProfiles(ProfilesData data) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kProfiles, jsonEncode(data.toJson()));
  }

  // --- Theme / address -------------------------------------------------------
  Future<String?> loadTheme() async =>
      (await SharedPreferences.getInstance()).getString(_kTheme);
  Future<void> saveTheme(String mode) async =>
      (await SharedPreferences.getInstance()).setString(_kTheme, mode);

  Future<String?> loadAddress() async =>
      (await SharedPreferences.getInstance()).getString(_kAddress);
  Future<void> saveAddress(String a) async =>
      (await SharedPreferences.getInstance()).setString(_kAddress, a);

  // --- Export / import (user picks the location / file) ----------------------
  /// Zip config.json + sounds and let the user choose where to save it.
  /// Returns the saved path/uri, or null if cancelled.
  Future<String?> exportBundle(AppConfig c, String profileName) async {
    final archive = Archive();
    final cfgBytes = utf8.encode(jsonEncode(c.toJson()));
    archive.addFile(ArchiveFile('config.json', cfgBytes.length, cfgBytes));

    final dir = await soundsDir();
    for (final b in c.buttons) {
      if (!b.hasSound) continue;
      final f = File('${dir.path}/${b.soundFileName}');
      if (await f.exists()) {
        final bytes = await f.readAsBytes();
        archive.addFile(
            ArchiveFile('sounds/${b.soundFileName}', bytes.length, bytes));
      }
    }
    final zip = Uint8List.fromList(ZipEncoder().encode(archive));

    final safeName = profileName.replaceAll(RegExp(r'[^\w\-]'), '_');
    return FlutterFileDialog.saveFile(
      params: SaveFileDialogParams(
        data: zip,
        fileName: 'walkie_$safeName.zip',
        mimeTypesFilter: const ['application/zip'],
      ),
    );
  }

  /// Let the user pick a .zip, extract config + sounds, and return the config.
  Future<AppConfig?> importBundle() async {
    final path = await FlutterFileDialog.pickFile(
      params: const OpenFileDialogParams(
        dialogType: OpenFileDialogType.document,
        copyFileToCacheDir: true,
        fileExtensionsFilter: ['zip'],
      ),
    );
    if (path == null) return null;

    final bytes = await File(path).readAsBytes();
    final archive = ZipDecoder().decodeBytes(bytes);
    final dir = await soundsDir();
    AppConfig? cfg;

    for (final f in archive) {
      if (!f.isFile) continue;
      final content = f.content as List<int>;
      if (f.name == 'config.json') {
        cfg = AppConfig.fromJson(
            jsonDecode(utf8.decode(content)) as Map<String, dynamic>);
      } else if (f.name.startsWith('sounds/')) {
        final outName = f.name.substring('sounds/'.length);
        if (outName.isEmpty) continue;
        await File('${dir.path}/$outName').writeAsBytes(content);
      }
    }
    return cfg;
  }
}
