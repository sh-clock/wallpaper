cmake_minimum_required(VERSION 3.22)
project(sh1poc C)

add_library(sh1 SHARED fog_poc.c sh1_jni.c)

target_include_directories(sh1 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(sh1 PRIVATE
    -O2
    -std=c99
    -Wno-unused-parameter
    -Wno-format-truncation
    -Wno-format
)

target_link_libraries(sh1
    android
    log
    m
)

# 16 KB page-size compatibility (Android 15+ devices with 16 KB kernels,
# required by Google Play): link the ELF with 16 KB max page alignment so
# libsh1.so loads on both 4 KB and 16 KB page-size devices.
target_link_options(sh1 PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
