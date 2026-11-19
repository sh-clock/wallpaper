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
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import java.util.Locale;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.databinding.FragmentBottomsheetSh1DiagnosticsBinding;
import xyz.zedler.patrick.doodle.service.Sh1Native;
import xyz.zedler.patrick.doodle.service.Sh1WallpaperService;
import xyz.zedler.patrick.doodle.util.Sh1Camdat;
import xyz.zedler.patrick.doodle.util.UiUtil;

/**
 * v7 F8: in-app engine diagnostics — a read-only live view of the
 * engine's presented FPS, frame time and freeze state plus the current
 * scene and pacing settings. Purely informational: the single native
 * call is the read-only stats() getter; nothing here can mutate engine
 * state. Updated twice a second while the sheet is visible.
 */
public class Sh1DiagnosticsBottomSheetDialogFragment extends BaseBottomSheetDialogFragment {

  private static final String TAG = "Sh1DiagnosticsBottomSheet";

  private FragmentBottomsheetSh1DiagnosticsBinding binding;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick = this::updateReadout;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentBottomsheetSh1DiagnosticsBinding.inflate(inflater, container, false);

    binding.textSh1DiagnosticsTitle.setText(R.string.sh1_diagnostics_title);
    binding.textSh1DiagnosticsDescription.setText(R.string.sh1_diagnostics_description);

    updateReadout();
    return binding.getRoot();
  }

  @Override
  public void onResume() {
    super.onResume();
    handler.postDelayed(tick, 500L);
  }

  @Override
  public void onPause() {
    handler.removeCallbacks(tick);
    super.onPause();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    binding = null;
  }

  private void updateReadout() {
    if (binding == null) {
      return;
    }
    StringBuilder sb = new StringBuilder();
    boolean running = Sh1Native.isRunning();
    sb.append(getString(R.string.sh1_diag_engine, running
        ? getString(R.string.sh1_diag_state_running)
        : getString(R.string.sh1_diag_state_stopped))).append('\n');

    if (running) {
      double[] st = Sh1Native.stats();
      double fps = st.length > 0 ? st[0] : 0;
      double frameMs = st.length > 1 ? st[1] : 0;
      boolean frozen = st.length > 2 && st[2] >= 0.5;
      int w = st.length > 3 ? (int) st[3] : 0;
      int h = st.length > 4 ? (int) st[4] : 0;
      sb.append(getString(R.string.sh1_diag_fps, String.format(Locale.US, "%.1f", fps)))
          .append('\n');
      sb.append(getString(R.string.sh1_diag_frame, String.format(Locale.US, "%.1f", frameMs)))
          .append('\n');
      sb.append(getString(R.string.sh1_diag_freeze, frozen
          ? getString(R.string.sh1_diag_state_frozen)
          : getString(R.string.sh1_diag_state_live))).append('\n');
      sb.append(getString(R.string.sh1_diag_raster, w, h)).append('\n');
    }

    SharedPreferences prefs = getSharedPrefs();
    int res = prefs.getInt(PREF.SH1_RESOLUTION, DEF.SH1_RESOLUTION);
    sb.append(getString(R.string.sh1_diag_res_setting,
        res == xyz.zedler.patrick.doodle.Constants.SH1.RES_PRESENT ? "640 × 360" : "320 × 224"))
        .append('\n');
    int cap = prefs.getInt(PREF.SH1_FPS_CAP, DEF.SH1_FPS_CAP);
    sb.append(getString(R.string.sh1_diag_cap, cap)).append('\n');
    int critical = prefs.getInt(PREF.SH1_BATTERY_CRITICAL, DEF.SH1_BATTERY_CRITICAL);
    sb.append(getString(R.string.sh1_diag_battery, critical)).append('\n');

    Sh1Camdat camdat = Sh1Camdat.load(requireContext());
    if (camdat.isValid()) {
      int scene = Sh1WallpaperService.getActiveSceneIndex(prefs);
      Sh1Camdat.Model model = camdat.getModel(
          Math.min(scene, camdat.modelCount() - 1));
      if (model != null) {
        sb.append(getString(R.string.sh1_diag_scene, model.file));
      }
    }
    binding.textSh1DiagnosticsReadout.setText(sb.toString());
    handler.postDelayed(tick, 500L);
  }

  @Override
  public void applyBottomInset(int bottom) {
    if (binding != null) {
      binding.linearSh1DiagnosticsContainer.setPadding(
          0, 0, 0, UiUtil.dpToPx(requireContext(), 8) + bottom
      );
    }
  }

  @NonNull
  @Override
  public String toString() {
    return TAG;
  }
}
