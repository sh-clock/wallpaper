/*
 * This file is part of Doodle Android.
 *
 * Doodle Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Doodle Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Doodle Android. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2019-2025 by Patrick Zedler
 */

package xyz.zedler.patrick.doodle.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v9.7 (Camtrack native editor): the FULL read/write camdat.json document
 * model — a functional port of the CamTrack v13 HTML editor's data layer.
 *
 * <p><b>Parse</b> mirrors the v13 editor's {@code sanitizeDoc()} /
 * {@code sanitizeTrack()} (which the ENGINE's own camdat parser in
 * cpp/fog_poc.c mirrors field-for-field): per-field defaults, strict-number
 * presence rules, the same clamps (fov 5..140, lazyRate 1..720, speed
 * floors, radius/size floors, turns 0.05..20, lanes round-then-clamp
 * 2..64, weatherTarget 0..8, envDay/envNight 0..20), the per-type lookMode
 * domains with per-type defaults, and the bounds-flag RECONSTRUCTION rules
 * (arcBounds/yawBounds/pitchBounds exist only as min/max pairs in JSON).
 * Invalid tracks (unknown type, or a curve/polyline with fewer than 2
 * usable anchors) are DROPPED exactly like both reference implementations,
 * so the document's track indices line up with the engine's own
 * {@code --track N} indexing.</p>
 *
 * <p><b>Serialize</b> mirrors the v13 editor's {@code docToJSON()} /
 * {@code trackToJSON()} / {@code sanitizeEnv()} exactly: the same field
 * whitelists per track type (irrelevant fields are omitted), the same
 * construction key order, {@code r4()} rounding (Math.round(v*10000)/10000)
 * on every emitted number, optional fields (weatherTarget / envDay /
 * envNight / lookAt / yawDeg / pitchDeg / lazyRateDegS / normalDirection /
 * arc+axis bounds / durationSec) emitted only when their rule says so, and
 * JSON.stringify(v, null, 2) formatting via {@link CamtrackJson} — a
 * camdat saved by this model is byte-compatible with the reference
 * editor's export (round-trip unit-proven against the shipped
 * camdat_v13.json).</p>
 *
 * <p>The legacy per-model booleans (fogon / nighton / flashlighton) are
 * DERIVED from the authored env the same way the v13 World &amp; Weather
 * panel derives them ({@link #deriveLegacyFlags}): fogon = engine-enabled
 * AND the base slot's fog mode != 0; nighton = the base slot's DARKENV
 * flag; flashlighton = env.flashlightOn. The flag/fog-mode bits per preset
 * are transcribed from the engine's SH_MAP_EFFECTS_INFOS table.</p>
 */
public final class CamtrackDoc {

  /* ---- format constants (blankDoc()) -------------------------------- */

  /** the shipped v13 format version (docToJSON: version: 1) */
  public static final int FORMAT_VERSION = 1;

  /**
   * generatedBy for NEW documents; the PARSED document's string is
   * preserved verbatim so a load+save round-trips byte-identically.
   */
  public static final String GENERATED_BY =
      "CamTrack Editor 1.4.1 (v13.5 room-original env + user light)";

  /**
   * The v13 format doc string, embedded verbatim from the shipped
   * camdat_v13.json (the complete consumer contract for every track type).
   */
  static final String DOC_STRING =
      "Parametric constant-speed camera tracks. line: pos(t)=start+s(t)*(end-start). circle: theta deg, pos(t)=center"
      + "+[radiusX*cos(theta),0,radiusZ*sin(theta)], y=center[1]. fixed: pos=position, yaw/pitch advance by *SpeedDegS "
      + "with optional *MinDeg/*MaxDeg boundaries. curve: uniform Catmull-Rom spline through points[] (end tangents dup"
      + "licated by clamping); a fixed 64-samples-per-segment chord table plus a monotone cubic Hermite (Fritsch-Carlso"
      + "n) refinement maps traveled distance to the spline parameter, pos(t) is the exact spline point (C1-smooth) at "
      + "constant speedMS; lookMode path uses the analytic spline derivative. polyline: a line with multiple turns — st"
      + "raight segments through points[] at constant speedMS; arc length is the exact segment-length sum, pos(t) lerps"
      + " inside the segment reached by traveled distance, lookMode path uses the (piecewise-constant) segment directio"
      + "n. spiral: f=phase01(t,turns*360/speedDegS,loopMode), theta=startAngleDeg+dir*turns*360*f with dir=+1 for cw a"
      + "nd -1 for ccw, r=radiusStart+(radiusEnd-radiusStart)*f, y=center[1]+yStart+(yEnd-yStart)*f, pos(t)=center+[r*c"
      + "os(theta),y-center[1],r*sin(theta)] — radius and height interpolate over the sweep (a circle when radiusStart="
      + "radiusEnd and yStart=yEnd). zigzag: lawnmower sweep — lanes passes across a sizeX by sizeZ rectangle centered "
      + "at center, waypoints at x=center[0]+/-sizeX/2 alternating direction while z advances sizeZ/(lanes-1) per pass,"
      + " joined by straight segments at constant speedMS (same segment walk as polyline). figure8: theta deg advances "
      + "like circle over one full 360 deg period (angleAt with bounds startAngleDeg..startAngleDeg+/-360), pos(t)=cent"
      + "er+[semiX*sin(theta),0,semiZ*sin(2*theta)], y=center[1] (Gerono lemniscate; closes every 360 deg). loopMode: l"
      + "oop wraps, pingPong mirrors at ends, once stops at end. Orientation: lookAt point when lookMode is center|poin"
      + "t|path; lookMode \"normal\" looks along N=normalize(cross(T,up)) with T the travel tangent (reversed on the pi"
      + "ngPong return leg, same rule as path) and up=+Y, so N is horizontal and perpendicular to T (T parallel to up f"
      + "alls back to cross(T,+Z), then cross(T,+X)); normalDirection \"forward\"|\"backward\" selects +N|-N; else fixe"
      + "d yaw/pitch (YXZ Euler, +Y up, pitch positive = up). Units: meters / degrees / seconds. Coordinates are the gl"
      + "TF file own world space (identity transform). Per-model flags: fogon, nighton, flashlighton (booleans, derived"
      + " from the World & Weather panel in v1) ride with each models[] entry for the consuming program; the full world"
      + " env also rides as models[].env { pair:[A,B] (MAP_EFFECTS_INFOS indices 0..20), weatherTarget 0..8 (sharedFunc"
      + "_800D0B18 arg), bootWeather -1..6, lensFlare 0..5, flashlightOn, psxWobble, dither, particles, engine }. durat"
      + "ionSec is informational (one playback cycle). Per-track weather (v12): models[].tracks[].weatherTarget (0..8, "
      + "optional, strict number) is the weather THE TRACK carries — the sharedFunc_800D0B18 argument, resolved track >"
      + " model env when present. Consumers apply it through the engine's own 512-tick sine cross-fade (the authentic r"
      + "untime transition); the CamTrack v12 EDITOR previews it INSTANTLY (no cross-fade) DAY/NIGHT ENVIRONMENTS (v13)"
      + ": models[].env.dayEnv and models[].env.nightEnv (optional, 0..20, MAP_EFFECTS_INFOS indices) are the scene’s A"
      + "uto-Mode presets — the environments the wallpaper applies by daylight phase (sun-time or the device schedule);"
      + " models[].tracks[].envDay / models[].tracks[].envNight (optional, 0..20) override them per track (resolved tra"
      + "ck > model; absent = inherit the model preset). The wallpaper app layers them under its own preferences (app o"
      + "verride > camdat track > camdat model > app custom mapping > the authentic defaults 1/3). The CamTrack v13 EDI"
      + "TOR previews the phase presets INSTANTLY through its session-only Day/Night lens (applied as the pair {N,N}, n"
      + "ever serialized); the ENGINE transitions day/night through its own environment paths.";

  /* ---- track type / enum constants ---------------------------------- */

  public static final String TYPE_LINE = "line";
  public static final String TYPE_CIRCLE = "circle";
  public static final String TYPE_FIXED = "fixed";
  public static final String TYPE_CURVE = "curve";
  public static final String TYPE_POLYLINE = "polyline";
  public static final String TYPE_SPIRAL = "spiral";
  public static final String TYPE_ZIGZAG = "zigzag";
  public static final String TYPE_FIGURE8 = "figure8";

  public static final String[] TYPES = {
      TYPE_LINE, TYPE_CIRCLE, TYPE_FIXED, TYPE_CURVE,
      TYPE_POLYLINE, TYPE_SPIRAL, TYPE_ZIGZAG, TYPE_FIGURE8
  };

  /** human labels for the add-track UI (the v13 editor's button titles) */
  public static final String[] TYPE_LABELS = {
      "Line — straight move",
      "Circle — circle / oval orbit",
      "Fixed — position, rotating view",
      "Curve — anchor-point spline",
      "Polyline — multi-turn corridor path",
      "Spiral — in/out sweep with height",
      "Zigzag — lawnmower full coverage",
      "Figure 8 — lemniscate loop"
  };

  public static final String LOOP_LOOP = "loop";
  public static final String LOOP_PINGPONG = "pingPong";
  public static final String LOOP_ONCE = "once";

  public static final String LOOK_PATH = "path";
  public static final String LOOK_LAZY = "lazy";
  public static final String LOOK_NORMAL = "normal";
  public static final String LOOK_POINT = "point";
  public static final String LOOK_FIXED = "fixed";
  public static final String LOOK_CENTER = "center";

  public static final String DIR_CW = "cw";
  public static final String DIR_CCW = "ccw";

  public static final String SIDE_FORWARD = "forward";
  public static final String SIDE_BACKWARD = "backward";

  /**
   * Per-preset fog-on bits (MAP_EFFECTS_INFOS field_E != 0) and DARKENV
   * flags, transcribed from the engine's SH_MAP_EFFECTS_INFOS table — the
   * legacy fogon/nighton derivation inputs.
   */
  static final boolean[] PRESET_FOG_ON = {
      false, true, true, false, false, true, true, true, true, false,
      false, false, false, true, false, false, false, true, false, true, false
  };
  static final boolean[] PRESET_DARK = {
      false, false, false, false, false, false, true, true, false, false,
      false, false, false, false, false, false, false, false, false, true, false
  };

  /* ------------------------------------------------------------------ */
  /* Track                                                               */

  /** one camera track — every field of every v13 track type */
  public static final class Track {
    public String id;
    public String name;
    public String type;
    public boolean enabled = true;
    public double fovDeg = 60;

    /** null = absent: inherit the model env default (the editor rule) */
    public Integer weatherTarget;
    /** v13 per-track day env override; null = inherit the model preset */
    public Integer envDay;
    /** v13 per-track night env override; null = inherit the model preset */
    public Integer envNight;

    public String loopMode;

    // path/position fields (union by type; doubles keep JSON fidelity)
    public double[] start;      // line
    public double[] end;        // line
    public double[] center;     // circle/spiral/zigzag/figure8
    public List<double[]> points;  // curve/polyline
    public double[] position;   // fixed
    public double radiusX;      // circle
    public double radiusZ;      // circle
    public double radiusStart;  // spiral
    public double radiusEnd;    // spiral
    public double turns;        // spiral
    public double yStart;       // spiral
    public double yEnd;         // spiral
    public double sizeX;        // zigzag
    public double sizeZ;        // zigzag
    public int lanes;           // zigzag
    public double semiX;        // figure8
    public double semiZ;        // figure8
    public double startAngleDeg;   // circle/spiral/figure8
    public String direction = DIR_CW;  // circle/spiral/figure8
    public double speedMS;         // line/curve/polyline/zigzag
    public double speedDegS;       // circle/spiral/figure8

    // look/orientation fields
    public String lookMode;
    public String normalDirection = SIDE_FORWARD;
    public double[] lookAt;
    public double yawDeg;
    public double pitchDeg;
    public double lazyRateDegS = 25;

    // bounds (reconstructed from min/max pairs, never serialized as flags)
    public boolean arcBounds;      // circle
    public double arcMinDeg = -90;
    public double arcMaxDeg = 90;
    public boolean yawBounds;      // fixed
    public double yawMinDeg = -45;
    public double yawMaxDeg = 45;
    public boolean pitchBounds;    // fixed
    public double pitchMinDeg = -10;
    public double pitchMaxDeg = 10;

    // fixed-type rotation speeds
    public double yawSpeedDegS;
    public double pitchSpeedDegS;

    /** v13 look-mode domain per type (sanitizeTrack's pick lists) */
    public static String[] lookModesFor(String type) {
      switch (type) {
        case TYPE_CIRCLE:
        case TYPE_SPIRAL:
        case TYPE_FIGURE8:
          return new String[]{LOOK_CENTER, LOOK_POINT, LOOK_PATH, LOOK_LAZY,
              LOOK_FIXED, LOOK_NORMAL};
        case TYPE_ZIGZAG:
          return new String[]{LOOK_PATH, LOOK_LAZY, LOOK_CENTER, LOOK_POINT,
              LOOK_FIXED, LOOK_NORMAL};
        case TYPE_FIXED:
          return new String[0];   // fixed tracks have no lookMode
        default:                  // line / curve / polyline
          return new String[]{LOOK_PATH, LOOK_LAZY, LOOK_POINT, LOOK_FIXED,
              LOOK_NORMAL};
      }
    }

    public static String defaultLookMode(String type) {
      switch (type) {
        case TYPE_CIRCLE:
        case TYPE_SPIRAL:
        case TYPE_FIGURE8:
          return LOOK_CENTER;
        case TYPE_ZIGZAG:
        case TYPE_LINE:
        case TYPE_CURVE:
        case TYPE_POLYLINE:
        default:
          return LOOK_PATH;
      }
    }

    /** default loop mode per type (sanitizeTrack) */
    public static String defaultLoopMode(String type) {
      switch (type) {
        case TYPE_LINE:
        case TYPE_POLYLINE:
        case TYPE_SPIRAL:
        case TYPE_ZIGZAG:
          return LOOP_PINGPONG;
        default:
          return LOOP_LOOP;    // circle / curve / figure8 / fixed
      }
    }

    /** true when the track parses for the ENGINE (the skip rule) */
    public boolean engineValid() {
      if (type == null) {
        return false;
      }
      switch (type) {
        case TYPE_CURVE:
        case TYPE_POLYLINE:
          return points != null && points.size() >= 2;
        default:
          return true;
      }
    }
  }

  /* ------------------------------------------------------------------ */
  /* Model env                                                           */

  /**
   * models[].env — every field the v13 World &amp; Weather panel writes.
   * Presence is tracked per field exactly like sanitizeEnv (a field is
   * emitted only when authored); pair is always [A,B] once present.
   */
  public static final class Env {
    public Integer pair0;
    public Integer pair1;
    public Integer weatherTarget;
    public Integer bootWeather;
    public Integer lensFlare;
    public Boolean flashlightOn;
    public Boolean psxWobble;
    public Boolean dither;
    public Boolean particles;
    public Boolean engine;
    public Integer dayEnv;
    public Integer nightEnv;

    /** true when no field is authored (env: null in the doc) */
    public boolean isEmpty() {
      return pair0 == null && pair1 == null && weatherTarget == null
          && bootWeather == null && lensFlare == null && flashlightOn == null
          && psxWobble == null && dither == null && particles == null
          && engine == null && dayEnv == null && nightEnv == null;
    }
  }

  /* ------------------------------------------------------------------ */
  /* Model                                                               */

  /** one models[] entry */
  public static final class Model {
    public String file;
    public String addedAt;         // ISO string or null
    public double[] boundsMin;     // null when absent
    public double[] boundsMax;
    public boolean fogon;
    public boolean nighton;
    public boolean flashlighton;
    public Env env;                // null when absent
    public Light light;            // v13.5 (T7): the user-programmable light
    public final List<Track> tracks = new ArrayList<>();

    Model(String file) {
      this.file = file;
    }

    /** model center from bounds (activeModelInfo(); [0,1,0] fallback) */
    public double[] center() {
      if (boundsMin != null && boundsMax != null) {
        return new double[]{
            (boundsMin[0] + boundsMax[0]) / 2.0,
            (boundsMin[1] + boundsMax[1]) / 2.0,
            (boundsMin[2] + boundsMax[2]) / 2.0
        };
      }
      return new double[]{0, 1, 0};
    }

    /** model size from bounds ([10,10,10] fallback — a sane room) */
    public double[] size() {
      if (boundsMin != null && boundsMax != null) {
        return new double[]{
            Math.abs(boundsMax[0] - boundsMin[0]),
            Math.abs(boundsMax[1] - boundsMin[1]),
            Math.abs(boundsMax[2] - boundsMin[2])
        };
      }
      return new double[]{10, 10, 10};
    }

    /** bounding-box diagonal (the turn-filter jump threshold input) */
    public double diag() {
      double[] s = size();
      return Math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
    }

    /**
     * Re-derive the legacy per-model flags from the authored env — the v13
     * World &amp; Weather panel's own rule (writeEnvToModelLive), evaluated
     * on the authored pair slot A / the flashlight state instead of the
     * live session engine (the native editor holds no SHCore state; the
     * preset bits are transcribed from the engine table).
     */
    public void deriveLegacyFlags() {
      boolean engineOn = env == null || env.engine == null || env.engine;
      int slotA = env != null && env.pair0 != null ? env.pair0 : 1;
      boolean flash = env != null && Boolean.TRUE.equals(env.flashlightOn);
      fogon = engineOn && presetFogOn(slotA);
      nighton = presetDark(slotA);
      flashlighton = flash;
    }

    static boolean presetFogOn(int idx) {
      return idx >= 0 && idx < PRESET_FOG_ON.length && PRESET_FOG_ON[idx];
    }

    static boolean presetDark(int idx) {
      return idx >= 0 && idx < PRESET_DARK.length && PRESET_DARK[idx];
    }
  }

  /* ------------------------------------------------------------------ */
  /* Document                                                            */

  public final List<Model> models = new ArrayList<>();
  /** the parsed generatedBy (preserved verbatim on serialize) */
  public String generatedBy = GENERATED_BY;

  private CamtrackDoc() {
  }

  /** an empty document (New) */
  public static CamtrackDoc blank() {
    return new CamtrackDoc();
  }

  /* ---- parse (sanitizeDoc/sanitizeTrack port) ---------------------- */

  /** number of tracks dropped by the last parse (informational) */
  public int droppedTracks;

  @SuppressWarnings("unchecked")
  public static CamtrackDoc parse(String text) {
    CamtrackDoc doc = new CamtrackDoc();
    Object root = CamtrackJson.parse(text);
    Map<String, Object> rootMap = CamtrackJson.asObj(root);
    if (rootMap == null) {
      return doc;   // no models: the empty doc (invalid for save)
    }
    doc.generatedBy = CamtrackJson.jstr(rootMap, "generatedBy", GENERATED_BY);
    List<Object> modelsArr = CamtrackJson.asArr(rootMap.get("models"));
    if (modelsArr == null) {
      return doc;
    }
    for (Object mo : modelsArr) {
      Map<String, Object> m = CamtrackJson.asObj(mo);
      if (m == null) {
        continue;
      }
      String file = CamtrackJson.jstr(m, "file", null);
      if (file == null || file.isEmpty()) {
        continue;   // sanitizeDoc: entries without a string file are skipped
      }
      Model entry = new Model(file);
      String addedAt = CamtrackJson.jstr(m, "addedAt", null);
      entry.addedAt = addedAt != null ? addedAt : isoNow();
      entry.env = parseEnv(CamtrackJson.jget(m, "env"));
      entry.light = parseLight(CamtrackJson.jget(m, "light"));
      entry.fogon = CamtrackJson.jbool(m, "fogon", false);
      entry.nighton = CamtrackJson.jbool(m, "nighton", false);
      entry.flashlighton = CamtrackJson.jbool(m, "flashlighton", false);
      Map<String, Object> bounds = CamtrackJson.asObj(CamtrackJson.jget(m, "bounds"));
      if (bounds != null) {
        double[] mn = vec3(CamtrackJson.jget(bounds, "min"), new double[]{0, 0, 0});
        double[] mx = vec3(CamtrackJson.jget(bounds, "max"), new double[]{0, 0, 0});
        if (mn != null && mx != null) {
          entry.boundsMin = mn;
          entry.boundsMax = mx;
        }
      }
      List<Object> tracks = CamtrackJson.asArr(CamtrackJson.jget(m, "tracks"));
      if (tracks != null) {
        for (Object to : tracks) {
          Track t = sanitizeTrack(CamtrackJson.asObj(to));
          if (t != null) {
            entry.tracks.add(t);
          } else {
            doc.droppedTracks++;
          }
        }
      }
      doc.models.add(entry);
    }
    return doc;
  }

  /* ------------------------------------------------------------------ */
  /* v13.5 (T7 — task 3): the USER PROGRAMMABLE LIGHT (models[].light)    */

  /** the light block — the authored 6-DOF flashlight (all fields optional) */
  public static final class Light {
    public Boolean on;            // Game_TurnFlashlightOn boot toggle
    public String follow;         // "camera" (anchor) | "world" (free pose)
    public double[] pos;          // meters, world (follow world)
    public double[] rot;          // degrees (pitch, yaw, roll — the AIM)
    public double[] offset;       // meters, camera-local (follow camera)
    public Double ahead;          // meters forward along the beam (>= 0)

    /** true when no field is authored (light: null in the doc) */
    public boolean isEmpty() {
      return on == null && follow == null && pos == null && rot == null
          && offset == null && ahead == null;
    }
  }

  /** sanitizeLight() port — the export whitelist + clamps (the engine's
   *  models[].light parse semantics: strict types, per-field presence)   */
  static Light parseLight(Object raw) {
    Map<String, Object> l = CamtrackJson.asObj(raw);
    if (l == null) {
      return null;
    }
    Light light = new Light();
    if (CamtrackJson.isBool(CamtrackJson.jget(l, "on"))) {
      light.on = (Boolean) CamtrackJson.jget(l, "on");
    }
    String follow = CamtrackJson.jstr(l, "follow", null);
    if ("world".equals(follow) || "camera".equals(follow)) {
      light.follow = follow;
    }
    List<Object> pos = CamtrackJson.asArr(CamtrackJson.jget(l, "pos"));
    if (pos != null && pos.size() >= 3 && CamtrackJson.isNum(pos.get(0))
        && CamtrackJson.isNum(pos.get(1)) && CamtrackJson.isNum(pos.get(2))) {
      light.pos = new double[]{(Double) pos.get(0), (Double) pos.get(1), (Double) pos.get(2)};
    }
    List<Object> rot = CamtrackJson.asArr(CamtrackJson.jget(l, "rot"));
    if (rot != null && rot.size() >= 3 && CamtrackJson.isNum(rot.get(0))
        && CamtrackJson.isNum(rot.get(1)) && CamtrackJson.isNum(rot.get(2))) {
      light.rot = new double[]{(Double) rot.get(0), (Double) rot.get(1), (Double) rot.get(2)};
    }
    List<Object> off = CamtrackJson.asArr(CamtrackJson.jget(l, "offset"));
    if (off != null && off.size() >= 3 && CamtrackJson.isNum(off.get(0))
        && CamtrackJson.isNum(off.get(1)) && CamtrackJson.isNum(off.get(2))) {
      light.offset = new double[]{(Double) off.get(0), (Double) off.get(1), (Double) off.get(2)};
    }
    if (CamtrackJson.isNum(CamtrackJson.jget(l, "ahead"))) {
      light.ahead = Math.max(0.0, (Double) CamtrackJson.jget(l, "ahead"));
    }
    return light.isEmpty() ? null : light;
  }

  /** lightMap() — the serializer (key order fixed, same whitelist) */
  private static Map<String, Object> lightMap(Light l) {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    if (l.on != null) {
      out.put("on", l.on);
    }
    if (l.follow != null) {
      out.put("follow", l.follow);
    }
    if (l.pos != null) {
      out.put("pos", vecList(l.pos));
    }
    if (l.rot != null) {
      out.put("rot", vecList(l.rot));
    }
    if (l.offset != null) {
      out.put("offset", vecList(l.offset));
    }
    if (l.ahead != null) {
      out.put("ahead", l.ahead);
    }
    return out;
  }

  private static List<Object> vecList(double[] v) {
    List<Object> out = new ArrayList<>();
    out.add(v[0]);
    out.add(v[1]);
    out.add(v[2]);
    return out;
  }

  /** sanitizeEnv() port — per-field presence + clamp */
  static Env parseEnv(Object raw) {
    Map<String, Object> e = CamtrackJson.asObj(raw);
    if (e == null) {
      return null;
    }
    Env env = new Env();
    List<Object> pair = CamtrackJson.asArr(CamtrackJson.jget(e, "pair"));
    if (pair != null && pair.size() >= 2
        && CamtrackJson.isNum(pair.get(0)) && CamtrackJson.isNum(pair.get(1))) {
      env.pair0 = clampInt((Double) pair.get(0), 0, 20);
      env.pair1 = clampInt((Double) pair.get(1), 0, 20);
    }
    if (CamtrackJson.isNum(CamtrackJson.jget(e, "weatherTarget"))) {
      env.weatherTarget = clampInt(
          (Double) CamtrackJson.jget(e, "weatherTarget"), 0, 8);
    }
    if (CamtrackJson.isNum(CamtrackJson.jget(e, "bootWeather"))) {
      env.bootWeather = clampInt(
          (Double) CamtrackJson.jget(e, "bootWeather"), -1, 6);
    }
    if (CamtrackJson.isNum(CamtrackJson.jget(e, "lensFlare"))) {
      env.lensFlare = clampInt(
          (Double) CamtrackJson.jget(e, "lensFlare"), 0, 5);
    }
    if (CamtrackJson.isBool(CamtrackJson.jget(e, "flashlightOn"))) {
      env.flashlightOn = (Boolean) CamtrackJson.jget(e, "flashlightOn");
    }
    if (CamtrackJson.isBool(CamtrackJson.jget(e, "psxWobble"))) {
      env.psxWobble = (Boolean) CamtrackJson.jget(e, "psxWobble");
    }
    if (CamtrackJson.isBool(CamtrackJson.jget(e, "dither"))) {
      env.dither = (Boolean) CamtrackJson.jget(e, "dither");
    }
    if (CamtrackJson.isBool(CamtrackJson.jget(e, "particles"))) {
      env.particles = (Boolean) CamtrackJson.jget(e, "particles");
    }
    if (CamtrackJson.isBool(CamtrackJson.jget(e, "engine"))) {
      env.engine = (Boolean) CamtrackJson.jget(e, "engine");
    }
    if (CamtrackJson.isNum(CamtrackJson.jget(e, "dayEnv"))) {
      env.dayEnv = clampInt((Double) CamtrackJson.jget(e, "dayEnv"), 0, 20);
    }
    if (CamtrackJson.isNum(CamtrackJson.jget(e, "nightEnv"))) {
      env.nightEnv = clampInt((Double) CamtrackJson.jget(e, "nightEnv"), 0, 20);
    }
    return env.isEmpty() ? null : env;
  }

  /** sanitizeTrack() port — returns null for a dropped track */
  static Track sanitizeTrack(Map<String, Object> raw) {
    if (raw == null) {
      return null;
    }
    String type = pick(CamtrackJson.jstr(raw, "type", null), TYPES, null);
    if (type == null) {
      return null;
    }
    Track tr = new Track();
    tr.type = type;
    String id = CamtrackJson.jstr(raw, "id", null);
    tr.id = id != null && !id.isEmpty() ? id : newId();
    String name = CamtrackJson.jstr(raw, "name", null);
    tr.name = name != null ? name.substring(0, Math.min(name.length(), 60)) : "";
    tr.enabled = CamtrackJson.jbool(raw, "enabled", true);
    tr.fovDeg = clamp(CamtrackJson.jnum(raw, "fovDeg", 60), 5, 140);
    tr.weatherTarget = optionalClamp(raw, "weatherTarget", 0, 8);
    tr.envDay = optionalClamp(raw, "envDay", 0, 20);
    tr.envNight = optionalClamp(raw, "envNight", 0, 20);
    tr.loopMode = pick(CamtrackJson.jstr(raw, "loopMode", null),
        new String[]{LOOP_LOOP, LOOP_PINGPONG, LOOP_ONCE},
        Track.defaultLoopMode(type));
    tr.normalDirection = pick(CamtrackJson.jstr(raw, "normalDirection", null),
        new String[]{SIDE_FORWARD, SIDE_BACKWARD}, SIDE_FORWARD);
    tr.lazyRateDegS = clamp(CamtrackJson.jnum(raw, "lazyRateDegS", 25), 1, 720);

    switch (type) {
      case TYPE_LINE: {
        tr.start = vec3(CamtrackJson.jget(raw, "start"),
            new double[]{0, 1, 0});
        tr.end = vec3(CamtrackJson.jget(raw, "end"), new double[]{1, 1, 0});
        tr.speedMS = Math.max(CamtrackJson.jnum(raw, "speedMS", 1), 0.001);
        tr.lookMode = pickLook(raw, TYPE_LINE);
        tr.lookAt = vec3(CamtrackJson.jget(raw, "lookAt"),
            new double[]{0, 1, 0});
        tr.yawDeg = CamtrackJson.jnum(raw, "yawDeg", 0);
        tr.pitchDeg = CamtrackJson.jnum(raw, "pitchDeg", 0);
        break;
      }
      case TYPE_CIRCLE: {
        tr.center = vec3(CamtrackJson.jget(raw, "center"),
            new double[]{0, 1, 0});
        tr.radiusX = Math.max(CamtrackJson.jnum(raw, "radiusX", 1), 0.01);
        double rzDef = CamtrackJson.isNum(CamtrackJson.jget(raw, "radiusZ"))
            ? (Double) CamtrackJson.jget(raw, "radiusZ") : tr.radiusX;
        tr.radiusZ = Math.max(rzDef, 0.01);
        tr.startAngleDeg = CamtrackJson.jnum(raw, "startAngleDeg", 0);
        tr.direction = pick(CamtrackJson.jstr(raw, "direction", null),
            new String[]{DIR_CW, DIR_CCW}, DIR_CW);
        tr.speedDegS = Math.max(CamtrackJson.jnum(raw, "speedDegS", 6), 0.001);
        tr.arcBounds = boundsRule(raw, "arcMinDeg", "arcMaxDeg");
        tr.arcMinDeg = CamtrackJson.jnum(raw, "arcMinDeg", -90);
        tr.arcMaxDeg = CamtrackJson.jnum(raw, "arcMaxDeg", 90);
        tr.lookMode = pickLook(raw, TYPE_CIRCLE);
        double[] centerRef = tr.center;
        tr.lookAt = vec3Or(CamtrackJson.jget(raw, "lookAt"), centerRef.clone());
        tr.yawDeg = CamtrackJson.jnum(raw, "yawDeg", 0);
        tr.pitchDeg = CamtrackJson.jnum(raw, "pitchDeg", 0);
        break;
      }
      case TYPE_CURVE:
      case TYPE_POLYLINE: {
        List<double[]> pts = new ArrayList<>();
        List<Object> arr = CamtrackJson.asArr(CamtrackJson.jget(raw, "points"));
        if (arr != null) {
          for (Object p : arr) {
            double[] v = vec3(p, null);
            if (v != null) {
              pts.add(v);
            }
          }
        }
        if (pts.size() < 2) {
          return null;    // a curve/polyline needs at least 2 anchors
        }
        tr.points = pts.size() > 64 ? new ArrayList<>(pts.subList(0, 64)) : pts;
        tr.speedMS = Math.max(CamtrackJson.jnum(raw, "speedMS", 1), 0.001);
        tr.lookMode = pickLook(raw, type);
        tr.lookAt = vec3(CamtrackJson.jget(raw, "lookAt"),
            new double[]{0, 1, 0});
        tr.yawDeg = CamtrackJson.jnum(raw, "yawDeg", 0);
        tr.pitchDeg = CamtrackJson.jnum(raw, "pitchDeg", 0);
        break;
      }
      case TYPE_SPIRAL: {
        tr.center = vec3(CamtrackJson.jget(raw, "center"),
            new double[]{0, 1, 0});
        tr.radiusStart = Math.max(
            CamtrackJson.jnum(raw, "radiusStart", 2.5), 0.02);
        tr.radiusEnd = Math.max(CamtrackJson.jnum(raw, "radiusEnd", 1), 0.02);
        tr.yStart = CamtrackJson.jnum(raw, "yStart", 0);
        tr.yEnd = CamtrackJson.jnum(raw, "yEnd", 0);
        tr.startAngleDeg = CamtrackJson.jnum(raw, "startAngleDeg", 0);
        tr.direction = pick(CamtrackJson.jstr(raw, "direction", null),
            new String[]{DIR_CW, DIR_CCW}, DIR_CW);
        tr.turns = clamp(CamtrackJson.jnum(raw, "turns", 1.25), 0.05, 20);
        tr.speedDegS = Math.max(CamtrackJson.jnum(raw, "speedDegS", 15), 0.001);
        tr.lookMode = pickLook(raw, TYPE_SPIRAL);
        tr.lookAt = vec3Or(CamtrackJson.jget(raw, "lookAt"),
            tr.center.clone());
        tr.yawDeg = CamtrackJson.jnum(raw, "yawDeg", 0);
        tr.pitchDeg = CamtrackJson.jnum(raw, "pitchDeg", 0);
        break;
      }
      case TYPE_ZIGZAG: {
        tr.center = vec3(CamtrackJson.jget(raw, "center"),
            new double[]{0, 1, 0});
        tr.sizeX = Math.max(CamtrackJson.jnum(raw, "sizeX", 4), 0.05);
        tr.sizeZ = Math.max(CamtrackJson.jnum(raw, "sizeZ", 3), 0.05);
        double lv = CamtrackJson.jnum(raw, "lanes", 3);
        int lanes = (int) (lv >= 0 ? lv + 0.5 : lv - 0.5);   // Math.round
        if (lanes == 0) {
          lanes = 2;                       // the "|| 2" rule
        }
        tr.lanes = Math.max(2, Math.min(64, lanes));
        tr.speedMS = Math.max(CamtrackJson.jnum(raw, "speedMS", 1), 0.001);
        tr.lookMode = pickLook(raw, TYPE_ZIGZAG);
        tr.lookAt = vec3(CamtrackJson.jget(raw, "lookAt"),
            new double[]{0, 1, 0});
        tr.yawDeg = CamtrackJson.jnum(raw, "yawDeg", 0);
        tr.pitchDeg = CamtrackJson.jnum(raw, "pitchDeg", 0);
        break;
      }
      case TYPE_FIGURE8: {
        tr.center = vec3(CamtrackJson.jget(raw, "center"),
            new double[]{0, 1, 0});
        tr.semiX = Math.max(CamtrackJson.jnum(raw, "semiX", 2), 0.02);
        tr.semiZ = Math.max(CamtrackJson.jnum(raw, "semiZ", 1.5), 0.02);
        tr.startAngleDeg = CamtrackJson.jnum(raw, "startAngleDeg", 0);
        tr.direction = pick(CamtrackJson.jstr(raw, "direction", null),
            new String[]{DIR_CW, DIR_CCW}, DIR_CW);
        tr.speedDegS = Math.max(CamtrackJson.jnum(raw, "speedDegS", 12), 0.001);
        tr.lookMode = pickLook(raw, TYPE_FIGURE8);
        tr.lookAt = vec3Or(CamtrackJson.jget(raw, "lookAt"),
            tr.center.clone());
        tr.yawDeg = CamtrackJson.jnum(raw, "yawDeg", 0);
        tr.pitchDeg = CamtrackJson.jnum(raw, "pitchDeg", 0);
        break;
      }
      default: {  // fixed
        tr.position = vec3(CamtrackJson.jget(raw, "position"),
            new double[]{0, 1, 0});
        tr.yawDeg = CamtrackJson.jnum(raw, "yawDeg", 0);
        tr.pitchDeg = CamtrackJson.jnum(raw, "pitchDeg", 0);
        tr.yawSpeedDegS = CamtrackJson.jnum(raw, "yawSpeedDegS", 0);
        tr.pitchSpeedDegS = CamtrackJson.jnum(raw, "pitchSpeedDegS", 0);
        tr.yawBounds = boundsRule(raw, "yawMinDeg", "yawMaxDeg");
        tr.yawMinDeg = CamtrackJson.jnum(raw, "yawMinDeg", -45);
        tr.yawMaxDeg = CamtrackJson.jnum(raw, "yawMaxDeg", 45);
        tr.pitchBounds = boundsRule(raw, "pitchMinDeg", "pitchMaxDeg");
        tr.pitchMinDeg = CamtrackJson.jnum(raw, "pitchMinDeg", -10);
        tr.pitchMaxDeg = CamtrackJson.jnum(raw, "pitchMaxDeg", 10);
        tr.lookMode = null;   // fixed tracks hold their own orientation
        break;
      }
    }
    return tr;
  }

  /* ---- serialize (docToJSON/trackToJSON/sanitizeEnv port) ---------- */

  /** serialize the document — byte-compatible with the v13 editor export */
  public String serialize() {
    Map<String, Object> root = new java.util.LinkedHashMap<>();
    root.put("format", "camdat");
    root.put("version", 1.0);
    root.put("generatedBy", generatedBy != null ? generatedBy : GENERATED_BY);
    root.put("units", unitsMap());
    root.put("doc", DOC_STRING);
    List<Object> modelsArr = new ArrayList<>();
    for (Model m : models) {
      Map<String, Object> mo = new java.util.LinkedHashMap<>();
      mo.put("file", m.file);
      mo.put("addedAt", m.addedAt != null ? m.addedAt : null);
      mo.put("bounds", boundsMap(m));
      mo.put("fogon", m.fogon);
      mo.put("nighton", m.nighton);
      mo.put("flashlighton", m.flashlighton);
      if (m.env != null) {
        mo.put("env", envMap(m.env));
      }
      if (m.light != null) {
        mo.put("light", lightMap(m.light));
      }
      List<Object> tracksArr = new ArrayList<>();
      for (Track t : m.tracks) {
        tracksArr.add(trackMap(t));
      }
      mo.put("tracks", tracksArr);
      modelsArr.add(mo);
    }
    root.put("models", modelsArr);
    return CamtrackJson.write(root);
  }

  private static Map<String, Object> unitsMap() {
    Map<String, Object> units = new java.util.LinkedHashMap<>();
    units.put("length", "meters");
    units.put("angle", "degrees");
    units.put("time", "seconds");
    units.put("up", "+Y");
    units.put("handedness", "right-handed (glTF)");
    return units;
  }

  private static Map<String, Object> boundsMap(Model m) {
    if (m.boundsMin == null || m.boundsMax == null) {
      return null;
    }
    Map<String, Object> bounds = new java.util.LinkedHashMap<>();
    bounds.put("min", rawVec3List(m.boundsMin));
    bounds.put("max", rawVec3List(m.boundsMax));
    return bounds;
  }

  /** bounds ride RAW — docToJSON writes `bounds: m.bounds || null` with NO
   *  r4 rounding (authoring-time glTF Box3 values, float32-derived) */
  private static List<Object> rawVec3List(double[] a) {
    List<Object> out = new ArrayList<>(3);
    out.add(a[0]);
    out.add(a[1]);
    out.add(a[2]);
    return out;
  }

  /** sanitizeEnv() port — the export whitelist + clamps, key order fixed */
  private static Map<String, Object> envMap(Env e) {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    if (e.pair0 != null && e.pair1 != null) {
      List<Object> pair = new ArrayList<>();
      pair.add((double) clampInt(e.pair0, 0, 20));
      pair.add((double) clampInt(e.pair1, 0, 20));
      out.put("pair", pair);
    }
    if (e.dayEnv != null) {
      out.put("dayEnv", (double) clampInt(e.dayEnv, 0, 20));
    }
    if (e.nightEnv != null) {
      out.put("nightEnv", (double) clampInt(e.nightEnv, 0, 20));
    }
    if (e.weatherTarget != null) {
      out.put("weatherTarget", (double) clampInt(e.weatherTarget, 0, 8));
    }
    if (e.bootWeather != null) {
      out.put("bootWeather", (double) clampInt(e.bootWeather, -1, 6));
    }
    if (e.lensFlare != null) {
      out.put("lensFlare", (double) clampInt(e.lensFlare, 0, 5));
    }
    if (e.flashlightOn != null) {
      out.put("flashlightOn", e.flashlightOn);
    }
    if (e.psxWobble != null) {
      out.put("psxWobble", e.psxWobble);
    }
    if (e.dither != null) {
      out.put("dither", e.dither);
    }
    if (e.particles != null) {
      out.put("particles", e.particles);
    }
    if (e.engine != null) {
      out.put("engine", e.engine);
    }
    return out;
  }

  /** trackToJSON() port — per-type whitelists, r4, optional omission */
  private static Map<String, Object> trackMap(Track tr) {
    Map<String, Object> o = new java.util.LinkedHashMap<>();
    o.put("id", tr.id);
    o.put("name", tr.name != null ? tr.name : "");
    o.put("type", tr.type);
    o.put("enabled", tr.enabled);
    o.put("fovDeg", r4(tr.fovDeg));
    o.put("loopMode", tr.loopMode);
    switch (tr.type) {
      case TYPE_LINE:
        o.put("start", vec3List(tr.start));
        o.put("end", vec3List(tr.end));
        o.put("speedMS", r4(tr.speedMS));
        putLookFields(o, tr);
        break;
      case TYPE_CIRCLE:
        o.put("center", vec3List(tr.center));
        o.put("radiusX", r4(tr.radiusX));
        o.put("radiusZ", r4(tr.radiusZ));
        o.put("startAngleDeg", r4(tr.startAngleDeg));
        o.put("direction", tr.direction);
        o.put("speedDegS", r4(tr.speedDegS));
        if (tr.arcBounds && tr.arcMaxDeg > tr.arcMinDeg) {
          o.put("arcMinDeg", r4(tr.arcMinDeg));
          o.put("arcMaxDeg", r4(tr.arcMaxDeg));
        }
        putLookFields(o, tr);
        break;
      case TYPE_CURVE:
      case TYPE_POLYLINE: {
        List<Object> pts = new ArrayList<>();
        for (double[] p : tr.points) {
          pts.add(vec3List(p));
        }
        o.put("points", pts);
        o.put("speedMS", r4(tr.speedMS));
        putLookFields(o, tr);
        break;
      }
      case TYPE_SPIRAL:
        o.put("center", vec3List(tr.center));
        o.put("radiusStart", r4(tr.radiusStart));
        o.put("radiusEnd", r4(tr.radiusEnd));
        o.put("yStart", r4(tr.yStart));
        o.put("yEnd", r4(tr.yEnd));
        o.put("startAngleDeg", r4(tr.startAngleDeg));
        o.put("direction", tr.direction);
        o.put("turns", r4(Math.abs(tr.turns)));
        o.put("speedDegS", r4(tr.speedDegS));
        putLookFields(o, tr);
        break;
      case TYPE_ZIGZAG:
        o.put("center", vec3List(tr.center));
        o.put("sizeX", r4(tr.sizeX));
        o.put("sizeZ", r4(tr.sizeZ));
        o.put("lanes", (double) tr.lanes);
        o.put("speedMS", r4(tr.speedMS));
        putLookFields(o, tr);
        break;
      case TYPE_FIGURE8:
        o.put("center", vec3List(tr.center));
        o.put("semiX", r4(tr.semiX));
        o.put("semiZ", r4(tr.semiZ));
        o.put("startAngleDeg", r4(tr.startAngleDeg));
        o.put("direction", tr.direction);
        o.put("speedDegS", r4(tr.speedDegS));
        putLookFields(o, tr);
        break;
      default:  // fixed
        o.put("position", vec3List(tr.position));
        o.put("yawDeg", r4(tr.yawDeg));
        o.put("pitchDeg", r4(tr.pitchDeg));
        o.put("yawSpeedDegS", r4(tr.yawSpeedDegS));
        o.put("pitchSpeedDegS", r4(tr.pitchSpeedDegS));
        if (tr.yawBounds && tr.yawMaxDeg > tr.yawMinDeg) {
          o.put("yawMinDeg", r4(tr.yawMinDeg));
          o.put("yawMaxDeg", r4(tr.yawMaxDeg));
        }
        if (tr.pitchBounds && tr.pitchMaxDeg > tr.pitchMinDeg) {
          o.put("pitchMinDeg", r4(tr.pitchMinDeg));
          o.put("pitchMaxDeg", r4(tr.pitchMaxDeg));
        }
        break;
    }
    if (tr.weatherTarget != null) {
      o.put("weatherTarget",
          (double) clampInt(tr.weatherTarget, 0, 8));
    }
    if (tr.envDay != null) {
      o.put("envDay", (double) clampInt(tr.envDay, 0, 20));
    }
    if (tr.envNight != null) {
      o.put("envNight", (double) clampInt(tr.envNight, 0, 20));
    }
    double duration = CamtrackMath.cycleDuration(tr);
    if (Double.isFinite(duration)) {
      o.put("durationSec", r4(duration));
    }
    return o;
  }

  /** the shared lookMode/lookAt/yaw/pitch/lazy export block */
  private static void putLookFields(Map<String, Object> o, Track tr) {
    o.put("lookMode", tr.lookMode);
    if (LOOK_NORMAL.equals(tr.lookMode)) {
      o.put("normalDirection",
          SIDE_BACKWARD.equals(tr.normalDirection) ? SIDE_BACKWARD
              : SIDE_FORWARD);
    }
    if (LOOK_POINT.equals(tr.lookMode)) {
      o.put("lookAt", vec3List(tr.lookAt));
    }
    if (LOOK_FIXED.equals(tr.lookMode)) {
      o.put("yawDeg", r4(tr.yawDeg));
      o.put("pitchDeg", r4(tr.pitchDeg));
    }
    if (LOOK_LAZY.equals(tr.lookMode)) {
      /* sanitizeTrack always sets lazyRateDegS (default 25, the v9.1 fix)
       * — the == null ? 25 branch of trackToJSON is unreachable here */
      o.put("lazyRateDegS", r4(tr.lazyRateDegS));
    }
  }

  /* ---- makeTrack (the v13 add-track defaults) ---------------------- */

  /**
   * makeTrack() port — a new track of the given type with the v13
   * editor's defaults, placed around the model's center (from bounds).
   */
  public static Track makeTrack(String type, Model model) {
    double[] c = model != null ? model.center() : new double[]{0, 1, 0};
    double[] s = model != null ? model.size() : new double[]{10, 10, 10};
    double y = r4(c[1]);
    Track t = new Track();
    t.id = newId();
    t.type = type;
    t.enabled = true;
    t.fovDeg = 60;
    t.lazyRateDegS = 25;
    t.weatherTarget = null;
    switch (type) {
      case TYPE_LINE: {
        double dx = Math.max(s[0] * 0.3, 0.5);
        t.start = new double[]{r4(c[0] - dx), y, r4(c[2])};
        t.end = new double[]{r4(c[0] + dx), y, r4(c[2])};
        t.speedMS = r4(Math.max(dist3(t.start, t.end) / 12.0, 0.05));
        t.loopMode = LOOP_PINGPONG;
        t.lookMode = LOOK_PATH;
        t.normalDirection = SIDE_FORWARD;
        t.lookAt = new double[]{r4(c[0]), y, r4(c[2])};
        t.yawDeg = 0;
        t.pitchDeg = 0;
        t.name = nextName(model, "Line");
        break;
      }
      case TYPE_CIRCLE: {
        double r = r4(Math.max(Math.min(s[0], s[2]) * 0.35, 0.2));
        t.center = new double[]{r4(c[0]), y, r4(c[2])};
        t.radiusX = r;
        t.radiusZ = r;
        t.startAngleDeg = 0;
        t.direction = DIR_CW;
        t.speedDegS = 6;
        t.arcBounds = false;
        t.arcMinDeg = -90;
        t.arcMaxDeg = 90;
        t.loopMode = LOOP_LOOP;
        t.lookMode = LOOK_CENTER;
        t.normalDirection = SIDE_FORWARD;
        t.lookAt = t.center.clone();
        t.yawDeg = 0;
        t.pitchDeg = 0;
        t.name = nextName(model, "Circle");
        break;
      }
      case TYPE_CURVE: {
        double dx = Math.max(s[0] * 0.3, 0.5);
        double dz = Math.max(s[2] * 0.22, 0.3);
        t.points = new ArrayList<>();
        t.points.add(new double[]{r4(c[0] - dx), y, r4(c[2])});
        t.points.add(new double[]{r4(c[0]), y, r4(c[2] + dz)});
        t.points.add(new double[]{r4(c[0] + dx), y, r4(c[2])});
        double l0 = dist3(t.points.get(0), t.points.get(1))
            + dist3(t.points.get(1), t.points.get(2));
        t.speedMS = r4(Math.max(l0 / 12.0, 0.05));
        t.loopMode = LOOP_PINGPONG;
        t.lookMode = LOOK_PATH;
        t.normalDirection = SIDE_FORWARD;
        t.lookAt = new double[]{r4(c[0]), y, r4(c[2])};
        t.yawDeg = 0;
        t.pitchDeg = 0;
        t.name = nextName(model, "Curve");
        break;
      }
      case TYPE_POLYLINE: {
        double dx = Math.max(s[0] * 0.3, 0.5);
        double dz = Math.max(s[2] * 0.35, 0.5);
        t.points = new ArrayList<>();
        t.points.add(new double[]{r4(c[0] - dx), y, r4(c[2] - dz)});
        t.points.add(new double[]{r4(c[0]), y, r4(c[2] - dz)});
        t.points.add(new double[]{r4(c[0]), y, r4(c[2] + dz)});
        t.points.add(new double[]{r4(c[0] + dx), y, r4(c[2] + dz)});
        double l0 = 0;
        for (int i = 0; i < t.points.size() - 1; i++) {
          l0 += dist3(t.points.get(i), t.points.get(i + 1));
        }
        t.speedMS = r4(Math.max(l0 / 14.0, 0.05));
        t.loopMode = LOOP_PINGPONG;
        t.lookMode = LOOK_PATH;
        t.normalDirection = SIDE_FORWARD;
        t.lookAt = new double[]{r4(c[0]), y, r4(c[2])};
        t.yawDeg = 0;
        t.pitchDeg = 0;
        t.name = nextName(model, "Polyline");
        break;
      }
      case TYPE_SPIRAL: {
        t.center = new double[]{r4(c[0]), y, r4(c[2])};
        t.radiusStart = r4(Math.max(Math.min(s[0], s[2]) * 0.42, 0.3));
        t.radiusEnd = r4(Math.max(Math.min(s[0], s[2]) * 0.15, 0.1));
        t.yStart = 0;
        t.yEnd = r4(Math.max(s[1] * 0.12, 0));
        t.startAngleDeg = 0;
        t.direction = DIR_CW;
        t.turns = 1.25;
        t.speedDegS = 18;
        t.loopMode = LOOP_PINGPONG;
        t.lookMode = LOOK_CENTER;
        t.normalDirection = SIDE_FORWARD;
        t.lookAt = t.center.clone();
        t.yawDeg = 0;
        t.pitchDeg = 0;
        t.name = nextName(model, "Spiral");
        break;
      }
      case TYPE_ZIGZAG: {
        t.center = new double[]{r4(c[0]), y, r4(c[2])};
        t.sizeX = r4(Math.max(s[0] * 0.8, 0.6));
        t.sizeZ = r4(Math.max(s[2] * 0.7, 0.4));
        t.lanes = 3;
        t.speedMS = r4(Math.max((t.sizeX * 3 + t.sizeZ) / 14.0, 0.05));
        t.loopMode = LOOP_PINGPONG;
        t.lookMode = LOOK_PATH;
        t.normalDirection = SIDE_FORWARD;
        t.lookAt = new double[]{r4(c[0]), y, r4(c[2])};
        t.yawDeg = 0;
        t.pitchDeg = 0;
        t.name = nextName(model, "Zigzag");
        break;
      }
      case TYPE_FIGURE8: {
        t.center = new double[]{r4(c[0]), y, r4(c[2])};
        t.semiX = r4(Math.max(s[0] * 0.38, 0.3));
        t.semiZ = r4(Math.max(s[2] * 0.3, 0.2));
        t.startAngleDeg = 0;
        t.direction = DIR_CW;
        t.speedDegS = 15;
        t.loopMode = LOOP_LOOP;
        t.lookMode = LOOK_CENTER;
        t.normalDirection = SIDE_FORWARD;
        t.lookAt = t.center.clone();
        t.yawDeg = 0;
        t.pitchDeg = 0;
        t.name = nextName(model, "Figure 8");
        break;
      }
      default: {  // fixed
        t.position = new double[]{
            r4(c[0] + Math.max(s[0] * 0.25, 0.4)), y, r4(c[2])};
        double dx = c[0] - t.position[0];
        double dz = c[2] - t.position[2];
        t.yawDeg = r4(Math.atan2(-dx, -dz) / Math.PI * 180.0);
        t.pitchDeg = 0;
        t.yawSpeedDegS = 3;
        t.pitchSpeedDegS = 0;
        t.yawBounds = false;
        t.yawMinDeg = -45;
        t.yawMaxDeg = 45;
        t.pitchBounds = false;
        t.pitchMinDeg = -10;
        t.pitchMaxDeg = 10;
        t.loopMode = LOOP_PINGPONG;
        t.lookMode = null;
        t.name = nextName(model, "Fixed");
        break;
      }
    }
    return t;
  }

  /** nextName(): "Line 1", "Line 2", ... — the first unused name */
  static String nextName(Model model, String base) {
    if (model == null) {
      return base + " 1";
    }
    int n = 1;
    while (true) {
      String candidate = base + ' ' + n;
      boolean taken = false;
      for (Track t : model.tracks) {
        if (candidate.equals(t.name)) {
          taken = true;
          break;
        }
      }
      if (!taken) {
        return candidate;
      }
      n++;
    }
  }

  /* ---- ids ---------------------------------------------------------- */

  private static final AtomicLong ID_COUNTER = new AtomicLong(0);

  /** newId(): 't' + base36 time + base36 counter + 4 random base36 chars */
  static String newId() {
    long time = System.currentTimeMillis();
    long ctr = ID_COUNTER.incrementAndGet();
    String rand = Long.toString(
        (long) (Math.random() * 1679616.0), 36);   // 36^4 space
    while (rand.length() < 4) {
      rand = "0" + rand;
    }
    if (rand.length() > 4) {
      rand = rand.substring(rand.length() - 4);
    }
    return "t" + Long.toString(time, 36) + Long.toString(ctr, 36) + rand;
  }

  /** public id generator access (duplicateTrack et al.) */
  public static String newIdPublic() {
    return newId();
  }

  /* ---- validation (the save gate — the v9.6 bridge rules) ---------- */

  /** non-empty reason string when the document must NOT be saved */
  public String validateForSave() {
    if (models.isEmpty()) {
      return "no models";
    }
    for (Model m : models) {
      if (sanitizeFileName(m.file) == null) {
        return "bad model file name: " + m.file;
      }
      if (m.tracks.isEmpty()) {
        return "model " + m.file + " has no tracks";
      }
    }
    return null;
  }

  /**
   * The same basename rule the JNI start() enforces (and Sh1Camdat
   * applies): separators stripped, ""/"."/".."/any ".." rejected — a
   * camdat file name must never escape filesDir/sh1.
   */
  public static String sanitizeFileName(String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    String name = raw;
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    if (name.isEmpty() || name.equals(".") || name.equals("..")
        || name.contains("..")) {
      return null;
    }
    return name;
  }

  /* ---- shared numeric helpers (the JS editor's) -------------------- */

  /**
   * r4(): Math.round(v*10000)/10000 — the editor's export rounding.
   *
   * <p><b>v9.8 fix (Phase H harness finding):</b> the reference's
   * {@code Math.round} operates on JavaScript <i>doubles</i>
   * ({@code floor(x + 0.5)}); Java's {@code Math.round(double)} truncates
   * to {@code long}, clamping everything beyond {@code Long.MAX_VALUE}
   * — which silently garbled every {@code |v| > ~9.2e14} (e.g.
   * {@code r4(1e18) -> 9.223372036854776e14}) and returned
   * {@code Long.MAX_VALUE/10000} garbage for the 1e308-class adversarial
   * coordinates. The JS editor itself corrupts there instead
   * ({@code Math.round(Infinity) -> Infinity -> JSON.stringify ->
   * null} — data loss on reload). This port now implements the reference's
   * exact double semantics: {@code floor(scaled + 0.5)} — bit-identical to
   * the JS editor for the entire sane camdat domain ({@code |scaled| <
   * 2^52}, where it also equals Java's {@code Math.round}) — and returns
   * {@code v} unchanged in the overflow range (a no-op rounding: the value
   * stays a finite, valid JSON number instead of either the long-clamped
   * garbage or the reference's {@code null}).</p>
   */
  public static double r4(double v) {
    double scaled = v * 10000.0;
    if (Double.isNaN(scaled) || Double.isInfinite(scaled)) {
      return v;
    }
    return Math.floor(scaled + 0.5) / 10000.0;
  }

  static double clamp(double v, double lo, double hi) {
    return Math.min(Math.max(v, lo), hi);
  }

  static int clampInt(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  static int clampInt(double v, int lo, int hi) {
    int iv = (int) v;   // | 0 truncation toward zero, like JS
    return clampInt(iv, lo, hi);
  }

  static double dist3(double[] a, double[] b) {
    double dx = b[0] - a[0];
    double dy = b[1] - a[1];
    double dz = b[2] - a[2];
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /** vec3(v, def): [x,y,z] of finite numbers, else def (null passes on) */
  static double[] vec3(Object v, double[] def) {
    List<Object> arr = CamtrackJson.asArr(v);
    if (arr == null || arr.size() < 3) {
      return def;
    }
    Double x = toFinite(arr.get(0));
    Double y = toFinite(arr.get(1));
    Double z = toFinite(arr.get(2));
    if (x == null || y == null || z == null) {
      return def;
    }
    return new double[]{x, y, z};
  }

  static double[] vec3Or(Object v, double[] def) {
    double[] r = vec3(v, def);
    return r != null ? r : def;
  }

  private static Double toFinite(Object o) {
    if (o instanceof Double) {
      double d = (Double) o;
      return isFinite(d) ? d : null;
    }
    return null;
  }

  /** Double.isFinite is API 24+ — inline the minSdk-23-safe check */
  static boolean isFinite(double d) {
    return !Double.isNaN(d) && !Double.isInfinite(d);
  }

  /** pick(v, list, def) — the JS pick() over a string domain */
  static String pick(String v, String[] domain, String def) {
    if (v != null) {
      for (String d : domain) {
        if (d.equals(v)) {
          return v;
        }
      }
    }
    return def;
  }

  private static String pickLook(Map<String, Object> raw, String type) {
    String rawLook = CamtrackJson.jstr(raw, "lookMode", null);
    return pick(rawLook, Track.lookModesFor(type), Track.defaultLookMode(type));
  }

  /**
   * The bounds-flag reconstruction rule: min != null AND max != null AND
   * num(max, 1) > num(min, 0) — used for arc / yaw / pitch bounds.
   */
  private static boolean boundsRule(
      Map<String, Object> raw, String minKey, String maxKey) {
    Object mn = CamtrackJson.jget(raw, minKey);
    Object mx = CamtrackJson.jget(raw, maxKey);
    if (mn == null || mx == null) {
      return false;
    }
    double mnv = mn instanceof Double ? (Double) mn : 0;
    double mxv = mx instanceof Double ? (Double) mx : 1;
    return mxv > mnv;
  }

  /**
   * The strict-number optional-clamp rule (weatherTarget/envDay/envNight):
   * present AND a finite number → clamped int; anything else → null.
   */
  private static Integer optionalClamp(
      Map<String, Object> raw, String key, int lo, int hi) {
    Object v = CamtrackJson.jget(raw, key);
    if (v instanceof Double && isFinite((Double) v)) {
      return clampInt((Double) v, lo, hi);
    }
    return null;
  }

  private static List<Object> vec3List(double[] a) {
    List<Object> out = new ArrayList<>(3);
    out.add(r4(a[0]));
    out.add(r4(a[1]));
    out.add(r4(a[2]));
    return out;
  }

  private static String isoNow() {
    /* new Date().toISOString() — millisecond precision, Z suffix; plain
     * java.text (minSdk 23: no java.time without desugaring) */
    java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT);
    fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    return fmt.format(new java.util.Date());
  }
}
