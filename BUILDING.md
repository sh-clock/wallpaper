# Building the SH1 Fog Wallpaper fork

Fork of Patrick Zedler's Doodle live wallpaper app in which the Silent
Hill 1 environment renderer is the app's single live wallpaper. The
original Doodle wallpapers were removed; the Environment settings page
exposes the game's 21 env presets, Auto Mode (day/night by device clock)
and the engine's runtime weather states; the scene corpus is enumerated
from `app/src/main/assets/sh1/camdat.json` (44 rooms).

## Requirements

- JDK 17+ (CI uses Temurin 17)
- Android SDK (platform 35, build-tools 34+)
- NDK 27.2.12479018, CMake 3.22.1
- Gradle 8.12 (or use `./gradlew`)

## Build

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties   # once
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the committed `sh1.keystore` (password
`sh1wall`, alias `sh1`). The version rides `version.properties`
(`versionName=1.7.x`, `versionCode` = the integer form) — do NOT bump it
by hand on asset changes; CI does that (below).

## CI (`.github/workflows/android.yml`)

On every push to `main`:

1. **bump** — if the diff vs the push's base touches
   `app/src/main/assets/**` (the scene corpus), `app/src/main/cpp/**`,
   `app/src/main/java/**`, `app/src/main/res/**`, the manifest or the
   gradle wiring: bump `version.properties` ONE minor step
   (1.7.40 → 1.7.41, versionCode 10740 → 10741), commit with
   `[skip ci]`, tag `v1.7.41` and push both.
2. **build** — checkout main (sees the bump), JDK 17,
   `assembleDebug` + `assembleRelease`, smoke-test the APKs (the native
   lib + all 44 scene GLBs + camdat.json are inside), then publish BOTH
   APKs as RELEASE ASSETS of the `v1.7.41` tag. A manual `v*` PAT tag
   push rebuilds + releases under that tag on demand.

PRs build + smoke-test but never bump/release. The arm64-v8a-only ABI
set comes from `app/build.gradle` (`ndk.abiFilters`) — the SH1 software
rasterizer builds one `.so`.

## Adding a scene (aim 7 — no Java, no engine edits)

1. Rip the room GLB (the upstream `tools/ipd2gltf.py`; the v33+ ripper
   emits the `SH1X` EXTRAs: env, propAnims, chunk header).
2. Drop it into `app/src/main/assets/sh1/`.
3. Add one `models[]` entry to `camdat.json`: `file`, `env` (from the
   GLB's own `SH1X.env`: `pair` = presetIdx0/1, `weatherTarget` = the
   field17 recipe), `bounds`, and ≥ 1 track. A conservative fallback
   track is a `fixed` camera inside the room's 40 m chunk cell
   (`SH1X.header.chunkX/chunkZ`: engine x ∈ [chunkX*40, chunkX*40+40),
   glTF z NEGATED) at eye height with fov 55–60 — outside the cell
   NOTHING renders (engine-true subcell window).

Push; CI rebuilds, bumps 1.7.x → 1.7.x+1 and releases. The app copies
every file in `assets/sh1` into its private storage at boot
(dynamic enumeration — no Java list to maintain).

## Install

1. Build or download the APK, install it (arm64-v8a devices, Android
   6.0+).
2. Tap the set-wallpaper button or pick **Fog Wallpaper** from the
   system live-wallpaper picker. The SH1 engine is the app's only
   wallpaper.
3. The Environment page (Auto Mode, env preset, weather) applies changes
   live to the running engine; the engine idles (~0 CPU) whenever the
   wallpaper is not visible.
