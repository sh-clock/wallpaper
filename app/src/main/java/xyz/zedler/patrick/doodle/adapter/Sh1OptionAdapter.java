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

package xyz.zedler.patrick.doodle.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.databinding.RowSh1OptionBinding;
import xyz.zedler.patrick.doodle.model.Sh1Option;
import xyz.zedler.patrick.doodle.util.ResUtil;
import xyz.zedler.patrick.doodle.util.ViewUtil;

/**
 * Single-choice option list for the SH1 environment bottom sheets — the
 * selection row pattern of the language chooser (check icon, selected
 * background, tinted texts) applied to plain key/value options.
 *
 * v7: optional per-row scene thumbnails (F9, the cached engine frame)
 * and a favorite star toggle (F3). Both views stay hidden for the plain
 * env/weather/track lists — no behavior change for existing sheets.
 */
public class Sh1OptionAdapter extends RecyclerView.Adapter<Sh1OptionAdapter.ViewHolder> {

  private static final String TAG = Sh1OptionAdapter.class.getSimpleName();

  private final List<Sh1Option> options;
  private int selectedValue;   /* v9 F22: mutable for the live search filter */
  private final Sh1OptionAdapterListener listener;

  public Sh1OptionAdapter(
      List<Sh1Option> options, int selectedValue, Sh1OptionAdapterListener listener
  ) {
    this.options = options;
    this.selectedValue = selectedValue;
    this.listener = listener;
  }

  /** v9 F22: swap the option list in place (the city search filter) */
  public void updateOptions(List<Sh1Option> newOptions, int newSelected) {
    options.clear();
    options.addAll(newOptions);
    selectedValue = newSelected;
    notifyDataSetChanged();
  }

  public static class ViewHolder extends RecyclerView.ViewHolder {

    private final RowSh1OptionBinding binding;

    public ViewHolder(RowSh1OptionBinding binding) {
      super(binding.getRoot());
      this.binding = binding;
    }
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(
        RowSh1OptionBinding.inflate(
            LayoutInflater.from(parent.getContext()), parent, false
        )
    );
  }

  @Override
  public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
    Sh1Option option = options.get(position);

    holder.binding.textSh1OptionTitle.setText(option.getTitle());
    holder.binding.textSh1OptionSummary.setText(option.getSummary());

    boolean isSelected = option.getValue() == selectedValue;
    Context context = holder.binding.getRoot().getContext();
    int colorSelected = ResUtil.getColor(context, R.attr.colorOnSecondaryContainer);
    holder.binding.imageSh1OptionSelected.setColorFilter(colorSelected);
    holder.binding.imageSh1OptionSelected.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
    if (isSelected) {
      holder.binding.linearSh1OptionContainer.setBackground(
          ViewUtil.getBgListItemSelected(context)
      );
    } else {
      holder.binding.linearSh1OptionContainer.setBackground(
          ViewUtil.getRippleBgListItemSurface(context)
      );
    }
    holder.binding.textSh1OptionTitle.setTextColor(
        isSelected ? colorSelected : ResUtil.getColor(context, R.attr.colorOnSurface)
    );
    holder.binding.textSh1OptionSummary.setTextColor(
        isSelected ? colorSelected : ResUtil.getColor(context, R.attr.colorOnSurfaceVariant)
    );

    /* v7 F9 thumbnail of the scene's cached engine frame;
     * v9 F28: APK-asset env thumbnails (the real engine --shot renders) */
    Bitmap thumb = loadThumbnail(option.getThumbnail());
    if (thumb == null) {
      thumb = loadAssetThumbnail(context, option.getAssetThumb());
    }
    if (thumb != null) {
      holder.binding.imageSh1OptionThumbnail.setImageBitmap(thumb);
      holder.binding.imageSh1OptionThumbnail.setVisibility(View.VISIBLE);
    } else {
      holder.binding.imageSh1OptionThumbnail.setImageDrawable(null);
      holder.binding.imageSh1OptionThumbnail.setVisibility(View.GONE);
    }

    /* v7 F3: favorite star (scene list only) */
    if (option.isShowStar()) {
      holder.binding.imageSh1OptionStar.setVisibility(View.VISIBLE);
      holder.binding.imageSh1OptionStar.setColorFilter(
          option.isFavorite()
              ? ResUtil.getColor(context, R.attr.colorPrimary)
              : ResUtil.getColor(context, R.attr.colorOnSurfaceVariant)
      );
      holder.binding.imageSh1OptionStar.setOnClickListener(
          v -> listener.onItemStarClicked(option)
      );
    } else {
      holder.binding.imageSh1OptionStar.setVisibility(View.GONE);
      holder.binding.imageSh1OptionStar.setOnClickListener(null);
    }

    holder.binding.linearSh1OptionContainer.setOnClickListener(
        view -> listener.onItemRowClicked(option)
    );
    holder.binding.linearSh1OptionContainer.setOnLongClickListener(
        view -> listener.onItemRowLongClicked(option)
    );
  }

  private static Bitmap loadThumbnail(File file) {
    if (file == null || !file.exists()) {
      return null;
    }
    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inSampleSize = 1;   // 320x224 / 640x360 PNGs are small already
    try {
      return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    } catch (Exception e) {
      return null;
    }
  }

  /** v9 F28: decode an APK-asset PNG (the engine's own --shot renders) */
  private static Bitmap loadAssetThumbnail(Context context, String assetPath) {
    if (assetPath == null || assetPath.isEmpty()) {
      return null;
    }
    try (java.io.InputStream in = context.getAssets().open(assetPath)) {
      return BitmapFactory.decodeStream(in);
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public int getItemCount() {
    return options.size();
  }

  public interface Sh1OptionAdapterListener {

    void onItemRowClicked(Sh1Option option);

    /** v7 F3: star tap — default no-op keeps existing listeners source-compatible */
    default void onItemStarClicked(Sh1Option option) {
    }

    /** v7 F11: long-press — default no-op keeps existing listeners source-compatible */
    default boolean onItemRowLongClicked(Sh1Option option) {
      return false;
    }
  }
}
