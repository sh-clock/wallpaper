# SH1 Fog fork notes (v5)

This fork strips the Doodle wallpaper pipeline and makes the Silent Hill 1
environment renderer the app's single live wallpaper.

## What was removed (v5)

- All 14 original Doodle wallpaper classes (`wallpaper/*.java`) and the SVG
  rendering pipeline (`SvgDrawable`, `SvgView`, `PathParser`, the
  `res/raw/wallpaper_*.svg` assets and `selection_*` drawables)
- `service/LiveWallpaperService.java` (the Doodle OpenGL renderer) and its
  manifest entry / `xml/wallpaper.xml` metadata
- The Parallax and Size settings pages (Doodle-renderer-only settings)
- The wallpaper color extraction bottom sheet and color picker views
- `TestActivity`, `RandomUtil`, the Doodle random-wallpaper prefs/migrations

## What was added (v5)

- `Constants.SH1`: the 21 environment mode names + summaries and the 9
  weather states, extracted verbatim from `cpp/fog_poc.c`
  (`SH_MODE_NAMES`, `WNAMES`) — the game's `MAP_EFFECTS_INFOS` presets.
- SH1 Environment settings page (the former Appearance page), built
  exclusively from the app's existing Material Design components:
  MaterialSwitch row (Auto Mode), two-line list rows, radio-style bottom
  sheets (the language-chooser pattern with `Sh1OptionAdapter`).
- Auto Mode: day (06:00-18:00 device clock) uses env preset 1, night
  (18:00-06:00) uses env preset 6; re-checked every minute (TIME_TICK,
  TIME_CHANGED, TIMEZONE_CHANGED) and on settings broadcasts.
- Live settings delivery to the running engine:
  `Sh1Native.env()/weather()` -> `sh1_android_set_env/_weather` ->
  consumed by the realtime loop through the engine's own paths
  (`live_apply_preset` = Gfx_MapEnvSet(N,N); `sh_particle_weather_target_set`
  = sharedFunc_800D0B18). Boot values ride the PoC's own CLI
  (`--preset N`, `--weather-target N`) with the PoC's own precedence
  rules (CLI > camdat env).

---

## v9.5 addendum (Camtrack v12 — CORRECTED per-track weather)

The v9.5 release corrects the v9.4 interpretation of the Camtrack v12
mission (user directive 2026-10-05): the **engine keeps its original
512-tick sine cross-fades between weather**; only the **CamTrack v12
EDITOR** (`camtrack_v12.html`, the full editor rewritten from the v10
editor) applies weather instantly in its preview. The app and the Fog PoC
accept the per-track weather DATA — nothing else changed in the engine's
weather behavior.

- **Per-track weather data** (the only engine-behavior addition): the
  camdat format gains `models[].tracks[].weatherTarget` (0..8, optional,
  strict number) — the weather THE TRACK carries. Boot resolution:
  CLI `--weather-target N` > the selected track's own `weatherTarget` >
  the model env's `weatherTarget` > the engine's map0 default. The Java
  side mirrors the same layering (`Sh1WallpaperService.getSceneWeather`
  is track-aware; `Sh1Camdat.Track.weatherTarget` /
  `Wallpaper.camdatWeather()`), so the weather pickers' "Scene default"
  row and the runtime bookkeeping always agree with the engine.
- **512-tick cross-fades RESTORED** (v9.4 revert): the resolved boot
  weather is applied through `sh_particle_weather_target_set`
  (the verbatim `sharedFunc_800D0B18` call) — the engine's authentic
  RUNTIME transition recipe, exactly as in v9.2 and earlier. A wallpaper
  boot/track switch cross-fades into the track's weather (~17 s at
  30 Hz); a runtime weather change cross-fades the same way (the W key /
  the app's weather settings). `sh_particle_weather_apply_instant` was
  removed from the engine — the instant preview lives ONLY in the editor.
- **u16 weather fidelity fix (retained from v9.4, decomp-verified)**: the
  boot syncs copy `weatherBits & 0xFFFF` into `weatherCurrent`/
  `weatherTarget` (the decomp's `u16` E0CB4/E0CB6 truncating
  assignments — `include/maps/particle.h:95-96`, `particle.c:81-82`) —
  the draw-case bits no longer pollute the class reads or fake a pending
  transition. This makes the cross-fade MORE accurate (no spurious
  512-tick dip when the target equals the boot state's low 16 bits).
- **Java mirror fix (retained from v9.4)**:
  `Sh1WallpaperService.MAP0_DEFAULT_WEATHER = 2` — an absent camdat
  `weatherTarget` resolves to the engine's effective boot weather (map0
  snow, wind 1) everywhere.
- **Shipped camdat**: "Line 1" inherits the model env weather (4 — snow,
  wind 3); "Line 2" carries `weatherTarget` 6 (rain, wind 1 — the game's
  own `D0B18(6)` street-event argument). Two wallpapers, two authentic
  weathers, both landing through the engine's cross-fade.

---

## v9.6 addendum (Camtrack v13 — day/night environments + the in-app editor)

The v9.6 release ships **CamTrack v13** (`camtrack_v13.html`, released
alongside the APK/source) and wires it INTO the app:

- **Day / Night environments (the camdat v13 schema):** every model gains
  `models[].env.dayEnv` / `env.nightEnv` (optional 0..20
  MAP_EFFECTS_INFOS indices — the presets the wallpaper's Auto Mode
  applies by daylight phase), and every track gains the per-track
  overrides `models[].tracks[].envDay` / `envNight` (optional 0..20,
  resolved track > model; absent = inherit). The ENGINE is untouched —
  the new fields ride the camdat JSON (verified byte-identical
  deterministic render: the v12 and v13 camdats produce the same
  shot MD5 through the desktop PoC; the key-selective parser ignores
  unknown fields by design). The service resolves and passes the
  effective preset through its own `--preset` argv, exactly as before.
- **App-side layering (the per-track override the app allows):**
  `getAutoDayEnv/getAutoNightEnv` gained context-aware overloads with the
  camdat layers slotted between the existing app-side preferences and
  the global custom mapping: **app track override (F30) > app scene
  override (F21) > camdat track envDay/envNight > camdat model
  dayEnv/nightEnv > app custom mapping (F19) > authentic 1/3**. All
  runtime paths (engine boot, the 60 s auto-check, the day/night
  boundary blend) and the Settings/Appearance displays resolve through
  them. `Sh1Camdat.Track/Model/Wallpaper` mirror the v13 fields.
- **The in-app Camtrack editor (new menu):** Settings -> "Camtrack
  editor" opens `CamtrackEditorActivity` — the FULL CamTrack v13 editor
  in a WebView with the LIVE PREVIEW, fully OFFLINE (three.js r170 is
  embedded in the editor's data-URL importmap; the page is served from
  the APK assets through `shouldInterceptRequest`, no network, no
  file-access policy dependence). The `window.AndroidBridge` JS
  interface serves the wallpaper's own camdat.json + model GLBs
  (filesDir first, bundled assets next), boots the preview lens on the
  wallpaper's CURRENT daylight phase (`isDaytimeNow`), and **Save to
  wallpaper** validates + atomically writes `filesDir/sh1/camdat.json`
  and restarts the engine through its own `requestSceneRestart` path.
- **Back-button fix (three-button navigation):** the LWP settings
  activity (`SettingsActivity`, inherited from upstream Doodle) had no
  task isolation — launched by the system wallpaper picker with
  FLAG_ACTIVITY_NEW_TASK it stacked on this app's stale MainActivity
  task, so the system BACK button revealed the app's main screen
  instead of returning to the picker. It now carries
  `taskAffinity=""` + `excludeFromRecents` + `autoRemoveFromRecents`:
  back returns to the wallpaper picker. The new editor activity handles
  back through `OnBackPressedDispatcher` (identical for the 3-button
  key, the gesture and predictive back) with an unsaved-changes dialog
  (save & close / discard / cancel).
- The editor's Day/Night chips are its PREVIEW lens (session-only,
  never serialized; the authored pair is shadowed while a phase runs —
  the proven Effects-Off pattern); the ENGINE keeps transitioning
  day/night through its own environment paths (Gfx_MapEnvUpdate blend
  / instant picks, exactly as v9 F20 shipped).

---

## v10.0 addendum — real-device bug fixes, menu restructure, resolution-aware dither

The v10.0 release fixes the two show-stoppers reported from real-device
testing of v9.9, restructures the main menu, removes the legacy Doodle
artifacts, and makes dithering resolution-aware across all backends.

### BUG 1 — the universal back-button freeze (root cause: the launcher
trampoline stacked a DUPLICATE MainActivity)

`LauncherActivity` (the splash) started `MainActivity` with STANDARD
launch mode. On every icon tap after the first, the system starts a NEW
splash ON TOP of the surviving MainActivity (the task's original root
component is finished), and the splash then started ANOTHER standard
MainActivity: the task became [MainActivity, MainActivity]. The first
BACK press finished the top clone and revealed the IDENTICAL-looking
bottom instance — visually "nothing happens / frozen" (no predictive
exit animation, the same screen underneath); the second press closed the
app for real. FIX: the trampoline now launches MainActivity with
FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP — the existing
instance receives the intent via onNewIntent, the splash is cleared, and
a single back press exits with the real system animation.

The Camtrack editor's back callback is also STATE-DRIVEN now (the only
manual OnBackPressedCallback in the app): enabled only while there are
unsaved changes / a failed save, or while a non-TRACKS page is showing
(back steps back to TRACKS first); a clean editor on the TRACKS page gets
the SYSTEM default predictive-back exit (the always-enabled v9.9
callback suppressed it).

### BUG 2 — the Camtrack editor slider crash

`java.lang.IllegalStateException: Value(1.1254324) must be equal to
valueFrom(0.0) plus a multiple of stepSize(1.0)` from
`BaseSlider.validateConfigurationIfDirty <- onDraw`: Material Slider
validates LAZILY AT DRAW TIME and programmatic setValue() does NOT snap
to the step lattice. The scrub slider (0..1000, step 1) received an
arbitrary per-mille float (`trackTime / cycle * 1000`) on EVERY
Choreographer frame while playing — the editor died seconds after open.
FIX: the scrub slider is continuous (no stepSize), `addSliderRow` keeps
every property slider continuous (`setStepSize(0f)` — track coordinates,
radii, speeds and angles are continuous physical quantities; exact
values enter through the numeric-entry path), and the shared
`Widget.Doodle.Slider` style no longer carries a blanket `android:stepSize`
default (a latent draw-time landmine for any future programmatic float).

### Main menu restructure

The Camtrack Editor is a MAIN MENU entry now (the first row on the
overview page, above Parallax); its former entry point in the Settings
page was removed.

### Legacy Doodle artifacts removed

The 5th-launch FEEDBACK popup (market:// rate links), the feedback bottom
sheet, the toolbar share action (its text carried the ORIGINAL app's dead
Play Store URL) and the About page's Play-Store / translation rows are
gone — zero live market:// or play.google references remain anywhere in
the app. (All 26 translated string files swept too.)

### Resolution-aware dithering (all backends)

The authentic PS1 ordered-dither looks unnatural when the raster is
scaled beyond the native 320x224, so the AUTOMATIC default above native
resolution is now dither OFF; a user who explicitly toggles the new
"PS1 dither" setting (Settings page, under Render resolution) gets their
choice respected at every resolution. The policy rides the engine's own
`--no-dither` CLI flag through the boot argv (exactly the wobble pattern:
ON = no flag = the engine default + the scene's own camdat envDither
chain), and the engine's single `dither` global gates the software
fb_put path AND the GLES/Vulkan shader flag — all three backends.

### Engine integrity (v10.0 verification)

`fog_poc.c`, `sh1_hw_render.c`, `sh1_hw_shaders.h`, `sh1_hw_types.h`,
`sh1_hw_vk.cpp`, `sh1_hw_vk_spv.h` and `vk/` are BYTE-IDENTICAL to the
v9.9 baseline (the only native delta is the additive `dither` parameter
on the Android-gated `Sh1Native.start` boot seam — the same class of
change as every prior seam). Desktop golden-shot proof: the v9.9-source
and v10.0-source builds render a deterministic t=2.5 frame with the SAME
MD5 (7f7b16dd5a59ad4461f24214a239f49e). 38 unit tests pass, including
Robolectric proofs that (a) the v9.9 scrub-slider configuration crashes
at draw time with the exact reported exception while the v10.0
configuration survives every arbitrary playhead float, (b) the splash
trampoline now starts MainActivity with SINGLE_TOP|CLEAR_TOP, and (c)
the dither policy matrix (auto off above native, explicit choice
respected everywhere).

### The v10.0 battery harness (bench/ in the source zip)

`bench/battery_bench.c` drives the engine's own realtime loop (the
wallpaper's exact boot, a standardized 60 s sequence = 1802 frames)
through all three backends with ZERO engine delta (the engine TU is
included with its main() renamed away; the backend is picked by the
`SH1_BENCH_BACKEND` env through a process constructor). Measured on this
2-core x86-64 sandbox with the GPU emulated (Mesa llvmpipe / lavapipe):
software 0.845 ms/frame vs GLES 0.564 / Vulkan 0.573 — and valgrind
callgrind attribution: the raster work the GPU absorbs is ~25% of the
loop, `transform_item2` is bit-stable across all backends, and the
real-device CPU instruction budget drops from 4.06 M to ~2.1 M per frame
(~49%). See the hardware-renderer report in the app's Other page for the
full battery model.

## v10.1 addendum — global back-button completion, standalone editor preview, STRICT CENTER-CROP

Real-device testing of v10.0 surfaced three issues. All three are fixed
and desktop/unit-proven; the engine's rasterizer/math is byte-identical
(v10.0 and v10.1 desktop builds compile to the SAME BINARY and render
the same deterministic t=2.5 golden shot, MD5 7f7b16dd5a59ad4461f24214a239f49e).

### BUG 1 (completed) — the GLOBAL back button

v10.0's fix covered the editor + the launcher trampoline only. The real
root cause of the remaining failures: `androidx.activity` resolved
TRANSITIVELY to a stale 1.8.1 (via appcompat 1.7.0 / material) — the
library that OWNS the OnBackPressedDispatcher <-> window
OnBackInvokedCallback bridge, i.e. the exact layer between the system's
predictive-back dispatch and the app on API 33+ with
`android:enableOnBackInvokedCallback="true"`. v10.1 pins
`androidx.activity:activity:1.9.3` EXPLICITLY (gradle resolution now
shows 1.5.1/1.7.0 -> 1.9.3 everywhere), and adds belt-and-braces back
interception on modal bottom sheets: every cancelable sheet registers a
lifecycle-aware callback on the ACTIVITY's dispatcher (enabled exactly
while the sheet's window shows, removed at onStop) so an OEM routing the
press to the activity instead of the dialog window can never pop the nav
stack under a still-showing sheet. 12 new Robolectric tests
(`BackNavigationTest`) drive the REAL dispatcher across EVERY nav
destination (settings/parallax/appearance/other/about/log, two stack
levels), the root-fallback finish, the toolbar navigateUp path, and both
sheet-back paths (activity dispatcher + dialog-window KEYCODE_BACK),
plus the interception-removed-after-dismiss guarantee.

### BUG 2 — the editor's black preview when the wallpaper was never set

`copyAssetsIfNeeded` was reachable only from `Sh1Engine.onSurfaceCreated`
(the wallpaper path — which never ran on a fresh install without the
wallpaper set), so the editor's `Sh1Native.start()` booted with no scene
data. v10.1: the copy takes a plain Context and is called by BOTH
`beginEditorPreview` and `updateEditorPreview` (idempotent,
double-checked under the class lock). The teardown side is fixed too:
`endEditorPreview` used to ALWAYS `restartEngine()` — with zero live
wallpaper engines that booted a HEADLESS engine rendering into no
surface (CPU burn + a stale boot conflicting with a later wallpaper
enable). It now tracks live `Sh1Engine` instances (constructor
increment / onDestroy decrement) and stops the engine outright when no
wallpaper consumer exists. Robolectric proof: `EditorPreviewAssetsTest`
copies all four engine data files from a plain app context with the
service NEVER instantiated, idempotently, and the copied camdat is the
real v13 document.

### TASK 3 — STRICT CENTER-CROP presentation (no stretch, no letterbox)

The v9-era blit cropped only in portrait and carried an integer-
truncation anisotropy (independent xs/ys 16.16 steps: a real ~0.36-0.8%
horizontal squeeze on 1080x2400), and LETTERBOXED in landscape
(aspect-fit). v10.1's `and_blit` (fog_poc.c) is a full rewrite of the
presentation blit ONLY (all three backends present through this one
function: software fb_put, GLES FBO readback, Vulkan readback —
fb_to_argb then and_blit):

* ONE uniform DDA step (16.16, source px per destination px = the
  reciprocal 1/s of the cover scale) drives BOTH axes — the scaling is
  isotropic BY CONSTRUCTION. (The first in-flight draft of this rewrite
  had the step inverted — s instead of 1/s; the new desktop proof
  battery caught it before shipping. That is exactly why the battery
  exists.)
* The window is fractional (e.g. 100.8 of 320 columns on 1080x2400) —
  no rounding is forced into the uniform scale; the origin keeps 16.16
  sub-texel precision and a DDA tail clamp guards the last texel.
* COVER in BOTH orientations: every window pixel is written (the
  letterbox clears are gone), the excess is cropped symmetrically.

Desktop proof (`bench/blit_crop_test.c` + `bench/extract_and_blit.sh`):
and_blit + sh1_android_set_crop are extracted VERBATIM (brace-counted,
provenance recorded) from the shipped fog_poc.c — the test proves the
REAL code, not a re-implementation. 816,842,146 checks over 48
framebuffer x surface pairs (incl. 1080x2400, 2400x1080, 1440x3200,
1170x2532, 1x1) + 3 RGB_565 pairs, all with stride = width+37:
cover/no-letterbox, circle-isotropy (a source circle must stay a circle
within 0.5% — measured noise <= 0.2%), DDA monotonicity, symmetric
centered crop, offset extremes pinned to the edges with every decoded
coordinate in bounds, and the exact 565 packing. The CONTRAST section
proves the battery would have REJECTED v10.0: its extracted math shows
0.73% circle stretch on portrait and 926,640 letterbox pixels on
landscape.

### TASK 4 — the crop overlay + user panning

New prefs `sh1_crop_x` / `sh1_crop_y` (float, normalized -1..1, default
0 = the symmetric authentic crop; NaN/out-of-range fall back to center —
unit-proven in `CropOffsetsTest`). The native seam is the established
presentation-only class: `sh1_android_set_crop(x, y)` sets a volatile
pair that `and_blit` reads at EVERY presented frame (same seam family as
visible/freeze/env) — live changes apply on the very next frame with NO
engine restart and ZERO engine-math impact; all three backends present
through it. The offsets slide the crop window along the cropped axis
(portrait phones pan X, landscape screens pan Y; the exactly-filling
axis has no room and is inert).

USER FIRST controls, MD3 Expressive:
* Settings page: two two-line rows + CONTINUOUS sliders (no stepSize —
  the v10.0 slider-crash lesson) with live push while dragging, persist
  on release, per-axis RESET buttons, and descriptive value labels
  ("Centered / Shifted left 37% ...").
* Camtrack editor: the preview (letterboxed to the raster's aspect, so
  the full frame is visible) is overlaid by `Sh1CropOverlayView` — a
  faithful Java mirror of and_blit's window math (same uniform cover
  scale, same fractional window, same clamped origin) drawing a
  semi-transparent scrim OUTSIDE the exact device-screen-aspect window
  with corner brackets, DRAG to pan (view px -> raster px -> normalized
  offset, the exact inverse of the C mapping), and a collapsible
  "Screen crop window" panel with the X/Y sliders + reset. Offsets
  persist on release and re-assert on the wallpaper engine at session
  end (endEditorPreview -> restartEngine reads the prefs).

### Engine integrity (v10.1 verification)

The ONLY native deltas vs v10.0 are Android-gated presentation code:
`and_blit`'s rewrite + `sh1_android_set_crop` (fog_poc.c, both inside
`#ifdef __ANDROID__`) and the additive `setCropOffset` JNI seam
(sh1_jni.c / Sh1Native.java). Proof: the v10.0-source and v10.1-source
DESKTOP builds are byte-identical binaries (MD5 5d81823bd943f412a81fe468ace11c02)
and render the identical deterministic t=2.5 golden shot (MD5
7f7b16dd5a59ad4461f24214a239f49e) — the 512-tick weather crossfade and
PS1 wobble/dither logic are untouched. 57 unit tests pass (38 carried
from v10.0 + 12 back-navigation + 6 crop-pref + 1 standalone-assets).

## v10.2 addendum — THE EXHAUSTIVE ACCURACY AUDIT

Mission: a ruthless line-by-line audit of 100% of the C/C++ engine + JNI
bridge against the ground-truth repos (silent-hill-decomp master,
swanstation main), with the Java state layer audited for engine-authority
violations. One real defect was found and remediated; everything else
verified clean.

### The audit (full detail in worklog.md Tasks 5-7)

Tables re-verified PROGRAMMATICALLY against ground truth (all EXACT):
* kSineTable 5120/5120 vs libkmath.s g_SineTable.
* GTE_UNR_TABLE 257/257 vs swanstation gte.cpp unr_table.
* DITHER4 vs swanstation gpu_types.h DITHER_MATRIX.
* FOG_CURVE 17/17 vs decomp D_800AE1C0.
* SH_MAP_EFFECTS_INFOS 21/21 entries FULL positional match vs decomp
  map_info.c (flags/spot/wLI/worldTint/field_E/fogDistance incl.
  Q12(CHUNK_SIZE)/fogColor/overlap/pointLightTint/worldTint/f21/f25).

Engine-math changes since v5, each verified against the actual decomp /
swanstation source text (zero-trust — comments were never believed):
* "v24 D8 CORRECTED" negative-MAC saturation (→0, not 255): CONFIRMED
  against gte.cpp:145-158 TruncateRGB(int32_t) + PushRGBFromMAC's
  re-signing uint32→int32 cast chain.
* sh_progress_weight / sh_clz32 (func_8003F6F0): line-for-line logic
  match vs world_effects.c:590.
* sh_env_blend family (func_8003F838/8003FD38/8003FE04/8003FCB0 +
  Math_WeightedAverageGet): branch-for-branch match incl. the D1
  verbatim fix (Q12(1/6)=682 truncation, Q12_MULT by 4915).
* sh_env_transition_start / sh_env_tick primType blend
  (Gfx_MapEnvStepUpdate 0x8003EF74 + Gfx_EffectsUpdate lines 411-428):
  match; the raised-cosine progress driver is map2_s00.c:1358-1366's own
  event curve rescaled 40..100→0..100 (documented deviation: the game's
  40% head start hides behind a ScreenFadeCmd the wallpaper lacks).
* v29 weather u16 masking: CONFIRMED vs particle.c:80-81 AND 365-366 —
  E0CB6/E0CB4 are u16, E0CB0 is u32; both Particle_SystemUpdate variants
  truncate.
* sh_particle_weather_target_set (sharedFunc_800D0B18) and
  sh_particle_weather_tick (the 512-tick sine crossfade driver + the
  0x200 completion): line-for-line / branch-for-branch match.
* gte_unr_divide vs swanstation UNRDivide (gte.cpp:414-456): VERBATIM
  (overflow guard + CLZ normalization + the whole table chain).
* The raster modulate Fix #1 (((tr>>3)*vr)>>4) confirmed present.
* Vulkan support stack: vk/vulkan_loader.{cpp,h}, vk/types.h,
  vk/vulkan_entry_points.h, vk/builders.cpp are BYTE-IDENTICAL to
  swanstation; builders.h/util.{h,cpp} differ ONLY by the documented
  include-path seam (sw_shim_deps.h shims the three util.cpp deps).
* ApplyDithering GLSL (both flavors) is CHARACTER-FOR-CHARACTER
  swanstation gpu_hw_shadergen.cpp:866-880.
* sh1_jni.c / Sh1Native.java / Sh1WallpaperService.java: clean — the
  Java layer never invents engine state; every runtime path routes
  through the engine's own pending-slot seams consumed on the engine
  thread. The v10.0 back-navigation and v10.1 BUG 1/2/3 fixes verified
  present; 57/57 JVM tests pass.

### THE defect found (v10.2 AUDIT FIX — fb_put quantize-flag branch)

Both hardware shaders (HW_FS_MESH_GLES in sh1_hw_shaders.h AND
vk/hw_mesh_vk.frag + its precompiled SPIR-V) branched the output
quantization on `v_flags.x != 0` instead of the QUANTIZE BIT. flags.x =
dither | quantize<<1, and the CPU's fb_put gates on quantize ALONE —
dither is inert without it. In the reachable (q=0, d=1) mode (--modern /
camdat psxWobble:false + dither ON — dither defaults ON), the GPU
backends wrongly rendered 15-bit-quantized+dithered output while the
software renderer rendered the plain 8-bit clamp: a genuine HW/SW
divergence. The shaders' own comments documented the correct mapping
("quantize && dither / quantize only / neither") — the code
mis-implemented the outer condition.

Fix: the outer branch now tests `(v_flags.x & 2) != 0` (the quantize
bit), exactly like fb_put, in BOTH flavors. The Vulkan SPIR-V was
regenerated with glslang 15.2.0 — the toolchain first PROVEN to
reproduce the prior .spv byte-identically from the prior GLSL, so only
the fixed branch's bytes moved (vert unchanged: byte-identical; frag
3764→3769 words). sh1_hw_vk_spv.h regenerated from the new bytes.

Proof harness (scripts + fb_put_equivalence.c, exhaustive): the FIXED
GPU chain ≡ the CPU fb_put chain for ALL FOUR (q,d) flag combinations
and ALL inputs (c in [-64, 320] × every dither cell × both
polarities) — ZERO divergences; the v10.1 branch DIVERGES in (q=0,d=1)
(e.g. c=1: CPU=1, v10.1 GPU=0) — the defect and its closure are both
machine-proven.

### Regression proofs (v10.2)

* DESKTOP GOLDEN SHOT: the v10.2 engine renders the deterministic
  t=2.5 still BYTE-IDENTICAL to the v5-audited baseline (MD5
  258c6bef961cc58c5116d2e14f7a5759 — the same value recorded in the
  v5 worklog entry): the software path is untouched by the audit fix
  (the fix lives in the Android/HW shader sources only).
* blit_crop_test (the v10.1 battery, re-verified against a FRESH
  brace-counted extraction of the CURRENT and_blit): 816,842,146
  checks, 0 failures — cover in both orientations, isotropy, monotonic
  DDA, symmetric center, clamped offsets, RGB_565, stride.
* 57/57 JVM unit tests pass (v10.1 suite unchanged).
* Identity: applicationId xyz.zedler.patrick.doodle.SH1.veH8pT
  (the documented dot-segment form of the requested dash suffix),
  versionCode 62, versionName 10.2, label "SH1 Fog v10.2",
  arm64-v8a only, 16 KB page-aligned, signed with the project keystore.

---

## v10.3 addendum — TRUE CROP PANNING + the universal back guarantee

Mission: real-device testing proved both v10.2 "fixes" failed — moving the
crop area shifted the view instead of panning a true 2D crop, and the
system back button silently did nothing. This release diagnoses both
defects at the evidence level and fixes them permanently.

### Bug 1 (crop) — the diagnosis and the fix

The AUDIT (source + the shipped APK dex, decompiled) DISPROVED the
hypothesized root cause: the crop offsets (`g_and_crop_x/y` →
`sh1_android_set_crop` → JNI `setCropOffset` → the editor/settings/service
pushes) reach EXACTLY ONE consumer — `and_blit`, the 2D presentation blit.
The projection (`render_frame`: aspect = fb->w/fb->h, fov = cam->fovDeg),
`cam_update_track`, the turn filter and every camera path have ZERO crop
input.

The REAL defect: the Camtrack editor preview was letterboxed to the
RASTER's aspect (320:224), so `and_blit`'s strict center-crop degenerated
to an exact fit — the crop offsets were VISUALLY INERT on the preview.
Dragging the crop moved the overlay rect while the rendered scene never
panned; the only visible motion was the natural camtrack camera — exactly
the reported "the crop window does not pan over the rendered scene; the
3D camera angle/center shifts instead".

The FIX (v10.3): the editor preview frame now carries the DEVICE DISPLAY's
aspect ratio (height-capped ~42%, centered) — the engine's OWN `and_blit`
then performs the REAL strict center-crop with the LIVE offsets on the
preview surface. The preview IS the WYSIWYG mirror of the phone screen:
dragging the preview (the overlay is now the full-surface drag layer, with
direct-manipulation 1:1 finger tracking at the content scale) pans the
rendered content itself on the next presented frame. Engine C code is
BYTE-IDENTICAL to v10.2.

The PROOF (desktop harness, `bench/crop_pan_invariance.c` — the REAL
fog_poc.c translation unit compiled with the REAL `__ANDROID__`
presentation block on Linux stubs):
* PROPERTY 1 (raster invariance): with the crop seam set to (-1,0),
  (0,0), (+1,0), the deterministic `--shot t=2.5` render is BYTE-IDENTICAL
  (md5 7f7b16dd5a59ad4461f24214a239f49e for all three) — the crop offsets
  cannot alter any 3D vertex transform, camera matrix, projection or
  rasterization byte.
* PROPERTY 2 (destination pan): 1,111,320 destination pixels across the
  three offsets verified against the independently re-implemented 16.16
  DDA window math — every pixel matches the exact shifted sampling window
  (origins: col 0.00 / 109.60 / 219.20 of 320 at 1080x2400).
* The v10.1 verbatim-extraction battery re-run: 816,842,146 checks,
  0 failures (strict center-crop, isotropic, full-bleed, clamped).

### Bug 2 (back) — the diagnosis and the fix

The AUDIT: every app-level back path is correct in source AND in the
shipped dex (editor state-driven callback, sheet interception added at
START/removed at STOP, trampoline CLEAR_TOP|SINGLE_TOP, taskAffinity
isolation, Activity pinned 1.9.3, `enableOnBackInvokedCallback=true`).
The v10.2 release contained ZERO Java back-code changes (its diff vs
v10.1 is only the GLES/Vulkan fb_put shader fix + strings) — the claimed
"back button fix" never existed. The v10.0/v10.1 Robolectric tests
dispatched through `dispatcher.onBackPressed()` only — bypassing the
platform `OnBackInvokedCallback` bridge entirely, the exact layer a real
API 33+ device exercises. In androidx.activity 1.9.x that bridge
registers/unregisters ONLY on enabled-state TRANSITIONS
(`updateEnabledCallbacks` → `updateBackInvokedCallbackState`): whenever
NO callback is enabled (root destination, no sheets), the platform
callback is UNREGISTERED — the app then depends entirely on the
system-default path, the layer reported dead on the real device.

The FIX (v10.3 — the universal terminal back callback, in MainActivity
(covers SettingsActivity) and CamtrackEditorActivity):
* Registered FIRST (= lowest LIFO priority — every higher layer: the nav
  stack's pop, the sheet dismissal, the editor's exit guard — consumes
  back before it) and ALWAYS ENABLED.
* This keeps `hasEnabledCallbacks` permanently true → the platform bridge
  stays REGISTERED for the activity's whole lifetime → the system always
  delivers back INTO the app on the predictive-back opt-in path. A
  bridge-level silent death is structurally impossible now.
* When reached it ACTS, never silent: `navigateUp()` (pops a stacked
  destination) else `finish()`.

New Robolectric coverage: the bridge-registration invariant, the framework
`ComponentActivity.onBackPressed()` entry on stacked + root, the editor's
back semantics (deeper page → TRACKS → finish), and the WYSIWYG preview
geometry + drag math + persistence (EditorCropPreviewTest).

---

## v10.4 addendum — the v10.2 FULL-CONTEXT crop editor, restored

Real-device verdict on v10.3: the universal back button WORKS (kept,
untouched); the crop interaction was rejected. With the v10.3 WYSIWYG
preview (device-display-aspect surface, live-cropped by and_blit),
dragging pans the rendered CONTENT under a fixed phone-shaped window —
and a narrow slice of the perspective render sliding by reads exactly as
"the camera angle rotated". The user's demanded model (verbatim intent):
the crop is a TRANSLATION of the "camera box" — think of the sides as
vertical letterboxes whose SIZES shift — with the FULL context visible
(what is being left out), and the scene itself must be actually cropped
without the camera being manipulated.

### What v10.4 changes (editor presentation ONLY — engine byte-identical)

* `CamtrackEditorActivity.applyPreviewAspect()` is REVERTED to the v10.2
  full-frame model: the preview frame is letterboxed to the ENGINE
  RASTER's aspect (320:224 default). The engine's own strict center-crop
  degenerates to an EXACT FIT on that surface — the FULL framebuffer is
  always visible, the rendered scene NEVER pans on the preview.
* `Sh1CropOverlayView` is REVERTED to the v10.2 overlay: it draws the
  phone screen's crop window (the SAME uniform-cover math as and_blit)
  with a semi-transparent scrim OUTSIDE it (what the screen crops away —
  the letterbox bars whose sizes the offsets shift), a crisp stroke and
  corner brackets. DRAG TRANSLATES THE WINDOW BOX over the fixed scene;
  the image never moves. The X/Y sliders and the persisted state are
  unchanged (same normalized -1..1 offsets, same prefs, same session-end
  re-assertion to the wallpaper engine).
* The v10.3 additions that are KEPT verbatim: the universal terminal
  back callback (MainActivity + editor — the confirmed-working back
  fix), the Choreographer frame-tick advance guard, EditorBootSmokeTest,
  the desktop invariance harness (`bench/`, `proof/`).

### What v10.4 does NOT change (the actual crop — already correct)

The native seam is BYTE-IDENTICAL to v10.1/v10.2/v10.3 (verified by
diff): `g_and_crop_x/y` reach EXACTLY ONE consumer — `and_blit`, the 2D
presentation blit. On the PHONE (display-aspect surface) the wallpaper
performs the REAL strict center-crop: the offsets slide the 16.16 DDA
source-rect origin over the fully rendered framebuffer — a pure 2D
window pan; the raster, the projection, the camera and the camtrack are
provably offset-invariant (desktop proof: byte-identical --shot renders
at crop -1/0/+1, md5 7f7b16dd5a59ad4461f24214a239f49e). The scene IS
actually cropped on the device — never a camera manipulation.

The distinction v10.4 restores: ON THE EDITOR PREVIEW the offsets are
deliberately inert (exact-fit aspect — the full context stays visible
and the WINDOW BOX translates); ON THE PHONE they are the live 2D crop.
v10.3 collapsed those two surfaces into one WYSIWYG view and thereby
made the content pan — the interaction the user rejected.

Identity: applicationId xyz.zedler.patrick.doodle.SH1.hk2lBg
(versionCode 64, versionName 10.4, label "SH1 Fog v10.4"), arm64-v8a
only, 16 KB page-aligned, signed with the project keystore.

---

## v10.7 addendum — FIXED PACKAGE NAME

User mandate: "make the package name: com.fog.sh.wallpaper". The v9.7
dynamic dot-segment scheme (xyz.zedler.patrick.doodle.SH1.<rand>) is
RETIRED. applicationId is now the fixed, clean
com.fog.sh.wallpaper.

What the rename touches and why it is safe:
* applicationId ONLY (app/build.gradle) + versionCode 67 / versionName
  10.7 + the changelog entry. ZERO Java/Kotlin/resource changes.
* The Java namespace stays xyz.zedler.patrick.doodle, so the R class,
  every manifest relative component name, the wallpaper metadata
  android:settingsActivity FQCN and ALL 20 JNI symbols
  (Java_xyz_zedler_patrick_doodle_*) are untouched — the engine build
  and bindings are byte-identical.
* All runtime package references are dynamic (getPackageName(),
  PrefsUtil's "<pkg>_preferences" name), so the app is internally
  consistent under the new ID. Verified by grep: no source file pins
  the old applicationId.
* Android treats a renamed applicationId as a NEW app: no in-place
  upgrade from any prior build. Fresh data sandbox (prefs + DE engine
  data start clean, bundled assets re-copied on first surface), and
  the live wallpaper must be re-picked once in the system picker. The
  old app keeps working until uninstalled.

Identity: applicationId com.fog.sh.wallpaper (versionCode 67,
versionName 10.7, label "Fog Wallpaper"), arm64-v8a only, 16 KB
page-aligned, signed with the project keystore.

---

## v11.0 addendum — THE CORRECT-SOURCE RESET (the v10_7 base + the v34 PoC engine + the 44-scene corpus)

The repo this addendum ships into was reset: the prior repo content was
an extremely broken alpha build (a custom com.sh1.poc shell), replaced
entirely by the correct fog-wallpaper-v10_7 source zip (this file's own
v5–v10.7 history is that source's genuine engineering log — kept
verbatim). The v11.0 transplant on top of that base:

* **Engine**: `cpp/fog_poc.c` is the v34 PoC engine (14913 lines ->
  15680 after the graft): EXTRAs-authoritative animations (the camdat
  env `animsDemo` no longer gates playback — the GLB's own SH1X propAnims
  are the authority), the waterLive semi-trans-additive water floor
  (RP_ADD_TEX, field_E=45) and the tick-discipline water scroll. The
  v10_7 Android glue grafted as additive `#ifdef __ANDROID__` blocks:
  the printf->logcat redirect at the file top (the W-D9 placement), the
  sh1_android_* seam block + run_window_android before the
  platform-backends #endif, the run_realtime `#elif __ANDROID__`
  dispatch, the `--hw` parse and the `#ifdef SH1_HW_RENDER` end include.
  TWO documented adaptations: `live_apply_env_transition` ->
  `live_apply_preset` (the v34 lineage never carried the v9-F20
  gradual-blend driver; the Auto Mode boundary lands as an instant
  Gfx_MapEnvSet — the JNI/Java surface unchanged), and the ALOGI/printf
  defines moved to the top. Desktop behavior proven BYTE-IDENTICAL to
  the PoC binary (md5-equal deterministic shots, THRF9FD + SU0203 v33).
* **Scenes**: ONE camdat.json enumerates 44 rooms (41 v33 GLBs +
  HP0003/THRF9FC/THRF9FE), env derived VERBATIM from each GLB's
  SH1X.env, bounds scraped from the engine's own Box3.setFromObject,
  47 tracks (3 user-authored fixed + 17 prior-repo ambient lines + the
  v10_7 THRF9FD lines + 25 conservative cell-center fixed). The prior
  repo's per-scene *_camdat.json files + scenes.json manifest are gone.
* **Java**: `copyAssetsIfNeeded` enumerates assets/sh1 dynamically —
  adding a scene is a GLB drop + a camdat entry (aim 7).
* **Versioning**: version.properties at the repo root; CI bumps ONE
  minor per asset change (1.7.40 -> 1.7.41 ...); no more v11–v27 jumps —
  the lineage continues forward from the prior repo's 1.7.40.
