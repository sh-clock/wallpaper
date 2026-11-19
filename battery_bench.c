/* battery_bench.c — v10.0 Phase I: the battery analysis harness.
 * ============================================================================
 * Drives the ENGINE'S OWN realtime loop with the wallpaper's EXACT boot
 * configuration (the shipped THRF9FD.glb + camdat.json, day pair {1,1},
 * the track's weather-target cross-fade, 320x224, 30 fps) for a
 * standardized 60-second sequence, through the THREE rendering backends:
 *
 *   SH1_BENCH_BACKEND unset  -> software (render_frame — the engine's own
 *                               rasterizer; the DEFAULT + accuracy truth)
 *   SH1_BENCH_BACKEND=gles   -> OpenGL ES  (sh1_hw_render, the v9.9 seam —
 *                               the SAME substitution run_window_android
 *                               performs on device)
 *   SH1_BENCH_BACKEND=vk     -> Vulkan     (sh1_hw_render, backend 2)
 *
 * ZERO ENGINE DELTA: the engine TU is included UNMODIFIED with its main()
 * renamed away at compile time (-D-free: the #define below) — the same
 * class of build-harness trick as bench_backend_ctor.c. The loop below is
 * the engine's own run_benchmark loop VERBATIM (same live_advance cadence,
 * same duration clamp) with the v9.9 backend dispatch replacing the direct
 * render_frame call, exactly as the Android realtime loop does.
 *
 * On this sandbox the GL/VK "GPU" is Mesa's llvmpipe/lavapipe (CPU
 * emulation) — the harness therefore reports wall time for the loop AND
 * leaves instruction attribution to valgrind callgrind (function-level:
 * the software rasterizer's raster/texel/fb_put instruction share is the
 * exact work the GPU absorbs on a real device; llvmpipe-internal
 * instructions are the emulated-GPU share).
 * ========================================================================*/
/* the engine TU FIRST (it sets its own feature-test macros + includes);
 * my includes come after, once its identifiers exist */
#define main engine_main_discarded    /* the engine's own CLI main */
#include "fog_poc.c"
#undef main

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

int main(int argc, char **argv) {
    (void)argc; (void)argv;

    /* ---- the wallpaper's boot configuration (fixed, standardized) ---- */
    const char *glbPath = "THRF9FD.glb";
    const char *camdatPath = "camdat.json";
    int w = 320, h = 224;             /* native PS1 raster (engine default) */
    double duration = 60.0;           /* the standardized 60-second sequence */
    double rate = 30.0;               /* the engine's authentic 30 fps       */
    int dither = 1;                   /* ON at native (the v10.0 auto rule)  */

    /* the backend: env-selected (see bench_backend_ctor.c — the ctor picks
     * it before main; read it back here for the report line) */
    const char *benv = getenv("SH1_BENCH_BACKEND");
    const char *bname = "software";
    if (benv != NULL) {
        if (strcmp(benv, "gles") == 0 || strcmp(benv, "gl") == 0)
            bname = "opengl-es";
        else if (strcmp(benv, "vk") == 0 || strcmp(benv, "vulkan") == 0)
            bname = "vulkan";
    }

    /* ---- the engine's own boot sequence (main()'s calls, fixed args) ---- */
    Scene sc;
    if (!scene_load_glb(&sc, glbPath)) return 1;
    Camdat cd;
    if (!camdat_load(&cd, camdatPath, glbPath)) {
        fprintf(stderr, "camdat: no usable track\n");
        return 1;
    }
    const CamTrack *track = &cd.tracks[cd.activeTrack];

    LiveApp A;
    memset(&A, 0, sizeof(A));
    /* the engine's default boot pair (map-default {1,1} day street — the
     * wallpaper's --preset 1 / camdat-pair resolution lands identically) */
    A.env.nightP0 = SH_PRESET_6_IDX;
    A.env.nightP1 = SH_PRESET_3_IDX;
    A.env.nightM0 = SH_PRESET_7_IDX;
    A.env.nightM1 = SH_PRESET_4_IDX;
    sh_env_set_pair(&A.env, SH_PRESET_DAY_IDX, SH_PRESET_DAY_IDX);
    A.env.night = 0;
    A.modeCursor = SH_PRESET_DAY_IDX;
    sh_flashlight_set(&A.env, 0);     /* day street: no flashlight */
    A.env.lensFlareType = 0;

    RenderOpts opts;
    memset(&opts, 0, sizeof(opts));
    opts.ps1 = 1;                     /* PSX wobble ON (the shipped default) */
    opts.linear = 0;

    A.sc = &sc;
    A.cd = &cd;
    A.track = track;
    A.fog = &A.env.fog;

    /* the engine's particles + the wallpaper's weather-target boot (the
     * track's own recipe through the RUNTIME 512-tick cross-fade — the
     * shipped model env weatherTarget 4, exactly what the service boots) */
    snow_init(&A.snow, &sc, track);
    {
        int weatherTarget = cd.tracks[cd.activeTrack].weatherTarget;
        if (weatherTarget < 0 && cd.hasEnv && cd.envWeatherTarget >= 0)
            weatherTarget = cd.envWeatherTarget;
        if (weatherTarget >= 0)
            sh_particle_weather_target_set(&A.snow, weatherTarget);
    }

    A.R.sc = &sc;
    A.R.fog = &A.env.fog;
    A.R.env = &A.env;
    A.R.opts = opts;
    A.R.quantize = opts.ps1;
    A.R.dither = dither;
    A.R.fogEnabled = 1;               /* the engine default (v10 FIX) */
    A.R.fb_w = w;
    A.R.fb_h = h;
    A.R.enginePixScale = (float) w / 320.0f;
    A.R.enginePixScaleY = (float) h / 224.0f;
    A.R.snow = A.snow.enabled ? &A.snow : NULL;
    frame_alloc(&A.fb, w, h);
    A.argb = (uint32_t *) malloc((size_t) w * h * 4);
    A.play.t = 0;
    A.play.speed = 1.0;
    A.play.sessionT = 0;
    turn_init(&A.play.turn);
    A.duration = duration;
    A.maxFps = rate;

    /* ---- the standardized 60 s loop (run_benchmark VERBATIM + dispatch) -- */
    int n = (int) (duration * rate) + 2;
    double t0 = now_sec();
    double firstFrameMs = -1.0;
    int i;
    for (i = 0; i < n; i++) {
        double dt = 1.0 / rate;
        if (A.duration > 0 && A.play.sessionT + dt > A.duration)
            dt = A.duration - A.play.sessionT;
        if (dt <= 0) break;
        double f0 = now_sec();
        CamPose cp = live_advance(&A, dt);
        if (live_done(&A)) break;
#ifdef SH1_HW_RENDER
        /* the v9.9 backend dispatch — EXACTLY run_window_android's
         * substitution (software keeps the engine's direct call) */
        if (g_sh1_hw_backend != 0) {
            sh1_hw_render(&A, &cp);
        } else
#endif
        {
            render_frame(&A.fb, &A.R, &cp);
        }
        double f1 = now_sec();
        if (firstFrameMs < 0) firstFrameMs = (f1 - f0) * 1000.0;
    }
    double el = now_sec() - t0;
    double mean = el * 1000.0 / (i > 0 ? i : 1);
    printf("battery-bench: backend=%s frames=%d wall=%.3fs mean=%.3f ms/frame "
           "(steady mean excl. first=%.3f) res=%dx%d dither=%d weatherTarget=%d\n",
           bname, i, el, mean,
           i > 1 ? (el * 1000.0 - firstFrameMs) / (i - 1) : mean,
           w, h, dither, cd.tracks[cd.activeTrack].weatherTarget >= 0
               ? cd.tracks[cd.activeTrack].weatherTarget
               : (cd.hasEnv ? cd.envWeatherTarget : -1));

#ifdef SH1_HW_RENDER
    sh1_hw_shutdown();
#endif
    live_save_final(&A);
    scene_free(&sc);
    camdat_free(&cd);
    return 0;
}
