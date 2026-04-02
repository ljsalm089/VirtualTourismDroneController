//
// Created by jason on 2/04/26.
//


#include "marker_tracker.h"

#include <opencv2/calib3d.hpp>
#include <opencv2/imgproc.hpp>
#include <vector>



namespace tracker {

    Config::Config(const std::string &config_file_path): config_node(YAML::Load(config_file_path)) {
    }

    cv::Mat & Config::get_camera_distort() {
        if (nullptr == camera_distort) {
            auto values = config_node["camera"]["distortion"].as<std::vector<double>>();
            if (values.size() != 5) {
                throw std::runtime_error("the size of camera distortion is not 5");
            }
            camera_distort = new cv::Mat(1, 5, CV_64F, values.data());
        }
        return * camera_distort;
    }

    cv::Mat & Config::get_camera_matrix() {
        if (nullptr == camera_matrix) {
            auto values = config_node["camera"]["intrinsic"].as<std::vector<double>>();
            if (values.size() != 9) {
                throw std::runtime_error("the size of camera matrix is not 9");
            }
            camera_matrix = new cv::Mat(3, 3, CV_64F, values.data());
        }
        return * camera_matrix;
    }

    bool MarkerTracker::initialize(std::shared_ptr<Config> config) {
        return true;
    }

    bool MarkerTracker::process_frame(cv::Mat *frame) {
        if (frame == nullptr || frame->empty()) {
            _tracking_state = TrackingState::LOST;
            return false;
        }

        // Convert the input frame to grayscale if necessary
        cv::Mat gray_frame;
        if (frame->channels() == 3) {
            cv::cvtColor(*frame, gray_frame, cv::COLOR_BGR2GRAY);
        } else if (frame->channels() == 4) {
            cv::cvtColor(*frame, gray_frame, cv::COLOR_BGRA2GRAY);
        } else {
            gray_frame = *frame;
        }

        std::vector<int> marker_ids;
        std::vector<std::vector<cv::Point2f>> marker_corners, rejected_candidates;

        // Initialize dictionary if not already
        if (dictionary.bytesList.empty()) {
             dictionary = cv::aruco::getPredefinedDictionary(cv::aruco::DICT_6X6_250);
             detector_params = cv::aruco::DetectorParameters();
        }

        cv::aruco::ArucoDetector detector(dictionary, detector_params);
        detector.detectMarkers(gray_frame, marker_corners, marker_ids, rejected_candidates);

        if (marker_ids.empty()) {
            _tracking_state = TrackingState::LOST;
            return false;
        }

        // Define the 3D coordinates of the marker corners in its own coordinate system
        // The marker is in the XY plane, centered at (0,0,0)
        std::vector<cv::Point3f> marker_obj_points;
        marker_obj_points.emplace_back(-marker_length / 2.f, marker_length / 2.f, 0);
        marker_obj_points.emplace_back(marker_length / 2.f, marker_length / 2.f, 0);
        marker_obj_points.emplace_back(marker_length / 2.f, -marker_length / 2.f, 0);
        marker_obj_points.emplace_back(-marker_length / 2.f, -marker_length / 2.f, 0);

        // For simplicity, we use the first detected marker
        int target_idx = 0;

        cv::Vec3d rvec, tvec;
        bool success = cv::solvePnP(marker_obj_points, marker_corners[target_idx], _config->get_camera_matrix(), _config->get_camera_distort(), rvec, tvec);

        if (success) {
            // Pose of marker in camera coordinate system: [R_cm | T_cm]
            // R_cm = Rodrigues(rvec), T_cm = tvec.
            // Camera pose in marker coordinate system (where marker is at origin):
            // R_mc = R_cm.T
            // T_mc = -R_mc * T_cm

            cv::Mat R_cm;
            cv::Rodrigues(rvec, R_cm);

            cv::Mat R_mc = R_cm.t();
            cv::Mat T_cm = cv::Mat(tvec);
            cv::Mat T_mc = -R_mc * T_cm;

            // Extract position
            _position = cv::Vec3d(T_mc.at<double>(0), T_mc.at<double>(1), T_mc.at<double>(2));

            // Extract rotation as rotation vector
            cv::Mat rvec_mc;
            cv::Rodrigues(R_mc, rvec_mc);
            _rotation = cv::Vec3d(rvec_mc.at<double>(0), rvec_mc.at<double>(1), rvec_mc.at<double>(2));

            _tracking_state = TrackingState::TRACKING;
            return true;
        } else {
            _tracking_state = TrackingState::LOST;
            return false;
        }
    }

    const cv::Vec3d &MarkerTracker::get_position() {
        return _position;
    }

    const cv::Vec3d &MarkerTracker::get_rotation() {
        return _rotation;
    }

    TrackingState MarkerTracker::get_tracking_state() {
        return _tracking_state;
    }

    void MarkerTracker::startup() {
        _position = cv::Vec3d(0, 0, 0);
        _rotation = cv::Vec3d(0, 0, 0);
        _tracking_state = TrackingState::INITIALIZING;

        dictionary = cv::aruco::getPredefinedDictionary(cv::aruco::DICT_6X6_250);
        detector_params = cv::aruco::DetectorParameters();
        marker_length = 0.1f;
    }

    void MarkerTracker::shutdown() {
        _tracking_state = TrackingState::INITIALIZING;
        _position = cv::Vec3d(0, 0, 0);
        _rotation = cv::Vec3d(0, 0, 0);
    }
}
