//
// Created by jason on 2/04/26.
//

#ifndef ANDROID_SDK_V5_AS_MARKER_TRACKER_H
#define ANDROID_SDK_V5_AS_MARKER_TRACKER_H

#include "opencv2/core.hpp"
#include "opencv2/objdetect/aruco_detector.hpp"

#include "yaml-cpp/yaml.h"

#include <map>

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
        ~Config();

        cv::Mat& get_camera_matrix();

        cv::Mat& get_camera_distort();

        std::map<int, std::pair<std::vector<double>, std::vector<double>>>& get_markers();

        cv::aruco::Dictionary& get_dictionary();

        cv::aruco::DetectorParameters& get_detector_params();

        float get_marker_length();

    private:
        const YAML::Node config_node;

        cv::Mat * _camera_matrix = nullptr;
        cv::Mat * _camera_distort = nullptr;
        std::map<int, std::pair<std::vector<double>, std::vector<double>>> * _markers = nullptr;
        cv::aruco::Dictionary * _dictionary = nullptr;
        cv::aruco::DetectorParameters * _detector_params = nullptr;
    };

    class MarkerTracker {

    public:
        MarkerTracker(std::shared_ptr<Config> config);

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

        // Optical flow state for short-term dead-reckoning when no marker is visible
        cv::Mat _prev_gray_frame;
        std::vector<cv::Point2f> _prev_features;
        int _frames_since_marker = 0;
        double _last_camera_depth = 1.0; // z-depth of last observed marker in camera frame (m)

        static constexpr int kMaxFlowFeatures = 200;
        // Frames before declaring LOST when no marker is seen (~3 s at 30 fps)
        static constexpr int kOpticalFlowLostThreshold = 90;
        static constexpr float kMaxFlowError = 20.0f;
        static constexpr int kMinTrackedFeatures = 8;
    };
}

#endif //ANDROID_SDK_V5_AS_MARKER_TRACKER_H
