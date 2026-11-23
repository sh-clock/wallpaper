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

package xyz.zedler.patrick.doodle.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import xyz.zedler.patrick.doodle.Constants.ACTION;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.Constants.SH1;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.activity.MainActivity;
import xyz.zedler.patrick.doodle.behavior.ScrollBehavior;
import xyz.zedler.patrick.doodle.behavior.SystemBarBehavior;
import xyz.zedler.patrick.doodle.databinding.FragmentAppearanceBinding;
import xyz.zedler.patrick.doodle.fragment.dialog.Sh1EnvModesBottomSheetDialogFragment;
import xyz.zedler.patrick.doodle.fragment.dialog.Sh1RandomizerBottomSheetDialogFragment;
import xyz.zedler.patrick.doodle.fragment.dialog.Sh1ScenesBottomSheetDialogFragment;
import xyz.zedler.patrick.doodle.fragment.dialog.Sh1WeatherBottomSheetDialogFragment;
import xyz.zedler.patrick.doodle.service.Sh1WallpaperService;
import xyz.zedler.patrick.doodle.util.ResUtil;
import xyz.zedler.patrick.doodle.util.Sh1Camdat;
import xyz.zedler.patrick.doodle.util.UiUtil;
import xyz.zedler.patrick.doodle.util.ViewUtil;
import xyz.zedler.patrick.doodle.view.SelectionCardView;

/**
 * v9.2 Feature 32 — the APPEARANCE page (the v9.1 monolithic Environment
 * page split into five categories). Carries the wallpaper selection and
 * the world look: the Doodle-base circular wallpaper selector (Feature
 * 31 — one circle per individual camdat track, Feature 30), the
 * per-wallpaper env overrides (v9 F21, now resolved per-track), the
 * global env defaults (manual preset + weather), the PSX wobble toggle
 * (v9 F24) and the wallpaper rotation family (randomizer, interval,
 * shuffle bag, favorites, pre-warm). All behavior code is moved VERBATIM
 * from v9.1's AppearanceFragment — only the hosting page changed.
 */
public class AppearanceFragment extends BaseFragment
    implements OnClickListener, OnCheckedChangeListener {

  private static final String TAG = AppearanceFragment.class.getSimpleName();

  private FragmentAppearanceBinding binding;
  private MainActivity activity;
  private android.content.BroadcastReceiver receiver;
  private boolean receiverRegistered = false;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentAppearanceBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    activity = (MainActivity) requireActivity();

    SystemBarBehavior systemBarBehavior = new SystemBarBehavior(activity);
    systemBarBehavior.setAppBar(binding.appBarAppearance);
    systemBarBehavior.setScroll(binding.scrollAppearance, binding.constraintAppearance);
    systemBarBehavior.setAdditionalBottomInset(activity.getFabTopEdgeDistance());
    systemBarBehavior.setUp();

    new ScrollBehavior().setUpScroll(binding.appBarAppearance, binding.scrollAppearance, true);

    binding.toolbarAppearance.setNavigationOnClickListener(getNavigationOnClickListener());
    binding.toolbarAppearance.setOnMenuItemClickListener(getOnMenuItemClickListener());

    ViewUtil.setOnClickListeners(
        this,
        binding.buttonEnvironmentInfo,
        binding.linearEnvironmentScene,
        binding.linearEnvironmentSceneDay,
        binding.linearEnvironmentSceneNight,
        binding.linearEnvironmentMode,
        binding.linearEnvironmentWeather,
        binding.linearEnvironmentRandomizer,
        binding.linearEnvironmentInterval,
        binding.linearEnvironmentThumbnails,
        binding.linearEnvironmentOverrides
    );
    ViewUtil.setOnCheckedChangeListeners(
        this,
        binding.switchEnvironmentThumbnails,
        binding.switchEnvironmentOverrides,
        binding.switchEnvironmentWobble,
        binding.switchEnvironmentShuffle,
        binding.switchEnvironmentFavorites,
        binding.switchEnvironmentPrewarm
    );

    refreshValues();
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshValues();

    // stay in sync with settings changes (bottom-sheet selections,
    // randomizer switches) and the device clock (Auto Mode day/night
    // flips while the page is open)
    receiver = new android.content.BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
          return;
        }
        if (binding == null) {
          return;
        }
        switch (intent.getAction()) {
          case ACTION.SETTINGS_CHANGED:
          case Intent.ACTION_TIME_TICK:
          case Intent.ACTION_TIME_CHANGED:
          case Intent.ACTION_TIMEZONE_CHANGED:
            refreshValues();
            break;
        }
      }
    };
    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION.SETTINGS_CHANGED);
    filter.addAction(Intent.ACTION_TIME_TICK);
    filter.addAction(Intent.ACTION_TIME_CHANGED);
    filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        // SETTINGS_CHANGED is app-internal (senders use
        // setPackage(getPackageName())); the time/timezone actions are
        // protected system broadcasts, still delivered to NOT_EXPORTED.
        requireActivity().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        requireActivity().registerReceiver(receiver, filter);
      }
      receiverRegistered = true;
    } catch (Exception e) {
      android.util.Log.e(TAG, "onResume: registerReceiver failed", e);
    }
  }

  @Override
  public void onPause() {
    if (receiverRegistered) {
      try {
        requireActivity().unregisterReceiver(receiver);
      } catch (Exception e) {
        android.util.Log.e(TAG, "onPause: unregisterReceiver failed", e);
      }
      receiverRegistered = false;
    }
    super.onPause();
  }

  /** row visibility + value texts, all from the persisted settings */
  private void refreshValues() {
    if (binding == null || activity == null) {
      return;
    }
    SharedPreferences prefs = getSharedPrefs();
    boolean auto = prefs.getBoolean(PREF.SH1_AUTO, DEF.SH1_AUTO);

    // scene row + the wallpaper data source
    Sh1Camdat camdat = Sh1Camdat.load(activity);
    int scene = Sh1WallpaperService.getActiveSceneIndex(prefs);
    int trackSel = prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK);
    Sh1Camdat.Model model = camdat.getModel(scene);
    Sh1Camdat.Wallpaper wallpaper = camdat.wallpaper(scene, trackSel);
    if (model == null) {
      binding.textEnvironmentSceneValue.setText(R.string.sh1_config_default);
      binding.linearEnvironmentWallpaperCircles.removeAllViews();
      binding.linearEnvironmentSceneDay.setVisibility(View.GONE);
      binding.linearEnvironmentSceneNight.setVisibility(View.GONE);
    } else {
      if (model.glbPresent) {
        binding.textEnvironmentSceneValue.setText(
            getString(R.string.sh1_scene_value, model.file,
                getString(R.string.sh1_wallpaper_count_value, camdat.wallpaperCount()))
        );
      } else {
        binding.textEnvironmentSceneValue.setText(
            getString(R.string.sh1_scene_missing)
        );
      }
      populateWallpaperSelector(camdat, prefs);
      // v9 F21 + v9.2 F30: per-wallpaper day/night env rows (only while
      // Auto Mode is on) — resolved TRACK > MODEL > global
      binding.linearEnvironmentSceneDay.setVisibility(auto ? View.VISIBLE : View.GONE);
      binding.linearEnvironmentSceneNight.setVisibility(auto ? View.VISIBLE : View.GONE);
      String trackId = wallpaper != null && !wallpaper.trackId.isEmpty()
          ? wallpaper.trackId : null;
      int dayOvr = Sh1WallpaperService.getTrackDayEnvOverride(prefs, trackId);
      if (dayOvr < 0) {
        dayOvr = Sh1WallpaperService.getSceneDayEnvOverride(prefs, model.file);
      }
      // v9.6 (Camtrack v13): with no app-side override the camdat-authored
      // preset (track envDay > model env.dayEnv) is the effective source
      int dayCamdat = wallpaper != null ? wallpaper.camdatDayEnv() : -1;
      binding.textEnvironmentSceneDayValue.setText(dayOvr >= 0
          ? getString(R.string.sh1_scene_env_value,
              String.valueOf(dayOvr), SH1.getEnvModeName(dayOvr))
          : dayCamdat >= 0
              ? getString(R.string.sh1_scene_env_value,
                  String.valueOf(dayCamdat), SH1.getEnvModeName(dayCamdat))
              : getString(R.string.sh1_scene_env_global,
                  SH1.getEnvModeName(Sh1WallpaperService.getAutoDayEnv(prefs, null))));
      int nightOvr = Sh1WallpaperService.getTrackNightEnvOverride(prefs, trackId);
      if (nightOvr < 0) {
        nightOvr = Sh1WallpaperService.getSceneNightEnvOverride(prefs, model.file);
      }
      int nightCamdat = wallpaper != null ? wallpaper.camdatNightEnv() : -1;
      binding.textEnvironmentSceneNightValue.setText(nightOvr >= 0
          ? getString(R.string.sh1_scene_env_value,
              String.valueOf(nightOvr), SH1.getEnvModeName(nightOvr))
          : nightCamdat >= 0
              ? getString(R.string.sh1_scene_env_value,
                  String.valueOf(nightCamdat), SH1.getEnvModeName(nightCamdat))
              : getString(R.string.sh1_scene_env_global,
                  SH1.getEnvModeName(Sh1WallpaperService.getAutoNightEnv(prefs, null))));
    }

    // the selector's own summary (the active wallpaper)
    if (wallpaper != null) {
      binding.textEnvironmentWallpapersValue.setText(
          getString(R.string.sh1_wallpaper_selector_value,
              wallpaper.name, wallpaper.model.file));
    } else {
      binding.textEnvironmentWallpapersValue.setText(
          getString(R.string.sh1_wallpaper_count_value, camdat.wallpaperCount()));
    }

    // v9 F24: PSX wobble toggle
    binding.switchEnvironmentWobble.setOnCheckedChangeListener(null);
    binding.switchEnvironmentWobble.setChecked(
        prefs.getBoolean(PREF.SH1_WOBBLE, DEF.SH1_WOBBLE));
    binding.switchEnvironmentWobble.setOnCheckedChangeListener(this);

    if (!auto) {
      int manual = Sh1WallpaperService.getManualEnv(prefs);
      binding.textEnvironmentModeValue.setText(
          getString(
              R.string.sh1_env_mode_value,
              String.valueOf(manual),
              SH1.getEnvModeName(manual)
          )
      );
    }

    int weather = Sh1WallpaperService.getUserWeather(prefs);
    if (weather == SH1.WEATHER_SCENE) {
      int sceneWeather = Sh1WallpaperService.getSceneWeather(activity);
      binding.textEnvironmentWeatherValue.setText(
          getString(
              R.string.sh1_weather_scene_value,
              SH1.getWeatherName(sceneWeather)
          )
      );
    } else {
      binding.textEnvironmentWeatherValue.setText(
          getString(
              R.string.sh1_weather_value,
              String.valueOf(weather),
              SH1.getWeatherName(weather)
          )
      );
    }

    // randomizer
    int randomizer = prefs.getInt(PREF.SH1_RANDOMIZER, DEF.SH1_RANDOMIZER);
    if (randomizer < 0 || randomizer >= SH1.RANDOM_COUNT) {
      randomizer = DEF.SH1_RANDOMIZER;
    }
    binding.textEnvironmentRandomizerValue.setText(
        getString(R.string.sh1_randomizer_value, SH1.RANDOM_MODES[randomizer])
    );
    binding.linearEnvironmentInterval.setVisibility(
        randomizer == SH1.RANDOM_INTERVAL ? View.VISIBLE : View.GONE);
    int interval = prefs.getInt(PREF.SH1_RANDOM_INTERVAL, DEF.SH1_RANDOM_INTERVAL);
    binding.textEnvironmentIntervalValue.setText(
        getString(R.string.sh1_randomizer_interval_description, interval)
    );
    // v7: randomizer extras only matter while the randomizer runs
    boolean randomOn = randomizer != SH1.RANDOM_OFF;
    binding.linearEnvironmentShuffle.setVisibility(randomOn ? View.VISIBLE : View.GONE);
    binding.linearEnvironmentFavorites.setVisibility(randomOn ? View.VISIBLE : View.GONE);
    binding.linearEnvironmentPrewarm.setVisibility(randomOn ? View.VISIBLE : View.GONE);

    // v7 switch rows
    setSwitchSilently(binding.switchEnvironmentThumbnails,
        prefs.getBoolean(PREF.SH1_THUMBNAILS, DEF.SH1_THUMBNAILS));
    setSwitchSilently(binding.switchEnvironmentShuffle,
        prefs.getBoolean(PREF.SH1_SHUFFLE_BAG, DEF.SH1_SHUFFLE_BAG));
    setSwitchSilently(binding.switchEnvironmentFavorites,
        prefs.getBoolean(PREF.SH1_FAVORITES_ONLY, DEF.SH1_FAVORITES_ONLY));
    setSwitchSilently(binding.switchEnvironmentPrewarm,
        prefs.getBoolean(PREF.SH1_PREWARM, DEF.SH1_PREWARM));
    setSwitchSilently(binding.switchEnvironmentOverrides,
        prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES));
  }

  /** set a switch without firing the change listener (refresh path) */
  private void setSwitchSilently(
      com.google.android.material.materialswitch.MaterialSwitch sw, boolean value) {
    sw.setOnCheckedChangeListener(null);
    sw.setChecked(value);
    sw.setOnCheckedChangeListener(this);
  }

  /**
   * v9.2 Features 30+31 — the Doodle-base wallpaper selector, re-introduced:
   * a horizontally scrolling row of circular {@link SelectionCardView}s,
   * ONE PER INDIVIDUAL camdat track (every track of every model — even
   * when several tracks originate from the exact same file/model — is one
   * distinct wallpaper). Tapping a circle selects that wallpaper (the
   * persisted (scene, track) pair -> the engine's own --track N boot
   * argv); the randomizer is untouched — it keeps drawing from its own
   * (model, track) pool.
   *
   * The thumbnail is the engine's own cached presented frame for THAT
   * track (filesDir/thumbnails/<glb>_t<N>.png, captured ~2 s after the
   * engine boots that wallpaper). It fills the circle COMPLETELY — the
   * inner 48 dp circular MaterialCardView clips the CENTER_CROPPED
   * ImageView (uniform cover scaling: zero bars, zero letterboxing).
   *
   * Efficiency (Phase I): each distinct bitmap is decoded exactly once
   * per populate pass and shared by reference across every card that
   * needs it; cards for models whose GLB is not present (BYOA pending)
   * are disabled with reduced alpha.
   */
  private void populateWallpaperSelector(Sh1Camdat camdat, SharedPreferences prefs) {
    LinearLayout circles = binding.linearEnvironmentWallpaperCircles;
    circles.removeAllViews();
    List<Sh1Camdat.Wallpaper> wallpapers = camdat.wallpapers();
    if (wallpapers.isEmpty()) {
      return;
    }
    int scene = Sh1WallpaperService.getActiveSceneIndex(prefs);
    int trackSel = prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK);
    // decode each model's thumbnail files once; per-track bitmaps differ
    HashMap<String, Bitmap> cache = new HashMap<>();
    int colorSelected = ResUtil.getColor(activity, R.attr.colorOnSecondaryContainer);
    int colorOnSurfaceVariant = ResUtil.getColor(activity, R.attr.colorOnSurfaceVariant);
    int captionTop = UiUtil.dpToPx(activity, 8);

    for (int i = 0; i < wallpapers.size(); i++) {
      final Sh1Camdat.Wallpaper w = wallpapers.get(i);
      final boolean isSelected = w.modelIndex == scene && w.trackIndex == trackSel;
      final boolean playable = w.playable() && w.enabled;

      // the per-track cached engine frame (decoded once per (glb,track))
      String key = w.model.file + "#" + w.trackIndex;
      Bitmap thumb = cache.get(key);
      if (thumb == null) {
        File file = Sh1WallpaperService.thumbnailFile(activity, w.model.file, w.trackIndex);
        if (file.exists()) {
          try {
            thumb = BitmapFactory.decodeFile(file.getAbsolutePath());
          } catch (Exception e) {
            thumb = null;
          }
        }
        if (thumb == null && w.model.glbPresent) {
          // not yet rendered for THIS track: fall back to any cached frame
          // of the same model (same room, different camera track) — still
          // a real render, never a dead placeholder when one exists
          for (int t = 0; t < w.model.tracks.size(); t++) {
            File alt = Sh1WallpaperService.thumbnailFile(activity, w.model.file, t);
            if (alt.exists()) {
              try {
                thumb = BitmapFactory.decodeFile(alt.getAbsolutePath());
              } catch (Exception e) {
                thumb = null;
              }
              if (thumb != null) {
                break;
              }
            }
          }
        }
        cache.put(key, thumb);   // null cached too: no repeated IO
      }

      // ONE item = the circle + its caption, stacked vertically — the
      // caption can never drift from its circle
      LinearLayout item = new LinearLayout(activity);
      item.setOrientation(LinearLayout.VERTICAL);
      item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

      SelectionCardView card = new SelectionCardView(activity);
      card.setCardImageBitmap(thumb);   // CENTER_CROP fill — no letterboxing
      card.setChecked(isSelected);
      card.setEnabled(playable);
      card.setAlpha(playable ? 1f : 0.45f);
      String contentDesc = w.name + " · " + w.model.file
          + (playable ? "" : getString(R.string.sh1_scene_missing));
      card.setContentDescription(contentDesc);
      card.setOnClickListener(v -> {
        if (!playable) {
          return;
        }
        performHapticClick();
        if (isSelected) {
          return;   // already the active wallpaper
        }
        prefs.edit()
            .putInt(PREF.SH1_SCENE, w.modelIndex)
            .putInt(PREF.SH1_TRACK, w.trackIndex)
            .apply();
        activity.requestSceneSwitch();
        refreshValues();
      });
      item.addView(card);

      // the caption under the circle: the wallpaper (track) name
      TextView caption = new TextView(activity, null, 0);
      caption.setText(w.name);
      caption.setTextAppearance(
          androidx.appcompat.R.style.TextAppearance_AppCompat_Small);
      caption.setTextColor(isSelected ? colorSelected : colorOnSurfaceVariant);
      caption.setMaxLines(1);
      caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
      caption.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
      caption.setContentDescription(contentDesc);
      LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      capLp.topMargin = captionTop;
      item.addView(caption, capLp);

      circles.addView(item);
    }
  }

  @Override
  public void onClick(View v) {
    int id = v.getId();
    if (getViewUtil().isClickDisabled(id)) {
      return;
    }
    performHapticClick();

    if (id == R.id.button_environment_info) {
      activity.showTextBottomSheet(R.raw.information, R.string.title_info);
    } else if (id == R.id.linear_environment_scene) {
      ViewUtil.startIcon(binding.imageEnvironmentScene);
      ViewUtil.showBottomSheet(activity, new Sh1ScenesBottomSheetDialogFragment());
    } else if (id == R.id.linear_environment_mode) {
      ViewUtil.startIcon(binding.imageEnvironmentMode);
      Sh1EnvModesBottomSheetDialogFragment sheet =
          new Sh1EnvModesBottomSheetDialogFragment();
      Bundle args = new Bundle();
      args.putInt(Sh1EnvModesBottomSheetDialogFragment.ARG_TARGET,
          Sh1EnvModesBottomSheetDialogFragment.TARGET_MANUAL);
      sheet.setArguments(args);
      ViewUtil.showBottomSheet(activity, sheet);
    } else if (id == R.id.linear_environment_weather) {
      ViewUtil.startIcon(binding.imageEnvironmentWeather);
      ViewUtil.showBottomSheet(activity, new Sh1WeatherBottomSheetDialogFragment());
    } else if (id == R.id.linear_environment_randomizer) {
      ViewUtil.startIcon(binding.imageEnvironmentRandomizer);
      ViewUtil.showBottomSheet(activity, new Sh1RandomizerBottomSheetDialogFragment());
    } else if (id == R.id.linear_environment_interval) {
      showIntervalPicker();
    } else if (id == R.id.linear_environment_thumbnails) {
      binding.switchEnvironmentThumbnails.toggle();
    } else if (id == R.id.linear_environment_overrides) {
      binding.switchEnvironmentOverrides.toggle();
    } else if (id == R.id.linear_environment_shuffle) {
      binding.switchEnvironmentShuffle.toggle();
    } else if (id == R.id.linear_environment_favorites) {
      binding.switchEnvironmentFavorites.toggle();
    } else if (id == R.id.linear_environment_prewarm) {
      binding.switchEnvironmentPrewarm.toggle();
    } else if (id == R.id.linear_environment_wobble) {
      binding.switchEnvironmentWobble.toggle();
    } else if (id == R.id.linear_environment_scene_day) {
      // v9 F21 + v9.2 F30: per-wallpaper Auto-Mode DAY env override picker
      ViewUtil.startIcon(binding.imageEnvironmentSceneDay);
      Sh1EnvModesBottomSheetDialogFragment sheet =
          new Sh1EnvModesBottomSheetDialogFragment();
      Bundle args = new Bundle();
      args.putInt(Sh1EnvModesBottomSheetDialogFragment.ARG_TARGET,
          Sh1EnvModesBottomSheetDialogFragment.TARGET_SCENE_DAY);
      sheet.setArguments(args);
      ViewUtil.showBottomSheet(activity, sheet);
    } else if (id == R.id.linear_environment_scene_night) {
      // v9 F21 + v9.2 F30: per-wallpaper Auto-Mode NIGHT env override picker
      ViewUtil.startIcon(binding.imageEnvironmentSceneNight);
      Sh1EnvModesBottomSheetDialogFragment sheet =
          new Sh1EnvModesBottomSheetDialogFragment();
      Bundle args = new Bundle();
      args.putInt(Sh1EnvModesBottomSheetDialogFragment.ARG_TARGET,
          Sh1EnvModesBottomSheetDialogFragment.TARGET_SCENE_NIGHT);
      sheet.setArguments(args);
      ViewUtil.showBottomSheet(activity, sheet);
    }
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
    int id = buttonView.getId();
    performHapticClick();
    SharedPreferences.Editor editor = getSharedPrefs().edit();
    if (id == R.id.switch_environment_wobble) {
      // v9 F24: PSX wobble — a boot argv value (--ps1/--modern absent):
      // restart with the new boot state (deterministic for every scene)
      editor.putBoolean(PREF.SH1_WOBBLE, isChecked).apply();
      activity.requestSceneSwitch();
    } else if (id == R.id.switch_environment_thumbnails) {
      editor.putBoolean(PREF.SH1_THUMBNAILS, isChecked).apply();
    } else if (id == R.id.switch_environment_shuffle) {
      editor.putBoolean(PREF.SH1_SHUFFLE_BAG, isChecked).apply();
    } else if (id == R.id.switch_environment_favorites) {
      editor.putBoolean(PREF.SH1_FAVORITES_ONLY, isChecked).apply();
    } else if (id == R.id.switch_environment_prewarm) {
      editor.putBoolean(PREF.SH1_PREWARM, isChecked).apply();
    } else if (id == R.id.switch_environment_overrides) {
      editor.putBoolean(PREF.SH1_SCENE_OVERRIDES, isChecked).apply();
      activity.requestSceneSwitch();
    }
  }

  /* ---- NumberPicker dialog (E-3 interval) ---- */

  private void showIntervalPicker() {
    SharedPreferences prefs = getSharedPrefs();
    int current = prefs.getInt(PREF.SH1_RANDOM_INTERVAL, DEF.SH1_RANDOM_INTERVAL);
    android.widget.FrameLayout container = wrapPicker(1, 720, current);
    android.widget.NumberPicker picker =
        (android.widget.NumberPicker) container.getChildAt(0);
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_randomizer_interval_title)
        .setView(container)
        .setPositiveButton(R.string.action_ok, (d, w) -> {
          getSharedPrefs().edit()
              .putInt(PREF.SH1_RANDOM_INTERVAL, picker.getValue())
              .putLong("sh1_random_next", 0L)
              .apply();
          activity.requestSettingsRefresh();
          refreshValues();
        })
        .setNegativeButton(R.string.action_cancel, null)
        .show();
  }

  /** a NumberPicker with the app's Material dialog padding conventions */
  private android.widget.FrameLayout wrapPicker(int min, int max, int value) {
    android.widget.FrameLayout container = new android.widget.FrameLayout(activity);
    int pad = (int) (20 * getResources().getDisplayMetrics().density);
    container.setPadding(0, 0, 0, 0);
    android.widget.NumberPicker picker = new android.widget.NumberPicker(activity);
    picker.setMinValue(min);
    picker.setMaxValue(max);
    picker.setValue(Math.max(min, Math.min(max, value)));
    picker.setWrapSelectorWheel(false);
    android.widget.FrameLayout.LayoutParams lp =
        new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.leftMargin = pad;
    lp.rightMargin = pad;
    container.addView(picker, lp);
    return container;
  }
}
