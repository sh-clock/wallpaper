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

package xyz.zedler.patrick.doodle.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.databinding.RowEditorTrackBinding;
import xyz.zedler.patrick.doodle.util.CamtrackDoc;
import xyz.zedler.patrick.doodle.util.CamtrackMath;
import xyz.zedler.patrick.doodle.util.ResUtil;
import xyz.zedler.patrick.doodle.util.ViewUtil;

/**
 * v9.7 (Camtrack native editor): the track list of the active model —
 * the mobile replica of the v13 editor's track list (type · duration ·
 * speed badges, the per-track weather chip, enable switch, per-row play)
 * with MD3 drag-reordering (the ItemTouchHelper drag handle).
 */
public class EditorTrackAdapter
    extends RecyclerView.Adapter<EditorTrackAdapter.ViewHolder> {

  public interface TrackAdapterListener {
    void onTrackClick(CamtrackDoc.Track track, int position);
    void onTrackPlay(CamtrackDoc.Track track, int position);
    void onTrackEnabled(CamtrackDoc.Track track, boolean enabled);
    void onTrackMoved(int from, int to);
    /** starts an ItemTouchHelper drag for the row's drag handle */
    void onDragHandleRequested(RecyclerView.ViewHolder viewHolder);
  }

  private List<CamtrackDoc.Track> tracks;
  private CamtrackDoc.Model model;
  private final TrackAdapterListener listener;
  private String selectedTrackId;

  public EditorTrackAdapter(
      CamtrackDoc.Model model, TrackAdapterListener listener) {
    this.model = model;
    this.tracks = model.tracks;
    this.listener = listener;
  }

  /** the ACTIVE MODEL changed (the models page): rebind to its tracks */
  @SuppressLint("NotifyDataSetChanged")
  public void swapModel(CamtrackDoc.Model newModel) {
    this.model = newModel;
    // v48 WAJ-2: rebind the LIST too — `tracks` is captured once at
    // construction, so without this the TRACKS page kept listing (and
    // drag-reordering) the PREVIOUS model's tracks
    this.tracks = newModel.tracks;
    notifyDataSetChanged();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void setSelected(String trackId) {
    this.selectedTrackId = trackId;
    notifyDataSetChanged();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void reload() {
    notifyDataSetChanged();
  }

  /** the list moved (drag) — notify in place */
  public void moved(int from, int to) {
    notifyItemMoved(from, to);
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(
        RowEditorTrackBinding.inflate(
            LayoutInflater.from(parent.getContext()), parent, false)
    );
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    Context context = holder.binding.getRoot().getContext();
    CamtrackDoc.Track track = tracks.get(position);

    boolean selected = track.id.equals(selectedTrackId);
    holder.binding.textEditorTrackName.setText(
        track.name == null || track.name.isEmpty() ? "(unnamed)" : track.name);
    holder.binding.textEditorTrackSub.setText(subLine(track));
    holder.binding.textEditorTrackWeather.setText(weatherChip(track));

    int colorSelected = ResUtil.getColor(context, R.attr.colorOnSecondaryContainer);
    int colorNormal = ResUtil.getColor(context, R.attr.colorOnSurface);
    int colorSub = ResUtil.getColor(context, R.attr.colorOnSurfaceVariant);
    holder.binding.textEditorTrackName.setTextColor(
        selected ? colorSelected : colorNormal);
    holder.binding.textEditorTrackSub.setTextColor(
        selected ? colorSelected : colorSub);
    if (selected) {
      holder.binding.linearEditorTrackContainer.setBackground(
          ViewUtil.getBgListItemSelected(context));
    } else {
      holder.binding.linearEditorTrackContainer.setBackground(
          ViewUtil.getRippleBgListItemSurface(context));
    }
    // disabled tracks dim
    holder.binding.linearEditorTrackContainer.setAlpha(
        track.enabled ? 1f : 0.45f);

    holder.binding.switchEditorTrackEnabled.setChecked(track.enabled);
    holder.binding.switchEditorTrackEnabled.setOnCheckedChangeListener(
        (v, checked) -> {
          if (listener != null) {
            listener.onTrackEnabled(track, checked);
          }
        });
    holder.binding.imageEditorTrackPlay.setOnClickListener(
        v -> {
          if (listener != null) {
            listener.onTrackPlay(track, holder.getAdapterPosition());
          }
        });
    holder.binding.imageEditorTrackDrag.setOnClickListener(v -> {
      if (listener != null) {
        listener.onDragHandleRequested(holder);
      }
    });
    holder.binding.linearEditorTrackContainer.setOnClickListener(
        v -> {
          if (listener != null) {
            listener.onTrackClick(track, holder.getAdapterPosition());
          }
        });
  }

  @Override
  public int getItemCount() {
    return tracks.size();
  }

  /** the v13 renderTrackList sub line: TYPE · dur · speed · geometry */
  private String subLine(CamtrackDoc.Track tr) {
    StringBuilder sb = new StringBuilder();
    sb.append(tr.type.toUpperCase());
    double t = CamtrackMath.cycleDuration(tr);
    if (Double.isFinite(t) && t > 0) {
      sb.append(" · ").append(durationText(t));
    } else if (Double.isInfinite(t)) {
      sb.append(" · ∞");
    }
    switch (tr.type) {
      case CamtrackDoc.TYPE_LINE:
      case CamtrackDoc.TYPE_CURVE:
      case CamtrackDoc.TYPE_POLYLINE:
        sb.append(" · ").append(fmt1(tr.speedMS)).append(" m/s");
        if (tr.points != null) {
          sb.append(" · ").append(tr.points.size()).append(" pts");
        }
        break;
      case CamtrackDoc.TYPE_ZIGZAG:
        sb.append(" · ").append(fmt1(tr.speedMS)).append(" m/s · ")
            .append(tr.lanes).append(" lanes · ")
            .append(fmt2(tr.sizeX)).append("×").append(fmt2(tr.sizeZ)).append(" m");
        break;
      case CamtrackDoc.TYPE_CIRCLE:
        sb.append(" · ").append(fmt1(tr.speedDegS)).append("°/s");
        if (tr.arcBounds && tr.arcMaxDeg > tr.arcMinDeg) {
          sb.append(" · arc ").append(fmt0(tr.arcMinDeg))
              .append("…").append(fmt0(tr.arcMaxDeg)).append("°");
        } else {
          sb.append(" · r").append(fmt2(tr.radiusX));
          if (tr.radiusZ != tr.radiusX) {
            sb.append("×").append(fmt2(tr.radiusZ));
          }
        }
        break;
      case CamtrackDoc.TYPE_SPIRAL:
        sb.append(" · ").append(fmt1(tr.speedDegS)).append("°/s · ")
            .append(fmt2(Math.abs(tr.turns))).append(" turns · r ")
            .append(fmt2(tr.radiusStart)).append("→").append(fmt2(tr.radiusEnd));
        break;
      case CamtrackDoc.TYPE_FIGURE8:
        sb.append(" · ").append(fmt1(tr.speedDegS)).append("°/s · ")
            .append(fmt2(tr.semiX)).append("×").append(fmt2(tr.semiZ)).append(" m");
        break;
      default:
        sb.append(" · yaw ").append(fmt1(tr.yawSpeedDegS)).append("°/s");
        break;
    }
    return sb.toString();
  }

  /** the weather chip: the track's own weather or the model default */
  private String weatherChip(CamtrackDoc.Track tr) {
    int own = tr.weatherTarget != null ? tr.weatherTarget : modelWeather();
    return weatherName(own) + (tr.weatherTarget != null ? "" : " ⌄");
  }

  private int modelWeather() {
    if (model != null && model.env != null && model.env.weatherTarget != null) {
      return model.env.weatherTarget;
    }
    return 2;   // the SHUI panel's absent-default (map0 snow)
  }

  private static String weatherName(int w) {
    if (w == 0) {
      return "none";
    }
    if (w < 5) {
      return "snow w" + (w - 1);
    }
    return "rain w" + (w - 5);
  }

  private static String durationText(double t) {
    if (t >= 100) {
      return String.format(java.util.Locale.ROOT, "%.0f s", t);
    }
    return String.format(java.util.Locale.ROOT, "%.1f s", t);
  }

  private static String fmt0(double v) {
    return String.format(java.util.Locale.ROOT, "%.0f", v);
  }

  private static String fmt1(double v) {
    return String.format(java.util.Locale.ROOT, "%.1f", v);
  }

  private static String fmt2(double v) {
    return String.format(java.util.Locale.ROOT, "%.2f", v);
  }

  static class ViewHolder extends RecyclerView.ViewHolder {

    final RowEditorTrackBinding binding;

    ViewHolder(RowEditorTrackBinding binding) {
      super(binding.getRoot());
      this.binding = binding;
    }
  }
}
