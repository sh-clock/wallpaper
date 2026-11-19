/*
 * This file is part of the SH1 Fog wallpaper fork (non-commercial).
 *
 * v10.1 TASK 4 — the Camtrack editor's CROP OVERLAY.
 * v10.4 — the v10.2 FULL-CONTEXT OVERLAY, restored. The v10.3 WYSIWYG
 * rewrite (invisible drag layer over a live-cropped phone-aspect preview)
 * is REVERTED per the user's real-device verdict: panning the rendered
 * CONTENT under a fixed window reads as "the camera angle rotated". This
 * view is the wanted interaction instead — the CROP WINDOW ("the camera
 * box") TRANSLATES over the FIXED full-frame scene:
 *
 * The editor preview shows the engine's FULL framebuffer, letterboxed to
 * the raster's aspect ratio (applyPreviewAspect). The wallpaper on the
 * phone itself is presented STRICT CENTER-CROP (aspect fill — see
 * fog_poc.c and_blit): the screen shows only the sub-window of the
 * framebuffer whose aspect ratio equals the device screen's. This view
 * draws EXACTLY that window on top of the preview:
 *
 *   - a semi-transparent scrim OUTSIDE the window (what the screen crops
 *     away — the letterbox bars whose sizes the offsets shift), computed
 *     with the SAME uniform-scale math as the native blit
 *     (s = max(screenW/fbW, screenH/fbH), window = screen/s),
 *   - a crisp stroke around the window,
 *   - DRAG: dragging slides the crop window along its croppable axis
 *     (the user's normalized -1..1 offsets — the same values the
 *     wallpaper's presentation blit consumes). The image never moves;
 *     only the window (the bars' sizes) translate.
 *
 * The math here is a faithful Java mirror of the C presentation layer
 * (and_blit): one uniform scale, fractional window origin, clamped
 * offsets. The engine itself is never touched — the actual crop on the
 * phone is the REAL 2D blit-rect pan (raster provably offset-invariant).
 */
package xyz.zedler.patrick.doodle.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;

public class Sh1CropOverlayView extends View {

  /** scrim over the area the screen crops away (60% black) */
  private static final int SCRIM_COLOR = 0x99000000;

  /** the listener receives offset changes while the user drags */
  public interface CropListener {
    void onCropOffsetsChanged(float x, float y, boolean dragging);
  }

  @Nullable
  private CropListener listener;

  /* the geometry inputs (set by the editor before drawing) */
  private int rasterW = 320;
  private int rasterH = 224;
  private int screenW = 1080;
  private int screenH = 2400;

  /* the live crop-window offsets (normalized -1..1, 0 = center) */
  private float offsetX = 0f;
  private float offsetY = 0f;

  /* drag bookkeeping */
  private boolean dragging;
  private float downX, downY;
  private float downOffsetX, downOffsetY;

  private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF windowRect = new RectF();

  public Sh1CropOverlayView(Context context) {
    super(context);
    init();
  }

  public Sh1CropOverlayView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public Sh1CropOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    scrimPaint.setColor(SCRIM_COLOR);
    scrimPaint.setStyle(Paint.Style.FILL);
    strokePaint.setColor(Color.WHITE);
    strokePaint.setStyle(Paint.Style.STROKE);
    strokePaint.setStrokeWidth(dp(2));
    handlePaint.setColor(Color.WHITE);
    handlePaint.setStyle(Paint.Style.STROKE);
    handlePaint.setStrokeWidth(dp(2));
    setClickable(true);
    setFocusable(false);
  }

  public void setCropListener(@Nullable CropListener l) {
    listener = l;
  }

  /** the engine raster size (the framebuffer the preview displays) */
  public void setGeometry(int rasterW, int rasterH, int screenW, int screenH) {
    this.rasterW = Math.max(1, rasterW);
    this.rasterH = Math.max(1, rasterH);
    this.screenW = Math.max(1, screenW);
    this.screenH = Math.max(1, screenH);
    invalidate();
  }

  public void setOffsets(float x, float y) {
    this.offsetX = clamp(x);
    this.offsetY = clamp(y);
    invalidate();
  }

  public float getOffsetX() {
    return offsetX;
  }

  public float getOffsetY() {
    return offsetY;
  }

  private static float clamp(float v) {
    if (!(v >= -1.0f)) {
      return -1.0f;   // NaN-safe
    }
    return Math.min(v, 1.0f);
  }

  private float dp(float v) {
    return v * getResources().getDisplayMetrics().density;
  }

  /**
   * The crop window in VIEW coordinates — the faithful Java mirror of
   * the native and_blit math: ONE uniform scale s = max(sw/fw, sh/fh)
   * (the screen meets or exceeds the raster in both dims), the window
   * is the screen-sized portion of the raster (screen/s), and the user
   * offsets slide it inside the available slack on the cropped axis.
   */
  private RectF computeWindow() {
    final int vw = getWidth();
    final int vh = getHeight();
    if (vw <= 0 || vh <= 0 || rasterW <= 0 || rasterH <= 0) {
      windowRect.set(0, 0, vw, vh);
      return windowRect;
    }
    // the preview displays the FULL raster: view scale (raster -> view px)
    final float scale = Math.min((float) vw / rasterW, (float) vh / rasterH);
    // the uniform cover scale (raster -> screen px): s = max(sw/fw, sh/fh)
    final double s = Math.max(
        (double) screenW / rasterW, (double) screenH / rasterH);
    // the visible window in raster units (fractional, exactly like the C)
    final double srcW = screenW / s;
    final double srcH = screenH / s;
    final double availX = rasterW - srcW;   // >= 0
    final double availY = rasterH - srcH;   // >= 0
    final double cx = availX > 0.0
        ? (availX * 0.5) * (1.0 + (double) offsetX) : 0.0;
    final double cy = availY > 0.0
        ? (availY * 0.5) * (1.0 + (double) offsetY) : 0.0;

    // the preview letterboxes the raster inside the view — center it
    final float ox = (vw - rasterW * scale) * 0.5f;
    final float oy = (vh - rasterH * scale) * 0.5f;
    windowRect.set(
        ox + (float) (cx * scale),
        oy + (float) (cy * scale),
        ox + (float) ((cx + srcW) * scale),
        oy + (float) ((cy + srcH) * scale)
    );
    return windowRect;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    RectF win = computeWindow();
    // the scrim: everything OUTSIDE the crop window (what the screen
    // crops away) — four rects around the window
    final int vw = getWidth();
    final int vh = getHeight();
    if (win.left > 0) {
      canvas.drawRect(0, 0, win.left, vh, scrimPaint);
    }
    if (win.right < vw) {
      canvas.drawRect(win.right, 0, vw, vh, scrimPaint);
    }
    if (win.top > 0) {
      canvas.drawRect(win.left, 0, win.right, win.top, scrimPaint);
    }
    if (win.bottom < vh) {
      canvas.drawRect(win.left, win.bottom, win.right, vh, scrimPaint);
    }
    // the window itself: a crisp stroke + subtle corner brackets
    canvas.drawRect(win, strokePaint);
    drawCornerBracket(canvas, win.left, win.top, 1, 1);
    drawCornerBracket(canvas, win.right, win.top, -1, 1);
    drawCornerBracket(canvas, win.left, win.bottom, 1, -1);
    drawCornerBracket(canvas, win.right, win.bottom, -1, -1);
  }

  private void drawCornerBracket(Canvas c, float x, float y, int dx, int dy) {
    float len = dp(14);
    c.drawLine(x, y, x + dx * len, y, handlePaint);
    c.drawLine(x, y, x, y + dy * len, handlePaint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        dragging = true;
        downX = event.getX();
        downY = event.getY();
        downOffsetX = offsetX;
        downOffsetY = offsetY;
        if (listener != null) {
          listener.onCropOffsetsChanged(offsetX, offsetY, true);
        }
        return true;
      case MotionEvent.ACTION_MOVE: {
        if (!dragging) {
          return false;
        }
        float scale = Math.min(
            (float) getWidth() / rasterW, (float) getHeight() / rasterH);
        if (scale <= 0f) {
          return true;
        }
        // the uniform cover scale + the raster-level slack (the SAME
        // math as computeWindow/the native blit)
        final double s = Math.max(
            (double) screenW / rasterW, (double) screenH / rasterH);
        final double srcW = screenW / s;
        final double srcH = screenH / s;
        final double availX = rasterW - srcW;   // > 0: the screen crops X
        final double availY = rasterH - srcH;   // > 0: the screen crops Y
        if (availX > 0.0) {
          // horizontal drag: view px -> raster cols -> normalized offset
          double dcx = (event.getX() - downX) / scale;
          double newCx = (availX * 0.5) * (1.0 + (double) downOffsetX) + dcx;
          if (newCx < 0.0) {
            newCx = 0.0;
          }
          if (newCx > availX) {
            newCx = availX;
          }
          offsetX = clamp((float) (2.0 * newCx / availX - 1.0));
        }
        if (availY > 0.0) {
          // vertical drag: view px -> raster rows -> normalized offset
          double dcy = (event.getY() - downY) / scale;
          double newCy = (availY * 0.5) * (1.0 + (double) downOffsetY) + dcy;
          if (newCy < 0.0) {
            newCy = 0.0;
          }
          if (newCy > availY) {
            newCy = availY;
          }
          offsetY = clamp((float) (2.0 * newCy / availY - 1.0));
        }
        invalidate();
        if (listener != null) {
          listener.onCropOffsetsChanged(offsetX, offsetY, true);
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        dragging = false;
        performClick();
        if (listener != null) {
          listener.onCropOffsetsChanged(offsetX, offsetY, false);
        }
        return true;
      default:
        return super.onTouchEvent(event);
    }
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }
}
