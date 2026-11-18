# Silent Hill 1 — Fog Wallpaper (Android live wallpaper)

Native Android **live wallpaper** running the SH1 PoC environment renderer
(single-file C99 software rasterizer, engine-native 320×224 raster, 30 fps
tick lockstep) via the NDK — no OpenGL, no game engine, no Java renderer:
`libsh1.so` blits the engine framebuffer straight into the wallpaper's
`Surface` through `ANativeWindow`.

> **This project is 100% AI Slop / Vibe Coded and is non-commercial**
>
> **This project's **CODE** is licenced under the GPL but any included assets **ARE NOT**. Assets are copyrighted by Konami. Using any assets for commercial use is **strictly prohibited** and must be removed immediately** — see
> [Konami's copyright notice](https://eu-support.konami.com/hc/en-gb/articles/9648771731479-Copyrights-Career-Opportunities-Goodies).

The bundled scenes (v10, the v32.2 engine): the **HP0003 hospital
corridor** (interior day), the **THRF9FD night streets** (with mapLink
neighbors THRF9FC/FE) and the **SU0203 nightmare-school fan room**, plus
the v32 special-case set — **18 more scenes** covering every animated
special case in the game: the hospital 16-fan ceiling array, the
wheelchair wheels, the rattling reception/locker doors, the boiler
spinners, the ferris wheel, the balloon floaters, the corpse walker, the
carousel, the hospital elevator doors, the levitating stone, the spinning
bin, the scroll belt, the chained door swing, the waterfall, the fence
fall, the water ripples and the TV room. The camera drifts along an
authored CamTrack in each scene; the engine rotates through the manifest
at every wallpaper restart. Build your own scenes
from your own copy of the game with the
[PoC repo's tools](https://github.com/sh-clock/PoC) (see its TOOLS.md).

## Adding a scene (the aim-7 protocol)

1. Drop `YOURROOM.glb` + `YOURROOM_camdat.json` into
   `app/src/main/assets/sh1/` (rip with the PoC `tools/ipd2gltf.py`,
   author the camdat with the PoC `tools/camtrack.html`).
2. Add one entry to `app/src/main/assets/sh1/scenes.json`:
   `{"glb": "YOURROOM.glb", "camdat": "YOURROOM_camdat.json"}`.
3. Push to main — CI bumps the version by one minor level (e.g.
   1.7.36 -> 1.7.37), rebuilds and publishes both APKs as a Release.
   No Java edits required; the camdat's `models[].env` object carries the
   per-scene environment (fog pair, weather, lens flare, flashlight,
   `animsDemo` to play the cutscene/event-gated drivers).

## Build

```
echo "sdk.dir=/path/to/android-sdk" > local.properties   # once
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
```

Requirements: JDK 17, Android SDK (platform 34), NDK 27.2.12479018,
CMake 3.22.1. Full details (16 KB page-size APK alignment, signing, CI) are in
[BUILDING.md](BUILDING.md).

## Architecture

* `app/src/main/cpp/fog_poc.c` — the PoC engine, **verbatim** except for a
  4th platform backend (`#ifdef __ANDROID__`: the X11 present path swapped
  for `ANativeWindow_lock` → nearest-neighbor aspect-fit blit →
  `ANativeWindow_unlockAndPost`, surviving surface destroy/recreate under a
  mutex) and an `__ANDROID__` branch in the realtime dispatch. The patched
  build renders byte-identical PNG output vs the desktop binary for the
  same deterministic `--shot` (matching md5).
* `app/src/main/cpp/sh1_jni.c` — JNI bootstrap: owns the Surface lifecycle,
  drives the engine's `main()` on a native thread with a programmatically
  built argv (`--glb <filesDir>/sh1/HP0003.glb --camdat <filesDir>/sh1/camdat.json
  --input <filesDir>/sh1 --window --fps 30 --no-hud`), forwards UI keys.
* `Sh1WallpaperService.java` — the `WallpaperService.Engine` mapping:
  `onSurfaceCreated` → asset bootstrap + engine start (once per process),
  `onVisibilityChanged` → battery gate (invisible = ~0 CPU),
  `onSurfaceDestroyed`/`onDestroy` → retract **this** engine's surface.
  Android runs the system preview and the real wallpaper as separate
  engines in one process; each registers its own surface binding
  (generation id) and the newest live one owns the output — the fix for
  the classic "black wallpaper after Set wallpaper" bug.
* `MainActivity.java` — launcher stub: routes the user to the system
  live-wallpaper preview (no demo controls; the wallpaper is the app).
* `app/src/main/assets/sh1/` — the bundled scene, copied to app-private
  storage on first launch (HP0003.glb + camdat.json + source IPD files).
  The APK is arm64-v8a, Android 6.0+.

## Install

1. Build or download the APK, install it (arm64-v8a devices, Android 6.0+).
2. Home screen → long-press → Wallpapers → Live wallpapers → **SH1 Fog**.
3. The engine idles (~0 CPU) whenever the wallpaper is not visible.

## Repository layout

| Path | What it is |
|---|---|
| `app/src/main/cpp/` | the engine + Android backend + JNI bridge |
| `app/src/main/java/com/sh1/poc/` | `Sh1WallpaperService`, `Sh1Native`, `MainActivity` |
| `app/src/main/assets/sh1/` | the bundled sample scene (see disclaimer above) |
| `BUILDING.md` | build requirements, signing, 16 KB page-size notes |
| `sh1.keystore` | the personal release keystore (regenerate your own for public use) |

## Acknowledgements

The engine is a port of the [SH1 PoC renderer](https://github.com/sh-clock/PoC)
(see its [ACKNOWLEDGEMENTS.md](https://github.com/sh-clock/PoC/blob/main/ACKNOWLEDGEMENTS.md)):
[silent-hill-decomp](https://github.com/shdecompilations/silent-hill-decomp)
(decompilation the engine math is replicated from) and
[SwanStation](https://github.com/libretro/swanstation) (PS1 GPU/GTE ground
truth — the GPL-3.0 maintained fork of the DuckStation core). Silent Hill
and its assets are © Konami — this is a non-commercial
fan research project.

## CI / Releases

`.github/workflows/android.yml` runs on every push/PR/manual dispatch and
builds both the debug and the signed release APK on a plain `ubuntu-latest`
runner (SDK/NDK preinstalled), with a smoke test that verifies each APK is
a valid zip containing `libsh1.so`. Push a tag `v*` (e.g. `v6.0`) to publish
a **GitHub Release** with both APKs attached — release assets are free and
permanent, unlike workflow artifacts.
