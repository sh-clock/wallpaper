#!/bin/bash
# extract_and_blit.sh — v10.1 Phase I: extract the STRICT CENTER-CROP
# presentation code (the crop statics + sh1_android_set_crop + and_blit)
# VERBATIM from fog_poc.c into a header the desktop test compiles against.
#
# Extraction is BRACE-COUNTED (not line-number based) so it stays valid
# as the source evolves; the generated header records the source lines
# for traceability. The desktop test therefore proves the REAL shipped
# code — never a re-implementation.
#
# usage: extract_and_blit.sh [src] [out] [legacy]
#   legacy = "legacy" extracts the v10.0-baseline math (and_clear_* +
#   and_blit) for the contrast assertions that prove the test battery
#   would have caught the v10.0 bugs.
set -e
SRC="${1:-/home/z/my-project/v10_1/sh1-wallpaper-doodle/app/src/main/cpp/fog_poc.c}"
OUT="${2:-/home/z/my-project/v10_1/bench/and_blit_extracted.h}"
MODE="${3:-new}"
[ -f "$SRC" ] || { echo "missing source: $SRC" >&2; exit 1; }

if [ "$MODE" = "legacy" ]; then
  # --- the v10.0 math: and_clear_8888 + and_clear_565 + and_blit -------
  awk '
    /^static void and_clear_8888/ { found = 1 }
    found {
      print
      n = gsub(/{/, "{");  d += n
      m = gsub(/}/, "}");  d -= m
      if (/^static void and_blit\(/) { infn = 1 }
      if (infn && /}/ && d <= 0) { exit }
    }
  ' "$SRC" > /tmp/and_blit_block.c
  START=$(grep -n "^static void and_clear_8888" "$SRC" | head -1 | cut -d: -f1)
  grep -q "static void and_blit" /tmp/and_blit_block.c \
    || { echo "FATAL: legacy and_blit not extracted" >&2; exit 1; }
  grep -q "xpos += xs;" /tmp/and_blit_block.c \
    || { echo "FATAL: legacy and_blit lost the split X step" >&2; exit 1; }
  END=$((START + $(wc -l < /tmp/and_blit_block.c) - 1))
  {
    echo "/* AUTO-EXTRACTED VERBATIM (LEGACY v10.0 math) from $(basename "$SRC") — DO NOT EDIT."
    echo " * lines $START-$END — contrast reference only."
    echo " */"
    echo
    cat /tmp/and_blit_block.c
  } > "$OUT"
  echo "EXTRACTED (legacy): $OUT  [$SRC:$START-$END, $(wc -l < /tmp/and_blit_block.c) lines]"
  exit 0
fi

# --- extraction 1: the crop presentation state (statics + setter) -----
awk '
  /^static volatile float g_and_crop_x/ { found = 1 }
  found {
    print
    n = gsub(/{/, "{");  d += n
    m = gsub(/}/, "}");  d -= m
    if (/^void sh1_android_set_crop\(/) { infn = 1 }
    if (infn && /}/ && d <= 0) { exit }
  }
' "$SRC" > /tmp/and_crop_block.c

# --- extraction 2: and_blit itself ------------------------------------
awk '
  /^static void and_blit\(LiveApp \*A, ANativeWindow_Buffer \*buf\) \{/ { found = 1 }
  found {
    print
    n = gsub(/{/, "{");  d += n
    m = gsub(/}/, "}");  d -= m
    if (d <= 0) { exit }
  }
' "$SRC" > /tmp/and_blit_block.c

# --- sanity: both blocks extracted, both signatures present ----------
grep -q "sh1_android_set_crop" /tmp/and_crop_block.c \
  || { echo "FATAL: crop setter not extracted" >&2; exit 1; }
grep -q "static void and_blit" /tmp/and_blit_block.c \
  || { echo "FATAL: and_blit not extracted" >&2; exit 1; }
grep -q "xpos += inc16;" /tmp/and_blit_block.c \
  || { echo "FATAL: and_blit lost the uniform X step" >&2; exit 1; }
grep -q "ypos += inc16;" /tmp/and_blit_block.c \
  || { echo "FATAL: and_blit lost the uniform Y step" >&2; exit 1; }

CROP_START=$(grep -n "^static volatile float g_and_crop_x" "$SRC" | head -1 | cut -d: -f1)
CROP_END=$((CROP_START + $(wc -l < /tmp/and_crop_block.c) - 1))
BLIT_START=$(grep -n "^static void and_blit(LiveApp \*A, ANativeWindow_Buffer \*buf) {" "$SRC" | head -1 | cut -d: -f1)
BLIT_END=$((BLIT_START + $(wc -l < /tmp/and_blit_block.c) - 1))

{
  echo "/* AUTO-EXTRACTED VERBATIM from $(basename "$SRC") — DO NOT EDIT."
  echo " * crop state:   lines $CROP_START-$CROP_END"
  echo " * and_blit:     lines $BLIT_START-$BLIT_END"
  echo " * Regenerate: bench/extract_and_blit.sh"
  echo " */"
  echo
  cat /tmp/and_crop_block.c
  echo
  cat /tmp/and_blit_block.c
} > "$OUT"

echo "EXTRACTED: $OUT"
echo "  crop state:  fog_poc.c:$CROP_START-$CROP_END ($(wc -l < /tmp/and_crop_block.c) lines)"
echo "  and_blit:    fog_poc.c:$BLIT_START-$BLIT_END ($(wc -l < /tmp/and_blit_block.c) lines)"
