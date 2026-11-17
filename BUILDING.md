# Building SH1 Fog Wallpaper

Silent Hill 1 environment renderer as a live wallpaper.

## Notable engineering notes

- **Per-engine surface bindings.** Android runs several engines of a
  WallpaperService in one process (the system preview AND the real wallpaper);
  each registers its own surface (generation id) and the newest live one
  owns the output — the preview's teardown can no longer null the real
  wallpaper's window.
- **16 KB page-size compatibility** (Google Play requirement for Android 15+
  devices): `libsh1.so` is linked with `-Wl,-z,max-page-size=16384` /
  `-Wl,-z,common-page-size=16384` + `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`,
  shipped uncompressed and 16 KB-aligned inside the APK.
- **Signed release builds**: `sh1.keystore` (personal project keystore,
  password `sh1wall`, alias `sh1`) is committed so local and CI builds share
  one signature. Regenerate your own for anything public:
  `keytool -genkeypair -keystore sh1.keystore -alias sh1 -keyalg RSA -keysize 2048 -validity 10950`
- GitHub Actions workflow builds debug + release APKs on every push.

## Requirements

- JDK 17
- Android SDK (platform 34, build-tools 34+)
- NDK 27.2.12479018, CMake 3.22.1
- Gradle 8.9 (or use `./gradlew`)

## Build

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties   # once
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
```

## CI

`.github/workflows/android.yml` runs on ubuntu-latest (SDK/NDK preinstalled)
and uploads both APKs as artifacts on every push / PR / manual run.

## Install

1. Build or download the APK, install it (arm64-v8a devices, Android 6.0+).
2. Home screen -> long-press -> Wallpapers -> Live wallpapers -> **SH1 Fog**.
3. The engine idles (~0 CPU) whenever the wallpaper is not visible.
