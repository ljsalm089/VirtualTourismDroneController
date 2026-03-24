cmake_minimum_required(VERSION 3.14)
project(spdlog)

# Since spdlog 1.3.1 is header-only, we create an interface library by default.
# However, to satisfy the requirement of being a dynamic library, we can
# create a SHARED library by providing a source file that includes the headers.
# Note that since the headers use "inline", they will still be inlined in callers
# unless SPDLOG_COMPILED_LIB is supported and used (supported in 1.4+).

add_library(spdlog SHARED spdlog_dummy.cpp)

# Trace level log
set (ENABLE_TRACE_LEVEL_LOG OFF CACHE BOOL "Enable trace level log")
if (ENABLE_TRACE_LEVEL_LOG)
    # Remove SPDLOG_TRACE macro at compile time by switching SPDLOG_ACTIVE_LEVEL.
    # NOTE: SPDLOG_ACTIVE_LEVEL has no effect on `spdlog::debug(...)`, `spdlog::trace(...)`, etc.
    target_compile_definitions(${PROJECT_NAME} PUBLIC SPDLOG_ACTIVE_LEVEL=SPDLOG_LEVEL_TRACE)
    message(STATUS "trace level log: ENABLED")
else()
    target_compile_definitions(${PROJECT_NAME} PUBLIC SPDLOG_ACTIVE_LEVEL=SPDLOG_LEVEL_DEBUG)
    message(STATUS "trace level log: DISABLED")
endif()

# Macro to differentiate if current platform is Android
if (ANDROID)
    message("current project name: ${PROJECT_NAME}")
    target_compile_definitions(${PROJECT_NAME} PUBLIC _SPDLOG_ANDROID)
    # DON'T APPEND DATE AND TIME IN THE LOG ON Android platform
    target_compile_definitions(${PROJECT_NAME} PUBLIC SPDLOG_NO_DATETIME)
    message(STATUS "Platform: ANDROID (SPDLOG_ANDROID macro defined)")
else()
    message(STATUS "Platform: NON-ANDROID")
endif()

target_include_directories(spdlog PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>
)


# If this were version 1.4+, we would define SPDLOG_COMPILED_LIB
# target_compile_definitions(spdlog PUBLIC SPDLOG_COMPILED_LIB)

add_library(spdlog::spdlog ALIAS spdlog)

# Install rules
install(TARGETS spdlog EXPORT spdlogTargets
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin
)

install(DIRECTORY include DESTINATION include)
