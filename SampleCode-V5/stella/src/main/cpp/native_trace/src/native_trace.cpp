//
// Created by jason on 20/03/26.
//

#include "native_trace.h"

#include <jni.h>

#ifdef DEBUG

#include <android/trace.h>
#include <dlfcn.h>

#endif

static void *(*trace_begin_section)(const char *name);

static void *(*trace_end_section)();

typedef void *(*fp_trace_begin_section)(const char *section_name);

typedef void *(*fp_trace_end_section)(void);

__attribute__((constructor))
bool initialize() {
    void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);

    if (nullptr == lib) {
        return false;
    }

    trace_begin_section = reinterpret_cast<fp_trace_begin_section>(dlsym(lib, "ATrace_beginSection"));
    trace_end_section = reinterpret_cast<fp_trace_end_section>(dlsym(lib, "ATrace_endSection"));
    return true;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
#ifdef DEBUG
    JNIEnv *env = nullptr;
    jint result = JNI_VERSION_1_6;

    vm->GetEnv((void **) (&env), JNI_VERSION_1_6);
    if (env == nullptr) {
        return JNI_ERR;
    }

    if (!initialize()) {
        return JNI_ERR;
    }
#endif
    return JNI_VERSION_1_6;
}

void *trace_begin_section_wrapper(const char *name) {
#ifdef DEBUG
    return trace_begin_section(name);
#else
    return nullptr;
#endif
}

void *trace_end_section_wrapper() {
#ifdef DEBUG
    return trace_end_section();
#else
    return nullptr;
#endif
}