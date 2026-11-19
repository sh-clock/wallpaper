/*
 * This file is part of Doodle Android.
 *
 * Doodle Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Doodle Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Doodle Android. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2019-2025 by Patrick Zedler
 */

package xyz.zedler.patrick.doodle.fragment.dialog;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.Constants.SH1;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.activity.MainActivity;
import xyz.zedler.patrick.doodle.adapter.Sh1OptionAdapter;
import xyz.zedler.patrick.doodle.databinding.FragmentBottomsheetSh1OptionsBinding;
import xyz.zedler.patrick.doodle.model.Sh1Option;
import xyz.zedler.patrick.doodle.service.Sh1WallpaperService;
import xyz.zedler.patrick.doodle.util.Sh1Camdat;
import xyz.zedler.patrick.doodle.util.Sh1Storage;
import xyz.zedler.patrick.doodle.util.UiUtil;

/**
 * The wallpaper selection menu (Phase E-6): the scene list is DYNAMIC —
 * generated from the models[] entries of the ACTIVE camdat.json. A model
 * whose GLB is not present in the app storage shows the BYOA hint and
 * selecting it opens the SAF asset picker instead of switching (Phase D
 * BYOA: the picked file is copied into the app's private asset dir with
 * a persistent URI grant, then the scene activates).
 *
 * v7:
 *   - F3 favorites: the star on each row pins scenes for the
 *     randomizer's favorites-only pool.
 *   - F5 storage guard: imports are size-capped and checked against the
 *     free disk space, with a Material warning before big copies.
 *   - F9 thumbnails: rows show the cached engine frame when one exists.
 *   - F11 per-scene overrides: long-press a row to pin the env mode and
 *     weather for that scene (applied at the engine's own boot argv).
 */
public class Sh1ScenesBottomSheetDialogFragment extends BaseBottomSheetDialogFragment
    implements Sh1OptionAdapter.Sh1OptionAdapterListener {

  private static final String TAG = "Sh1ScenesBottomSheet";

  /** F5: hard cap + the "large asset" warning threshold */
  private static final long IMPORT_CAP_BYTES = 512L * 1024 * 1024;
  private static final long LARGE_ASSET_BYTES = 64L * 1024 * 1024;
  private static final long LOW_DISK_BYTES = 256L * 1024 * 1024;

  private FragmentBottomsheetSh1OptionsBinding binding;

  /** the missing-asset scene the user wants to load (BYOA) */
  private int pendingByoaScene = -1;

  private final ActivityResultLauncher<String[]> glbPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocument(),
          this::onGlbPicked);

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentBottomsheetSh1OptionsBinding.inflate(inflater, container, false);

    MainActivity activity = (MainActivity) requireActivity();

    binding.textSh1OptionsTitle.setText(R.string.sh1_scene_select);
    binding.textSh1OptionsDescription.setText(R.string.sh1_scene_select_description);

    SharedPreferences prefs = getSharedPrefs();
    Sh1Camdat camdat = Sh1Camdat.load(activity);
    int selected = Sh1WallpaperService.getActiveSceneIndex(prefs);
    java.util.HashSet<String> favorites = favoriteSet(prefs);
    List<Sh1Option> options = new ArrayList<>();
    if (camdat.isValid()) {
      for (int i = 0; i < camdat.modelCount(); i++) {
        Sh1Camdat.Model model = camdat.getModel(i);
        String summary;
        if (!model.glbPresent) {
          summary = getString(R.string.sh1_scene_missing);
        } else if (model.envPair0 >= 0 && model.envPair1 >= 0) {
          // v9.4: an absent weatherTarget displays the engine's effective
          // boot weather (the map0 default, snow wind 1) — mirrors what the
          // engine actually applies for such a camdat (Camtrack v12 Rule 1)
          int weather = model.weatherTarget >= 0
              ? model.weatherTarget : Sh1WallpaperService.MAP0_DEFAULT_WEATHER;
          // v9.5 (Camtrack v12): flag models whose tracks carry their OWN
          // weather — the per-track data the wallpaper selector resolves
          // (track > model); the weather shown here is the model default
          boolean anyTrackWeather = false;
          for (Sh1Camdat.Track t : model.tracks) {
            if (t.weatherTarget >= 0) {
              anyTrackWeather = true;
              break;
            }
          }
          summary = getString(
              R.string.sh1_scene_env,
              String.valueOf(model.envPair0), String.valueOf(model.envPair1),
              SH1.getWeatherName(weather)
          ) + " · " + getString(
              anyTrackWeather
                  ? R.string.sh1_scene_tracks_weather
                  : R.string.sh1_scene_track_count,
              model.tracks.size());
        } else {
          summary = getString(
              R.string.sh1_scene_env_none
          ) + " · " + getString(R.string.sh1_scene_track_count, model.tracks.size());
        }
        Sh1Option option = new Sh1Option(i, model.file, summary);
        option.setShowStar(true);
        option.setFavorite(favorites.contains(model.file));
        if (model.glbPresent) {
          // v9.2 F30: per-track thumbnails — the model row shows its
          // first enabled track's cached engine frame
          option.setThumbnail(
              Sh1WallpaperService.thumbnailFile(activity, model.file,
                  model.firstEnabledTrack()));
        }
        int ovrEnv = Sh1WallpaperService.getSceneEnvOverride(prefs, model.file);
        int ovrWeather = Sh1WallpaperService.getSceneWeatherOverride(prefs, model.file);
        if (ovrEnv >= 0 || ovrWeather >= 0) {
          option.setHasOverride(true);
        }
        options.add(option);
      }
    } else {
      options.add(new Sh1Option(0, "THRF9FD.glb", getString(R.string.sh1_config_default)));
    }

    binding.recyclerSh1Options.setLayoutManager(
        new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false)
    );
    binding.recyclerSh1Options.setAdapter(
        new Sh1OptionAdapter(options, Math.min(selected, options.size() - 1), this)
    );

    return binding.getRoot();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    binding = null;
  }

  @Override
  public void onItemRowClicked(@Nullable Sh1Option option) {
    if (option == null) {
      return;
    }
    performHapticClick();
    MainActivity activity = (MainActivity) requireActivity();
    Sh1Camdat camdat = Sh1Camdat.load(activity);
    Sh1Camdat.Model model = camdat.getModel(option.getValue());
    if (model != null && !model.glbPresent) {
      // BYOA (Phase D): the required GLB is not in the app's asset dir —
      // prompt the user to load it from device storage (SAF, persistable)
      pendingByoaScene = option.getValue();
      glbPicker.launch(new String[]{"*/*"});
      return;
    }
    int current = getSharedPrefs().getInt(PREF.SH1_SCENE, DEF.SH1_SCENE);
    if (option.getValue() != current) {
      getSharedPrefs().edit()
          .putInt(PREF.SH1_SCENE, option.getValue())
          .putInt(PREF.SH1_TRACK, DEF.SH1_TRACK)   // new scene: first enabled track
          .apply();
      dismiss();
      activity.requestSceneSwitch();
    } else {
      dismiss();
    }
  }

  /** v7 F3: toggle this scene's favorite pin */
  @Override
  public void onItemStarClicked(@Nullable Sh1Option option) {
    if (option == null) {
      return;
    }
    performHapticClick();
    MainActivity activity = (MainActivity) requireActivity();
    Sh1Camdat camdat = Sh1Camdat.load(activity);
    Sh1Camdat.Model model = camdat.getModel(option.getValue());
    if (model == null) {
      return;
    }
    SharedPreferences prefs = getSharedPrefs();
    java.util.LinkedHashSet<String> favorites = new java.util.LinkedHashSet<>(
        favoriteSet(prefs));
    if (!favorites.add(model.file)) {
      favorites.remove(model.file);   // already starred -> unstar
    }
    prefs.edit().putString(PREF.SH1_FAVORITES, join(favorites)).apply();
    // refresh the rows (star tint)
    binding.recyclerSh1Options.getAdapter().notifyDataSetChanged();
  }

  /** v7 F11: per-scene env/weather override editor */
  @Override
  public boolean onItemRowLongClicked(@Nullable Sh1Option option) {
    if (option == null) {
      return false;
    }
    performHapticClick();
    MainActivity activity = (MainActivity) requireActivity();
    Sh1Camdat camdat = Sh1Camdat.load(activity);
    Sh1Camdat.Model model = camdat.getModel(option.getValue());
    if (model == null || !model.glbPresent) {
      return false;
    }
    showSceneOverrideDialog(model.file);
    return true;
  }

  /* ---- F11: per-scene override dialog (two single-choice pickers) ---- */

  private void showSceneOverrideDialog(String glbFile) {
    SharedPreferences prefs = getSharedPrefs();
    boolean master = prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES);
    int env = prefs.getInt(Sh1WallpaperService.sceneEnvOverrideKey(glbFile), -1);
    int weather = prefs.getInt(Sh1WallpaperService.sceneWeatherOverrideKey(glbFile), -1);
    String envName = env >= 0 && env < SH1.ENV_COUNT
        ? env + " — " + SH1.getEnvModeName(env) : getString(R.string.sh1_override_none);
    String weatherName = weather >= 0 && weather < SH1.WEATHER_COUNT
        ? SH1.getWeatherName(weather) : getString(R.string.sh1_override_none);
    new MaterialAlertDialogBuilder((MainActivity) requireActivity())
        .setTitle(glbFile)
        .setMessage(getString(R.string.sh1_override_dialog_msg))
        .setPositiveButton(
            getString(R.string.sh1_override_pick_env, envName),
            (d, w) -> showEnvOverridePicker(glbFile))
        .setNegativeButton(
            getString(R.string.sh1_override_pick_weather, weatherName),
            (d, w) -> showWeatherOverridePicker(glbFile))
        .setNeutralButton(master
                ? R.string.sh1_override_clear : R.string.action_cancel,
            (d, w) -> {
              if (master) {
                getSharedPrefs().edit()
                    .remove(Sh1WallpaperService.sceneEnvOverrideKey(glbFile))
                    .remove(Sh1WallpaperService.sceneWeatherOverrideKey(glbFile))
                    .apply();
                MainActivity activity = (MainActivity) requireActivity();
                activity.requestSceneSwitch();
              }
            })
        .show();
  }

  private void showEnvOverridePicker(String glbFile) {
    SharedPreferences prefs = getSharedPrefs();
    int current = prefs.getInt(Sh1WallpaperService.sceneEnvOverrideKey(glbFile), -1);
    String[] names = new String[SH1.ENV_COUNT + 1];
    names[0] = getString(R.string.sh1_override_none);
    for (int i = 0; i < SH1.ENV_COUNT; i++) {
      names[i + 1] = i + " — " + SH1.getEnvModeName(i);
    }
    int checked = current >= 0 ? current + 1 : 0;
    new MaterialAlertDialogBuilder((MainActivity) requireActivity())
        .setTitle(R.string.sh1_override_env_title)
        .setSingleChoiceItems(names, checked, (d, which) -> {
          int value = which == 0 ? -1 : which - 1;
          getSharedPrefs().edit()
              .putInt(Sh1WallpaperService.sceneEnvOverrideKey(glbFile), value)
              .apply();
          d.dismiss();
          MainActivity activity = (MainActivity) requireActivity();
          activity.requestSceneSwitch();
        })
        .setNegativeButton(R.string.action_cancel, null)
        .show();
  }

  private void showWeatherOverridePicker(String glbFile) {
    SharedPreferences prefs = getSharedPrefs();
    int current = prefs.getInt(Sh1WallpaperService.sceneWeatherOverrideKey(glbFile), -1);
    String[] names = new String[SH1.WEATHER_COUNT + 1];
    names[0] = getString(R.string.sh1_override_none);
    for (int i = 0; i < SH1.WEATHER_COUNT; i++) {
      names[i + 1] = SH1.getWeatherName(i);
    }
    int checked = current >= 0 ? current + 1 : 0;
    new MaterialAlertDialogBuilder((MainActivity) requireActivity())
        .setTitle(R.string.sh1_override_weather_title)
        .setSingleChoiceItems(names, checked, (d, which) -> {
          int value = which == 0 ? -1 : which - 1;
          getSharedPrefs().edit()
              .putInt(Sh1WallpaperService.sceneWeatherOverrideKey(glbFile), value)
              .apply();
          d.dismiss();
          MainActivity activity = (MainActivity) requireActivity();
          activity.requestSceneSwitch();
        })
        .setNegativeButton(R.string.action_cancel, null)
        .show();
  }

  /* ---- BYOA import with the v7 F5 storage guard ---- */

  /** copy the picked GLB into the app storage and activate its scene */
  private void onGlbPicked(@Nullable Uri uri) {
    if (uri == null || pendingByoaScene < 0) {
      return;   // cancelled
    }
    MainActivity activity = (MainActivity) requireActivity();
    // destination name = the camdat model's own file name (sanitized)
    Sh1Camdat camdat = Sh1Camdat.load(activity);
    Sh1Camdat.Model model = camdat.getModel(pendingByoaScene);
    String destName = model != null ? model.file : "imported.glb";
    long size = documentSize(activity, uri);
    if (size > IMPORT_CAP_BYTES) {
      warnTooLarge(activity, size);
      pendingByoaScene = -1;
      return;
    }
    if (guardEnabled() && (size > LARGE_ASSET_BYTES || freeBytes(activity) < LOW_DISK_BYTES)) {
      confirmLargeImport(activity, size, () -> copyGlb(activity, uri, destName));
      return;   // pendingByoaScene stays; copyGlb finishes the flow
    }
    copyGlb(activity, uri, destName);
  }

  private void copyGlb(MainActivity activity, Uri uri, String destName) {
    try {
      activity.getContentResolver().takePersistableUriPermission(
          uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (SecurityException e) {
      android.util.Log.w(TAG, "onGlbPicked: no persistable grant", e);
    }
    boolean ok = false;
    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
      if (in != null) {
        File out = new File(Sh1Storage.sh1Dir(activity), destName);
        out.getParentFile().mkdirs();
        try (OutputStream os = new java.io.FileOutputStream(out)) {
          byte[] buf = new byte[64 * 1024];
          int n;
          while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
          }
        }
        ok = true;
      }
    } catch (Exception e) {
      android.util.Log.e(TAG, "onGlbPicked: copy failed", e);
    }
    if (ok) {
      getSharedPrefs().edit()
          .putInt(PREF.SH1_SCENE, pendingByoaScene)
          .putInt(PREF.SH1_TRACK, DEF.SH1_TRACK)
          .apply();
      dismiss();
      activity.requestSceneSwitch();
    }
    pendingByoaScene = -1;
  }

  private boolean guardEnabled() {
    return getSharedPrefs().getBoolean(PREF.SH1_STORAGE_GUARD, DEF.SH1_STORAGE_GUARD);
  }

  private static long documentSize(MainActivity activity, Uri uri) {
    try {
      return activity.getContentResolver().openAssetFileDescriptor(uri, "r").getLength();
    } catch (Exception e) {
      return -1;   // unknown -> no early guard; the copy itself will fail cleanly
    }
  }

  private static long freeBytes(MainActivity activity) {
    try {
      StatFs stat = new StatFs(Sh1Storage.engineDataPath(activity));
      return stat.getAvailableBytes();
    } catch (Exception e) {
      return Long.MAX_VALUE;
    }
  }

  private void warnTooLarge(MainActivity activity, long size) {
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_import_too_large_title)
        .setMessage(getString(R.string.sh1_import_too_large_msg, size / (1024 * 1024)))
        .setPositiveButton(R.string.action_ok, null)
        .show();
  }

  private void confirmLargeImport(MainActivity activity, long size, Runnable proceed) {
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_import_confirm_title)
        .setMessage(getString(R.string.sh1_import_confirm_msg, size / (1024 * 1024)))
        .setPositiveButton(R.string.action_ok, (d, w) -> proceed.run())
        .setNegativeButton(R.string.action_cancel, (d, w) -> pendingByoaScene = -1)
        .show();
  }

  /* ---- helpers ---- */

  private static java.util.HashSet<String> favoriteSet(SharedPreferences prefs) {
    java.util.HashSet<String> set = new java.util.HashSet<>();
    String raw = prefs.getString(PREF.SH1_FAVORITES, DEF.SH1_FAVORITES);
    if (raw != null && !raw.isEmpty()) {
      for (String s : raw.split(",")) {
        if (!s.isEmpty()) {
          set.add(s);
        }
      }
    }
    return set;
  }

  private static String join(java.util.Set<String> set) {
    StringBuilder sb = new StringBuilder();
    for (String s : set) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(s);
    }
    return sb.toString();
  }

  @Override
  public void applyBottomInset(int bottom) {
    if (binding != null) {
      binding.recyclerSh1Options.setPadding(
          0, UiUtil.dpToPx(requireContext(), 8),
          0, UiUtil.dpToPx(requireContext(), 8) + bottom
      );
    }
  }

  @NonNull
  @Override
  public String toString() {
    return TAG;
  }
}
