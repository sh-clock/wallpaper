/* v10.3 bench stub: the minimal ANativeWindow surface contract the engine's
 * Android presentation block needs. The desktop harness provides the
 * implementations; on a real device they come from libandroid. */
#ifndef SH1_BENCH_NATIVE_WINDOW_STUB_H
#define SH1_BENCH_NATIVE_WINDOW_STUB_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ANativeWindow ANativeWindow;

enum {
    WINDOW_FORMAT_RGBA_8888 = 1,
    WINDOW_FORMAT_RGBX_8888 = 2,
    WINDOW_FORMAT_RGB_565 = 4,
};

typedef struct ANativeWindow_Buffer {
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t format;
    void *bits;
} ANativeWindow_Buffer;

int  ANativeWindow_lock(ANativeWindow *window, ANativeWindow_Buffer *outBuffer, void *inOutDirtyBounds);
int  ANativeWindow_unlockAndPost(ANativeWindow *window);
void ANativeWindow_acquire(ANativeWindow *window);
void ANativeWindow_release(ANativeWindow *window);

#ifdef __cplusplus
}
#endif

#endif
