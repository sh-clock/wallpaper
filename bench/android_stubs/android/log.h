/* v10.3 bench stub: android/log.h — routes the engine's logcat chatter to
 * stdout so the desktop harness output is readable. */
#ifndef SH1_BENCH_LOG_STUB_H
#define SH1_BENCH_LOG_STUB_H

#include <stdarg.h>

enum {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
};

int __android_log_print(int prio, const char *tag, const char *fmt, ...);

#endif
