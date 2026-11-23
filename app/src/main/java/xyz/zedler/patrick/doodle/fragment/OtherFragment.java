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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.snackbar.Snackbar.Callback;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import xyz.zedler.patrick.doodle.Constants.ACTION;
import xyz.zedler.patrick.doodle.Constants.CONTRAST;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.EXTRA;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.Constants.THEME;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.activity.LauncherActivity;
import xyz.zedler.patrick.doodle.activity.MainActivity;
import xyz.zedler.patrick.doodle.behavior.ScrollBehavior;
import xyz.zedler.patrick.doodle.behavior.SystemBarBehavior;
import xyz.zedler.patrick.doodle.databinding.FragmentOtherBinding;
import xyz.zedler.patrick.doodle.fragment.dialog.Sh1DiagnosticsBottomSheetDialogFragment;
import xyz.zedler.patrick.doodle.service.Sh1WallpaperService;
import xyz.zedler.patrick.doodle.util.DialogUtil;
import xyz.zedler.patrick.doodle.util.LocaleUtil;
import xyz.zedler.patrick.doodle.util.Sh1Camdat;
import xyz.zedler.patrick.doodle.util.Sh1Storage;
import xyz.zedler.patrick.doodle.util.UiUtil;
import xyz.zedler.patrick.doodle.util.ViewUtil;
import xyz.zedler.patrick.doodle.view.SelectionCardView;

/**
 * v9.2 Feature 32 — the OTHER OPTIONS page (the v9.1 monolithic
 * Environment page split into five categories). Carries the battery &
 * power family (critical freeze threshold, mid-battery half-rate,
 * power-saver compliance, charging resume, AOD freeze, adaptive pacing),
 * the data & diagnostics family (camdat config import via SAF, BYOA
 * asset import, storage guard, watchdog, SAF backup/restore,
 * diagnostics, Quick Settings tiles) and the app's own rows (language,
 * theme, contrast, launcher, log, reset). All behavior code is moved
 * VERBATIM from v9.1's AppearanceFragment — only the hosting page
 * changed.
 */
public class OtherFragment extends BaseFragment
    implements OnClickListener, OnCheckedChangeListener {

  private static final String TAG = OtherFragment.class.getSimpleName();

  /** the engine's verbatim camdat track type strings (camdat_parse_type) */
  private static final String[] TRACK_TYPES = {
      "line", "circle", "curve", "fixed", "polyline", "spiral", "zigzag", "figure8"
  };

  /** F5: import cap + warning thresholds */
  private static final long IMPORT_CAP_BYTES = 512L * 1024 * 1024;
  private static final long LARGE_ASSET_BYTES = 64L * 1024 * 1024;
  private static final long LOW_DISK_BYTES = 256L * 1024 * 1024;

  private FragmentOtherBinding binding;
  private MainActivity activity;
  private DialogUtil dialogUtilReset;
  private android.content.BroadcastReceiver receiver;
  private boolean receiverRegistered = false;

  /* SAF launchers (Phase D BYOA + Phase E-1 config + v7 F7/F14 backup) —
   * persistable URI grants so the same folders/files can be re-read across
   * reboots. */

  private final ActivityResultLauncher<String[]> configPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onConfigPicked);

  private final ActivityResultLauncher<Uri> byoaFolderPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
          this::onByoaFolderPicked);

  private final ActivityResultLauncher<String> backupExportPicker =
      registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
          this::onBackupExportPicked);

  private final ActivityResultLauncher<String[]> backupImportPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocument(),
          this::onBackupImportPicked);

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentOtherBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
    dialogUtilReset.dismiss();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    activity = (MainActivity) requireActivity();

    SystemBarBehavior systemBarBehavior = new SystemBarBehavior(activity);
    systemBarBehavior.setAppBar(binding.appBarOther);
    systemBarBehavior.setScroll(binding.scrollOther, binding.constraintOther);
    systemBarBehavior.setAdditionalBottomInset(activity.getFabTopEdgeDistance());
    systemBarBehavior.setUp();

    new ScrollBehavior().setUpScroll(binding.appBarOther, binding.scrollOther, true);

    binding.toolbarOther.setNavigationOnClickListener(getNavigationOnClickListener());
    binding.toolbarOther.setOnMenuItemClickListener(getOnMenuItemClickListener());

    binding.textOtherLanguage.setText(
        LocaleUtil.followsSystem()
            ? getString(R.string.other_language_system)
            : LocaleUtil.getLocaleName()
    );

    setUpThemeSelection();

    int id;
    switch (getSharedPrefs().getInt(PREF.UI_MODE, DEF.UI_MODE)) {
      case AppCompatDelegate.MODE_NIGHT_NO:
        id = R.id.button_other_theme_light;
        break;
      case AppCompatDelegate.MODE_NIGHT_YES:
        id = R.id.button_other_theme_dark;
        break;
      default:
        id = R.id.button_other_theme_auto;
        break;
    }
    binding.toggleOtherTheme.check(id);
    binding.toggleOtherTheme.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
      if (!isChecked) {
        return;
      }
      int pref;
      if (checkedId == R.id.button_other_theme_light) {
        pref = AppCompatDelegate.MODE_NIGHT_NO;
      } else if (checkedId == R.id.button_other_theme_dark) {
        pref = AppCompatDelegate.MODE_NIGHT_YES;
      } else {
        pref = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
      }
      getSharedPrefs().edit().putInt(PREF.UI_MODE, pref).apply();
      performHapticClick();
      activity.restartToApply(
          0, getInstanceState(), false, true
      );
    });

    int idContrast;
    switch (getSharedPrefs().getString(PREF.UI_CONTRAST, DEF.UI_CONTRAST)) {
      case CONTRAST.MEDIUM:
        idContrast = R.id.button_other_contrast_medium;
        break;
      case CONTRAST.HIGH:
        idContrast = R.id.button_other_contrast_high;
        break;
      default:
        idContrast = R.id.button_other_contrast_standard;
        break;
    }
    binding.toggleOtherContrast.check(idContrast);
    binding.toggleOtherContrast.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
      if (!isChecked) {
        return;
      }
      String pref;
      if (checkedId == R.id.button_other_contrast_medium) {
        pref = CONTRAST.MEDIUM;
      } else if (checkedId == R.id.button_other_contrast_high) {
        pref = CONTRAST.HIGH;
      } else {
        pref = CONTRAST.STANDARD;
      }
      getSharedPrefs().edit().putString(PREF.UI_CONTRAST, pref).apply();
      performHapticClick();
      ViewUtil.startIcon(binding.imageSettingsContrast);
      activity.restartToApply(
          0, getInstanceState(), false, true
      );
    });
    boolean enabled = !getSharedPrefs().getString(PREF.THEME, DEF.THEME).equals(THEME.DYNAMIC);
    binding.toggleOtherContrast.setEnabled(enabled);
    binding.textSettingsContrastDynamic.setVisibility(enabled ? View.GONE : View.VISIBLE);
    binding.textSettingsContrastDynamic.setText(
        VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE
            ? R.string.other_contrast_dynamic
            : R.string.other_contrast_dynamic_unsupported
    );

    binding.switchOtherLauncher.setChecked(
        activity.getPackageManager().getComponentEnabledSetting(
            new ComponentName(activity, LauncherActivity.class)
        ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    );
    binding.switchOtherLauncher.jumpDrawablesToCurrentState();

    dialogUtilReset = new DialogUtil(activity, "reset");
    dialogUtilReset.createCaution(
        R.string.msg_reset,
        R.string.msg_reset_description,
        R.string.action_reset,
        () -> {
          performHapticHeavyClick();
          activity.reset();
          activity.restartToApply(
              100,
              getInstanceState(),
              Sh1WallpaperService.isEngineRunning(),
              true
          );
        });
    dialogUtilReset.showIfWasShown(savedInstanceState);

    // v9.9: the RENDERING BACKEND picker — the v9.8 disabled-switch row
    // becomes a real 3-way setting (Software default / OpenGL ES / Vulkan;
    // the user overruled the v9.8 no-ship verdict — the hardware path
    // ships at its measured ~99% bit-exact ceiling with the PS1 wobble +
    // dither tables perfectly preserved). The current backend shows in
    // the row's description; hardware picks show the experimental warning
    // BEFORE applying. A change restarts the engine (boot argv --hw).
    refreshBackendRow();

    ViewUtil.setOnClickListeners(
        this,
        binding.linearOtherLanguage,
        binding.linearOtherHwrender,
        binding.linearOtherLauncher,
        binding.linearOtherLog,
        binding.linearOtherReset,
        binding.linearEnvironmentBattery,
        binding.linearEnvironmentMidBattery,
        binding.linearEnvironmentPowerSave,
        binding.linearEnvironmentConfig,
        binding.linearEnvironmentByoa,
        binding.linearEnvironmentStorageGuard,
        binding.linearEnvironmentWatchdog,
        binding.linearEnvironmentBackup,
        binding.linearEnvironmentCharging,
        binding.linearEnvironmentAod,
        binding.linearEnvironmentHalfrate,
        binding.linearEnvironmentAdaptive,
        binding.linearEnvironmentDiagnostics,
        binding.linearEnvironmentTiles
    );

    ViewUtil.setOnCheckedChangeListeners(
        this,
        binding.switchOtherLauncher,
        binding.switchEnvironmentPowerSave,
        binding.switchEnvironmentStorageGuard,
        binding.switchEnvironmentWatchdog,
        binding.switchEnvironmentCharging,
        binding.switchEnvironmentAod,
        binding.switchEnvironmentHalfrate,
        binding.switchEnvironmentAdaptive,
        binding.switchEnvironmentTiles
    );

    refreshSh1Values();
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshSh1Values();

    // stay in sync with settings changes (battery broadcasts arrive via
    // the service; the values here refresh on SETTINGS_CHANGED)
    receiver = new android.content.BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
          return;
        }
        if (binding == null) {
          return;
        }
        if (ACTION.SETTINGS_CHANGED.equals(intent.getAction())) {
          refreshSh1Values();
        }
      }
    };
    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION.SETTINGS_CHANGED);
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        requireActivity().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
      } else {
        requireActivity().registerReceiver(receiver, filter);
      }
      receiverRegistered = true;
    } catch (Exception e) {
      Log.e(TAG, "onResume: registerReceiver failed", e);
    }
  }

  @Override
  public void onPause() {
    if (receiverRegistered) {
      try {
        requireActivity().unregisterReceiver(receiver);
      } catch (Exception e) {
        Log.e(TAG, "onPause: unregisterReceiver failed", e);
      }
      receiverRegistered = false;
    }
    super.onPause();
  }

  /** the SH1 rows' value texts + switch states (moved VERBATIM) */
  private void refreshSh1Values() {
    if (binding == null || activity == null) {
      return;
    }
    SharedPreferences prefs = getSharedPrefs();

    // battery
    int critical = prefs.getInt(PREF.SH1_BATTERY_CRITICAL, DEF.SH1_BATTERY_CRITICAL);
    binding.textEnvironmentBatteryValue.setText(
        getString(R.string.sh1_battery_critical_description, critical)
    );
    int mid = prefs.getInt(PREF.SH1_BATTERY_MID, DEF.SH1_BATTERY_MID);
    binding.textEnvironmentMidBatteryValue.setText(
        getString(R.string.sh1_mid_battery_description, mid)
    );
    binding.linearEnvironmentMidBattery.setVisibility(
        prefs.getBoolean(PREF.SH1_BATTERY_HALFRATE, DEF.SH1_BATTERY_HALFRATE)
            ? View.VISIBLE : View.GONE);

    // v7 switch rows
    setSwitchSilently(binding.switchEnvironmentPowerSave,
        prefs.getBoolean(PREF.SH1_POWER_SAVE_COMPLY, DEF.SH1_POWER_SAVE_COMPLY));
    setSwitchSilently(binding.switchEnvironmentStorageGuard,
        prefs.getBoolean(PREF.SH1_STORAGE_GUARD, DEF.SH1_STORAGE_GUARD));
    setSwitchSilently(binding.switchEnvironmentWatchdog,
        prefs.getBoolean(PREF.SH1_WATCHDOG, DEF.SH1_WATCHDOG));
    setSwitchSilently(binding.switchEnvironmentCharging,
        prefs.getBoolean(PREF.SH1_CHARGE_EXEMPT, DEF.SH1_CHARGE_EXEMPT));
    setSwitchSilently(binding.switchEnvironmentAod,
        prefs.getBoolean(PREF.SH1_AOD_FREEZE, DEF.SH1_AOD_FREEZE));
    setSwitchSilently(binding.switchEnvironmentHalfrate,
        prefs.getBoolean(PREF.SH1_BATTERY_HALFRATE, DEF.SH1_BATTERY_HALFRATE));
    setSwitchSilently(binding.switchEnvironmentAdaptive,
        prefs.getBoolean(PREF.SH1_ADAPTIVE_PACING, DEF.SH1_ADAPTIVE_PACING));
    setSwitchSilently(binding.switchEnvironmentTiles,
        prefs.getBoolean(PREF.SH1_TILE_NEXT, DEF.SH1_TILE_NEXT)
            && prefs.getBoolean(PREF.SH1_TILE_FREEZE, DEF.SH1_TILE_FREEZE));

    // camdat config row
    Sh1Camdat camdat = Sh1Camdat.load(activity);
    int scenes = camdat.isValid() ? camdat.modelCount() : 1;
    boolean customConfig = new File(Sh1Storage.sh1Dir(activity), "camdat.json").exists();
    binding.textEnvironmentConfigValue.setText(
        getString(R.string.sh1_config_value,
            customConfig ? getString(R.string.sh1_config_external)
                : getString(R.string.sh1_config_default),
            scenes)
    );
  }

  /** set a switch without firing the change listener (refresh path) */
  private void setSwitchSilently(
      com.google.android.material.materialswitch.MaterialSwitch sw, boolean value) {
    sw.setOnCheckedChangeListener(null);
    sw.setChecked(value);
    sw.setOnCheckedChangeListener(this);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    if (dialogUtilReset != null) {
      dialogUtilReset.saveState(outState);
    }
  }

  @Override
  public void onClick(View v) {
    int id = v.getId();
    if (id == R.id.linear_other_language && getViewUtil().isClickEnabled(id)) {
      performHapticClick();
      ViewUtil.startIcon(binding.imageOtherLanguage);
      navigate(OtherFragmentDirections.actionOtherToLanguagesDialog());
    } else if (id == R.id.linear_other_launcher) {
      binding.switchOtherLauncher.toggle();
    } else if (id == R.id.linear_other_hwrender) {
      // v9.9: the rendering-backend picker (3-way single choice). The
      // software renderer is the DEFAULT; the hardware backends are
      // experimental opt-ins gated by the mandated Material 3 warning.
      performHapticClick();
      showBackendPicker();
    } else if (id == R.id.linear_other_log) {
      performHapticClick();
      navigateToFragment(OtherFragmentDirections.actionOtherToLog());
    } else if (id == R.id.linear_other_reset && getViewUtil().isClickEnabled(id)) {
      performHapticClick();
      dialogUtilReset.show();
    } else if (getViewUtil().isClickDisabled(id)) {
      return;
    } else {
      performHapticClick();
      if (id == R.id.linear_environment_battery) {
        ViewUtil.startIcon(binding.imageEnvironmentBattery);
        showBatteryPicker();
      } else if (id == R.id.linear_environment_mid_battery) {
        ViewUtil.startIcon(binding.imageEnvironmentMidBattery);
        showMidBatteryPicker();
      } else if (id == R.id.linear_environment_power_save) {
        binding.switchEnvironmentPowerSave.toggle();
      } else if (id == R.id.linear_environment_config) {
        ViewUtil.startIcon(binding.imageEnvironmentConfig);
        configPicker.launch(new String[]{"application/json", "application/octet-stream",
            "text/plain", "text/comma-separated-values", "*/*"});
      } else if (id == R.id.linear_environment_byoa) {
        ViewUtil.startIcon(binding.imageEnvironmentByoa);
        byoaFolderPicker.launch(null);
      } else if (id == R.id.linear_environment_storage_guard) {
        binding.switchEnvironmentStorageGuard.toggle();
      } else if (id == R.id.linear_environment_watchdog) {
        binding.switchEnvironmentWatchdog.toggle();
      } else if (id == R.id.linear_environment_backup) {
        ViewUtil.startIcon(binding.imageEnvironmentBackup);
        showBackupDialog();
      } else if (id == R.id.linear_environment_charging) {
        binding.switchEnvironmentCharging.toggle();
      } else if (id == R.id.linear_environment_aod) {
        binding.switchEnvironmentAod.toggle();
      } else if (id == R.id.linear_environment_halfrate) {
        binding.switchEnvironmentHalfrate.toggle();
      } else if (id == R.id.linear_environment_adaptive) {
        binding.switchEnvironmentAdaptive.toggle();
      } else if (id == R.id.linear_environment_tiles) {
        binding.switchEnvironmentTiles.toggle();
      } else if (id == R.id.linear_environment_diagnostics) {
        ViewUtil.startIcon(binding.imageEnvironmentDiagnostics);
        ViewUtil.showBottomSheet(activity, new Sh1DiagnosticsBottomSheetDialogFragment());
      }
    }
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
    int id = buttonView.getId();
    performHapticClick();
    if (id == R.id.switch_other_launcher) {
      ViewUtil.startIcon(binding.imageOtherLauncher);
      if (isChecked) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          activity.showSnackbar(R.string.other_launcher_unsupported);
          binding.switchOtherLauncher.setOnCheckedChangeListener(null);
          binding.switchOtherLauncher.setChecked(false);
          binding.switchOtherLauncher.setOnCheckedChangeListener(this);
        } else {
          Snackbar snackbar = activity.getSnackbar(R.string.msg_hide, Snackbar.LENGTH_LONG);
          snackbar.setAction(getString(R.string.action_hide), view -> {
            performHapticHeavyClick();
            activity.getPackageManager().setComponentEnabledSetting(
                new ComponentName(activity, LauncherActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );
          });
          snackbar.addCallback(new Callback() {
            @Override
            public void onDismissed(Snackbar transientBottomBar, int event) {
              if (binding == null || activity == null
                  || event == BaseCallback.DISMISS_EVENT_CONSECUTIVE) {
                return;
              }
              try {
                binding.switchOtherLauncher.setOnCheckedChangeListener(null);
                binding.switchOtherLauncher.setChecked(
                    activity.getPackageManager().getComponentEnabledSetting(
                        new ComponentName(activity, LauncherActivity.class)
                    ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                );
                binding.switchOtherLauncher.setOnCheckedChangeListener(OtherFragment.this);
              } catch (NullPointerException e) {
                Log.e(TAG, "onDismissed: error when the snackbar was dismissed", e);
              }
            }
          });
          activity.showSnackbar(snackbar);
        }
      } else {
        activity.getPackageManager().setComponentEnabledSetting(
            new ComponentName(activity, LauncherActivity.class),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        );
      }
    } else {
      SharedPreferences.Editor editor = getSharedPrefs().edit();
      if (id == R.id.switch_environment_power_save) {
        editor.putBoolean(PREF.SH1_POWER_SAVE_COMPLY, isChecked).apply();
        activity.requestSettingsRefresh();
      } else if (id == R.id.switch_environment_storage_guard) {
        editor.putBoolean(PREF.SH1_STORAGE_GUARD, isChecked).apply();
      } else if (id == R.id.switch_environment_watchdog) {
        editor.putBoolean(PREF.SH1_WATCHDOG, isChecked).apply();
      } else if (id == R.id.switch_environment_charging) {
        editor.putBoolean(PREF.SH1_CHARGE_EXEMPT, isChecked).apply();
        activity.requestSettingsRefresh();
      } else if (id == R.id.switch_environment_aod) {
        editor.putBoolean(PREF.SH1_AOD_FREEZE, isChecked).apply();
        activity.requestSettingsRefresh();
      } else if (id == R.id.switch_environment_halfrate) {
        editor.putBoolean(PREF.SH1_BATTERY_HALFRATE, isChecked).apply();
        activity.requestSettingsRefresh();
        refreshSh1Values();
      } else if (id == R.id.switch_environment_adaptive) {
        editor.putBoolean(PREF.SH1_ADAPTIVE_PACING, isChecked).apply();
        activity.requestSettingsRefresh();
      } else if (id == R.id.switch_environment_tiles) {
        editor.putBoolean(PREF.SH1_TILE_NEXT, isChecked).apply();
        editor.putBoolean(PREF.SH1_TILE_FREEZE, isChecked).apply();
      }
    }
  }

  private void setUpThemeSelection() {
    boolean hasDynamic = DynamicColors.isDynamicColorAvailable();
    ViewGroup container = binding.linearOtherThemeContainer;
    for (int i = hasDynamic ? -1 : 0; i < 4; i++) {
      String name;
      int resId;
      switch (i) {
        case -1:
          name = THEME.DYNAMIC;
          resId = -1;
          break;
        case 0:
          name = THEME.RED;
          resId = getContrastThemeResId(
              R.style.Theme_Doodle_Red,
              R.style.ThemeOverlay_Doodle_Red_MediumContrast,
              R.style.ThemeOverlay_Doodle_Red_HighContrast
          );
          break;
        case 2:
          name = THEME.GREEN;
          resId = getContrastThemeResId(
              R.style.Theme_Doodle_Green,
              R.style.ThemeOverlay_Doodle_Green_MediumContrast,
              R.style.ThemeOverlay_Doodle_Green_HighContrast
          );
          break;
        case 3:
          name = THEME.BLUE;
          resId = getContrastThemeResId(
              R.style.Theme_Doodle_Blue,
              R.style.ThemeOverlay_Doodle_Blue_MediumContrast,
              R.style.ThemeOverlay_Doodle_Blue_HighContrast
          );
          break;
        default:
          name = THEME.YELLOW;
          resId = getContrastThemeResId(
              R.style.Theme_Doodle_Yellow,
              R.style.ThemeOverlay_Doodle_Yellow_MediumContrast,
              R.style.ThemeOverlay_Doodle_Yellow_HighContrast
          );
          break;
      }

      SelectionCardView card = new SelectionCardView(activity);
      card.setNestedContext(
          i == -1 && VERSION.SDK_INT >= VERSION_CODES.S
              ? DynamicColors.wrapContextIfAvailable(activity)
              : new ContextThemeWrapper(activity, resId)
      );
      card.setOnClickListener(v -> {
        if (!card.isChecked()) {
          card.startCheckedIcon();
          ViewUtil.startIcon(binding.imageOtherTheme);
          performHapticClick();
          ViewUtil.uncheckAllChildren(container);
          card.setChecked(true);
          getSharedPrefs().edit().putString(PREF.THEME, name).apply();
          activity.restartToApply(
              100, getInstanceState(), false, true
          );
        }
      });

      String selected = getSharedPrefs().getString(PREF.THEME, DEF.THEME);
      boolean isSelected;
      if (selected.isEmpty()) {
        isSelected = hasDynamic ? name.equals(THEME.DYNAMIC) : name.equals(THEME.YELLOW);
      } else {
        isSelected = selected.equals(name);
      }
      card.setChecked(isSelected);
      container.addView(card);

      if (hasDynamic && i == -1) {
        MaterialDivider divider = new MaterialDivider(activity);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
            UiUtil.dpToPx(activity, 1), UiUtil.dpToPx(activity, 40)
        );
        int marginLeft, marginRight;
        if (UiUtil.isLayoutRtl(activity)) {
          marginLeft = UiUtil.dpToPx(activity, 8);
          marginRight = UiUtil.dpToPx(activity, 4);
        } else {
          marginLeft = UiUtil.dpToPx(activity, 4);
          marginRight = UiUtil.dpToPx(activity, 8);
        }
        layoutParams.setMargins(marginLeft, 0, marginRight, 0);
        layoutParams.gravity = Gravity.CENTER_VERTICAL;
        divider.setLayoutParams(layoutParams);
        container.addView(divider);
      }
    }

    Bundle bundleInstanceState = activity.getIntent().getBundleExtra(EXTRA.INSTANCE_STATE);
    if (bundleInstanceState != null) {
      binding.scrollOtherTheme.scrollTo(
          bundleInstanceState.getInt(EXTRA.SCROLL_POSITION, 0),
          0
      );
    }
  }

  private int getContrastThemeResId(
      @StyleRes int resIdStandard,
      @StyleRes int resIdMedium,
      @StyleRes int resIdHigh
  ) {
    switch (getSharedPrefs().getString(PREF.UI_CONTRAST, DEF.UI_CONTRAST)) {
      case CONTRAST.MEDIUM:
        return resIdMedium;
      case CONTRAST.HIGH:
        return resIdHigh;
      default:
        return resIdStandard;
    }
  }

  private Bundle getInstanceState() {
    Bundle bundle = new Bundle();
    if (binding != null) {
      // v48 WAJ-15: no "+ 1" — string+int concat produced the key
      // "scroll_position1", which the restore (~683, EXTRA.SCROLL_POSITION)
      // never read, so the restored scroll X was always 0
      bundle.putInt(EXTRA.SCROLL_POSITION, binding.scrollOtherTheme.getScrollX());
    }
    return bundle;
  }

  /* ---- NumberPicker dialogs (E-4 battery; Phase I: steps up to 99%) ---- */

  /**
   * v9.9: the RENDERING BACKEND picker — Software (Default) / OpenGL ES
   * (Hardware) / Vulkan (Hardware). Material 3 single-choice dialog; a
   * hardware selection first shows the mandated experimental warning
   * (older/budget devices may crash / glitch / drain battery; software
   * recommended for stability) and only applies on the explicit OK.
   * A change persists the pref and restarts the engine through the
   * proven requestSceneSwitch path (the backend is a boot argv value
   * exactly like --res; the engine also self-falls-back to software
   * when the device can't run the chosen backend).
   */
  private void showBackendPicker() {
    SharedPreferences prefs = getSharedPrefs();
    int current = prefs.getInt(PREF.SH1_HW_BACKEND, DEF.SH1_HW_BACKEND);
    String[] items = {
        getString(R.string.sh1_hwbackend_software),
        getString(R.string.sh1_hwbackend_gles),
        getString(R.string.sh1_hwbackend_vulkan)
    };
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_hwbackend_title)
        .setSingleChoiceItems(items, current, (d, which) -> {
          if (which == current) {
            d.dismiss();
            return;
          }
          if (which == 0) {
            // software: the default and the accuracy source of truth —
            // apply directly (no warning needed going BACK to default)
            prefs.edit().putInt(PREF.SH1_HW_BACKEND, 0).apply();
            d.dismiss();
            applyBackendSwitch();
          } else {
            // hardware: the mandated experimental warning FIRST
            d.dismiss();
            confirmHardwareBackend(which);
          }
        })
        .setNegativeButton(R.string.action_cancel, null)
        .show();
  }

  /** the mandated Material 3 hardware-rendering warning + the apply */
  private void confirmHardwareBackend(int which) {
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_hwbackend_warning_title)
        .setMessage(R.string.sh1_hwbackend_warning_body)
        .setPositiveButton(R.string.sh1_hwbackend_warning_accept, (d, w) -> {
          getSharedPrefs().edit().putInt(PREF.SH1_HW_BACKEND, which).apply();
          applyBackendSwitch();
        })
        .setNegativeButton(R.string.action_cancel, null)
        .show();
  }

  /** persist applied: restart the engine with the new --hw boot value */
  private void applyBackendSwitch() {
    refreshBackendRow();
    activity.requestSceneSwitch();
  }

  /** the row's description = the active backend (with its short caveat) */
  private void refreshBackendRow() {
    int be = getSharedPrefs().getInt(PREF.SH1_HW_BACKEND, DEF.SH1_HW_BACKEND);
    int res;
    switch (be) {
      case 1:  res = R.string.sh1_hwbackend_gles; break;
      case 2:  res = R.string.sh1_hwbackend_vulkan; break;
      default: res = R.string.sh1_hwbackend_software; break;
    }
    binding.textOtherHwrenderValue.setText(getString(
        R.string.sh1_hwbackend_current, getString(res)));
  }

  private void showBatteryPicker() {
    SharedPreferences prefs = getSharedPrefs();
    int current = prefs.getInt(PREF.SH1_BATTERY_CRITICAL, DEF.SH1_BATTERY_CRITICAL);
    FrameLayout container = wrapPicker(1, 99, current);
    NumberPicker picker = (NumberPicker) container.getChildAt(0);
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_battery_critical_title)
        .setView(container)
        .setPositiveButton(R.string.action_ok, (d, w) -> {
          getSharedPrefs().edit()
              .putInt(PREF.SH1_BATTERY_CRITICAL, picker.getValue())
              .apply();
          activity.requestSettingsRefresh();
          refreshSh1Values();
        })
        .setNegativeButton(R.string.action_cancel, null)
        .show();
  }

  /** v7 F12: the mid-battery half-rate threshold (1..99, >= critical) */
  private void showMidBatteryPicker() {
    SharedPreferences prefs = getSharedPrefs();
    int critical = prefs.getInt(PREF.SH1_BATTERY_CRITICAL, DEF.SH1_BATTERY_CRITICAL);
    int current = Math.max(prefs.getInt(PREF.SH1_BATTERY_MID, DEF.SH1_BATTERY_MID), critical);
    FrameLayout container = wrapPicker(Math.min(99, critical), 99, current);
    NumberPicker picker = (NumberPicker) container.getChildAt(0);
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_mid_battery_title)
        .setView(container)
        .setPositiveButton(R.string.action_ok, (d, w) -> {
          getSharedPrefs().edit()
              .putInt(PREF.SH1_BATTERY_MID, picker.getValue())
              .apply();
          activity.requestSettingsRefresh();
          refreshSh1Values();
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

  /* ---- v7 F7/F14: settings + config backup / restore via SAF ---- */

  private void showBackupDialog() {
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_backup_dialog_title)
        .setMessage(R.string.sh1_backup_dialog_msg)
        .setPositiveButton(R.string.sh1_backup_export, (d, w) ->
            backupExportPicker.launch("sh1-backup.json"))
        .setNegativeButton(R.string.sh1_backup_import, (d, w) ->
            backupImportPicker.launch(new String[]{"application/json", "*/*"}))
        .setNeutralButton(R.string.action_cancel, null)
        .show();
  }

  private void onBackupExportPicked(@Nullable Uri uri) {
    if (uri == null || activity == null) {
      return;   // cancelled
    }
    try {
      JSONObject doc = new JSONObject();
      doc.put("format", "sh1-backup");
      doc.put("version", 1);
      JSONObject prefsOut = new JSONObject();
      Map<String, ?> all = getSharedPrefs().getAll();
      for (Map.Entry<String, ?> entry : all.entrySet()) {
        if (entry.getKey().startsWith("sh1_")) {
          Object value = entry.getValue();
          if (value instanceof Integer) {
            prefsOut.put(entry.getKey(), (Integer) value);
          } else if (value instanceof Boolean) {
            prefsOut.put(entry.getKey(), (Boolean) value);
          } else if (value instanceof Long) {
            prefsOut.put(entry.getKey(), (Long) value);
          } else if (value instanceof String) {
            prefsOut.put(entry.getKey(), (String) value);
          } else if (value instanceof Float) {
            prefsOut.put(entry.getKey(), (Float) value);
          }
        }
      }
      doc.put("prefs", prefsOut);
      // the ACTIVE camdat.json (filesDir first, bundled fallback)
      JSONObject camdat;
      File file = camdatFile();
      if (file.exists()) {
        try (InputStream in = new FileInputStream(file)) {
          camdat = new JSONObject(readStream(in));
        }
      } else {
        try (InputStream in = activity.getAssets().open("sh1/camdat.json")) {
          camdat = new JSONObject(readStream(in));
        }
      }
      doc.put("camdat", camdat);
      try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
        if (os != null) {
          os.write(doc.toString(2).getBytes("UTF-8"));
        }
      }
      snackbar(R.string.sh1_backup_done);
    } catch (Exception e) {
      Log.e(TAG, "onBackupExportPicked: failed", e);
      snackbar(R.string.sh1_backup_invalid);
    }
  }

  private void onBackupImportPicked(@Nullable Uri uri) {
    if (uri == null || activity == null || binding == null) {
      return;   // cancelled
    }
    try {
      String content = readUriText(uri);
      JSONObject doc = new JSONObject(content);
      if (!"sh1-backup".equals(doc.optString("format"))) {
        snackbar(R.string.sh1_backup_invalid);
        return;
      }
      JSONObject prefsIn = doc.optJSONObject("prefs");
      if (prefsIn == null) {
        snackbar(R.string.sh1_backup_invalid);
        return;
      }
      SharedPreferences.Editor editor = getSharedPrefs().edit();
      Iterator<String> keys = prefsIn.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!key.startsWith("sh1_")) {
          continue;
        }
        Object value = prefsIn.get(key);
        if (value instanceof Integer) {
          editor.putInt(key, (Integer) value);
        } else if (value instanceof Boolean) {
          editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Long) {
          editor.putLong(key, (Long) value);
        } else if (value instanceof String) {
          editor.putString(key, (String) value);
        } else if (value instanceof Double) {
          editor.putFloat(key, (float) (double) value);
        }
      }
      editor.apply();
      JSONObject camdat = doc.optJSONObject("camdat");
      if (camdat != null) {
        writeCamdat(camdat.toString(2));
        afterConfigApplied();
      } else {
        activity.requestSceneSwitch();
      }
      snackbar(R.string.sh1_backup_restored);
      refreshSh1Values();
    } catch (Exception e) {
      Log.e(TAG, "onBackupImportPicked: failed", e);
      snackbar(R.string.sh1_backup_invalid);
    }
  }

  /* ---- camdat.json import (Phase E-1: SAF + Overwrite/Merge dialog;
          v7 F13: validation report after applying) ---- */

  private void onConfigPicked(@Nullable Uri uri) {
    if (uri == null || binding == null) {
      return;
    }
    try {
      // persistent grant: the config can be re-read after a reboot
      takePersistable(uri);
      String content = readUriText(uri);
      JSONObject incoming = new JSONObject(content);
      if (!isUsableCamdat(incoming)) {
        snackbar(R.string.sh1_config_invalid);
        return;
      }
      String report = buildValidationReport(incoming);
      new MaterialAlertDialogBuilder(activity)
          .setTitle(R.string.sh1_config_merge_title)
          .setMessage(R.string.sh1_config_merge_msg)
          .setPositiveButton(R.string.sh1_config_action_overwrite, (d, w) -> {
            writeCamdat(content);
            afterConfigApplied();
            showValidationReport(report);
          })
          .setNegativeButton(R.string.sh1_config_action_merge, (d, w) -> {
            mergeCamdat(incoming);
            afterConfigApplied();
            showValidationReport(report);
          })
          .setNeutralButton(R.string.action_cancel, null)
          .show();
    } catch (Exception e) {
      Log.e(TAG, "onConfigPicked: failed", e);
      snackbar(R.string.sh1_config_invalid);
    }
  }

  /**
   * v7 F13: which models/tracks the import carries, validated the way the
   * ENGINE validates them (models without file or tracks are unusable;
   * unknown track type strings are silently DROPPED by camdat_load —
   * camdat_parse_type returns -1 and camdat_load_track fails).
   */
  private static String buildValidationReport(JSONObject root) {
    StringBuilder accepted = new StringBuilder();
    StringBuilder rejected = new StringBuilder();
    int nAccepted = 0;
    int nRejected = 0;
    JSONArray models = root.optJSONArray("models");
    if (models != null) {
      for (int i = 0; i < models.length(); i++) {
        JSONObject m = models.optJSONObject(i);
        String name = m != null ? m.optString("file", "") : "";
        String why = null;
        if (m == null || name.isEmpty()) {
          why = "missing model file name";
        } else {
          JSONArray tracks = m.optJSONArray("tracks");
          if (tracks == null || tracks.length() == 0) {
            why = "no tracks";
          } else {
            int dropped = 0;
            for (int t = 0; t < tracks.length(); t++) {
              JSONObject tr = tracks.optJSONObject(t);
              String type = tr != null ? tr.optString("type", "") : "";
              boolean known = false;
              for (String k : TRACK_TYPES) {
                if (k.equals(type)) {
                  known = true;
                  break;
                }
              }
              if (!known) {
                dropped++;
              }
            }
            if (dropped > 0 && dropped == tracks.length()) {
              why = "all tracks have unknown types";
            } else if (dropped > 0) {
              why = dropped + " unknown track type(s) dropped";
            }
          }
        }
        if (why == null) {
          nAccepted++;
          if (accepted.length() > 0) {
            accepted.append("\n");
          }
          accepted.append("• ").append(name);
        } else {
          nRejected++;
          if (rejected.length() > 0) {
            rejected.append("\n");
          }
          rejected.append("• ").append(name.isEmpty() ? "(unnamed)" : name)
              .append(" — ").append(why);
        }
      }
    }
    StringBuilder report = new StringBuilder();
    report.append(nAccepted).append(" accepted, ").append(nRejected).append(" rejected.");
    if (accepted.length() > 0) {
      report.append("\n\nAccepted:\n").append(accepted);
    }
    if (rejected.length() > 0) {
      report.append("\n\nRejected:\n").append(rejected);
    }
    return report.toString();
  }

  private void showValidationReport(String report) {
    if (binding == null || activity == null) {
      return;
    }
    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_config_report_title)
        .setMessage(report)
        .setPositiveButton(R.string.action_ok, null)
        .show();
  }

  /** >= 1 model with >= 1 track (the engine's own "usable" rule) */
  private static boolean isUsableCamdat(JSONObject root) {
    JSONArray models = root.optJSONArray("models");
    if (models == null) {
      return false;
    }
    for (int i = 0; i < models.length(); i++) {
      JSONObject m = models.optJSONObject(i);
      if (m == null) {
        continue;
      }
      String file = Sh1Camdat.sanitizeFileName(m.optString("file", ""));
      if (file == null) {
        continue;
      }
      JSONArray tracks = m.optJSONArray("tracks");
      if (tracks != null && tracks.length() > 0) {
        return true;
      }
    }
    return false;
  }

  private void writeCamdat(String content) {
    try {
      File out = camdatFile();
      out.getParentFile().mkdirs();
      try (OutputStream os = new FileOutputStream(out)) {
        os.write(content.getBytes("UTF-8"));
      }
    } catch (Exception e) {
      Log.e(TAG, "writeCamdat: failed", e);
    }
  }

  /**
   * MERGE rule: keep the current models[], then for each incoming model
   * replace the entry with the same "file" name or append it. Top-level
   * metadata (format/version/units/doc/...) comes from the incoming
   * document (it is the newer config).
   */
  private void mergeCamdat(JSONObject incoming) {
    try {
      JSONObject current;
      File file = camdatFile();
      if (file.exists()) {
        try (InputStream in = new FileInputStream(file)) {
          current = new JSONObject(readStream(in));
        }
      } else {
        try (InputStream in = activity.getAssets().open("sh1/camdat.json")) {
          current = new JSONObject(readStream(in));
        }
      }
      JSONArray currentModels = current.optJSONArray("models");
      if (currentModels == null) {
        currentModels = new JSONArray();
      }
      JSONArray incomingModels = incoming.optJSONArray("models");
      for (int i = 0; i < incomingModels.length(); i++) {
        JSONObject m = incomingModels.optJSONObject(i);
        if (m == null) {
          continue;
        }
        String name = m.optString("file", "");
        int existing = -1;
        for (int c = 0; c < currentModels.length(); c++) {
          JSONObject cm = currentModels.optJSONObject(c);
          if (cm != null && cm.optString("file", "").equals(name)) {
            existing = c;
            break;
          }
        }
        if (existing >= 0) {
          currentModels.put(existing, m);
        } else {
          currentModels.put(m);
        }
      }
      incoming.put("models", currentModels);
      writeCamdat(incoming.toString(2));
    } catch (Exception e) {
      Log.e(TAG, "mergeCamdat: failed", e);
    }
  }

  /** clamp the selection to the new config and switch the engine */
  private void afterConfigApplied() {
    Sh1Camdat camdat = Sh1Camdat.load(activity);
    SharedPreferences prefs = getSharedPrefs();
    int scene = Sh1WallpaperService.getActiveSceneIndex(prefs);
    if (scene >= camdat.modelCount()) {
      scene = 0;
    }
    Sh1Camdat.Model model = camdat.getModel(scene);
    int track = model != null ? model.firstEnabledTrack() : -1;
    prefs.edit()
        .putInt(PREF.SH1_SCENE, scene)
        .putInt(PREF.SH1_TRACK, track)
        .apply();
    activity.requestSceneSwitch();
    refreshSh1Values();
  }

  private File camdatFile() {
    return new File(Sh1Storage.sh1Dir(activity), "camdat.json");
  }

  /* ---- BYOA asset import (Phase D: SAF tree picker, persistent grant;
          v7 F5: storage guard) ---- */

  private void onByoaFolderPicked(@Nullable Uri treeUri) {
    if (treeUri == null || binding == null) {
      return;
    }
    try {
      takePersistable(treeUri);
      boolean guard = getSharedPrefs().getBoolean(
          PREF.SH1_STORAGE_GUARD, DEF.SH1_STORAGE_GUARD);
      long free = freeBytes();
      int copied = 0;
      Uri configUri = null;
      Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
          treeUri, DocumentsContract.getTreeDocumentId(treeUri));
      try (Cursor cursor = activity.getContentResolver()
          .query(children, new String[]{
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
        if (cursor != null) {
          while (cursor.moveToNext()) {
            String docId = cursor.getString(0);
            String name = Sh1Camdat.sanitizeFileName(cursor.getString(1));
            long size = cursor.getLong(2);
            if (name == null) {
              continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            if (lower.endsWith(".glb") || lower.endsWith(".gltf")) {
              if (size > IMPORT_CAP_BYTES) {
                Log.w(TAG, "onByoaFolderPicked: " + name
                    + " exceeds the 512 MB import cap — skipped");
                continue;
              }
              if (guard && (size > LARGE_ASSET_BYTES || free < LOW_DISK_BYTES)) {
                // warn once for the first large/risky file, then continue
                // with the remaining (smaller) ones
                snackbar(getString(R.string.sh1_import_confirm_msg,
                    (int) (size / (1024 * 1024))));
                continue;
              }
              if (copyDocument(docUri, name)) {
                copied++;
                free -= size;
              }
            } else if (lower.equals("camdat.json")) {
              configUri = docUri;
            }
          }
        }
      }
      if (configUri != null) {
        // a camdat.json in the imported folder: the standard Overwrite /
        // Merge dialog flow (same as a directly picked config)
        try {
          String content = readUriText(configUri);
          JSONObject incoming = new JSONObject(content);
          if (isUsableCamdat(incoming)) {
            String report = buildValidationReport(incoming);
            new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sh1_config_merge_title)
                .setMessage(R.string.sh1_config_merge_msg)
                .setPositiveButton(R.string.sh1_config_action_overwrite, (d, w) -> {
                  writeCamdat(content);
                  afterConfigApplied();
                  showValidationReport(report);
                })
                .setNegativeButton(R.string.sh1_config_action_merge, (d, w) -> {
                  mergeCamdat(incoming);
                  afterConfigApplied();
                  showValidationReport(report);
                })
                .setNeutralButton(R.string.action_cancel, null)
                .show();
          }
        } catch (Exception e) {
          Log.e(TAG, "onByoaFolderPicked: camdat failed", e);
        }
      }
      if (copied > 0) {
        snackbar(getString(R.string.sh1_byoa_imported, copied));
      } else if (configUri == null) {
        snackbar(R.string.sh1_byoa_none);
      }
      refreshSh1Values();
    } catch (Exception e) {
      Log.e(TAG, "onByoaFolderPicked: failed", e);
      snackbar(R.string.sh1_byoa_none);
    }
  }

  private boolean copyDocument(Uri uri, String name) {
    try {
      File dir = Sh1Storage.sh1Dir(activity);
      if (!dir.exists() && !dir.mkdirs()) {
        return false;
      }
      File out = new File(dir, name);
      try (InputStream in = activity.getContentResolver().openInputStream(uri);
          OutputStream os = new FileOutputStream(out)) {
        if (in == null) {
          return false;
        }
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
          os.write(buf, 0, n);
        }
      }
      return true;
    } catch (Exception e) {
      Log.e(TAG, "copyDocument: " + name, e);
      return false;
    }
  }

  private long freeBytes() {
    try {
      android.os.StatFs stat = new android.os.StatFs(Sh1Storage.engineDataPath(activity));
      return stat.getAvailableBytes();
    } catch (Exception e) {
      return Long.MAX_VALUE;
    }
  }

  /* ---- helpers ---- */

  private void takePersistable(Uri uri) {
    try {
      activity.getContentResolver().takePersistableUriPermission(
          uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (SecurityException e) {
      // not fatal: the import works this session; re-selection re-grants
      Log.w(TAG, "takePersistable: no persistable grant", e);
    }
  }

  private String readUriText(Uri uri) throws Exception {
    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
      if (in == null) {
        throw new java.io.IOException("null stream");
      }
      return readStream(in);
    }
  }

  private static String readStream(InputStream in) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[64 * 1024];
    int n;
    while ((n = in.read(buf)) > 0) {
      bos.write(buf, 0, n);
    }
    return bos.toString("UTF-8");
  }

  private void snackbar(int resId) {
    if (binding != null) {
      Snackbar.make(binding.constraintOther, resId, Snackbar.LENGTH_LONG).show();
    }
  }

  private void snackbar(String text) {
    if (binding != null) {
      Snackbar.make(binding.constraintOther, text, Snackbar.LENGTH_LONG).show();
    }
  }
}
