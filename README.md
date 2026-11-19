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

> **Reset (2026-11-19):** this repository was cleared and rebuilt from the
> correct `fog-wallpaper-v10_7-source.zip` base (the previous tree was an
> extremely broken alpha build). The 44-scene corpus and the SH1X engine
> (v33.8 — the PoC's: prop-anim drivers, room-original env EXTRAs, the
> user programmable light, water FX, mapLink) were then ported onto that
> base. The versioning was restarted with it: ONE linear series
> (10.7 → 10.8 → 10.9 …), bumped by CI on every releasable change — the
> old repo's competing v1.7.x / v11.0.x tags are retired.

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
   APKs, bumps the version ONE minor level (10.7 → 10.8) and publishes
   the release — no other step is needed.

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
