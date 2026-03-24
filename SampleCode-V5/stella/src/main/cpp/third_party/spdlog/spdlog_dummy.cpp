#include "spdlog/spdlog.h"
#include "spdlog/sinks/stdout_color_sinks.h"
#include "spdlog/sinks/basic_file_sink.h"
#include "spdlog/sinks/null_sink.h"

// This is a dummy source file to satisfy CMake's requirement for a SHARED library
// for spdlog 1.3.1 (which was header-only).

namespace spdlog {
    // some symbols to prevent it being an empty object file
    void spdlog_shared_lib_placeholder() {}
}
