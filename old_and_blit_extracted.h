/* AUTO-EXTRACTED VERBATIM (LEGACY v10.0 math) from fog_poc.c — DO NOT EDIT.
 * lines 9812-9894 — contrast reference only.
 */

static void and_clear_8888(ANativeWindow_Buffer *buf) {
    uint8_t *base = (uint8_t *)buf->bits;
    for (int y = 0; y < buf->height; y++)
        memset(base + (size_t)y * (size_t)buf->stride * 4u, 0,
               (size_t)buf->width * 4u);
}

static void and_clear_565(ANativeWindow_Buffer *buf) {
    uint16_t *base = (uint16_t *)buf->bits;
    for (int y = 0; y < buf->height; y++)
        memset(base + (size_t)y * (size_t)buf->stride, 0,
               (size_t)buf->width * 2u);
}

static void and_blit(LiveApp *A, ANativeWindow_Buffer *buf) {
    const int fw = A->fb.w, fh = A->fb.h;
    const int ww = buf->width, wh = buf->height;
    int dstW, dstH, offX, offY, sx0 = 0, srcW = fw;
    const int cover = (wh > ww);   /* portrait wallpaper: full-bleed crop */
    if (cover) {
        /* cover: scale to FILL the screen, center-crop the source columns.
         * A letterboxed 320x224 band would waste ~70% of a phone screen. */
        int sw = (int)((long long)fh * ww / wh);
        if (sw > fw) sw = fw;
        sx0 = (fw - sw) >> 1;
        srcW = sw;
        dstW = ww; dstH = wh;
        offX = 0; offY = 0;
    } else {
        /* landscape: aspect-fit + letterbox (the classic PoC look) */
        dstW = ww;
        dstH = (int)((long long)fh * ww / fw);
        if (dstH > wh) { dstH = wh; dstW = (int)((long long)fw * wh / fh); }
        offX = (ww - dstW) >> 1;
        offY = (wh - dstH) >> 1;
    }
    const uint32_t *src = A->argb;
    if (buf->format == WINDOW_FORMAT_RGB_565) {
        if (!cover) and_clear_565(buf);
        uint16_t *base = (uint16_t *)buf->bits;
        const int xs = (srcW << 16) / dstW, ys = (fh << 16) / dstH;
        int ypos = 0;
        for (int y = 0; y < dstH; y++) {
            const uint32_t *srow = src + (size_t)(ypos >> 16) * fw;
            uint16_t *drow = base + (size_t)(y + offY) * (size_t)buf->stride
                           + (size_t)offX;
            int xpos = sx0 << 16;
            for (int x = 0; x < dstW; x++) {
                uint32_t p = srow[xpos >> 16];
                int r = (int)(p >> 16) & 0xF8, g = (int)(p >> 8) & 0xFC, b = (int)p & 0xF8;
                drow[x] = (uint16_t)((r << 8) | (g << 3) | (b >> 3));
                xpos += xs;
            }
            ypos += ys;
        }
    } else {
        /* WINDOW_FORMAT_RGBA_8888 **and** WINDOW_FORMAT_RGBX_8888 —
         * the system wallpaper surface is typically RGBX_8888 (format 2);
         * ignoring it was the black-screen bug. Same byte layout for both. */
        if (!cover) and_clear_8888(buf);
        uint8_t *base = (uint8_t *)buf->bits;
        const int xs = (srcW << 16) / dstW, ys = (fh << 16) / dstH;
        int ypos = 0;
        for (int y = 0; y < dstH; y++) {
            const uint32_t *srow = src + (size_t)(ypos >> 16) * fw;
            uint8_t *drow = base + (size_t)(y + offY) * (size_t)buf->stride * 4u
                          + (size_t)offX * 4u;
            int xpos = sx0 << 16;
            for (int x = 0; x < dstW; x++) {
                uint32_t p = srow[xpos >> 16];
                uint8_t *d = drow + (size_t)x * 4u;
                /* engine packs 0xAARRGGBB -> memory bytes B,G,R,A;      */
                /* RGBA_8888/RGBX_8888 want memory bytes R,G,B,A(X:=FF)  */
                d[0] = (uint8_t)(p >> 16);
                d[1] = (uint8_t)(p >> 8);
                d[2] = (uint8_t)p;
                d[3] = 0xFF;
                xpos += xs;
            }
            ypos += ys;
        }
    }
}
