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

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Java-side view of the active camdat.json (the CamTrack Editor format the
 * engine itself parses verbatim in cpp/fog_poc.c camdat_load). The UI uses
 * this to list the scenes defined by the ACTIVE config (models[] with
 * their camera tracks), to validate that every model's GLB is actually
 * present in the app's asset directory, and to remember which
 * model/track the wallpaper should boot with.
 *
 * v9.2 Feature 30: a WALLPAPER is ONE TRACK of ONE model. Every track
 * parsed from the camdat — even when several tracks originate from the
 * exact same file/model — is an individual, distinct wallpaper. The
 * flattened {@link #wallpapers()} list enumerates them all in camdat
 * order (model order, then track order inside each model), exactly the
 * order the engine itself indexes tracks with its own --track N flag.
 *
 * The authoritative parsing of every field stays in the ENGINE — this
 * class reads only what the settings UI needs: models[].file,
 * models[].tracks[] (id, name, enabled) and models[].env (for the scene
 * subtitle). Anything absent degrades to the documented defaults, exactly
 * like the engine's own jstr()/jbool() lookups.
 */
public final class Sh1Camdat {

  private static final String TAG = Sh1Camdat.class.getSimpleName();

  /** one camera track of a model (subset the UI displays) */
  public static class Track {
    public final String id;
    public final String name;
    public final boolean enabled;
    /**
     * v9.5 (Camtrack v12): the track's OWN weather — models[].tracks[].
     * weatherTarget, the sharedFunc_800D0B18 argument 0..8. -1 = absent:
     * the track inherits the model env's weatherTarget (the editor's own
     * layering: track > model; the engine's camdat fold resolves the
     * same way before the boot cross-fade).
     */
    public final int weatherTarget;
    /**
     * v9.6 (Camtrack v13): the track's DAY env override — models[].
     * tracks[].envDay, a MAP_EFFECTS_INFOS index 0..20. -1 = absent:
     * the wallpaper's Auto Mode inherits the model's env.dayEnv for
     * this track. Mirrors the app-side per-track override layering
     * (app override > camdat track > camdat model > app custom >
     * authentic default).
     */
    public final int envDay;
    /** v9.6 (Camtrack v13): the track's NIGHT env override (envNight) */
    public final int envNight;

    Track(String id, String name, boolean enabled, int weatherTarget,
        int envDay, int envNight) {
      this.id = id == null ? "" : id;
      this.name = name == null || name.isEmpty() ? "Track" : name;
      this.enabled = enabled;
      this.weatherTarget = weatherTarget;
      this.envDay = envDay;
      this.envNight = envNight;
    }
  }

  /** one scene = one models[] entry of camdat.json */
  public static class Model {
    public String file;
    public boolean fogOn;
    public boolean nightOn;
    public int envPair0;
    public int envPair1;
    public int weatherTarget;
    /** v9.6 (Camtrack v13): the model's Auto-Mode DAY preset (models[].
     * env.dayEnv, 0..20); -1 = absent (falls back to the app layers) */
    public int envDay;
    /** v9.6 (Camtrack v13): the model's Auto-Mode NIGHT preset (-1 = absent) */
    public int envNight;
    public final List<Track> tracks = new ArrayList<>();
    /** true when <filesDir>/sh1/<file> exists (BYOA check) */
    public boolean glbPresent;

    Model(String file) {
      this.file = file;
      this.envPair0 = -1;
      this.envPair1 = -1;
      this.weatherTarget = -1;
      this.envDay = -1;
      this.envNight = -1;
      this.fogOn = false;
      this.nightOn = false;
    }

    /** first enabled track index (the engine's own playTarget rule) */
    public int firstEnabledTrack() {
      for (int i = 0; i < tracks.size(); i++) {
        if (tracks.get(i).enabled) {
          return i;
        }
      }
      return tracks.isEmpty() ? -1 : 0;
    }

    /** the track with the given index, null when out of range */
    public Track track(int index) {
      if (index < 0 || index >= tracks.size()) {
        return null;
      }
      return tracks.get(index);
    }
  }

  /**
   * v9.2 Feature 30: ONE WALLPAPER = one track of one model. Multiple
   * tracks originating from the exact same file/model are as many
   * DISTINCT wallpapers. Carries everything the selector UI and the
   * override system need: the model indices for the persisted selection
   * ({@link Constants.PREF#SH1_SCENE}/{@link Constants.PREF#SH1_TRACK}
   * -> the engine's own --track N) and the stable editor track id used
   * as the per-wallpaper override key.
   */
  public static class Wallpaper {
    public final int modelIndex;
    public final int trackIndex;
    public final String trackId;
    public final String name;
    public final boolean enabled;
    public final Model model;
    /** v9.5 (Camtrack v12): the track object itself (the weather source) */
    public final Track track;

    Wallpaper(int modelIndex, int trackIndex, Track track, Model model) {
      this.modelIndex = modelIndex;
      this.trackIndex = trackIndex;
      this.trackId = track.id;
      this.name = track.name;
      this.enabled = track.enabled;
      this.model = model;
      this.track = track;
    }

    /**
     * v9.5 (Camtrack v12): this wallpaper's camdat weather — the track's
     * OWN weatherTarget when present, else the model env's; -1 when both
     * are absent (the engine's map0 default applies). Mirrors the
     * engine's per-track camdat fold exactly (track > model).
     */
    public int camdatWeather() {
      return track.weatherTarget >= 0 ? track.weatherTarget : model.weatherTarget;
    }

    /**
     * v9.6 (Camtrack v13): this wallpaper's camdat DAY env preset — the
     * track's OWN envDay override when present, else the model's
     * env.dayEnv; -1 when neither is authored (the app layers resolve).
     * Mirrors the engine-facing resolution the service performs
     * (track > model).
     */
    public int camdatDayEnv() {
      return track.envDay >= 0 ? track.envDay : model.envDay;
    }

    /** v9.6 (Camtrack v13): the NIGHT env preset (track > model, -1 absent) */
    public int camdatNightEnv() {
      return track.envNight >= 0 ? track.envNight : model.envNight;
    }

    /** true when this wallpaper's model GLB is present (playable) */
    public boolean playable() {
      return model != null && model.glbPresent;
    }

    /** selector label: the track name (the individual wallpaper) */
    public String label() {
      return name;
    }
  }

  private final List<Model> models = new ArrayList<>();
  private final List<Wallpaper> wallpaperList = new ArrayList<>();
  private boolean valid;

  /** parse the ACTIVE camdat.json: the device-protected data dir first
   * (v10.6 direct boot — readable before first unlock), bundled asset as
   * the fallback */
  public static Sh1Camdat load(Context context) {
    Sh1Camdat cd = new Sh1Camdat();
    JSONObject root = null;
    try {
      File file = new File(Sh1Storage.sh1Dir(context), "camdat.json");
      if (file.exists()) {
        try (InputStream in = new FileInputStream(file)) {
          root = readJson(in);
        }
      } else {
        try (InputStream in = context.getAssets().open("sh1/camdat.json")) {
          root = readJson(in);
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "load: camdat parse failed", e);
    }
    if (root != null) {
      cd.parse(root);
    }
    cd.checkAssets(context);
    return cd;
  }

  private static JSONObject readJson(InputStream in) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[64 * 1024];
    int n;
    while ((n = in.read(buf)) > 0) {
      bos.write(buf, 0, n);
    }
    return new JSONObject(bos.toString("UTF-8"));
  }

  private void parse(JSONObject root) {
    try {
      JSONArray arr = root.optJSONArray("models");
      if (arr == null) {
        return;
      }
      for (int i = 0; i < arr.length(); i++) {
        JSONObject m = arr.optJSONObject(i);
        if (m == null) {
          continue;
        }
        String file = sanitizeFileName(m.optString("file", ""));
        if (file == null) {
          continue;   // path traversal attempt or empty — skipped
        }
        Model model = new Model(file);
        model.fogOn = m.optBoolean("fogon", false);
        model.nightOn = m.optBoolean("nighton", false);
        JSONObject env = m.optJSONObject("env");
        if (env != null) {
          JSONArray pair = env.optJSONArray("pair");
          if (pair != null && pair.length() >= 2) {
            model.envPair0 = clamp(pair.optInt(0, -1), -1, 20);
            model.envPair1 = clamp(pair.optInt(1, -1), -1, 20);
          }
          model.weatherTarget = clamp(env.optInt("weatherTarget", -1), -1, 8);
          // v9.6 (Camtrack v13): the Auto-Mode day/night presets
          model.envDay = clamp(env.optInt("dayEnv", -1), -1, 20);
          model.envNight = clamp(env.optInt("nightEnv", -1), -1, 20);
        }
        JSONArray tracks = m.optJSONArray("tracks");
        if (tracks != null) {
          for (int t = 0; t < tracks.length(); t++) {
            JSONObject tr = tracks.optJSONObject(t);
            if (tr == null) {
              continue;
            }
            model.tracks.add(new Track(
                tr.optString("id", ""),
                tr.optString("name", "Track"),
                tr.optBoolean("enabled", true),
                // v9.5 (Camtrack v12): the per-track weather data — strict
                // number clamped to the engine's 0..8 D0B18 range, -1 absent
                clamp(tr.optInt("weatherTarget", -1), -1, 8),
                // v9.6 (Camtrack v13): the per-track day/night env
                // overrides — strict numbers clamped to 0..20, -1 absent
                clamp(tr.optInt("envDay", -1), -1, 20),
                clamp(tr.optInt("envNight", -1), -1, 20)
            ));
          }
        }
        if (!model.tracks.isEmpty()) {
          models.add(model);   // the engine needs >= 1 usable track
        }
      }
      // v9.2 Feature 30: flatten EVERY track into an individual wallpaper
      // (camdat order: model order, then track order inside each model —
      // the engine's own --track N indexing for the same file)
      for (int m = 0; m < models.size(); m++) {
        Model model = models.get(m);
        for (int t = 0; t < model.tracks.size(); t++) {
          wallpaperList.add(new Wallpaper(m, t, model.tracks.get(t), model));
        }
      }
      valid = !models.isEmpty();
    } catch (Exception e) {
      Log.e(TAG, "parse: failed", e);
      valid = false;
    }
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  /** BYOA check: which models have their GLB in the device-protected sh1 dir */
  private void checkAssets(Context context) {
    File dir = Sh1Storage.sh1Dir(context);
    for (Model m : models) {
      m.glbPresent = new File(dir, m.file).exists();
    }
  }

  public boolean isValid() {
    return valid;
  }

  public int modelCount() {
    return models.size();
  }

  public Model getModel(int index) {
    if (index < 0 || index >= models.size()) {
      return null;
    }
    return models.get(index);
  }

  /**
   * v9.2 Feature 30: EVERY individual wallpaper (one per camdat track,
   * including multiple tracks originating from the same file/model), in
   * camdat order. The list is live-shared (do not mutate).
   */
  public List<Wallpaper> wallpapers() {
    return wallpaperList;
  }

  /** the number of individual wallpapers (v9.2 Feature 30) */
  public int wallpaperCount() {
    return wallpaperList.size();
  }

  /**
   * the wallpaper for a persisted (model, track) selection; null when the
   * selection does not point at a parseable track
   */
  public Wallpaper wallpaper(int modelIndex, int trackIndex) {
    for (Wallpaper w : wallpaperList) {
      if (w.modelIndex == modelIndex && w.trackIndex == trackIndex) {
        return w;
      }
    }
    return null;
  }

  /**
   * the wallpaper carrying a given editor track id (stable across camdat
   * re-ordering); null when no such track exists
   */
  public Wallpaper wallpaperById(String trackId) {
    if (trackId == null || trackId.isEmpty()) {
      return null;
    }
    for (Wallpaper w : wallpaperList) {
      if (trackId.equals(w.trackId)) {
        return w;
      }
    }
    return null;
  }

  /**
   * Sanitize a camdat file name to a plain basename. Returns null for
   * anything that could escape the asset directory (path separators,
   * "..", empty) — the same rule the JNI start() enforces before the
   * name reaches the engine's argv.
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
}
