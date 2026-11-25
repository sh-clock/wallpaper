#version 450
/* hw_mesh_vk.frag — the v9.9 hardware mesh FRAGMENT shader (Vulkan flavor).
 *
 * Logic blocks IDENTICAL to HW_FS_MESH_GLES in sh1_hw_shaders.h:
 *   - the SWANSTATION-VERBATIM s_dither_values + ApplyDithering block
 *     (gpu_hw_shadergen.cpp's fragment emission, character-for-character);
 *   - the DuckStation UV DDA block (mul_u32 / shr_s64_12 / add64 + the
 *     Q24 evaluation) verbatim from the v9.8 desktop PoC.
 * Vulkan differences only: gl_FragCoord is already top-left (no flip),
 * push constants instead of uniforms, explicit descriptor binding.
 *
 * MRT: location 0 = RGBA8 color, location 1 = R32F flake-occlusion z.  */
#define RESOLUTION_SCALE 1u
/* ShaderGen::WriteHeader's GLSL macro aliases (verbatim set, the subset
 * the shader references) — these make the VERBATIM shader blocks below
 * (written in shadergen's api-neutral type style) valid GLSL */
#define float2 vec2
#define float3 vec3
#define float4 vec4
#define int2 ivec2
#define int3 ivec3
#define int4 ivec4
#define uint2 uvec2
#define uint3 uvec3
#define uint4 uvec4
#define float2x2 mat2
#define float3x3 mat3
#define float4x4 mat4
#define mul(x, y) ((x) * (y))
#define nointerpolation flat
#define frac fract
#define lerp mix
#define CONSTANT const
#define GLOBAL
#define FOR_UNROLL for
#define FOR_LOOP for
#define IF_BRANCH if
#define IF_FLATTEN if
layout(set = 0, binding = 0) uniform sampler2DArray samp0;
layout(push_constant) uniform PC {
  vec2 u_fbSize;
  uint u_scaled_dithering;
  uint u_true_color;
} pc;
#define u_fbSize pc.u_fbSize
#define u_scaled_dithering pc.u_scaled_dithering
#define u_true_color pc.u_true_color
layout(location = 0) in vec2 v_uv;
layout(location = 1) in vec3 v_colw;
layout(location = 2) in vec3 v_ocolw;
layout(location = 3) in float v_zw;
layout(location = 4) in float v_w;
layout(location = 5) flat in uvec4 v_ddaA;
layout(location = 6) flat in uvec2 v_ddaB;
layout(location = 7) flat in ivec4 v_ddaAnchor;
layout(location = 8) flat in ivec2 v_texSize;
layout(location = 9) flat in ivec4 v_flags;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 fragZ;

/* ---- 32x32 -> 64 multiply (two's-complement bit patterns are identical
 * for signed/unsigned operands) ------------------------------------------ */
uvec2 mul_u32(uint a, uint b) {
  uint aH = a >> 16u, aL = a & 0xFFFFu;
  uint bH = b >> 16u, bL = b & 0xFFFFu;
  uint ll = aL * bL;
  uint mid = aL * bH + aH * bL;          /* may wrap mod 2^32             */
  uint midCarry = (mid < aL * bH) ? 1u : 0u;
  uint lo = ll + (mid << 16u);           /* may carry                      */
  uint loCarry = (lo < ll) ? 1u : 0u;
  uint hi = aH * bH + midCarry + (mid >> 16u) + loCarry;
  return uvec2(hi, lo);
}
/* (hi:lo) >> 12 ARITHMETIC (sign-extended) — the C int64 >> 12          */
uvec2 shr_s64_12(uvec2 v) {
  uint hi = v.x, lo = v.y;
  uint loOut = (hi << 20u) | (lo >> 12u);
  uint hiOut = hi >> 12u;
  if ((hi & 0x80000000u) != 0u) hiOut |= 0xFFFFE000u;
  return uvec2(hiOut, loOut);
}
uvec2 add64(uvec2 a, uvec2 b) {
  uint lo = a.y + b.y;
  uint carry = (lo < a.y) ? 1u : 0u;
  return uvec2(a.x + b.x + carry, lo);
}

/* ================== SWANSTATION VERBATIM (gpu_hw_shadergen.cpp) ========
 * s_dither_values + ApplyDithering, exactly as generated (CONSTANT and the
 * uint2/uint3 types resolve through the WriteHeader macros above).       */
CONSTANT int[16] s_dither_values = int[16]( -4, +0, -3, +1, +2, -2, +3, -1, -3, +1, -4, +0, +3, -1, +2, -2 );

uint3 ApplyDithering(uint2 coord, uint3 icol)
{
  uint2 fc;
  if (u_scaled_dithering != 0u)
    fc = coord & uint2(3u, 3u);
  else
    fc = (coord / uint2(RESOLUTION_SCALE, RESOLUTION_SCALE)) & uint2(3u, 3u);
  int offset = s_dither_values[fc.y * 4u + fc.x];

  if (u_true_color != 0u)
    return uint3(clamp(int3(icol) + int3(offset, offset, offset), 0, 255));
  else
    return uint3(clamp((int3(icol) + int3(offset, offset, offset)) >> 3, 0, 31));
}
/* ================== END SWANSTATION VERBATIM ========================== */

/* rgb_to_15bit_expand: the engine's 5->8 replication applied after the
 * verbatim ApplyDithering — the full chain is fb_put's
 * expand5(quantize8(c)) == expand5(clamp((c+d)>>3, 0, 31)) (proven equal:
 * clamp-then-shift and shift-then-clamp agree for all c,d)             */
uint expand5(uint v) { return (v << 3u) | (v >> 2u); }

void main() {
  /* the pixel's INTEGER top-left coordinate — raster_tri's DDA evaluation
   * position (DuckStation's integer current_x/current_y, no +0.5).
   * Vulkan's framebuffer origin IS top-left/y-down — direct readout.   */
  ivec2 pix = ivec2(gl_FragCoord.xy);

  /* ---- texture sampling ------------------------------------------------ */
  vec4 texel;
  if (v_flags.y != 0) {
    /* raster_tri's exact sequence (the DuckStation UV DDA). v45 audit
     * H-1: the anchor's z,w slots carry the FLOAT BITS of the
     * double factor 4096/scale — the multiply runs in float and
     * truncates ONCE, the engine's own single-truncation
     * arithmetic (the old int32 pre-truncation lost up to
     * 0.667 Q12/px from the anchor and drifted the sampled texel
     * by up to 0.078 engine px at fractional-scale modes):
     *   dxq = (int64)(x * ddaKX) - ddaX0  (x < 320*scale, so
     *   x*ddaKX < 320*4096 = 1,310,720: s32-safe at EVERY scale;
     *   bit-identical at integral scales, where 4096/scale is an
     *   exact power of two and the float product is exact)
     *   usum = (int64)U0 + (((int64)(s32)Dudx * dxq) >> 12)
     *                       + (((int64)(s32)Dudy * dyq) >> 12)
     *   texel = Truncate8(usum >> 24) % tw  (the u8 texcoord wrap)      */
    int dxq = int(float(pix.x) * uintBitsToFloat(uint(v_ddaAnchor.z)))
             - v_ddaAnchor.x;
    int dyq = int(float(pix.y) * uintBitsToFloat(uint(v_ddaAnchor.w)))
             - v_ddaAnchor.y;
    uvec2 t1 = shr_s64_12(mul_u32(v_ddaA.z, uint(dxq)));
    uvec2 t2 = shr_s64_12(mul_u32(v_ddaA.w, uint(dyq)));
    uvec2 t3 = shr_s64_12(mul_u32(v_ddaB.x, uint(dxq)));
    uvec2 t4 = shr_s64_12(mul_u32(v_ddaB.y, uint(dyq)));
    uvec2 usum = add64(add64(uvec2(0u, v_ddaA.x), t1), t2);
    uvec2 vsum = add64(add64(uvec2(0u, v_ddaA.y), t3), t4);
    uint iu = (usum.y >> 24u) & 0xFFu;   /* Truncate8: the u8 wrap at 256  */
    uint iv = (vsum.y >> 24u) & 0xFFu;
    int iuw = int(iu) % v_texSize.x; if (iuw < 0) iuw += v_texSize.x;
    int ivw = int(iv) % v_texSize.y; if (ivw < 0) ivw += v_texSize.y;
    texel = texelFetch(samp0, ivec3(iuw, ivw, v_flags.w), 0);
  } else {
    /* tex_sample fallback: floorf(u*tw) + modulo wrap — replicated with
     * texelFetch + manual wrap (the array's layers are padded, so the
     * sampler's own wrap can never be used)                              */
    int fu = int(floor(v_uv.x * float(v_texSize.x)));
    int fv = int(floor(v_uv.y * float(v_texSize.y)));
    fu %= v_texSize.x; if (fu < 0) fu += v_texSize.x;
    fv %= v_texSize.y; if (fv < 0) fv += v_texSize.y;
    texel = texelFetch(samp0, ivec3(fu, fv, v_flags.w), 0);
  }

  uint ta = uint(texel.a * 255.0 + 0.5);
  if (ta == 0u) discard;   /* PSX 0x0000 hole (DuckStation: texture_color==0 -> return) */

  /* the per-vertex colors: affine recovery (w==1 in PS1 mode -> the plain
   * varying); the CPU rounds with (int)(x + 0.5) — replicated exactly  */
  vec3 vcol = v_colw / v_w;   /* w10: DIVIDE — see the GLES header */
  vec3 vocol = v_ocolw / v_w; /* w10: same */
  int vr = int(vcol.r + 0.5);
  int vg = int(vcol.g + 0.5);
  int vb = int(vcol.b + 0.5);
  int orr = int(vocol.r + 0.5);
  int og = int(vocol.g + 0.5);
  int ob = int(vocol.b + 0.5);
  int tr = int(texel.r * 255.0 + 0.5);
  int tg = int(texel.g * 255.0 + 0.5);
  int tb = int(texel.b * 255.0 + 0.5);

  /* engine modulate + additive veil (raster_tri's SwanStation form:
   * ((texel5 * color8) >> 4) + veil — then fb_put quantizes)            */
  int r = (((tr >> 3) * vr) >> 4) + orr;
  int g = (((tg >> 3) * vg) >> 4) + og;
  int b = (((tb >> 3) * vb) >> 4) + ob;

  /* fb_put(quantize, dither): mode = dither | quantize<<1
   *   quantize && dither : expand5(ApplyDithering(c))      (15-bit + dith)
   *   quantize only      : expand5(clamp(c) >> 3)          (15-bit)
   *   neither            : clamp(c)                        (24-bit)
   * v10.2 AUDIT FIX (fb_put branch): the CPU's fb_put gates on QUANTIZE
   *   alone (`if (quantize)`) — dither is INERT when quantize == 0, so
   *   (q=0, d=1) renders the plain 24-bit clamp. The v9.9-v10.1 code
   *   branched on `v_flags.x != 0`, wrongly dithering that case (a real
   *   HW/SW divergence in --modern + dither-on). The outer branch now
   *   tests the quantize bit (bit 1), exactly like fb_put.             */
  if ((v_flags.x & 2) != 0) {
    if ((v_flags.x & 1) != 0) {
      uint3 d = ApplyDithering(uint2(pix), uint3(clamp(ivec3(r, g, b), 0, 255)));
      fragColor = vec4(float(expand5(d.x)) / 255.0,
                       float(expand5(d.y)) / 255.0,
                       float(expand5(d.z)) / 255.0, 1.0);
    } else {
      uint3 c5 = uint3(clamp(ivec3(r, g, b), 0, 255)) >> 3u;
      fragColor = vec4(float(expand5(c5.x)) / 255.0,
                       float(expand5(c5.y)) / 255.0,
                       float(expand5(c5.z)) / 255.0, 1.0);
    }
  } else {
    fragColor = vec4(float(clamp(r, 0, 255)) / 255.0,
                     float(clamp(g, 0, 255)) / 255.0,
                     float(clamp(b, 0, 255)) / 255.0, 1.0);
  }
  /* the flake-occlusion depth (affine z == raster_tri's barycentric z) */
  fragZ = vec4(v_zw / v_w, 0.0, 0.0, 1.0);  /* w10: same */
}
