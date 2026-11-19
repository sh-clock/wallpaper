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

package xyz.zedler.patrick.doodle.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import androidx.annotation.Nullable;
import java.util.List;
import xyz.zedler.patrick.doodle.util.CamtrackDoc;
import xyz.zedler.patrick.doodle.util.CamtrackMath;
import xyz.zedler.patrick.doodle.util.ResUtil;
import xyz.zedler.patrick.doodle.R;

/**
 * v9.7 (Camtrack native editor): the native 2D TOP-DOWN ORTHOGRAPHIC path
 * editor canvas — the mobile-native replacement for the HTML editor's 3D
 * viewport path editing. Draws every track of the active model in the XZ
 * plane (Y = height rides in the property sheet), the model bounds, a
 * meter grid, the anchors of the SELECTED track, its look target and the
 * live camera-pose marker, all in the app's Material 3 colors.
 *
 * <p>Gestures: one-finger drag pans (or drags a grabbed ANCHOR: line
 * start/end, curve/polyline waypoints, the centers of circle/spiral/
 * zigzag/figure8, the fixed position, the lookAt target); pinch zooms;
 * a tap selects the nearest track path. The geometry math is the exact
 * camtrack reference port ({@link CamtrackMath}).</p>
 */
public class CamtrackPathView extends View {

  /** an editable anchor point of the selected track */
  public interface AnchorListener {
    /** an anchor moved to a new XZ position (meters, world space) */
    void onAnchorMoved(CamtrackDoc.Track track, String kind, int index,
        double x, double z);

    /** the user tapped — select the nearest track (id or null) */
    void onTrackPick(@Nullable String trackId);
  }

  private static final double MIN_SCALE = 0.5;    // px per meter
  private static final double MAX_SCALE = 400.0;

  private final Paint paintPath = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintPathSel = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintPathOff = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintAnchor = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintAnchorGrab = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintLook = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintCam = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintCamBeam = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintBounds = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint paintTextBg = new Paint(Paint.ANTI_ALIAS_FLAG);

  private CamtrackDoc doc;
  private CamtrackDoc.Model model;
  private String selectedTrackId;
  private AnchorListener listener;

  /** world center of the view (meters) */
  private double viewX;
  private double viewZ = 100;      // THR's neighborhood
  /** pixels per meter */
  private double scale = 6.0;

  private CamtrackMath.Pose poseMarker;   // live camera pose (display-only)

  /** the grabbed anchor, null when none */
  private static final class Grab {
    CamtrackDoc.Track track;
    String kind;      // "start"|"end"|"center"|"position"|"lookAt"|"point"
    int index;        // for kind == "point"
    double dx;        // world-space grab offset (meters)
    double dz;
  }

  private Grab grab;
  private final GestureDetector tapDetector;
  private final ScaleGestureDetector scaleDetector;
  private boolean twoFingers = false;

  public CamtrackPathView(Context context) {
    this(context, null);
  }

  public CamtrackPathView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    float d = getResources().getDisplayMetrics().density;

    paintPath.setStyle(Paint.Style.STROKE);
    paintPath.setStrokeWidth(2 * d);
    paintPath.setColor(0xFF8A9099);

    paintPathSel.setStyle(Paint.Style.STROKE);
    paintPathSel.setStrokeWidth(3 * d);
    paintPathSel.setColor(ResUtil.getColor(context, R.attr.colorPrimary));

    paintPathOff.setStyle(Paint.Style.STROKE);
    paintPathOff.setStrokeWidth(1.5f * d);
    paintPathOff.setColor(0x598A9099);
    paintPathOff.setPathEffect(new DashPathEffect(new float[]{6 * d, 6 * d}, 0));

    paintAnchor.setStyle(Paint.Style.FILL);
    paintAnchor.setColor(ResUtil.getColor(context, R.attr.colorPrimary));
    paintAnchor.setStrokeWidth(2 * d);

    paintAnchorGrab.setStyle(Paint.Style.FILL);
    paintAnchorGrab.setColor(ResUtil.getColor(context, R.attr.colorSecondary));

    paintLook.setStyle(Paint.Style.FILL);
    paintLook.setColor(0xFFFFB454);
    paintLook.setStrokeWidth(2 * d);

    paintCam.setStyle(Paint.Style.FILL);
    paintCam.setColor(0xFF53B9FF);

    paintCamBeam.setStyle(Paint.Style.STROKE);
    paintCamBeam.setStrokeWidth(1.5f * d);
    paintCamBeam.setColor(0xAAFFB454);

    paintGrid.setStyle(Paint.Style.STROKE);
    paintGrid.setStrokeWidth(1);
    paintGrid.setColor(0x14333843);

    paintBounds.setStyle(Paint.Style.STROKE);
    paintBounds.setStrokeWidth(1.5f * d);
    paintBounds.setColor(ResUtil.getColor(context, R.attr.colorOnSurfaceVariant));
    paintBounds.setPathEffect(
        new DashPathEffect(new float[]{8 * d, 6 * d}, 0));

    paintText.setColor(ResUtil.getColor(context, R.attr.colorOnSurface));
    paintText.setTextSize(11 * d);

    paintTextBg.setStyle(Paint.Style.FILL);
    paintTextBg.setColor(0xB317191F);

    tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onSingleTapConfirmed(MotionEvent e) {
        if (listener == null) {
          return true;
        }
        String pick = pickTrack(e.getX(), e.getY());
        listener.onTrackPick(pick);
        return true;
      }

      @Override
      public boolean onScroll(MotionEvent e1, MotionEvent e2,
          float dx, float dy) {
        if (twoFingers) {
          return true;    // the scale detector owns multi-touch
        }
        if (grab != null) {
          moveGrab(e2.getX(), e2.getY());
        } else {
          viewX -= dx / scale;
          viewZ -= dy / scale;
        }
        invalidate();
        return true;
      }
    });
    scaleDetector = new ScaleGestureDetector(context,
        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
          @Override
          public boolean onScale(ScaleGestureDetector detector) {
            double factor = detector.getScaleFactor();
            double ns = scale * factor;
            // keep the world point under the focal anchor stable
            double wx = screenToWorldX(detector.getFocusX());
            double wz = screenToWorldZ(detector.getFocusY());
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, ns));
            viewX = wx - (detector.getFocusX() - getWidth() / 2.0) / scale;
            viewZ = wz - (detector.getFocusY() - getHeight() / 2.0) / scale;
            invalidate();
            return true;
          }
        });
  }

  /* ---- data binding -------------------------------------------------- */

  public void setDocument(CamtrackDoc doc, CamtrackDoc.Model activeModel,
      String selectedTrackId) {
    this.doc = doc;
    this.model = activeModel;
    this.selectedTrackId = selectedTrackId;
    if (grab != null && grab.track != null
        && !grab.track.id.equals(selectedTrackId)) {
      grab = null;
    }
    invalidate();
  }

  public void setPoseMarker(CamtrackMath.Pose pose) {
    this.poseMarker = pose;
    invalidate();
  }

  public void setAnchorListener(AnchorListener listener) {
    this.listener = listener;
  }

  /** frame the model bounds (or the track path) on first show */
  public void frameModel() {
    if (model != null && model.boundsMin != null && model.boundsMax != null) {
      viewX = (model.boundsMin[0] + model.boundsMax[0]) / 2.0;
      viewZ = (model.boundsMin[2] + model.boundsMax[2]) / 2.0;
      double spanX = Math.max(1.0, model.boundsMax[0] - model.boundsMin[0]);
      double spanZ = Math.max(1.0, model.boundsMax[2] - model.boundsMin[2]);
      double sx = getWidth() > 0 ? getWidth() * 0.8 / spanX : 6;
      double sz = getHeight() > 0 ? getHeight() * 0.8 / spanZ : 6;
      scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(sx, sz)));
    } else if (model != null && !model.tracks.isEmpty()) {
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;
      for (CamtrackDoc.Track tr : model.tracks) {
        for (double[] p : CamtrackMath.pathSamples(tr)) {
          minX = Math.min(minX, p[0]);
          maxX = Math.max(maxX, p[0]);
          minZ = Math.min(minZ, p[2]);
          maxZ = Math.max(maxZ, p[2]);
        }
      }
      viewX = (minX + maxX) / 2.0;
      viewZ = (minZ + maxZ) / 2.0;
      double spanX = Math.max(1.0, maxX - minX);
      double spanZ = Math.max(1.0, maxZ - minZ);
      double sx = getWidth() > 0 ? getWidth() * 0.8 / spanX : 6;
      double sz = getHeight() > 0 ? getHeight() * 0.8 / spanZ : 6;
      scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(sx, sz)));
    }
    invalidate();
  }

  /* ---- coordinate mapping -------------------------------------------- */

  private double worldToScreenX(double wx) {
    return getWidth() / 2.0 + (wx - viewX) * scale;
  }

  private double worldToScreenZ(double wz) {
    return getHeight() / 2.0 + (wz - viewZ) * scale;
  }

  private double screenToWorldX(double sx) {
    return viewX + (sx - getWidth() / 2.0) / scale;
  }

  private double screenToWorldZ(double sz) {
    return viewZ + (sz - getHeight() / 2.0) / scale;
  }

  /* ---- hit testing ---------------------------------------------------- */

  private static final double HIT_RADIUS_DP = 22;

  private double hitRadiusPx() {
    return HIT_RADIUS_DP * getResources().getDisplayMetrics().density;
  }

  private static final class Hit {
    CamtrackDoc.Track track;
    String kind;
    int index;
    double dx;
    double dz;
  }

  private Hit hitTest(double sx, double sy) {
    if (model == null) {
      return null;
    }
    CamtrackDoc.Track sel = selectedTrack();
    if (sel == null) {
      return null;
    }
    final double r = hitRadiusPx();
    double bestD = r * r;
    Hit best = null;
    // the selected track's anchors: nearest within the radius wins
    switch (sel.type) {
      case CamtrackDoc.TYPE_LINE: {
        best = tryPoint(sel, "start", sel.start, sx, sy, best, bestD);
        bestD = best != null && "start".equals(best.kind)
            ? dist2To(sel.start, sx, sy) : bestD;
        Hit end = tryPoint(sel, "end", sel.end, sx, sy, best, bestD);
        if (end != null) {
          best = end;
        }
        break;
      }
      case CamtrackDoc.TYPE_CIRCLE:
      case CamtrackDoc.TYPE_SPIRAL:
      case CamtrackDoc.TYPE_ZIGZAG:
      case CamtrackDoc.TYPE_FIGURE8:
        best = tryPoint(sel, "center", sel.center, sx, sy, best, bestD);
        break;
      case CamtrackDoc.TYPE_CURVE:
      case CamtrackDoc.TYPE_POLYLINE: {
        for (int i = 0; i < sel.points.size(); i++) {
          double d2 = dist2To(sel.points.get(i), sx, sy);
          if (d2 < bestD) {
            bestD = d2;
            Hit h = new Hit();
            h.track = sel;
            h.kind = "point";
            h.index = i;
            h.dx = sel.points.get(i)[0] - screenToWorldX(sx);
            h.dz = sel.points.get(i)[2] - screenToWorldZ(sy);
            best = h;
          }
        }
        break;
      }
      default:
        best = tryPoint(sel, "position", sel.position, sx, sy, best, bestD);
        break;
    }
    if (CamtrackDoc.LOOK_POINT.equals(sel.lookMode) && sel.lookAt != null) {
      Hit look = tryPoint(sel, "lookAt", sel.lookAt, sx, sy, best, bestD);
      if (look != null) {
        best = look;
      }
    }
    return best;
  }

  private Hit tryPoint(CamtrackDoc.Track track, String kind, double[] p,
      double sx, double sy, Hit current, double bestD) {
    double d2 = dist2To(p, sx, sy);
    if (d2 >= bestD) {
      return current;
    }
    Hit h = new Hit();
    h.track = track;
    h.kind = kind;
    h.index = -1;
    h.dx = p[0] - screenToWorldX(sx);
    h.dz = p[2] - screenToWorldZ(sy);
    return h;
  }

  private double dist2To(double[] world, double sx, double sy) {
    double dx = worldToScreenX(world[0]) - sx;
    double dz = worldToScreenZ(world[2]) - sy;
    return dx * dx + dz * dz;
  }

  private String pickTrack(double sx, double sy) {
    if (model == null) {
      return null;
    }
    String best = null;
    double bestD = hitRadiusPx() * 1.5;
    bestD *= bestD;
    for (CamtrackDoc.Track tr : model.tracks) {
      if (!tr.engineValid()) {
        continue;
      }
      List<double[]> samples = CamtrackMath.pathSamples(tr);
      for (double[] p : samples) {
        double d2 = dist2To(p, sx, sy);
        if (d2 < bestD) {
          bestD = d2;
          best = tr.id;
        }
      }
    }
    return best;
  }

  private CamtrackDoc.Track selectedTrack() {
    if (model == null || selectedTrackId == null) {
      return null;
    }
    for (CamtrackDoc.Track tr : model.tracks) {
      if (selectedTrackId.equals(tr.id)) {
        return tr;
      }
    }
    return null;
  }

  /* ---- touch ---------------------------------------------------------- */

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    scaleDetector.onTouchEvent(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_POINTER_DOWN:
        twoFingers = true;
        grab = null;      // a second finger cancels the anchor grab
        break;
      case MotionEvent.ACTION_POINTER_UP:
        // the event still reports the PRE-lift count: count-1 fingers
        // remain after this pointer lifts
        twoFingers = event.getPointerCount() - 1 >= 2;
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        twoFingers = false;
        if (grab != null && listener != null) {
          performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
        grab = null;
        break;
      default:
        break;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !twoFingers) {
      Hit hit = hitTest(event.getX(), event.getY());
      if (hit != null) {
        grab = new Grab();
        grab.track = hit.track;
        grab.kind = hit.kind;
        grab.index = hit.index;
        grab.dx = hit.dx;
        grab.dz = hit.dz;
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
      }
    }
    tapDetector.onTouchEvent(event);
    return true;
  }

  private void moveGrab(float sx, float sy) {
    if (grab == null || listener == null || grab.track == null) {
      return;
    }
    double wx = screenToWorldX(sx) + grab.dx;
    double wz = screenToWorldZ(sy) + grab.dz;
    listener.onAnchorMoved(grab.track, grab.kind, grab.index, wx, wz);
    invalidate();
  }

  /* ---- drawing -------------------------------------------------------- */

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    drawGrid(canvas);
    if (model == null) {
      return;
    }
    drawBounds(canvas);
    CamtrackDoc.Track sel = selectedTrack();
    for (CamtrackDoc.Track tr : model.tracks) {
      if (!tr.engineValid()) {
        continue;
      }
      boolean isSel = tr == sel;
      drawTrackPath(canvas, tr, isSel);
    }
    if (sel != null) {
      drawAnchors(canvas, sel);
      drawLookTarget(canvas, sel);
    }
    drawPoseMarker(canvas);
  }

  private void drawGrid(Canvas canvas) {
    // pick a step (meters) that stays readable at this scale
    double step = 1;
    while (step * scale < 28) {
      step *= step * scale < 4 ? 10 : 5;
    }
    double x0 = screenToWorldX(0);
    double x1 = screenToWorldX(getWidth());
    double z0 = screenToWorldZ(0);
    double z1 = screenToWorldZ(getHeight());
    Path p = new Path();
    for (double gx = Math.floor(x0 / step) * step; gx <= x1; gx += step) {
      float sx = (float) worldToScreenX(gx);
      p.moveTo(sx, 0);
      p.lineTo(sx, getHeight());
    }
    for (double gz = Math.floor(z0 / step) * step; gz <= z1; gz += step) {
      float sz = (float) worldToScreenZ(gz);
      p.moveTo(0, sz);
      p.lineTo(getWidth(), sz);
    }
    canvas.drawPath(p, paintGrid);
  }

  private void drawBounds(Canvas canvas) {
    if (model.boundsMin == null || model.boundsMax == null) {
      return;
    }
    float l = (float) worldToScreenX(model.boundsMin[0]);
    float r = (float) worldToScreenX(model.boundsMax[0]);
    float t = (float) worldToScreenZ(model.boundsMin[2]);
    float b = (float) worldToScreenZ(model.boundsMax[2]);
    canvas.drawRect(new RectF(
        Math.min(l, r), Math.min(t, b), Math.max(l, r), Math.max(t, b)),
        paintBounds);
  }

  private void drawTrackPath(Canvas canvas, CamtrackDoc.Track tr, boolean sel) {
    List<double[]> samples = CamtrackMath.pathSamples(tr);
    if (samples.size() < 2) {
      return;
    }
    Path p = new Path();
    p.moveTo((float) worldToScreenX(samples.get(0)[0]),
        (float) worldToScreenZ(samples.get(0)[2]));
    for (int i = 1; i < samples.size(); i++) {
      p.lineTo((float) worldToScreenX(samples.get(i)[0]),
          (float) worldToScreenZ(samples.get(i)[2]));
    }
    Paint paint = tr.enabled == false ? paintPathOff : (sel ? paintPathSel : paintPath);
    canvas.drawPath(p, paint);
  }

  private void drawAnchors(Canvas canvas, CamtrackDoc.Track tr) {
    float d = getResources().getDisplayMetrics().density;
    switch (tr.type) {
      case CamtrackDoc.TYPE_LINE:
        anchorDot(canvas, tr.start, 5 * d, false);
        anchorDot(canvas, tr.end, 5 * d, false);
        break;
      case CamtrackDoc.TYPE_CIRCLE:
      case CamtrackDoc.TYPE_SPIRAL:
      case CamtrackDoc.TYPE_ZIGZAG:
      case CamtrackDoc.TYPE_FIGURE8:
        anchorDot(canvas, tr.center, 5 * d, false);
        break;
      case CamtrackDoc.TYPE_CURVE:
      case CamtrackDoc.TYPE_POLYLINE:
        for (double[] pt : tr.points) {
          anchorDot(canvas, pt, 4.5f * d, false);
        }
        break;
      default:
        anchorDot(canvas, tr.position, 5 * d, false);
        break;
    }
    if (grab != null && grab.track == tr) {
      double[] at = null;
      switch (grab.kind) {
        case "start": at = tr.start; break;
        case "end": at = tr.end; break;
        case "center": at = tr.center; break;
        case "position": at = tr.position; break;
        case "lookAt": at = tr.lookAt; break;
        case "point":
          if (grab.index >= 0 && grab.index < tr.points.size()) {
            at = tr.points.get(grab.index);
          }
          break;
        default: break;
      }
      if (at != null) {
        anchorDot(canvas, at, 6 * d, true);
        // the live coordinate readout next to the grabbed anchor
        String label = String.format(java.util.Locale.ROOT,
            "x %.2f  y %.2f  z %.2f", at[0], at[1], at[2]);
        float tx = (float) worldToScreenX(at[0]) + 12 * d;
        float ty = (float) worldToScreenZ(at[2]) - 14 * d;
        RectF bg = new RectF(tx - 4 * d, ty - 12 * d,
            tx + paintText.measureText(label) + 4 * d, ty + 4 * d);
        canvas.drawRoundRect(bg, 4 * d, 4 * d, paintTextBg);
        canvas.drawText(label, tx, ty, paintText);
      }
    }
  }

  private void anchorDot(Canvas canvas, double[] world, float radiusPx,
      boolean grabbed) {
    float sx = (float) worldToScreenX(world[0]);
    float sy = (float) worldToScreenZ(world[2]);
    canvas.drawCircle(sx, sy, radiusPx, grabbed ? paintAnchorGrab : paintAnchor);
  }

  private void drawLookTarget(Canvas canvas, CamtrackDoc.Track tr) {
    if (!CamtrackDoc.LOOK_POINT.equals(tr.lookMode) || tr.lookAt == null) {
      return;
    }
    float sx = (float) worldToScreenX(tr.lookAt[0]);
    float sy = (float) worldToScreenZ(tr.lookAt[2]);
    float d = getResources().getDisplayMetrics().density;
    canvas.drawCircle(sx, sy, 4 * d, paintLook);
    canvas.drawLine(sx - 6 * d, sy, sx + 6 * d, sy, paintLook);
    canvas.drawLine(sx, sy - 6 * d, sx, sy + 6 * d, paintLook);
  }

  private void drawPoseMarker(Canvas canvas) {
    if (poseMarker == null || poseMarker.pos == null) {
      return;
    }
    float sx = (float) worldToScreenX(poseMarker.pos[0]);
    float sy = (float) worldToScreenZ(poseMarker.pos[2]);
    float d = getResources().getDisplayMetrics().density;
    // the camera body: a small box with a view beam
    double fx = 0;
    double fz = 0;
    if (poseMarker.forward != null) {
      fx = poseMarker.forward[0] - poseMarker.pos[0];
      fz = poseMarker.forward[2] - poseMarker.pos[2];
    } else if (poseMarker.yawDeg != null) {
      double rad = Math.toRadians(poseMarker.yawDeg);
      fx = -Math.sin(rad);
      fz = -Math.cos(rad);
    }
    double fl = Math.sqrt(fx * fx + fz * fz);
    if (fl > 1e-9) {
      fx /= fl;
      fz /= fl;
      float beam = 30 * d;
      canvas.drawLine(sx, sy,
          sx + (float) (fx * beam), sy + (float) (fz * beam), paintCamBeam);
    }
    float w = 7 * d;
    float h = 5 * d;
    canvas.save();
    canvas.rotate((float) Math.toDegrees(Math.atan2(fx, fz)), sx, sy);
    canvas.drawRoundRect(new RectF(sx - w / 2, sy - h / 2, sx + w / 2, sy + h / 2),
        2 * d, 2 * d, paintCam);
    canvas.restore();
    // the Y (height) badge
    String y = String.format(java.util.Locale.ROOT, "y %.2f", poseMarker.pos[1]);
    float tx = sx + 10 * d;
    float ty = sy - 8 * d;
    RectF bg = new RectF(tx - 3 * d, ty - 11 * d,
        tx + paintText.measureText(y) + 3 * d, ty + 3 * d);
    canvas.drawRoundRect(bg, 3 * d, 3 * d, paintTextBg);
    canvas.drawText(y, tx, ty, paintText);
  }
}
