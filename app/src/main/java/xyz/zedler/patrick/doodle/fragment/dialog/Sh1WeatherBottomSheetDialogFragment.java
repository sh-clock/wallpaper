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
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.Constants.SH1;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.activity.MainActivity;
import xyz.zedler.patrick.doodle.adapter.Sh1OptionAdapter;
import xyz.zedler.patrick.doodle.databinding.FragmentBottomsheetSh1OptionsBinding;
import xyz.zedler.patrick.doodle.model.Sh1Option;
import xyz.zedler.patrick.doodle.service.Sh1WallpaperService;
import xyz.zedler.patrick.doodle.util.UiUtil;

/**
 * Chooser for the runtime weather states (the engine's
 * sharedFunc_800D0B18 argument range 0..8) plus the scene's own camdat
 * recipe as the default entry. Layout and behavior mirror the language
 * chooser.
 */
public class Sh1WeatherBottomSheetDialogFragment extends BaseBottomSheetDialogFragment
    implements Sh1OptionAdapter.Sh1OptionAdapterListener {

  private static final String TAG = "Sh1WeatherBottomSheet";

  private FragmentBottomsheetSh1OptionsBinding binding;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentBottomsheetSh1OptionsBinding.inflate(inflater, container, false);

    MainActivity activity = (MainActivity) requireActivity();

    binding.textSh1OptionsTitle.setText(R.string.sh1_weather_select);
    binding.textSh1OptionsDescription.setText(R.string.sh1_weather_select_description);

    SharedPreferences prefs = getSharedPrefs();
    int selected = Sh1WallpaperService.getUserWeather(prefs);
    int scene = Sh1WallpaperService.getSceneWeather(activity);
    List<Sh1Option> options = new ArrayList<>(SH1.WEATHER_COUNT + 1);
    options.add(
        new Sh1Option(
            SH1.WEATHER_SCENE,
            getString(R.string.sh1_weather_scene_default),
            getString(R.string.sh1_weather_scene_value, SH1.getWeatherName(scene))
        )
    );
    for (int i = 0; i < SH1.WEATHER_COUNT; i++) {
      options.add(new Sh1Option(i, i + " — " + SH1.getWeatherName(i), ""));
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
    int current = Sh1WallpaperService.getUserWeather(getSharedPrefs());
    if (option.getValue() != current) {
      performHapticClick();
      getSharedPrefs().edit().putInt(PREF.SH1_WEATHER, option.getValue()).apply();
      MainActivity activity = (MainActivity) requireActivity();
      activity.requestSettingsRefresh();
      dismiss();
    }
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
