cmake_minimum_required(VERSION 3.22.1)

project(resource_stress LANGUAGES CXX)

if(CMAKE_HOST_WIN32)
    set(SHADER_HOST_TAG windows-x86_64)
elseif(CMAKE_HOST_APPLE)
    set(SHADER_HOST_TAG darwin-x86_64)
else()
    set(SHADER_HOST_TAG linux-x86_64)
endif()

find_program(
    GLSLC_EXECUTABLE
    NAMES glslc glslc.exe
    HINTS "${ANDROID_NDK}/shader-tools/${SHADER_HOST_TAG}"
    NO_DEFAULT_PATH
    REQUIRED
)

set(STRESS_SHADER_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/shaders/stress.comp")
set(STRESS_SHADER_BINARY "${CMAKE_CURRENT_BINARY_DIR}/stress.comp.spv")
set(STRESS_SHADER_HEADER "${CMAKE_CURRENT_BINARY_DIR}/stress_shader_spv.h")

add_custom_command(
    OUTPUT "${STRESS_SHADER_HEADER}"
    COMMAND "${GLSLC_EXECUTABLE}"
        --target-env=vulkan1.0
        -O
        -o "${STRESS_SHADER_BINARY}"
        "${STRESS_SHADER_SOURCE}"
    COMMAND "${CMAKE_COMMAND}"
        "-DSPIRV_PATH=${STRESS_SHADER_BINARY}"
        "-DHEADER_PATH=${STRESS_SHADER_HEADER}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spirv.cmake"
    DEPENDS
        "${STRESS_SHADER_SOURCE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/embed_spirv.cmake"
    VERBATIM
)

add_library(
    resource_stress
    SHARED
    "${STRESS_SHADER_HEADER}"
    native_stress.cpp
    cpu_stress.cpp
    memory_stress.cpp
    gpu_stress.cpp
    vulkan_context.cpp
)

target_compile_features(resource_stress PRIVATE cxx_std_17)
target_compile_options(resource_stress PRIVATE -Wall -Wextra -Wpedantic)
target_include_directories(resource_stress PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")

find_library(log_library log REQUIRED)
find_library(vulkan_library vulkan REQUIRED)
target_link_libraries(resource_stress PRIVATE ${log_library} ${vulkan_library})
