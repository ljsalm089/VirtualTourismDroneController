#ifndef COMMON_H
#define COMMON_H

#define DEBUG

#ifdef DEBUG
#include <android/log.h>

#define D(tag, fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, tag, fmt, ##__VA_ARGS__)
#define I(tag, fmt, ...) __android_log_print(ANDROID_LOG_INFO, tag, fmt, ##__VA_ARGS__)
#define E(tag, fmt, ...) __android_log_print(ANDROID_LOG_ERROR, tag, fmt, ##__VA_ARGS__)
#define W(tag, fmt, ...) __android_log_print(ANDROID_LOG_WARN, tag, fmt, ##__VA_ARGS__)
#define Verbose(tag, fmt, ...) __android_log_print(ANDROID_LOG_VERBOSE, tag, fmt, ##__VA_ARGS__)
#define Fatal(tag, fmt, ...) __android_log_print(ANDROID_LOG_FATAL, tag, fmt, ##__VA_ARGS__)

#else

#define D(tag, ...)
#define I(tag, ...)
#define E(tag, ...)
#define W(tag, ...)
#define V(tag, ...)
#define F(tag, ...)

#endif

#endif // COMMON_H
