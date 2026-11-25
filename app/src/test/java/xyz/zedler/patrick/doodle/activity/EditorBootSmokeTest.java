package xyz.zedler.patrick.doodle.activity;

import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLegacyChoreographer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.LEGACY)
public class EditorBootSmokeTest {

  /**
   * THE FRAME-CALLBACK DELAY (the s26/V52 unit-hang fix).
   *
   * The editor's Choreographer frameTick re-posts itself guarded only by
   * "frame time advanced" (the v10.3 guard, CamtrackEditorActivity ~:311) —
   * correct on a real display, where each doFrame arrives on a new vsync.
   * ShadowLegacyChoreographer.getFrameTimeNanos() BUMPS its synthetic clock
   * by one FRAME_INTERVAL on every call, so the advancing-time guard never
   * fires and a zero-delay re-post chain spins the legacy scheduler at
   * 100% CPU forever (the 6h testDebugUnitTest hang, run 45364114419).
   *
   * This was LATENT until the v48 batch: pre-v48 the boot chain died on
   * its own — the shadow clock starts at 0, so doFrame #1 ties "0 > 0"
   * and the guard kills the first re-post, and nothing re-armed it (the
   * editor had NO onResume re-post). WAJ-14 (e034eec) added onResume()
   * -> postFrameTick() (CamtrackEditorActivity:324): the second post's
   * bumped synthetic timestamp passes the guard and the chain spins.
   * (The pre-#24 MaterialSlider throw never fired at boot — trackTime
   * starts 0, the playhead 0 is in range; WAJ-1's clamp is unrelated to
   * the un-masking.)
   *
   * Robolectric's own remedy (ShadowLegacyChoreographer's doc): give the
   * frame callback a nonzero delay so the same-drain re-post chain breaks.
   * Production code stays untouched — on device the delay parameter is a
   * no-op of the real Choreographer's postFrameCallback path.
   */
  @Before
  public void setFrameCallbackDelay() {
    ShadowLegacyChoreographer.setPostFrameCallbackDelay(16);
  }

  @Test
  public void activityBoots() {
    ActivityController<CamtrackEditorActivity> c =
        Robolectric.buildActivity(CamtrackEditorActivity.class).setup();
    System.out.println("booted: " + (c.get() != null));
    c.destroy();
  }
}
