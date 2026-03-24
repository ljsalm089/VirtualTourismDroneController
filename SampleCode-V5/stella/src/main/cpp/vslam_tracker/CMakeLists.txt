cmake_minimum_required(VERSION 3.14)

file(GLOB_RECURSE TRACKER_SRC_FILES
    "src/*.cpp"
    "src/*.c"
)

message("list all the files in src: ${TRACKER_SRC_FILES}")

project(vslam_tracker)

add_library(vslam_tracker SHARED
    ${TRACKER_SRC_FILES}
)

target_include_directories(vslam_tracker PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(vslam_tracker PRIVATE
    stella_vslam
    opencv_core
)
