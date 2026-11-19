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

package xyz.zedler.patrick.doodle.service;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;

/**
 * v7 F15: Quick Settings tile "Freeze wallpaper" — a MANUAL contributor
 * to the existing render-once/freeze state (Sh1WallpaperService
 * .setManualFreeze OR-combines with battery/power-save/AOD). The frozen
 * output is the software-rasterized engine frame at 0 FPS — never a
 * static image. Clicking again resumes the normal lockstep.
 *
 * Respects the feature toggle: disabled -> STATE_UNAVAILABLE + inert.
 */
public class Sh1FreezeTileService extends TileService {

  @Override
  public void onStartListening() {
    super.onStartListening();
    updateTile();
  }

  @Override
  public void onClick() {
    super.onClick();
    SharedPreferences prefs = Sh1WallpaperService.getPrefs(this);
    if (!enabled(prefs)) {
      updateTile();   // inert while the feature toggle is off
      return;
    }
    boolean now = !Sh1WallpaperService.isManuallyFrozen(prefs);
    Sh1WallpaperService.setManualFreeze(this, now);
    updateTile();
  }

  private boolean enabled(SharedPreferences prefs) {
    return prefs.getBoolean(PREF.SH1_TILE_FREEZE, DEF.SH1_TILE_FREEZE);
  }

  private void updateTile() {
    Tile tile = getQsTile();
    if (tile == null) {
      return;
    }
    SharedPreferences prefs = Sh1WallpaperService.getPrefs(this);
    boolean on = enabled(prefs);
    boolean frozen = Sh1WallpaperService.isManuallyFrozen(prefs);
    tile.setState(!on ? Tile.STATE_UNAVAILABLE
        : (frozen ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE));
    tile.setSubtitle(getString(
        !on ? xyz.zedler.patrick.doodle.R.string.sh1_tile_disabled
            : (frozen ? xyz.zedler.patrick.doodle.R.string.sh1_tile_frozen
            : xyz.zedler.patrick.doodle.R.string.sh1_tile_live)));
    tile.updateTile();
  }
}
