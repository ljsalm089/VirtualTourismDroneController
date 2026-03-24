//
// Created by Json.Li on 17/02/2026.
//


#include <iostream>

#include "jni.h"
#include "tracker_loader.hpp"

#include "native_trace.h"

#define TAG "StellaNative"

void initialize(JNIEnv *env, jobject thiz) {
}


void destroy(JNIEnv *env, jobject thiz) {

}


const JNINativeMethod methods[] = {
        {"initialize", "()V", reinterpret_cast<void *>(initialize)},
        {"destroy", "()V", reinterpret_cast<void *>(destroy)}
};


JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    D(TAG, "JNI_OnLoad");

    JNIEnv *env;
    jclass native_class;

    jint result = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (result != JNI_OK) {
        E(TAG, "ERROR: GetEnv failed in JNI_Onload");
        goto _return;
    }

    native_class = env->FindClass("org/jason/testapp/android/stella/StellaNative");
    if (native_class == nullptr) {
        E(TAG, "ERROR: FindClass failed in JNI_Onload");
        return -1;
    }

    result = env->RegisterNatives(native_class, methods, sizeof(methods) / sizeof(JNINativeMethod));
    if (result != JNI_OK) {
        E(TAG, "ERROR: RegisterNatives failed in JNI_Onload");
        goto _return;
    }

    TRACE_BEGIN("bind_methods_for_tracker");
    result = bind_methods_for_tracker(env);
    TRACE_END();
    if (JNI_OK != result) {
        goto _unbind_methods;
    } else {
        result = JNI_VERSION_1_6;
        goto _return;
    }

    _unbind_methods:
    env->UnregisterNatives(native_class);

    _return:
    return result;
}


JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (JNI_OK == vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6)) {
        unbind_methods_for_tracker(env);
    }
}
