//
// Created by jason on 2/04/26.
//

#ifndef ANDROID_SDK_V5_AS_MARKER_TRACKER_H
#define ANDROID_SDK_V5_AS_MARKER_TRACKER_H

#include "opencv2/core.hpp"
#include "opencv2/objdetect/aruco_detector.hpp"

#include "yaml-cpp/yaml.h"

namespace tracker {

    enum TrackingState {
        INITIALIZING = 0,
        TRACKING = 1,
        LOST = 2,
        INVALID = -1
    };

    class Config {
    public:
        Config(const std::string & config_file_path);

        cv::Mat& get_camera_matrix();

        cv::Mat& get_camera_distort();

    private:
        const YAML::Node config_node;

        cv::Mat * camera_matrix = nullptr;
        cv::Mat * camera_distort = nullptr;
    };

    class MarkerTracker {

    public:
        bool initialize(std::shared_ptr<Config> config);

        bool process_frame(cv::Mat * frame);

        TrackingState get_tracking_state();

        const cv::Vec3d & get_rotation();

        const cv::Vec3d & get_position();

        void startup();

        void shutdown();

    private:
        std::shared_ptr<Config> _config;
        TrackingState _tracking_state = TrackingState::INITIALIZING;
        cv::Vec3d _position = cv::Vec3d(0, 0, 0);
        cv::Vec3d _rotation = cv::Vec3d(0, 0, 0);

        cv::aruco::Dictionary dictionary;
        cv::aruco::DetectorParameters detector_params;
        float marker_length = 0.1f; // 10cm by default
    };
}

#endif //ANDROID_SDK_V5_AS_MARKER_TRACKER_H
