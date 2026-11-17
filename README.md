# Silent Hill 1 — Fog Wallpaper (Android live wallpaper)

Native Android **live wallpaper** running the SH1 PoC environment renderer
(single-file C99 software rasterizer, engine-native 320×224 raster, 30 fps
tick lockstep) via the NDK — no OpenGL, no game engine, no Java renderer:
`libsh1.so` blits the engine framebuffer straight into the wallpaper's
`Surface` through `ANativeWindow`.

> **This project is 100% AI Slop / Vibe Coded and is non-commerical**
>
> **This project's **CODE** is licenced under the GPL but any included assets **ARE NOT**. Assets are copywrited by Konami. Using any assets for commerical use is **strictly prohibited** and must be removed immediately** — see
> [Konami's copyright notice](https://eu-support.konami.com/hc/en-gb/articles/9648771731479-Copyrights-Career-Opportunities-Goodies).

*This statement is included verbatim as required. Note the correct spelling of the two trademarks concerned: "commercial", "copyrighted".*

The bundled sample scene is the **HP0003 hospital corridor** (interior day
environment — the engine runs no weather particles indoors), with the
camera drifting along a CamTrack corridor track. Build your own scenes
from your own copy of the game with the
[PoC repo's tools](https://github.com/sh-clock/PoC) (see its TOOLS.md).

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
[DuckStation](https://github.com/stenzek/duckstation) (PS1 GPU/GTE ground
truth). Silent Hill and its assets are © Konami — this is a non-commercial
fan research project.

## CI / Releases

`.github/workflows/android.yml` runs on every push/PR/manual dispatch and
builds both the debug and the signed release APK on a plain `ubuntu-latest`
runner (SDK/NDK preinstalled), with a smoke test that verifies each APK is
a valid zip containing `libsh1.so`. Push a tag `v*` (e.g. `v6.0`) to publish
a **GitHub Release** with both APKs attached — release assets are free and
permanent, unlike workflow artifacts.
