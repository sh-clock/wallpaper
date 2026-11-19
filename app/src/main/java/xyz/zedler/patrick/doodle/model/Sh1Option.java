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

package xyz.zedler.patrick.doodle.model;

import java.io.File;

/** One selectable SH1 environment option (env mode, weather, scene...). */
public class Sh1Option {

  private final int value;
  private final String title;
  private final String summary;

  /* v7 scene-list extras (F3 favorites, F9 thumbnails): absent for the
   * plain env/weather/track/randomizer lists — the adapter hides the
   * views when the fields are unset. */

  private File thumbnail;
  private String assetThumb;
  private boolean favorite;
  private boolean showStar;
  private boolean hasOverride;

  public Sh1Option(int value, String title, String summary) {
    this.value = value;
    this.title = title;
    this.summary = summary;
  }

  public int getValue() {
    return value;
  }

  public String getTitle() {
    return title;
  }

  public String getSummary() {
    return summary;
  }

  public File getThumbnail() {
    return thumbnail;
  }

  public void setThumbnail(File thumbnail) {
    this.thumbnail = thumbnail;
  }

  /** v9 F28: an APK-asset path (sh1/thumbs/…) — the real engine render */
  public String getAssetThumb() {
    return assetThumb;
  }

  public void setAssetThumb(String assetThumb) {
    this.assetThumb = assetThumb;
  }

  public boolean isFavorite() {
    return favorite;
  }

  public void setFavorite(boolean favorite) {
    this.favorite = favorite;
  }

  public boolean isShowStar() {
    return showStar;
  }

  public void setShowStar(boolean showStar) {
    this.showStar = showStar;
  }

  public boolean isHasOverride() {
    return hasOverride;
  }

  public void setHasOverride(boolean hasOverride) {
    this.hasOverride = hasOverride;
  }
}
