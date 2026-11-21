#!/bin/bash
# S20-cont: build the WALLPAPER TWIN engine on a plain Linux host (the
# software-rasterizer path — the exact same fog_poc.c the APK's libsh1.so
# compiles from source; the __ANDROID__ surface backend and the SH1_HW_RENDER
# GLES/Vulkan backends are #ifdef'd out on host).
# This is the anim-suite / water-verification binary: every scene, animation
# driver, water FX and TV paint path is reachable headlessly via --shot.
set -e
cd "$(dirname "$0")/.."             # repo root
gcc -O2 -std=c99 -Wall -Wextra -Wno-unused-parameter \
    -o bench/fog_poc app/src/main/cpp/fog_poc.c \
    -I app/src/main/cpp -lm
echo "BUILD OK: bench/fog_poc (host software engine)"
./bench/fog_poc --help 2>/dev/null | head -2 || true
