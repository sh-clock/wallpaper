/* egl_probe.c — diagnose the surfaceless EGL path under local mesa */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#ifndef EGL_PLATFORM_SURFACELESS_MESA
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#endif

int main(void) {
    printf("EGL client library: %s\n", eglQueryString(EGL_NO_DISPLAY, EGL_VERSION));
    EGLDisplay dpy = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL);
    printf("eglGetPlatformDisplay(surfaceless) = %p\n", (void *) dpy);
    if (dpy == EGL_NO_DISPLAY) {
        printf("  eglGetError = 0x%x\n", eglGetError());
    }
    if (dpy == EGL_NO_DISPLAY) {
        dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        printf("eglGetDisplay(default) = %p\n", (void *) dpy);
    }
    if (dpy == EGL_NO_DISPLAY) return 1;
    EGLint maj = 0, min = 0;
    if (!eglInitialize(dpy, &maj, &min)) {
        printf("eglInitialize failed: 0x%x\n", eglGetError());
        return 1;
    }
    printf("EGL initialized %d.%d | vendor: %s\n", maj, min,
           eglQueryString(dpy, EGL_VENDOR));
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttribs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                           EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                           EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                           EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(dpy, cfgAttribs, &cfg, 1, &n) || n < 1) {
        printf("eglChooseConfig failed: 0x%x (n=%d)\n", eglGetError(), n);
        return 1;
    }
    EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttribs);
    printf("ES3 context = %p (err 0x%x)\n", (void *) ctx, eglGetError());
    EGLint pbAttribs[] = {EGL_WIDTH, 320, EGL_HEIGHT, 224, EGL_NONE};
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pbAttribs);
    printf("pbuffer = %p (err 0x%x)\n", (void *) surf, eglGetError());
    if (eglMakeCurrent(dpy, surf, surf, ctx)) {
        printf("makeCurrent OK — GL_RENDERER = (deferred, requires a draw)\n");
        const char *v = (const char *) glGetString(GL_VENDOR);
        printf("GL_VENDOR = %s\n", v ? v : "(null)");
    } else {
        printf("makeCurrent failed: 0x%x\n", eglGetError());
    }
    return 0;
}
