/* sh1_hw_types.h — v9.9 hardware backend shared types (plain C, visible to
 * BOTH translation units: sh1_hw_render.c (included into the engine TU)
 * and sh1_hw_vk.cpp (the standalone Vulkan renderer)). No engine types
 * leak across this boundary.
 */
#ifndef SH1_HW_TYPES_H
#define SH1_HW_TYPES_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* vertex-rate data: 3 vertices per triangle (44 bytes — see the shaders) */
typedef struct {
    float sx, sy;          /* GTE-SNAPPED screen coords (top-left origin)  */
    float u, v;            /* normalized glTF UVs (the affine/persp path)  */
    float vr, vg, vb;      /* per-vertex fog/light color (color units)     */
    float orr, og, ob;     /* per-vertex additive veil color               */
    float z;               /* view depth (m) — the flake-occlusion source  */
} HwVert;

/* instance-rate data: one per triangle (64 bytes — see the shaders) */
typedef struct {
    uint32_t ddaU0, ddaV0, ddaDudx, ddaDudy;   /* a_ddaA (u32 Q24)         */
    uint32_t ddaDvdx, ddaDvdy;                 /* a_ddaB (u32 Q24)         */
    int32_t  ddaX0, ddaY0, ddaKX, ddaKY;       /* a_ddaAnchor: X0,Y0 (Q12);
                                                  KX,KY = FLOAT BITS of
                                                  4096/scale (v45 H-1: the
                                                  shaders widen + multiply
                                                  in float — the engine's
                                                  single-truncation DDA) */
    int32_t  texW, texH;                       /* a_texSize                */
    int32_t  flags;                            /* a_flags.x: bit0 dither, bit1 quantize */
    int32_t  useDDA;                           /* a_flags.y                */
    int32_t  persp;                            /* a_flags.z                */
    int32_t  layer;                            /* a_flags.w: texture layer */
} HwInst;

/* plain mirror of the engine's TexInfo (offsets into the texture blob) */
typedef struct {
    int32_t off, w, h;
} HwTex;

/* the frame batch (defined in sh1_hw_render.c; the Vulkan TU reads it).
 * The per-triangle instance record is REPLICATED 3x (one copy per
 * vertex — see hw_batch_reserve) so a plain triangle-soup draw works;
 * Vulkan uses the IDENTICAL layout (two vertex-rate bindings).          */
typedef struct {
    HwVert *verts;  size_t nVert, capVert;      /* 3 per triangle          */
    HwInst *insts;  size_t nInst,  capInst;     /* 3 per triangle (replicated) */
    HwVert *zverts; size_t nZVert, capZVert;    /* 3 per OPAQUE triangle    */
    HwInst *zinsts; size_t nZInst,  capZInst;   /* 3 per triangle (replicated) */
} HwBatch;

extern HwBatch g_hwBatch;

/* the Vulkan backend entry points (sh1_hw_vk.cpp) */
int  sh1_vk_hw_init(int w, int h, const unsigned char *texBlob,
                    const HwTex *tex, int texCount);
int  sh1_vk_hw_render(unsigned char *rgbOut, float *zOut, int w, int h,
                      int cr, int cg, int cb);
void sh1_vk_hw_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif /* SH1_HW_TYPES_H */
