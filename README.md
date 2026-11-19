# Fog Wallpaper

A non-commercial fan project: the Silent Hill 1 fog street as an Android
live wallpaper, software-rendered with engine-faithful logic — the game's
own environment presets, fog, snow and rain particles, and drifting camera
tracks at PSX resolution. No ads, no telemetry, no tracking; fully offline.

The app shell, settings UI and Material Design come from Patrick Zedler's
open-source [Doodle Android](https://github.com/patzly/doodle-android)
live wallpaper app (GPL-3.0). The original Doodle wallpapers were removed
in this fork; the Silent Hill 1 environment renderer is the one and only
wallpaper.

## Features

* The Silent Hill 1 town street, rasterized exactly like the PSX original
  (320×224, PSX wobble, 15-bit color, ordered dither)
* All 21 environment presets of the game (MAP_EFFECTS_INFOS, verbatim)
* Auto Mode: day street (06:00–18:00) / night street (18:00–06:00)
  following the device clock, optional sun-time schedule
* The engine's runtime weather states: snow and rain with wind 0–3
* Strict center-crop presentation (never stretched) with a user-adjustable
  crop window — draggable in the Camtrack editor and via sliders
* Camtrack editor: a native Material 3 editor for camera tracks, day/night
  environments and per-track weather, with a live engine preview
* Rendering backends: software (default), OpenGL ES and Vulkan
  (experimental)
* Battery-friendly: 30 fps renderer that idles at ~0 CPU when the screen
  is off; no wakelocks; arm64-v8a

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
