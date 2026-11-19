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
 * v7 F15: Quick Settings tile "Next scene" — advances the wallpaper to
 * the next playable camdat scene through the SAME app-layer entry point
 * the settings UI uses (Sh1WallpaperService.requestNextScene: engine
 * stop/restart through its own K_QUIT path). No engine changes, no new
 * dangerous permissions (only the standard BIND_QUICK_SETTINGS_TILE
 * binding permission).
 *
 * The tile respects the feature toggle: when "Quick settings tiles" is
 * disabled in the app settings, the tile goes to STATE_UNAVAILABLE and
 * the click is safely inert.
 */
public class Sh1NextSceneTileService extends TileService {

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
    Sh1WallpaperService.requestNextScene(this);
    updateTile();
  }

  private boolean enabled(SharedPreferences prefs) {
    return prefs.getBoolean(PREF.SH1_TILE_NEXT, DEF.SH1_TILE_NEXT);
  }

  private void updateTile() {
    Tile tile = getQsTile();
    if (tile == null) {
      return;
    }
    SharedPreferences prefs = Sh1WallpaperService.getPrefs(this);
    tile.setState(enabled(prefs) ? Tile.STATE_ACTIVE : Tile.STATE_UNAVAILABLE);
    tile.setSubtitle(getString(
        enabled(prefs) ? xyz.zedler.patrick.doodle.R.string.sh1_tile_next_subtitle
            : xyz.zedler.patrick.doodle.R.string.sh1_tile_disabled));
    tile.updateTile();
  }
}
