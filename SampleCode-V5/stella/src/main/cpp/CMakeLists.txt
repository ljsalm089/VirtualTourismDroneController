cmake_minimum_required(VERSION 3.14)

# define a function to print the searching path of the header files for a specific target
function(print_include_directories target_name tag)
    get_target_property(INCLUDE_DIRS ${target_name} INCLUDE_DIRECTORIES)
    message("searching include path after ${tag}: ${INCLUDE_DIRS}")
endfunction()

project(stella_android)

message("PATH=$ENV{PATH}")
find_program(CCACHE_PROGRAM ccache)
if (CCACHE_PROGRAM)
    message("program ccache found: ${CCACHE_PROGRAM}")
    set(CMAKE_C_COMPILER_LAUNCHER ${CCACHE_PROGRAM})
    set(CMAKE_CXX_COMPILER_LAUNCHER ${CCACHE_PROGRAM})
else()
    message("Cannot find ccache")
endif()

# Android system libraries
find_library(log-lib log)
find_library(android-lib android)

add_subdirectory(native_trace)

# Find OpenCV. Java component is usually not needed for C++ linking.
find_package(OpenCV 4.7 REQUIRED)

message("test variable in root folder: ${PROJECT_SOURCE_DIR}")
message("test variable in root folder: ${CMAKE_SOURCE_DIR}")
message("OpenCV_DIR: ${OpenCV_DIR}")
message("OpenCV_INCLUDE_DIRS: ${OpenCV_INCLUDE_DIRS}")

# spdlog
add_subdirectory(third_party/spdlog)
set(spdlog_FOUND TRUE CACHE BOOL "" FORCE)


# ==========================================================
# Integrate FBoW native library
# ==========================================================
# Disable FBoW utils and tests to avoid unnecessary builds
set(BUILD_UTILS OFF CACHE BOOL "Disable FBoW Utils" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "Disable FBoW Tests" FORCE)
# Build FBoW as a static library
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build FBoW as static library" FORCE)

add_subdirectory(third_party/FBoW)


# add the dependency Eigen3
set(EIGEN_BUILD_DOC OFF)
set(BUILD_TESTING OFF)
add_subdirectory(third_party/eigen-3.4.0)


# ==========================================================
# Integrate SuiteSparse for g2o
# ==========================================================
# Only build necessary components to reduce build time and avoid missing dependencies on Android.
# g2o primarily needs CHOLMOD (which depends on AMD, COLAMD, CAMD, CCOLAMD) and optionally CXSparse.
set(SUITESPARSE_ENABLE_PROJECTS "suitesparse_config;amd;camd;ccolamd;colamd;cholmod;cxsparse" CACHE STRING "SuiteSparse projects to build" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "Build static" FORCE)
set(SUITESPARSE_USE_CUDA OFF CACHE BOOL "Disable CUDA" FORCE)
set(SUITESPARSE_USE_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)
set(SUITESPARSE_DEMOS OFF CACHE BOOL "Disable Demos" FORCE)
set(SUITESPARSE_REQUIRE_BLAS OFF CACHE BOOL "Disable BLAS requirement" FORCE)

# CHOLMOD options: Disable Supernodal to avoid heavy BLAS/LAPACK dependency on Android.
# This makes it easier to compile while still providing basic sparse Cholesky functionality.
set(CHOLMOD_SUPERNODAL OFF CACHE BOOL "Disable Supernodal (avoids BLAS/LAPACK dependency)" FORCE)

# add the dependency SuiteSparse
add_subdirectory(third_party/SuiteSparse)

# Set variables to satisfy g2o's find_package calls (FindSuiteSparse.cmake and FindCSparse.cmake)
set(SuiteSparse_FOUND TRUE CACHE BOOL "" FORCE)
set(SuiteSparse_CHOLMOD_FOUND TRUE CACHE BOOL "" FORCE)
set(SuiteSparse_AMD_FOUND TRUE CACHE BOOL "" FORCE)
set(SuiteSparse_COLAMD_FOUND TRUE CACHE BOOL "" FORCE)
set(SuiteSparse_CAMD_FOUND TRUE CACHE BOOL "" FORCE)
set(SuiteSparse_CCOLAMD_FOUND TRUE CACHE BOOL "" FORCE)
set(SuiteSparse_Config_FOUND TRUE CACHE BOOL "" FORCE)

# For g2o's FindCSparse.cmake (CXSparse is used as a drop-in for CSparse)
set(CSPARSE_FOUND TRUE CACHE BOOL "" FORCE)
set(CSPARSE_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/SuiteSparse/CXSparse/Include" CACHE PATH "" FORCE)
set(CSPARSE_LIBRARY CXSparse_static CACHE FILEPATH "" FORCE)

# If g2o uses variables instead of targets:
set(CHOLMOD_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/SuiteSparse/CHOLMOD/Include" CACHE PATH "" FORCE)
set(CHOLMOD_LIBRARIES CHOLMOD_static AMD_static COLAMD_static CAMD_static CCOLAMD_static SuiteSparseConfig_static CACHE STRING "" FORCE)

# Configure g2o features
set(G2O_USE_CHOLMOD ON CACHE BOOL "Build g2o with CHOLMOD support" FORCE)
set(G2O_USE_CSPARSE ON CACHE BOOL "Build g2o with CSParse support" FORCE)
set(G2O_USE_OPENMP OFF CACHE BOOL "Disable OpenMP in g2o" FORCE)
set(G2O_USE_OPENGL OFF CACHE BOOL "Disable OpenGL in g2o" FORCE)
set(G2O_USE_LOGGING OFF CACHE BOOL "Disable logging for g2o" FORCE)
set(G2O_BUILD_APPS OFF CACHE BOOL "Disable g2o apps" FORCE)
set(G2O_BUILD_EXAMPLES OFF CACHE BOOL "Disable g2o examples" FORCE)
set(BUILD_UNITTESTS OFF CACHE BOOL "Disable g2o unit tests" FORCE)
set(G2O_BUILD_PLAYGROUND OFF CACHE BOOL "Disable g2o playground" FORCE)

# g2o depends on eigen3, so this should be added after the eigen3
add_subdirectory(third_party/g2o)
# ==========================================================


# integrate yaml-cpp into the project for stella_vslam
set(YAML_CPP_BUILD_TESTS OFF)
set(YAML_CPP_BUILD_TOOLS OFF)
set(YAML_CPP_INSTALL OFF)
add_subdirectory(third_party/yaml-cpp)

# ==========================================================
# Create sqlite3
add_subdirectory(third_party/sqlite3)

# tinycolormap
add_subdirectory(third_party/tinycolormap)

# integrate stella_vslam
add_subdirectory(stella_vslam)

# load tracker module
add_subdirectory(vslam_tracker)

add_subdirectory(marker_tracker)

add_library(native-lib SHARED
    native-lib.cpp
)

target_include_directories(native-lib PUBLIC
    ${CMAKE_SOURCE_DIR}/third_party/json/include
    ${CMAKE_SOURCE_DIR}/third_party/tinycolormap/include
    ${CMAKE_SOURCE_DIR}/stella_vslam/src
)

message("the value of opencv libraries: ${OpenCV_LIBRARIES}")

target_link_libraries(native-lib PUBLIC
    native_trace
    stella_vslam
    Eigen3::Eigen
    spdlog::spdlog
    fbow
    yaml-cpp
    g2o::core
    g2o::stuff
    g2o::types_sba
    g2o::types_sim3
    g2o::solver_dense
    g2o::solver_eigen
    g2o::solver_csparse
    g2o::csparse_extension
    vslam_tracker
    marker_tracker
    ${OpenCV_LIBRARIES}
    ${log-lib}
)
