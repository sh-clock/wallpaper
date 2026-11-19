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

package xyz.zedler.patrick.doodle;

import android.content.SharedPreferences;
import android.util.Log;
import androidx.appcompat.app.AppCompatDelegate;
import java.util.Calendar;

public final class Constants {

  private static final String TAG = Constants.class.getSimpleName();

  public static final class PREF {

    // SH1 Environment

    public static final String SH1_ENV = "sh1_env";            // 0..20 (MAP_EFFECTS_INFOS index)
    public static final String SH1_AUTO = "sh1_auto";          // day/night follows device clock
    public static final String SH1_WEATHER = "sh1_weather";    // -1 scene default, 0..8 override

    // SH1 Scene & camdat (Phase D/E)

    public static final String SH1_SCENE = "sh1_scene";        // camdat models[] index of the active scene
    public static final String SH1_TRACK = "sh1_track";        // track index in that model, -1 = first enabled

    // SH1 Day/Night schedule (Phase E-2)

    public static final String SH1_DAY_HOUR = "sh1_day_hour";
    public static final String SH1_DAY_MINUTE = "sh1_day_minute";
    public static final String SH1_NIGHT_HOUR = "sh1_night_hour";
    public static final String SH1_NIGHT_MINUTE = "sh1_night_minute";

    // SH1 Scene randomizer (Phase E-3)

    public static final String SH1_RANDOMIZER = "sh1_randomizer";      // see Constants.SH1.RANDOM_*
    public static final String SH1_RANDOM_INTERVAL = "sh1_random_interval";  // minutes

    // SH1 Randomizer extras (v7 Phase H: F2 shuffle bag, F3 favorites,
    // F4 pre-warm)

    public static final String SH1_SHUFFLE_BAG = "sh1_shuffle_bag";
    public static final String SH1_FAVORITES = "sh1_favorites";          // comma-joined model file names
    public static final String SH1_FAVORITES_ONLY = "sh1_favorites_only";
    public static final String SH1_PREWARM = "sh1_prewarm";

    // SH1 Battery / power save (Phase E-4/E-5 + v7 F10/F12/F16)

    public static final String SH1_BATTERY_CRITICAL = "sh1_battery_critical";  // percent
    public static final String SH1_POWER_SAVE_COMPLY = "sh1_power_save_comply";
    public static final String SH1_BATTERY_MID = "sh1_battery_mid";      // half-rate threshold, percent
    public static final String SH1_BATTERY_HALFRATE = "sh1_battery_halfrate";
    public static final String SH1_CHARGE_EXEMPT = "sh1_charge_exempt";
    public static final String SH1_AOD_FREEZE = "sh1_aod_freeze";

    // SH1 Presentation (v7 Phase H: F1 adaptive pacing, F17 resolution,
    // F18 frame-rate cap)

    public static final String SH1_ADAPTIVE_PACING = "sh1_adaptive_pacing";
    public static final String SH1_RESOLUTION = "sh1_resolution";        // v9 F25: ladder index (see RES_MODES)
    public static final String SH1_FPS_CAP = "sh1_fps_cap";              // v9 F26: 1..60 directly (was enum)

    // SH1 v9.0 Phase H additions

    public static final String SH1_WOBBLE = "sh1_wobble";                // F24: PSX wobble toggle
    public static final String SH1_HW_BACKEND = "sh1_hw_backend";        // v9.9: 0 sw, 1 gles, 2 vk
    public static final String SH1_DITHER = "sh1_dither";                // v10.0: PS1 ordered-dither (user choice)
    public static final String SH1_DITHER_USER_SET = "sh1_dither_user"; // v10.0: the user explicitly chose a dither state
    public static final String SH1_CROP_X = "sh1_crop_x";                // v10.1 TASK 4: crop-window pan, normalized -1..1
    public static final String SH1_CROP_Y = "sh1_crop_y";                // v10.1 TASK 4: crop-window pan, normalized -1..1
    public static final String SH1_TRANSITION = "sh1_transition";        // F20: gradual day/night blend
    public static final String SH1_SUN_MODE = "sh1_sun_mode";            // F22: 0 clock schedule, 1 sun time
    public static final String SH1_CITY = "sh1_city";                    // F22: "name|country|lat|lon"
    public static final String SH1_TILT = "sh1_tilt";                    // F23: device tilt camera
    public static final String SH1_TILT_THRESHOLD = "sh1_tilt_threshold";// F23: engage threshold, degrees
    public static final String SH1_TILT_MAX = "sh1_tilt_max";            // F23: max camera shift, degrees
    public static final String SH1_TILT_RATE = "sh1_tilt_rate";          // F23: sensor polling, Hz

    // SH1 BYOA / config / safety (v7 Phase H: F5 storage guard, F6 watchdog)

    public static final String SH1_STORAGE_GUARD = "sh1_storage_guard";
    public static final String SH1_WATCHDOG = "sh1_watchdog";

    // SH1 Auto-Mode preset mapping (v7 Phase H: F19)

    public static final String SH1_AUTO_CUSTOM = "sh1_auto_custom";
    public static final String SH1_AUTO_DAY_ENV = "sh1_auto_day_env";    // 0..20
    public static final String SH1_AUTO_NIGHT_ENV = "sh1_auto_night_env";  // 0..20

    // SH1 Per-scene env/weather overrides (v7 Phase H: F11)

    public static final String SH1_SCENE_OVERRIDES = "sh1_scene_overrides";  // master toggle

    // SH1 Quick Settings tiles (v7 Phase H: F15)

    public static final String SH1_TILE_NEXT = "sh1_tile_next";
    public static final String SH1_TILE_FREEZE = "sh1_tile_freeze";
    public static final String SH1_MANUAL_FREEZE = "sh1_manual_freeze";  // tile-driven freeze contributor

    // SH1 Scene thumbnails (v7 Phase H: F9)

    public static final String SH1_THUMBNAILS = "sh1_thumbnails";

    // Other

    public static final String LANGUAGE = "language";
    public static final String THEME = "app_theme";
    public static final String UI_MODE = "mode";
    public static final String UI_CONTRAST = "ui_contrast";

    public static final String LAST_VERSION = "last_version";
  }

  public static final class DEF {

    public static final int SH1_ENV = Constants.SH1.ENV_DAY;
    public static final boolean SH1_AUTO = true;
    public static final int SH1_WEATHER = Constants.SH1.WEATHER_SCENE;

    public static final int SH1_SCENE = 0;
    public static final int SH1_TRACK = -1;

    public static final int SH1_DAY_HOUR = 6;
    public static final int SH1_DAY_MINUTE = 0;
    public static final int SH1_NIGHT_HOUR = 18;
    public static final int SH1_NIGHT_MINUTE = 0;

    public static final int SH1_RANDOMIZER = Constants.SH1.RANDOM_OFF;
    public static final int SH1_RANDOM_INTERVAL = 30;

    public static final boolean SH1_SHUFFLE_BAG = false;     // v6 behavior = plain random (conservative)
    public static final String SH1_FAVORITES = "";
    public static final boolean SH1_FAVORITES_ONLY = false;
    public static final boolean SH1_PREWARM = true;          // IO-only page-cache hint, zero visual effect

    public static final int SH1_BATTERY_CRITICAL = 15;
    public static final boolean SH1_POWER_SAVE_COMPLY = true;
    public static final int SH1_BATTERY_MID = 30;            // only active while the half-rate toggle is ON
    public static final boolean SH1_BATTERY_HALFRATE = false; // alters presentation -> off by default
    public static final boolean SH1_CHARGE_EXEMPT = true;    // v6-verified behavior (freeze skips charging)
    public static final boolean SH1_AOD_FREEZE = false;      // engine already idles while invisible

    public static final boolean SH1_ADAPTIVE_PACING = false; // mandate: disabled by default
    public static final int SH1_RESOLUTION = 0;              // v9 F25: ladder index 0 = 320x224 (authentic)
    public static final int SH1_FPS_CAP = 30;                // v9 F26: authentic 30 fps default

    public static final boolean SH1_WOBBLE = true;           // F24: authentic per-scene camdat/engine default
    public static final int SH1_HW_BACKEND = 0;               // v9.9: SOFTWARE — the default + source of truth
    public static final boolean SH1_DITHER = true;            // v10.0: authentic PS1 ordered-dither at native res
    public static final boolean SH1_DITHER_USER_SET = false;  // v10.0: the auto rule applies until the user chooses
    public static final float SH1_CROP_X = 0.0f;              // v10.1: symmetric center crop (the authentic look)
    public static final float SH1_CROP_Y = 0.0f;              // v10.1: symmetric center crop
    public static final boolean SH1_TRANSITION = true;       // F20: the game's own gradual blend at boundaries
    public static final boolean SH1_SUN_MODE = false;        // F22: clock schedule is the v8 default; sun-time opt-in
    public static final String SH1_CITY = null;              // F22: no city selected yet
    public static final boolean SH1_TILT = false;            // F23: authentic camera default (tilt opt-in)
    public static final int SH1_TILT_THRESHOLD = 15;         // F23: the mandated 15-degree deadband
    public static final int SH1_TILT_MAX = 3;                // v9.1 Fix 3: default shift 3 deg, ceiling now 1..20
    public static final int SH1_TILT_RATE = 5;               // F23: 5 Hz default polling

    public static final boolean SH1_STORAGE_GUARD = true;   // safety only, never alters valid imports
    public static final boolean SH1_WATCHDOG = true;        // authentic scenes never present all-black

    public static final boolean SH1_AUTO_CUSTOM = false;    // authentic mapping day=1 / night=6
    public static final int SH1_AUTO_DAY_ENV = Constants.SH1.ENV_DAY;
    public static final int SH1_AUTO_NIGHT_ENV = Constants.SH1.ENV_NIGHT;

    public static final boolean SH1_SCENE_OVERRIDES = true; // no-op until an override is configured

    public static final boolean SH1_TILE_NEXT = true;       // tiles are inert until added in QS settings
    public static final boolean SH1_TILE_FREEZE = true;
    public static final boolean SH1_MANUAL_FREEZE = false;

    public static final boolean SH1_THUMBNAILS = true;      // UI-only cache of the engine's own frame

    public static final String LANGUAGE = null;
    public static final String THEME = "";
    public static final int UI_MODE = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    public static final String UI_CONTRAST = CONTRAST.STANDARD;
  }

  /**
   * SH1 environment system — the 21 MAP_EFFECTS_INFOS presets of the game
   * (decomp src/bodyprog/sys/map_info.c), carried verbatim by the engine
   * (SH_MAP_EFFECTS_INFOS / SH_MODE_NAMES in cpp/fog_poc.c). The array
   * index IS the engine preset index: Auto Mode day = 1, night = 6.
   */
  public static final class SH1 {

    /** Auto Mode day preset: MAP_EFFECTS_INFOS[1] "THR day street" */
    public static final int ENV_DAY = 1;
    /** Auto Mode night preset (v9 F27): MAP_EFFECTS_INFOS[3] "night street
     * + FLASHLIGHT" — the user-mandated default night env (was 6) */
    public static final int ENV_NIGHT = 3;
    /** Weather setting "-1": keep the scene's own camdat weather recipe */
    public static final int WEATHER_SCENE = -1;
    public static final int ENV_COUNT = 21;
    public static final int WEATHER_COUNT = 9;

    /** scene randomizer trigger modes (Phase E-3) */
    public static final int RANDOM_OFF = 0;
    public static final int RANDOM_DAILY = 1;
    public static final int RANDOM_HOURLY = 2;
    public static final int RANDOM_LOCK = 3;
    public static final int RANDOM_INTERVAL = 4;
    public static final int RANDOM_COUNT = 5;
    public static final String[] RANDOM_MODES = {
        "Off",
        "Daily",
        "Hourly",
        "On phone lock",
        "Custom interval"
    };

    /**
     * v9 F25: the render resolution ladder (the engine's own --res WxH
     * values). Index 0 is the AUTHENTIC PS1 raster — high resolutions are
     * NEVER the default and exist only as user opt-in steps on the slider.
     * The 640x360 entry is the v7 "presentation" widescreen mode.
     */
    public static final int[][] RES_MODES = {
        {320, 224},    /* 0: authentic PS1 raster (engine default, no --res) */
        {480, 336},    /* 1: 1.5x                                            */
        {640, 360},    /* 2: presentation 16:9 (the v7 F17 mode)              */
        {640, 448},    /* 3: 2x                                               */
        {960, 540},    /* 4: presentation 16:9 hi                            */
        {960, 672},    /* 5: 3x                                               */
        {1280, 896},   /* 6: 4x                                               */
    };
    public static final int RES_COUNT = RES_MODES.length;
    public static final int RES_AUTHENTIC = 0;   /* ladder index of 320x224 */
    /** legacy v7 constant kept for source compat (the 640x360 step) */
    public static final int RES_PRESENT = 2;

    /**
     * Presentation frame-rate caps: the engine's own --fps values.
     * v9 F26: the pref stores the fps NUMBER directly (1..60; the old
     * v8 enum values 15/24/30/60 read identically). 30 is the authentic
     * default (g_IntervalVBlanks=2, one present per tick); 60 = the
     * display-duplicate mode; sub-15 values use the Android loop's 30 Hz
     * cadence + fractional present gate (world speed stays real-time).
     */
    public static final int FPS_MIN = 1;
    public static final int FPS_MAX = 60;
    public static final int FPS_AUTHENTIC = 30;  /* one present per 30 Hz tick */
    public static final int[] FPS_VALUES = {15, 24, 30, 60}; /* legacy v8 steps */
    public static final int FPS_COUNT = 4;

    /** battery half-rate pacing (v7 F12): the presentation cap while the
     * battery is at/below the mid threshold but above the critical freeze */
    public static final int HALFRATE_FPS = 15;

    /**
     * The 21 environment mode names, extracted verbatim from the Fog PoC
     * source (SH_MODE_NAMES in cpp/fog_poc.c, which mirrors the decomp
     * MAP_EFFECTS_INFOS table order and roles).
     */
    public static final String[] ENV_MODES = {
        "fog-off ambient room",
        "THR day street",
        "interior day, ENUM-2 dir. light",
        "night street + FLASHLIGHT",
        "nightmare flashlight",
        "interior flashlight, DYNAMIC fog",
        "night street",
        "night THICK",
        "LIGHTNING flash env",
        "nowheres rust",
        "lighter-equipped env",
        "red darkness",
        "interior flashlight",
        "dusk street",
        "rust/brown flashlight",
        "dark red flashlight",
        "dim flashlight",
        "THICK fog room",
        "bright spotlight",
        "blue night",
        "map1_s06 flashlight"
    };

    /**
     * One-line summaries per preset, taken from the same PoC table comments
     * (spot = flashlight spotlight intensity, wLI = world light intensity,
     * tint = world tint triplet, maxB = max brightness byte).
     */
    public static final String[] ENV_MODES_SUMMARY = {
        "wLI 1.15, no fog, 100m",
        "fog 13.5m {108,100,116}",
        "fog 14m",
        "spot 1.3, fog off",
        "spot 0.7, fog off",
        "field_E=3 + overlap",
        "fog 14m black, wLI 0.44",
        "fog 9.29987m black",
        "maxB 0x50, fog 40m",
        "map7_s03, tint 1.0/0.7/0.6",
        "UseLighter, maxB 0x92",
        "tint 1.3/0.4/0.2",
        "wLI 0.1",
        "fog 13.5m {92,84,100}",
        "maxB 0x38, tint 1.1/0.6/0.65",
        "maxB 0xC4",
        "spot 1.4, tint 0.85/0.6/0.6",
        "fog 6.5m {108,100,100}",
        "spot 2.5",
        "fog 15m, tint 0.5/0.88/1.25",
        "spot 0.8"
    };

    /**
     * The 9 runtime weather states, extracted verbatim from the Fog PoC
     * source (the WNAMES table of the W-key weather cycle — the engine's
     * sharedFunc_800D0B18 argument range 0..8).
     */
    public static final String[] WEATHERS = {
        "NONE",
        "SNOW (wind 0)",
        "SNOW (wind 1)",
        "SNOW (wind 2)",
        "SNOW (wind 3)",
        "RAIN (wind 0)",
        "RAIN (wind 1)",
        "RAIN (wind 2)",
        "RAIN (wind 3)"
    };

    /**
     * Auto Mode time check on the DEVICE clock (Calendar.getInstance()
     * uses the device timezone). Default daytime is 06:00 (inclusive) to
     * 18:00 (exclusive): day uses env mode 1, night uses env mode 6.
     */
    public static boolean isDaytime() {
      Calendar now = Calendar.getInstance();
      int hour = now.get(Calendar.HOUR_OF_DAY);
      return hour >= 6 && hour < 18;
    }

    /**
     * Phase E-2: the same day/night window with the USER'S schedule
     * (Material TimePicker values, minutes-of-day). Day = [dayStart,
     * nightStart) as a wrapping window: with the defaults 06:00/18:00
     * this is exactly isDaytime(). Degenerate equal start times yield an
     * empty day window (always night) — the interval arithmetic's own
     * outcome, kept deterministic.
     */
    public static boolean isDaytime(int dayHour, int dayMinute, int nightHour, int nightMinute) {
      Calendar now = Calendar.getInstance();
      int mod = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
      int day = dayHour * 60 + dayMinute;
      int night = nightHour * 60 + nightMinute;
      if (day == night) {
        return false;   // empty [start, start) window
      }
      if (day < night) {
        return mod >= day && mod < night;
      }
      // wrapping window (e.g. day 20:00 -> night 04:00)
      return mod >= day || mod < night;
    }

    /**
     * v9 F22: Sun-Time Auto Mode — the effective day/night state at the
     * selected city RIGHT NOW (NOAA solar elevation above the civil
     * twilight threshold -6 deg). Falls back to the device-clock schedule
     * when no city is selected. Pure local math, zero network calls.
     */
    public static boolean isSunTimeDay(SharedPreferences prefs) {
      String raw = prefs.getString(PREF.SH1_CITY, DEF.SH1_CITY);
      if (raw == null || raw.isEmpty()) {
        int[] day = new int[]{prefs.getInt(PREF.SH1_DAY_HOUR, DEF.SH1_DAY_HOUR),
            prefs.getInt(PREF.SH1_DAY_MINUTE, DEF.SH1_DAY_MINUTE)};
        int[] night = new int[]{prefs.getInt(PREF.SH1_NIGHT_HOUR, DEF.SH1_NIGHT_HOUR),
            prefs.getInt(PREF.SH1_NIGHT_MINUTE, DEF.SH1_NIGHT_MINUTE)};
        return isDaytime(day[0], day[1], night[0], night[1]);
      }
      String[] f = raw.split("\\|");
      if (f.length != 4) {
        return isDaytime();
      }
      try {
        double lat = Double.parseDouble(f[2]);
        double lon = Double.parseDouble(f[3]);
        return xyz.zedler.patrick.doodle.util.Sh1Solar.isCivilDay(
            lat, lon, System.currentTimeMillis());
      } catch (NumberFormatException e) {
        return isDaytime();
      }
    }

    /** HH:MM formatting of a schedule time for the settings row */
    public static String formatTime(int hour, int minute) {
      return String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
    }

    public static String getEnvModeName(int index) {
      if (index < 0 || index >= ENV_COUNT) {
        Log.e(TAG, "getEnvModeName: invalid index " + index);
        return "";
      }
      return ENV_MODES[index];
    }

    public static String getEnvModeSummary(int index) {
      if (index < 0 || index >= ENV_COUNT) {
        return "";
      }
      return ENV_MODES_SUMMARY[index];
    }

    public static String getWeatherName(int weather) {
      if (weather < 0 || weather >= WEATHER_COUNT) {
        return "";
      }
      return WEATHERS[weather];
    }
  }

  public static final class ACTION {

    public static final String THEME_AND_DESIGN_CHANGED = "action_theme_and_design_changed";
    public static final String THEME_CHANGED = "action_theme_changed";
    public static final String SETTINGS_CHANGED = "action_settings_changed";
  }

  public static final class EXTRA {

    public static final String RUN_AS_SUPER_CLASS = "run_as_super_class";
    public static final String INSTANCE_STATE = "instance_state";
    public static final String SHOW_FORCE_STOP_REQUEST = "show_force_stop_request";
    public static final String SCROLL_POSITION = "scroll_position";
    public static final String SELECTION_INDEX = "selection_index";
    public static final String COLOR = "color";
  }

  public static final class THEME {

    public static final String DYNAMIC = "dynamic";
    public static final String RED = "red";
    public static final String YELLOW = "yellow";
    public static final String GREEN = "green";
    public static final String BLUE = "blue";
  }

  public static final class CONTRAST {

    public static final String STANDARD = "standard";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";
  }
}
