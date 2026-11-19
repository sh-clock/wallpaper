/*
 * v10.1 BUG 2 — the STANDALONE EDITOR SESSION asset tests.
 *
 * The reported black preview: the Camtrack editor was opened on a fresh
 * install with the wallpaper NEVER set. copyAssetsIfNeeded used to be
 * callable only from Sh1Engine.onSurfaceCreated (the wallpaper path —
 * which never ran), so the data dir lacked the engine's scene data and
 * Sh1Native.start() booted with nothing to render.
 *
 * The fix routes the editor's beginEditorPreview / updateEditorPreview
 * through the SAME idempotent copy, taking a plain Context. The test
 * proves the standalone path WITHOUT EVER STARTING THE SERVICE (exactly
 * the broken scenario): a plain application context must be enough.
 *
 * v10.6 DIRECT BOOT: the copy lands in the DEVICE-PROTECTED data dir
 * (Sh1Storage) — the storage that is writable before first unlock.
 *
 * NOTE: the whole flow runs as ONE test method on purpose — the copy's
 * sAssetsReady latch is process-global (correct in production, where
 * the data dirs are stable for the process lifetime), while Robolectric
 * gives every test method a FRESH data dir: separate methods would see
 * the latch set with an empty new dir.
 */
package xyz.zedler.patrick.doodle.service;

import android.content.Context;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import androidx.test.core.app.ApplicationProvider;
import xyz.zedler.patrick.doodle.util.Sh1Storage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EditorPreviewAssetsTest {

  private static final String[] ASSETS = {
      "THRF9FD.glb", "THRF9FD.IPD", "THR_GLB.PLM", "camdat.json"
  };

  /**
   * The standalone session lifecycle in one flow: first call copies
   * (fresh install, wallpaper never set), the second call is a no-op
   * (the editor's update path), and the copied camdat is real content.
   */
  @Test
  public void standaloneCopyCopiesAllEngineDataAndIsIdempotent() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    // the wallpaper service class is NEVER instantiated here — exactly
    // the "wallpaper not set" scenario from the bug report
    Sh1WallpaperService.copyAssetsIfNeeded(context);

    File dir = Sh1Storage.sh1Dir(context);
    assertTrue("the device-protected sh1 dir must exist after the copy",
        dir.isDirectory());
    File deFiles = Sh1Storage.storageContext(context).getFilesDir();
    assertTrue("the storage context must be device-protected (API 24+)",
        Sh1Storage.storageContext(context).isDeviceProtectedStorage());
    assertEquals("the engine data dir must live INSIDE the device-protected filesDir",
        deFiles.getAbsolutePath(),
        dir.getParentFile().getAbsolutePath());
    long[] sizes = new long[ASSETS.length];
    for (int i = 0; i < ASSETS.length; i++) {
      File f = new File(dir, ASSETS[i]);
      assertTrue("engine data missing: " + ASSETS[i], f.isFile());
      assertTrue("engine data empty: " + ASSETS[i], f.length() > 0);
      sizes[i] = f.length();
    }

    // the idempotent second call (the updateEditorPreview path) must not
    // rewrite or drop anything
    Sh1WallpaperService.copyAssetsIfNeeded(context);
    for (int i = 0; i < ASSETS.length; i++) {
      assertEquals("file rewritten on the idempotent path: " + ASSETS[i],
          sizes[i], new File(dir, ASSETS[i]).length());
    }

    // the copied camdat is the real v13 document the editor edits
    File camdat = new File(dir, "camdat.json");
    byte[] data = java.nio.file.Files.readAllBytes(camdat.toPath());
    String json = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
    assertTrue("camdat must be non-trivial", json.length() > 100);
    assertTrue("camdat must be a JSON object", json.startsWith("{"));
    assertTrue("camdat must contain models/tracks",
        json.contains("models") || json.contains("tracks"));
  }
}
