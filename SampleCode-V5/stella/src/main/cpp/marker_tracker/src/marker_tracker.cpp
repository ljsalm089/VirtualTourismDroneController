//
// Created by jason on 2/04/26.
//


#include "marker_tracker.h"

#include <opencv2/calib3d.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#include <vector>



namespace tracker {

    Config::Config(const std::string &config_file_path): config_node(YAML::LoadFile(config_file_path)) {
    }

    Config::~Config() {
        delete _camera_matrix;
        delete _camera_distort;
        delete _markers;
        delete _dictionary;
        delete _detector_params;
    }

    cv::Mat & Config::get_camera_distort() {
        if (nullptr == _camera_distort) {
            auto camera_node = config_node["Camera"];
            if (!camera_node) camera_node = config_node["camera"];

            auto values = camera_node["distortion"].as<std::vector<double>>();
            if (values.size() != 5) {
                throw std::runtime_error("the size of camera distortion is not 5");
            }
            _camera_distort = new cv::Mat(1, 5, CV_64F);
            std::copy(values.begin(), values.end(), _camera_distort->ptr<double>());
        }
        return * _camera_distort;
    }

    cv::Mat & Config::get_camera_matrix() {
        if (nullptr == _camera_matrix) {
            auto camera_node = config_node["Camera"];
            if (!camera_node) camera_node = config_node["camera"];

            auto values = camera_node["intrinsic"].as<std::vector<double>>();
            if (values.size() != 9) {
                throw std::runtime_error("the size of camera matrix is not 9");
            }
            _camera_matrix = new cv::Mat(3, 3, CV_64F);
            std::copy(values.begin(), values.end(), _camera_matrix->ptr<double>());
        }
        return * _camera_matrix;
    }

    std::map<int, std::pair<std::vector<double>, std::vector<double>>> & Config::get_markers() {
        if (nullptr == _markers) {
            _markers = new std::map<int, std::pair<std::vector<double>, std::vector<double>>>();
            auto markers_node = config_node["Markers"];
            if (!markers_node) {
                markers_node = config_node["markers"];
            }

            if (markers_node && markers_node.IsSequence()) {
                for (const auto& node : markers_node) {
                    int id = node["id"].as<int>();
                    auto pos = node["position"].as<std::vector<double>>();
                    auto rot = node["rotation"].as<std::vector<double>>();
                    (*_markers)[id] = std::make_pair(std::move(pos), std::move(rot));
                }
            }
        }
        return * _markers;
    }

    cv::aruco::Dictionary& Config::get_dictionary() {
        if (nullptr == _dictionary) {
            auto marker_node = config_node["Marker"];
            if (!marker_node) marker_node = config_node["marker"];

            int dict_id = cv::aruco::DICT_6X6_250; // default
            if (marker_node && marker_node["dictionary"]) {
                static const std::map<std::string, int> dict_name_map = {
                    {"DICT_4X4_50",   cv::aruco::DICT_4X4_50},
                    {"DICT_4X4_100",  cv::aruco::DICT_4X4_100},
                    {"DICT_4X4_250",  cv::aruco::DICT_4X4_250},
                    {"DICT_4X4_1000", cv::aruco::DICT_4X4_1000},
                    {"DICT_5X5_50",   cv::aruco::DICT_5X5_50},
                    {"DICT_5X5_100",  cv::aruco::DICT_5X5_100},
                    {"DICT_5X5_250",  cv::aruco::DICT_5X5_250},
                    {"DICT_5X5_1000", cv::aruco::DICT_5X5_1000},
                    {"DICT_6X6_50",   cv::aruco::DICT_6X6_50},
                    {"DICT_6X6_100",  cv::aruco::DICT_6X6_100},
                    {"DICT_6X6_250",  cv::aruco::DICT_6X6_250},
                    {"DICT_6X6_1000", cv::aruco::DICT_6X6_1000},
                    {"DICT_7X7_50",   cv::aruco::DICT_7X7_50},
                    {"DICT_7X7_100",  cv::aruco::DICT_7X7_100},
                    {"DICT_7X7_250",  cv::aruco::DICT_7X7_250},
                    {"DICT_7X7_1000", cv::aruco::DICT_7X7_1000},
                };
                std::string dict_name = marker_node["dictionary"].as<std::string>();
                auto it = dict_name_map.find(dict_name);
                if (it != dict_name_map.end()) {
                    dict_id = it->second;
                }
            }
            _dictionary = new cv::aruco::Dictionary(
                cv::aruco::getPredefinedDictionary(dict_id));
        }
        return *_dictionary;
    }

    cv::aruco::DetectorParameters& Config::get_detector_params() {
        if (nullptr == _detector_params) {
            _detector_params = new cv::aruco::DetectorParameters();

            auto marker_node = config_node["Marker"];
            if (!marker_node) marker_node = config_node["marker"];

            if (marker_node && marker_node["detector_params"]) {
                auto params_node = marker_node["detector_params"];
                if (params_node["adaptiveThreshWinSizeMin"])
                    _detector_params->adaptiveThreshWinSizeMin =
                        params_node["adaptiveThreshWinSizeMin"].as<int>();
                if (params_node["adaptiveThreshWinSizeMax"])
                    _detector_params->adaptiveThreshWinSizeMax =
                        params_node["adaptiveThreshWinSizeMax"].as<int>();
                if (params_node["adaptiveThreshWinSizeStep"])
                    _detector_params->adaptiveThreshWinSizeStep =
                        params_node["adaptiveThreshWinSizeStep"].as<int>();
                if (params_node["adaptiveThreshConstant"])
                    _detector_params->adaptiveThreshConstant =
                        params_node["adaptiveThreshConstant"].as<double>();
                if (params_node["minMarkerPerimeterRate"])
                    _detector_params->minMarkerPerimeterRate =
                        params_node["minMarkerPerimeterRate"].as<double>();
                if (params_node["maxMarkerPerimeterRate"])
                    _detector_params->maxMarkerPerimeterRate =
                        params_node["maxMarkerPerimeterRate"].as<double>();
                if (params_node["polygonalApproxAccuracyRate"])
                    _detector_params->polygonalApproxAccuracyRate =
                        params_node["polygonalApproxAccuracyRate"].as<double>();
                if (params_node["minCornerDistanceRate"])
                    _detector_params->minCornerDistanceRate =
                        params_node["minCornerDistanceRate"].as<double>();
                if (params_node["minDistanceToBorder"])
                    _detector_params->minDistanceToBorder =
                        params_node["minDistanceToBorder"].as<int>();
                if (params_node["errorCorrectionRate"])
                    _detector_params->errorCorrectionRate =
                        params_node["errorCorrectionRate"].as<double>();
            }
        }
        return *_detector_params;
    }

    float Config::get_marker_length() {
        auto marker_node = config_node["Marker"];
        if (!marker_node) marker_node = config_node["marker"];

        if (marker_node && marker_node["length"]) {
            return marker_node["length"].as<float>();
        }
        return 0.1f; // default 10 cm
    }

    MarkerTracker::MarkerTracker(std::shared_ptr<Config> config): _config(std::move(config)) {

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

        if (!marker_ids.empty()) {
            // ----------------------------------------------------------------
            // ArUco marker detected – compute absolute pose via PnP
            // ----------------------------------------------------------------

            // Define the 3D coordinates of the marker corners in its own coordinate system
            // The marker is in the XY plane, centered at (0,0,0)
            std::vector<cv::Point3f> marker_obj_points;
            marker_obj_points.emplace_back(-marker_length / 2.f, marker_length / 2.f, 0);
            marker_obj_points.emplace_back(marker_length / 2.f, marker_length / 2.f, 0);
            marker_obj_points.emplace_back(marker_length / 2.f, -marker_length / 2.f, 0);
            marker_obj_points.emplace_back(-marker_length / 2.f, -marker_length / 2.f, 0);

            // Prefer a marker with a known world pose from config; fall back to the first detected
            auto& known_markers = _config->get_markers();
            int target_idx = 0;
            int target_id = marker_ids[0];
            for (int i = 0; i < static_cast<int>(marker_ids.size()); i++) {
                if (known_markers.count(marker_ids[i])) {
                    target_idx = i;
                    target_id = marker_ids[i];
                    break;
                }
            }

            cv::Vec3d rvec, tvec;
            bool success = cv::solvePnP(marker_obj_points, marker_corners[target_idx],
                                        _config->get_camera_matrix(), _config->get_camera_distort(),
                                        rvec, tvec);

            if (success) {
                // Save the z-distance from camera to marker; used later as optical-flow depth scale
                _last_camera_depth = std::max(tvec[2], 0.1);

                // Pose of marker in camera coordinate system: [R_cm | T_cm]
                // R_cm = Rodrigues(rvec), T_cm = tvec.
                // Camera pose in marker local coordinate system:
                // R_mc = R_cm.T,  T_mc = -R_mc * T_cm
                cv::Mat R_cm;
                cv::Rodrigues(rvec, R_cm);

                cv::Mat R_mc = R_cm.t();
                cv::Mat T_cm = cv::Mat(tvec);
                cv::Mat T_mc = -R_mc * T_cm;  // drone/camera position in marker-local frame

                if (known_markers.count(target_id)) {
                    // Transform camera pose from marker-local frame to world frame using the
                    // marker's known world pose (position T_wm, rotation R_wm).
                    // T_world = R_wm * T_mc + T_wm
                    // R_wc    = R_wm * R_mc
                    const auto& [pos_wm, rot_wm] = known_markers.at(target_id);

                    cv::Mat R_wm;
                    cv::Rodrigues(cv::Vec3d(rot_wm[0], rot_wm[1], rot_wm[2]), R_wm);
                    cv::Mat T_wm = (cv::Mat_<double>(3, 1) << pos_wm[0], pos_wm[1], pos_wm[2]);

                    cv::Mat T_world = R_wm * T_mc + T_wm;
                    _position = cv::Vec3d(T_world.at<double>(0), T_world.at<double>(1), T_world.at<double>(2));

                    cv::Mat R_wc = R_wm * R_mc;
                    cv::Mat rvec_wc;
                    cv::Rodrigues(R_wc, rvec_wc);
                    _rotation = cv::Vec3d(rvec_wc.at<double>(0), rvec_wc.at<double>(1), rvec_wc.at<double>(2));
                } else {
                    // No world info for this marker; return position in marker-local frame
                    _position = cv::Vec3d(T_mc.at<double>(0), T_mc.at<double>(1), T_mc.at<double>(2));

                    cv::Mat rvec_mc;
                    cv::Rodrigues(R_mc, rvec_mc);
                    _rotation = cv::Vec3d(rvec_mc.at<double>(0), rvec_mc.at<double>(1), rvec_mc.at<double>(2));
                }

                // ---- Refresh optical-flow baseline from this frame ----
                _frames_since_marker = 0;
                cv::goodFeaturesToTrack(gray_frame, _prev_features,
                                        kMaxFlowFeatures, 0.01, 10);
                gray_frame.copyTo(_prev_gray_frame);

                _tracking_state = TrackingState::TRACKING;
                return true;
            }
            // solvePnP failed – fall through to the optical-flow path below
        }

        // ----------------------------------------------------------------
        // No marker visible (or PnP failed): short-term dead-reckoning via
        // sparse Lucas–Kanade optical flow.
        // ----------------------------------------------------------------
        _frames_since_marker++;

        // If we have been without a marker for too long, or we never had a
        // valid baseline, declare LOST and reset the flow state.
        if (_frames_since_marker > kOpticalFlowLostThreshold ||
            _prev_gray_frame.empty() ||
            _prev_features.empty() ||
            _tracking_state == TrackingState::INITIALIZING) {
            _tracking_state = TrackingState::LOST;
            gray_frame.copyTo(_prev_gray_frame);
            _prev_features.clear();
            return false;
        }

        // Track the previous feature set into the current frame
        std::vector<cv::Point2f> curr_features;
        std::vector<uchar> status;
        std::vector<float> err;
        cv::calcOpticalFlowPyrLK(_prev_gray_frame, gray_frame,
                                  _prev_features, curr_features,
                                  status, err,
                                  cv::Size(21, 21), 3);

        // Collect successfully tracked point pairs
        std::vector<cv::Point2f> good_prev, good_curr;
        for (size_t i = 0; i < status.size(); i++) {
            if (status[i] && err[i] < kMaxFlowError) {
                good_prev.push_back(_prev_features[i]);
                good_curr.push_back(curr_features[i]);
            }
        }

        if (static_cast<int>(good_prev.size()) < kMinTrackedFeatures) {
            _tracking_state = TrackingState::LOST;
            gray_frame.copyTo(_prev_gray_frame);
            _prev_features.clear();
            return false;
        }

        // ------------------------------------------------------------------
        // Rotation estimate: decompose the Essential matrix
        // ------------------------------------------------------------------
        cv::Mat R_delta = cv::Mat::eye(3, 3, CV_64F); // identity fallback
        {
            cv::Mat inlier_mask;
            cv::Mat E = cv::findEssentialMat(good_prev, good_curr,
                                              _config->get_camera_matrix(),
                                              cv::RANSAC, 0.999, 1.0, inlier_mask);
            if (!E.empty() && E.rows == 3 && E.cols == 3) {
                cv::Mat t_unit;
                int n_inliers = cv::recoverPose(E, good_prev, good_curr,
                                                _config->get_camera_matrix(),
                                                R_delta, t_unit, inlier_mask);

                if (n_inliers < kMinTrackedFeatures) {
                    R_delta = cv::Mat::eye(3, 3, CV_64F);
                }
            }
        }

        // ------------------------------------------------------------------
        // Translation estimate: back-project mean pixel flow to camera-frame
        // 3-D displacement using the last known marker depth as scale.
        //
        // Pinhole model: flow_x = -fx * delta_X / Z
        //   => delta_X = -flow_x * Z / fx   (and same for Y)
        // ------------------------------------------------------------------
        cv::Point2d mean_flow(0.0, 0.0);
        for (size_t i = 0; i < good_prev.size(); i++) {
            mean_flow.x += good_curr[i].x - good_prev[i].x;
            mean_flow.y += good_curr[i].y - good_prev[i].y;
        }
        mean_flow.x /= static_cast<double>(good_prev.size());
        mean_flow.y /= static_cast<double>(good_prev.size());

        const double fx = _config->get_camera_matrix().at<double>(0, 0);
        const double fy = _config->get_camera_matrix().at<double>(1, 1);
        const double depth = _last_camera_depth;

        cv::Mat delta_cam = (cv::Mat_<double>(3, 1)
            << -mean_flow.x * depth / fx,
               -mean_flow.y * depth / fy,
                0.0);

        // Rotate camera-frame delta into world frame using current orientation
        cv::Mat R_curr;
        cv::Rodrigues(_rotation, R_curr);
        cv::Mat delta_world = R_curr * delta_cam;

        _position[0] += delta_world.at<double>(0);
        _position[1] += delta_world.at<double>(1);
        _position[2] += delta_world.at<double>(2);

        // Accumulate rotation: R_new = R_delta * R_curr
        cv::Mat R_new = R_delta * R_curr;
        cv::Mat rvec_new;
        cv::Rodrigues(R_new, rvec_new);
        _rotation = cv::Vec3d(rvec_new.at<double>(0), rvec_new.at<double>(1), rvec_new.at<double>(2));

        // ------------------------------------------------------------------
        // Update the optical-flow baseline for the next frame.
        // Continue tracking the surviving points; re-detect if too few remain.
        // ------------------------------------------------------------------
        gray_frame.copyTo(_prev_gray_frame);
        _prev_features.clear();
        for (size_t i = 0; i < status.size(); i++) {
            if (status[i] && err[i] < kMaxFlowError) {
                _prev_features.push_back(curr_features[i]);
            }
        }
        if (static_cast<int>(_prev_features.size()) < 30) {
            cv::goodFeaturesToTrack(gray_frame, _prev_features,
                                    kMaxFlowFeatures, 0.01, 10);
        }

        _tracking_state = TrackingState::TRACKING;
        return true;
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

        dictionary = _config->get_dictionary();
        detector_params = _config->get_detector_params();
        marker_length = _config->get_marker_length();

        // Reset optical-flow dead-reckoning state
        _prev_gray_frame.release();
        _prev_features.clear();
        _frames_since_marker = 0;
        _last_camera_depth = 1.0;
    }

    void MarkerTracker::shutdown() {
        _tracking_state = TrackingState::INITIALIZING;
        _position = cv::Vec3d(0, 0, 0);
        _rotation = cv::Vec3d(0, 0, 0);

        // Clear optical-flow state
        _prev_gray_frame.release();
        _prev_features.clear();
        _frames_since_marker = 0;
        _last_camera_depth = 1.0;
    }
}
