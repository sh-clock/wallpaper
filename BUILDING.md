# Building the SH1 Fog Wallpaper fork (v5)

Fork of Patrick Zedler's Doodle live wallpaper app in which the Silent
Hill 1 environment renderer is the app's single live wallpaper. The
original Doodle wallpapers were removed; the Environment settings page
exposes the game's 21 env presets, Auto Mode (day/night by device clock)
and the engine's runtime weather states.

## What's new in v5

- **All original Doodle wallpapers removed**: the 14 wallpaper classes, the
  SVG pipeline, the parallax/size settings pages, the color pickers, the
  random-wallpaper machinery and the Doodle renderer service are gone.
- **Environment settings page** (Material Design, Doodle components only):
  Auto Mode switch, current-mode row, env-mode picker and weather picker
  (radio-style bottom sheets modeled on the language chooser). The 21 mode
  names/summaries and 9 weather states are extracted verbatim from
  `cpp/fog_poc.c`.
- **Auto Mode**: day 06:00-18:00 -> env preset 1 (THR day street), night
  18:00-06:00 -> env preset 6 (night street), evaluated on the device clock
  (TIME_TICK/TIME_CHANGED/TIMEZONE_CHANGED) and on every settings change.
- **Live settings delivery**: `Sh1Native.env()/weather()` bridge into the
  engine's own mode paths (`live_apply_preset` / Gfx_MapEnvSet;
  `sh_particle_weather_target_set` / sharedFunc_800D0B18). Boot values use
  the PoC's own CLI flags `--preset N` / `--weather-target N`.
- **16 KB page-size compatibility** (unchanged from v3): `libsh1.so` linked
  with `-Wl,-z,max-page-size=16384`, shipped uncompressed and aligned.
- **minSdk 23**, arm64-v8a only, signed release builds with `sh1.keystore`
  (personal project keystore, password `sh1wall`, alias `sh1`).
- versionCode 45, versionName `6.0.0-sh1.5`, applicationId
  `xyz.zedler.patrick.doodle.SH1.b0l27` (randomized suffix, dot-separated
  because hyphens are invalid in applicationId segments).

## Requirements

- JDK 17+ (built with Temurin 21)
- Android SDK (platform 35, build-tools 34+)
- NDK 27.2.12479018, CMake 3.22.1
- Gradle 8.12 (or use `./gradlew`)

## Build

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties   # once
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
```

## CI

`.github/workflows/android.yml` runs on ubuntu-latest (SDK/NDK preinstalled).
On every push it builds both APKs and smoke-tests them; when a releasable
change lands (assets -> PATCH bump, engine/shell code -> MINOR bump) it
bumps `version.properties`, tags and publishes BOTH APKs as RELEASE
ASSETS on the release (never workflow artifacts — those stay at zero).
See README 'Versioning (the 1.0.0 scheme)' and 'Release signing'.

## Install

1. Build or download the APK, install it (arm64-v8a devices, Android 6.0+).
2. Tap the set-wallpaper button or pick **SH1 Fog v5** from the system
   live-wallpaper picker. The SH1 engine is the app's only wallpaper.
3. The Environment page (Auto Mode, env preset, weather) applies changes
   live to the running engine; the engine idles (~0 CPU) whenever the
   wallpaper is not visible.
