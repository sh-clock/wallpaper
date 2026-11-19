# Fog Wallpaper

A non-commercial fan project: Silent Hill 1 rooms as an Android live
wallpaper, software-rendered with engine-faithful logic — the game's own
environment presets, fog, snow and rain particles, the rooms' original
animated props, and drifting camera tracks at PSX resolution. No ads, no
telemetry, no tracking; fully offline.

The app shell, settings UI and Material Design come from Patrick Zedler's
open-source [Doodle Android](https://github.com/patzly/doodle-android)
live wallpaper app (GPL-3.0). The original Doodle wallpapers were removed
in this fork; the Silent Hill 1 environment renderer is the one and only
wallpaper.

## The scene corpus (44 rooms)

The wallpaper enumerates its scenes from **one file**:
`app/src/main/assets/sh1/camdat.json` — every `models[]` entry is one scene
(one room GLB + its camera tracks + its environment). Each GLB carries
the room's own `SH1X.env` EXTRAs (the WorldEnv pair + weather recipe the
game's map overlay headers define), re-resolved from the decompilation at
rip time; the camdat mirrors those values verbatim.

The corpus spans the town street (THR), the school (SU/SC), the hospital
(HP) and nightmare hospital (HU), the sewers with LIVE water floors
(DR/DRU), Nowhere (ER), the lakeside amusement park (APU) and the
nightmare town (SPU). 32 of the 44 rooms boot the dark
flashlight-hell environments — 21 pair `{7,4}` (nightmare) + 11 `{6,3}`
(night), 16 of them raining; the other 12 are 7 day-street `{1,1}`,
3 interior-day `{2,2}` and 2 thick-fog Nowhere `{9,9}`. Weather across
the corpus: 16 rooms rain, 11 snow, 17 none.

Adding a scene = **two file edits, no Java and no engine changes**:

1. drop the room's `.glb` into `app/src/main/assets/sh1/`;
2. add a `models[]` entry to `camdat.json` (`file`, `env` from the GLB's
   own `SH1X.env`, `bounds`, and at least one track — e.g. a conservative
   `fixed` camera inside the room's 40 m chunk cell).

CI rebuilds the app and bumps the version one minor step
(1.7.x → 1.7.x+1) on every asset change — see below. The per-scene
camera tracks can be authored with the Camtrack editor (in-app, or the
upstream `tools/camtrack.html` editor) and pasted into `camdat.json`.

## Features

* 44 Silent Hill 1 rooms, rasterized exactly like the PSX original
  (320×224, PSX wobble, 15-bit color, ordered dither)
* The rooms' ORIGINAL environment pairs (fog, flashlight, world tint) +
  the room-original weather recipes (rain in the sewers, snow on the
  street) — boots from each GLB's `SH1X.env` EXTRAs
* The rooms' embedded animations play by default (fans spin, rides
  orbit, doors rattle, the sewer water floors scroll — the SH1X
  propAnims drivers, EXTRAs-authoritative)
* All 21 environment presets of the game (MAP_EFFECTS_INFOS, verbatim)
* Auto Mode: day / night presets following the device clock, optional
  sun-time schedule
* The engine's runtime weather states: snow and rain with wind 0–3
* Strict center-crop presentation (never stretched) with a
  user-adjustable crop window — draggable in the Camtrack editor and via
  sliders
* Camtrack editor: a native Material 3 editor for camera tracks,
  day/night environments and per-track weather, with a live engine
  preview
* Rendering backends: software (default), OpenGL ES and Vulkan
  (experimental)
* Battery-friendly: 30 fps renderer that idles at ~0 CPU when the screen
  is off; no wakelocks; arm64-v8a

## Versioning + CI

The version rides `version.properties` at the repo root
(`versionName`/`versionCode`, currently the 1.7.x lineage). Every push to
`main` that changes `app/src/main/assets/**` (a scene drop), the engine
or the Java/res/gradle wiring bumps **one minor step**
(1.7.40 → 1.7.41), tags `v1.7.41` and publishes both APKs (debug +
release) as release assets of that tag. No more v11–v27 version jumps —
the lineage moves forward one step per change.

## Building

Requires JDK 17 and an Android SDK with `platforms;android-35`,
`build-tools;35.0.0`, `ndk;27.2.12479018`, `cmake;3.22.1`.

```
./gradlew assembleRelease
./gradlew testReleaseUnitTest
```

See `BUILDING.md` and `CUSTOM.md` for the fork's engineering history.

## Engine provenance

The native renderer (`app/src/main/cpp/fog_poc.c`) is the v34 PoC engine
(verbatim desktop behavior, proven by md5-identical deterministic shots):
the SH1 decompilation's fog/lighting/particle logic ported line-for-line,
with the v10_7 Android presentation seams (ANativeWindow blit, settings
bridge, battery freeze) grafted as `#ifdef __ANDROID__` blocks. The
GLES/Vulkan backends are the Swanstation-derived hw seam (opt-in).

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
