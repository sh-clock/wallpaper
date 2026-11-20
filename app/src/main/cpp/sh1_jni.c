/* sh1_jni.c - JNI bootstrap for the Silent Hill PoC on Android.
 *
 * Responsibilities:
 *   - own the Surface lifecycle (publish ANativeWindow to the engine)
 *   - spawn the engine thread and drive fog_poc.c's main() with a
 *     programmatically built argv (reuses 100% of the CLI setup logic:
 *     GLB/IPD/PLM loading, camdat parsing, renderer init)
 *   - forward optional UI key events into the engine's LiveKey enum
 *
 * JNI symbols are generated against SH1_JNI_CLASS so the SAME C source
 * serves any Java host package:
 *   standalone:  com.sh1.poc.Sh1Native                         (default)
 *   doodle fork: xyz.zedler.patrick.doodle.service.Sh1Native
 *                (-DSH1_JNI_CLASS=xyz_zedler_patrick_doodle_service_Sh1Native)
 *
 * The engine thread keeps running across surface destroy/recreate (the
 * window pointer is swapped under a mutex) and idles while invisible,
 * which is what makes it safe as a live wallpaper engine. No
 * --duration is passed, so live_done() never fires and the camera
 * tracks cycle forever. The on-screen HUD is disabled with --no-hud:
 * a wallpaper has no keyboard, so it would be uncontrollable.
 *
 * v3: per-engine surface bindings (fixes the apply-flow black screen).
 *   Android runs SEVERAL Engine instances of the same WallpaperService
 *   in one process: the system preview (WallpaperPreviewActivity) and
 *   the real wallpaper behind the launcher. Their callbacks interleave
 *   — when the user taps "Set wallpaper", the real engine attaches
 *   WHILE the preview is still alive, and only afterwards does the
 *   preview finish (onVisibilityChanged(false) + onSurfaceDestroyed).
 *   A single global window pointer is clobbered by whichever engine
 *   tears down last: the real wallpaper loses its window and never
 *   renders again (black screen). So every engine registers its own
 *   binding under a generation id handed out by Java, and we always
 *   publish the NEWEST binding that still holds a live surface, using
 *   that engine's visibility flag. Tearing down one engine falls back
 *   to the next-best binding automatically. */

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

extern int main(int argc, char **argv);
extern void sh1_android_set_window(ANativeWindow *win);
extern void sh1_android_set_visible(int vis);
extern void sh1_android_post_key(int k);
extern void sh1_android_set_env(int idx);
extern void sh1_android_set_weather(int w);
extern void sh1_android_set_env_trans(int idx);  /* v9 F20: gradual transition */
extern void sh1_android_set_tilt(double yawDeg, double pitchDeg); /* v9 F23 */
extern void sh1_android_set_freeze(int on);
extern void sh1_android_set_crop(float x, float y);   /* v10.1 TASK 4: crop offsets */
extern void sh1_android_get_stats(double *out);      /* v7: read-only diagnostics */
extern int  sh1_android_grab_frame(uint32_t *dst, int max_px);  /* v7: frame snapshot */
extern void sh1_android_set_fps_cap(double fps);     /* v7: runtime present cap */
extern void sh1_android_prefetch(const char *path);  /* v7: IO-only pre-warm    */
extern void sh1_android_seek(double t);              /* v9.7: editor scrub      */
extern void sh1_android_set_paused(int on);          /* v9.7: editor pause      */
extern void sh1_android_set_rate(double rate);       /* v9.7: editor rate       */

/* LiveKey K_QUIT value (sh1_keys.h enum order: NONE=0, ESC=1, QUIT=2).
 * Posting it makes run_window_android's own key poll break the loop
 * through live_action() -> the engine's OWN quit path (no new engine
 * code). Kept in sync with sh1_keys.h manually, like the header itself. */
#define SH1_KEY_QUIT 2

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  "sh1poc-jni", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "sh1poc-jni", __VA_ARGS__)

/* --- JNI symbol generation ----------------------------------------- */
#ifndef SH1_JNI_CLASS
#define SH1_JNI_CLASS com_sh1_poc_Sh1Native
#endif
#define SH1_CAT_(a, b) a##b
#define SH1_CAT(a, b)  SH1_CAT_(a, b)
#define SH1_JNI_FN(name) SH1_CAT(Java_, SH1_CAT(SH1_JNI_CLASS, SH1_CAT(_, name)))

static char g_dir[512] = {0};          /* app filesDir, set by start() */
static pthread_t g_thread;
static volatile int g_started = 0;
static volatile int g_thread_valid = 0;  /* g_thread holds a joinable id */
static pthread_mutex_t g_start_lock = PTHREAD_MUTEX_INITIALIZER;

/* the scene the engine was last started with (basename only) */
static char g_glb[256] = {0};
static int g_track = -1;

typedef struct { int argc; char **argv; } MainArgs;

static void *engine_thread(void *p) {
    MainArgs *ma = (MainArgs *)p;
    ALOGI("engine thread: entering fog_poc main() (dir=%s)", g_dir);
    int rc = main(ma->argc, ma->argv);
    ALOGI("engine thread: main() returned %d", rc);
    for (int i = 0; i < ma->argc; i++) free(ma->argv[i]);
    free(ma->argv);
    free(ma);
    g_started = 0;
    g_thread_valid = 0;
    return NULL;
}

/* --- per-engine surface bindings ------------------------------------ */

#define SH1_MAX_BIND 4

typedef struct {
    int            gen;      /* generation id; 0 = free slot             */
    ANativeWindow *win;      /* our ANativeWindow reference (may be NULL) */
    int            visible;  /* that engine's visibility flag            */
} Sh1Bind;

static Sh1Bind s_bind[SH1_MAX_BIND];
static pthread_mutex_t s_bind_lock = PTHREAD_MUTEX_INITIALIZER;

static Sh1Bind *bind_find_locked(int gen) {
    for (int i = 0; i < SH1_MAX_BIND; i++)
        if (s_bind[i].gen == gen) return &s_bind[i];
    return NULL;
}

/* find or create the binding for gen; evicts the oldest if the table
 * is full (preview + live + one relayout = 3, so this never happens) */
static Sh1Bind *bind_acquire_locked(int gen) {
    Sh1Bind *b = bind_find_locked(gen);
    if (b) return b;
    Sh1Bind *slot = NULL, *oldest = &s_bind[0];
    for (int i = 0; i < SH1_MAX_BIND; i++) {
        if (!s_bind[i].gen) { slot = &s_bind[i]; break; }
        if (s_bind[i].gen < oldest->gen) oldest = &s_bind[i];
    }
    if (!slot) {
        slot = oldest;
        ALOGI("bind table full, evicting gen %d", slot->gen);
    }
    if (slot->win) { ANativeWindow_release(slot->win); slot->win = NULL; }
    slot->gen = gen;
    slot->visible = 1;
    return slot;
}

static void bind_release_locked(int gen) {
    Sh1Bind *b = bind_find_locked(gen);
    if (!b) return;
    if (b->win) { ANativeWindow_release(b->win); b->win = NULL; }
    b->gen = 0;
    b->visible = 0;
}

/* publish the newest binding that still holds a surface to the render
 * thread; its visibility flag gates rendering. Called after every
 * binding event so teardowns transparently fall back to the engine
 * that should own the screen next. */
static void bind_publish_locked(void) {
    Sh1Bind *best = NULL;
    for (int i = 0; i < SH1_MAX_BIND; i++) {
        Sh1Bind *b = &s_bind[i];
        if (b->gen && b->win && (!best || b->gen > best->gen)) best = b;
    }
    sh1_android_set_visible(best ? best->visible : 0);
    sh1_android_set_window(best ? best->win : NULL);
    ALOGI("publish: gen=%d %s visible=%d",
          best ? best->gen : 0, best ? "surface" : "none",
          best ? best->visible : 0);
}

/* --- JNI entry points ------------------------------------------------ */

/* start the engine once per process (idempotent; survives hosts)
 * bootPreset: the effective env mode at boot — passed as the PoC's own
 *   --preset N flag (Gfx_MapEnvSet(N,N) at boot; CLI precedence over the
 *   camdat env object is the PoC's documented rule). -1 = absent -> the
 *   scene's camdat env pair wins (faithful PoC default boot).
 * bootWeather: runtime weather override 0..8 passed as --weather-target N
 *   (sharedFunc_800D0B18 at boot — the exact boot mechanism the PoC CLI
 *   documents for this). -1 = absent -> the camdat weather recipe wins.
 * glbFile: BASENAME of the scene GLB inside <filesDir>/sh1 (from the
 *   active camdat.json models[].file). NULL/"" -> "THRF9FD.glb" (the
 *   PoC's own default). Rejected if it contains '/', '\\' or ".." —
 *   path traversal must never reach argv.
 * trackIdx: camera track index inside that model's tracks[] passed as
 *   --track N (the PoC's own track-selection flag, like the editor's
 *   play button). -1 = absent -> the first enabled track (PoC default).
 * resW/resH (v7 F17): the ENGINE'S OWN --res flag. 0/0 = absent -> the
 *   engine default 320x224 (the authentic PS1 raster — main()'s own
 *   w=320 h=224). The caller maps the user's setting onto this; the
 *   engine path is exactly the desktop one. A changed resolution needs
 *   an engine restart (framebuffer allocation happens in main()).
 * fps (v7 F18): the ENGINE'S OWN --fps flag (presentation cap).
 *   <= 0 -> 30, the engine's authentic default (g_IntervalVBlanks=2,
 *   one present per 30 Hz tick). 60 = display-duplicate mode. The
 *   runtime cap seam may only step BELOW this boot value (F1/F12).
 *   v9 F26: any value 1..60 is legal — sub-15 rates switch the Android
 *   loop to the 30 Hz cadence + fractional present gate (world speed
 *   stays real-time; only the raster is sampled).
 * wobble (v9 F24): -1 = absent (the scene's camdat psxWobble + the
 *   engine's own default win — the AUTHENTIC behavior), 1 = forced on
 *   (--ps1: GTE integer vertex snap + affine UV + 15-bit), 0 = forced
 *   off (--modern: perspective-correct, 24-bit). Uses the engine's own
 *   CLI flags; a change needs an engine restart like --res.
 * backend (v9.9): the RENDERING backend — 0 = software (the DEFAULT
 *   and source of truth), 1 = OpenGL ES (hardware, experimental),
 *   2 = Vulkan (hardware, experimental). Passed as the engine's own
 *   --hw flag; a change needs an engine restart exactly like --res
 *   (the EGL/Vulkan context is created at boot and torn down at exit).
 *   The hardware backends render ONLY the mesh rasterization on the
 *   GPU (the desktop-proven substitution); snow/rain sprites, the
 *   brightness overlay and the ANativeWindow presentation remain the
 *   engine's own CPU code — identical in all three backends.
 * dither (v10.0 Task 3): 1 = dither ON (NO flag — the engine default +
 *   the scene's own camdat envDither rule, the authentic chain, exactly
 *   like wobble's absent state); 0 = forced off (--no-dither — the
 *   resolution-aware automatic default above the native 320x224
 *   raster). Gates ALL backends: the engine's single dither global
 *   drives the software fb_put path and the GLES/Vulkan shader flag.
 *   A change needs an engine restart exactly like --res.            */
JNIEXPORT void JNICALL
SH1_JNI_FN(start)(JNIEnv *env, jclass clz, jstring filesDir,
                  jint bootPreset, jint bootWeather,
                  jstring glbFile, jint trackIdx,
                  jint resW, jint resH, jdouble fps, jint wobble,
                  jint dither, jint backend) {
    (void)clz;
    pthread_mutex_lock(&g_start_lock);
    if (g_started) { ALOGI("start: engine already running"); pthread_mutex_unlock(&g_start_lock); return; }

    const char *dir = (*env)->GetStringUTFChars(env, filesDir, NULL);
    snprintf(g_dir, sizeof(g_dir), "%s", dir ? dir : "");
    (*env)->ReleaseStringUTFChars(env, filesDir, dir);
    if (!g_dir[0]) { ALOGE("start: empty filesDir"); pthread_mutex_unlock(&g_start_lock); return; }

    /* scene GLB: validate the basename BEFORE it reaches argv */
    char glbName[256];
    glbName[0] = 0;
    if (glbFile != NULL) {
        const char *g = (*env)->GetStringUTFChars(env, glbFile, NULL);
        if (g && *g) {
            if (strchr(g, '/') || strchr(g, '\\') || strstr(g, "..")) {
                ALOGE("start: rejected glb name '%s' (path separators)", g);
            } else {
                snprintf(glbName, sizeof(glbName), "%s", g);
            }
        }
        if (g) (*env)->ReleaseStringUTFChars(env, glbFile, g);
    }
    if (!glbName[0]) snprintf(glbName, sizeof(glbName), "THRF9FD.glb");
    snprintf(g_glb, sizeof(g_glb), "%s", glbName);
    g_track = (trackIdx >= 0) ? trackIdx : -1;

    /* argv mirroring the desktop realtime invocation, minus the HUD:
     *   fog_poc --glb <glb> --camdat <camdat> --input <dir> --window
     *           --fps 30 --no-hud [--preset N] [--weather-target N]
     *           [--track N] [--res WxH]
     * Engine-native 320x224 raster (no --res flag) and 30 fps are the
     * main() defaults; --res/--fps carry the v7 F17/F18 user settings
     * through the SAME flags the desktop CLI uses. */
    if (resW < 0) resW = 0;
    if (resH < 0) resH = 0;
    if (resW == 0 || resH == 0) { resW = 0; resH = 0; }   /* absent */
    int useRes = (resW >= 16 && resH >= 16 && resW <= 4096 && resH <= 4096);
    if (fps <= 0.0 || fps > 240.0) fps = 30.0;             /* authentic */
    int usePreset = (bootPreset >= 0 && bootPreset <= 20);
    int useWeather = (bootWeather >= 0 && bootWeather <= 8);
    int useTrack = (g_track >= 0);
    int useWobbleOn  = (wobble == 1);    /* v9 F24: --ps1   (forced on)  */
    int useWobbleOff = (wobble == 0);    /* v9 F24: --modern (forced off) */
    int useNoDither  = (dither == 0);    /* v10.0: --no-dither (forced off;
                                            1 = absent = the authentic
                                            engine default + camdat rule) */
    int backendId = (backend == 1 || backend == 2) ? (int)backend : 0;
    int argc = 11 + (usePreset ? 2 : 0) + (useWeather ? 2 : 0)
             + (useTrack ? 2 : 0) + (useRes ? 2 : 0)
             + (useWobbleOn ? 1 : 0) + (useWobbleOff ? 1 : 0)
             + (useNoDither ? 1 : 0)
             + (backendId ? 2 : 0);
    MainArgs *ma = (MainArgs *)malloc(sizeof(MainArgs));
    if (!ma) { pthread_mutex_unlock(&g_start_lock); return; }
    ma->argc = argc;
    ma->argv = (char **)calloc(argc + 1, sizeof(char *));
    if (!ma->argv) { free(ma); pthread_mutex_unlock(&g_start_lock); return; }
    char glbPathBuf[600], camdatPathBuf[600], inputBuf[600], fpsBuf[32], resBuf[32];
    snprintf(glbPathBuf, sizeof(glbPathBuf), "%s/sh1/%s", g_dir, glbName);
    snprintf(camdatPathBuf, sizeof(camdatPathBuf), "%s/sh1/camdat.json", g_dir);
    snprintf(inputBuf, sizeof(inputBuf), "%s/sh1", g_dir);
    snprintf(fpsBuf, sizeof(fpsBuf), "%d", (int)(fps + 0.5));
    ma->argv[0]  = strdup("fog_poc");
    ma->argv[1]  = strdup("--glb");
    ma->argv[2]  = strdup(glbPathBuf);
    ma->argv[3]  = strdup("--camdat");
    ma->argv[4]  = strdup(camdatPathBuf);
    ma->argv[5]  = strdup("--input");
    ma->argv[6]  = strdup(inputBuf);
    ma->argv[7]  = strdup("--window");
    ma->argv[8]  = strdup("--fps");
    ma->argv[9]  = strdup(fpsBuf);
    ma->argv[10] = strdup("--no-hud");
    int ai = 11;
    char num[16];
    if (usePreset) {
        ma->argv[ai++] = strdup("--preset");
        snprintf(num, sizeof(num), "%d", (int)bootPreset);
        ma->argv[ai++] = strdup(num);
    }
    if (useWeather) {
        ma->argv[ai++] = strdup("--weather-target");
        snprintf(num, sizeof(num), "%d", (int)bootWeather);
        ma->argv[ai++] = strdup(num);
    }
    if (useTrack) {
        ma->argv[ai++] = strdup("--track");
        snprintf(num, sizeof(num), "%d", g_track);
        ma->argv[ai++] = strdup(num);
    }
    if (useRes) {
        snprintf(resBuf, sizeof(resBuf), "%dx%d", (int)resW, (int)resH);
        ma->argv[ai++] = strdup("--res");
        ma->argv[ai++] = strdup(resBuf);
    }
    /* v9 F24: the engine's own wobble CLI flags (identical to the desktop
     * invocation); absent = the camdat psxWobble + engine default rule */
    if (useWobbleOn)  ma->argv[ai++] = strdup("--ps1");
    if (useWobbleOff) ma->argv[ai++] = strdup("--modern");
    /* v10.0 Task 3: the engine's own dither CLI flag — forced OFF only
     * (the resolution-aware auto rule); absent = the authentic engine
     * default + the scene's own camdat envDither precedence */
    if (useNoDither) ma->argv[ai++] = strdup("--no-dither");
    /* v9.9: the rendering-backend flag (the engine's own --hw seam);
     * absent = software (the default) */
    if (backendId) {
        ma->argv[ai++] = strdup("--hw");
        ma->argv[ai++] = strdup(backendId == 1 ? "gles" : "vk");
    }

    g_started = 1;
    g_thread_valid = 1;
    /* v38 (audit-B): the engine thread now carries a ~300 KB Scene (the
     * v38 animsFresh[64] engine-fresh snapshot doubled the stack-local
     * struct) plus realtime call depth (render -> bind's Grp grps[64] ~
     * 17 KB nested, raster/fx locals, the Vulkan backend chain). The
     * bionic default ~1 MB stack leaves an untracked margin — give the
     * engine thread an explicit 4 MB so the peak (~0.5 MB) sits far from
     * the guard page. */
    pthread_attr_t engAttr;
    pthread_attr_init(&engAttr);
    pthread_attr_setstacksize(&engAttr, 4u << 20);
    if (pthread_create(&g_thread, &engAttr, engine_thread, ma) != 0) {
        pthread_attr_destroy(&engAttr);
        ALOGE("start: pthread_create failed");
        g_started = 0;
        g_thread_valid = 0;
        for (int i = 0; i < ma->argc; i++) free(ma->argv[i]);
        free(ma->argv); free(ma);
        pthread_mutex_unlock(&g_start_lock);
        return;
    }
    pthread_attr_destroy(&engAttr);   /* v38: success path cleanup */
    ALOGI("start: scene %s track %d preset %d weather %d res %s fps %d wobble %s dither %s backend %d",
          glbName, g_track, bootPreset, bootWeather,
          useRes ? resBuf : "default(320x224)", (int)(fps + 0.5),
          useWobbleOn ? "on(--ps1)" : (useWobbleOff ? "off(--modern)"
                                                    : "camdat/default"),
          useNoDither ? "off(--no-dither)" : "engine-default",
          backendId);
    pthread_mutex_unlock(&g_start_lock);

    /* re-assert the current best surface binding onto the FRESH engine:
     * g_and_win/g_and_visible persist in the process, but publishing
     * again is harmless and covers a restart that raced a binding
     * change (the freeze dirty flag re-renders if frozen). */
    pthread_mutex_lock(&s_bind_lock);
    bind_publish_locked();
    pthread_mutex_unlock(&s_bind_lock);
}

/* stop the engine thread through its OWN quit path: K_QUIT is polled at
 * the top of run_window_android BEFORE the visibility gate, so it works
 * even while invisible; main() then returns normally (full teardown),
 * engine_thread clears g_started. Call from a BACKGROUND thread — the
 * join normally completes within one 33 ms loop pass plus teardown. */
JNIEXPORT jboolean JNICALL
SH1_JNI_FN(stop)(JNIEnv *env, jclass clz) {
    (void)env; (void)clz;
    pthread_mutex_lock(&g_start_lock);
    if (!g_started || !g_thread_valid) {
        pthread_mutex_unlock(&g_start_lock);
        return JNI_TRUE;   /* already stopped */
    }
    sh1_android_post_key(SH1_KEY_QUIT);
    pthread_t t = g_thread;
    pthread_mutex_unlock(&g_start_lock);
    pthread_join(t, NULL);
    ALOGI("stop: engine thread joined, g_started=%d", g_started);
    return g_started == 0 ? JNI_TRUE : JNI_FALSE;
}

/* publish THIS engine's surface (surfaceCreated); takes our reference */
JNIEXPORT void JNICALL
SH1_JNI_FN(surfaceCreated)(JNIEnv *env, jclass clz, jint gen, jobject surface) {
    (void)clz;
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (!win) { ALOGE("surfaceCreated: ANativeWindow_fromSurface failed"); return; }
    pthread_mutex_lock(&s_bind_lock);
    Sh1Bind *b = bind_acquire_locked(gen);
    if (b->win) ANativeWindow_release(b->win);  /* re-attach after relayout */
    b->win = win;
    b->visible = 1;   /* a fresh surface counts as visible; the very next
                         onVisibilityChanged resyncs the flag */
    bind_publish_locked();
    pthread_mutex_unlock(&s_bind_lock);
}

/* THIS engine's surface is going away; call BEFORE returning from
 * onSurfaceDestroyed. Falls back to any other live engine. */
JNIEXPORT void JNICALL
SH1_JNI_FN(surfaceDestroyed)(JNIEnv *env, jclass clz, jint gen) {
    (void)env; (void)clz;
    pthread_mutex_lock(&s_bind_lock);
    bind_release_locked(gen);
    bind_publish_locked();
    pthread_mutex_unlock(&s_bind_lock);
}

/* per-engine visibility gate (battery: false = engine idles at ~33ms) */
JNIEXPORT void JNICALL
SH1_JNI_FN(visible)(JNIEnv *env, jclass clz, jint gen, jboolean vis) {
    (void)env; (void)clz;
    pthread_mutex_lock(&s_bind_lock);
    Sh1Bind *b = bind_acquire_locked(gen);
    b->visible = vis ? 1 : 0;
    bind_publish_locked();
    pthread_mutex_unlock(&s_bind_lock);
}

/* forward an engine LiveKey value (future settings toggles) */
JNIEXPORT void JNICALL
SH1_JNI_FN(key)(JNIEnv *env, jclass clz, jint key) {
    (void)env; (void)clz;
    sh1_android_post_key((int)key);
}

/* apply an environment preset at runtime — consumed by the realtime loop
 * through the engine's own live_apply_preset path (Gfx_MapEnvSet(idx,idx)) */
JNIEXPORT void JNICALL
SH1_JNI_FN(env)(JNIEnv *env, jclass clz, jint presetIdx) {
    (void)env; (void)clz;
    sh1_android_set_env((int)presetIdx);
}

/* v9 F20: apply an environment preset through the engine's GRADUAL
 * transition (the Gfx_MapEnvUpdate(PrimitiveType_S32) progress blend the
 * game uses at its day/night boundaries) — consumed by the realtime loop
 * via live_apply_env_transition; the blend machinery is verbatim. */
JNIEXPORT void JNICALL
SH1_JNI_FN(envTransition)(JNIEnv *env, jclass clz, jint presetIdx) {
    (void)env; (void)clz;
    sh1_android_set_env_trans((int)presetIdx);
}

/* v9 F23: device-tilt camera offset (degrees; render pose only — the
 * engine keeps the authentic camtrack camera). See fog_poc.c's seam. */
JNIEXPORT void JNICALL
SH1_JNI_FN(setTilt)(JNIEnv *env, jclass clz, jdouble yawDeg, jdouble pitchDeg) {
    (void)env; (void)clz;
    sh1_android_set_tilt((double)yawDeg, (double)pitchDeg);
}

/* set the runtime weather target (sharedFunc_800D0B18) — consumed by the
 * realtime loop through the engine's own weather-transition path */
JNIEXPORT void JNICALL
SH1_JNI_FN(weather)(JNIEnv *env, jclass clz, jint weather) {
    (void)env; (void)clz;
    sh1_android_set_weather((int)weather);
}

/* battery/power-save freeze: render the current frame exactly ONCE and
 * hold the Surface at 0 FPS (never a static image swap — the frozen
 * output is the software-rasterized engine frame; see fog_poc.c's seam) */
JNIEXPORT void JNICALL
SH1_JNI_FN(freeze)(JNIEnv *env, jclass clz, jboolean on) {
    (void)env; (void)clz;
    sh1_android_set_freeze(on ? 1 : 0);
}

/* v10.1 TASK 4: the user's crop-window offsets (normalized -1..1, 0 =
 * symmetric center). Presentation-layer only — consumed by and_blit at
 * every presented frame (the same seam class as visible/freeze); a live
 * change applies on the next frame, no engine restart, no engine math. */
JNIEXPORT void JNICALL
SH1_JNI_FN(setCropOffset)(JNIEnv *env, jclass clz, jfloat x, jfloat y) {
    (void)env; (void)clz;
    sh1_android_set_crop((float)x, (float)y);
}

/* v7 F6: is the engine thread alive (g_started)? Read-only — lets the
 * Java watchdog distinguish "corrupt GLB killed main()" from "engine
 * presenting normally" without any new engine code. */
JNIEXPORT jboolean JNICALL
SH1_JNI_FN(isRunning)(JNIEnv *env, jclass clz) {
    (void)env; (void)clz;
    return g_started ? JNI_TRUE : JNI_FALSE;
}

/* v7 F8: read-only diagnostics — {fps, frameMs, frozen, fbW, fbH}.
 * Never null when the engine has run; zeros when it has not. */
JNIEXPORT jdoubleArray JNICALL
SH1_JNI_FN(stats)(JNIEnv *env, jclass clz) {
    (void)clz;
    double s[5] = {0, 0, 0, 0, 0};
    sh1_android_get_stats(s);
    jdoubleArray arr = (*env)->NewDoubleArray(env, 5);
    if (arr == NULL) return NULL;
    (*env)->SetDoubleArrayRegion(env, arr, 0, 5, (const jdouble *)s);
    return arr;
}

/* v7 F6/F9: copy the last PRESENTED frame (ARGB_8888 packed ints,
 * fbW*fbH pixels — Bitmap.createBitmap colors[] layout). Returns NULL
 * when the engine has not presented anything (not running / no frame). */
JNIEXPORT jintArray JNICALL
SH1_JNI_FN(grabFrame)(JNIEnv *env, jclass clz) {
    (void)clz;
    double s[5] = {0, 0, 0, 0, 0};
    sh1_android_get_stats(s);
    int w = (int)s[3], h = (int)s[4];
    if (w <= 0 || h <= 0) return NULL;
    int n = w * h;
    uint32_t *buf = (uint32_t *)malloc((size_t)n * sizeof(uint32_t));
    if (!buf) return NULL;
    int got = sh1_android_grab_frame(buf, n);
    jintArray arr = NULL;
    if (got == n) {
        arr = (*env)->NewIntArray(env, n);
        if (arr != NULL)
            (*env)->SetIntArrayRegion(env, arr, 0, n, (const jint *)buf);
    }
    free(buf);
    return arr;
}

/* v7 F1/F12/F18: runtime PRESENTATION cap (adaptive pacing governor,
 * battery half-rate). Only ever steps BELOW the boot --fps value; the
 * engine's 30 Hz tick lockstep is untouched (fog_poc.c seam comment). */
JNIEXPORT void JNICALL
SH1_JNI_FN(setFpsCap)(JNIEnv *env, jclass clz, jdouble fps) {
    (void)env; (void)clz;
    sh1_android_set_fps_cap((double)fps);
}

/* v7 F4: IO-only read-ahead of a scene GLB (posix_fadvise WILLNEED with
 * a sequential-read fallback). Safe to call from any thread; no engine
 * state is involved. */
JNIEXPORT void JNICALL
SH1_JNI_FN(prefetch)(JNIEnv *env, jclass clz, jstring path) {
    (void)clz;
    if (path == NULL) return;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p) {
        sh1_android_prefetch(p);
        (*env)->ReleaseStringUTFChars(env, path, p);
    }
}

/* v9.7 Camtrack editor preview: scrub the live preview to an absolute
 * track phase (seconds inside the cycle; the loop wraps it). Consumed by
 * the realtime loop through the engine's own exact-pose-on-seek rule —
 * see fog_poc.c's editor seam comments. */
JNIEXPORT void JNICALL
SH1_JNI_FN(seek)(JNIEnv *env, jclass clz, jdouble t) {
    (void)env; (void)clz;
    sh1_android_seek((double)t);
}

/* v9.7 Camtrack editor preview: pause/resume the camtrack playback (the
 * K_SPACE field — the engine's own pause path). */
JNIEXPORT void JNICALL
SH1_JNI_FN(setPaused)(JNIEnv *env, jclass clz, jboolean paused) {
    (void)env; (void)clz;
    sh1_android_set_paused(paused ? 1 : 0);
}

/* v9.7 Camtrack editor preview: playback rate (the K_PLUS/K_MINUS field,
 * engine range 0.125..8). */
JNIEXPORT void JNICALL
SH1_JNI_FN(setRate)(JNIEnv *env, jclass clz, jdouble rate) {
    (void)env; (void)clz;
    sh1_android_set_rate((double)rate);
}
