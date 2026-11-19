cmake_minimum_required(VERSION 3.22)
project(sh1poc C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# v9.9: the engine TU now also compiles the hardware renderers:
#   - fog_poc.c      — the byte-frozen engine (desktop golden-md5 proven;
#                      the SH1_HW_RENDER gate at its end includes
#                      sh1_hw_render.c INTO the TU so the renderer sees
#                      the engine's static types — same composition the
#                      desktop harness audits)
#   - sh1_hw_vk.cpp  — the Vulkan backend (Swanstation's verbatim
#                      loader/builders/util in vk/ + the precompiled
#                      SPIR-V in sh1_hw_vk_spv.h; libvulkan is dlopen'd
#                      at runtime by the loader — no link-time dep)
#   - sh1_jni.c      — the JNI bootstrap (unchanged role)
add_library(sh1 SHARED
    fog_poc.c
    sh1_hw_vk.cpp
    vk/vulkan_loader.cpp
    vk/builders.cpp
    vk/util.cpp
    sh1_jni.c)

target_include_directories(sh1 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# JNI symbols for the doodle host class: xyz.zedler.patrick.doodle.service.Sh1Native
# (see sh1_jni.c — same C source as the standalone build, different symbol root)
# SH1_HW_RENDER: compiles the hardware-backend seam (see fog_poc.c's end)
target_compile_definitions(sh1 PRIVATE
    SH1_JNI_CLASS=xyz_zedler_patrick_doodle_service_Sh1Native
    SH1_HW_RENDER=1
)

target_compile_options(sh1 PRIVATE
    -O2
    -Wno-unused-parameter
    -Wno-format-truncation
    -Wno-format
)
set_source_files_properties(fog_poc.c PROPERTIES COMPILE_OPTIONS
    "-std=c99;-Wno-unused-function")

target_link_libraries(sh1
    android
    log
    EGL
    GLESv3
    m
)

# 16 KB page-size compatibility (Android 15+ devices with 16 KB kernels,
# required by Google Play): link the ELF with 16 KB max page alignment so
# libsh1.so loads on both 4 KB and 16 KB page-size devices.
target_link_options(sh1 PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
