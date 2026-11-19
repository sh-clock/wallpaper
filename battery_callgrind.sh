#!/bin/bash
# battery_callgrind.sh — v10.0 Phase I: instruction attribution for the
# 60-second standardized sequence across the three backends.
#
# Outputs (per backend):
#   cg-<backend>.out      raw callgrind data
#   cg-<backend>.summary  total instruction count (Ir)
#   cg-<backend>.top      function-level attribution (top functions)
set -e
BENCH=/home/z/my-project/v10/bench
MESA=/home/z/mesa-local
VG=$MESA/root/usr/bin/valgrind
CG_ANNOTATE=$MESA/root/usr/bin/callgrind_annotate
export LD_LIBRARY_PATH=$MESA/root/usr/lib/x86_64-linux-gnu
export __EGL_VENDOR_LIBRARY_FILENAMES=$MESA/root/usr/share/glvnd/egl_vendor.d/50_mesa.json
export VK_ICD_FILENAMES=$MESA/root/usr/share/vulkan/icd.d/lvp_icd.json
export VALGRIND_LIB=$MESA/root/usr/libexec/valgrind

cd $BENCH

run_one() {
  local name="$1"; shift
  echo "=== callgrind: $name (60 s sequence, 1802 frames) ==="
  env "$@" $VG --tool=callgrind --callgrind-out-file=cg-$name.out \
      --cacheuse=no \
      ./battery_fog > bench-$name.log 2>&1
  # total Ir from the callgrind output header
  grep -E "I   refs:" cg-$name.out | awk '{print "TOTAL_Ir " $NF}' > cg-$name.summary
  cat bench-$name.log | grep "battery-bench" >> cg-$name.summary
  # function attribution (exclusive costs, top 40)
  $CG_ANNOTATE --threshold=99 --inclusive=no cg-$name.out > cg-$name.top 2>/dev/null || true
  head -4 cg-$name.top | tail -1 >> /dev/null || true
  echo "--- $name done ---"
  cat cg-$name.summary
}

run_one software
run_one gles     SH1_BENCH_BACKEND=gles
run_one vk       SH1_BENCH_BACKEND=vk

echo
echo "ALL DONE"
