/*
 * v10.4 — the Camtrack editor's FULL-CONTEXT crop editor + universal back
 * tests (supersedes the v10.3 WYSIWYG assertions per the user's
 * real-device verdict).
 *
 * CROP (v10.4 — the v10.2 model, restored): the editor preview frame
 * carries the ENGINE RASTER's aspect ratio (320:224), so the engine's own
 * and_blit degenerates to an exact fit there — the FULL framebuffer is
 * always visible and the crop offsets are INERT on the preview surface.
 * Sh1CropOverlayView draws the phone screen's crop window on top (scrim
 * outside it = what is left out) and dragging TRANSLATES that window box
 * over the fixed scene. These tests verify the Java side of that
 * contract:
 *   - the preview frame's LayoutParams match the RASTER aspect (the full
 *     frame visible — NOT the display aspect; the WYSIWYG regression is
 *     guarded against),
 *   - the drag layer converts touch drags to the SAME normalized -1..1
 *     offsets via the and_blit-mirrored cover math (cropped axis responds,
 *     the filling axis is inert, extreme drags clamp),
 *   - a released drag persists the offsets to the canonical prefs.
 *
 * The ACTUAL crop on the phone stays the TRUE 2D blit-rect pan (native
 * and_blit — provably raster-invariant; unchanged since v10.1).
 *
 * BACK (v10.3, kept): in the editor, back ALWAYS acts — from a deeper
 * page it steps back to TRACKS; from a clean TRACKS page the terminal
 * callback finishes the activity. No silent fall-through anywhere.
 */
package xyz.zedler.patrick.doodle.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import java.util.concurrent.TimeUnit;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ViewFlipper;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLegacyChoreographer;
import xyz.zedler.patrick.doodle.Constants;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.view.Sh1CropOverlayView;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
// portrait phone dimensions (the phone SCREEN the crop window mirrors);
// static theme pinned (the Robolectric legacy resource bridge limitation,
// see TrampolineBackFixTest)
@Config(sdk = 34, qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.LEGACY)
public class EditorCropPreviewTest {

  private ActivityController<CamtrackEditorActivity> controller;
  private CamtrackEditorActivity activity;

  @Before
  public void setUp() {
    // s26/V52 (the unit-hang fix, twin of EditorBootSmokeTest's note):
    // a zero-delay frame callback re-posts itself within the same drain
    // under ShadowLegacyChoreographer (its frame time BUMPS per call, so
    // the editor's advancing-clock guard never terminates the chain).
    // The 16ms delay parks each re-post in the future: idleFor(16) below
    // then executes EXACTLY ONE doFrame pass per call — the contract this
    // test's idle() helper always documented.
    ShadowLegacyChoreographer.setPostFrameCallbackDelay(16);
    Context deviceCtx = ApplicationProvider.getApplicationContext()
        .createDeviceProtectedStorageContext();
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(deviceCtx);
    prefs.edit().putString(Constants.PREF.THEME, Constants.THEME.RED).commit();

    controller = Robolectric.buildActivity(CamtrackEditorActivity.class).setup();
    activity = controller.get();
    idle();
  }

  @After
  public void tearDown() {
    if (controller != null) {
      controller.destroy();
    }
  }

  /**
   * A BOUNDED looper drain. The editor's Choreographer frame callback
   * legitimately re-posts itself every frame (a real display does this
   * forever), so a full ShadowLooper.idle() would spin forever under
   * Robolectric. Draining ONE frame's worth (16 ms) executes everything
   * that is due now while always terminating. (s26/V52: terminating is
   * only true WITH the 16ms frame-callback delay set in setUp — with the
   * zero-delay default the re-post is due in the same drain and the
   * advancing-frame-time guard cannot stop the chain.)
   */
  private static void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS);
  }

  /* ---- the FULL-CONTEXT preview geometry (v10.2 model restored) -------- */

  /**
   * The preview frame must carry the ENGINE RASTER's aspect (320:224 by
   * default), full-bleed width — that is what makes the engine's and_blit
   * degenerate to an EXACT FIT on the preview (the full framebuffer
   * visible, the crop offsets inert there) while Sh1CropOverlayView
   * visualizes the phone screen's window on top. It must NOT carry the
   * display's aspect (the reverted v10.3 WYSIWYG regression).
   */
  @Test
  public void previewFrameShowsFullRasterAspect() throws Exception {
    FrameLayout frame = activity.findViewById(R.id.frame_editor_preview);
    assertNotNull(frame);
    // drive the aspect application exactly like the surface callback does
    java.lang.reflect.Method m =
        CamtrackEditorActivity.class.getDeclaredMethod("applyPreviewAspect");
    m.setAccessible(true);
    m.invoke(activity);
    idle();

    LinearLayout.LayoutParams lp =
        (LinearLayout.LayoutParams) frame.getLayoutParams();
    assertEquals("preview must stay full-bleed width",
        LinearLayout.LayoutParams.MATCH_PARENT, lp.width);
    assertTrue("preview height must be positive", lp.height > 0);

    // the raster aspect (default authentic 320x224): the LAID-OUT width
    // (MATCH_PARENT resolves at measure time) against the set height —
    // h == vw * rh / rw within integer rounding (+/- 2 px)
    int vw = Math.max(1, frame.getWidth());
    int rw = 320, rh = 224;
    long lhs = (long) vw * rh;
    long rhs = (long) lp.height * rw;
    assertTrue("preview aspect must equal the RASTER aspect (full frame "
            + "visible), got " + vw + "x" + lp.height,
        Math.abs(lhs - rhs) <= 2L * Math.max(rw, rh));

    // and it must NOT be the display aspect (the WYSIWYG regression guard).
    // NOTE: Robolectric's WindowMetrics shadow returns a degenerate 0x0
    // bounds here (the v10.3 test's display-aspect check passed VACUOUSLY
    // on 0==0), so this guard runs only when the display size is real —
    // the positive raster-aspect check above already discriminates hard
    // (a display-aspect 360x800 preview fails it by ~548 px of height).
    int[] screen = xyz.zedler.patrick.doodle.util.UiUtil.getRealDisplaySize(activity);
    if (screen[0] > 0 && screen[1] > 0) {
      long dLhs = (long) vw * screen[1];
      long dRhs = (long) lp.height * screen[0];
      assertTrue("preview aspect must NOT mirror the display aspect (the "
          + "v10.3 WYSIWYG regression); vw=" + vw + " lp.height=" + lp.height
          + " screen=" + screen[0] + "x" + screen[1],
          Math.abs(dLhs - dRhs) > 2L * Math.max(screen[0], screen[1]));
    }
  }

  /* ---- the drag layer math (unchanged contract) ------------------------- */

  private Sh1CropOverlayView overlay() {
    Sh1CropOverlayView v = activity.findViewById(R.id.crop_editor_overlay);
    assertNotNull(v);
    return v;
  }

  private static void drag(Sh1CropOverlayView v, float dx, float dy) {
    long downTime = 400L;
    v.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime,
        MotionEvent.ACTION_DOWN, v.getWidth() / 2f, v.getHeight() / 2f, 0));
    v.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 100L,
        MotionEvent.ACTION_MOVE, v.getWidth() / 2f + dx, v.getHeight() / 2f + dy, 0));
    v.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 120L,
        MotionEvent.ACTION_UP, v.getWidth() / 2f + dx, v.getHeight() / 2f + dy, 0));
    idle();
  }

  /**
   * The drag layer is the Java mirror of and_blit: ONE uniform cover
   * scale, the window slides only along the CROPPED axis. On the portrait
   * test display with the 320x224 raster the height FILLS (availY == 0)
   * and the width is cropped — so a horizontal drag must move the offset
   * (the window BOX translates over the fixed full-frame scene) and a
   * vertical drag must be INERT.
   */
  @Test
  public void horizontalDragPansVerticalStaysInert() {
    Sh1CropOverlayView v = overlay();
    v.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(360,
            android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(800,
            android.view.View.MeasureSpec.EXACTLY));
    v.layout(0, 0, 360, 800);

    assertEquals(0f, v.getOffsetX(), 1e-6f);
    assertEquals(0f, v.getOffsetY(), 1e-6f);

    // a pure HORIZONTAL drag: the cropped axis responds
    drag(v, 120f, 0f);
    assertTrue("horizontal drag must pan the crop window (offset > 0)",
        v.getOffsetX() > 0.05f);
    assertEquals("vertical drag on the filling axis must be inert",
        0f, v.getOffsetY(), 1e-6f);
  }

  /**
   * Extreme drags CLAMP the window inside the framebuffer (the same
   * clamping and_blit applies — the window can never show outside the
   * rendered scene).
   */
  @Test
  public void extremeDragsClamp() {
    Sh1CropOverlayView v = overlay();
    v.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(360,
            android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(800,
            android.view.View.MeasureSpec.EXACTLY));
    v.layout(0, 0, 360, 800);

    drag(v, 100000f, 0f);
    assertEquals("far-right drag clamps at the right edge",
        1.0f, v.getOffsetX(), 1e-6f);

    drag(v, -200000f, 0f);
    assertEquals("far-left drag clamps at the left edge",
        -1.0f, v.getOffsetX(), 1e-6f);
  }

  /**
   * A released drag persists the offsets through the editor's canonical
   * pref path — the state the wallpaper engine re-assertes at session end
   * (the REAL 2D crop on the phone).
   */
  @Test
  public void releasedDragPersistsOffsets() {
    Sh1CropOverlayView v = overlay();
    v.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(360,
            android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(800,
            android.view.View.MeasureSpec.EXACTLY));
    v.layout(0, 0, 360, 800);

    float before = v.getOffsetX();
    drag(v, 150f, 0f);
    float after = v.getOffsetX();
    assertTrue("the drag must have moved the offset", after > before);

    SharedPreferences prefs = new xyz.zedler.patrick.doodle.util.PrefsUtil(activity)
        .checkForMigrations().getSharedPrefs();
    // prefs.apply() lands asynchronously — drain bounded frames until it
    // does (or fail after a generous window)
    for (int i = 0; i < 40; i++) {
      if (Math.abs(prefs.getFloat(Constants.PREF.SH1_CROP_X, 0f) - after) < 1e-4f) {
        break;
      }
      idle();
    }
    assertEquals("the released drag must persist X",
        after, prefs.getFloat(Constants.PREF.SH1_CROP_X, 0f), 1e-4f);
  }

  /* ---- universal back in the editor (v10.3, kept) ------------------------ */

  private ViewFlipper flipper() {
    ViewFlipper f = activity.findViewById(R.id.flipper_editor_pages);
    assertNotNull(f);
    return f;
  }

  @Test
  public void backFromCleanTracksFinishes() {
    assertEquals(0, flipper().getDisplayedChild());
    assertFalse(activity.isFinishing());

    activity.getOnBackPressedDispatcher().onBackPressed();
    idle();

    assertTrue("back from a clean TRACKS page must finish the editor — "
        + "never silent, never dead", activity.isFinishing());
  }

  @Test
  public void backFromDeeperPageStepsToTracksThenFinishes() {
    BottomNavigationView nav = activity.findViewById(R.id.nav_editor);
    assertNotNull(nav);
    nav.setSelectedItemId(R.id.menu_editor_world);
    idle();
    assertEquals(2, flipper().getDisplayedChild());
    assertFalse(activity.isFinishing());

    // back: the editor's own state-driven guard runs FIRST (LIFO) — it
    // steps back to the TRACKS page, NOT out of the editor
    activity.getOnBackPressedDispatcher().onBackPressed();
    idle();
    assertEquals("back must step to the TRACKS page first",
        0, flipper().getDisplayedChild());
    assertFalse(activity.isFinishing());

    // second back: the terminal callback — a guaranteed, visible exit
    activity.getOnBackPressedDispatcher().onBackPressed();
    idle();
    assertTrue("the second back must finish the editor",
        activity.isFinishing());
  }

  @Test
  public void terminalBackCallbackAlwaysRegistered() {
    // the invariant that keeps the platform bridge permanently registered
    assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
  }
}
