# Fog Wallpaper

A non-commercial fan project: Silent Hill 1 as an Android live wallpaper,
software-rendered with engine-faithful logic — the game's own environment
presets, fog, snow and rain particles, the SH1X special animations and
drifting camera tracks at PSX resolution. No ads, no telemetry, no
tracking; fully offline.

The app shell, settings UI and Material Design come from Patrick Zedler's
open-source [Doodle Android](https://github.com/patzly/doodle-android)
live wallpaper app (GPL-3.0). The original Doodle wallpapers were removed
in this fork; the Silent Hill 1 environment renderer is the one and only
wallpaper.

## Features

* 44 scenes (the town streets, both schools, the hospital, the resort,
  the sewers, the amusement park, Nowhere), rasterized exactly like the
  PSX original (320×224, PSX wobble, 15-bit color, ordered dither)
* All 21 environment presets of the game (MAP_EFFECTS_INFOS, verbatim)
* Auto Mode: the scene's own day/night environments follow the device
  clock (or a sun-time schedule) — derived from each room's UNBIASED
  SH1X.env EXTRAs (night-only locations like the amusement park never
  render in daylight; interiors never rain)
* The engine's runtime weather states: snow and rain with wind 0–3
* The SH1X prop animations play in every scene (nightmare-school fans,
  the amusement-park ferris wheel and carousel, the town-center TV
  noise, the sewers' waterfall and water FX) — the verbatim decomp driver
  math
* The user programmable light (Harry's flashlight: world/camera anchor,
  authored aim, disable, engine-true day-mode suppression)
* Strict center-crop presentation (never stretched) with a
  user-adjustable crop window — draggable in the Camtrack editor and via
  sliders
* Camtrack editor: a native Material 3 editor for camera tracks, day/night
  environments, per-track weather and the user light, with a live engine
  preview
* Rendering backends: software (default), OpenGL ES and Vulkan
  (experimental)
* Battery-friendly: 30 fps renderer that idles at ~0 CPU when the screen
  is off; no wakelocks; arm64-v8a

## Adding a scene (the drop-in flow)

1. Drop the room's `.glb` (from the PoC repo's `tools/ipd2gltf.py` ripper)
   into `app/src/main/assets/sh1/`.
2. Add a `models[]` entry to `app/src/main/assets/sh1/camdat.json` — the
   `file` field + at least one camera track. The World & Weather fields
   can be authored in the camtrack editor (or derived: the editor reads
   the GLB's SH1X.env and proposes the room-original defaults).
3. Commit and push to `main`. CI detects the asset change, builds both
   APKs and publishes the release — no other step is needed.

## Versioning (the 1.0.0 scheme)

The version lives in `version.properties` and the CI bump job is its only
writer. Starting from **1.0.0**:

* an **asset-only** change (scenes under `app/src/main/assets/`) bumps the
  PATCH level — 1.0.0 → 1.0.1 → 1.0.2 …
* a change that also touches the **engine/native/shell/gradle** code bumps
  the MINOR level — 1.0.0 → 1.1.0 …
* `versionCode` increments by 1 on every bump; the tag is `v<name>`.

The old series (v1.7.x / v10.x / v11) are retired.

## Release signing

The release APK is signed with the key from the repo secrets when they are
set, falling back to the committed shared **test** key (`sh1.keystore`,
password `sh1wall`) until then. To use your own key:

1. Generate a keypair locally (never commit it):

   ```sh
   keytool -genkeypair -v \
     -keystore sh1-release.keystore \
     -alias sh1release -keyalg RSA -keysize 4096 -validity 10000 \
     -storetype PKCS12
   ```

   (Answer the dname prompts — CN/O/L/C — and set BOTH passwords. Keep the
   `.keystore` file and its passwords safe: a Play-Store-style upgrade
   signature requires the SAME key forever.)

2. Add the four repo secrets (repo → Settings → Secrets and variables →
   Actions → New repository secret):

   | Secret | Value |
   |---|---|
   | `WALLPAPER_KEYSTORE_BASE64` | `base64 -w0 sh1-release.keystore` output |
   | `WALLPAPER_KEYSTORE_PASSWORD` | the keystore password |
   | `WALLPAPER_KEY_ALIAS` | `sh1release` |
   | `WALLPAPER_KEY_PASSWORD` | the key password |

3. Done — the next CI release build signs with your key (the base64 is
   decoded at build time; the key itself never enters the repo or any
   artifact).

Users can also import their own GLBs + camdats on-device (BYOA) without a
rebuild — the scene picker is dynamic (the camdat's `models[]` drives it).

## Building

Requires JDK 17 and an Android SDK with `platforms;android-35`,
`build-tools;35.0.0`, `ndk;27.2.12479018`, `cmake;3.22.1`.

```
./gradlew assembleRelease
./gradlew testReleaseUnitTest
```

See `BUILDING.md` and `CUSTOM.md` for the fork's engineering history.

## Credits

* [Doodle Android](https://github.com/patzly/doodle-android) by Patrick
  Zedler — base app and UI (GPL-3.0)
* [silent-hill-decomp](https://github.com/shdecompilations/silent-hill-decomp)
  — game logic reference (GPL-3.0)
* [SwanStation](https://github.com/libretro/swanstation) — software
  rasterizer reference (GPL-3.0)

## Disclaimer

Silent Hill is a trademark of Konami. This is a non-commercial fan work
following Konami's published fan-content policy.
