package xyz.zedler.patrick.doodle.util;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.service.Sh1WallpaperService;

/**
 * v9.9: the rendering-backend boot setting regression tests.
 *
 * The SOFTWARE renderer must be the DEFAULT (DEF.SH1_HW_BACKEND == 0) and
 * getBootBackend must clamp every out-of-range value back to software —
 * the hardware backends (1 = OpenGL ES, 2 = Vulkan) are strictly opt-in
 * (the experimental-toggle mandate; a corrupted pref must never force a
 * hardware backend on an unaware user).
 */
public class HwBackendPrefTest {

  /** minimal in-memory SharedPreferences fake (getInt only) */
  private static SharedPreferences fakePrefs(final int stored) {
    return new SharedPreferences() {
      private final Map<String, Object> vals = new HashMap<>();

      {
        vals.put(PREF.SH1_HW_BACKEND, stored);
      }

      @Override
      public int getInt(String key, int defValue) {
        Object v = vals.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
      }

      // ---- unused surface ----
      @Override public Map<String, ?> getAll() { return vals; }
      @Override public String getString(String key, String defValue) { return defValue; }
      @Override public long getLong(String key, long defValue) { return defValue; }
      @Override public float getFloat(String key, float defValue) { return defValue; }
      @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
      @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValue) { return defValue; }
      @Override public boolean contains(String key) { return vals.containsKey(key); }
      @Override public Editor edit() { return null; }
      @Override public void registerOnSharedPreferenceChangeListener(
          OnSharedPreferenceChangeListener l) {}
      @Override public void unregisterOnSharedPreferenceChangeListener(
          OnSharedPreferenceChangeListener l) {}
    };
  }

  @Test
  public void softwareIsTheDefault() {
    assertEquals("DEF.SH1_HW_BACKEND must be 0 (software, the accuracy source of truth)",
        0, DEF.SH1_HW_BACKEND);
  }

  @Test
  public void absentPrefBootsSoftware() {
    assertEquals(0, Sh1WallpaperService.getBootBackend(
        fakePrefs(Integer.MIN_VALUE) /* getInt falls to the default */));
  }

  @Test
  public void hardwareIdsPassThrough() {
    assertEquals(1, Sh1WallpaperService.getBootBackend(fakePrefs(1)));
    assertEquals(2, Sh1WallpaperService.getBootBackend(fakePrefs(2)));
  }

  @Test
  public void corruptValuesClampToSoftware() {
    assertEquals(0, Sh1WallpaperService.getBootBackend(fakePrefs(-1)));
    assertEquals(0, Sh1WallpaperService.getBootBackend(fakePrefs(3)));
    assertEquals(0, Sh1WallpaperService.getBootBackend(fakePrefs(99)));
    assertEquals(0, Sh1WallpaperService.getBootBackend(fakePrefs(Integer.MAX_VALUE)));
  }
}
