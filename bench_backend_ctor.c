/* bench_backend_ctor.c — v10.0 Phase I battery harness driver.
 * ============================================================================
 * Selects the rendering backend for the DESKTOP battery benchmark BEFORE
 * the engine's main() parses argv — through a process constructor, so the
 * ENGINE SOURCE carries ZERO delta (the --hw CLI parse is deliberately
 * Android-gated in fog_poc.c; the desktop benchmark selects the backend
 * via the SH1_BENCH_BACKEND environment variable instead).
 *
 *   unset                -> backend 0 (software — the default + truth)
 *   SH1_BENCH_BACKEND=gles  -> backend 1 (OpenGL ES)
 *   SH1_BENCH_BACKEND=vk    -> backend 2 (Vulkan)
 *
 * The rest of the harness is the engine's own --benchmark N mode: N frames
 * of the REAL realtime loop (the same code path the wallpaper runs), with
 * the same camdat/Glb as the shipped wallpaper. Timing: wall clock;
 * instruction attribution: valgrind --tool=callgrind.
 * ========================================================================*/
#include <stdlib.h>
#include <string.h>

void sh1_hw_set_backend(int backend);

__attribute__((constructor))
static void bench_pick_backend(void) {
    const char *b = getenv("SH1_BENCH_BACKEND");
    if (b == NULL) {
        return;                 /* software: the engine default */
    }
    if (strcmp(b, "gles") == 0 || strcmp(b, "gl") == 0) {
        sh1_hw_set_backend(1);
    } else if (strcmp(b, "vk") == 0 || strcmp(b, "vulkan") == 0) {
        sh1_hw_set_backend(2);
    }
}
