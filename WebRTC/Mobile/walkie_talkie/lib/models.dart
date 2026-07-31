/// A configurable soundboard button on the remoter. Pressing it tells the
/// speaker to play [soundFileName]. Icon is stored as an index into
/// [kButtonIcons] so it stays a const IconData.
class SoundButton {
  String id;
  String name;
  int iconIndex;
  String? soundFileName; // file name inside the app's sounds dir, e.g. "<id>.m4a"

  SoundButton({
    required this.id,
    required this.name,
    this.iconIndex = 0,
    this.soundFileName,
  });

  bool get hasSound => soundFileName != null && soundFileName!.isNotEmpty;

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'iconIndex': iconIndex,
        'soundFileName': soundFileName,
      };

  factory SoundButton.fromJson(Map<String, dynamic> j) => SoundButton(
        id: j['id'] as String,
        name: (j['name'] ?? '') as String,
        iconIndex: (j['iconIndex'] ?? 0) as int,
        soundFileName: j['soundFileName'] as String?,
      );
}

/// The full persisted app configuration.
class AppConfig {
  List<SoundButton> buttons;

  AppConfig({List<SoundButton>? buttons}) : buttons = buttons ?? [];

  Map<String, dynamic> toJson() => {
        'version': 1,
        'buttons': buttons.map((b) => b.toJson()).toList(),
      };

  factory AppConfig.fromJson(Map<String, dynamic> j) => AppConfig(
        buttons: ((j['buttons'] ?? []) as List)
            .map((e) => SoundButton.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// A named set of profiles (scenarios). The remoter keeps several profiles and
/// picks the active one from the top of the screen.
class ProfilesData {
  Map<String, AppConfig> profiles;
  String current;

  ProfilesData({required this.profiles, required this.current});

  AppConfig get config =>
      profiles[current] ?? (profiles.values.isEmpty ? AppConfig() : profiles.values.first);

  List<String> get names => profiles.keys.toList();

  factory ProfilesData.initial() =>
      ProfilesData(profiles: {'Mặc định': AppConfig()}, current: 'Mặc định');

  Map<String, dynamic> toJson() => {
        'current': current,
        'profiles': profiles.map((k, v) => MapEntry(k, v.toJson())),
      };

  factory ProfilesData.fromJson(Map<String, dynamic> j) {
    final raw = (j['profiles'] ?? {}) as Map<String, dynamic>;
    final profiles = raw.map(
        (k, v) => MapEntry(k, AppConfig.fromJson(v as Map<String, dynamic>)));
    if (profiles.isEmpty) profiles['Mặc định'] = AppConfig();
    var current = (j['current'] ?? '') as String;
    if (!profiles.containsKey(current)) current = profiles.keys.first;
    return ProfilesData(profiles: profiles, current: current);
  }
}
