#ifndef DEMO_HPP
#define DEMO_HPP

#include "common.h"

#include <jni.h>


jint bind_methods_for_tracker(JNIEnv * env);


void unbind_methods_for_tracker(JNIEnv *env);

#endif // DEMO_HPP
