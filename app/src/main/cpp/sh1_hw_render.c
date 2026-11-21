/* sh1_hw_render.c — v9.9 hardware backends: the render_frame substitution.
 * ============================================================================
 * INCLUDED INTO the engine's translation unit (fog_poc.c) — see the
 * `#ifdef SH1_HW_RENDER` include gate at the end of fog_poc.c. It has
 * direct access to the engine's static types/functions, exactly like the
 * v9.8 desktop PoC harness (audit/v9_8/harness/gles) which proved this
 * architecture: PSNR 43.85..50.26 dB / 98.94..99.20% bit-exact / SSIM
 * 0.995+ vs the byte-frozen software rasterizer, with the GTE vertex
 * snap bit-identical by construction and the dither table verbatim.
 *
 * ARCHITECTURE (locked in the Phase G worklog entry, Task ID 12):
 *   The GPU replaces ONLY the MESH-QUAD RASTERIZATION of render_frame.
 *   The OT walk, transforms, GTE snap, culls and quad pairing are the
 *   engine's own code walked VERBATIM (copied line-for-line from
 *   render_frame with raster_tri() redirected to the GPU batch and the
 *   snow/rain sprite entries DEFERRED). The GPU renders the batch to an
 *   offscreen FBO (MRT: RGBA8 color + R32F flake-occlusion z — written
 *   only by OPAQUE non-hole fragments, exactly raster_tri's
 *   `if (pass == RP_OPAQUE) fb->z[pi] = z`), then READS BACK into
 *   fb->rgb/fb->z, and the engine's own CPU tail runs VERBATIM: the
 *   deferred sprite blits (z-tested against the readback z, in their OT
 *   order), the WorldGfx_2dEffectsDraw brightness overlay, g_dbgFrame.
 *   Presentation (live_draw_overlay -> fb_to_argb -> and_blit) is the
 *   engine's untouched code in every backend.
 *
 * KNOWN DIVERGENCE CLASSES (both MEASURED by the v9.9 desktop harness,
 * not assumed): (1) the GL/Vulkan fill-rule residue (the CPU rasterizer
 * double-covers shared edges; a fixed-function GPU cannot); (2) sprite
 * z-ordering: the CPU blits test the FINAL z (all opaque quads drawn)
 * instead of z-as-of-sprite-OT-position — diverges only where a
 * nearer-OT-bucket covering quad has interpolated z FARTHER than the
 * sprite's depth at that pixel, or where a later STP/BLEND quad would
 * have painted over a farther sprite. Bounded to sprite-covered pixels.
 *
 * The wobble (GTE snap) stays CPU-side in the shared transform —
 * bit-identical in ALL backends by construction (the user's explicit
 * bar for shipping the hardware path). The dither tables are the
 * SWANSTATION-VERBATIM GLSL/SPIR-V blocks (sh1_hw_shaders.h).
 * ============================================================================
 */
#ifndef SH1_HW_RENDER_C
#define SH1_HW_RENDER_C

#ifdef SH1_HW_RENDER

#include <EGL/egl.h>
#include <EGL/eglext.h>
#ifndef EGL_PLATFORM_SURFACELESS_MESA
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#endif
/* fog_poc.c defines the GLTF component-type constants with GL_ names
 * (lines ~1186: GL_FLOAT/GL_UNSIGNED_INT/GL_UNSIGNED_SHORT, values
 * 5126/5125/5123); the GLES headers define the IDENTICAL values — drop
 * ours first so the include is clean (the values match, no semantic
 * change: 0x1406==5126, 0x1405==5125, 0x1403==5123).                    */
#undef GL_FLOAT
#undef GL_UNSIGNED_INT
#undef GL_UNSIGNED_SHORT
#include <GLES3/gl32.h>
#include <stdlib.h>
#include <string.h>

#include "sh1_hw_types.h"
#include "sh1_hw_shaders.h"

#ifdef __ANDROID__
#include <android/log.h>
#define HWLOGI(...) __android_log_print(ANDROID_LOG_INFO,  "sh1-hw", __VA_ARGS__)
#define HWLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "sh1-hw", __VA_ARGS__)
#else
#define HWLOGI(...) printf("[sh1-hw] " __VA_ARGS__), printf("\n")
#define HWLOGE(...) fprintf(stderr, "[sh1-hw ERR] " __VA_ARGS__), fprintf(stderr, "\n")
#endif

/* ==========================================================================
 * the batch + the deferred sprite list
 * ========================================================================*/
HwBatch g_hwBatch;

typedef struct {
    int kind;              /* 1 snow, 2/4 rain streak/drip, 3 splash,
                            * 5 TV paint quad (tri0 = g_hwTvq index)  */
    int tri0;              /* kind 5: the tvq[] index (SW: post[bi].tri0) */
    SnowFlakeDraw fd;
    RainFlakeDraw rfd;
} HwSprite;

/* WA2-3 seam: the batch-owned TV paint quad copy — the SW's tvq[24] is
 * stack-local to render_frame; the HW kind-5 blit is deferred to
 * hw_replay_sprites (after the GPU readback), so the quads must outlive
 * hw_build_batch. Filled by sh_tvpaint_collect each frame (24 max, the
 * engine's per-anim TV_MAX_QUADS class; g_hwNTvq == 0 when the GLB has
 * no tvPaint extras or dbgItem >= 0).                                */
static TvQuad g_hwTvq[24];
static int g_hwNTvq;

static HwSprite *g_hwSprites = NULL;
static size_t g_hwNSprite = 0, g_hwCapSprite = 0;

static void hw_batch_reset(void) {
    g_hwBatch.nVert = g_hwBatch.nInst = 0;
    g_hwBatch.nZVert = g_hwBatch.nZInst = 0;
    g_hwNSprite = 0;
}

static int hw_batch_reserve(size_t nTri) {
    if (g_hwBatch.capVert < nTri * 3) {
        size_t cap = nTri * 3 + 4096;
        HwVert *v = (HwVert *)realloc(g_hwBatch.verts, cap * sizeof(HwVert));
        if (!v) return 0;
        g_hwBatch.verts = v; g_hwBatch.capVert = cap;
    }
    if (g_hwBatch.capInst < nTri * 3) {
        /* the per-triangle instance record is REPLICATED 3x (one copy per
         * vertex) so a plain glDrawArrays over the triangle soup works —
         * glDrawArraysInstanced would repeat the FIRST 3 vertices for
         * every instance, which is NOT the batch's layout. Vulkan gets
         * the IDENTICAL layout (two vertex-rate bindings, no instancing) */
        size_t cap = nTri * 3 + 3072;
        HwInst *i = (HwInst *)realloc(g_hwBatch.insts, cap * sizeof(HwInst));
        if (!i) return 0;
        g_hwBatch.insts = i; g_hwBatch.capInst = cap;
        HwVert *zv = (HwVert *)realloc(g_hwBatch.zverts, cap * sizeof(HwVert));
        if (!zv) return 0;
        g_hwBatch.zverts = zv; g_hwBatch.capZVert = cap;
        HwInst *zi = (HwInst *)realloc(g_hwBatch.zinsts, cap * sizeof(HwInst));
        if (!zi) return 0;
        g_hwBatch.zinsts = zi; g_hwBatch.capZInst = cap;
    }
    return 1;
}

static void hw_batch_free(void) {
    free(g_hwBatch.verts);  free(g_hwBatch.insts);
    free(g_hwBatch.zverts); free(g_hwBatch.zinsts);
    free(g_hwSprites);
    memset(&g_hwBatch, 0, sizeof(g_hwBatch));
    g_hwSprites = NULL; g_hwNSprite = g_hwCapSprite = 0;
}

static int hw_sprite_push(int kind, const SnowFlakeDraw *fd,
                          const RainFlakeDraw *rfd, int tri0) {
    if (g_hwNSprite >= g_hwCapSprite) {
        size_t cap = g_hwCapSprite ? g_hwCapSprite * 2 : 1024;
        HwSprite *s = (HwSprite *)realloc(g_hwSprites,
                                          cap * sizeof(HwSprite));
        if (!s) return 0;
        g_hwSprites = s; g_hwCapSprite = cap;
    }
    HwSprite *s = &g_hwSprites[g_hwNSprite++];
    memset(s, 0, sizeof(*s));
    s->kind = kind;
    s->tri0 = tri0;
    if (fd) s->fd = *fd;
    if (rfd) s->rfd = *rfd;
    return 1;
}

/* ==========================================================================
 * the GLES backend (EGL pbuffer context + offscreen FBO; NO window surface
 * — presentation stays the engine's own CPU blit in every backend)
 * ========================================================================*/
static EGLDisplay g_gl_dpy = EGL_NO_DISPLAY;
static EGLContext g_gl_ctx = EGL_NO_CONTEXT;
static EGLSurface g_gl_surf = EGL_NO_SURFACE;
static int g_gl_ready = 0;          /* context + FBO fully operational     */
static int g_gl_failed = 0;         /* init failed once — stay on software */
static GLuint g_gl_prog = 0;
static GLuint g_gl_vao = 0;
static GLuint g_gl_vbo = 0, g_gl_ibo = 0;       /* color-pass buffers      */
static GLuint g_gl_zvbo = 0, g_gl_zibo = 0;     /* z-pass buffers          */
static GLuint g_gl_fbo = 0;
static GLuint g_gl_coltex = 0, g_gl_ztex = 0;
static GLuint g_gl_texarr = 0;
static int g_gl_layers = 0, g_gl_maxW = 0, g_gl_maxH = 0;
/* glColorMaski: absent from the NDK's API-23 libGLESv3 stub (link-time),
 * present in every ES 3.0 driver — resolve it through eglGetProcAddress
 * (which Android defines to return core functions too). NULL -> the MRT
 * write-mask split is unavailable -> fall back to software.            */
static void (*p_glColorMaski)(GLuint, GLboolean, GLboolean, GLboolean,
                              GLboolean) = NULL;

static int gles_check(const char *what) {
    GLenum e = glGetError();
    if (e != GL_NO_ERROR) {
        HWLOGE("GLES: %s -> GL error 0x%x", what, (unsigned)e);
        return 0;
    }
    return 1;
}

static GLuint gles_compile(GLenum type, const char *src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, NULL);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[2048];
        GLsizei n = 0;
        log[0] = 0;
        glGetShaderInfoLog(sh, sizeof(log) - 1, &n, log);
        HWLOGE("shader compile FAILED (%s)", type == GL_VERTEX_SHADER ? "vs" : "fs");
        HWLOGE("%s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

/* attribute binding helpers — the z pass and the color pass draw from
 * DIFFERENT vertex/instance buffers, so the attribute pointers must be
 * re-pointed between the two draws (both stay in the ONE VAO)          */
static void gles_bind_attrs(GLuint vertBuf, GLuint instBuf) {
    glBindBuffer(GL_ARRAY_BUFFER, vertBuf);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(HwVert),
                          (const void *)offsetof(HwVert, sx));
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(HwVert),
                          (const void *)offsetof(HwVert, u));
    glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(HwVert),
                          (const void *)offsetof(HwVert, vr));
    glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(HwVert),
                          (const void *)offsetof(HwVert, orr));
    glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(HwVert),
                          (const void *)offsetof(HwVert, z));
    glBindBuffer(GL_ARRAY_BUFFER, instBuf);
    glVertexAttribIPointer(5, 4, GL_UNSIGNED_INT, sizeof(HwInst),
                           (const void *)offsetof(HwInst, ddaU0));
    glVertexAttribIPointer(6, 2, GL_UNSIGNED_INT, sizeof(HwInst),
                           (const void *)offsetof(HwInst, ddaDvdx));
    glVertexAttribIPointer(7, 4, GL_INT, sizeof(HwInst),
                           (const void *)offsetof(HwInst, ddaX0));
    glVertexAttribIPointer(8, 2, GL_INT, sizeof(HwInst),
                           (const void *)offsetof(HwInst, texW));
    glVertexAttribIPointer(9, 4, GL_INT, sizeof(HwInst),
                           (const void *)offsetof(HwInst, flags));
}

static int gles_hw_init(int w, int h, const Scene *sc) {
    if (g_gl_failed) return 0;
    if (g_gl_ready) return 1;

    /* --- EGL context (pbuffer — desktop surfaceless-Mesa or Android) ---
     * eglGetPlatformDisplay is absent from the NDK's API-23 libEGL stub
     * (arrived with API 24): compile it only where the NDK symbol exists
     * (__ANDROID_API__ — the desktop harness defines only __ANDROID__);
     * Android always uses the default display anyway (the FBO is the
     * real render target — the pbuffer only anchors makeCurrent).      */
    EGLDisplay dpy = EGL_NO_DISPLAY;
#if !defined(__ANDROID_API__) || __ANDROID_API__ >= 24
    dpy = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA,
                                (EGLNativeDisplayType)NULL, NULL);
#endif
    if (dpy == EGL_NO_DISPLAY)
        dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, NULL, NULL)) {
        HWLOGE("EGL: no display");
        g_gl_failed = 1;
        return 0;
    }
    g_gl_dpy = dpy;
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttribs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg = NULL;
    EGLint n = 0;
    if (!eglChooseConfig(dpy, cfgAttribs, &cfg, 1, &n) || n < 1) {
        HWLOGE("EGL: no ES3 pbuffer config");
        g_gl_failed = 1;
        return 0;
    }
    EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    g_gl_ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttribs);
    if (g_gl_ctx == EGL_NO_CONTEXT) {
        HWLOGE("EGL: ES3 context failed");
        g_gl_failed = 1;
        return 0;
    }
    EGLint pbAttribs[] = {EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE};
    g_gl_surf = eglCreatePbufferSurface(dpy, cfg, pbAttribs);
    if (g_gl_surf == EGL_NO_SURFACE) {
        HWLOGE("EGL: pbuffer failed");
        g_gl_failed = 1;
        return 0;
    }
    if (!eglMakeCurrent(dpy, g_gl_surf, g_gl_surf, g_gl_ctx)) {
        HWLOGE("EGL: makeCurrent failed");
        g_gl_failed = 1;
        return 0;
    }
    p_glColorMaski = (void (*)(GLuint, GLboolean, GLboolean, GLboolean,
                                GLboolean))eglGetProcAddress("glColorMaski");
    if (!p_glColorMaski) {
        HWLOGE("GLES: glColorMaski unavailable — staying on software");
        g_gl_failed = 1;
        return 0;
    }
    HWLOGI("GLES backend: %s | %s | %s",
           (const char *)glGetString(GL_VENDOR),
           (const char *)glGetString(GL_RENDERER),
           (const char *)glGetString(GL_VERSION));

    /* --- program ------------------------------------------------------- */
    GLuint vs = gles_compile(GL_VERTEX_SHADER, HW_VS_MESH_GLES);
    GLuint fs = gles_compile(GL_FRAGMENT_SHADER, HW_FS_MESH_GLES);
    if (!vs || !fs) { g_gl_failed = 1; return 0; }
    g_gl_prog = glCreateProgram();
    glAttachShader(g_gl_prog, vs);
    glAttachShader(g_gl_prog, fs);
    glLinkProgram(g_gl_prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(g_gl_prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[2048]; log[0] = 0;
        glGetProgramInfoLog(g_gl_prog, sizeof(log) - 1, NULL, log);
        HWLOGE("program link FAILED: %s", log);
        g_gl_failed = 1;
        return 0;
    }
    glUseProgram(g_gl_prog);
    GLint un_fbSize = glGetUniformLocation(g_gl_prog, "u_fbSize");
    glUniform2f(un_fbSize, (float)w, (float)h);
    GLint un_sd = glGetUniformLocation(g_gl_prog, "u_scaled_dithering");
    glUniform1ui(un_sd, 1u);         /* dither grid at output res */
    GLint un_tc = glGetUniformLocation(g_gl_prog, "u_true_color");
    glUniform1ui(un_tc, getenv("HW_DEBUG_SHADER") ? 7u : 0u);  /* 7 = the
        debug early-out (flags as colors); normally the 15-bit path */

    /* --- VAO: attributes 0-4 vertex rate, 5-9 instance rate (divisor 1) - */
    glGenVertexArrays(1, &g_gl_vao);
    glBindVertexArray(g_gl_vao);
    glGenBuffers(1, &g_gl_vbo);
    glGenBuffers(1, &g_gl_ibo);
    glGenBuffers(1, &g_gl_zvbo);
    glGenBuffers(1, &g_gl_zibo);
    gles_bind_attrs(g_gl_vbo, g_gl_ibo);
    for (int loc = 0; loc <= 9; loc++) glEnableVertexAttribArray(loc);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);      /* raster_tri accepts both windings */
    glViewport(0, 0, w, h);

    /* --- offscreen MRT FBO: RGBA8 color + R32F z ----------------------- */
    glGenFramebuffers(1, &g_gl_fbo);
    glGenTextures(1, &g_gl_coltex);
    glGenTextures(1, &g_gl_ztex);
    glBindTexture(GL_TEXTURE_2D, g_gl_coltex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glBindTexture(GL_TEXTURE_2D, g_gl_ztex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, w, h, 0, GL_RED,
                 GL_FLOAT, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, g_gl_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, g_gl_coltex, 0);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1,
                           GL_TEXTURE_2D, g_gl_ztex, 0);
    const GLenum bufs[2] = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
    glDrawBuffers(2, bufs);
    GLenum fbSt = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (fbSt != GL_FRAMEBUFFER_COMPLETE) {
        HWLOGE("GLES: MRT FBO incomplete: status 0x%x (R32F render target "
               "needs EXT_color_buffer_float) — staying on the software "
               "renderer", (unsigned)fbSt);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        g_gl_failed = 1;
        return 0;
    }
    if (!gles_check("setup")) { g_gl_failed = 1; return 0; }

    /* --- the scene texture array (padded layers; wrap is in-shader) ----- */
    g_gl_layers = sc->texCount > 0 ? sc->texCount : 1;
    g_gl_maxW = 1; g_gl_maxH = 1;
    for (int i = 0; i < sc->texCount; i++) {
        if (sc->tex[i].w > g_gl_maxW) g_gl_maxW = sc->tex[i].w;
        if (sc->tex[i].h > g_gl_maxH) g_gl_maxH = sc->tex[i].h;
    }
    if (g_gl_maxW > 2048) g_gl_maxW = 2048;
    if (g_gl_maxH > 2048) g_gl_maxH = 2048;
    glGenTextures(1, &g_gl_texarr);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D_ARRAY, g_gl_texarr);
    glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, g_gl_maxW, g_gl_maxH,
                 g_gl_layers, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    for (int i = 0; i < sc->texCount; i++) {
        const TexInfo *t = &sc->tex[i];
        if (t->w <= 0 || t->h <= 0 || t->w > 2048 || t->h > 2048) continue;
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, t->w, t->h, 1,
                        GL_RGBA, GL_UNSIGNED_BYTE, sc->texBlob + t->off);
    }
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (!gles_check("texture array")) { g_gl_failed = 1; return 0; }
    HWLOGI("GLES: texture array %dx%d x %d layers (FBO %dx%d MRT RGBA8+R32F)",
           g_gl_maxW, g_gl_maxH, g_gl_layers, w, h);
    g_gl_ready = 1;
    return 1;
}

static void gles_hw_shutdown(void) {
    if (g_gl_dpy != EGL_NO_DISPLAY && g_gl_ctx != EGL_NO_CONTEXT)
        eglMakeCurrent(g_gl_dpy, g_gl_surf, g_gl_surf, g_gl_ctx);
    if (g_gl_prog) glDeleteProgram(g_gl_prog);
    if (g_gl_vao) glDeleteVertexArrays(1, &g_gl_vao);
    if (g_gl_vbo) glDeleteBuffers(1, &g_gl_vbo);
    if (g_gl_ibo) glDeleteBuffers(1, &g_gl_ibo);
    if (g_gl_zvbo) glDeleteBuffers(1, &g_gl_zvbo);
    if (g_gl_zibo) glDeleteBuffers(1, &g_gl_zibo);
    if (g_gl_fbo) glDeleteFramebuffers(1, &g_gl_fbo);
    if (g_gl_coltex) glDeleteTextures(1, &g_gl_coltex);
    if (g_gl_ztex) glDeleteTextures(1, &g_gl_ztex);
    if (g_gl_texarr) glDeleteTextures(1, &g_gl_texarr);
    if (g_gl_dpy != EGL_NO_DISPLAY) {
        if (g_gl_surf != EGL_NO_SURFACE) eglDestroySurface(g_gl_dpy, g_gl_surf);
        if (g_gl_ctx != EGL_NO_CONTEXT) eglDestroyContext(g_gl_dpy, g_gl_ctx);
        eglTerminate(g_gl_dpy);
    }
    g_gl_prog = 0; g_gl_vao = 0; g_gl_vbo = g_gl_ibo = 0;
    g_gl_zvbo = g_gl_zibo = 0; g_gl_fbo = 0;
    g_gl_coltex = g_gl_ztex = g_gl_texarr = 0;
    g_gl_surf = EGL_NO_SURFACE; g_gl_ctx = EGL_NO_CONTEXT;
    g_gl_dpy = EGL_NO_DISPLAY;
    g_gl_ready = 0; g_gl_failed = 0;
}

/* the frame draw: z-pass (opaque instances, attachment 0 masked off) then
 * color pass (all instances, attachment 1 masked off), then the readback.
 * GL's framebuffer origin is bottom-left; both readbacks are row-flipped
 * into the engine's top-left layout (the v9.8 harness's proven flip).    */
static int gles_hw_render(Frame *fb, const Renderer *R) {
    const int w = fb->w, h = fb->h;
    const size_t nAll = g_hwBatch.nInst, nZ = g_hwBatch.nZInst;
    uint8_t cr = R->fogEnabled ? R->fog->fogColor[0] : 16;
    uint8_t cg = R->fogEnabled ? R->fog->fogColor[1] : 16;
    uint8_t cb = R->fogEnabled ? R->fog->fogColor[2] : 16;

    glBindFramebuffer(GL_FRAMEBUFFER, g_gl_fbo);
    glViewport(0, 0, w, h);
    glUseProgram(g_gl_prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D_ARRAY, g_gl_texarr);

    /* clear BOTH attachments (masks fully on — clear respects masks) */
    p_glColorMaski(0, GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    p_glColorMaski(1, GL_TRUE, GL_FALSE, GL_FALSE, GL_FALSE);
    const GLfloat colClear[4] = {cr / 255.0f, cg / 255.0f, cb / 255.0f, 1.0f};
    const GLfloat zClear[4] = {1e30f, 0.0f, 0.0f, 0.0f};
    glClearBufferfv(GL_COLOR, 0, colClear);
    glClearBufferfv(GL_COLOR, 1, zClear);

    /* ---- z pass: OPAQUE instances only; color masked off -------------- */
    if (nZ > 0) {
        p_glColorMaski(0, GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glBindBuffer(GL_ARRAY_BUFFER, g_gl_zvbo);
        glBufferData(GL_ARRAY_BUFFER,
                     (GLsizeiptr)(sizeof(HwVert) * g_hwBatch.nZVert),
                     g_hwBatch.zverts, GL_STREAM_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, g_gl_zibo);
        glBufferData(GL_ARRAY_BUFFER,
                     (GLsizeiptr)(sizeof(HwInst) * g_hwBatch.nZVert),
                     g_hwBatch.zinsts, GL_STREAM_DRAW);
        gles_bind_attrs(g_gl_zvbo, g_gl_zibo);
        glDrawArrays(GL_TRIANGLES, 0, (GLsizei)g_hwBatch.nZVert);
    }

    /* ---- color pass: ALL instances in OT order; z masked off ---------- */
    p_glColorMaski(0, GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    p_glColorMaski(1, GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
    if (nAll > 0) {
        glBindBuffer(GL_ARRAY_BUFFER, g_gl_vbo);
        glBufferData(GL_ARRAY_BUFFER,
                     (GLsizeiptr)(sizeof(HwVert) * g_hwBatch.nVert),
                     g_hwBatch.verts, GL_STREAM_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, g_gl_ibo);
        glBufferData(GL_ARRAY_BUFFER,
                     (GLsizeiptr)(sizeof(HwInst) * g_hwBatch.nVert),
                     g_hwBatch.insts, GL_STREAM_DRAW);
        gles_bind_attrs(g_gl_vbo, g_gl_ibo);
        glDrawArrays(GL_TRIANGLES, 0, (GLsizei)g_hwBatch.nVert);
    }

    if (getenv("HW_DEBUG_COLOR")) {
        /* probe the color attachment right after the color-pass draw */
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        uint8_t probe[16 * 4];
        glReadPixels(120, 100, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, probe);
        HWLOGI("color probe @120,100: %d %d %d / %d %d %d / %d %d %d",
               probe[0], probe[1], probe[2], probe[4], probe[5], probe[6],
               probe[8], probe[9], probe[10]);
        GLint db0 = -1, db1 = -1;
        glGetIntegerv(GL_DRAW_BUFFER0, &db0);
        glGetIntegerv(GL_DRAW_BUFFER1, &db1);
        HWLOGI("draw buffers: 0=0x%x 1=0x%x (ATTACHMENT0=0x8CE0)", db0, db1);
        GLint vp[4] = {0, 0, 0, 0};
        glGetIntegerv(GL_VIEWPORT, vp);
        HWLOGI("viewport: %d %d %d %d", vp[0], vp[1], vp[2], vp[3]);
    }
    /* ---- readback ------------------------------------------------------ */
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadBuffer(GL_COLOR_ATTACHMENT0);
    {
        uint8_t *rgba = (uint8_t *)malloc((size_t)w * h * 4);
        if (!rgba) return 0;
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        for (int y = 0; y < h; y++) {
            uint8_t *dst = fb->rgb + (size_t)(h - 1 - y) * w * 3;
            const uint8_t *src = rgba + (size_t)y * w * 4;
            for (int x = 0; x < w; x++) {
                dst[x * 3] = src[x * 4];
                dst[x * 3 + 1] = src[x * 4 + 1];
                dst[x * 3 + 2] = src[x * 4 + 2];
            }
        }
        free(rgba);
    }
    {
        /* R32F: query the implementation read format (RED/FLOAT expected;
         * RGBA/FLOAT tolerated — .r extracted)                              */
        glReadBuffer(GL_COLOR_ATTACHMENT1);
        GLint fmt = 0, type = 0;
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &fmt);
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &type);
        if (fmt == GL_RED && type == GL_FLOAT) {
            float *zimg = (float *)malloc((size_t)w * h * sizeof(float));
            if (!zimg) return 0;
            glReadPixels(0, 0, w, h, GL_RED, GL_FLOAT, zimg);
            for (int y = 0; y < h; y++)
                memcpy(fb->z + (size_t)(h - 1 - y) * w,
                       zimg + (size_t)y * w, (size_t)w * sizeof(float));
            free(zimg);
        } else if (fmt == GL_RGBA && type == GL_FLOAT) {
            GLfloat *tmp = (GLfloat *)malloc((size_t)w * h * 4 * sizeof(GLfloat));
            if (!tmp) return 0;
            glReadPixels(0, 0, w, h, GL_RGBA, GL_FLOAT, tmp);
            for (int y = 0; y < h; y++) {
                float *dst = fb->z + (size_t)(h - 1 - y) * w;
                const GLfloat *src = tmp + (size_t)y * w * 4;
                for (int x = 0; x < w; x++) dst[x] = src[x * 4];
            }
            free(tmp);
        } else {
            HWLOGE("GLES: unsupported z readback (fmt 0x%x type 0x%x)",
                   (unsigned)fmt, (unsigned)type);
            return 0;
        }
    }
    if (!gles_check("frame")) return 0;
    return 1;
}

/* ==========================================================================
 * hw_push_tri — the raster_tri() REDIRECT target. Derives the SAME
 * DuckStation UV DDA parameters raster_tri derives (the derivation block
 * is copied verbatim from raster_tri's prologue — identical to the v9.8
 * harness's gles_push_tri).
 * ========================================================================*/
static void hw_push_tri(const RVert *vA, const RVert *vB, const RVert *vC,
                        int tw, int th, PassMode pass, const RenderOpts *opts,
                        int quantize, int dither, float engScaleX,
                        float engScaleY, int texIdx) {
    if ((size_t)g_hwBatch.nInst + 1 > g_hwBatch.capInst ||
        (size_t)g_hwBatch.nVert + 3 > g_hwBatch.capVert)
        return;                              /* reserve covers real counts */
    HwVert *Vv[3];
    Vv[0] = &g_hwBatch.verts[g_hwBatch.nVert];
    Vv[1] = &g_hwBatch.verts[g_hwBatch.nVert + 1];
    Vv[2] = &g_hwBatch.verts[g_hwBatch.nVert + 2];
    const RVert *V[3] = {vA, vB, vC};
    for (int k = 0; k < 3; k++) {
        Vv[k]->sx = V[k]->sx;
        Vv[k]->sy = V[k]->sy;
        Vv[k]->u = V[k]->u;
        Vv[k]->v = V[k]->v;
        Vv[k]->vr = (float)V[k]->vcol[0];
        Vv[k]->vg = (float)V[k]->vcol[1];
        Vv[k]->vb = (float)V[k]->vcol[2];
        Vv[k]->orr = (float)V[k]->ocol[0];
        Vv[k]->og = (float)V[k]->ocol[1];
        Vv[k]->ob = (float)V[k]->ocol[2];
        Vv[k]->z = V[k]->z;
    }
    HwInst instTmp;
    memset(&instTmp, 0, sizeof(instTmp));
    HwInst *in = &instTmp;
    in->flags = (dither ? 1 : 0) | (quantize ? 2 : 0);
    in->persp = opts->linear ? 1 : 0;
    in->texW = tw;
    in->texH = th;
    in->layer = texIdx;
    /* ---- the raster_tri DDA derivation, VERBATIM ----------------------- */
    int persp = opts->linear;
    if (!persp && tw > 0 && th > 0 && tw <= 256 && th <= 256 &&
        engScaleX > 0.5f && engScaleY > 0.5f) {
        int32_t ex[3], ey[3], iu[3], iv[3];
        for (int k = 0; k < 3; k++) {
            const RVert *Vv2 = (k == 0) ? vA : (k == 1) ? vB : vC;
            ex[k] = (int32_t)lrintf(Vv2->sx / engScaleX);
            ey[k] = (int32_t)lrintf(Vv2->sy / engScaleY);
            iu[k] = (int32_t)lrintf(Vv2->u * (float)tw);
            iv[k] = (int32_t)lrintf(Vv2->v * (float)th);
        }
        int32_t det = (ex[1]-ex[0])*(ey[2]-ey[1]) - (ex[2]-ex[1])*(ey[1]-ey[0]);
        if (det != 0) {
            int32_t det_ux = (ex[1]-ex[0])*(iu[2]-iu[1]) - (ex[2]-ex[1])*(iu[1]-iu[0]);
            int32_t det_uy = (iu[1]-iu[0])*(ey[2]-ey[1]) - (iu[2]-iu[1])*(ey[1]-ey[0]);
            int32_t det_vx = (ex[1]-ex[0])*(iv[2]-iv[1]) - (ex[2]-ex[1])*(iv[1]-iv[0]);
            int32_t det_vy = (iv[1]-iv[0])*(ey[2]-ey[1]) - (iv[2]-iv[1])*(ey[1]-ey[0]);
            in->ddaDudx = (uint32_t)(int32_t)((int64_t)det_uy * 4096 / det) << 12;
            in->ddaDudy = (uint32_t)(int32_t)((int64_t)det_ux * 4096 / det) << 12;
            in->ddaDvdx = (uint32_t)(int32_t)((int64_t)det_vy * 4096 / det) << 12;
            in->ddaDvdy = (uint32_t)(int32_t)((int64_t)det_vx * 4096 / det) << 12;
            in->ddaU0 = ((uint32_t)(uint8_t)iu[0] << 24) + (2048u << 12);
            in->ddaV0 = ((uint32_t)(uint8_t)iv[0] << 24) + (2048u << 12);
            in->ddaX0 = (int32_t)((int64_t)ex[0] << 12);
            in->ddaY0 = (int32_t)((int64_t)ey[0] << 12);
            in->ddaKX = (int32_t)(4096.0 / (double)engScaleX);
            in->ddaKY = (int32_t)(4096.0 / (double)engScaleY);
            in->useDDA = 1;
        }
    }
    /* the instance record is REPLICATED 3x into the vertex-rate buffer
     * (one copy per vertex — integer attributes are flat-interpolated from
     * the PROVOKING vertex, so every copy must carry the full record)   */
    g_hwBatch.insts[g_hwBatch.nVert] = instTmp;
    g_hwBatch.insts[g_hwBatch.nVert + 1] = instTmp;
    g_hwBatch.insts[g_hwBatch.nVert + 2] = instTmp;
    g_hwBatch.nVert += 3;
    g_hwBatch.nInst += 1;
    /* the z pass takes OPAQUE triangles only (raster_tri writes fb->z
     * ONLY for RP_OPAQUE non-hole fragments — the shader's discard gives
     * the hole rule; this filter gives the pass rule)                    */
    if (pass == RP_OPAQUE) {
        if ((size_t)g_hwBatch.nZVert + 3 <= g_hwBatch.capZVert) {
            memcpy(&g_hwBatch.zverts[g_hwBatch.nZVert], Vv[0], sizeof(HwVert) * 3);
            memcpy(&g_hwBatch.zinsts[g_hwBatch.nZVert], in, sizeof(HwInst));
            memcpy(&g_hwBatch.zinsts[g_hwBatch.nZVert + 1], in, sizeof(HwInst));
            memcpy(&g_hwBatch.zinsts[g_hwBatch.nZVert + 2], in, sizeof(HwInst));
            g_hwBatch.nZVert += 3;
            g_hwBatch.nZInst += 1;
        }
    }
}

/* ==========================================================================
 * hw_build_batch + hw_replay_sprites — render_frame's body walked VERBATIM
 * (the OT-build block copied line-for-line; raster_tri redirected to
 * hw_push_tri; the snow/rain sprite blits deferred to hw_replay_sprites).
 * The clear is the GPU clear; the CPU fb is written by the readback.
 *
 * v43 re-port (WA2-3): the v36 TV paint block (sh_tvpaint_collect at the
 * engine's position — after the weather flakes, before the mesh quads —
 * so on an OT bucket tie the TV quads sit between the flakes and the
 * room geometry) + the OT walk's kind-5 arm are re-ported. The stale
 * v9.x copy had NO TV collection at all: the deferred sprite list carried
 * kinds 1-4 only, so the TV noise was entirely missing from both hardware
 * backends. SEAM: the SW's tvq[24] is stack-local to render_frame; the
 * kind-5 blit (sh_tvpaint_draw_quad, a CPU raster_tri pair) is DEFERRED
 * to hw_replay_sprites after the GPU readback like the weather sprites,
 * so the batch owns the copy (g_hwTvq/g_hwNTvq) and the SW's
 * `tri0 >= 0 && tri0 < nTvq` bounds check runs at the replay. Known
 * bounded residual (the documented sprite divergence class): a deferred
 * entry composites over ALL GPU-batched quads instead of only the
 * farther ones — for the TV quads this only matters where nearer mesh
 * geometry overlaps a screen (it would have painted over the TV).
 * ========================================================================*/
static int hw_build_batch(Frame *fb, const Renderer *R, const CamPose *cam) {
    const Scene *sc = R->sc;
    if (g_lightDump && R->env) {
        const ShLight *Ld = &R->env->light;
        fprintf(g_lightDump, "POSE F%d pos=%d,%d,%d dir=%d,%d,%d f28=%d,%d,%d f30=%d,%d,%d dqa=%d int=%d cam=%.3f,%.3f,%.3f\n",
                g_dbgFrame, Ld->pos[0], Ld->pos[1], Ld->pos[2],
                Ld->dir[0], Ld->dir[1], Ld->dir[2],
                Ld->field_28[0], Ld->field_28[1], Ld->field_28[2],
                Ld->field_30[0], Ld->field_30[1], Ld->field_30[2],
                Ld->dqa, Ld->intensity,
                cam->pos.x, cam->pos.y, cam->pos.z);
    }
    float aspect = (float)fb->w / (float)fb->h;
    mat4 view = mat4_view_from_quat(cam->pos, cam->q);
    mat4 proj = mat4_perspective(cam->fovDeg, aspect, 0.05f, 100.0f);
    mat4 viewProj = mat4_mul(&proj, &view);

    if (R->fpDump) {
        uint32_t u;
        fprintf(R->fpDump, "cam pos %f %f %f q %f %f %f %f fov %f\n",
                cam->pos.x, cam->pos.y, cam->pos.z,
                cam->q.x, cam->q.y, cam->q.z, cam->q.w, cam->fovDeg);
        for (int k = 0; k < 16; k++) {
            memcpy(&u, &view.m[k], 4); fprintf(R->fpDump, "view%02d %08x\n", k, u);
            memcpy(&u, &proj.m[k], 4); fprintf(R->fpDump, "proj%02d %08x\n", k, u);
            memcpy(&u, &viewProj.m[k], 4); fprintf(R->fpDump, "vpc%02d %08x\n", k, u);
        }
    }

    int nItems = sc->itemCount;
    RVert *xv = (RVert *)calloc((size_t)(sc->vertCount ? sc->vertCount : 1), sizeof(RVert));
    for (int i = 0; i < nItems; i++) {
        transform_item2(R, &sc->item[i], &view, &viewProj,
                        xv + sc->item[i].vbase, NULL);
        if (R->fpDump && i == 0) {
            const DrawItem *it = &sc->item[i];
            for (int k = 0; k < it->vcount && k < 6; k++) {
                const RVert *o = &xv[it->vbase + k];
                uint32_t u;
                fprintf(R->fpDump, "v%d sx %f sy %f z %f u %f v %f vc %d %d %d oc %d %d %d\n",
                        k, o->sx, o->sy, o->z, o->u, o->v,
                        o->vcol[0], o->vcol[1], o->vcol[2],
                        o->ocol[0], o->ocol[1], o->ocol[2]);
                memcpy(&u, &o->sx, 4); fprintf(R->fpDump, "v%dsx %08x\n", k, u);
                memcpy(&u, &o->sy, 4); fprintf(R->fpDump, "v%dsy %08x\n", k, u);
            }
        }
    }

    int farCullQ8 = 0x79C << 3;
    if (R->fogEnabled && R->fog->isFogEnabled && R->fog->farQ8 < farCullQ8)
        farCullQ8 = R->fog->farQ8;

    int nQuadMax = 0;
    for (int i = 0; i < nItems; i++) {
        if (R->dbgItem >= 0 && i != R->dbgItem) continue;
        if (sc->item[i].matIdx >= 0 && sc->item[i].matIdx < sc->matCount)
            nQuadMax += sc->item[i].icount / 3 + 1;
    }
    PostEnt *post = (PostEnt *)malloc(sizeof(PostEnt) *
                                      (size_t)(nQuadMax + SNOW_COUNT_MAX + 1));
    if (!xv || !post) { free(xv); free(post); return 0; }
    int nPost = 0, seq = 0;
    int nFlake = 0;
    const Snow *S = R->snow;
    int snowScale = fb->w / 320; if (snowScale < 1) snowScale = 1;
    if (S && S->enabled && R->dbgItem < 0) {
        for (int i = 0; i < S->addedCount && i < SNOW_COUNT_MAX; i++) {
            const SnowPart *p = &S->p[i];
            if (p->hide) continue;               /* engine: type_1F >= 240  */
            if (p->state == 0) continue;         /* Spawn state not drawn   */
            float wx = (float)(S->boxX + p->pos[0]) * (1.0f / 4096.0f);
            float wy = S->datumM - (float)(p->pos[1]) * (1.0f / 4096.0f);
            float wz = (float)(S->boxZ + p->pos[2]) * (1.0f / 4096.0f);
            float cx = viewProj.m[0]*wx + viewProj.m[4]*wy + viewProj.m[8]*wz  + viewProj.m[12];
            float cy = viewProj.m[1]*wx + viewProj.m[5]*wy + viewProj.m[9]*wz  + viewProj.m[13];
            float cw = viewProj.m[3]*wx + viewProj.m[7]*wy + viewProj.m[11]*wz + viewProj.m[15];
            if (cw <= 1e-6f) continue;
            float vz = view.m[2]*wx + view.m[6]*wy + view.m[10]*wz + view.m[14];
            float depth = -vz;
            if (depth <= 0.05f) continue;
            float ndcX = cx / cw, ndcY = cy / cw;
            float sx = (ndcX + 1.0f) * 0.5f * (float)fb->w;
            float sy = (1.0f - (ndcY + 1.0f) * 0.5f) * (float)fb->h;
            if (R->opts.ps1)
                gte_snap_screen(&sx, &sy, R->enginePixScale, R->enginePixScaleY);

            int otz = (int)floorf(depth * 64.0f + 0.5f);
            int snowBase = R->env ? sh_env_snow_base(R->env)[0] : SNOW_PRIM_BASE;
            int prim = (int)((unsigned)(snowBase -
                              (engine_fog_80055A50(R->fog, otz) >> 2)) & 0xFFu);
            int zHalf = otz >> 1;
            if (p->state >= 2) {
                if (p->state < 31) {
                    prim -= (prim * (int)p->state) >> 5;   /* resting fade  */
                    prim &= 0xFF;                          /* engine u8     */
                } else {
                    continue;              /* engine resets state and returns */
                }
            }
            if (!(zHalf > 32 && zHalf < 2047)) continue;
            int size, u0t, clutRow;
            if (zHalf < 64)        { size = 4; u0t = 3; clutRow = 96; }               /* near */
            else if (zHalf >= 256) { size = 1; u0t = 0; clutRow = 103; }              /* far  */
            else                   { size = 3; u0t = 0; clutRow = 96 + (zHalf >> 5); }/* mid  */
            PostEnt pe;
            pe.bucket = zHalf; pe.seq = seq++; pe.kind = 1; pe.item = -1; pe.tri0 = -1; pe.ntri = 0;
            pe.fd.sx = (int)sx; pe.fd.sy = (int)sy;
            pe.fd.sizePx = size * snowScale;
            pe.fd.u0t = u0t; pe.fd.clutRow = clutRow;
            pe.fd.prim = prim; pe.fd.depth = depth;
            post[nPost++] = pe; nFlake++;

            if (R->snowDump) {
                int px = (int)sx, py = (int)sy;
                int occ = (px < 0 || px >= fb->w || py < 0 || py >= fb->h)
                          ? -1 : (fd_depth_ge_z(depth, fb, px, py) ? 1 : 0);
                fprintf(R->snowDump,
                        "flake i=%d state=%d wx=%.3f wy=%.3f wz=%.3f sx=%d sy=%d "
                        "depth=%.3f otz=%d clut=%d prim=%d occ=%d\n",
                        i, p->state, wx, wy, wz, px, py, depth, otz, clutRow,
                        prim, occ);
            }
        }

        /* ---- v8: collect the visible RAIN (Particle_RainDraw, pure
         * render) — the pass-1 bank [addedCount, +addedCount1) ---- */
        for (int i = S->addedCount;
             i < S->addedCount + S->addedCount1 && i < SNOW_COUNT_MAX; i++) {
            const SnowPart *p = &S->p[i];
            if (p->state == 0) continue;          /* Spawn state not drawn  */
            float hx = (float)(S->boxX + p->pos[0]) * (1.0f / 4096.0f);
            float hy = S->datumM - (float)(p->pos[1]) * (1.0f / 4096.0f);
            float hz = (float)(S->boxZ + p->pos[2]) * (1.0f / 4096.0f);
            float tx = (float)(S->boxX + p->pos1[0]) * (1.0f / 4096.0f);
            float ty = S->datumM - (float)(p->pos1[1]) * (1.0f / 4096.0f);
            float tz = (float)(S->boxZ + p->pos1[2]) * (1.0f / 4096.0f);
            float tcx = viewProj.m[0]*tx + viewProj.m[4]*ty + viewProj.m[8]*tz  + viewProj.m[12];
            float tcy = viewProj.m[1]*tx + viewProj.m[5]*ty + viewProj.m[9]*tz  + viewProj.m[13];
            float tcw = viewProj.m[3]*tx + viewProj.m[7]*ty + viewProj.m[11]*tz + viewProj.m[15];
            float hcx = viewProj.m[0]*hx + viewProj.m[4]*hy + viewProj.m[8]*hz  + viewProj.m[12];
            float hcy = viewProj.m[1]*hx + viewProj.m[5]*hy + viewProj.m[9]*hz  + viewProj.m[13];
            float hcw = viewProj.m[3]*hx + viewProj.m[7]*hy + viewProj.m[11]*hz + viewProj.m[15];
            if (tcw <= 1e-6f || hcw <= 1e-6f) continue;
            float tvz = view.m[2]*tx + view.m[6]*ty + view.m[10]*tz + view.m[14];
            float hvz = view.m[2]*hx + view.m[6]*hy + view.m[10]*hz + view.m[14];
            float tDepth = -tvz, hDepth = -hvz;
            if (tDepth <= 0.05f || hDepth <= 0.05f) continue;
            float tsx = ((tcx / tcw) + 1.0f) * 0.5f * (float)fb->w;
            float tsy = (1.0f - ((tcy / tcw) + 1.0f) * 0.5f) * (float)fb->h;
            float hsx = ((hcx / hcw) + 1.0f) * 0.5f * (float)fb->w;
            float hsy = (1.0f - ((hcy / hcw) + 1.0f) * 0.5f) * (float)fb->h;
            if (R->opts.ps1)
                gte_snap_screen(&tsx, &tsy, R->enginePixScale, R->enginePixScaleY),
                gte_snap_screen(&hsx, &hsy, R->enginePixScale, R->enginePixScaleY);
            int otz = (int)floorf(tDepth * 64.0f + 0.5f);
            int zHalf = otz >> 1;
            if (!(zHalf > 32 && zHalf < 2047)) continue;
            int clutRow = (zHalf > 63 && zHalf < 256)
                              ? 104 + (zHalf >> 5) : 104;
            PostEnt pe;
            memset(&pe, 0, sizeof(pe));
            pe.bucket = zHalf; pe.seq = seq++; pe.item = -1; pe.tri0 = -1; pe.ntri = 0;
            if (p->state == 1) {
                int r, g, b;
                if (S->drawCase == 1 || S->drawCase == 4) {
                    int base = R->env ? sh_env_rain_base(R->env)[0] : 14;
                    r = g = b = (int)((unsigned)(base -
                        (engine_fog_80055A50(R->fog, otz) >> 5)) & 0xFFu);
                } else {
                    const uint8_t *base = R->env ? sh_env_rain_base(R->env)
                                                 : NULL;
                    r = base ? base[0] : 14;
                    g = base ? base[1] : 14;
                    b = base ? base[2] : 30;
                }
                pe.kind = 2;
                pe.rfd.x0 = (int)tsx; pe.rfd.y0 = (int)tsy;  /* tail */
                pe.rfd.x1 = (int)hsx; pe.rfd.y1 = (int)hsy;  /* head */
                pe.rfd.clutRow = clutRow;
                pe.rfd.primR = r; pe.rfd.primG = g; pe.rfd.primB = b;
                pe.rfd.depth = tDepth;
                post[nPost++] = pe; nFlake++;
            } else if (p->type == 2 || p->type == 242) {
                if (p->state >= 17) continue;
                int sp64 = (S->rainIntensity < 27000) ? 3 :
                           (S->rainIntensity < 135000) ? 4 : 5;
                int c = p->state << sp64;
                int cx[4], cy[4];
                int ok = 1;
                static const int sgn[4][2] = { {-1,-1}, {-1,1}, {1,-1}, {1,1} };
                for (int k = 0; k < 4; k++) {
                    float wx = (float)(S->boxX + p->pos[0] + sgn[k][0] * c) * (1.0f / 4096.0f);
                    float wy = hy;
                    float wz = (float)(S->boxZ + p->pos[2] + sgn[k][1] * c) * (1.0f / 4096.0f);
                    float ccx = viewProj.m[0]*wx + viewProj.m[4]*wy + viewProj.m[8]*wz  + viewProj.m[12];
                    float ccy = viewProj.m[1]*wx + viewProj.m[5]*wy + viewProj.m[9]*wz  + viewProj.m[13];
                    float ccw = viewProj.m[3]*wx + viewProj.m[7]*wy + viewProj.m[11]*wz + viewProj.m[15];
                    if (ccw <= 1e-6f) { ok = 0; break; }
                    float psx2 = ((ccx / ccw) + 1.0f) * 0.5f * (float)fb->w;
                    float psy2 = (1.0f - ((ccy / ccw) + 1.0f) * 0.5f) * (float)fb->h;
                    if (R->opts.ps1)
                        gte_snap_screen(&psx2, &psy2, R->enginePixScale, R->enginePixScaleY);
                    cx[k] = (int)psx2;
                    cy[k] = (int)psy2;
                }
                if (!ok) continue;
                int cc = c >> (sp64 - 3);
                int color = 0x80 - cc;
                int r = color, g = color, b = color;
                if (S->drawCase == 2 || S->drawCase == 6) {
                    if (R->env && R->env->isFlashlightOn) r = color * 2;
                }
                r -= (r * (int)p->state) >> 4;
                g -= (g * (int)p->state) >> 4;
                b -= (b * (int)p->state) >> 4;
                r &= 0xFF; g &= 0xFF; b &= 0xFF;
                pe.kind = 3;
                pe.rfd.x0 = cx[0]; pe.rfd.y0 = cy[0];
                pe.rfd.x1 = cx[1]; pe.rfd.y1 = cy[1];
                pe.rfd.x2 = cx[2]; pe.rfd.y2 = cy[2];
                pe.rfd.x3 = cx[3]; pe.rfd.y3 = cy[3];
                pe.rfd.clutRow = clutRow;
                pe.rfd.primR = r; pe.rfd.primG = g; pe.rfd.primB = b;
                pe.rfd.depth = tDepth;
                post[nPost++] = pe; nFlake++;
            } else {
                if (p->state >= 49) continue;
                int cc = (p->state < 31) ? (int)p->state : 0x20;
                pe.kind = 4;
                pe.rfd.x0 = (int)tsx; pe.rfd.y0 = (int)tsy;  /* tail */
                pe.rfd.x1 = (int)hsx; pe.rfd.y1 = (int)hsy + 1; /* y1++ */
                pe.rfd.clutRow = clutRow;
                pe.rfd.primR = cc; pe.rfd.primG = cc; pe.rfd.primB = cc + 0x18;
                pe.rfd.depth = tDepth;
                post[nPost++] = pe; nFlake++;
            }
        }
        if (R->snowDump) fprintf(R->snowDump, "frame end nFlake=%d\n", nFlake);
    }

/* ---- v36: the TV paint quads (func_800D88C8) — collected AFTER the
     * weather flakes, BEFORE the mesh quads: the engine's add order is
     * particles (game_sys_states.c:172) -> the map update's TV prims
     * (func_800DA3E0 -> func_800D7718) -> WorldGfx_Draw's mesh quads
     * (:189), so on an OT bucket tie the TV quads sit BETWEEN the flakes
     * and the room geometry (LIFO: last-added draws first/below).
     * HW SEAM (WA2-3): the SW's tvq[] is stack-local to render_frame;
     * the kind-5 blit (sh_tvpaint_draw_quad) is a CPU raster that runs
     * AFTER the GPU readback (hw_replay_sprites), so the batch owns the
     * copy (g_hwTvq) — the collect fills it directly.                  */
    g_hwNTvq = 0;                    /* the batch-owned count (the replay
                                      * bounds-checks against this)        */
    if (R->dbgItem < 0) {
        sh_tvpaint_collect(&sc->sh1x, &viewProj, &view, fb, R,
                           post, &nPost, &seq, g_hwTvq, &g_hwNTvq, 24,
                           nQuadMax + SNOW_COUNT_MAX + 1);
    }

    /* ---- collect the QUADS (ALL materials) as per-quad OT entries ------ */
    for (int i = 0; i < nItems; i++) {
        if (R->dbgItem >= 0 && i != R->dbgItem) continue;
        const DrawItem *it = &sc->item[i];
        if (it->matIdx < 0 || it->matIdx >= sc->matCount) continue;
        const int ams = sc->mat[it->matIdx].alphaMode;
        for (int k = 0; k + 2 < it->icount;) {
            int isQuad = 0;
            if (k + 5 < it->icount) {
                uint32_t i1 = sc->idxs[it->ibase + k + 1];
                uint32_t i2 = sc->idxs[it->ibase + k + 2];
                uint32_t j0 = sc->idxs[it->ibase + k + 3];
                uint32_t j2 = sc->idxs[it->ibase + k + 5];
                if (j0 == i1 && j2 == i2) isQuad = 1;
            }
            int nv = isQuad ? 4 : 3;
            uint32_t vi[4];
            vi[0] = sc->idxs[it->ibase + k];
            vi[1] = sc->idxs[it->ibase + k + 1];
            vi[2] = sc->idxs[it->ibase + k + 2];
            vi[3] = isQuad ? sc->idxs[it->ibase + k + 4] : 0;
            int bad = 0;
            for (int q = 0; q < nv; q++)
                if (vi[q] >= (uint32_t)it->vcount) { bad = 1; break; }
            if (!bad && ams != 1) {
                const RVert *VA = xv + it->vbase + vi[0];
                const RVert *VB = xv + it->vbase + vi[1];
                const RVert *VC = xv + it->vbase + vi[2];
                if (VA->sx < -1e8f || VB->sx < -1e8f || VC->sx < -1e8f) {
                    bad = 1;
                } else {
                    float n1 = (VB->sx - VA->sx) * (VC->sy - VA->sy) -
                               (VC->sx - VA->sx) * (VB->sy - VA->sy);
                    if (n1 >= 0.0f) {
                        const RVert *VD = xv + it->vbase + vi[3];
                        float n2 = (VD->sx - VA->sx) * (VB->sy - VA->sy) -
                                   (VB->sx - VA->sx) * (VD->sy - VA->sy);
                        if (n2 >= 0.0f) bad = 1;
                    }
                }
            }
            int bucket = -1;
            if (!bad) {
                if (ams == 1) {
                    float cz = 0.0f;
                    for (int q = 0; q < nv; q++) cz += xv[it->vbase + vi[q]].z;
                    cz /= (float)nv;
                    int cq8 = (int)floorf(cz * 256.0f + 0.5f);
                    int cq6 = cq8 >> 2;
                    if (cq6 > 32 && cq8 <= farCullQ8)
                        bucket = cq6 >> 1;
                } else {
                    int mz = 0;
                    for (int q = 0; q < nv; q++) {
                        int q8 = (int)floorf(xv[it->vbase + vi[q]].z * 256.0f + 0.5f);
                        if (q8 > mz) mz = q8;
                    }
                    if (mz > 0 && mz <= farCullQ8) {
                        if (mz < 32) mz = 32;
                        bucket = it->otBase + (mz >> 3);
                    }
                }
            }
            if (bucket >= 0) {
                PostEnt pe;
                memset(&pe, 0, sizeof(pe));
                pe.bucket = bucket; pe.seq = seq++;
                pe.kind = 0; pe.item = i;
                pe.tri0 = k; pe.ntri = isQuad ? 2 : 1;
                post[nPost++] = pe;
            }
            k += isQuad ? 6 : 3;
        }
    }

    qsort(post, (size_t)nPost, sizeof(PostEnt), postent_cmp);

    /* reserve the batch for the worst case, then walk the OT */
    hw_batch_reset();
    if (!hw_batch_reserve((size_t)nQuadMax * 2 + 16)) {
        free(xv); free(post);
        return 0;
    }
    for (int bi = 0; bi < nPost; bi++) {
        if (post[bi].kind == 5) {           /* v36: the TV paint quad   */
            /* DEFERRED like the weather sprites — the engine's own blit
             * (sh_tvpaint_draw_quad) runs on the CPU after the GPU
             * readback, against the batch-owned g_hwTvq copy; the SW's
             * `tri0 < nTvq` bounds check lives at the replay (g_hwNTvq) */
            if (!hw_sprite_push(5, NULL, NULL, post[bi].tri0)) {
                free(xv); free(post);
                return 0;
            }
            continue;
        }
        if (post[bi].kind != 0) {
            /* DEFERRED sprite entry — the engine's own blit runs on the
             * CPU after the GPU readback (see the header comment)        */
            if (!hw_sprite_push(post[bi].kind, &post[bi].fd, &post[bi].rfd,
                                post[bi].tri0)) {
                free(xv); free(post);
                return 0;
            }
            continue;
        }
        const DrawItem *it = &sc->item[post[bi].item];
        const MaterialInfo *m = &sc->mat[it->matIdx];
        if (m->texIdx < 0 || m->texIdx >= sc->texCount) continue;
        const TexInfo *t = &sc->tex[m->texIdx];
        if (t->w <= 0) continue;
        const uint8_t *tex = sc->texBlob + t->off;
        const PassMode quadPass = (m->alphaMode == 0) ? RP_OPAQUE : RP_BLEND_TEX;
        for (int ti = 0; ti < post[bi].ntri && post[bi].tri0 + ti * 3 + 2 < it->icount; ti++) {
            int k = post[bi].tri0 + ti * 3;
            uint32_t ia = sc->idxs[it->ibase + k], ib = sc->idxs[it->ibase + k + 1],
                     ic = sc->idxs[it->ibase + k + 2];
            if (ia >= (uint32_t)it->vcount || ib >= (uint32_t)it->vcount ||
                ic >= (uint32_t)it->vcount) continue;
            RVert *a = xv + it->vbase + ia, *b = xv + it->vbase + ib, *c = xv + it->vbase + ic;
            if (a->z <= 0.05f || b->z <= 0.05f || c->z <= 0.05f) continue;
            {
                float zm = fmaxf(a->z, fmaxf(b->z, c->z));
                if ((int)floorf(zm * 256.0f + 0.5f) > farCullQ8) continue;
            }
            hw_push_tri(a, b, c, t->w, t->h, quadPass, &R->opts,
                        R->opts.ps1, R->dither, R->enginePixScale,
                        R->enginePixScaleY, m->texIdx);
            (void)tex;
        }
    }
    free(xv); free(post);
    return 1;
}

/* the deferred sprite replay — the engine's own blit functions, in their
 * OT order, z-tested against the GPU-readback fb->z. The kind-5 arm is
 * the SW OT walk's TV arm verbatim (the bounds check against the
 * batch-owned count) — sh_tvpaint_draw_quad is the engine's own
 * raster_tri pair.                                                     */
static void hw_replay_sprites(Frame *fb, const Renderer *R) {
    for (size_t i = 0; i < g_hwNSprite; i++) {
        const HwSprite *s = &g_hwSprites[i];
        if (s->kind == 1) snow_blit_flake(fb, &s->fd);
        else if (s->kind == 2) rain_blit_streak(fb, &s->rfd);
        else if (s->kind == 3) rain_blit_splash(fb, &s->rfd);
        else if (s->kind == 4) rain_blit_streak(fb, &s->rfd);
        else if (s->kind == 5) {           /* v36: the TV paint quad   */
            if (s->tri0 >= 0 && s->tri0 < g_hwNTvq)
                sh_tvpaint_draw_quad(fb, R, &g_hwTvq[s->tri0]);
        }
    }
}

/* ==========================================================================
 * the public seam — called from run_window_android's dispatch (the three
 * render_frame call sites). backend: 0 software, 1 GLES, 2 Vulkan.
 * ========================================================================*/
static int g_sh1_hw_backend = 0;    /* 0 sw (default), 1 gles, 2 vk */
static int g_sh1_hw_inited = 0;

void sh1_hw_set_backend(int backend) {
    if (backend < 0 || backend > 2) backend = 0;
    if (backend == g_sh1_hw_backend) return;
    if (g_sh1_hw_inited) {
        if (g_sh1_hw_backend == 1) gles_hw_shutdown();
        else if (g_sh1_hw_backend == 2) sh1_vk_hw_shutdown();
        g_sh1_hw_inited = 0;
    }
    g_sh1_hw_backend = backend;
}

int sh1_hw_get_backend(void) { return g_sh1_hw_backend; }

/* the render_frame substitution (also the harness's entry point) */
void sh1_hw_render(LiveApp *A, const CamPose *cam) {
    Frame *fb = &A->fb;
    Renderer *R = &A->R;
    if (g_sh1_hw_backend == 0) {
        render_frame(fb, R, cam);
        return;
    }
    /* lazy init (needs the loaded Scene + fb dims) */
    if (!g_sh1_hw_inited) {
        int ok = 0;
        if (g_sh1_hw_backend == 1) {
            ok = gles_hw_init(fb->w, fb->h, R->sc);
        } else if (g_sh1_hw_backend == 2) {
            HwTex *tex = NULL;
            int n = R->sc->texCount;
            if (n > 0) {
                tex = (HwTex *)malloc((size_t)n * sizeof(HwTex));
                if (tex) {
                    for (int i = 0; i < n; i++) {
                        tex[i].off = R->sc->tex[i].off;
                        tex[i].w = R->sc->tex[i].w;
                        tex[i].h = R->sc->tex[i].h;
                    }
                }
            }
            ok = sh1_vk_hw_init(fb->w, fb->h, R->sc->texBlob, tex, n);
            free(tex);
        }
        if (!ok) {
            HWLOGE("backend %d init failed — falling back to software",
                   g_sh1_hw_backend);
            g_sh1_hw_backend = 0;
            render_frame(fb, R, cam);
            return;
        }
        g_sh1_hw_inited = 1;
        HWLOGI("hardware backend %d initialized (%dx%d)",
               g_sh1_hw_backend, fb->w, fb->h);
    }
    /* build the batch (the engine's own walk) */
    if (!hw_build_batch(fb, R, cam)) {
        render_frame(fb, R, cam);
        return;
    }
    if (getenv("HW_DEBUG_BATCH")) {
        HWLOGI("batch: nInst=%zu nZInst=%zu (first 3):",
               g_hwBatch.nInst, g_hwBatch.nZInst);
        for (int k = 0; k < 3 && (size_t)k < g_hwBatch.nInst; k++) {
            HwVert *v = &g_hwBatch.verts[k * 3];
            HwInst *m = &g_hwBatch.insts[k];
            HWLOGI("  tri%d v0=(%.1f,%.1f z=%.2f) flags=%d dda=%d layer=%d tw=%d",
                   k, v[0].sx, v[0].sy, v[0].z, m->flags, m->useDDA, m->layer, m->texW);
        }
    }
    /* GPU render + readback into fb->rgb / fb->z */
    int ok = 0;
    if (g_sh1_hw_backend == 1)
        ok = gles_hw_render(fb, R);
    else if (g_sh1_hw_backend == 2) {
        uint8_t cr = R->fogEnabled ? R->fog->fogColor[0] : 16;
        uint8_t cg = R->fogEnabled ? R->fog->fogColor[1] : 16;
        uint8_t cb = R->fogEnabled ? R->fog->fogColor[2] : 16;
        ok = sh1_vk_hw_render(fb->rgb, fb->z, fb->w, fb->h, cr, cg, cb);
    }
    if (!ok) {
        HWLOGE("hardware frame failed — falling back to software (this frame)");
        render_frame(fb, R, cam);
        return;
    }
    /* the engine's own CPU tail: sprites (z-tested against the readback
     * z), the WorldGfx_2dEffectsDraw brightness overlay, the diag counter */
    hw_replay_sprites(fb, R);
    if (R->env && R->env->screenBrightness > 0) {
        int br5 = R->env->screenBrightness >> 3;
        uint8_t *rgb = fb->rgb;
        for (size_t i = 0; i < (size_t)fb->w * fb->h; i++) {
            int r = (rgb[i * 3] >> 3) + br5;
            int g = (rgb[i * 3 + 1] >> 3) + br5;
            int b = (rgb[i * 3 + 2] >> 3) + br5;
            if (r > 31) r = 31;
            if (g > 31) g = 31;
            if (b > 31) b = 31;
            rgb[i * 3]     = (uint8_t)((r << 3) | (r >> 2));
            rgb[i * 3 + 1] = (uint8_t)((g << 3) | (g >> 2));
            rgb[i * 3 + 2] = (uint8_t)((b << 3) | (b >> 2));
        }
    }
    g_dbgFrame++;
}

void sh1_hw_shutdown(void) {
    /* teardown ONLY the matching backend: the Swanstation loader's
     * UnloadVulkanLibrary() DECREMENTS its ref count UNCONDITIONALLY and
     * dlclose()s — calling it without a prior LoadVulkanLibrary() (e.g.
     * a pure-software boot) dlclose(NULL)s and SEGFAULTS (found by the
     * backend-switch harness, BACKEND_SEQ=sw). A half-started backend of
     * a DIFFERENT id is cleaned by its own init-failure path.          */
    if (g_sh1_hw_backend == 1) gles_hw_shutdown();
    else if (g_sh1_hw_backend == 2) sh1_vk_hw_shutdown();
    g_sh1_hw_inited = 0;
    g_sh1_hw_backend = 0;
    hw_batch_free();
}

#endif /* SH1_HW_RENDER */
#endif /* SH1_HW_RENDER_C */
