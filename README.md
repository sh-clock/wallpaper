# Silent Hill 1 PoC Renderer — Android Port (proof of concept)

Native Android port of the `fog_poc` v23 Silent Hill 1 environment
renderer (single-file C99 software rasterizer, 320x224 engine raster,
authentic 30 fps tick lockstep) as a fullscreen test app.

This is the **renderer-on-device validation build** for the wallpaper
fork. It proves: NDK compilation of the engine as-is, the
ANativeWindow present path, asset bootstrap, and surface
lifecycle handling — everything the `WallpaperService` version needs.

## Build

```
./gradlew assembleDebug        # or assembleRelease (unsigned)
```

Requirements: JDK 17, Android SDK (platform 34), NDK 27.2.12479018,
CMake 3.22.1. CI: `.github/workflows/android.yml` builds both APKs on
a plain `ubuntu-latest` runner.

## What was changed in the engine (2 surgical patches)

`fog_poc.c` is otherwise **verbatim** — verified: patched build renders
byte-identical PNG output (matching md5) vs the original v23 Linux
binary for the same deterministic `--shot` invocation.

1. **A 4th platform backend** (`#ifdef __ANDROID__`, alongside the
   Win32 / SDL2 / X11 backends): the X11 loop with `XPutImage` swapped
   for `ANativeWindow_lock` -> nearest-neighbor aspect-fit scale blit of
   the engine's `0xAARRGGBB` framebuffer -> `ANativeWindow_unlockAndPost`.
   The engine keeps running across surface destroy/recreate (window
   pointer swapped under a mutex) — the pattern the live wallpaper
   service will use.
2. **The `run_realtime()` dispatch** gained an `#elif defined(__ANDROID__)`
   branch.

Support files: `sh1_jni.c` (JNI bootstrap — owns the Surface lifecycle,
drives `main()` on a native thread with a programmatically built argv,
forwards UI keys), `sh1_keys.h` (verbatim copy of the `LiveKey` enum).

## App wiring

- `MainActivity` (no AndroidX, zero Java dependencies): fullscreen
  landscape `SurfaceView`; copies the bundled SH1 data files from
  `assets/sh1/` into app-private storage on first run; starts the
  engine thread; publishes/parks the surface on
  `surfaceCreated`/`surfaceDestroyed`; pauses rendering on
  `onPause`/invisibility.
- Engine invocation equivalent to:
  `fog_poc --glb <filesDir>/sh1/THRF9FD.glb --camdat <filesDir>/sh1/camdat.json --input <filesDir>/sh1 --window --fps 30`

## Demo controls

| Key | Engine action |
|---|---|
| Volume up | W — weather cycle (snow / rain / off) |
| Volume down | N — night / nightmare |
| M | fog toggle |
| H | HUD toggle |
| Back | move task to background (engine keeps running) |

## Next step (wallpaper fork)

Replace the `Activity` host with a `WallpaperService.Engine`:
`surfaceCreated`/`surfaceDestroyed` map 1:1 to the JNI calls already
here, `onVisibilityChanged` -> `nativeVisible`, `onOffsetsChanged` ->
camera pan. Settings bind to the camdat `env` schema (pair, weather,
fog, dither, psxWobble, particles, ...).

---

## Repository layout

Android Gradle project for the SH1 Fog live wallpaper (v4). The engine
(`app/src/main/cpp/fog_poc.c`, the PoC v23 renderer with the Android
backend) builds via the NDK into `libsh1.so`; see `BUILDING.md` for
requirements and the v3→v4 history.

## CI / Releases

`.github/workflows/android.yml` runs on every push/PR/manual dispatch and
builds both the debug and the signed release APK on a plain `ubuntu-latest`
runner (SDK/NDK preinstalled), with a smoke test that verifies each APK is
a valid zip containing `libsh1.so`.

Push a tag `v*` (e.g. `v4.0`) to publish a **GitHub Release** with both
APKs attached. Releases are used instead of workflow artifacts — release
assets do not count against the Actions artifact storage quota.
