/*
 * v10.1 Phase G — GLOBAL BACK-NAVIGATION tests.
 *
 * The v10.0 suite verified only the trampoline INTENT FLAGS — never an
 * actual back press dispatched through the NavController back stack.
 * These tests drive the REAL entry point: the activity's
 * OnBackPressedDispatcher — the exact call the system's
 * OnBackInvokedCallback bridge makes on API 33+ (and that
 * Activity.onBackPressed() makes on older APIs), so a pass here means
 * the same Java-side wiring a real device exercises works.
 *
 * Coverage matrix (every nav_main.xml destination):
 *   overview -> settings / parallax / appearance / other / about
 *   other    -> log (a second stack level)
 *   root     -> finish via the fallback (no enabled callbacks)
 *   toolbar  -> navigateUp() (the toolbar arrow path)
 *   sheets   -> dispatcher back dismisses a modal bottom sheet
 */
package xyz.zedler.patrick.doodle.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import xyz.zedler.patrick.doodle.Constants;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.fragment.OtherFragmentDirections;
import xyz.zedler.patrick.doodle.fragment.OverviewFragmentDirections;
import xyz.zedler.patrick.doodle.fragment.dialog.LanguagesBottomSheetDialogFragment;
import xyz.zedler.patrick.doodle.util.ViewUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
// sdk 34: a real modern device level (predictive-back era). The theme is
// pinned to a STATIC value below so the Material3 dynamic-color overlay
// path (the documented Robolectric legacy resource bridge limitation,
// see TrampolineBackFixTest) never engages.
@Config(sdk = 34)
public class BackNavigationTest {

  private ActivityController<MainActivity> controller;
  private MainActivity activity;
  private NavController navController;

  @Before
  public void setUp() {
    // pin a STATIC theme (red) — avoids DynamicColors.applyToActivityIfAvailable
    Context deviceCtx = ApplicationProvider.getApplicationContext()
        .createDeviceProtectedStorageContext();
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(deviceCtx);
    prefs.edit().putString(Constants.PREF.THEME, Constants.THEME.RED).commit();

    controller = Robolectric.buildActivity(MainActivity.class).setup();
    activity = controller.get();

    NavHostFragment navHost = (NavHostFragment) activity.getSupportFragmentManager()
        .findFragmentById(R.id.fragment_main_nav_host);
    assertNotNull("NavHostFragment must be in the activity's support FM", navHost);
    navController = navHost.getNavController();
    assertNotNull(navController);
    idle();
  }

  @After
  public void tearDown() {
    if (controller != null) {
      controller.destroy();
    }
  }

  private static void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  /** the system-back entry point on EVERY API level (bridge target) */
  private void pressSystemBack() {
    activity.getOnBackPressedDispatcher().onBackPressed();
    idle();
  }

  private int currentDestinationId() {
    assertNotNull(navController.getCurrentDestination());
    return navController.getCurrentDestination().getId();
  }

  private void assertAtOverview() {
    assertEquals(R.id.overviewFragment, currentDestinationId());
  }

  @Test
  public void startsAtOverview() {
    assertAtOverview();
  }

  @Test
  public void backPopsSettingsToOverview() {
    activity.navigate(OverviewFragmentDirections.actionOverviewToSettings());
    idle();
    assertEquals(R.id.settingsFragment, currentDestinationId());
    assertFalse(activity.isFinishing());

    pressSystemBack();
    assertAtOverview();
    assertFalse("back must POP, not finish, from a stacked destination",
        activity.isFinishing());
  }

  @Test
  public void backPopsParallaxToOverview() {
    activity.navigate(OverviewFragmentDirections.actionOverviewToParallax());
    idle();
    assertEquals(R.id.parallaxFragment, currentDestinationId());

    pressSystemBack();
    assertAtOverview();
    assertFalse(activity.isFinishing());
  }

  @Test
  public void backPopsAppearanceToOverview() {
    activity.navigate(OverviewFragmentDirections.actionOverviewToAppearance());
    idle();
    assertEquals(R.id.appearanceFragment, currentDestinationId());

    pressSystemBack();
    assertAtOverview();
    assertFalse(activity.isFinishing());
  }

  @Test
  public void backPopsOtherToOverview() {
    activity.navigate(OverviewFragmentDirections.actionOverviewToOther());
    idle();
    assertEquals(R.id.otherFragment, currentDestinationId());

    pressSystemBack();
    assertAtOverview();
    assertFalse(activity.isFinishing());
  }

  @Test
  public void backPopsAboutToOverview() {
    activity.navigate(OverviewFragmentDirections.actionOverviewToAbout());
    idle();
    assertEquals(R.id.aboutFragment, currentDestinationId());

    pressSystemBack();
    assertAtOverview();
    assertFalse(activity.isFinishing());
  }

  @Test
  public void backPopsLogToOtherToOverview() {
    activity.navigate(OverviewFragmentDirections.actionOverviewToOther());
    idle();
    activity.navigate(OtherFragmentDirections.actionOtherToLog());
    idle();
    assertEquals(R.id.logFragment, currentDestinationId());

    pressSystemBack();
    assertEquals(R.id.otherFragment, currentDestinationId());
    assertFalse(activity.isFinishing());

    pressSystemBack();
    assertAtOverview();
    assertFalse(activity.isFinishing());
  }

  @Test
  public void backAtRootFinishesThroughFallback() {
    assertAtOverview();
    // no enabled callbacks at the start destination: the dispatcher's
    // fallback must route the press to the default handling = finish
    pressSystemBack();
    assertTrue("back at the start destination must finish the activity",
        activity.isFinishing());
  }

  /**
   * v10.3 BUG 2 — the TERMINAL back callback invariant: it is ALWAYS
   * ENABLED (even at the root destination), so the OnBackPressedDispatcher
   * ALWAYS has an enabled callback while the activity lives. This is what
   * keeps the platform bridge (the window's OnBackInvokedCallback on API
   * 33+, registered/unregistered by updateEnabledCallbacks only on
   * enabled-state TRANSITIONS in androidx.activity 1.9.x) PERMANENTLY
   * REGISTERED — the system can never fall into the opted-in-but-
   * unregistered state where back presses are silently dropped.
   */
  @Test
  public void terminalCallbackKeepsBridgeRegisteredAtRoot() {
    assertAtOverview();
    assertTrue("the terminal callback must keep an enabled callback at the "
        + "root destination (the platform bridge stays registered)",
        activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
    // and it still TERMINATES visibly: finish, never silent
    pressSystemBack();
    assertTrue(activity.isFinishing());
  }

  /**
   * v10.3: the LEGACY system entry point — ComponentActivity.onBackPressed()
   * is what the framework calls on API < 33 (and what the platform bridge's
   * OnBackInvokedCallback routes into on 33+). Exercising it proves the
   * full ComponentActivity->dispatcher->callback chain on a stacked
   * destination AND at root.
   */
  @Test
  public void componentActivityOnBackPressedPopsThenFinishes() {
    activity.navigate(OverviewFragmentDirections.actionOverviewToSettings());
    idle();
    assertEquals(R.id.settingsFragment, currentDestinationId());

    activity.onBackPressed();   // the real framework entry (deprecated but live)
    idle();
    assertAtOverview();
    assertFalse(activity.isFinishing());

    activity.onBackPressed();
    idle();
    assertTrue("back at root through the framework entry must finish",
        activity.isFinishing());
  }

  @Test
  public void toolbarNavigateUpPopsToOverview() {
    activity.navigate(OverviewFragmentDirections.actionOverviewToSettings());
    idle();
    assertEquals(R.id.settingsFragment, currentDestinationId());

    // the toolbar arrow path (BaseFragment.getNavigationOnClickListener)
    activity.navigateUp();
    idle();
    assertAtOverview();
    assertFalse(activity.isFinishing());
  }

  @Test
  public void backDismissesModalBottomSheet() {
    assertAtOverview();
    LanguagesBottomSheetDialogFragment sheet = new LanguagesBottomSheetDialogFragment();
    ViewUtil.showBottomSheet(activity, sheet);
    idle();
    assertTrue("sheet must be showing", sheet.getDialog() != null && sheet.getDialog().isShowing());

    // a showing modal sheet must consume back (dismiss) — never swallow
    // it silently and never let it finish the activity underneath
    pressSystemBack();

    boolean dismissed = sheet.getDialog() == null || !sheet.getDialog().isShowing();
    assertTrue("back must dismiss the modal sheet", dismissed);
    assertFalse("back consumed by the sheet must NOT finish the activity",
        activity.isFinishing());
    // the nav destination beneath the sheet is untouched
    assertAtOverview();
  }

  @Test
  public void dialogWindowBackDismissesModalBottomSheet() {
    assertAtOverview();
    LanguagesBottomSheetDialogFragment sheet = new LanguagesBottomSheetDialogFragment();
    ViewUtil.showBottomSheet(activity, sheet);
    idle();
    assertTrue("sheet must be showing", sheet.getDialog() != null && sheet.getDialog().isShowing());

    // the PRIMARY sheet-back path below API 33 (and the base layer on
    // every API): the focused DIALOG window receives KEYCODE_BACK ->
    // Dialog.onBackPressed() -> cancel() -> sheet collapse -> dismiss
    android.app.Dialog dialog = sheet.getDialog();
    assertNotNull(dialog);
    dialog.dispatchKeyEvent(new android.view.KeyEvent(
        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
    dialog.dispatchKeyEvent(new android.view.KeyEvent(
        android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
    idle();
    // give the collapse->dismiss callback a looper pass if posted
    Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks();

    boolean dismissed = sheet.getDialog() == null || !sheet.getDialog().isShowing();
    assertTrue("dialog-window back must dismiss the modal sheet", dismissed);
    assertFalse(activity.isFinishing());
    assertAtOverview();
  }

  @Test
  public void sheetBackInterceptRemovedAfterDismiss() {
    assertAtOverview();
    LanguagesBottomSheetDialogFragment sheet = new LanguagesBottomSheetDialogFragment();
    ViewUtil.showBottomSheet(activity, sheet);
    idle();

    pressSystemBack();   // dismiss via the interception
    idle();
    Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks();

    // after dismissal the interception is GONE: back must reach the nav
    // layer again (pop/finish), not be swallowed by a dead sheet
    // navigate somewhere so the assertion is about POP, not finish
    activity.navigate(OverviewFragmentDirections.actionOverviewToSettings());
    idle();
    pressSystemBack();
    assertAtOverview();
    assertFalse(activity.isFinishing());
  }
}
