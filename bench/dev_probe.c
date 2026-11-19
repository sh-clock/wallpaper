#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <stdio.h>
int main(void) {
    EGLBoolean (*queryDevices)(EGLint, EGLDeviceEXT *, EGLint *) =
        (void *) eglGetProcAddress("eglQueryDevicesEXT");
    printf("eglQueryDevicesEXT = %p\n", (void *) queryDevices);
    if (!queryDevices) return 1;
    EGLDeviceEXT devs[8]; EGLint n = 0;
    if (!queryDevices(8, devs, &n)) { printf("query failed 0x%x\n", eglGetError()); return 1; }
    printf("devices: %d\n", n);
    for (int i = 0; i < n; i++) {
        const char *(*qds)(EGLDeviceEXT, EGLint) = (void *) eglGetProcAddress("eglQueryDeviceStringEXT"); const char *ext = qds ? qds(devs[i], EGL_EXTENSIONS) : NULL;
        printf("  dev %d extensions: %s\n", i, ext ? ext : "(null)");
    }
    if (n > 0) {
        EGLDisplay d = eglGetPlatformDisplay(EGL_PLATFORM_DEVICE_EXT, devs[0], NULL);
        printf("device display = %p (err 0x%x)\n", (void*)d, eglGetError());
        EGLint maj=0,min=0;
        if (eglInitialize(d, &maj, &min)) printf("initialized %d.%d\n", maj, min);
        else printf("init failed 0x%x\n", eglGetError());
    }
    return 0;
}
