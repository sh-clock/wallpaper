/*
 * v10.0 — the resolution-aware DITHER POLICY tests (Phase H Task 3).
 *
 * The policy under test (Sh1WallpaperService.getEffectiveDither):
 *   - user never touched the Dither toggle:
 *       resolution ladder 0 (native 320x224)  -> dither ON  (authentic)
 *       any higher resolution                 -> dither OFF (automatic)
 *   - user explicitly toggled Dither (USER_SET latched):
 *       their choice is respected at EVERY resolution.
 *   - getBootDither: ON -> 1 (pass NO flag, the authentic engine chain);
 *                    OFF -> 0 (the engine's own --no-dither).
 */
package xyz.zedler.patrick.doodle.service;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.Constants.SH1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class DitherPolicyTest {

  private SharedPreferences prefs;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    prefs = context.getSharedPreferences("dither_test", Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
  }

  private void setRes(int idx) {
    prefs.edit().putInt(PREF.SH1_RESOLUTION, idx).commit();
  }

  private void setUser(boolean ditherOn) {
    prefs.edit().putBoolean(PREF.SH1_DITHER, ditherOn)
        .putBoolean(PREF.SH1_DITHER_USER_SET, true).commit();
  }

  /* ---- the automatic rule (user never touched the toggle) ---- */

  @Test
  public void autoRule_nativeResolution_ditherOn() {
    setRes(SH1.RES_AUTHENTIC);   // 320x224
    assertTrue(Sh1WallpaperService.getEffectiveDither(prefs));
    assertEquals(1, Sh1WallpaperService.getBootDither(prefs));
  }

  @Test
  public void autoRule_anyUpscale_ditherOff() {
    for (int idx = 1; idx < SH1.RES_COUNT; idx++) {
      prefs.edit().clear().commit();
      setRes(idx);
      assertFalse("dither must be auto-OFF at ladder index " + idx,
          Sh1WallpaperService.getEffectiveDither(prefs));
      assertEquals(0, Sh1WallpaperService.getBootDither(prefs));
    }
  }

  @Test
  public void autoRule_defaultPrefState_isNativeOn() {
    // no prefs written at all: default resolution 0 -> ON
    assertTrue(Sh1WallpaperService.getEffectiveDither(prefs));
  }

  /* ---- the explicit user choice (respected at every resolution) ---- */

  @Test
  public void userChoice_on_respectedAtHighResolution() {
    setRes(SH1.RES_COUNT - 1);   // the highest upscale
    setUser(true);
    assertTrue(Sh1WallpaperService.getEffectiveDither(prefs));
    assertEquals(1, Sh1WallpaperService.getBootDither(prefs));
  }

  @Test
  public void userChoice_off_respectedAtNativeResolution() {
    setRes(SH1.RES_AUTHENTIC);
    setUser(false);
    assertFalse(Sh1WallpaperService.getEffectiveDither(prefs));
    assertEquals(0, Sh1WallpaperService.getBootDither(prefs));
  }

  @Test
  public void userChoice_survivesResolutionChanges() {
    setUser(true);
    for (int idx = 0; idx < SH1.RES_COUNT; idx++) {
      setRes(idx);
      assertTrue(Sh1WallpaperService.getEffectiveDither(prefs));
    }
    setUser(false);
    for (int idx = 0; idx < SH1.RES_COUNT; idx++) {
      setRes(idx);
      assertFalse(Sh1WallpaperService.getEffectiveDither(prefs));
    }
  }

  /* ---- out-of-range robustness (clamped like the boot resolution) ---- */

  @Test
  public void garbageResolution_clampsToAutoRule() {
    prefs.edit().putInt(PREF.SH1_RESOLUTION, 999).commit();
    assertFalse(Sh1WallpaperService.getEffectiveDither(prefs));
    prefs.edit().putInt(PREF.SH1_RESOLUTION, -5).commit();
    assertTrue(Sh1WallpaperService.getEffectiveDither(prefs));
  }

  /* ---- the DEF constants document the shipped defaults ---- */

  @Test
  public void defaults_documented() {
    assertTrue(DEF.SH1_DITHER);           // authentic ON at native
    assertFalse(DEF.SH1_DITHER_USER_SET); // the auto rule governs first
    assertEquals(0, DEF.SH1_RESOLUTION);  // native raster default
  }
}
