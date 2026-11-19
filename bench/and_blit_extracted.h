/* AUTO-EXTRACTED VERBATIM from fog_poc.c — DO NOT EDIT.
 * crop state:   lines 9633-9643
 * and_blit:     lines 9830-9979
 * Regenerate: bench/extract_and_blit.sh
 */

static volatile float g_and_crop_x = 0.0f;
static volatile float g_and_crop_y = 0.0f;

void sh1_android_set_crop(float x, float y) {
    if (!(x >= -1.0f)) x = -1.0f;   /* also catches NaN */
    if (!(x <= 1.0f)) x = 1.0f;
    if (!(y >= -1.0f)) y = -1.0f;
    if (!(y <= 1.0f)) y = 1.0f;
    g_and_crop_x = x;
    g_and_crop_y = y;
}

static void and_blit(LiveApp *A, ANativeWindow_Buffer *buf) {
    /* v10.1 TASK 3 — STRICT CENTER-CROP (Aspect Fill), both orientations.
     *
     * The framebuffer (fw x fh — 320x224 or the user's --res choice) is
     * scaled by ONE uniform factor s = max(ww/fw, wh/fh): the image
     * meets or exceeds the surface in BOTH dimensions and the excess is
     * cropped symmetrically. ZERO non-uniform scaling — the DDAs step
     * the SOURCE coordinate by the SAME 16.16 reciprocal 1/s per
     * destination pixel, so the scaling is isotropic BY CONSTRUCTION.
     * This replaces the v9-era split whose independently truncated
     * xs/ys steps carried a measurable anisotropy (1080x2400 vs
     * 320x224: xs != ys -> a ~0.36% horizontal squeeze — a true
     * stretch) and whose landscape branch letterboxed (aspect-fit)
     * instead of cropping.
     *
     * v10.1 TASK 4 — the crop window is user-shiftable: g_and_crop_x/y
     * (normalized -1..1, 0 = symmetric center) slide the window along
     * the CROPPED axis; the axis that fills exactly has zero available
     * room, so its offset is physically inert (portrait phone: only X
     * pans; landscape screen: only Y pans). The window origin keeps
     * 16.16 sub-texel precision — the visible window itself is
     * fractional (e.g. 100.8 of 320 columns on 1080x2400), so no
     * rounding is forced into the uniform scale.
     */
    const int fw = A->fb.w, fh = A->fb.h;
    const int ww = buf->width, wh = buf->height;
    if (fw <= 0 || fh <= 0 || ww <= 0 || wh <= 0) {
        return;
    }

    /* the uniform 16.16 DDA step (SOURCE pixels per DESTINATION pixel
     * = the reciprocal 1/s of the cover scale) from the LIMITING axis:
     * width-limited  (ww*fh >= wh*fw, s = ww/fw): step = fw/ww — the
     *   width fills exactly (window_w = ww * fw/ww = fw), the height is
     *   cropped (landscape-ish windows)
     * height-limited (otherwise,          s = wh/fh): step = fh/wh — the
     *   height fills exactly (window_h = wh * fh/wh = fh), the width is
     *   cropped (portrait phones)
     * NOTE: the step is the RECIPROCAL of s — the DDA advances a SOURCE
     * coordinate once per DESTINATION pixel (the v10.1 desktop proof
     * bench/blit_crop_test.c catches any inversion here).               */
    long long inc16;
    long long availX16, availY16;
    if ((long long)ww * fh >= (long long)wh * fw) {
        inc16 = ((long long)fw << 16) / ww;      /* 1/s = fw/ww */
        availX16 = 0;                            /* width fills exactly */
        availY16 = ((long long)fh << 16)
                 - ((long long)wh * fw << 16) / ww;   /* cropped rows */
    } else {
        inc16 = ((long long)fh << 16) / wh;      /* 1/s = fh/wh */
        availY16 = 0;                            /* height fills exactly */
        availX16 = ((long long)fw << 16)
                 - ((long long)ww * fh << 16) / wh;   /* cropped cols */
    }
    if (inc16 < 1) {
        inc16 = 1;
    }
    if (availX16 < 0) {
        availX16 = 0;    /* truncation guards (cannot trigger with sane dims) */
    }
    if (availY16 < 0) {
        availY16 = 0;
    }

    /* the crop-window origin: symmetric center + the user's offset.
     * clamped so the window always stays inside the framebuffer.      */
    const double ox = (double)g_and_crop_x;   /* -1..1 */
    const double oy = (double)g_and_crop_y;
    long long cx16 = 0, cy16 = 0;
    if (availX16 > 0) {
        double c = (double)(availX16 >> 1) * (1.0 + ox);
        if (c < 0.0) {
            c = 0.0;
        }
        if (c > (double)availX16) {
            c = (double)availX16;
        }
        cx16 = (long long)c;
    }
    if (availY16 > 0) {
        double c = (double)(availY16 >> 1) * (1.0 + oy);
        if (c < 0.0) {
            c = 0.0;
        }
        if (c > (double)availY16) {
            c = (double)availY16;
        }
        cy16 = (long long)c;
    }

    /* full-bleed presentation: every window pixel is written (cover in
     * both orientations) — no letterbox clears remain.                    */
    const uint32_t *src = A->argb;
    if (buf->format == WINDOW_FORMAT_RGB_565) {
        uint16_t *base = (uint16_t *)buf->bits;
        long long ypos = cy16;
        for (int y = 0; y < wh; y++) {
            int sy = (int)(ypos >> 16);
            if (sy >= fh) {
                sy = fh - 1;    /* DDA tail clamp (at most 1 texel) */
            }
            const uint32_t *srow = src + (size_t)sy * fw;
            uint16_t *drow = base + (size_t)y * (size_t)buf->stride;
            long long xpos = cx16;
            for (int x = 0; x < ww; x++) {
                int sx = (int)(xpos >> 16);
                if (sx >= fw) {
                    sx = fw - 1;
                }
                uint32_t p = srow[sx];
                int r = (int)(p >> 16) & 0xF8, g = (int)(p >> 8) & 0xFC,
                    b = (int)p & 0xF8;
                drow[x] = (uint16_t)((r << 8) | (g << 3) | (b >> 3));
                xpos += inc16;
            }
            ypos += inc16;
        }
    } else {
        /* WINDOW_FORMAT_RGBA_8888 **and** WINDOW_FORMAT_RGBX_8888 —
         * the system wallpaper surface is typically RGBX_8888 (format 2);
         * ignoring it was the black-screen bug. Same byte layout for both. */
        uint8_t *base = (uint8_t *)buf->bits;
        long long ypos = cy16;
        for (int y = 0; y < wh; y++) {
            int sy = (int)(ypos >> 16);
            if (sy >= fh) {
                sy = fh - 1;
            }
            const uint32_t *srow = src + (size_t)sy * fw;
            uint8_t *drow = base + (size_t)y * (size_t)buf->stride * 4u;
            long long xpos = cx16;
            for (int x = 0; x < ww; x++) {
                int sx = (int)(xpos >> 16);
                if (sx >= fw) {
                    sx = fw - 1;
                }
                uint32_t p = srow[sx];
                uint8_t *d = drow + (size_t)x * 4u;
                /* engine packs 0xAARRGGBB -> memory bytes B,G,R,A;      */
                /* RGBA_8888/RGBX_8888 want memory bytes R,G,B,A(X:=FF)  */
                d[0] = (uint8_t)(p >> 16);
                d[1] = (uint8_t)(p >> 8);
                d[2] = (uint8_t)p;
                d[3] = 0xFF;
                xpos += inc16;
            }
            ypos += inc16;
        }
    }
}
