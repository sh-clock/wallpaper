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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
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
import xyz.zedler.patrick.doodle.util.UiUtil;

/**
 * Chooser for the 21 engine environment modes (the verbatim
 * MAP_EFFECTS_INFOS preset list carried by the PoC). The layout, the
 * single-choice rows and the behavior mirror the language chooser.
 *
 * v7 F19: the sheet serves three targets — the MANUAL preset (the
 * original behavior) and the Auto-Mode DAY / NIGHT preset mappings
 * (which of the 21 presets the day and night schedule switch to). The
 * target is passed via the standard Bundle arguments.
 *
 * v9 F21: two more targets — the PER-SCENE day/night env overrides
 * (sh1_ovr_day_<glb> / sh1_ovr_night_<glb>), with an explicit
 * "global default" first row (override off).
 *
 * v9.2 F30: the per-scene targets now write the PER-WALLPAPER (track)
 * keys (sh1_ovr_day_t<id> / sh1_ovr_night_t<id>, keyed by the camdat
 * editor's stable track id). The service resolves track > model >
 * global, so a model-level v9.1 override keeps applying to every track
 * of that model until an individual wallpaper gets its own.
 *
 * v9 F28: every row carries the REAL-RENDER thumbnail of that preset —
 * a deterministic --shot frame produced by the actual engine at build
 * time (assets/sh1/thumbs/preset_N.png); zero invention.
 */
public class Sh1EnvModesBottomSheetDialogFragment extends BaseBottomSheetDialogFragment
    implements Sh1OptionAdapter.Sh1OptionAdapterListener {

  private static final String TAG = "Sh1EnvModesBottomSheet";

  public static final String ARG_TARGET = "target";
  public static final int TARGET_MANUAL = 0;
  public static final int TARGET_AUTO_DAY = 1;
  public static final int TARGET_AUTO_NIGHT = 2;
  public static final int TARGET_SCENE_DAY = 3;    /* v9 F21 */
  public static final int TARGET_SCENE_NIGHT = 4;  /* v9 F21 */

  /** the F28 asset thumbnail of a preset (the engine's own --shot render) */
  private static String presetThumb(int idx) {
    return "sh1/thumbs/preset_" + idx + ".png";
  }

  private FragmentBottomsheetSh1OptionsBinding binding;
  private int target = TARGET_MANUAL;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentBottomsheetSh1OptionsBinding.inflate(inflater, container, false);

    MainActivity activity = (MainActivity) requireActivity();

    Bundle args = getArguments();
    if (args != null) {
      target = args.getInt(ARG_TARGET, TARGET_MANUAL);
    }

    SharedPreferences prefs = getSharedPrefs();
    String sceneFile = currentSceneFile(prefs);
    int selected;
    List<Sh1Option> options = new ArrayList<>(SH1.ENV_COUNT + 1);
    switch (target) {
      case TARGET_AUTO_DAY:
        binding.textSh1OptionsTitle.setText(R.string.sh1_auto_day_preset_select);
        binding.textSh1OptionsDescription.setText(R.string.sh1_auto_preset_select_description);
        selected = Sh1WallpaperService.getAutoDayEnv(prefs);
        break;
      case TARGET_AUTO_NIGHT:
        binding.textSh1OptionsTitle.setText(R.string.sh1_auto_night_preset_select);
        binding.textSh1OptionsDescription.setText(R.string.sh1_auto_preset_select_description);
        selected = Sh1WallpaperService.getAutoNightEnv(prefs);
        break;
      case TARGET_SCENE_DAY:
      case TARGET_SCENE_NIGHT: {
        boolean day = target == TARGET_SCENE_DAY;
        binding.textSh1OptionsTitle.setText(
            day ? R.string.sh1_scene_day_env : R.string.sh1_scene_night_env);
        binding.textSh1OptionsDescription.setText(
            R.string.sh1_scene_env_override_description);
        // v9.2 F30: the active WALLPAPER's (track) override, falling back
        // to the model-level one for display
        String trackId = currentTrackId(prefs);
        int ovr = day
            ? Sh1WallpaperService.getTrackDayEnvOverride(prefs, trackId)
            : Sh1WallpaperService.getTrackNightEnvOverride(prefs, trackId);
        if (ovr < 0) {
          ovr = day
              ? Sh1WallpaperService.getSceneDayEnvOverride(prefs, sceneFile)
              : Sh1WallpaperService.getSceneNightEnvOverride(prefs, sceneFile);
        }
        selected = ovr;
        // the explicit "use the global default" row (override off)
        int global = day ? Sh1WallpaperService.getAutoDayEnv(prefs, null)
            : Sh1WallpaperService.getAutoNightEnv(prefs, null);
        Sh1Option globalRow = new Sh1Option(-1,
            getString(R.string.sh1_scene_env_global_row),
            getString(R.string.sh1_scene_env_value,
                String.valueOf(global), SH1.getEnvModeName(global)));
        options.add(globalRow);
        break;
      }
      default:
        binding.textSh1OptionsTitle.setText(R.string.sh1_env_select);
        binding.textSh1OptionsDescription.setText(R.string.sh1_env_select_description);
        selected = Sh1WallpaperService.getManualEnv(prefs);
        break;
    }
    for (int i = 0; i < SH1.ENV_COUNT; i++) {
      Sh1Option option = new Sh1Option(
          i, i + " — " + SH1.getEnvModeName(i), SH1.getEnvModeSummary(i));
      option.setAssetThumb(presetThumb(i));   /* v9 F28: the real render */
      options.add(option);
    }

    binding.recyclerSh1Options.setLayoutManager(
        new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false)
    );
    binding.recyclerSh1Options.setAdapter(
        new Sh1OptionAdapter(options, selected, this)
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
    SharedPreferences prefs = getSharedPrefs();
    switch (target) {
      case TARGET_AUTO_DAY:
        performHapticClick();
        prefs.edit()
            .putBoolean(PREF.SH1_AUTO_CUSTOM, true)
            .putInt(PREF.SH1_AUTO_DAY_ENV, option.getValue())
            .apply();
        break;
      case TARGET_AUTO_NIGHT:
        performHapticClick();
        prefs.edit()
            .putBoolean(PREF.SH1_AUTO_CUSTOM, true)
            .putInt(PREF.SH1_AUTO_NIGHT_ENV, option.getValue())
            .apply();
        break;
      case TARGET_SCENE_DAY:
      case TARGET_SCENE_NIGHT: {
        performHapticClick();
        // v9.2 F30: write the ACTIVE wallpaper's per-track key (the
        // editor track id — stable across camdat re-ordering); -1 clears
        // it back to the model-level / global default
        String trackId = currentTrackId(prefs);
        if (trackId != null) {
          prefs.edit().putInt(
              target == TARGET_SCENE_DAY
                  ? Sh1WallpaperService.trackDayEnvOverrideKey(trackId)
                  : Sh1WallpaperService.trackNightEnvOverrideKey(trackId),
              option.getValue())   /* -1 = back to the global default */
              .apply();
        } else {
          // no track id in the camdat: the v9.1 per-model key
          String sceneFile = currentSceneFile(prefs);
          if (sceneFile != null) {
            prefs.edit().putInt(
                target == TARGET_SCENE_DAY
                    ? Sh1WallpaperService.sceneDayEnvOverrideKey(sceneFile)
                    : Sh1WallpaperService.sceneNightEnvOverrideKey(sceneFile),
                option.getValue())
                .apply();
          }
        }
        break;
      }
      default:
        int current = prefs.getInt(PREF.SH1_ENV, DEF.SH1_ENV);
        if (option.getValue() == current) {
          return;
        }
        performHapticClick();
        prefs.edit().putInt(PREF.SH1_ENV, option.getValue()).apply();
        break;
    }
    MainActivity activity = (MainActivity) requireActivity();
    activity.requestSettingsRefresh();
    dismiss();
  }

  /** the GLB file name of the ACTIVE scene (the F21 model-level key) */
  private String currentSceneFile(SharedPreferences prefs) {
    Sh1Camdat camdat = Sh1Camdat.load(requireContext());
    Sh1Camdat.Model model = camdat.getModel(
        Sh1WallpaperService.getActiveSceneIndex(prefs));
    return model != null ? model.file : null;
  }

  /**
   * v9.2 F30: the camdat editor track id of the ACTIVE wallpaper (null
   * when the camdat carries no id or the selection is invalid)
   */
  private String currentTrackId(SharedPreferences prefs) {
    return Sh1WallpaperService.activeTrackId(requireContext(), prefs);
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
