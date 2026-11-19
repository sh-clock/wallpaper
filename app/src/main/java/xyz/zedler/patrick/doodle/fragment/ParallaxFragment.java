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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.FrameLayout;
import android.widget.NumberPicker;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.activity.MainActivity;
import xyz.zedler.patrick.doodle.behavior.ScrollBehavior;
import xyz.zedler.patrick.doodle.behavior.SystemBarBehavior;
import xyz.zedler.patrick.doodle.databinding.FragmentParallaxBinding;
import xyz.zedler.patrick.doodle.util.ViewUtil;

/**
 * v9.2 Feature 32 — the PARALLAX settings page (the v9.1 monolithic
 * Environment page split into five categories). Carries the device-tilt
 * camera controls exactly as they were in v9.1 (v9 F23 + v9.1 Fix 3):
 * the tilt enable toggle, the significance threshold / deadzone, the
 * maximum camera angle and the sensor polling rate. All behavior code
 * is moved VERBATIM from AppearanceFragment — only the hosting page
 * changed.
 */
public class ParallaxFragment extends BaseFragment
    implements OnClickListener, OnCheckedChangeListener {

  private static final String TAG = ParallaxFragment.class.getSimpleName();

  private FragmentParallaxBinding binding;
  private MainActivity activity;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentParallaxBinding.inflate(inflater, container, false);
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
    systemBarBehavior.setAppBar(binding.appBarParallax);
    systemBarBehavior.setScroll(binding.scrollParallax, binding.constraintParallax);
    systemBarBehavior.setAdditionalBottomInset(activity.getFabTopEdgeDistance());
    systemBarBehavior.setUp();

    new ScrollBehavior().setUpScroll(binding.appBarParallax, binding.scrollParallax, true);

    binding.toolbarParallax.setNavigationOnClickListener(getNavigationOnClickListener());
    binding.toolbarParallax.setOnMenuItemClickListener(getOnMenuItemClickListener());

    ViewUtil.setOnClickListeners(
        this,
        binding.linearEnvironmentTilt,
        binding.linearEnvironmentTiltRate
    );
    ViewUtil.setOnCheckedChangeListeners(
        this,
        binding.switchEnvironmentTilt
    );

    /* v9 F23 + v9.1 Fix 3: Material sliders — the value commits on release;
     * the labels are concise (degrees) */
    binding.sliderEnvironmentTiltThreshold.addOnChangeListener((slider, value, fromUser) ->
        binding.textEnvironmentTiltThresholdValue.setText(
            getString(R.string.sh1_tilt_threshold_description, (int) value)));
    binding.sliderEnvironmentTiltThreshold.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(Slider slider) {
          }

          @Override
          public void onStopTrackingTouch(Slider slider) {
            getSharedPrefs().edit()
                .putInt(PREF.SH1_TILT_THRESHOLD, (int) slider.getValue())
                .apply();
          }
        });
    binding.sliderEnvironmentTiltThreshold.setLabelFormatter(
        value -> getString(R.string.sh1_tilt_threshold_description, (int) value));

    binding.sliderEnvironmentTiltMax.addOnChangeListener((slider, value, fromUser) ->
        binding.textEnvironmentTiltMaxValue.setText(
            getString(R.string.sh1_tilt_max_description, String.valueOf(value))));
    binding.sliderEnvironmentTiltMax.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(Slider slider) {
          }

          @Override
          public void onStopTrackingTouch(Slider slider) {
            getSharedPrefs().edit()
                .putInt(PREF.SH1_TILT_MAX, (int) Math.round(slider.getValue()))
                .apply();
          }
        });
    binding.sliderEnvironmentTiltMax.setLabelFormatter(
        value -> getString(R.string.sh1_tilt_max_description, String.valueOf(value)));

    refreshValues();
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshValues();
  }

  /** row visibility + value texts, all from the persisted settings */
  private void refreshValues() {
    if (binding == null || activity == null) {
      return;
    }
    SharedPreferences prefs = getSharedPrefs();

    // v9 F23 + v9.1 Fix 3: tilt settings (max shift ceiling now 20 deg)
    binding.switchEnvironmentTilt.setOnCheckedChangeListener(null);
    binding.switchEnvironmentTilt.setChecked(
        prefs.getBoolean(PREF.SH1_TILT, DEF.SH1_TILT));
    binding.switchEnvironmentTilt.setOnCheckedChangeListener(this);
    int tiltThreshold = Math.max(5, Math.min(
        prefs.getInt(PREF.SH1_TILT_THRESHOLD, DEF.SH1_TILT_THRESHOLD), 45));
    binding.sliderEnvironmentTiltThreshold.setValue(tiltThreshold);
    binding.textEnvironmentTiltThresholdValue.setText(
        getString(R.string.sh1_tilt_threshold_description, tiltThreshold));
    int tiltMax = Math.max(1, Math.min(
        prefs.getInt(PREF.SH1_TILT_MAX, DEF.SH1_TILT_MAX), 20));
    binding.sliderEnvironmentTiltMax.setValue(tiltMax);
    binding.textEnvironmentTiltMaxValue.setText(
        getString(R.string.sh1_tilt_max_description, String.valueOf(tiltMax)));
    int tiltRate = Math.max(1, Math.min(
        prefs.getInt(PREF.SH1_TILT_RATE, DEF.SH1_TILT_RATE), 20));
    binding.textEnvironmentTiltRateValue.setText(
        getString(R.string.sh1_tilt_rate_description, tiltRate));
    boolean tiltOn = prefs.getBoolean(PREF.SH1_TILT, DEF.SH1_TILT);
    binding.linearEnvironmentTiltThreshold.setVisibility(tiltOn ? View.VISIBLE : View.GONE);
    binding.sliderEnvironmentTiltThreshold.setVisibility(tiltOn ? View.VISIBLE : View.GONE);
    binding.linearEnvironmentTiltMax.setVisibility(tiltOn ? View.VISIBLE : View.GONE);
    binding.sliderEnvironmentTiltMax.setVisibility(tiltOn ? View.VISIBLE : View.GONE);
    binding.linearEnvironmentTiltRate.setVisibility(tiltOn ? View.VISIBLE : View.GONE);
  }

  @Override
  public void onClick(View v) {
    int id = v.getId();
    if (getViewUtil().isClickDisabled(id)) {
      return;
    }
    performHapticClick();

    if (id == R.id.linear_environment_tilt) {
      binding.switchEnvironmentTilt.toggle();
    } else if (id == R.id.linear_environment_tilt_rate) {
      showTiltRatePicker();
    }
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
    int id = buttonView.getId();
    performHapticClick();
    SharedPreferences.Editor editor = getSharedPrefs().edit();
    if (id == R.id.switch_environment_tilt) {
      // v9 F23: device-tilt camera (the service re-registers the sensor)
      editor.putBoolean(PREF.SH1_TILT, isChecked).apply();
      activity.requestSettingsRefresh();
      refreshValues();
    }
  }

  /**
   * v9 F23: the tilt sensor polling-rate picker (1..20 Hz; 5 Hz default —
   * lower rates save power, higher rates trade battery for immediacy).
   */
  private void showTiltRatePicker() {
    SharedPreferences prefs = getSharedPrefs();
    int current = prefs.getInt(PREF.SH1_TILT_RATE, DEF.SH1_TILT_RATE);
    FrameLayout container = wrapPicker(1, 20, current);
    NumberPicker picker = (NumberPicker) container.getChildAt(0);
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_tilt_rate)
        .setView(container)
        .setPositiveButton(R.string.action_ok, (d, w) -> {
          getSharedPrefs().edit()
              .putInt(PREF.SH1_TILT_RATE, picker.getValue())
              .apply();
          // the service re-registers the sensor on the next settings pass
          activity.requestSettingsRefresh();
          refreshValues();
        })
        .setNegativeButton(R.string.action_cancel, null)
        .show();
  }

  /** a NumberPicker with the app's Material dialog padding conventions */
  private FrameLayout wrapPicker(int min, int max, int value) {
    FrameLayout container = new FrameLayout(activity);
    int pad = (int) (20 * getResources().getDisplayMetrics().density);
    container.setPadding(0, 0, 0, 0);
    NumberPicker picker = new NumberPicker(activity);
    picker.setMinValue(min);
    picker.setMaxValue(max);
    picker.setValue(Math.max(min, Math.min(max, value)));
    picker.setWrapSelectorWheel(false);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.leftMargin = pad;
    lp.rightMargin = pad;
    container.addView(picker, lp);
    return container;
  }
}
