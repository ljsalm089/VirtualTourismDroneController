#include "tracker_loader.hpp"
#include "common.h"

#include "stella_vslam/system.h"
#include "stella_vslam/config.h"
#include "stella_vslam/publish/map_publisher.h"
#include "stella_vslam/publish/frame_publisher.h"
#include "opencv2/core.hpp"

#include "spdlog/spdlog.h"
#include "spdlog/sinks/android_sink.h"

#include "Eigen/Eigen"

#include "native_trace.h"

#include <string>
#include <chrono>
#include <algorithm>
#include <cmath>

#define TAG "NativeTracker"

typedef struct _Context {
    jclass tracker_clazz = nullptr;
    jmethodID tracker_ptr = nullptr;

    std::shared_ptr<spdlog::logger> logger;
} Context;

static Context context;

typedef struct _Tracker {
    std::shared_ptr<stella_vslam::config> config;
    stella_vslam::system * tracker;
    uint64_t timestamp;

    cv::Mat processed_frame;

} Tracker;

static Tracker * obtain_tracker_struct(jlong tracker_struct_ptr) {
    return reinterpret_cast<Tracker *>(tracker_struct_ptr);
}

static Tracker * obtain_tracker_struct(JNIEnv*env, jobject thiz) {
    jlong ptr = env->CallLongMethod(thiz, context.tracker_ptr);
    return obtain_tracker_struct(ptr);
}

static stella_vslam::system *obtain_tracker(jlong tracker_ptr) {
    return obtain_tracker_struct(tracker_ptr)->tracker;
}

static stella_vslam::system *obtain_tracker(JNIEnv *env, jobject thiz) {
    jlong ptr = env->CallLongMethod(thiz, context.tracker_ptr);
    return obtain_tracker(ptr);
}

static stella_vslam::config *obtain_config(JNIEnv *env, jobject thiz) {
    return obtain_tracker_struct(env, thiz)->config.get();
}

static stella_vslam::config * create_config_tracker(JNIEnv *env, jobject thiz, jstring config_path) {
    auto config_path_str = std::string(env->GetStringUTFChars(config_path, nullptr));
    return new stella_vslam::config(config_path_str);
}

static jlong create_native_tracker(JNIEnv *env, jobject thiz, jstring config_file_path, jstring vocabulary_file_path) {
    auto start_time = std::chrono::high_resolution_clock::now();

    stella_vslam::config * config = nullptr;
    try {
        config = create_config_tracker(env, thiz, config_file_path);
    } catch (std::runtime_error &e) {
        E(TAG, "failed to read config from file (%s): %s\n", env->GetStringUTFChars(config_file_path, nullptr), e.what());
    }
    if (nullptr == config) {
        return 0;
    }
    std::shared_ptr<stella_vslam::config> config_ptr(config);
    stella_vslam::system * tracker = nullptr;
    try{
        tracker = new stella_vslam::system(config_ptr, std::string(env->GetStringUTFChars(vocabulary_file_path, nullptr)));
    } catch (std::runtime_error &e) {
        E(TAG, "failed to create system: %s\n", e.what());
    }

    auto end_time = std::chrono::high_resolution_clock::now();
    D(TAG, "the initialization time: %lld ms\n", (end_time - start_time) / std::chrono::milliseconds(1));

    if (nullptr == tracker) {
        delete config;
        return 0;
    }
    auto tracker_struct = new Tracker {
            .config = config_ptr,
            .tracker = tracker,
            .timestamp = 0
    };
    I(TAG, "created native tracker successfully\n");
    return reinterpret_cast<jlong>(tracker_struct);
}

static void destroy(JNIEnv *env, jobject thiz) {
    auto tracker_struct = obtain_tracker_struct(env, thiz);

    auto tracker = tracker_struct->tracker;
    delete tracker;

    delete tracker_struct;
}

static jboolean save_map_database(JNIEnv *env, jobject thiz, jstring db_path) {
    auto tracker = obtain_tracker(env, thiz);
    auto db_file_path = std::string(env->GetStringUTFChars(db_path, nullptr));

    return tracker->save_map_database(db_file_path) ? JNI_TRUE : JNI_FALSE;
}

static jboolean load_map_database(JNIEnv *env, jobject thiz, jstring db_path) {
    auto tracker = obtain_tracker(env, thiz);
    auto db_file_path = std::string(env->GetStringUTFChars(db_path, nullptr));

    return tracker->load_map_database(db_file_path) ? JNI_TRUE : JNI_FALSE;
}

static jboolean is_mapping_module_enabled(JNIEnv *env, jobject thiz) {
    auto tracker = obtain_tracker(env, thiz);
    return tracker->mapping_module_is_enabled() ? JNI_TRUE : JNI_FALSE;
}

static void set_mapping_module(JNIEnv *env, jobject thiz, jboolean enabled) {
    auto tracker = obtain_tracker(env, thiz);
    if (enabled) {
        tracker->enable_mapping_module();
    } else {
        tracker->disable_mapping_module();
    }
}

static jboolean is_loop_detector_enabled(JNIEnv *env, jobject thiz) {
    auto tracker_ptr = obtain_tracker(env, thiz);
    return tracker_ptr->loop_detector_is_enabled();
}

static void set_loop_detector(JNIEnv *env, jobject thiz, jboolean enabled) {
    auto tracker_ptr = obtain_tracker(env, thiz);
    if (enabled) {
        tracker_ptr->enable_loop_detector();
    } else {
        tracker_ptr->disable_loop_detector();
    }
}

static void process_frame(JNIEnv *env, jobject thiz, jlong tracker_ptr, jlong
                                                                            frame_ptr, jdouble frame_timestamp_in_seconds) {
    TRACE_FUNC_WITH_NAME("native_process_frame");
    spdlog::debug("Tracing native_process_frame");
    auto track_struct = obtain_tracker_struct(tracker_ptr);
    auto tracker = track_struct->tracker;
    auto frame = reinterpret_cast<cv::Mat *>(frame_ptr);

    tracker->feed_monocular_frame(*frame, frame_timestamp_in_seconds);
}

static jlong obtain_processed_frame(JNIEnv *env, jobject thiz, jlong tracker_ptr) {
    auto tracker_struct = obtain_tracker_struct(tracker_ptr);
    auto tracker = tracker_struct->tracker;
    auto frame_publisher = tracker->get_frame_publisher().get();
    tracker_struct->processed_frame = frame_publisher->draw_frame();
    return reinterpret_cast<jlong>(&tracker_struct->processed_frame);
}

static jboolean relocalize_camera_pose(JNIEnv *env, jobject thiz, jdoubleArray new_pose) {
    auto tracker_ptr = obtain_tracker(env, thiz);
    if (!tracker_ptr) {
        return JNI_FALSE;
    }

    jdouble* elements = env->GetDoubleArrayElements(new_pose, nullptr);
    if (!elements) {
        return JNI_FALSE;
    }

    // elements: [x, y, z, pitch_deg, yaw_deg, roll_deg]
    stella_vslam::Vec3_t translation(elements[0], elements[1], elements[2]);
    double pitch = elements[3] * (M_PI / 180.0);
    double yaw   = elements[4] * (M_PI / 180.0);
    double roll  = elements[5] * (M_PI / 180.0);

    // Reconstruct rotation matrix from Euler angles in XYZ order
    stella_vslam::Mat33_t rotation_mat;
    rotation_mat = Eigen::AngleAxisd(pitch, stella_vslam::Vec3_t::UnitX())
                 * Eigen::AngleAxisd(yaw,   stella_vslam::Vec3_t::UnitY())
                 * Eigen::AngleAxisd(roll,  stella_vslam::Vec3_t::UnitZ());

    stella_vslam::Mat44_t cam_pose_wc = stella_vslam::Mat44_t::Identity();
    cam_pose_wc.block<3, 3>(0, 0) = rotation_mat;
    cam_pose_wc.block<3, 1>(0, 3) = translation;

    env->ReleaseDoubleArrayElements(new_pose, elements, JNI_ABORT);

    return tracker_ptr->relocalize_by_pose(cam_pose_wc) ? JNI_TRUE : JNI_FALSE;
}

static void startup(JNIEnv *env, jobject thiz) {
    D(TAG, "start up the tracking system\n");
    auto tracker_ptr = obtain_tracker(env, thiz);
    tracker_ptr->startup();
}

static void shutdown(JNIEnv *env, jobject thiz) {
    D(TAG, "shutdown the tracking system\n");
    auto tracker_ptr = obtain_tracker(env, thiz);
    tracker_ptr->shutdown();
}

static jdoubleArray get_current_position_rotation(JNIEnv *env, jobject thiz) {
//    D(TAG, "get current position and rotation from the tracking system\n");
    auto tracker_ptr = obtain_tracker(env, thiz);
    if (!tracker_ptr) {
        return nullptr;
    }

    auto map_publisher = tracker_ptr->get_map_publisher();
    if (!map_publisher) {
        return nullptr;
    }

    // get_current_cam_pose() returns cam_pose_cw (World to Camera)
    const stella_vslam::Mat44_t cam_pose_cw = map_publisher->get_current_cam_pose();

    // Convert to cam_pose_wc (Camera to World)
    const stella_vslam::Mat44_t cam_pose_wc = cam_pose_cw.inverse();

    const stella_vslam::Vec3_t translation = cam_pose_wc.block<3, 1>(0, 3);
    const stella_vslam::Mat33_t rotation_mat = cam_pose_wc.block<3, 3>(0, 0);

    // Extract intrinsic XYZ Euler angles (R = Rx·Ry·Rz) directly from the
    // rotation matrix elements.
    //
    // Eigen's eulerAngles(0,1,2) is NOT used here because it returns angles in
    // [0, π] for the middle axis and can flip by ±π discontinuously near
    // singularities, even for a physically smooth rotation.
    //
    // From the matrix expansion of R = Rx(pitch)·Ry(yaw)·Rz(roll):
    //   R(0,2) = -sin(yaw)
    //   R(1,2) =  cos(yaw)·sin(pitch)    R(2,2) = cos(yaw)·cos(pitch)
    //   R(0,1) =  cos(yaw)·sin(roll)     R(0,0) = cos(yaw)·cos(roll)
    //
    // atan2 returns values in (-π, π] and is continuous everywhere it is
    // well-defined.  The only true singularity is yaw = ±90° (gimbal lock),
    // handled explicitly below.
    const double sin_yaw = std::clamp(-rotation_mat(0, 2), -1.0, 1.0);
    const double cos_yaw = std::sqrt(std::max(0.0, 1.0 - sin_yaw * sin_yaw));

    const double yaw_rad = std::asin(sin_yaw);
    double pitch_rad, roll_rad;

    if (cos_yaw > 1e-6) {
        // Normal case: both pitch and roll are uniquely recoverable.
        pitch_rad = std::atan2(rotation_mat(1, 2), rotation_mat(2, 2));
        roll_rad  = std::atan2(rotation_mat(0, 1), rotation_mat(0, 0));
    } else {
        // Gimbal lock (yaw ≈ ±90°): pitch and roll become coupled.
        // Capture the combined DoF in pitch and set roll to 0.
        pitch_rad = std::atan2(-rotation_mat(1, 0), rotation_mat(1, 1));
        roll_rad  = 0.0;
    }

    jdoubleArray result = env->NewDoubleArray(6);
    if (result == nullptr) {
        return nullptr;
    }

    jdouble elements[6];
    elements[0] = translation(0);
    elements[1] = translation(1);
    elements[2] = translation(2);
    // Convert to degrees
    elements[3] = pitch_rad * (180.0 / M_PI); // Pitch (X-axis)
    elements[4] = yaw_rad   * (180.0 / M_PI); // Yaw   (Y-axis)
    elements[5] = roll_rad  * (180.0 / M_PI); // Roll  (Z-axis)

    env->SetDoubleArrayRegion(result, 0, 6, elements);

    return result;
}

static jint get_tracking_state(JNIEnv *env, jobject thiz, jlong ptr) {
//    D(TAG, "get tracking state of the tracking system\n");
    spdlog::debug("get tracking state of the tracking system\n");
    auto tracker_ptr = obtain_tracker(ptr);
    if (!tracker_ptr) {
        return -1;
    }
    return static_cast<jint>(tracker_ptr->get_tracker_state());
}

static JNINativeMethod methods[] = {
        {"createNativeTracker", "(Ljava/lang/String;Ljava/lang/String;)J", (void *) create_native_tracker},
        {"destroy", "()V", (void *) destroy},
        {"saveMapDatabase", "(Ljava/lang/String;)Z", (void *) save_map_database},
        {"loadMapDatabase", "(Ljava/lang/String;)Z", (void *) load_map_database},
        {"isMappingModuleEnabled", "()Z", (void *) is_mapping_module_enabled},
        {"setMappingModule", "(Z)V", (void *) set_mapping_module},
        {"isLoopDetectorEnabled", "()Z", (void *) is_loop_detector_enabled},
        {"setLoopDetector", "(Z)V", (void *) set_loop_detector},
        {"startup", "()V", (void *) startup},
        {"shutdown", "()V", (void *) shutdown},
        {"nativeProcessFrame", "(JJD)V", (void *) process_frame},
        {"relocalizeCameraPose", "([D)Z", (void *) relocalize_camera_pose},
        {"getPositionAndRotation", "()[D", (void *) get_current_position_rotation},
        {"nativeTrackingState", "(J)I", (void *) get_tracking_state},
        {"nativeGetProcessedFrame", "(J)J", (void *) obtain_processed_frame}
};

jint bind_methods_for_tracker(JNIEnv *env) {
    D(TAG, "bind methods for tracker");
    jint result = JNI_OK;
    jclass tracker_clazz = env->FindClass("org/jason/testapp/android/stella/tracker/VSlamTracker");
    auto logger = spdlog::android_logger_mt(TAG);

    if (tracker_clazz == nullptr) {
        return -1;
    }
    context.tracker_clazz = reinterpret_cast<jclass>(env->NewGlobalRef(tracker_clazz));

    context.tracker_ptr = env->GetMethodID(context.tracker_clazz, "getTracker", "()J");
    if (nullptr == context.tracker_ptr) {
        E(TAG, "failed to obtain the method: getTracker\n");
        result = -1;
        goto _release_clazz;
    }

    result = env->RegisterNatives(tracker_clazz, methods, sizeof(methods) / sizeof(JNINativeMethod));
    if (result != JNI_OK) {
        E(TAG, "failed to register native method for class: VSLamTracker\n");
        goto _release_clazz;
    } else {
        goto _return;
    }

    spdlog::set_default_logger(logger);


    _release_clazz:
    env->DeleteGlobalRef(context.tracker_clazz);

    _return:
    return result;
}

void unbind_methods_for_tracker(JNIEnv *env) {

}
