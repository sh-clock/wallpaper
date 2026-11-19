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
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.google.android.material.slider.Slider;
import java.util.Locale;
import xyz.zedler.patrick.doodle.Constants.ACTION;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.Constants.SH1;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.activity.MainActivity;
import xyz.zedler.patrick.doodle.behavior.ScrollBehavior;
import xyz.zedler.patrick.doodle.behavior.SystemBarBehavior;
import xyz.zedler.patrick.doodle.databinding.FragmentSettingsBinding;
import xyz.zedler.patrick.doodle.fragment.dialog.Sh1CitiesPickerBottomSheetDialogFragment;
import xyz.zedler.patrick.doodle.fragment.dialog.Sh1EnvModesBottomSheetDialogFragment;
import xyz.zedler.patrick.doodle.service.Sh1WallpaperService;
import xyz.zedler.patrick.doodle.util.ViewUtil;

/**
 * v9.2 Feature 32 — the SETTINGS page (the v9.1 monolithic Environment
 * page split into five categories). Carries the presentation and
 * schedule controls exactly as they were in v9.1: frame rate (v9 F26)
 * and resolution (v9 F25) sliders, Auto Mode (v8), the sun-time/NOAA
 * mode (v9 F22) with the city / manual-coordinates picker (v9.1 Fix 2),
 * the device-clock day/night schedules, the gradual phase change (v9
 * F20) and the custom day/night preset mapping (v7 F19). All behavior
 * code is moved VERBATIM from AppearanceFragment — only the hosting
 * page changed.
 */
public class SettingsFragment extends BaseFragment
    implements OnClickListener, OnCheckedChangeListener {

  private static final String TAG = SettingsFragment.class.getSimpleName();

  private FragmentSettingsBinding binding;
  private MainActivity activity;
  private android.content.BroadcastReceiver receiver;
  private boolean receiverRegistered = false;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentSettingsBinding.inflate(inflater, container, false);
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
    systemBarBehavior.setAppBar(binding.appBarSettings);
    systemBarBehavior.setScroll(binding.scrollSettings, binding.constraintSettings);
    systemBarBehavior.setAdditionalBottomInset(activity.getFabTopEdgeDistance());
    systemBarBehavior.setUp();

    new ScrollBehavior().setUpScroll(binding.appBarSettings, binding.scrollSettings, true);

    binding.toolbarSettings.setNavigationOnClickListener(getNavigationOnClickListener());
    binding.toolbarSettings.setOnMenuItemClickListener(getOnMenuItemClickListener());

    ViewUtil.setOnClickListeners(
        this,
        binding.linearEnvironmentAuto,
        binding.linearEnvironmentDay,
        binding.linearEnvironmentNight,
        binding.linearEnvironmentSunMode,
        binding.linearEnvironmentCity,
        binding.linearEnvironmentAutoDay,
        binding.linearEnvironmentAutoNight
    );
    ViewUtil.setOnClickListeners(
        this,
        binding.linearEnvironmentDither,
        binding.buttonEnvironmentResolutionReset,
        binding.buttonEnvironmentFpsReset,
        binding.buttonEnvironmentCropXReset,
        binding.buttonEnvironmentCropYReset
    );
    ViewUtil.setOnCheckedChangeListeners(
        this,
        binding.switchEnvironmentAuto,
        binding.switchEnvironmentTransition,
        binding.switchEnvironmentSunMode,
        binding.switchEnvironmentAutoPreset,
        binding.switchEnvironmentDither
    );

    /* v9 F25/F26: Material sliders — the value commits on release;
     * the labels are concise (resolution ladder / fps) */
    binding.sliderEnvironmentResolution.addOnChangeListener((slider, value, fromUser) ->
        binding.textEnvironmentResolutionValue.setText(resLabel((int) value)));
    binding.sliderEnvironmentResolution.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(Slider slider) {
          }

          @Override
          public void onStopTrackingTouch(Slider slider) {
            int idx = (int) slider.getValue();
            if (idx != getSharedPrefs().getInt(PREF.SH1_RESOLUTION, DEF.SH1_RESOLUTION)) {
              getSharedPrefs().edit().putInt(PREF.SH1_RESOLUTION, idx).apply();
              // the framebuffer is allocated at engine boot: restart with
              // the new --res value (the authentic default = no flag)
              activity.requestSceneSwitch();
            }
          }
        });
    binding.sliderEnvironmentResolution.setLabelFormatter(value -> resLabel((int) value));

    binding.sliderEnvironmentFps.addOnChangeListener((slider, value, fromUser) ->
        binding.textEnvironmentFpsValue.setText(fpsLabel((int) value)));
    binding.sliderEnvironmentFps.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(Slider slider) {
          }

          @Override
          public void onStopTrackingTouch(Slider slider) {
            int fps = (int) slider.getValue();
            if (fps != getSharedPrefs().getInt(PREF.SH1_FPS_CAP, DEF.SH1_FPS_CAP)) {
              getSharedPrefs().edit().putInt(PREF.SH1_FPS_CAP, fps).apply();
              // the presentation cap is a boot argv value: restart with the
              // new --fps (the authentic default 30 = the engine's own rate)
              activity.requestSceneSwitch();
            }
          }
        });
    binding.sliderEnvironmentFps.setLabelFormatter(value -> fpsLabel((int) value));

    /* v10.1 TASK 4: the crop-window pan sliders — CONTINUOUS (no stepSize:
     * the v10.0 slider-crash lesson — programmatic floats must never hit a
     * step lattice) and LIVE: while dragging, the offset goes straight to
     * the running engine's presentation blit (next-frame effect, no
     * restart); on release it persists through the standard flow. */
    binding.sliderEnvironmentCropX.addOnChangeListener((slider, value, fromUser) -> {
      binding.textEnvironmentCropXValue.setText(cropXLabel(value));
      if (fromUser) {
        pushCropLive(value, binding.sliderEnvironmentCropY.getValue());
      }
    });
    binding.sliderEnvironmentCropX.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(Slider slider) {
          }

          @Override
          public void onStopTrackingTouch(Slider slider) {
            persistCropFromSliders();
          }
        });
    binding.sliderEnvironmentCropX.setLabelFormatter(
        value -> String.format(Locale.US, "%.2f", value));

    binding.sliderEnvironmentCropY.addOnChangeListener((slider, value, fromUser) -> {
      binding.textEnvironmentCropYValue.setText(cropYLabel(value));
      if (fromUser) {
        pushCropLive(binding.sliderEnvironmentCropX.getValue(), value);
      }
    });
    binding.sliderEnvironmentCropY.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(Slider slider) {
          }

          @Override
          public void onStopTrackingTouch(Slider slider) {
            persistCropFromSliders();
          }
        });
    binding.sliderEnvironmentCropY.setLabelFormatter(
        value -> String.format(Locale.US, "%.2f", value));

    refreshValues();
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshValues();

    // stay in sync with settings changes (bottom-sheet selections) and the
    // device clock (Auto Mode day/night flips while the page is open)
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
        requireActivity().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
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
    boolean sunMode = prefs.getBoolean(PREF.SH1_SUN_MODE, DEF.SH1_SUN_MODE);

    binding.switchEnvironmentAuto.setOnCheckedChangeListener(null);
    binding.switchEnvironmentAuto.setChecked(auto);
    binding.switchEnvironmentAuto.setOnCheckedChangeListener(this);

    // schedule rows: only meaningful when Auto Mode is on (the toggle
    // DISABLES the day/night schedule completely -> single manual mode)
    binding.linearEnvironmentDay.setVisibility(
        auto && !sunMode ? View.VISIBLE : View.GONE);
    binding.linearEnvironmentNight.setVisibility(
        auto && !sunMode ? View.VISIBLE : View.GONE);

    // v9 F22 rows: sun mode + city (city only while sun mode is on)
    binding.switchEnvironmentSunMode.setOnCheckedChangeListener(null);
    binding.switchEnvironmentSunMode.setChecked(sunMode);
    binding.switchEnvironmentSunMode.setOnCheckedChangeListener(this);
    binding.linearEnvironmentCity.setVisibility(sunMode ? View.VISIBLE : View.GONE);
    String cityRaw = prefs.getString(PREF.SH1_CITY, DEF.SH1_CITY);
    if (cityRaw == null || cityRaw.isEmpty()) {
      binding.textEnvironmentCityValue.setText(R.string.sh1_city_none);
    } else {
      String[] f = cityRaw.split("\\|");
      if (f.length == 4) {
        // v9.1 Fix 2: manual coordinates show their own label
        String label = "Custom".equals(f[0])
            ? getString(R.string.sh1_city_custom) : f[0] + ", " + f[1];
        long[] tw = null;
        try {
          tw = xyz.zedler.patrick.doodle.util.Sh1Solar.civilTwilightUtc(
              Double.parseDouble(f[2]), Double.parseDouble(f[3]));
        } catch (NumberFormatException ignored) {
        }
        if (tw != null) {
          java.text.DateFormat hm = java.text.DateFormat.getTimeInstance(
              java.text.DateFormat.SHORT, Locale.getDefault());
          binding.textEnvironmentCityValue.setText(
              getString(R.string.sh1_city_twilight_value,
                  label,
                  hm.format(new java.util.Date(tw[0])),
                  hm.format(new java.util.Date(tw[1]))));
        } else {
          binding.textEnvironmentCityValue.setText(
              getString(R.string.sh1_city_value, label));
        }
      } else {
        binding.textEnvironmentCityValue.setText(R.string.sh1_city_none);
      }
    }

    // v9 F20: gradual transition toggle
    binding.switchEnvironmentTransition.setOnCheckedChangeListener(null);
    binding.switchEnvironmentTransition.setChecked(
        prefs.getBoolean(PREF.SH1_TRANSITION, DEF.SH1_TRANSITION));
    binding.switchEnvironmentTransition.setOnCheckedChangeListener(this);

    // v9.6 (Camtrack v13): context-aware — the camdat day/night presets
    // participate in the CURRENT-mode display
    int env = Sh1WallpaperService.getEffectiveEnv(activity, prefs);
    if (auto) {
      boolean day = sunMode ? SH1.isSunTimeDay(prefs) : SH1.isDaytime();
      binding.textEnvironmentCurrent.setText(
          day ? R.string.sh1_current_mode_day : R.string.sh1_current_mode_night
      );
    } else {
      binding.textEnvironmentCurrent.setText(
          getString(
              R.string.sh1_current_mode_manual,
              String.valueOf(env),
              SH1.getEnvModeName(env)
          )
      );
    }

    // schedule values (Material TimePicker results)
    int[] day = Sh1WallpaperService.getDaySchedule(prefs);
    int[] night = Sh1WallpaperService.getNightSchedule(prefs);
    binding.textEnvironmentDayValue.setText(
        getString(R.string.sh1_day_time_description, SH1.formatTime(day[0], day[1]))
    );
    binding.textEnvironmentNightValue.setText(
        getString(R.string.sh1_night_time_description, SH1.formatTime(night[0], night[1]))
    );

    // v7 F19: the customization toggle + the two mapped presets
    setSwitchSilently(binding.switchEnvironmentAutoPreset,
        prefs.getBoolean(PREF.SH1_AUTO_CUSTOM, DEF.SH1_AUTO_CUSTOM));
    boolean customAuto = auto && prefs.getBoolean(PREF.SH1_AUTO_CUSTOM, DEF.SH1_AUTO_CUSTOM);
    binding.linearEnvironmentAutoDay.setVisibility(customAuto ? View.VISIBLE : View.GONE);
    binding.linearEnvironmentAutoNight.setVisibility(customAuto ? View.VISIBLE : View.GONE);
    int dayEnv = Sh1WallpaperService.getAutoDayEnv(prefs);
    binding.textEnvironmentAutoDayValue.setText(
        getString(R.string.sh1_env_mode_value, String.valueOf(dayEnv),
            SH1.getEnvModeName(dayEnv)));
    int nightEnv = Sh1WallpaperService.getAutoNightEnv(prefs);
    binding.textEnvironmentAutoNightValue.setText(
        getString(R.string.sh1_env_mode_value, String.valueOf(nightEnv),
            SH1.getEnvModeName(nightEnv)));

    // v7 F17/F18 + v9 F25/F26: presentation settings (sliders + values)
    int resIdx = Math.max(0, Math.min(
        prefs.getInt(PREF.SH1_RESOLUTION, DEF.SH1_RESOLUTION), SH1.RES_COUNT - 1));
    binding.sliderEnvironmentResolution.setValue(resIdx);
    binding.textEnvironmentResolutionValue.setText(resLabel(resIdx));

    // v10.0 Task 3: the dither toggle mirrors the EFFECTIVE state (the
    // user's explicit choice when made, the resolution rule otherwise)
    boolean userSetDither = prefs.getBoolean(
        PREF.SH1_DITHER_USER_SET, DEF.SH1_DITHER_USER_SET);
    boolean ditherOn = Sh1WallpaperService.getEffectiveDither(prefs);
    setSwitchSilently(binding.switchEnvironmentDither, ditherOn);
    if (userSetDither) {
      binding.textEnvironmentDitherValue.setText(
          ditherOn ? R.string.sh1_dither_user_on : R.string.sh1_dither_user_off);
    } else {
      binding.textEnvironmentDitherValue.setText(
          ditherOn ? R.string.sh1_dither_auto_on : R.string.sh1_dither_auto_off);
    }

    int fps = Math.max(SH1.FPS_MIN, Math.min(
        prefs.getInt(PREF.SH1_FPS_CAP, DEF.SH1_FPS_CAP), SH1.FPS_MAX));
    binding.sliderEnvironmentFps.setValue(fps);
    binding.textEnvironmentFpsValue.setText(fpsLabel(fps));

    // v10.1 TASK 4: the crop pan (live-settable presentation state — no
    // restart, so a plain value refresh suffices)
    float[] crop = Sh1WallpaperService.getCropOffsets(prefs);
    binding.sliderEnvironmentCropX.setValue(crop[0]);
    binding.sliderEnvironmentCropY.setValue(crop[1]);
    binding.textEnvironmentCropXValue.setText(cropXLabel(crop[0]));
    binding.textEnvironmentCropYValue.setText(cropYLabel(crop[1]));
  }

  /** set a switch without firing the change listener (refresh path) */
  private void setSwitchSilently(
      com.google.android.material.materialswitch.MaterialSwitch sw, boolean value) {
    sw.setOnCheckedChangeListener(null);
    sw.setChecked(value);
    sw.setOnCheckedChangeListener(this);
  }

  @Override
  public void onClick(View v) {
    int id = v.getId();
    if (getViewUtil().isClickDisabled(id)) {
      return;
    }
    performHapticClick();

    if (id == R.id.linear_environment_dither) {
      binding.switchEnvironmentDither.toggle();
    } else if (id == R.id.linear_environment_auto) {
      binding.switchEnvironmentAuto.toggle();
    } else if (id == R.id.linear_environment_day) {
      showTimePicker(true);
    } else if (id == R.id.linear_environment_night) {
      showTimePicker(false);
    } else if (id == R.id.linear_environment_auto_day) {
      ViewUtil.startIcon(binding.imageEnvironmentAutoDay);
      Sh1EnvModesBottomSheetDialogFragment sheet =
          new Sh1EnvModesBottomSheetDialogFragment();
      Bundle args = new Bundle();
      args.putInt(Sh1EnvModesBottomSheetDialogFragment.ARG_TARGET,
          Sh1EnvModesBottomSheetDialogFragment.TARGET_AUTO_DAY);
      sheet.setArguments(args);
      ViewUtil.showBottomSheet(activity, sheet);
    } else if (id == R.id.linear_environment_auto_night) {
      ViewUtil.startIcon(binding.imageEnvironmentAutoNight);
      Sh1EnvModesBottomSheetDialogFragment sheet =
          new Sh1EnvModesBottomSheetDialogFragment();
      Bundle args = new Bundle();
      args.putInt(Sh1EnvModesBottomSheetDialogFragment.ARG_TARGET,
          Sh1EnvModesBottomSheetDialogFragment.TARGET_AUTO_NIGHT);
      sheet.setArguments(args);
      ViewUtil.showBottomSheet(activity, sheet);
    } else if (id == R.id.linear_environment_sun_mode) {
      binding.switchEnvironmentSunMode.toggle();
    } else if (id == R.id.linear_environment_city) {
      // v9 F22: the built-in world city picker (search over the GeoNames set)
      ViewUtil.startIcon(binding.imageEnvironmentCity);
      ViewUtil.showBottomSheet(activity, new Sh1CitiesPickerBottomSheetDialogFragment());
    } else if (id == R.id.linear_environment_resolution) {
      // v9 F25: the slider below the row IS the control; the row itself
      // just reflects the value (non-clickable chrome)
    } else if (id == R.id.linear_environment_fps) {
      // v9 F26: same — the slider is the control
    } else if (id == R.id.button_environment_resolution_reset) {
      // v9 F25: Reset to the authentic 320x224 PS1 raster
      performHapticClick();
      if (getSharedPrefs().getInt(PREF.SH1_RESOLUTION, DEF.SH1_RESOLUTION)
          != SH1.RES_AUTHENTIC) {
        getSharedPrefs().edit().putInt(PREF.SH1_RESOLUTION, SH1.RES_AUTHENTIC).apply();
        activity.requestSceneSwitch();
        refreshValues();
      }
    } else if (id == R.id.button_environment_fps_reset) {
      // v9 F26: Reset to the authentic 30 fps
      performHapticClick();
      if (getSharedPrefs().getInt(PREF.SH1_FPS_CAP, DEF.SH1_FPS_CAP)
          != SH1.FPS_AUTHENTIC) {
        getSharedPrefs().edit().putInt(PREF.SH1_FPS_CAP, SH1.FPS_AUTHENTIC).apply();
        activity.requestSceneSwitch();
        refreshValues();
      }
    } else if (id == R.id.button_environment_crop_x_reset) {
      // v10.1 TASK 4: re-center the horizontal crop window
      performHapticClick();
      if (getSharedPrefs().getFloat(PREF.SH1_CROP_X, DEF.SH1_CROP_X) != 0f) {
        getSharedPrefs().edit().putFloat(PREF.SH1_CROP_X, 0f).apply();
        pushCropLive(0f, Sh1WallpaperService.getCropOffsets(getSharedPrefs())[1]);
        activity.requestSettingsRefresh();
        refreshValues();
      }
    } else if (id == R.id.button_environment_crop_y_reset) {
      // v10.1 TASK 4: re-center the vertical crop window
      performHapticClick();
      if (getSharedPrefs().getFloat(PREF.SH1_CROP_Y, DEF.SH1_CROP_Y) != 0f) {
        getSharedPrefs().edit().putFloat(PREF.SH1_CROP_Y, 0f).apply();
        pushCropLive(Sh1WallpaperService.getCropOffsets(getSharedPrefs())[0], 0f);
        activity.requestSettingsRefresh();
        refreshValues();
      }
    }
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
    int id = buttonView.getId();
    performHapticClick();
    SharedPreferences.Editor editor = getSharedPrefs().edit();
    if (id == R.id.switch_environment_auto) {
      editor.putBoolean(PREF.SH1_AUTO, isChecked).apply();
      activity.requestSettingsRefresh();
      refreshValues();
    } else if (id == R.id.switch_environment_transition) {
      // v9 F20: gradual day/night blend at the auto-mode boundary
      editor.putBoolean(PREF.SH1_TRANSITION, isChecked).apply();
      activity.requestSettingsRefresh();
    } else if (id == R.id.switch_environment_sun_mode) {
      // v9 F22: sun-time auto mode (NOAA civil twilight at the city)
      editor.putBoolean(PREF.SH1_SUN_MODE, isChecked).apply();
      activity.requestSettingsRefresh();
      refreshValues();
    } else if (id == R.id.switch_environment_auto_preset) {
      // F19: disabling the customization restores the authentic mapping
      // (day preset 1 / night preset 6) — getAutoDay/NightEnv read the
      // authentic values whenever the toggle is off.
      editor.putBoolean(PREF.SH1_AUTO_CUSTOM, isChecked).apply();
      activity.requestSettingsRefresh();
      refreshValues();
    } else if (id == R.id.switch_environment_dither) {
      // v10.0 Task 3: an EXPLICIT user choice — respected at every
      // resolution (SH1_DITHER_USER_SET latches; the auto rule only
      // governs until the user touches the toggle once).
      editor.putBoolean(PREF.SH1_DITHER, isChecked)
          .putBoolean(PREF.SH1_DITHER_USER_SET, true).apply();
      // dither is a boot argv value (--no-dither): restart the engine
      activity.requestSceneSwitch();
      refreshValues();
    }
  }

  /* ---- Material TimePicker (Phase E-2; Phase I: dial input mode) ---- */

  private void showTimePicker(boolean day) {
    SharedPreferences prefs = getSharedPrefs();
    int[] current = day ? Sh1WallpaperService.getDaySchedule(prefs)
        : Sh1WallpaperService.getNightSchedule(prefs);
    MaterialTimePicker picker = new MaterialTimePicker.Builder()
        .setTimeFormat(DateFormat.is24HourFormat(activity)
            ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
        .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
        .setHour(current[0])
        .setMinute(current[1])
        .setTitleText(day ? R.string.sh1_time_picker_day : R.string.sh1_time_picker_night)
        .build();
    picker.addOnPositiveButtonClickListener(view -> {
      getSharedPrefs().edit()
          .putInt(day ? PREF.SH1_DAY_HOUR : PREF.SH1_NIGHT_HOUR, picker.getHour())
          .putInt(day ? PREF.SH1_DAY_MINUTE : PREF.SH1_NIGHT_MINUTE, picker.getMinute())
          .apply();
      activity.requestSettingsRefresh();
      refreshValues();
    });
    picker.show(getChildFragmentManager(), day ? "dayTimePicker" : "nightTimePicker");
  }

  /* ---- v9 F25/F26: the presentation labels ---- */

  /** v9 F25: the concise resolution-ladder label (row value + slider) */
  private String resLabel(int idx) {
    if (idx < 0 || idx >= SH1.RES_COUNT) {
      idx = 0;
    }
    int[] r = SH1.RES_MODES[idx];
    if (idx == SH1.RES_AUTHENTIC) {
      return getString(R.string.sh1_res_default_label);
    }
    return getString(R.string.sh1_res_slider_value, r[0] + " × " + r[1]);
  }

  /** v9 F26: the concise frame-rate label */
  private String fpsLabel(int fps) {
    if (fps == SH1.FPS_AUTHENTIC) {
      return getString(R.string.sh1_fps_authentic);
    }
    return fps + " fps";
  }

  /** v10.1 TASK 4: the horizontal crop label (0 = centered, the default) */
  private String cropXLabel(float x) {
    if (Math.abs(x) < 0.005f) {
      return getString(R.string.sh1_crop_center);
    }
    int pct = Math.round(Math.abs(x) * 100f);
    return getString(
        x < 0 ? R.string.sh1_crop_left : R.string.sh1_crop_right, pct);
  }

  /** v10.1 TASK 4: the vertical crop label (0 = centered, the default) */
  private String cropYLabel(float y) {
    if (Math.abs(y) < 0.005f) {
      return getString(R.string.sh1_crop_center);
    }
    int pct = Math.round(Math.abs(y) * 100f);
    return getString(
        y < 0 ? R.string.sh1_crop_up : R.string.sh1_crop_down, pct);
  }

  /**
   * v10.1 TASK 4: LIVE push — the presentation blit reads the offsets at
   * every frame, so the running wallpaper follows the drag directly. The
   * guarded native call is inert when the engine is not loaded (unit
   * tests, engine down) — the persisted pref + the next boot covers it.
   */
  private void pushCropLive(float x, float y) {
    try {
      xyz.zedler.patrick.doodle.service.Sh1Native.setCropOffset(x, y);
    } catch (Throwable ignored) {
      // no engine in this process state — the persisted value applies at
      // the next boot / settings refresh
    }
  }

  /** v10.1 TASK 4: commit the slider values (release / reset paths) */
  private void persistCropFromSliders() {
    float x = binding.sliderEnvironmentCropX.getValue();
    float y = binding.sliderEnvironmentCropY.getValue();
    SharedPreferences prefs = getSharedPrefs();
    if (prefs.getFloat(PREF.SH1_CROP_X, DEF.SH1_CROP_X) != x
        || prefs.getFloat(PREF.SH1_CROP_Y, DEF.SH1_CROP_Y) != y) {
      prefs.edit()
          .putFloat(PREF.SH1_CROP_X, x)
          .putFloat(PREF.SH1_CROP_Y, y)
          .apply();
      // the canonical live-apply broadcast (the service re-asserts the
      // offsets on the running engine — same values, idempotent)
      activity.requestSettingsRefresh();
    }
  }
}
