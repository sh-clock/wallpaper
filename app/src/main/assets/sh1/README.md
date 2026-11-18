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

3. **Add the scene to the manifest** — one entry in
   `app/src/main/assets/sh1/scenes.json`:

   ```
   {"glb": "SU0203.glb", "camdat": "SU0203_fan_camdat.json"}
   ```

   The Java side copies EVERY file under assets/sh1/ into filesDir
   dynamically (no hardcoded ASSETS list since v8 — the v7.1 list
   required a Java code change per scene and app updates had to be kept
   in sync by hand), and the native side (`sh1_jni.c`) reads
   scenes.json at every wallpaper-process start, rotating through the
   entries via the persisted index in filesDir/sh1/scene.idx. A missing
   manifest, a broken entry, or a missing GLB falls back to the HP0003
   defaults (graceful — the wallpaper never errors out).

4. Commit & push. CI bumps the version by one level (1.7.30 -> 1.7.31),
   builds and publishes the APK release automatically.

The current rotation: HP0003 (hospital reception) -> THRF9FD
(streets west, the mapLink demo) -> SU0203 (the nightmare-school fan
room — the FAN_HIDE propAnims spinRng driver runs live).

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
