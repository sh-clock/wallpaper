#version 450
/* hw_mesh_vk.vert — the v9.9 hardware mesh VERTEX shader (Vulkan flavor).
 *
 * Logic IDENTICAL to HW_VS_MESH_GLES in sh1_hw_shaders.h (the v9.8
 * desktop-PoC shader, restructured for instancing). Vulkan differences
 * only: y-down NDC origin (no flip needed — the engine's screen coords
 * are already top-left/y-down like Vulkan's framebuffer space).
 *
 * Compiled to SPIR-V offline by glslang (the same toolchain family
 * Swanstation's pre-baked embedded_spirv pipeline uses).
 *
 * attributes: 0-4 vertex rate, 5-9 instance rate (see sh1_hw_vk.cpp)   */
layout(location = 0) in vec2 a_pos;    /* GTE-SNAPPED screen x,y (top-left) */
layout(location = 1) in vec2 a_uv;     /* normalized glTF UVs               */
layout(location = 2) in vec3 a_col;    /* per-vertex fog/light color        */
layout(location = 3) in vec3 a_ocol;   /* per-vertex additive veil color    */
layout(location = 4) in float a_z;     /* view depth (m)                    */
layout(location = 5) in uvec4 a_ddaA;      /* U0,V0,Dudx,Dudy (u32 Q24)     */
layout(location = 6) in uvec2 a_ddaB;      /* Dvdx,Dvdy (u32 Q24)           */
layout(location = 7) in ivec4 a_ddaAnchor; /* X0,Y0 (Q12), KX,KY (float bits)  */
layout(location = 8) in ivec2 a_texSize;   /* tw,th                        */
layout(location = 9) in ivec4 a_flags;     /* mode,useDDA,persp,layer      */
layout(push_constant) uniform PC {
  vec2 u_fbSize;
} pc;
#define u_fbSize pc.u_fbSize
layout(location = 0) out vec2 v_uv;
layout(location = 1) out vec3 v_colw;
layout(location = 2) out vec3 v_ocolw;
layout(location = 3) out float v_zw;
layout(location = 4) out float v_w;
layout(location = 5) flat out uvec4 v_ddaA;
layout(location = 6) flat out uvec2 v_ddaB;
layout(location = 7) flat out ivec4 v_ddaAnchor;
layout(location = 8) flat out ivec2 v_texSize;
layout(location = 9) flat out ivec4 v_flags;
void main() {
  /* screen(top-left,y-down) -> Vulkan NDC (y-down, top = -1) — direct  */
  vec2 ndc = vec2(a_pos.x / u_fbSize.x * 2.0 - 1.0,
                  a_pos.y / u_fbSize.y * 2.0 - 1.0);
  float w = (a_flags.z != 0) ? a_z : 1.0;   /* persp vs PS1 affine clip */
  gl_Position = vec4(ndc, 0.0, w);
  v_uv = a_uv;              /* plain: affine when w==1, persp-correct else */
  v_colw = a_col * w;       /* affine recovery pair (see GLES header)      */
  v_ocolw = a_ocol * w;
  v_zw = a_z * w;
  v_w = w;
  v_ddaA = a_ddaA; v_ddaB = a_ddaB; v_ddaAnchor = a_ddaAnchor;
  v_texSize = a_texSize; v_flags = a_flags;
}
