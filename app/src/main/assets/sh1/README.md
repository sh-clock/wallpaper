# sh1/ — scene assets & the insertion protocol

The wallpaper engine loads `HP0003.glb` (the default scene) plus the
camdat `camdat.json`, all from this directory (see `sh1_jni.c`). To add
a new scene:

1. Convert the room IPD with the PoC converter (GPL-3.0 project tools):

   ```
   python3 tools/ipd2gltf.py --input <BG dir> \
       --search-roots <TIM dir> --output app/src/main/assets/sh1 \
       --rooms <ROOM>            # e.g. THRF9FD or SU0203
   ```

   One GLB per room, named exactly `<ROOM>.glb`. Convert the room's GRID
   NEIGHBORS TOO (the 8 adjacent cells): the engine's mapLink loader
   probes `<room>.glb` files in this directory at runtime and merges
   them so the map edge is a continuation, not a cliff. A missing
   neighbor file is a silent no-op.

2. Add an authored camdat (CamTrack Editor v13+, `tools/camtrack.html`
   in the PoC repo) as `sh1/<room>_camdat.json`.

3. Point the default scene at it: edit the three argv strings in
   `app/src/main/cpp/sh1_jni.c` (`HP0003.glb` / `camdat.json`).

4. Commit & push. CI bumps the version minor digit (1.7.30 -> 1.7.31),
   builds and publishes the APK release automatically.

Current contents:
- `HP0003.glb` + `HP0003.IPD` — hospital reception (interior; the
  default scene; roundtrip-verified byte-identical)
- `THRF9FD/THRF9FC/THRF9FE.glb` — the streets-west block (exterior; the
  mapLink demonstration set: THRF9FD merges FC+FE at runtime)
- `SU0203.glb` — the nightmare-school fan room (the REAL FAN_HIDE
  geometry, animated by the SH1X propAnims spinRng driver)
- `THR_GLB.PLM` — the shared THR texture table (base rip file)
- `camdat.json` — the default HP0003 camera track (particles off: the
  engine runs no weather in interiors)

The base rip files (`*.IPD`, `*.PLM`) ride for provenance and
re-conversion; the GLBs are self-contained.
