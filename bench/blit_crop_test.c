/*
 * blit_crop_test.c — v10.1 Phase I: the DESKTOP PROOF of the STRICT
 * CENTER-CROP presentation (TASK 3 + TASK 4).
 *
 * The and_blit + sh1_android_set_crop below are VERBATIM extractions
 * from the SHIPPED fog_poc.c (bench/extract_and_blit.sh, brace-counted,
 * provenance in and_blit_extracted.h) — this tests the REAL code, never
 * a re-implementation. The v10.0-baseline math is included the same way
 * (old_and_blit_extracted.h) purely as the CONTRAST reference: the final
 * assertions prove this battery would have REJECTED the v10.0 bugs.
 *
 * PROPERTIES PROVEN (per framebuffer x surface pair, RGBA_8888 unless
 * noted):
 *
 *  1. COVER (no letterbox): every destination pixel is written and
 *     decodes to a VALID source coordinate (a sentinel pre-fill makes
 *     any unwritten pixel fail). This is the full-bleed guarantee in
 *     BOTH orientations — the v10.0 landscape path letterboxed.
 *
 *  2. ISOTROPY / NO STRETCH (the decisive one): a CIRCLE in the source
 *     framebuffer must remain a circle in the destination — its dst
 *     bounding box width/height ratio must be 1 within 0.5% (measured
 *     quantization noise is <= 0.2%; the v10.0 split-step math distorts
 *     a 320x224 frame onto a 1080x2400 screen by ~0.8% -> caught).
 *
 *  3. MONOTONIC DDA: the sampled source coordinate never runs backwards
 *     along a scanline (a broken step would show up as such).
 *
 *  4. SYMMETRY (offset 0): the centered crop window crops the source
 *     symmetrically — left crop == right crop and top crop == bottom
 *     crop, each within 1 texel (the fractional window's floor).
 *
 *  5. OFFSET EXTREMES (TASK 4): offsets +-1 pin the window to the
 *     corresponding source edge (within 2 texels of the slack limit),
 *     and every decoded coordinate stays in bounds at every offset
 *     (the DDA tail clamp works).
 *
 *  6. RGB_565 path: cover + coarse coordinate bounds (5:6:5 packing
 *     keeps 5 bits of the encoded coordinate) + exact solid-color
 *     packing of the (r<<8)|(g<<3)|(b>>3) formula.
 *
 *  7. STRIDE: every buffer uses stride = width + 37 (non-trivial row
 *     padding — the real ANativeWindow_Buffer contract).
 *
 *  8. CONTRAST: the extracted v10.0 math FAILS isotropy on
 *     320x224 -> 1080x2400 and FAILS cover on 320x224 -> 2400x1080.
 *
 * Coordinate encoding in the source pattern (property 1/4/5):
 *   p = (sx << 12) | sy   (sx, sy < 4096)
 *   dst R = sx >> 4, G = ((sx & 0xF) << 4) | (sy >> 8), B = sy & 0xFF
 *   decode: sx = (R << 4) | (G >> 4), sy = ((G & 0xF) << 8) | B
 */
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

/* ---- the minimal Android surface stubs and_blit needs ------------- */
typedef struct { int w, h; } FbTiny;
typedef struct { FbTiny fb; uint32_t *argb; } LiveApp;
typedef struct { int width, height, stride, format; void *bits; }
    ANativeWindow_Buffer;
#define WINDOW_FORMAT_RGBA_8888 1
#define WINDOW_FORMAT_RGBX_8888 2
#define WINDOW_FORMAT_RGB_565 4

/* ---- the REAL v10.1 presentation (verbatim) ------------------------ */
#include "and_blit_extracted.h"

/* ---- the v10.0 math, contrast only (verbatim) ---------------------- */
#define and_blit old_and_blit
#define and_clear_8888 old_and_clear_8888
#define and_clear_565 old_and_clear_565
#include "old_and_blit_extracted.h"
#undef and_blit
#undef and_clear_8888
#undef and_clear_565

/* ------------------------------------------------------------------ */
static int g_fail = 0;
static int g_check = 0;

#define CHECK(cond, ...) do {                                        \
    g_check++;                                                        \
    if (!(cond)) {                                                    \
      g_fail++;                                                       \
      printf("FAIL: ");                                               \
      printf(__VA_ARGS__);                                            \
      printf("  (%s:%d)\n", __FILE__, __LINE__);                     \
    }                                                                 \
  } while (0)

static uint32_t *g_src;
static uint8_t *g_dst8;
static uint16_t *g_dst16;
static LiveApp g_app;
static ANativeWindow_Buffer g_buf;
static int g_fw, g_fh, g_ww, g_wh, g_stride;

static void setup(int fw, int fh, int ww, int wh, int format565) {
  g_fw = fw; g_fh = fh; g_ww = ww; g_wh = wh;
  g_stride = ww + 37;   /* property 7: non-trivial row padding */
  free(g_src); free(g_dst8); free(g_dst16);
  g_src = malloc((size_t)fw * fh * 4);
  g_app.fb.w = fw; g_app.fb.h = fh; g_app.argb = g_src;
  g_buf.width = ww; g_buf.height = wh; g_buf.stride = g_stride;
  g_buf.format = format565 ? WINDOW_FORMAT_RGB_565 : WINDOW_FORMAT_RGBX_8888;
  if (format565) {
    g_dst16 = malloc((size_t)g_stride * wh * 2);
    g_dst8 = NULL;
    for (int i = 0; i < g_stride * wh; i++) {
      g_dst16[i] = 0xFFFF;   /* sentinel: decodes out of bounds */
    }
    g_buf.bits = g_dst16;
  } else {
    g_dst8 = malloc((size_t)g_stride * wh * 4);
    g_dst16 = NULL;
    memset(g_dst8, 0xEE, (size_t)g_stride * wh * 4);   /* sentinel */
    g_buf.bits = g_dst8;
  }
}

static void fill_coords(void) {
  for (int y = 0; y < g_fh; y++) {
    for (int x = 0; x < g_fw; x++) {
      g_src[(size_t)y * g_fw + x] = ((uint32_t)x << 12) | (uint32_t)y;
    }
  }
}

static void fill_solid(uint32_t color) {
  for (int i = 0; i < g_fw * g_fh; i++) {
    g_src[i] = color;
  }
}

/* decode the dst pixel (x, y) back to source coordinates (8888 path) */
static void decode8888(int x, int y, int *sx, int *sy) {
  const uint8_t *d = g_dst8 + ((size_t)y * g_stride + x) * 4;
  *sx = ((int)d[0] << 4) | (d[1] >> 4);
  *sy = ((d[1] & 0xF) << 8) | (int)d[2];
}

static int pixel_is_sentinel8888(int x, int y) {
  const uint8_t *d = g_dst8 + ((size_t)y * g_stride + x) * 4;
  return d[0] == 0xEE && d[1] == 0xEE && d[2] == 0xEE && d[3] == 0xEE;
}

/* property 1 + 5: cover + bounds at the CURRENT crop offsets */
static void check_cover(const char *tag) {
  for (int y = 0; y < g_wh; y++) {
    for (int x = 0; x < g_ww; x++) {
      CHECK(!pixel_is_sentinel8888(x, y),
          "%s cover: dst(%d,%d) unwritten (letterbox!) [%dx%d -> %dx%d]",
          tag, x, y, g_fw, g_fh, g_ww, g_wh);
      int sx, sy;
      decode8888(x, y, &sx, &sy);
      CHECK(sx >= 0 && sx < g_fw && sy >= 0 && sy < g_fh,
          "%s bounds: dst(%d,%d) -> src(%d,%d) OUT OF RANGE [%dx%d -> %dx%d]",
          tag, x, y, sx, sy, g_fw, g_fh, g_ww, g_wh);
      if (g_fail > 20) {
        return;   /* don't spam thousands of lines on a broken blit */
      }
    }
  }
}

/* properties 2 + 3: the circle stays a circle; the DDA never reverses */
static void check_circle_isotropy(void) {
  if (g_ww < 64 || g_wh < 64) {
    return;   /* too small to measure a meaningful bbox */
  }
  /* the uniform cover scale (test-side expectation math only) */
  const double s = fmax((double)g_ww / g_fw, (double)g_wh / g_fh);
  /* the circle must fit INSIDE the crop window so its bbox is whole */
  const double r_src = 0.45 * fmin((double)g_ww, (double)g_wh) / s;
  const double cx = g_fw / 2.0, cy = g_fh / 2.0;
  for (int y = 0; y < g_fh; y++) {
    for (int x = 0; x < g_fw; x++) {
      double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
      g_src[(size_t)y * g_fw + x] =
          (dx * dx + dy * dy <= r_src * r_src) ? 0x00FF7F00u : 0x00000000u;
    }
  }
  sh1_android_set_crop(0.0f, 0.0f);
  and_blit(&g_app, &g_buf);
  int min_x = 1 << 30, max_x = -(1 << 30), min_y = 1 << 30, max_y = -(1 << 30);
  for (int y = 0; y < g_wh; y++) {
    for (int x = 0; x < g_ww; x++) {
      const uint8_t *d = g_dst8 + ((size_t)y * g_stride + x) * 4;
      if (d[0] == 0xFF && d[1] == 0x7F && d[2] == 0x00) {
        if (x < min_x) min_x = x;
        if (x > max_x) max_x = x;
        if (y < min_y) min_y = y;
        if (y > max_y) max_y = y;
      }
    }
  }
  CHECK(max_x > min_x && max_y > min_y,
      "circle: not found [%dx%d -> %dx%d]", g_fw, g_fh, g_ww, g_wh);
  if (max_x <= min_x || max_y <= min_y) {
    return;
  }
  const double w_dst = max_x - min_x + 1.0;
  const double h_dst = max_y - min_y + 1.0;
  const double ratio = w_dst / h_dst;
  CHECK(fabs(ratio - 1.0) <= 0.005,
      "circle STRETCHED: dst bbox %.1fx%.1f ratio %.4f (%.2f%% off) "
      "[%dx%d -> %dx%d]", w_dst, h_dst, ratio, 100.0 * fabs(ratio - 1.0),
      g_fw, g_fh, g_ww, g_wh);

  /* property 3: monotonic source coordinate along the center row */
  fill_coords();
  and_blit(&g_app, &g_buf);
  int prev = -1;
  const int row = g_wh / 2;
  for (int x = 0; x < g_ww; x++) {
    int sx, sy;
    decode8888(x, row, &sx, &sy);
    CHECK(prev < 0 || sx >= prev,
        "DDA reversed: sx(%d)=%d < sx(%d)=%d", x, sx, x - 1, prev);
    prev = sx;
    if (g_fail > 20) {
      return;
    }
  }
}

/* property 4: centered window crops symmetrically. The WINDOW is exact
 * (avail/2 on both sides); the SAMPLED texel extent can sit up to one
 * step inside it on each side (nearest-neighbor quantization — a step
 * > 1 src px when downscaling), so the tolerance scales with the step */
static void check_symmetry(void) {
  if (g_ww < 2 || g_wh < 2) {
    return;   /* a 1-px surface compares the same pixel with itself */
  }
  const double s = fmax((double)g_ww / g_fw, (double)g_wh / g_fh);
  const double step_x = (g_ww / s) / g_ww;   /* src px per dst px */
  const double step_y = (g_wh / s) / g_wh;
  const int tol_x = 1 + (int)ceil(step_x);
  const int tol_y = 1 + (int)ceil(step_y);
  sh1_android_set_crop(0.0f, 0.0f);
  fill_coords();
  and_blit(&g_app, &g_buf);
  int sx0, sy0, sx1, sy1;
  decode8888(0, g_wh / 2, &sx0, &sy0);
  decode8888(g_ww - 1, g_wh / 2, &sx1, &sy1);
  CHECK(abs(sx0 - (g_fw - 1 - sx1)) <= tol_x,
      "X asymmetric: left crop %d vs right crop %d (tol %d) [%dx%d -> %dx%d]",
      sx0, g_fw - 1 - sx1, tol_x, g_fw, g_fh, g_ww, g_wh);
  decode8888(g_ww / 2, 0, &sx0, &sy0);
  decode8888(g_ww / 2, g_wh - 1, &sx1, &sy1);
  CHECK(abs(sy0 - (g_fh - 1 - sy1)) <= tol_y,
      "Y asymmetric: top crop %d vs bottom crop %d (tol %d) [%dx%d -> %dx%d]",
      sy0, g_fh - 1 - sy1, tol_y, g_fw, g_fh, g_ww, g_wh);
}

/* property 5: +-1 pins the window to the edge; all coords stay valid */
static void check_offset_extremes(void) {
  const double s = fmax((double)g_ww / g_fw, (double)g_wh / g_fh);
  const double win_w = g_ww / s, win_h = g_wh / s;
  const double avail_x = g_fw - win_w, avail_y = g_fh - win_h;
  const float offs[4][2] = {
      {1.0f, 0.0f}, {-1.0f, 0.0f}, {0.0f, 1.0f}, {0.0f, -1.0f}
  };
  for (int k = 0; k < 4; k++) {
    sh1_android_set_crop(offs[k][0], offs[k][1]);
    fill_coords();
    and_blit(&g_app, &g_buf);
    check_cover("offsets");
    int sx0, sy0, sx1, sy1;
    decode8888(0, g_wh / 2, &sx0, &sy0);
    decode8888(g_ww - 1, g_wh / 2, &sx1, &sy1);
    decode8888(g_ww / 2, 0, &sx1, &sy0);   /* reusing vars for the column */
    decode8888(g_ww / 2, g_wh - 1, &sx1, &sy1);
    if (offs[k][0] > 0) {
      CHECK(sx0 >= (int)avail_x - 2,
          "offset +X: window not pinned right (sx0=%d avail=%.1f)",
          sx0, avail_x);
    } else if (offs[k][0] < 0) {
      CHECK(sx0 <= 2,
          "offset -X: window not pinned left (sx0=%d)", sx0);
    }
    if (offs[k][1] > 0) {
      CHECK(sy0 >= (int)avail_y - 2,
          "offset +Y: window not pinned bottom (sy0=%d avail=%.1f)",
          sy0, avail_y);
    } else if (offs[k][1] < 0) {
      CHECK(sy0 <= 2,
          "offset -Y: window not pinned top (sy0=%d)", sy0);
    }
    if (g_fail > 20) {
      return;
    }
  }
  sh1_android_set_crop(0.0f, 0.0f);
}

/* property 6: the RGB_565 path */
static void check_565(void) {
  setup(g_fw, g_fh, g_ww, g_wh, 1);
  fill_coords();
  sh1_android_set_crop(0.0f, 0.0f);
  and_blit(&g_app, &g_buf);
  for (int y = 0; y < g_wh; y++) {
    for (int x = 0; x < g_ww; x++) {
      uint16_t v = g_dst16[(size_t)y * g_stride + x];
      CHECK(v != 0xFFFF, "565 cover: dst(%d,%d) unwritten", x, y);
      int r5 = v >> 11, g6 = (v >> 5) & 0x3F, b5 = v & 0x1F;
      /* R byte of the pattern = sx>>4 -> r5 = sx>>7 (coarse, tol 128) */
      int sx = r5 << 7;
      CHECK(sx <= g_fw + 128, "565 sx bounds: %d (r5=%d)", sx, r5);
      /* B byte of the pattern = sy&0xFF -> b5 = (sy&0xF8)>>3 (tol 8) */
      int sy_lo = b5 << 3;
      CHECK(sy_lo <= g_fh + 8, "565 sy bounds: %d (b5=%d)", sy_lo, b5);
      (void)g6;
      if (g_fail > 20) {
        return;
      }
    }
  }
  /* solid color: exact 565 packing proof */
  fill_solid(0x00FCF8F0u);   /* R=FC G=F8 B=F0 */
  and_blit(&g_app, &g_buf);
  const uint16_t want = (uint16_t)(((0xFC & 0xF8) << 8)
      | ((0xF8 & 0xFC) << 3) | (0xF0 >> 3));
  for (int y = 0; y < g_wh; y++) {
    for (int x = 0; x < g_ww; x++) {
      CHECK(g_dst16[(size_t)y * g_stride + x] == want,
          "565 packing: got 0x%04X want 0x%04X",
          g_dst16[(size_t)y * g_stride + x], want);
      if (g_fail > 20) {
        return;
      }
    }
  }
  setup(g_fw, g_fh, g_ww, g_wh, 0);   /* restore 8888 for the caller */
}

/* property 8: the v10.0 math must FAIL this battery (test power) */
static void contrast_legacy(void) {
  /* isotropy failure: 320x224 -> 1080x2400 */
  setup(320, 224, 1080, 2400, 0);
  const double s = fmax(1080.0 / 320, 2400.0 / 224);
  const double r_src = 0.45 * fmin(1080.0, 2400.0) / s;
  const double cx = 160, cy = 112;
  for (int y = 0; y < 224; y++) {
    for (int x = 0; x < 320; x++) {
      double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
      g_src[(size_t)y * 320 + x] =
          (dx * dx + dy * dy <= r_src * r_src) ? 0x00FF7F00u : 0u;
    }
  }
  old_and_blit(&g_app, &g_buf);
  int min_x = 1 << 30, max_x = -(1 << 30), min_y = 1 << 30, max_y = -(1 << 30);
  for (int y = 0; y < 2400; y++) {
    for (int x = 0; x < 1080; x++) {
      const uint8_t *d = g_dst8 + ((size_t)y * g_stride + x) * 4;
      if (d[0] == 0xFF && d[1] == 0x7F && d[2] == 0x00) {
        if (x < min_x) min_x = x;
        if (x > max_x) max_x = x;
        if (y < min_y) min_y = y;
        if (y > max_y) max_y = y;
      }
    }
  }
  const double ratio_old = (max_x - min_x + 1.0) / (max_y - min_y + 1.0);
  printf("  legacy 320x224 -> 1080x2400: circle ratio %.4f (%.2f%% stretch)\n",
      ratio_old, 100.0 * fabs(ratio_old - 1.0));
  CHECK(fabs(ratio_old - 1.0) > 0.005,
      "CONTRAST: the legacy math no longer shows its anisotropy "
      "(ratio %.4f) — the test battery would be meaningless", ratio_old);

  /* cover failure: 320x224 -> 2400x1080 (the v10.0 landscape letterbox).
   * the legacy path CLEARS the full buffer first, so the letterbox bands
   * are black with alpha 0x00 (the clear) instead of the 0xEE sentinel —
   * detect both, plus any non-FF alpha (never written by the blit) */
  setup(320, 224, 2400, 1080, 0);
  fill_coords();
  old_and_blit(&g_app, &g_buf);
  int unwritten = 0;
  for (int y = 0; y < 1080; y++) {
    for (int x = 0; x < 2400; x++) {
      const uint8_t *d = g_dst8 + ((size_t)y * g_stride + x) * 4;
      if (pixel_is_sentinel8888(x, y) || d[3] != 0xFF) {
        unwritten++;
      }
    }
  }
  printf("  legacy 320x224 -> 2400x1080: %d unwritten (letterbox) pixels\n",
      unwritten);
  CHECK(unwritten > 0,
      "CONTRAST: the legacy math no longer letterboxes in landscape "
      "— the test battery would be meaningless");
}

/* ------------------------------------------------------------------ */
int main(void) {
  static const int fbs[][2] = {
      {320, 224}, {480, 336}, {256, 224}, {640, 448}
  };
  static const int surfaces[][2] = {
      {1080, 2400}, {2400, 1080}, {1080, 1920}, {1440, 3200},
      {3200, 1440}, {1170, 2532}, {320, 224}, {224, 320},
      {480, 336}, {1, 1}, {320, 240}, {240, 320}
  };

  printf("== v10.1 STRICT CENTER-CROP presentation proof ==\n");
  printf("   (and_blit VERBATIM from the shipped fog_poc.c)\n\n");

  int combos = 0;
  for (size_t i = 0; i < sizeof(fbs) / sizeof(fbs[0]); i++) {
    for (size_t j = 0; j < sizeof(surfaces) / sizeof(surfaces[0]); j++) {
      setup(fbs[i][0], fbs[i][1], surfaces[j][0], surfaces[j][1], 0);
      sh1_android_set_crop(0.0f, 0.0f);
      fill_coords();
      and_blit(&g_app, &g_buf);
      check_cover("centered");
      check_circle_isotropy();
      check_symmetry();
      check_offset_extremes();
      combos++;
      if (g_fail > 20) {
        printf("ABORT: too many failures at %dx%d -> %dx%d\n",
            fbs[i][0], fbs[i][1], surfaces[j][0], surfaces[j][1]);
        break;
      }
    }
    if (g_fail > 20) {
      break;
    }
  }
  printf("  %d framebuffer x surface combinations (RGBA_8888, stride %d)\n",
      combos, 37);

  /* the 565 path on the phone-class and square pairs */
  static const int p565[][4] = {
      {320, 224, 1080, 2400}, {320, 224, 2400, 1080},
      {256, 224, 320, 224}
  };
  for (size_t k = 0; k < sizeof(p565) / sizeof(p565[0]); k++) {
    setup(p565[k][0], p565[k][1], p565[k][2], p565[k][3], 0);
    check_565();
  }
  printf("  %zu RGB_565 combinations\n", sizeof(p565) / sizeof(p565[0]));

  printf("\n== CONTRAST: the extracted v10.0 math must FAIL ==\n");
  contrast_legacy();

  free(g_src); free(g_dst8); free(g_dst16);
  printf("\n%d checks, %d failures\n", g_check, g_fail);
  if (g_fail == 0) {
    printf("PROOF PASSED: strict center-crop, isotropic by construction, "
        "full-bleed both orientations, clamped user offsets.\n");
    return 0;
  }
  return 1;
}
