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
    return NULL;
}

static char *path_join(const char *dir, const char *name) {
    size_t n = strlen(dir) + strlen(name) + 2;
    char *s = (char *)malloc(n);
    if (s) snprintf(s, n, "%s/%s", dir, name);
    return s;
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

/* start the engine once per process (idempotent; survives hosts) */
JNIEXPORT void JNICALL
SH1_JNI_FN(start)(JNIEnv *env, jclass clz, jstring filesDir) {
    (void)clz;
    if (g_started) { ALOGI("start: engine already running"); return; }

    const char *dir = (*env)->GetStringUTFChars(env, filesDir, NULL);
    snprintf(g_dir, sizeof(g_dir), "%s", dir ? dir : "");
    (*env)->ReleaseStringUTFChars(env, filesDir, dir);
    if (!g_dir[0]) { ALOGE("start: empty filesDir"); return; }

    /* argv mirroring the desktop realtime invocation, minus the HUD:
     *   fog_poc --glb <glb> --camdat <camdat> --input <dir> --window
     *           --fps 30 --no-hud
     * Engine-native 320x224 raster and PSX-wobble are the main() defaults. */
    MainArgs *ma = (MainArgs *)malloc(sizeof(MainArgs));
    if (!ma) return;
    ma->argc = 11;
    ma->argv = (char **)calloc(12, sizeof(char *));
    if (!ma->argv) { free(ma); return; }
    ma->argv[0]  = strdup("fog_poc");
    ma->argv[1]  = strdup("--glb");
    ma->argv[2]  = path_join(g_dir, "sh1/HP0003.glb");
    ma->argv[3]  = strdup("--camdat");
    ma->argv[4]  = path_join(g_dir, "sh1/camdat.json");
    ma->argv[5]  = strdup("--input");
    ma->argv[6]  = path_join(g_dir, "sh1");
    ma->argv[7]  = strdup("--window");
    ma->argv[8]  = strdup("--fps");
    ma->argv[9]  = strdup("30");
    ma->argv[10] = strdup("--no-hud");

    g_started = 1;
    if (pthread_create(&g_thread, NULL, engine_thread, ma) != 0) {
        ALOGE("start: pthread_create failed");
        g_started = 0;
        for (int i = 0; i < ma->argc; i++) free(ma->argv[i]);
        free(ma->argv); free(ma);
        return;
    }
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
