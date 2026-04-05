//
// Created by jason on 1/04/26.
//

#include <marker_tracker.h>
#include <opencv2/core/mat.hpp>
#include "marker_tracker_loader.h"

#include "spdlog/spdlog.h"

typedef struct context_struct {
    jclass clazz;
    jfieldID native_object_ptr;
} Context;

static Context context;


static tracker::MarkerTracker * obtain_native_tracker(jlong tracker_ptr) {
    return reinterpret_cast<tracker::MarkerTracker *>(tracker_ptr);
}

static tracker::MarkerTracker * obtain_native_tracker(JNIEnv *env, jobject thiz) {
    jlong ptr = env->GetLongField(thiz, context.native_object_ptr);
    return obtain_native_tracker(ptr);
}


static jlong create_native_object(JNIEnv *env, jobject thiz, jstring file_path) {
    auto config = new tracker::Config(std::string(env->GetStringUTFChars(file_path, JNI_FALSE)));
    auto tracker = new tracker::MarkerTracker(std::make_shared<tracker::Config>(* config));
    return reinterpret_cast<jlong>(tracker);
}

static jboolean native_process_video_frame(JNIEnv *env, jobject thiz, jlong tracker_ptr, jlong frame_ptr) {
    auto tracker = obtain_native_tracker(tracker_ptr);
    auto frame = reinterpret_cast<cv::Mat *>(frame_ptr);
    if (tracker && frame) {
        return tracker->process_frame(frame);
    }
    return false;
}

static void startup(JNIEnv *env, jobject thiz) {
    auto tracker = obtain_native_tracker(env, thiz);
    if (tracker) {
        tracker->startup();
    }
}

static void shutdown(JNIEnv *env, jobject thiz) {
    auto tracker = obtain_native_tracker(env, thiz);
    if (tracker) {
        tracker->shutdown();
    }
}

static void destroy(JNIEnv *env, jobject thiz) {
    auto tracker = obtain_native_tracker(env, thiz);
    if (tracker) {
        delete tracker;
        env->SetLongField(thiz, context.native_object_ptr, 0L);
    }
}

static jdoubleArray get_current_position(JNIEnv *env, jobject thiz) {
    auto tracker = obtain_native_tracker(env, thiz);
    if (tracker == nullptr) return nullptr;
    auto position = tracker->get_position();
    auto position_array = env->NewDoubleArray(3);
    if (position_array != nullptr) {
        env->SetDoubleArrayRegion(position_array, 0, 3, position.val);
    }
    return position_array;
}

static jdoubleArray get_current_rotation(JNIEnv *env, jobject thiz) {
    auto tracker = obtain_native_tracker(env, thiz);
    if (tracker == nullptr) return nullptr;
    auto rotation = tracker->get_rotation();
    auto rotation_array = env->NewDoubleArray(3);
    if (rotation_array != nullptr) {
        env->SetDoubleArrayRegion(rotation_array, 0, 3, rotation.val);
    }
    return rotation_array;
}

static jint get_tracking_state(JNIEnv *env, jobject thiz) {
    auto tracker = obtain_native_tracker(env, thiz);
    if (tracker == nullptr) return -1;
    return static_cast<jint>(tracker->get_tracking_state());
}


static JNINativeMethod methods[] = {
        {"createNativeObject", "(Ljava/lang/String;)J", (void *) create_native_object},
        {"nativeProcessFrame", "(JJ)Z", (void *) native_process_video_frame},
        {"getCurrentPosition", "()[D", (void *) get_current_position},
        {"getCurrentRotation", "()[D", (void *) get_current_rotation},
        {"nativeTrackingState", "()I", (void *) get_tracking_state},
        {"startup", "()V", (void *) startup},
        {"shutdown", "()V", (void *) shutdown},
        {"destroy", "()V", (void *) destroy},
};

jint bind_methods_for_marker_tracker(JNIEnv *env) {
    jclass clazz = env->FindClass("org/jason/testapp/android/stella/tracker/MarkerBasedTracker");
    if (nullptr == clazz) {
        spdlog::error("Failed to find the class: org/jason/testapp/android/stella/tracker/MarkerBasedTracker");
        return JNI_ERR;
    }

    context.clazz = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    context.native_object_ptr = env->GetFieldID(context.clazz, "trackerPtr", "J");
    if (nullptr == context.native_object_ptr) {
        spdlog::error("Failed to find the field: trackerPtr");
        return JNI_ERR;
    }

    jint result = env->RegisterNatives(context.clazz, methods, sizeof(methods) / sizeof(JNINativeMethod));
    if (result != JNI_OK) {
        context.native_object_ptr = nullptr;
        env->DeleteGlobalRef(context.clazz);
        context.clazz = nullptr;
        spdlog::error("Failed to register native method for class: MarkerBasedTracker");
        return JNI_ERR;
    }
    spdlog::info("Bind methods for module marker_tracker successfully");
    return JNI_OK;
}

void unbind_methods_for_marker_tracker(JNIEnv *env) {
    if (nullptr != context.clazz) {
        env->DeleteGlobalRef(context.clazz);
        context.clazz = nullptr;
    }
    context.native_object_ptr = nullptr;
    spdlog::info("Unbind methods for module marker_tracker successfully");
}
