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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.activity.MainActivity;
import xyz.zedler.patrick.doodle.adapter.Sh1OptionAdapter;
import xyz.zedler.patrick.doodle.databinding.FragmentBottomsheetSh1CitiesBinding;
import xyz.zedler.patrick.doodle.model.Sh1Option;
import xyz.zedler.patrick.doodle.util.Sh1Cities;
import xyz.zedler.patrick.doodle.util.UiUtil;

/**
 * v9 F22 + v9.1 Fix 2: the built-in world city picker for Sun-Time Auto
 * Mode — a searchable single-choice list over the Sh1Cities database
 * (every national capital + every city of one million or more, GeoNames
 * cities15000, CC BY 4.0). Selecting a city persists
 * "name|country|lat|lon" — the NOAA pipeline reads the coordinates.
 * v9.1 Fix 2 adds the MANUAL latitude/longitude fallback: a custom
 * coordinates dialog persists "Custom|Manual|lat|lon" through the SAME
 * pref key, so the exact coordinates feed directly into the NOAA
 * algorithm and OVERRIDE the city database selection (picking a city
 * again simply replaces them). A "no city" row clears back to the
 * device-clock schedule fallback.
 */
public class Sh1CitiesPickerBottomSheetDialogFragment extends BaseBottomSheetDialogFragment
    implements Sh1OptionAdapter.Sh1OptionAdapterListener {

  private static final String TAG = "Sh1CitiesPickerBottomSheet";

  /** the v9.1 Fix 2 manual location marker (name|country fields) */
  private static final String CUSTOM_NAME = "Custom";
  private static final String CUSTOM_COUNTRY = "Manual";

  private static final int SEARCH_LIMIT = 200;

  private FragmentBottomsheetSh1CitiesBinding binding;
  private Sh1OptionAdapter adapter;
  private List<Sh1Cities.City> cities = new ArrayList<>();

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentBottomsheetSh1CitiesBinding.inflate(inflater, container, false);

    MainActivity activity = (MainActivity) requireActivity();

    binding.textSh1CitiesTitle.setText(R.string.sh1_city_select_title);
    binding.textSh1CitiesDescription.setText(R.string.sh1_city_select_description);

    cities = Sh1Cities.all();
    adapter = new Sh1OptionAdapter(optionsFor(cities), selectedIndex(), this);
    binding.recyclerSh1Cities.setLayoutManager(
        new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false)
    );
    binding.recyclerSh1Cities.setAdapter(adapter);

    binding.editSh1CitiesSearch.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int a, int b, int c) {
      }

      @Override
      public void onTextChanged(CharSequence s, int a, int b, int c) {
      }

      @Override
      public void afterTextChanged(Editable s) {
        if (adapter == null) {
          return;
        }
        List<Sh1Cities.City> found = Sh1Cities.search(s.toString());
        if (found.size() > SEARCH_LIMIT) {
          found = new ArrayList<>(found.subList(0, SEARCH_LIMIT));
        }
        cities = found;
        adapter.updateOptions(optionsFor(cities), selectedIndex());
      }
    });

    // v9.1 Fix 2: the manual coordinates row + the clear row
    binding.linearSh1CitiesCustom.setOnClickListener(v -> showCustomCoordsDialog());
    binding.linearSh1CitiesNone.setOnClickListener(v -> {
      performHapticClick();
      getSharedPrefs().edit().remove(PREF.SH1_CITY).apply();
      ((MainActivity) requireActivity()).requestSettingsRefresh();
      dismiss();
    });
    refreshCustomRow();

    return binding.getRoot();
  }

  /** v9.1 Fix 2: the custom row reflects the persisted manual location */
  private void refreshCustomRow() {
    if (binding == null) {
      return;
    }
    String raw = getSharedPrefs().getString(PREF.SH1_CITY, DEF.SH1_CITY);
    String[] f = raw != null ? raw.split("\\|") : new String[0];
    if (f.length == 4 && CUSTOM_NAME.equals(f[0])) {
      binding.textSh1CitiesCustomSummary.setText(
          getString(R.string.sh1_city_custom_active_summary, f[2], f[3]));
    } else {
      binding.textSh1CitiesCustomSummary.setText(R.string.sh1_city_custom_summary);
    }
  }

  /**
   * v9.1 Fix 2: the manual latitude/longitude dialog. Valid coordinates
   * persist "Custom|Manual|lat|lon" through the SAME pref key the city
   * list writes — the entire NOAA pipeline (isSunTimeDay, civil twilight
   * display, the auto-mode boundary) reads the manual coordinates
   * directly, overriding any city database selection.
   */
  private void showCustomCoordsDialog() {
    MainActivity activity = (MainActivity) requireActivity();
    int pad = (int) (20 * getResources().getDisplayMetrics().density);
    LinearLayout container = new LinearLayout(activity);
    container.setOrientation(LinearLayout.VERTICAL);
    container.setPadding(pad, pad / 2, pad, 0);

    TextInputLayout latLayout = new TextInputLayout(activity, null,
        com.google.android.material.R.attr.textInputOutlinedStyle);
    TextInputEditText latEdit = new TextInputEditText(activity);
    latEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    latEdit.setMaxLines(1);
    latEdit.setHint(R.string.sh1_city_custom_lat_hint);
    latLayout.addView(latEdit);

    TextInputLayout lonLayout = new TextInputLayout(activity, null,
        com.google.android.material.R.attr.textInputOutlinedStyle);
    TextInputEditText lonEdit = new TextInputEditText(activity);
    lonEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    lonEdit.setMaxLines(1);
    lonEdit.setHint(R.string.sh1_city_custom_lon_hint);
    lonLayout.addView(lonEdit);

    container.addView(latLayout);
    container.addView(lonLayout);

    // prefill: the current manual coordinates, else the selected city's
    // (tweaking from a nearby city is the common case), else empty
    String raw = getSharedPrefs().getString(PREF.SH1_CITY, DEF.SH1_CITY);
    String[] f = raw != null ? raw.split("\\|") : new String[0];
    if (f.length == 4) {
      if (CUSTOM_NAME.equals(f[0])) {
        latEdit.setText(f[2]);
        lonEdit.setText(f[3]);
      } else {
        for (Sh1Cities.City c : Sh1Cities.all()) {
          if (c.name.equals(f[0]) && c.country.equals(f[1])) {
            latEdit.setText(String.format(Locale.US, "%.4f", c.lat));
            lonEdit.setText(String.format(Locale.US, "%.4f", c.lon));
            break;
          }
        }
      }
    }

    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sh1_city_custom_title)
        .setMessage(R.string.sh1_city_custom_description)
        .setView(container)
        .setNegativeButton(R.string.action_cancel, null)
        .setPositiveButton(R.string.action_ok, null);
    androidx.appcompat.app.AlertDialog dialog = builder.create();
    dialog.setOnShowListener(d -> dialog.getButton(
        androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      String latStr = latEdit.getText() != null
          ? latEdit.getText().toString().trim() : "";
      String lonStr = lonEdit.getText() != null
          ? lonEdit.getText().toString().trim() : "";
      Double lat = null;
      Double lon = null;
      try {
        lat = Double.parseDouble(latStr);
      } catch (NumberFormatException e) {
        lat = null;
      }
      try {
        lon = Double.parseDouble(lonStr);
      } catch (NumberFormatException e) {
        lon = null;
      }
      boolean valid = true;
      if (lat == null || Double.isNaN(lat) || lat < -90.0 || lat > 90.0) {
        latLayout.setError(getString(R.string.sh1_city_custom_invalid_lat));
        valid = false;
      } else {
        latLayout.setError(null);
      }
      if (lon == null || Double.isNaN(lon) || lon < -180.0 || lon > 180.0) {
        lonLayout.setError(getString(R.string.sh1_city_custom_invalid_lon));
        valid = false;
      } else {
        lonLayout.setError(null);
      }
      if (!valid) {
        return;   // keep the dialog open with the field errors visible
      }
      // full double precision: the NOAA math receives the exact values
      String latVal = String.valueOf(lat.doubleValue());
      String lonVal = String.valueOf(lon.doubleValue());
      getSharedPrefs().edit()
          .putString(PREF.SH1_CITY,
              CUSTOM_NAME + "|" + CUSTOM_COUNTRY + "|" + latVal + "|" + lonVal)
          .apply();
      performHapticClick();
      MainActivity main = (MainActivity) requireActivity();
      main.requestSettingsRefresh();
      dialog.dismiss();
      dismiss();
    }));
    dialog.show();
  }

  private List<Sh1Option> optionsFor(List<Sh1Cities.City> list) {
    List<Sh1Option> options = new ArrayList<>(list.size());
    for (Sh1Cities.City c : list) {
      options.add(new Sh1Option(cities.indexOf(c), c.name, c.country));
    }
    return options;
  }

  private int selectedIndex() {
    SharedPreferences prefs = getSharedPrefs();
    String raw = prefs.getString(PREF.SH1_CITY, DEF.SH1_CITY);
    if (raw == null || raw.isEmpty()) {
      return -1;
    }
    String[] f = raw.split("\\|");
    if (f.length != 4) {
      return -1;
    }
    for (int i = 0; i < cities.size(); i++) {
      Sh1Cities.City c = cities.get(i);
      if (c.name.equals(f[0]) && c.country.equals(f[1])) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    binding = null;
  }

  @Override
  public void onItemRowClicked(@Nullable Sh1Option option) {
    if (option == null || option.getValue() < 0
        || option.getValue() >= cities.size()) {
      return;
    }
    Sh1Cities.City city = cities.get(option.getValue());
    performHapticClick();
    getSharedPrefs().edit()
        .putString(PREF.SH1_CITY,
            city.name + "|" + city.country + "|" + city.lat + "|" + city.lon)
        .apply();
    MainActivity activity = (MainActivity) requireActivity();
    activity.requestSettingsRefresh();
    dismiss();
  }

  @Override
  public void applyBottomInset(int bottom) {
    if (binding != null) {
      binding.recyclerSh1Cities.setPadding(
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
