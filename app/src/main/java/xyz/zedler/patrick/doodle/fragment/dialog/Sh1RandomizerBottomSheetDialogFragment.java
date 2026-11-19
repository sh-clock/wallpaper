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
import xyz.zedler.patrick.doodle.util.UiUtil;

/**
 * Scene randomizer trigger chooser (Phase E-3): off / daily / hourly /
 * on phone lock / custom interval. Selection persists and the wallpaper
 * service's receivers act on it (ACTION_SCREEN_OFF, TIME_TICK, interval
 * handler).
 */
public class Sh1RandomizerBottomSheetDialogFragment extends BaseBottomSheetDialogFragment
    implements Sh1OptionAdapter.Sh1OptionAdapterListener {

  private static final String TAG = "Sh1RandomizerBottomSheet";

  private FragmentBottomsheetSh1OptionsBinding binding;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentBottomsheetSh1OptionsBinding.inflate(inflater, container, false);

    MainActivity activity = (MainActivity) requireActivity();

    binding.textSh1OptionsTitle.setText(R.string.sh1_randomizer_select);
    binding.textSh1OptionsDescription.setText(R.string.sh1_randomizer_select_description);

    SharedPreferences prefs = getSharedPrefs();
    int selected = prefs.getInt(PREF.SH1_RANDOMIZER, DEF.SH1_RANDOMIZER);
    if (selected < 0 || selected >= SH1.RANDOM_COUNT) {
      selected = DEF.SH1_RANDOMIZER;
    }
    List<Sh1Option> options = new ArrayList<>(SH1.RANDOM_COUNT);
    for (int i = 0; i < SH1.RANDOM_COUNT; i++) {
      options.add(new Sh1Option(i, SH1.RANDOM_MODES[i], ""));
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
    performHapticClick();
    getSharedPrefs().edit().putInt(PREF.SH1_RANDOMIZER, option.getValue()).apply();
    // reset the trigger anchors when the mode changes
    getSharedPrefs().edit()
        .putLong("sh1_random_last_day", -1L)
        .putLong("sh1_random_last_hour", -1L)
        .putLong("sh1_random_next", 0L)
        .apply();
    dismiss();
    MainActivity activity = (MainActivity) requireActivity();
    activity.requestSettingsRefresh();
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
