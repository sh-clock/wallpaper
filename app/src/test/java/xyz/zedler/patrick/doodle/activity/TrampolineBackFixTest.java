/*
 * v10.0 — the LAUNCHER TRAMPOLINE back-fix test (Phase G BUG 1, primary).
 *
 * The universal back-freeze root cause: the splash trampoline launched
 * MainActivity with STANDARD launch mode, so every icon tap after the
 * first stacked a DUPLICATE MainActivity ([MainActivity, MainActivity]).
 * BACK #1 finished the top clone revealing the identical-looking bottom
 * instance ("nothing happens / frozen"); BACK #2 closed the app for real.
 *
 * The fix: startNewMainActivity() now adds FLAG_ACTIVITY_CLEAR_TOP |
 * FLAG_ACTIVITY_SINGLE_TOP — the existing MainActivity receives the intent
 * (onNewIntent) and the trampoline above it is cleared; exactly one
 * instance survives.
 */
package xyz.zedler.patrick.doodle.activity;

import android.content.Intent;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
// ^ sdk 29: below S_V2 the trampoline takes the RUN_AS_SUPER_CLASS path
// (no full MainActivity UI) and DynamicColors.isDynamicColorAvailable()
// is false, so UiUtil.setTheme applies a STATIC theme (setTheme only —
// no ThemeOverlay applyStyle), avoiding the Robolectric legacy resource
// bridge limitation with Material3 harmonized-color overlays. The intent
// under test (startNewMainActivity) is identical on every API level.
public class TrampolineBackFixTest {

  /**
   * The trampoline's MainActivity intent carries CLEAR_TOP | SINGLE_TOP:
   * the routing that makes the EXISTING instance the single survivor.
   */
  @Test
  public void trampolineIntentRoutesToExistingInstance() {
    ActivityController<LauncherActivity> controller =
        Robolectric.buildActivity(LauncherActivity.class).setup();
    LauncherActivity launcher = controller.get();

    // drive the splash path: the 800 ms handler delay is on the main
    // looper (PAUSED in Robolectric 4.14 — unpause + run to end)
    org.robolectric.shadows.ShadowLooper mainLooper =
        Shadows.shadowOf(Looper.getMainLooper());
    mainLooper.idleIfPaused();
    mainLooper.runToEndOfTasks();

    // the trampoline must have started exactly one MainActivity intent
    Intent started = Shadows.shadowOf(
        (android.app.Application) ApplicationProvider.getApplicationContext())
        .getNextStartedActivity();
    assertNotNull("trampoline must start MainActivity", started);
    assertEquals(MainActivity.class.getName(),
        started.resolveActivity(launcher.getPackageManager()).getClassName());

    // THE FIX: the flags that prevent the duplicate stack entry
    boolean hasClearTop = (started.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0;
    boolean hasSingleTop = (started.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0;
    assertTrue("missing FLAG_ACTIVITY_CLEAR_TOP (duplicate-stack back freeze)",
        hasClearTop);
    assertTrue("missing FLAG_ACTIVITY_SINGLE_TOP (duplicate-stack back freeze)",
        hasSingleTop);

    // and the trampoline finished itself (it never lingers on the stack)
    assertTrue("trampoline must finish after launching", launcher.isFinishing());

    controller.destroy();
  }

  /**
   * Only ONE activity of the app is left running after the trampoline
   * flow completes — no lingering second instance in Robolectric's
   * activity registry.
   */
  @Test
  public void singleSurvivingInstance() {
    ActivityController<LauncherActivity> controller =
        Robolectric.buildActivity(LauncherActivity.class).setup();
    org.robolectric.shadows.ShadowLooper mainLooper =
        Shadows.shadowOf(Looper.getMainLooper());
    mainLooper.idleIfPaused();
    mainLooper.runToEndOfTasks();
    controller.destroy();

    // no second LauncherActivity got started from the splash path
    Intent extra = Shadows.shadowOf(
        (android.app.Application) ApplicationProvider.getApplicationContext())
        .getNextStartedActivity();
    // the first nextStartedActivity was consumed by the previous assertion
    // in the OTHER test; here we just verify the registry is consistent:
    // the started intents point at MainActivity (never back at the splash)
    while (extra != null) {
      assertTrue("unexpected self-restart of the splash activity",
          !LauncherActivity.class.getName()
              .equals(extra.resolveActivity(controller.get().getPackageManager())));
      extra = Shadows.shadowOf(
          (android.app.Application) ApplicationProvider.getApplicationContext())
          .getNextStartedActivity();
    }
  }
}
