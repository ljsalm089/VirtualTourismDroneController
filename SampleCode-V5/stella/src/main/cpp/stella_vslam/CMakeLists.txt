project(stella_vslam)
# ----- Find dependencies -----

# json
message("current source dir: ${PROJECT_SOURCE_DIR}")
set(json_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/third_party/json/include)

# g2o
if(NOT TARGET g2o::core)
    message("Seems like there is no target: g2o::core")
    find_package(g2o REQUIRED
                 COMPONENTS
                 g2o::core
                 g2o::stuff
                 g2o::types_sba
                 g2o::types_sim3
                 g2o::solver_dense
                 g2o::solver_eigen
                 g2o::solver_csparse
                 g2o::csparse_extension
                 OPTIONAL_COMPONENTS
                 g2o::csparse)
endif()

# gtsam
set(USE_GTSAM OFF CACHE BOOL "Enable gtsam")
unset(GTSAM_INCLUDE_DIRS CACHE)
find_path(GTSAM_INCLUDE_DIRS NAMES gtsam
          PATHS /usr/local/include/)
if(USE_GTSAM AND GTSAM_INCLUDE_DIRS)
    message(STATUS "gtsam: enabled (Found in ${GTSAM_INCLUDE_DIRS})")
else()
    set(USE_GTSAM OFF)
    message(STATUS "gtsam: disabled")
endif()

# Check first if CSparse is built from g2o
if(TARGET g2o::csparse)
    set(CXSPARSE_LIBRARIES g2o::csparse)
elseif(TARGET csparse)
    set(CXSPARSE_LIBRARIES csparse)
else()
    # CXSparse for g2o
    find_package(CXSparse QUIET)
    if(CXSPARSE_FOUND)
        include_directories(${CXSPARSE_INCLUDE_DIRS})
        print_include_directories("stella_vslam" "cxsparse")
    endif()
    # SuiteSparse for g2o
    if(NOT SUITESPARSE_FOUND)
        if(MSVC)
            find_package(SuiteSparse CONFIG QUIET)
        else()
            find_package(SuiteSparse QUIET)
        endif()
        if(SUITESPARSE_FOUND)
            include_directories(${SUITESPARSE_INCLUDE_DIRS})
            print_include_directories("stella_vslam" "SuiteSparse")
        endif()
    endif()
endif()

# Check BoW Framework
# if(NOT fbow_FOUND)
#     add_subdirectory(${PROJECT_SOURCE_DIR}/third_party/FBoW)# ${PROJECT_BINARY_DIR}/third_party/FBoW)
# endif()
# if(BOW_FRAMEWORK MATCHES "DBoW2")
#     find_package(DBoW2 REQUIRED)
# elseif(BOW_FRAMEWORK MATCHES "FBoW")
#     if(NOT TARGET fbow AND NOT TARGET fbow::fbow)
#         find_package(fbow QUIET)
#     endif()
# else()
#     message(FATAL_ERROR "Invalid BoW framework: ${BOW_FRAMEWORK}")
# endif()

# cuda_efficient_features
set(USE_CUDA_EFFICIENT_DESCRIPTORS OFF CACHE BOOL "Enable cuda_efficient_features")
find_path(cuda_efficient_features_INCLUDE_DIR NAMES cuda_efficient_features.h)
find_library(cuda_efficient_features_LIBRARY NAMES cuda_efficient_features)
find_package_handle_standard_args(cuda_efficient_features DEFAULT_MSG cuda_efficient_features_LIBRARY cuda_efficient_features_INCLUDE_DIR)
if(USE_CUDA_EFFICIENT_DESCRIPTORS AND cuda_efficient_features_FOUND)
    message(STATUS "cuda_efficient_features: enabled")
else()
    message(STATUS "cuda_efficient_features: disabled")
    set(USE_CUDA_EFFICIENT_DESCRIPTORS OFF)
endif()

# ----- Create stella_vslam library -----

message("project name: ${PROJECT_NAME}")
add_library(${PROJECT_NAME}
            ${CMAKE_CURRENT_SOURCE_DIR}/config.h
            ${CMAKE_CURRENT_SOURCE_DIR}/type.h
            ${CMAKE_CURRENT_SOURCE_DIR}/system.h
            ${CMAKE_CURRENT_SOURCE_DIR}/tracking_module.h
            ${CMAKE_CURRENT_SOURCE_DIR}/mapping_module.h
            ${CMAKE_CURRENT_SOURCE_DIR}/global_optimization_module.h
            ${CMAKE_CURRENT_SOURCE_DIR}/config.cc
            ${CMAKE_CURRENT_SOURCE_DIR}/system.cc
            ${CMAKE_CURRENT_SOURCE_DIR}/tracking_module.cc
            ${CMAKE_CURRENT_SOURCE_DIR}/mapping_module.cc
            ${CMAKE_CURRENT_SOURCE_DIR}/global_optimization_module.cc)

message("project source dir: ${CMAKE_SOURCE_DIR}")
include_directories(AFTER
    ${CMAKE_SOURCE_DIR}/
    ${CMAKE_SOURCE_DIR}/third_party/json/include
)
print_include_directories("stella_vslam" "SuiteSparse")

# Set output directory of the library
set_target_properties(${PROJECT_NAME} PROPERTIES
                      OUTPUT_NAME stella_vslam
                      ARCHIVE_OUTPUT_DIRECTORY ${PROJECT_BINARY_DIR}/lib
                      LIBRARY_OUTPUT_DIRECTORY ${PROJECT_BINARY_DIR}/lib)

# ----- Compile configuration -----

# cuda_efficient_features
if (USE_CUDA_EFFICIENT_DESCRIPTORS)
    find_package(OpenCV REQUIRED core features2d cudev cudafilters cudaimgproc cudawarping)
    find_package(CUDAToolkit REQUIRED)
    target_compile_definitions(${PROJECT_NAME} PUBLIC USE_CUDA_EFFICIENT_DESCRIPTORS)
endif ()

# gtsam
if (USE_GTSAM)
    target_compile_definitions(${PROJECT_NAME} PUBLIC USE_GTSAM)
endif()

# OpenMP
if(USE_OPENMP)
    target_compile_options(${PROJECT_NAME} PRIVATE ${OpenMP_CXX_FLAGS})
    target_compile_definitions(${PROJECT_NAME} PUBLIC USE_OPENMP)
    message(STATUS "OpenMP: ENABLED")
else()
    message(STATUS "OpenMP: DISABLED")
endif()

set(USE_SSE_ORB OFF CACHE BOOL "Enable SSE3 instruction for ORB extraction")
if(USE_SSE_ORB)
    target_compile_options(${PROJECT_NAME} PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-msse3>)
    target_compile_definitions(${PROJECT_NAME} PUBLIC USE_SSE_ORB)
    message(STATUS "SSE3 for ORB extraction: ENABLED")
else()
    message(STATUS "SSE3 for ORB extraction: DISABLED")
endif()

set(USE_SSE_FP_MATH OFF CACHE BOOL "Enable SSE instruction for floating-point operation")
if(USE_SSE_FP_MATH)
    target_compile_options(${PROJECT_NAME} PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-mfpmath=sse>)
    message(STATUS "SSE for floating-point operation: ENABLED")
else()
    message(STATUS "SSE for floating-point operation: DISABLED")
endif()

# if(BOW_FRAMEWORK MATCHES "DBoW2")
#     set(BoW_LIBRARY ${DBoW2_LIBS})
#     target_compile_definitions(${PROJECT_NAME} PUBLIC USE_DBOW2)
#     message(STATUS "BoW framework: ${BOW_FRAMEWORK} (found in ${DBoW2_INCLUDE_DIRS})")
# elseif(BOW_FRAMEWORK MATCHES "FBoW")
#     if(TARGET fbow::fbow OR TARGET fbow)
#         set(BoW_LIBRARY fbow)
#         message(STATUS "BoW framework: ${BOW_FRAMEWORK} (Using TARGET fbow)")
#     elseif(fbow_FOUND)
#         set(BoW_LIBRARY ${fbow_LIBS})
#         message(STATUS "BoW framework: ${BOW_FRAMEWORK} (found in ${fbow_INCLUDE_DIRS})")
#     else()
#         target_include_directories(${PROJECT_NAME}
#                                 PUBLIC
#                                 "$<BUILD_INTERFACE:${PROJECT_SOURCE_DIR}/third_party/FBoW/include>"
#                                 "$<INSTALL_INTERFACE:include/fbow>")
#         set(BoW_LIBRARY fbow)
#         message(STATUS "BoW framework: ${BOW_FRAMEWORK} (Using submodule)")
#     endif()
# else()
#     message(FATAL_ERROR "Invalid BoW framework: ${BOW_FRAMEWORK}")
# endif()
target_include_directories(${PROJECT_NAME}
                        PUBLIC
                        "$<BUILD_INTERFACE:${CMAKE_SOURCE_DIR}/third_party/FBoW/include>"
                        "$<INSTALL_INTERFACE:include/fbow>")
set(BoW_LIBRARY fbow)
message(STATUS "BoW framework: ${BOW_FRAMEWORK} (Using submodule)")

if(USE_ARUCO)
    target_compile_definitions(${PROJECT_NAME} PUBLIC USE_ARUCO)
endif()


# Determinism
set (DETERMINISTIC OFF CACHE BOOL "Use deterministic code")
if (DETERMINISTIC)
    target_compile_definitions(${PROJECT_NAME} PUBLIC DETERMINISTIC)
    message(STATUS "Determinism: ENABLED")
else()
    message(STATUS "Determinism: DISABLED")
endif()

# Tracy
set(USE_TRACY OFF CACHE BOOL "Enable tracy")
if(USE_TRACY)
    message(STATUS "tracy: ENABLED")
    # Gperftools
    find_package(Tracy REQUIRED)
else()
    message(STATUS "tracy: DISABLED")
endif()
if(USE_TRACY)
    target_compile_definitions(${PROJECT_NAME} PRIVATE -DTRACY_ENABLE=1)
    target_link_libraries(${PROJECT_NAME} PRIVATE Tracy::TracyClient)
endif()

# ----- Configure stella_vslam library -----

# Include directories
target_include_directories(${PROJECT_NAME}
                           PUBLIC
                           ${CMAKE_SOURCE_DIR}/
                           ${CMAKE_SOURCE_DIR}/third_party/json/include
)

# Link to required libraries
target_link_libraries(${PROJECT_NAME}
                      PUBLIC
                      fbow
                      tinycolormap
                      native_trace
                      "$<$<BOOL:${USE_OPENMP}>:OpenMP::OpenMP_CXX>"
                      Eigen3::Eigen
                      yaml-cpp
                      sqlite3
                      opencv_core
                      opencv_features2d
                      opencv_calib3d
                      "$<$<BOOL:${LINK_OBJETECT}>:opencv_objdetect>"
                      "$<$<BOOL:${LINK_ARUCO}>:opencv_aruco>"
                      g2o::core
                      g2o::stuff
                      g2o::types_sba
                      g2o::types_sim3
                      g2o::solver_dense
                      g2o::solver_eigen
                      g2o::solver_csparse
                      g2o::csparse_extension
                      "$<$<BOOL:${USE_GTSAM}>:gtsam>"
                      ${CXSPARSE_LIBRARIES}
                      ${SUITESPARSE_LIBRARIES}
                      ${BoW_LIBRARY}
                      "$<$<BOOL:${spdlog_FOUND}>:spdlog::spdlog>"
                      "$<$<BOOL:${USE_CUDA_EFFICIENT_DESCRIPTORS}>:${cuda_efficient_features_LIBRARY};opencv_cudafilters;opencv_cudawarping>"
                      PRIVATE
                      "$<$<BOOL:${LAPACK_LIBRARIES}>:${LAPACK_LIBRARIES}>"
                      "$<$<BOOL:${USE_CUDA_EFFICIENT_DESCRIPTORS}>:CUDA::cudart;CUDA::cublas>")

print_include_directories(${PROJECT_NAME} "stella_vslam")

# ----- Install configuration -----

set(STELLA_VSLAM_INCLUDE_INSTALL_DIR ${INCLUDES_DESTINATION}/stella_vslam)

# Install stella_vslam library
install(TARGETS ${PROJECT_NAME}
        EXPORT ${STELLA_VSLAM_TARGETS_EXPORT_NAME}
        RUNTIME DESTINATION ${RUNTIME_DESTINATION}
        LIBRARY DESTINATION ${LIBRARY_DESTINATION}
        ARCHIVE DESTINATION ${ARCHIVE_DESTINATION})

# Install stella_vslam headers
file(GLOB HEADERS "${CMAKE_CURRENT_SOURCE_DIR}/*.h")
install(FILES ${HEADERS}
        DESTINATION ${STELLA_VSLAM_INCLUDE_INSTALL_DIR})

# Install 3rd party headers
install(FILES ${json_INCLUDE_DIR}/nlohmann/json.hpp ${json_INCLUDE_DIR}/nlohmann/json_fwd.hpp
        DESTINATION ${STELLA_VSLAM_INCLUDE_INSTALL_DIR}/3rd/json/include/nlohmann)


# ----- Append subdirectory -----

add_subdirectory(camera)
add_subdirectory(data)
add_subdirectory(feature)
add_subdirectory(initialize)
add_subdirectory(io)
add_subdirectory(marker_detector)
add_subdirectory(marker_model)
add_subdirectory(match)
add_subdirectory(module)
add_subdirectory(optimize)
add_subdirectory(publish)
add_subdirectory(solve)
add_subdirectory(util)
