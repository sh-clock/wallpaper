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

package xyz.zedler.patrick.doodle.util;

import java.util.ArrayList;
import java.util.List;

/**
 * v9.7 (Camtrack native editor): the EDITOR-SIDE track math — a faithful
 * Java port of the CamTrack v13 HTML editor's {@code evalTrack()} /
 * {@code cycleDuration()} / curve-polyline-zigzag LUT functions (which the
 * engine's own camtrack_eval mirrors in C). Used by the native editor's
 * 2D top-down path canvas and its timeline: position at t, path geometry
 * for drawing, and cycle durations.
 *
 * <p>This is PREVIEW math only — the wallpaper's actual playback always
 * comes from the engine itself (the live preview IS the engine); this
 * class draws the editor chrome around it. Formulas follow the camdat
 * format contract (the DOC_STRING) exactly: phase01/tri01 wrapping, the
 * angleAt bounds walk, the uniform Catmull-Rom with the fixed 64-samples-
 * per-segment chord table, the monotone cubic Hermite (Fritsch-Carlson,
 * clamped M0+M1 <= 3) distance-to-parameter map, the exact polyline
 * segment walk and the zigzag waypoint law.</p>
 */
public final class CamtrackMath {

  private CamtrackMath() {
  }

  /** the camdat format's fixed curve table resolution — do not change */
  public static final int CURVE_SAMPLES = 64;

  private static final double DEG = Math.PI / 180.0;

  /* ---- phase helpers (the format contract) ------------------------- */

  /** tri01(u): the pingPong mirror on a wrapped u in [0,2) */
  public static double tri01(double u) {
    u = ((u % 2) + 2) % 2;
    return u < 1 ? u : 2 - u;
  }

  /** phase01(t, T, mode): loop wraps, pingPong mirrors, once stops */
  public static double phase01(double t, double period, String mode) {
    if (!(period > 0)) {
      return 0;
    }
    double u = t / period;
    if ("loop".equals(mode)) {
      return ((u % 1) + 1) % 1;
    }
    if ("once".equals(mode)) {
      return Math.min(u, 1);
    }
    return tri01(u);
  }

  /** angleAt: the bounds walk for circle/figure8 rotation */
  public static double angleAt(
      double t, double a0, double w, int dir, Double min, Double max, String mode) {
    double v = a0 + dir * w * t;
    if (min == null || max == null || !(max > min)) {
      return v;                       // free rotation
    }
    double a = clamp(a0, min, max);
    double x = (a + dir * w * t) - min;
    double span = max - min;
    if ("loop".equals(mode)) {
      return min + ((x % span) + span) % span;
    }
    if ("once".equals(mode)) {
      return clamp(a + dir * w * t, min, max);
    }
    double k = Math.floor(x / span);
    double r = x - k * span;
    return ((k % 2) + 2) % 2 == 0 ? min + r : max - r;
  }

  static double clamp(double v, double lo, double hi) {
    return Math.min(Math.max(v, lo), hi);
  }

  static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  static double dist3(double[] a, double[] b) {
    double dx = b[0] - a[0];
    double dy = b[1] - a[1];
    double dz = b[2] - a[2];
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  static double[] lerp3(double[] a, double[] b, double f) {
    return new double[]{
        a[0] + (b[0] - a[0]) * f,
        a[1] + (b[1] - a[1]) * f,
        a[2] + (b[2] - a[2]) * f
    };
  }

  static double[] normSafe(double[] d) {
    double len = Math.sqrt(
        d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
    if (len > 1e-9) {
      return new double[]{d[0] / len, d[1] / len, d[2] / len};
    }
    return new double[]{0, 0, -1};
  }

  static double[] cross3(double[] a, double[] b) {
    return new double[]{
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    };
  }

  /** normalFromTangent: the lookMode-normal side vector (up=+Y) */
  static double[] normalFromTangent(double[] t, boolean backward) {
    double[] tan = normSafe(t);
    double[] n = cross3(tan, new double[]{0, 1, 0});
    if (len(n) < 1e-6) {
      n = cross3(tan, new double[]{0, 0, 1});
    }
    if (len(n) < 1e-9) {
      n = cross3(tan, new double[]{1, 0, 0});
    }
    n = normSafe(n);
    return backward ? new double[]{-n[0], -n[1], -n[2]} : n;
  }

  private static double len(double[] v) {
    return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
  }

  /* ---- Catmull-Rom -------------------------------------------------- */

  static double[] catmullRom(
      double[] p0, double[] p1, double[] p2, double[] p3, double u) {
    double u2 = u * u;
    double u3 = u2 * u;
    return new double[]{
        0.5 * (2 * p1[0] + (-p0[0] + p2[0]) * u
            + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * u2
            + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * u3),
        0.5 * (2 * p1[1] + (-p0[1] + p2[1]) * u
            + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * u2
            + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * u3),
        0.5 * (2 * p1[2] + (-p0[2] + p2[2]) * u
            + (2 * p0[2] - 5 * p1[2] + 4 * p2[2] - p3[2]) * u2
            + (-p0[2] + 3 * p1[2] - 3 * p2[2] + p3[2]) * u3)
    };
  }

  static double[] catmullRomTangent(
      double[] p0, double[] p1, double[] p2, double[] p3, double u) {
    double u2 = u * u;
    return new double[]{
        0.5 * ((-p0[0] + p2[0])
            + 2 * (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * u
            + 3 * (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * u2),
        0.5 * ((-p0[1] + p2[1])
            + 2 * (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * u
            + 3 * (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * u2),
        0.5 * ((-p0[2] + p2[2])
            + 2 * (2 * p0[2] - 5 * p1[2] + 4 * p2[2] - p3[2]) * u
            + 3 * (-p0[2] + 3 * p1[2] - 3 * p2[2] + p3[2]) * u2)
    };
  }

  /* ---- LUTs ---------------------------------------------------------- */

  /** the curve chord table: samples, cumulative arc length, Hermite slopes */
  public static final class CurveLut {
    public final List<double[]> samples = new ArrayList<>();
    public final List<Double> cum = new ArrayList<>();
    public double total;
    public final List<double[]> p;      // anchors
    public final double[] ms;           // per-sample dx/dd slope
    public final int n;

    CurveLut(List<double[]> points) {
      this.p = points;
      this.n = points.size();
      int sampleCount = Math.max(0, n - 1) * CURVE_SAMPLES + 1;
      this.ms = new double[sampleCount];
      build();
    }

    private void build() {
      double totalAcc = 0;
      double[] prev = null;
      for (int s = 0; s < n - 1; s++) {
        for (int j = 0; j < CURVE_SAMPLES; j++) {
          double[] pt = catmullRom(at(s - 1), at(s), at(s + 1), at(s + 2),
              (double) j / CURVE_SAMPLES);
          if (prev != null) {
            totalAcc += dist3(prev, pt);
          }
          cum.add(totalAcc);
          samples.add(pt);
          prev = pt;
        }
      }
      double[] end = p.get(n - 1).clone();
      totalAcc += dist3(prev, end);
      cum.add(totalAcc);
      samples.add(end);
      total = totalAcc;
      // per-sample Hermite slopes: the EXACT instantaneous rate K/|dseg|
      int count = samples.size();
      for (int i = 0; i < count; i++) {
        int sg = Math.min(i / CURVE_SAMPLES, n - 2);
        double u = (double) (i - sg * CURVE_SAMPLES) / CURVE_SAMPLES;
        double[] dv = catmullRomTangent(
            at(sg - 1), at(sg), at(sg + 1), at(sg + 2), u);
        double l = len(dv);
        if (l > 1e-9) {
          ms[i] = CURVE_SAMPLES / l;
        } else {
          double a;
          double b;
          if (i == 0) {
            a = cum.get(0);
            b = count > 1 ? cum.get(1) : cum.get(0) + 1;
          } else if (i == count - 1) {
            a = cum.get(count - 2);
            b = cum.get(count - 1);
          } else {
            a = cum.get(i - 1);
            b = cum.get(i + 1);
          }
          double w = b - a;
          ms[i] = w > 1e-12 ? (i == 0 || i == count - 1 ? 1 / w : 2 / w) : 0;
        }
      }
    }

    private double[] at(int i) {
      return p.get(clamp(i, 0, n - 1));
    }

    /** the exact spline point at traveled distance d */
    public double[] posAt(double d) {
      d = clamp(d, 0, total);
      int lo = 0;
      int hi = cum.size() - 1;
      while (hi - lo > 1) {
        int mid = (lo + hi) >> 1;
        if (cum.get(mid) <= d) {
          lo = mid;
        } else {
          hi = mid;
        }
      }
      double span = cum.get(hi) - cum.get(lo);
      double f = span > 1e-12 ? (d - cum.get(lo)) / span : 0;
      double m0 = Math.max(ms[lo], 0) * span;
      double m1 = Math.max(ms[hi], 0) * span;
      double mSum = m0 + m1;
      if (mSum > 3) {
        m0 *= 3 / mSum;
        m1 *= 3 / mSum;
      }
      double f2 = f * f;
      double f3 = f2 * f;
      double x = lo + (f3 - 2 * f2 + f) * m0 + (-2 * f3 + 3 * f2)
          + (f3 - f2) * m1;
      int seg = (int) Math.floor(x / CURVE_SAMPLES);
      if (seg > n - 2) {
        seg = n - 2;
      }
      double u = clamp((x - seg * CURVE_SAMPLES) / CURVE_SAMPLES, 0, 1);
      return catmullRom(at(seg - 1), at(seg), at(seg + 1), at(seg + 2), u);
    }

    /** the analytic tangent at traveled distance d (chord fallback) */
    public double[] tangentAt(double d) {
      d = clamp(d, 0, total);
      int lo = 0;
      int hi = cum.size() - 1;
      while (hi - lo > 1) {
        int mid = (lo + hi) >> 1;
        if (cum.get(mid) <= d) {
          lo = mid;
        } else {
          hi = mid;
        }
      }
      double span = cum.get(hi) - cum.get(lo);
      double f = span > 1e-12 ? (d - cum.get(lo)) / span : 0;
      double m0 = Math.max(ms[lo], 0) * span;
      double m1 = Math.max(ms[hi], 0) * span;
      double mSum = m0 + m1;
      if (mSum > 3) {
        m0 *= 3 / mSum;
        m1 *= 3 / mSum;
      }
      double f2 = f * f;
      double f3 = f2 * f;
      double x = lo + (f3 - 2 * f2 + f) * m0 + (-2 * f3 + 3 * f2)
          + (f3 - f2) * m1;
      int seg = (int) Math.floor(x / CURVE_SAMPLES);
      if (seg > n - 2) {
        seg = n - 2;
      }
      double u = clamp((x - seg * CURVE_SAMPLES) / CURVE_SAMPLES, 0, 1);
      double[] t = catmullRomTangent(
          at(seg - 1), at(seg), at(seg + 1), at(seg + 2), u);
      if (len(t) < 1e-9) {
        for (int k = lo; k < samples.size() - 1; k++) {
          double[] c = sub3(samples.get(k + 1), samples.get(k));
          if (len(c) > 1e-9) {
            t = c;
            break;
          }
        }
      }
      return normSafe(t);
    }
  }

  static double[] sub3(double[] a, double[] b) {
    return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
  }

  static double[] add3(double[] a, double[] b) {
    return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
  }

  /** the polyline/zigzag LUT: cumulative chord lengths over waypoints */
  public static final class PointsLut {
    public final List<double[]> p;
    public final double[] cum;
    public final double total;

    PointsLut(List<double[]> points) {
      this.p = points;
      this.cum = new double[points.size()];
      double totalAcc = 0;
      for (int i = 0; i < points.size() - 1; i++) {
        totalAcc += dist3(points.get(i), points.get(i + 1));
        cum[i + 1] = totalAcc;
      }
      this.total = totalAcc;
    }

    /** {seg, f}: the segment reached by d and the fraction inside it;
     *  f is returned as element[2]/1e6 to stay allocation-light */
    public double[] segAndFrac(double d) {
      d = clamp(d, 0, total);
      int lo = 0;
      int hi = cum.length - 1;
      while (hi - lo > 1) {
        int mid = (lo + hi) >> 1;
        if (cum[mid] <= d) {
          lo = mid;
        } else {
          hi = mid;
        }
      }
      double span = cum[hi] - cum[lo];
      double f = span > 1e-12 ? (d - cum[lo]) / span : 0;
      return new double[]{lo, f};
    }

    public double[] posAt(double d) {
      d = clamp(d, 0, total);
      int lo = 0;
      int hi = cum.length - 1;
      while (hi - lo > 1) {
        int mid = (lo + hi) >> 1;
        if (cum[mid] <= d) {
          lo = mid;
        } else {
          hi = mid;
        }
      }
      double span = cum[hi] - cum[lo];
      double f = span > 1e-12 ? (d - cum[lo]) / span : 0;
      return lerp3(p.get(lo), p.get(lo + 1), f);
    }

    /** the (piecewise-constant) segment direction at d */
    public double[] tangentAt(double d) {
      d = clamp(d, 0, total);
      int lo = 0;
      int hi = cum.length - 1;
      while (hi - lo > 1) {
        int mid = (lo + hi) >> 1;
        if (cum[mid] <= d) {
          lo = mid;
        } else {
          hi = mid;
        }
      }
      for (int i = lo; i < p.size() - 1; i++) {
        double[] t = sub3(p.get(i + 1), p.get(i));
        if (len(t) > 1e-9) {
          return normSafe(t);
        }
      }
      for (int i = lo - 1; i >= 0; i--) {
        double[] t = sub3(p.get(i + 1), p.get(i));
        if (len(t) > 1e-9) {
          return normSafe(t);
        }
      }
      return new double[]{0, 0, -1};
    }
  }

  /** zigzagPoints(): the lawnmower waypoints (the format contract) */
  public static List<double[]> zigzagPoints(CamtrackDoc.Track tr) {
    double[] c = tr.center;
    double w = Math.max(tr.sizeX, 0.01);
    double d = Math.max(tr.sizeZ, 0.01);
    int lanes = Math.max(2, (int) Math.round(tr.lanes));
    double dz = d / (lanes - 1);
    List<double[]> pts = new ArrayList<>();
    int side = -1;                     // first pass runs -X -> +X
    for (int i = 0; i < lanes; i++) {
      double z = c[2] - d / 2 + i * dz;
      pts.add(new double[]{c[0] + side * w / 2, c[1], z});
      side = -side;
      pts.add(new double[]{c[0] + side * w / 2, c[1], z});
    }
    return pts;
  }

  /* ---- cycleDuration (the v13 editor's, == the engine's) ------------ */

  /** one playback cycle in seconds (Double.POSITIVE_INFINITY for loops) */
  public static double cycleDuration(CamtrackDoc.Track tr) {
    switch (tr.type) {
      case CamtrackDoc.TYPE_LINE: {
        double t = dist3(tr.start, tr.end) / Math.max(tr.speedMS, 1e-6);
        return CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode) ? t * 2 : t;
      }
      case CamtrackDoc.TYPE_CIRCLE: {
        double w = Math.abs(tr.speedDegS);
        if (w < 1e-6) {
          w = 1e-6;
        }
        boolean bounded = tr.arcBounds && tr.arcMaxDeg > tr.arcMinDeg;
        if (bounded) {
          double span = tr.arcMaxDeg - tr.arcMinDeg;
          return CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode)
              ? 2 * span / w : span / w;
        }
        return CamtrackDoc.LOOP_ONCE.equals(tr.loopMode)
            ? Double.POSITIVE_INFINITY : 360 / w;
      }
      case CamtrackDoc.TYPE_CURVE: {
        double total = curveLut(tr).total;
        double t = total / Math.max(tr.speedMS, 1e-6);
        return CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode) ? t * 2 : t;
      }
      case CamtrackDoc.TYPE_POLYLINE: {
        double total = new PointsLut(tr.points).total;
        double t = total / Math.max(tr.speedMS, 1e-6);
        return CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode) ? t * 2 : t;
      }
      case CamtrackDoc.TYPE_ZIGZAG: {
        double total = new PointsLut(zigzagPoints(tr)).total;
        double t = total / Math.max(tr.speedMS, 1e-6);
        return CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode) ? t * 2 : t;
      }
      case CamtrackDoc.TYPE_SPIRAL: {
        double t = Math.abs(tr.turns) * 360 / Math.max(tr.speedDegS, 1e-6);
        return CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode) ? t * 2 : t;
      }
      case CamtrackDoc.TYPE_FIGURE8: {
        double t = 360 / Math.max(tr.speedDegS, 1e-6);
        return CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode) ? t * 2 : t;
      }
      default: {  // fixed: the longest axis mirror/span
        double t = 0;
        boolean yawB = tr.yawBounds && tr.yawMaxDeg > tr.yawMinDeg;
        boolean pitchB = tr.pitchBounds && tr.pitchMaxDeg > tr.pitchMinDeg;
        double yawSpan = yawB ? tr.yawMaxDeg - tr.yawMinDeg : 360;
        double pitchSpan = pitchB ? tr.pitchMaxDeg - tr.pitchMinDeg : 360;
        double yawSp = Math.abs(tr.yawSpeedDegS);
        double pitchSp = Math.abs(tr.pitchSpeedDegS);
        if (yawSp > 0) {
          double per = CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode)
              ? 2 * yawSpan / yawSp : yawSpan / yawSp;
          if (per > t) {
            t = per;
          }
        }
        if (pitchSp > 0) {
          double per = CamtrackDoc.LOOP_PINGPONG.equals(tr.loopMode)
              ? 2 * pitchSpan / pitchSp : pitchSpan / pitchSp;
          if (per > t) {
            t = per;
          }
        }
        return t;
      }
    }
  }

  /* ---- the eval (evalTrack's position + facing math) ---------------- */

  /** the camera pose preview: position + unit forward (look) direction */
  public static final class Pose {
    public final double[] pos;
    public final double[] forward;   // unit; null when yaw/pitch held
    public final Double yawDeg;      // fixed-look yaw (or null)
    public final Double pitchDeg;

    Pose(double[] pos, double[] forward, Double yaw, Double pitch) {
      this.pos = pos;
      this.forward = forward;
      this.yawDeg = yaw;
      this.pitchDeg = pitch;
    }
  }

  /**
   * evalTrack(tr, time) — position + the facing target for the canvas
   * camera body. The lookAt/yaw semantics match the format contract; the
   * turn filter (the engine's) is NOT applied — the marker shows the
   * instantaneous target facing, like the editor's anchor gizmo.
   */
  public static Pose evalPose(CamtrackDoc.Track tr, double time) {
    switch (tr.type) {
      case CamtrackDoc.TYPE_LINE: {
        double len = dist3(tr.start, tr.end);
        double t = len / Math.max(tr.speedMS, 1e-6);
        double s = phase01(time, t, tr.loopMode);
        double[] pos = lerp3(tr.start, tr.end, s);
        if (CamtrackDoc.LOOK_PATH.equals(tr.lookMode)
            || CamtrackDoc.LOOK_LAZY.equals(tr.lookMode)) {
          boolean returning = isReturning(time, t, tr.loopMode);
          double[] dir = normSafe(returning ? sub3(tr.start, tr.end)
              : sub3(tr.end, tr.start));
          return new Pose(pos, add3(pos, dir), null, null);
        }
        if (CamtrackDoc.LOOK_NORMAL.equals(tr.lookMode)) {
          boolean returning = isReturning(time, t, tr.loopMode);
          double[] tan = normSafe(returning ? sub3(tr.start, tr.end)
              : sub3(tr.end, tr.start));
          double[] n = normalFromTangent(tan,
              CamtrackDoc.SIDE_BACKWARD.equals(tr.normalDirection));
          return new Pose(pos, add3(pos, n), null, null);
        }
        if (CamtrackDoc.LOOK_POINT.equals(tr.lookMode)) {
          return new Pose(pos, tr.lookAt.clone(), null, null);
        }
        return new Pose(pos, null, tr.yawDeg, tr.pitchDeg);
      }
      case CamtrackDoc.TYPE_CIRCLE: {
        int dir = CamtrackDoc.DIR_CCW.equals(tr.direction) ? -1 : 1;
        boolean bounded = tr.arcBounds && tr.arcMaxDeg > tr.arcMinDeg;
        double th = angleAt(time, tr.startAngleDeg,
            Math.abs(tr.speedDegS) < 1e-9 ? 0 : Math.abs(tr.speedDegS), dir,
            bounded ? tr.arcMinDeg : null,
            bounded ? tr.arcMaxDeg : null, tr.loopMode);
        double rad = th * DEG;
        double[] pos = new double[]{
            tr.center[0] + tr.radiusX * Math.cos(rad),
            tr.center[1],
            tr.center[2] + tr.radiusZ * Math.sin(rad)
        };
        if (CamtrackDoc.LOOK_CENTER.equals(tr.lookMode)) {
          return new Pose(pos, tr.center.clone(), null, null);
        }
        if (CamtrackDoc.LOOK_POINT.equals(tr.lookMode)) {
          return new Pose(pos, tr.lookAt.clone(), null, null);
        }
        double tx = -tr.radiusX * Math.sin(rad);
        double tz = tr.radiusZ * Math.cos(rad);
        double l = Math.hypot(tx, tz);
        if (l < 1e-9) {
          l = 1;
        }
        double[] tan = new double[]{dir * tx / l, 0, dir * tz / l};
        if (CamtrackDoc.LOOK_PATH.equals(tr.lookMode)
            || CamtrackDoc.LOOK_LAZY.equals(tr.lookMode)) {
          return new Pose(pos, add3(pos, tan), null, null);
        }
        if (CamtrackDoc.LOOK_NORMAL.equals(tr.lookMode)) {
          double[] n = normalFromTangent(tan,
              CamtrackDoc.SIDE_BACKWARD.equals(tr.normalDirection));
          return new Pose(pos, add3(pos, n), null, null);
        }
        return new Pose(pos, null, tr.yawDeg, tr.pitchDeg);
      }
      case CamtrackDoc.TYPE_CURVE: {
        CurveLut lut = curveLut(tr);
        double t = lut.total / Math.max(tr.speedMS, 1e-6);
        double s = phase01(time, t, tr.loopMode);
        double d = s * lut.total;
        double[] pos = lut.posAt(d);
        return poseFromTangent(tr, pos, lut.tangentAt(d), time, t);
      }
      case CamtrackDoc.TYPE_POLYLINE: {
        PointsLut lut = new PointsLut(tr.points);
        double t = lut.total / Math.max(tr.speedMS, 1e-6);
        double s = phase01(time, t, tr.loopMode);
        double d = s * lut.total;
        double[] pos = lut.posAt(d);
        return poseFromTangent(tr, pos, lut.tangentAt(d), time, t);
      }
      case CamtrackDoc.TYPE_ZIGZAG: {
        PointsLut lut = new PointsLut(zigzagPoints(tr));
        double t = lut.total / Math.max(tr.speedMS, 1e-6);
        double s = phase01(time, t, tr.loopMode);
        double d = s * lut.total;
        double[] pos = lut.posAt(d);
        if (CamtrackDoc.LOOK_CENTER.equals(tr.lookMode)) {
          return new Pose(pos, tr.center.clone(), null, null);
        }
        if (CamtrackDoc.LOOK_POINT.equals(tr.lookMode)) {
          return new Pose(pos, tr.lookAt.clone(), null, null);
        }
        return poseFromTangent(tr, pos, lut.tangentAt(d), time, t);
      }
      case CamtrackDoc.TYPE_SPIRAL: {
        int dir = CamtrackDoc.DIR_CCW.equals(tr.direction) ? -1 : 1;
        double sweep = Math.abs(tr.turns) * 360;
        double t = sweep / Math.max(tr.speedDegS, 1e-6);
        double f = phase01(time, t, tr.loopMode);
        double th = (tr.startAngleDeg + dir * sweep * f) * DEG;
        double r = tr.radiusStart + (tr.radiusEnd - tr.radiusStart) * f;
        double[] pos = new double[]{
            tr.center[0] + r * Math.cos(th),
            tr.center[1] + tr.yStart + (tr.yEnd - tr.yStart) * f,
            tr.center[2] + r * Math.sin(th)
        };
        if (CamtrackDoc.LOOK_CENTER.equals(tr.lookMode)) {
          return new Pose(pos, tr.center.clone(), null, null);
        }
        if (CamtrackDoc.LOOK_POINT.equals(tr.lookMode)) {
          return new Pose(pos, tr.lookAt.clone(), null, null);
        }
        // the analytic spiral tangent
        double dth = dir * sweep * DEG;
        double dr = tr.radiusEnd - tr.radiusStart;
        double dy = tr.yEnd - tr.yStart;
        double[] tan = normSafe(new double[]{
            dr * Math.cos(th) - r * Math.sin(th) * dth,
            dy,
            dr * Math.sin(th) + r * Math.cos(th) * dth
        });
        return poseFromTangent(tr, pos, tan, time, t);
      }
      case CamtrackDoc.TYPE_FIGURE8: {
        int dir = CamtrackDoc.DIR_CCW.equals(tr.direction) ? -1 : 1;
        // bound the sweep to exactly one full 360 deg period
        double aMin = dir > 0 ? tr.startAngleDeg : tr.startAngleDeg - 360;
        double aMax = dir > 0 ? tr.startAngleDeg + 360 : tr.startAngleDeg;
        double t = 360 / Math.max(tr.speedDegS, 1e-6);
        double th = angleAt(time, tr.startAngleDeg,
            Math.abs(tr.speedDegS) < 1e-9 ? 0 : Math.abs(tr.speedDegS), dir,
            aMin, aMax, tr.loopMode) * DEG;
        double[] pos = new double[]{
            tr.center[0] + tr.semiX * Math.sin(th),
            tr.center[1],
            tr.center[2] + tr.semiZ * Math.sin(2 * th)
        };
        if (CamtrackDoc.LOOK_CENTER.equals(tr.lookMode)) {
          return new Pose(pos, tr.center.clone(), null, null);
        }
        if (CamtrackDoc.LOOK_POINT.equals(tr.lookMode)) {
          return new Pose(pos, tr.lookAt.clone(), null, null);
        }
        // analytic Gerono tangent, travel direction = dir * dpos/dtheta
        double tx = tr.semiX * Math.cos(th);
        double tz = 2 * tr.semiZ * Math.cos(2 * th);
        double l = Math.hypot(tx, tz);
        if (l < 1e-9) {
          l = 1;
        }
        double[] tan = new double[]{dir * tx / l, 0, dir * tz / l};
        return poseFromTangent(tr, pos, tan, time, t);
      }
      default: {  // fixed
        double[] pos = tr.position.clone();
        // the fixed orientation: yaw/pitch advance at their speeds with
        // the boundary mirrors — the same angleAt walk
        double yaw = angleAt(time, tr.yawDeg,
            Math.abs(tr.yawSpeedDegS), tr.yawSpeedDegS < 0 ? -1 : 1,
            tr.yawBounds && tr.yawMaxDeg > tr.yawMinDeg ? tr.yawMinDeg : null,
            tr.yawBounds && tr.yawMaxDeg > tr.yawMinDeg ? tr.yawMaxDeg : null,
            tr.loopMode);
        double pitch = angleAt(time, tr.pitchDeg,
            Math.abs(tr.pitchSpeedDegS), tr.pitchSpeedDegS < 0 ? -1 : 1,
            tr.pitchBounds && tr.pitchMaxDeg > tr.pitchMinDeg ? tr.pitchMinDeg : null,
            tr.pitchBounds && tr.pitchMaxDeg > tr.pitchMinDeg ? tr.pitchMaxDeg : null,
            tr.loopMode);
        return new Pose(pos, null, yaw, pitch);
      }
    }
  }

  private static Pose poseFromTangent(
      CamtrackDoc.Track tr, double[] pos, double[] tangent, double time,
      double period) {
    if (CamtrackDoc.LOOK_PATH.equals(tr.lookMode)
        || CamtrackDoc.LOOK_LAZY.equals(tr.lookMode)) {
      boolean returning = isReturning(time, period, tr.loopMode);
      double[] tan = returning ? new double[]{-tangent[0], -tangent[1], -tangent[2]}
          : tangent;
      return new Pose(pos, add3(pos, tan), null, null);
    }
    if (CamtrackDoc.LOOK_NORMAL.equals(tr.lookMode)) {
      boolean returning = isReturning(time, period, tr.loopMode);
      double[] tan = returning ? new double[]{-tangent[0], -tangent[1], -tangent[2]}
          : tangent;
      double[] n = normalFromTangent(tan,
          CamtrackDoc.SIDE_BACKWARD.equals(tr.normalDirection));
      return new Pose(pos, add3(pos, n), null, null);
    }
    if (CamtrackDoc.LOOK_POINT.equals(tr.lookMode)) {
      return new Pose(pos, tr.lookAt.clone(), null, null);
    }
    return new Pose(pos, null, tr.yawDeg, tr.pitchDeg);
  }

  private static boolean isReturning(double time, double period, String mode) {
    if (!CamtrackDoc.LOOP_PINGPONG.equals(mode) || !(period > 0)) {
      return false;
    }
    double u = time / period;
    return ((u % 2) + 2) % 2 >= 1;
  }

  /* ---- drawable path geometry (the 2D canvas) ----------------------- */

  /**
   * The track's drawable XZ path: chord samples for curves, waypoints for
   * polyline/zigzag, analytic rings for circle/spiral/figure8, the segment
   * for lines, and the single anchor for fixed cameras.
   */
  public static List<double[]> pathSamples(CamtrackDoc.Track tr) {
    List<double[]> out = new ArrayList<>();
    switch (tr.type) {
      case CamtrackDoc.TYPE_LINE:
        out.add(tr.start.clone());
        out.add(tr.end.clone());
        break;
      case CamtrackDoc.TYPE_FIXED:
        out.add(tr.position.clone());
        break;
      case CamtrackDoc.TYPE_CIRCLE: {
        boolean bounded = tr.arcBounds && tr.arcMaxDeg > tr.arcMinDeg;
        double min = bounded ? tr.arcMinDeg : 0;
        double max = bounded ? tr.arcMaxDeg : 360;
        int steps = Math.max(24, (int) Math.ceil(Math.abs(max - min) / 3.0));
        for (int i = 0; i <= steps; i++) {
          double th = (min + (max - min) * i / steps) * DEG;
          out.add(new double[]{
              tr.center[0] + tr.radiusX * Math.cos(th),
              tr.center[1],
              tr.center[2] + tr.radiusZ * Math.sin(th)
          });
        }
        break;
      }
      case CamtrackDoc.TYPE_SPIRAL: {
        int dir = CamtrackDoc.DIR_CCW.equals(tr.direction) ? -1 : 1;
        double sweep = Math.abs(tr.turns) * 360;
        int steps = Math.max(48, (int) Math.ceil(sweep / 3.0));
        for (int i = 0; i <= steps; i++) {
          double f = (double) i / steps;
          double th = (tr.startAngleDeg + dir * sweep * f) * DEG;
          double r = tr.radiusStart + (tr.radiusEnd - tr.radiusStart) * f;
          out.add(new double[]{
              tr.center[0] + r * Math.cos(th),
              tr.center[1] + tr.yStart + (tr.yEnd - tr.yStart) * f,
              tr.center[2] + r * Math.sin(th)
          });
        }
        break;
      }
      case CamtrackDoc.TYPE_FIGURE8: {
        int steps = Math.max(64, 128);
        for (int i = 0; i <= steps; i++) {
          double th = (tr.startAngleDeg + 360.0 * i / steps) * DEG;
          out.add(new double[]{
              tr.center[0] + tr.semiX * Math.sin(th),
              tr.center[1],
              tr.center[2] + tr.semiZ * Math.sin(2 * th)
          });
        }
        break;
      }
      case CamtrackDoc.TYPE_CURVE:
        out.addAll(curveLut(tr).samples);
        break;
      case CamtrackDoc.TYPE_POLYLINE:
        for (double[] p : tr.points) {
          out.add(p.clone());
        }
        break;
      case CamtrackDoc.TYPE_ZIGZAG:
        for (double[] p : zigzagPoints(tr)) {
          out.add(p.clone());
        }
        break;
      default:
        break;
    }
    return out;
  }

  /** curveLUT with the v13 memoization signature (anchors joined) */
  private static CurveLut curveLut(CamtrackDoc.Track tr) {
    return new CurveLut(tr.points);
  }
}
