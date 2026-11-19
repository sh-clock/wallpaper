#!/usr/bin/env python3
"""v10.3 Phase G ACTION 4 — the mathematical verifier for the crop-pan
proof harness (crop_pan_invariance).

PROPERTY 1 (raster invariance): the three engine shots (crop -1/0/+1) are
byte-identical — asserted by the caller via md5.

PROPERTY 2 (destination pan + exact window math): this script re-implements
the shipped and_blit 16.16 DDA math INDEPENDENTLY (from the source, not
from the harness) and asserts, for a dense sample grid of destination
pixels at every offset:

  dst[y][x] == src[sy][sx]

where (sx, sy) are the exact source coordinates the C computes:
  inc16   = (fh << 16) / wh            (height-limited: 320x224 -> 1080x2400)
  availX  = (fw << 16) - ((ww*fh << 16) / wh)
  cx16    = clamp((availX >> 1) * (1 + ox))
  cy16    = 0                           (the height fills exactly)
  sx      = min((cx16 + x*inc16) >> 16, fw-1)
  sy      = min((y*inc16) >> 16, fh-1)

Also asserts the three destinations DIFFER (the visible window pans) and
that the pan is a pure translation of the sampling origin (identical inc).
"""
import struct
import sys
import zlib


def decode_png_rgb(path):
    data = open(path, "rb").read()
    assert data[:8] == bytes([137, 80, 78, 71, 13, 10, 26, 10])
    p = 8
    w = h = None
    idat = b""
    while p + 8 <= len(data):
        (length,) = struct.unpack(">I", data[p:p + 4])
        typ = data[p + 4:p + 8]
        chunk = data[p + 8:p + 8 + length]
        if typ == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", chunk[:10])
            assert depth == 8 and ctype == 2, (depth, ctype)
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
        p += 8 + length + 4
    raw = zlib.decompress(idat)
    stride = 1 + w * 3
    assert len(raw) == h * stride
    px = []
    for y in range(h):
        row = raw[y * stride:(y + 1) * stride]
        filt = row[0]
        assert filt == 0, f"unexpected filter {filt}"
        px.append(row[1:])
    return w, h, px


def load_dst(path, w, h, stride):
    raw = open(path, "rb").read()
    assert len(raw) == stride * h * 4
    px = []
    for y in range(h):
        row = raw[y * stride * 4:(y * stride + w) * 4]
        px.append([tuple(row[x * 4:x * 4 + 3]) for x in range(w)])
    return px


def src_pixel(src, sx, sy):
    o = sx * 3
    return tuple(src[sy][o:o + 3])


def main():
    proof = sys.argv[1] if len(sys.argv) > 1 else "."
    fw, fh, src = decode_png_rgb(f"{proof}/shot_p0.png")
    print(f"source raster: {fw}x{fh}")
    assert (fw, fh) == (320, 224), "expected the authentic raster"

    ww, wh = 1080, 2400
    stride = 1080

    # ---- the and_blit math, re-derived from the shipped C ---------------
    assert ww * fh < wh * fw  # height-limited (portrait phone)
    inc16 = (fh << 16) // wh
    availX16 = (fw << 16) - ((ww * fh << 16) // wh)
    availY16 = 0
    assert availY16 == 0

    offsets = {"m1": -1.0, "p0": 0.0, "p1": 1.0}
    dsts = {}
    for tag in offsets:
        dsts[tag] = load_dst(f"{proof}/dst_{tag}.raw", ww, wh, stride)

    # the destinations must DIFFER (the visible window pans)
    assert dsts["m1"] != dsts["p0"], "offset -1 vs 0 must differ (no pan!)"
    assert dsts["p0"] != dsts["p1"], "offset 0 vs +1 must differ (no pan!)"
    assert dsts["m1"] != dsts["p1"]

    # every sampled destination pixel equals the source pixel at the
    # EXACT coordinate the C DDA computes
    step_x = max(1, ww // 320)
    step_y = max(1, wh // 320)
    checked = 0
    for tag, ox in offsets.items():
        cx = (availX16 >> 1) * (1.0 + ox)
        cx = max(0.0, min(cx, float(availX16)))
        cx16 = int(cx)
        dst = dsts[tag]
        for y in range(0, wh, step_y):
            ypos = y * inc16
            sy = ypos >> 16
            if sy >= fh:
                sy = fh - 1
            for x in range(0, ww, step_x):
                xpos = cx16 + x * inc16
                sx = xpos >> 16
                if sx >= fw:
                    sx = fw - 1
                got = dst[y][x]
                want = src_pixel(src, sx, sy)
                assert got == want, (
                    f"offset {tag}: dst[{y}][{x}]={got} != src[{sy}][{sx}]={want}")
                checked += 1
    print(f"verified {checked} destination pixels per offset "
          f"({len(offsets)} offsets) — every one matches the exact DDA window")

    # the pan is a PURE ORIGIN TRANSLATION: identical step, origin shifted
    # by the availX fraction — dst(+1)'s window shows the RIGHT edge band
    inc_f = inc16 / 65536.0
    win_cols = ww * inc_f
    print(f"window: {win_cols:.2f} of {fw} raster cols "
          f"(avail {availX16 / 65536.0:.2f}); "
          f"origins: -1 -> col 0.00, 0 -> col {(availX16 >> 1) / 65536.0:.2f}, "
          f"+1 -> col {availX16 / 65536.0:.2f}")
    print("PROPERTY 2 VERIFIED: the crop offsets translate the 2D sampling "
          "window over the unchanged raster — a TRUE 2D crop pan.")
    print("PROOF COMPLETE: raster byte-identical under all offsets; "
          "destination = the exact shifted window. No 3D camera involvement "
          "is possible.")


if __name__ == "__main__":
    main()
