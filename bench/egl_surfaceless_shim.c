/* egl_surfaceless_shim.c — v10.0 battery harness EGL platform shim.
 *
 * The engine's GLES backend (sh1_hw_render.c, ZERO-DELTA for this bench)
 * requests the Mesa surfaceless platform via eglGetPlatformDisplay(
 * EGL_PLATFORM_SURFACELESS_MESA). This sandbox's glvnd build rejects that
 * platform before it reaches Mesa, and there is no X11/Wayland/DRM at all.
 * Mesa's software path IS reachable through the EGL DEVICE platform with
 * the always-present "llvmpipe" software EGLDevice.
 *
 * This shim interposes ONLY the surfaceless-MESA call and re-routes it to
 * the first EGLDevice (eglQueryDevicesEXT -> EGL_PLATFORM_DEVICE_EXT).
 * Every other call passes through to the real glvnd entrypoint. Loaded
 * with LD_PRELOAD; the engine binary is untouched.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <dlfcn.h>
#include <stddef.h>

#ifndef EGL_PLATFORM_SURFACELESS_MESA
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#endif
#ifndef EGL_PLATFORM_DEVICE_EXT
#define EGL_PLATFORM_DEVICE_EXT 0x313F
#endif

static EGLDisplay (*real_gpd)(EGLenum, void *, const EGLAttrib *);

static void load_real(void) {
    if (real_gpd == NULL) {
        real_gpd = (EGLDisplay (*)(EGLenum, void *, const EGLAttrib *))
            dlsym(RTLD_NEXT, "eglGetPlatformDisplay");
    }
}

EGLDisplay eglGetPlatformDisplay(EGLenum platform, void *native_display,
                                  const EGLAttrib *attrib_list) {
    load_real();
    if (platform == EGL_PLATFORM_SURFACELESS_MESA && real_gpd != NULL) {
        EGLBoolean (*queryDevices)(EGLint, EGLDeviceEXT *, EGLint *)
            = (EGLBoolean (*)(EGLint, EGLDeviceEXT *, EGLint *))
                eglGetProcAddress("eglQueryDevicesEXT");
        if (queryDevices != NULL) {
            EGLDeviceEXT devs[8];
            EGLint n = 0;
            if (queryDevices(8, devs, &n) && n > 0) {
                EGLDisplay d = real_gpd(EGL_PLATFORM_DEVICE_EXT, devs[0], NULL);
                if (d != EGL_NO_DISPLAY) {
                    return d;
                }
            }
        }
    }
    return real_gpd != NULL
        ? real_gpd(platform, native_display, attrib_list) : EGL_NO_DISPLAY;
}
