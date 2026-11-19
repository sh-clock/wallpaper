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
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import androidx.preference.PreferenceManager;
import java.util.Objects;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;

public class PrefsUtil {

  private static final String TAG = PrefsUtil.class.getSimpleName();

  private final Context context;
  private SharedPreferences sharedPrefs;

  public PrefsUtil(Context context) {
    this.context = context;
    migrateToStorageContext();
  }

  private void migrateToStorageContext() {
    Context storageContext;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      Context deviceContext = context.createDeviceProtectedStorageContext();
      boolean success = deviceContext.moveSharedPreferencesFrom(
          context, context.getPackageName() + "_preferences"
      );
      if (!success) {
        Log.w(TAG, "Failed to migrate shared preferences to storage context");
      }
      storageContext = deviceContext;
    } else {
      storageContext = context;
    }
    sharedPrefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
  }

  public PrefsUtil checkForMigrations() {

    // language is now stored with "-" instead of "_"
    if (sharedPrefs.contains(PREF.LANGUAGE)) {
      String language = sharedPrefs.getString(PREF.LANGUAGE, DEF.LANGUAGE);
      if (language != null && language.contains("_")) {
        sharedPrefs.edit()
            .putString(PREF.LANGUAGE, language.replace("_", "-"))
            .apply();
      }
    }

    // the former Doodle-renderer settings (wallpaper choice, variants,
    // colors, parallax, zoom, screen-off delay, GPU) and the SH1 v4
    // pre-refactor leftovers have no consumers anymore: clear them so a
    // reset-free upgrade lands on clean defaults
    String[] obsolete = {
        "wallpaper", "variant_pixel", "variant_johanna", "variant_reiko",
        "variant_anthony", "night_mode", "dark_text", "light_text",
        "random", "random_list", "random_current_wallpaper", "daily_time",
        "random_interval", "swipe", "swipe_intensity", "power_save_swipe",
        "tilt", "tilt_intensity", "refresh_rate", "damping_tilt",
        "threshold", "power_save_tilt", "size", "zoom", "zoom_rotation",
        "power_save_zoom", "zoom_launcher", "use_zoom_damping",
        "damping_zoom", "zoom_unlock", "zoom_unlock_mode_in",
        "zoom_duration", "hardware_acceleration", "screen_off_delay",
        "scale", "damping", "theme"
    };
    for (String key : obsolete) {
      if (sharedPrefs.contains(key)) {
        removePreference(key);
      }
    }

    return this;
  }

  public SharedPreferences getSharedPrefs() {
    return sharedPrefs;
  }

  private void removePreference(String key) {
    SharedPreferences.Editor editor = sharedPrefs.edit();
    editor.remove(key);
    editor.apply();
  }

  private void migrateString(String keyOld, String keyNew, String def) {
    if (sharedPrefs.contains(keyOld)) {
      SharedPreferences.Editor editor = sharedPrefs.edit();
      try {
        String current = sharedPrefs.getString(keyOld, def);
        if (!Objects.equals(current, def)) {
          editor.remove(keyOld);
          editor.putString(keyNew, current);
        }
      } catch (ClassCastException ignored) {
        editor.remove(keyOld);
      }
      editor.apply();
    }
  }

  private void migrateBoolean(String keyOld, String keyNew, boolean def) {
    if (sharedPrefs.contains(keyOld)) {
      SharedPreferences.Editor editor = sharedPrefs.edit();
      try {
        boolean current = sharedPrefs.getBoolean(keyOld, def);
        if (!Objects.equals(current, def)) {
          editor.remove(keyOld);
          editor.putBoolean(keyNew, current);
        }
      } catch (ClassCastException ignored) {
        editor.remove(keyOld);
      }
      editor.apply();
    }
  }

  private void migrateInteger(String keyOld, String keyNew, int def) {
    if (sharedPrefs.contains(keyOld)) {
      SharedPreferences.Editor editor = sharedPrefs.edit();
      try {
        int current = sharedPrefs.getInt(keyOld, def);
        if (!Objects.equals(current, def)) {
          editor.remove(keyOld);
          editor.putInt(keyNew, current);
        }
      } catch (ClassCastException ignored) {
        editor.remove(keyOld);
      }
      editor.apply();
    }
  }
}
