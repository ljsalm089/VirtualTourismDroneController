cmake_minimum_required(VERSION 3.14)

file(GLOB_RECURSE MARKER_TRACKER_SRC_FILES
    "src/*.cpp"
    "src/*.c"
)

message("list all the files in src for marker traker: ${MARKER_TRACKER_SRC_FILES}")

project(marker_tracker)

add_library(marker_tracker SHARED
    ${MARKER_TRACKER_SRC_FILES}
)

message("current src dir: ${CMAKE_CURRENT_SRC_DIR}")

target_include_directories(marker_tracker PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(marker_tracker PRIVATE
    native_trace
    opencv_core
)