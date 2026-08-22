cmake_minimum_required(VERSION 3.22.1)

project(resource_stress LANGUAGES CXX)

add_library(
    resource_stress
    SHARED
    native_stress.cpp
    cpu_stress.cpp
    memory_stress.cpp
)

target_compile_features(resource_stress PRIVATE cxx_std_17)
target_compile_options(resource_stress PRIVATE -Wall -Wextra -Wpedantic)

find_library(log_library log REQUIRED)
target_link_libraries(resource_stress PRIVATE ${log_library})
