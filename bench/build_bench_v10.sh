#!/bin/bash
# build_bench.sh — v10.0 Phase I: build the desktop battery harness
# (the engine's own --benchmark mode + the three rendering backends:
#  software / OpenGL ES (llvmpipe via surfaceless EGL) / Vulkan (lavapipe)).
set -e
SRC=/home/z/my-project/v10/sh1-wallpaper-doodle/app/src/main/cpp
BENCH=/home/z/my-project/v10/bench
MESA=/home/z/my-project/../mesa-local  # -> /home/z/mesa-local
MESA=/home/z/mesa-local
INC=$MESA/root/usr/include
LIB=$MESA/root/usr/lib/x86_64-linux-gnu

cd $BENCH
gcc -O2 -std=c99 -DSH1_HW_RENDER=1 \
    -I$INC \
    -Wno-unused-function -Wno-unused-parameter -Wno-format \
    -c $SRC/fog_poc.c -o fog_poc.o
gcc -O2 -I$INC -c bench_backend_ctor.c -o bench_backend_ctor.o
g++ -O2 -std=c++17 -DSH1_HW_RENDER=1 -I$INC -I$SRC \
    -c $SRC/sh1_hw_vk.cpp -o sh1_hw_vk.o
g++ -O2 -std=c++17 -I$INC -I$SRC \
    -c $SRC/vk/vulkan_loader.cpp -o vk_vulkan_loader.o
g++ -O2 -std=c++17 -I$INC -I$SRC \
    -c $SRC/vk/builders.cpp -o vk_builders.o
g++ -O2 -std=c++17 -I$INC -I$SRC \
    -c $SRC/vk/util.cpp -o vk_util.o
g++ -o fog_bench fog_poc.o bench_backend_ctor.o sh1_hw_vk.o \
    vk_vulkan_loader.o vk_builders.o vk_util.o \
    -L$LIB -lEGL -lGLESv2 -ldl -lm -lpthread
echo "BUILD OK: $BENCH/fog_bench"
ldd fog_bench | grep -E "EGL|GLES|vulkan" || true
