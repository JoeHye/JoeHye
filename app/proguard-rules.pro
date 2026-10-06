# Project-specific R8 rules. Room, Compose, AndroidX and Health Connect ship their own consumer rules.

# Enums persisted or restored by name (SoundMode in the presets table, ThemeMode in preferences,
# saved UI state). Keep their constants and valueOf/values so names survive R8.
-keepclassmembers enum com.blackcloudgroup.binaural.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
