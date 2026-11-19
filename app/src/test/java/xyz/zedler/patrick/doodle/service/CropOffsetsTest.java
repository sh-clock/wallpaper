/*
 * v10.1 TASK 4 — the CROP-WINDOW OFFSET preference tests.
 *
 * getCropOffsets is the single read path for the user's crop-window pan
 * (normalized -1..1, 0 = the symmetric center crop — the authentic
 * default). It must survive a hand-edited / restored prefs file with
 * NaN or out-of-range values by falling back to the CENTER (never
 * propagating garbage into the engine's presentation seam).
 */
package xyz.zedler.patrick.doodle.service;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CropOffsetsTest {

  private SharedPreferences prefs;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    prefs = context.getSharedPreferences("crop_test", Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
  }

  @Test
  public void defaultsAreTheSymmetricCenter() {
    float[] crop = Sh1WallpaperService.getCropOffsets(prefs);
    assertEquals(DEF.SH1_CROP_X, crop[0], 0.0f);
    assertEquals(DEF.SH1_CROP_Y, crop[1], 0.0f);
    assertEquals(0.0f, crop[0], 0.0f);
    assertEquals(0.0f, crop[1], 0.0f);
  }

  @Test
  public void storedValuesRoundTrip() {
    prefs.edit()
        .putFloat(PREF.SH1_CROP_X, 0.25f)
        .putFloat(PREF.SH1_CROP_Y, -0.5f)
        .commit();
    float[] crop = Sh1WallpaperService.getCropOffsets(prefs);
    assertEquals(0.25f, crop[0], 0.0f);
    assertEquals(-0.5f, crop[1], 0.0f);
  }

  @Test
  public void boundariesPassThrough() {
    prefs.edit()
        .putFloat(PREF.SH1_CROP_X, 1.0f)
        .putFloat(PREF.SH1_CROP_Y, -1.0f)
        .commit();
    float[] crop = Sh1WallpaperService.getCropOffsets(prefs);
    assertEquals(1.0f, crop[0], 0.0f);
    assertEquals(-1.0f, crop[1], 0.0f);
  }

  @Test
  public void outOfRangeFallsBackToCenter() {
    prefs.edit()
        .putFloat(PREF.SH1_CROP_X, 1.5f)
        .putFloat(PREF.SH1_CROP_Y, -1.0001f)
        .commit();
    float[] crop = Sh1WallpaperService.getCropOffsets(prefs);
    assertEquals("over-range must fall back to the center", 0.0f, crop[0], 0.0f);
    assertEquals("under-range must fall back to the center", 0.0f, crop[1], 0.0f);
  }

  @Test
  public void nanFallsBackToCenter() {
    prefs.edit()
        .putFloat(PREF.SH1_CROP_X, Float.NaN)
        .putFloat(PREF.SH1_CROP_Y, Float.NaN)
        .commit();
    float[] crop = Sh1WallpaperService.getCropOffsets(prefs);
    assertEquals("NaN must fall back to the center", 0.0f, crop[0], 0.0f);
    assertEquals("NaN must fall back to the center", 0.0f, crop[1], 0.0f);
  }

  @Test
  public void extremeJunkFallsBackToCenter() {
    // a restored/migrated prefs file could carry anything
    prefs.edit()
        .putFloat(PREF.SH1_CROP_X, Float.NEGATIVE_INFINITY)
        .putFloat(PREF.SH1_CROP_Y, 1e20f)
        .commit();
    float[] crop = Sh1WallpaperService.getCropOffsets(prefs);
    assertEquals(0.0f, crop[0], 0.0f);
    assertEquals(0.0f, crop[1], 0.0f);
  }
}
