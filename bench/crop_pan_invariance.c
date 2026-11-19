/*
 * crop_pan_invariance.c — v10.3 Phase G ACTION 4: the DESKTOP MATHEMATICAL
 * PROOF of TRUE CROP PANNING, built on the REAL engine translation unit.
 *
 * The harness #includes the SHIPPED fog_poc.c VERBATIM with the REAL
 * __ANDROID__ presentation block compiled in (minimal Linux stubs for the
 * four ANativeWindow calls + __android_log_print; the engine's own
 * desktop --shot path drives the render). It then proves, per run:
 *
 *   PROPERTY 1 (RASTER INVARIANCE — "no camera movement"):
 *     with the crop seam pre-set via the REAL sh1_android_set_crop, the
 *     engine renders the deterministic --shot frame; the output PNG must
 *     be BYTE-IDENTICAL for crop offsets (-1,0), (0,0), (+1,0). The crop
 *     offsets cannot alter any 3D vertex transformation, camera matrix,
 *     projection, camtrack evaluation or rasterization byte — the dataflow
 *     g_and_crop_x/y -> and_blit -> destination is presentation-only.
 *
 *   PROPERTY 2 (DESTINATION PAN — "the 2D pixel output shifts"):
 *     the REAL and_blit (static inside the same translation unit, called
 *     directly on a synthetic LiveApp whose fb/argb are the DECODED REAL
 *     rendered frame from PROPERTY 1) blits into a fake
 *     1080x2400 RGBA_8888 ANativeWindow surface; the destination dump is
 *     written to disk. Across the three offsets the destinations MUST
 *     differ (the visible window pans over the SAME source raster).
 *
 * The Python verifier (verify_crop_invariance.py) asserts both properties
 * plus the exact window math: every destination pixel equals the source
 * pixel at the coordinate the and_blit 16.16 DDA + the offset origin
 * computes — the crop is EXACTLY a 2D source-rectangle translation.
 *
 * usage: crop_pan_invariance <cropX> <cropY> <glbDir> <outShot.png> <outDst.raw>
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include "android/native_window.h"

/* ---- the fake surface the stub ANativeWindow_lock hands to and_blit --- */
static uint8_t *g_surf_bits = NULL;
static int g_surf_w = 0, g_surf_h = 0, g_surf_stride = 0, g_surf_format = 0;

int ANativeWindow_lock(ANativeWindow *window, ANativeWindow_Buffer *outBuffer,
                       void *inOutDirtyBounds) {
    (void)window; (void)inOutDirtyBounds;
    if (!g_surf_bits) return -1;
    outBuffer->width = g_surf_w;
    outBuffer->height = g_surf_h;
    outBuffer->stride = g_surf_stride;
    outBuffer->format = g_surf_format;
    outBuffer->bits = g_surf_bits;
    return 0;
}

int ANativeWindow_unlockAndPost(ANativeWindow *window) { (void)window; return 0; }
void ANativeWindow_acquire(ANativeWindow *window) { (void)window; }
void ANativeWindow_release(ANativeWindow *window) { (void)window; }

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stdout, "[%s] ", tag);
    vfprintf(stdout, fmt, ap);
    fprintf(stdout, "\n");
    va_end(ap);
    return 0;
}

/* ---- include the REAL engine (single translation unit) ---------------
 * __ANDROID__ is defined so the seam + and_blit + run_window_android are
 * compiled; the harness never calls run_window_android (only the desktop
 * --shot path through the engine's own main).                     */
#define main fog_poc_main
#include "../work/app/src/main/cpp/fog_poc.c"
#undef main

int main(int argc, char **argv) {
    if (argc < 6) {
        fprintf(stderr,
            "usage: %s <cropX> <cropY> <glbDir> <outShot.png> <outDst.raw>\n",
            argv[0]);
        return 2;
    }
    const float cropX = (float)atof(argv[1]);
    const float cropY = (float)atof(argv[2]);
    const char *dir = argv[3];
    const char *shotPath = argv[4];
    const char *dstPath = argv[5];

    /* 1. set the REAL crop seam to this run's offsets */
    sh1_android_set_crop(cropX, cropY);

    /* 2. drive the engine's own --shot path (the deterministic render) */
    char glb[600], camdat[600], inputDir[600], shotArg[700];
    snprintf(glb, sizeof(glb), "%s/THRF9FD.glb", dir);
    snprintf(camdat, sizeof(camdat), "%s/camdat.json", dir);
    snprintf(inputDir, sizeof(inputDir), "%s", dir);
    snprintf(shotArg, sizeof(shotArg), "t=2.5,file=%s", shotPath);
    char *engArgv[11];
    engArgv[0] = (char *)"fog_poc";
    engArgv[1] = (char *)"--glb";
    engArgv[2] = glb;
    engArgv[3] = (char *)"--camdat";
    engArgv[4] = camdat;
    engArgv[5] = (char *)"--input";
    engArgv[6] = inputDir;
    engArgv[7] = (char *)"--out";
    engArgv[8] = (char *)".";
    engArgv[9] = (char *)"--shot";
    engArgv[10] = shotArg;
    int rc = fog_poc_main(11, engArgv);
    if (rc != 0) {
        fprintf(stderr, "engine shot path failed (rc=%d)\n", rc);
        return 3;
    }

    /* 3. read the shot back and decode it with the engine's own decoder */
    FILE *fp = fopen(shotPath, "rb");
    if (!fp) { fprintf(stderr, "cannot reopen shot\n"); return 4; }
    fseek(fp, 0, SEEK_END);
    long pngN = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    uint8_t *png = (uint8_t *)malloc((size_t)pngN);
    if (fread(png, 1, (size_t)pngN, fp) != (size_t)pngN) {
        fprintf(stderr, "short read on shot\n"); return 4;
    }
    fclose(fp);
    int w = 0, h = 0;
    uint8_t *rgba = png_decode_rgba(png, (size_t)pngN, &w, &h);
    if (!rgba || w <= 0 || h <= 0) {
        fprintf(stderr, "png_decode_rgba failed on the shot\n"); return 5;
    }
    printf("decoded shot: %dx%d (crop %+0.1f,%+0.1f)\n", w, h, cropX, cropY);

    /* 4. run the REAL and_blit over the REAL rendered raster into the fake
     *    phone surface (1080x2400 RGBA_8888, trivial stride 1080) */
    g_surf_w = 1080;
    g_surf_h = 2400;
    g_surf_stride = 1080;
    g_surf_format = WINDOW_FORMAT_RGBA_8888;
    g_surf_bits = (uint8_t *)calloc(
        (size_t)g_surf_h * (size_t)g_surf_stride * 4u, 1u);

    static LiveApp app;   /* zeroed; and_blit reads only fb.w/fb.h/argb */
    app.fb.w = w;
    app.fb.h = h;
    app.argb = (uint32_t *)malloc((size_t)w * (size_t)h * sizeof(uint32_t));
    for (size_t i = 0; i < (size_t)w * (size_t)h; i++) {
        /* decoded RGBA bytes -> the engine's 0xAARRGGBB word packing */
        uint32_t r = rgba[i * 4 + 0], g = rgba[i * 4 + 1],
                 b = rgba[i * 4 + 2];
        app.argb[i] = 0xFF000000u | (r << 16) | (g << 8) | b;
    }

    ANativeWindow_Buffer buf;
    ANativeWindow *dummy = (ANativeWindow *)0x1;
    if (ANativeWindow_lock(dummy, &buf, NULL) != 0) {
        fprintf(stderr, "fake surface lock failed\n"); return 6;
    }
    and_blit(&app, &buf);
    ANativeWindow_unlockAndPost(dummy);

    /* 5. dump the destination for the Python verifier */
    FILE *fo = fopen(dstPath, "wb");
    if (!fo) { fprintf(stderr, "cannot write dst\n"); return 7; }
    fwrite(g_surf_bits, 4u, (size_t)g_surf_h * (size_t)g_surf_stride, fo);
    fclose(fo);
    printf("destination dumped: %dx%d stride %d (crop %+0.1f,%+0.1f)\n",
           g_surf_w, g_surf_h, g_surf_stride, cropX, cropY);

    free(g_surf_bits);
    free(app.argb);
    free(rgba);
    free(png);
    return 0;
}
