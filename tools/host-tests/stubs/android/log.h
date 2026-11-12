#pragma once
#include <cstdio>
#include <cstdarg>
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6, ANDROID_LOG_DEBUG = 3 };
inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    std::fprintf(stderr, "[%d/%s] ", prio, tag); std::vfprintf(stderr, fmt, ap); std::fprintf(stderr, "\n");
    va_end(ap); return 0;
}
