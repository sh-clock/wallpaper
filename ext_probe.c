#include <EGL/egl.h>
#include <stdio.h>
int main(void) {
    const char *c = eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS);
    printf("client extensions: %s\n", c ? c : "(null)");
    const char *v = eglQueryString(EGL_NO_DISPLAY, EGL_VENDOR);
    printf("client vendors: %s\n", v ? v : "(null)");
    return 0;
}
