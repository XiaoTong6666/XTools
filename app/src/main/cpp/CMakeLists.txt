cmake_minimum_required(VERSION 3.10.2)
project(tools-jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

set(TOOLS_JNI_SOURCES
    core/engine.cpp
    core/daemon_guard.cpp
)

add_library(tools-jni SHARED
    jni_bridge.cpp
    jni/egl_gpu.cpp
    ${TOOLS_JNI_SOURCES}
)

target_include_directories(tools-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${THIRD_PARTY_DIR}
    ${THIRD_PARTY_DIR}/nlohmann
    ${THIRD_PARTY_DIR}/fmt
    ${THIRD_PARTY_DIR}/spdlog
)

target_compile_definitions(tools-jni PRIVATE
    FMT_HEADER_ONLY
    SPDLOG_FMT_EXTERNAL
    JSON_HAS_CPP_17
    TOOLS_JNI_LIB
)

find_library(log-lib log)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(tools-jni ${log-lib} ${egl-lib} ${glesv2-lib})

target_compile_options(tools-jni PRIVATE -Os -Wall -Wextra)
