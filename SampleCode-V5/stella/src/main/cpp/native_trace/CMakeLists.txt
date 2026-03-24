cmake_minimum_required(VERSION 3.14)

project(native_trace)

file(GLOB_RECURSE TRACE_SRC_FILES
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.c"
)

include_directories(AFTER
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

message(DEBUG "source files for native trace: ${TRACE_SRC_FILES}")

add_library(native_trace SHARED
    ${TRACE_SRC_FILES}
)

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(native_trace PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(native_trace PUBLIC
    ${android-lib}
    ${log-lib}
)
