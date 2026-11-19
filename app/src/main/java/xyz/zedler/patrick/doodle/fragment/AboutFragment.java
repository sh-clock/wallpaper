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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.activity.MainActivity;
import xyz.zedler.patrick.doodle.behavior.ScrollBehavior;
import xyz.zedler.patrick.doodle.behavior.SystemBarBehavior;
import xyz.zedler.patrick.doodle.databinding.FragmentAboutBinding;
import xyz.zedler.patrick.doodle.util.ViewUtil;

public class AboutFragment extends BaseFragment implements OnClickListener {

  private FragmentAboutBinding binding;
  private MainActivity activity;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState
  ) {
    binding = FragmentAboutBinding.inflate(inflater, container, false);
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
    systemBarBehavior.setAppBar(binding.appBarAbout);
    systemBarBehavior.setScroll(binding.scrollAbout, binding.constraintAbout);
    systemBarBehavior.setAdditionalBottomInset(activity.getFabTopEdgeDistance());
    systemBarBehavior.setUp();

    new ScrollBehavior().setUpScroll(binding.appBarAbout, binding.scrollAbout, true);

    binding.toolbarAbout.setNavigationOnClickListener(getNavigationOnClickListener());
    binding.toolbarAbout.setOnMenuItemClickListener(getOnMenuItemClickListener());

    ViewUtil.setOnClickListeners(
        this,
        binding.linearAboutChangelog,
        binding.linearAboutDeveloper,
        binding.linearAboutGithub,
        binding.linearAboutPrivacy,
        binding.linearAboutLicenseGpl,
        binding.linearAboutCreditDecomp,
        binding.linearAboutCreditSwanstation,
        binding.linearAboutCreditDoodle,
        binding.linearAboutLicenseJost,
        binding.linearAboutLicenseMaterialComponents,
        binding.linearAboutLicenseMaterialIcons,
        binding.linearAboutLicenseGeonames
    );
  }

  @Override
  public void onClick(View v) {
    int id = v.getId();
    if (getViewUtil().isClickDisabled(id)) {
      return;
    } else {
      performHapticClick();
    }

    if (id == R.id.linear_about_changelog) {
      performHapticClick();
      activity.showChangelogBottomSheet();
      ViewUtil.startIcon(binding.imageAboutChangelog);
    } else if (id == R.id.linear_about_developer) {
      startActivity(
          new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.app_website)))
      );
    } else if (id == R.id.linear_about_github) {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.app_github))));
    } else if (id == R.id.linear_about_privacy) {
      // v10.5: the privacy row opens the LOCAL privacy statement (this
      // fork is fully offline) instead of linking the base app's
      // external policy page.
      activity.showTextBottomSheet(
          R.raw.privacy, R.string.about_privacy
      );
    } else if (id == R.id.linear_about_license_gpl) {
      // v9.2 Feature 33: this app's own license — the full GPLv3 text
      ViewUtil.startIcon(binding.imageAboutLicenseGpl);
      activity.showTextBottomSheet(
          R.raw.license_gpl, R.string.license_gpl_v3, R.string.app_github
      );
    } else if (id == R.id.linear_about_credit_decomp) {
      // v9.2 Feature 33: the Silent Hill decompilation — the game-logic
      // source of truth this wallpaper's fog/engine math is ported from
      ViewUtil.startIcon(binding.imageAboutCreditDecomp);
      activity.showTextBottomSheet(
          R.raw.license_gpl, R.string.credit_decomp, R.string.credit_decomp_link
      );
    } else if (id == R.id.linear_about_credit_swanstation) {
      // v9.2 Feature 33: SwanStation — the software-rasterizer reference
      ViewUtil.startIcon(binding.imageAboutCreditSwanstation);
      activity.showTextBottomSheet(
          R.raw.license_gpl, R.string.credit_swanstation, R.string.credit_swanstation_link
      );
    } else if (id == R.id.linear_about_credit_doodle) {
      // v9.2 Feature 33: Doodle Android — the GPL-3.0 base app this fork
      // is built on (by Patrick Zedler)
      ViewUtil.startIcon(binding.imageAboutCreditDoodle);
      activity.showTextBottomSheet(
          R.raw.license_gpl, R.string.credit_doodle, R.string.app_github
      );
    } else if (id == R.id.linear_about_license_jost) {
      ViewUtil.startIcon(binding.imageAboutLicenseJost);
      activity.showTextBottomSheet(
          R.raw.license_ofl, R.string.license_jost, R.string.license_jost_link
      );
    } else if (id == R.id.linear_about_license_material_components) {
      ViewUtil.startIcon(binding.imageAboutLicenseMaterialComponents);
      activity.showTextBottomSheet(
          R.raw.license_apache,
          R.string.license_material_components,
          R.string.license_material_components_link
      );
    } else if (id == R.id.linear_about_license_material_icons) {
      ViewUtil.startIcon(binding.imageAboutLicenseMaterialIcons);
      activity.showTextBottomSheet(
          R.raw.license_apache,
          R.string.license_material_icons,
          R.string.license_material_icons_link
      );
    } else if (id == R.id.linear_about_license_geonames) {
      ViewUtil.startIcon(binding.imageAboutLicenseGeonames);
      activity.showTextBottomSheet(
          R.raw.license_geonames,
          R.string.license_geonames,
          R.string.license_geonames_link
      );
    }
  }
}