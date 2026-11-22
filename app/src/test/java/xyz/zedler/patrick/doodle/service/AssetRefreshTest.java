/*
 * V42 E-1 (the gate form) — the ASSET REFRESH upgrade-path tests.
 *
 * The reported bug: copyAssetsIfNeeded skipped every existing data-dir
 * file forever, so an APK update that CHANGES a bundled asset (the
 * v1.7.0 night-only camdat re-derivation; the v1.8.0 DR01FE drop-in)
 * never reached upgraders. The gate's form: a version-keyed refresh with
 * the USER-DATA guard (a .asset_hashes manifest; a data-dir file whose
 * hash still matches what we copied is OURS -> refresh; one that differs
 * is USER data -> preserved).
 *
 * The whole flow runs as ONE test method on purpose — the sAssetsReady
 * latch is process-global (see EditorPreviewAssetsTest's NOTE) while
 * Robolectric gives every method a fresh data dir. V43 (the v1.10.0
 * red-CI fix): the latch is reset before EVERY copy invocation via the
 * @VisibleForTesting hook, so (a) the class is order-independent against
 * EditorPreviewAssetsTest and (b) every phase below exercises the REAL
 * guard logic (version marker + hash manifest) instead of short-
 * circuiting on the latch — the phase-3 refresh previously could not
 * have run at all with the latch left set by phase 1.
 */
package xyz.zedler.patrick.doodle.service;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import androidx.test.core.app.ApplicationProvider;
import xyz.zedler.patrick.doodle.util.Sh1Storage;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AssetRefreshTest {

  private static final String GLB = "THRF9FD.glb";
  private static final String CAMDAT = "camdat.json";

  private static byte[] apkBytes(Context context, String name) throws Exception {
    try (InputStream in = context.getAssets().open("sh1/" + name)) {
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        bos.write(buf, 0, n);
      }
      return bos.toByteArray();
    }
  }

  private static byte[] fileBytes(File f) throws Exception {
    byte[] out = new byte[(int) f.length()];
    try (java.io.DataInputStream di = new java.io.DataInputStream(
        new java.io.FileInputStream(f))) {
      di.readFully(out);
    }
    return out;
  }

  private static void writeFile(File f, byte[] data) throws Exception {
    try (FileOutputStream os = new FileOutputStream(f)) {
      os.write(data);
    }
  }

  private static long readMarker(File marker) throws Exception {
    try (java.io.DataInputStream di = new java.io.DataInputStream(
        new java.io.FileInputStream(marker))) {
      return di.readLong();
    }
  }

  /**
   * The upgrade path in one flow:
   * (1) first install copies everything + writes the markers;
   * (2) a same-version rebind is a no-op (idempotence);
   * (3) an UPDATE (the version marker tampered to an older value, the
   *     exact signal the real versionCode change gives):
   *     - a deleted GLB is re-copied,
   *     - a GLB WE corrupted-but-recorded (hash == manifest? no — a
   *       MISSING file) is re-copied,
   *     - a USER-MODIFIED file (hash != the manifest) is PRESERVED,
   *     - our unmodified copies are refreshed (byte == the APK);
   * (4) the no-manifest transition: with .asset_hashes removed, a forced
   *     refresh re-copies the corpus AND backs up a user camdat.json.
   */
  @Test
  public void upgradePathRefreshesStaleAssetsAndPreservesUserData() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Sh1WallpaperService.resetAssetsReadyForTest();
    Sh1WallpaperService.copyAssetsIfNeeded(context);
    File dir = Sh1Storage.sh1Dir(context);
    assertTrue(dir.isDirectory());

    // (1) the markers exist and the first copy is APK content
    File marker = new File(dir, ".asset_version");
    File manifest = new File(dir, ".asset_hashes");
    assertTrue(marker.isFile());
    assertTrue(manifest.isFile());
    assertTrue(readMarker(marker) > 0);
    File glb = new File(dir, GLB);
    assertArrayEquals(apkBytes(context, GLB), fileBytes(glb));

    // (2) same-version no-op: the rebind runs the REAL guard (the version
    //     marker matches, every file present) and returns WITHOUT the
    //     rewrite — not the latch shortcut (V43: the latch is reset so
    //     this actually reaches the guard)
    long glbMtime = glb.lastModified();
    Sh1WallpaperService.resetAssetsReadyForTest();
    Sh1WallpaperService.copyAssetsIfNeeded(context);
    assertEquals(glbMtime, glb.lastModified());

    // (3) the UPDATE: simulate the versionCode change by stamping an
    //     older version into the marker (the refresh trigger), with the
    //     user having (a) deleted a GLB, (b) edited the camdat
    java.io.DataOutputStream mo = new java.io.DataOutputStream(
        new java.io.FileOutputStream(marker));
    mo.writeLong(1L);
    mo.close();
    assertTrue(new File(dir, "SU0203.glb").delete());      // a deleted asset
    File camdat = new File(dir, CAMDAT);
    writeFile(camdat, "{\"user\":true}".getBytes(StandardCharsets.UTF_8));

    Sh1WallpaperService.resetAssetsReadyForTest();
    Sh1WallpaperService.copyAssetsIfNeeded(context);

    // the deleted GLB is back and byte-identical to the APK
    File su = new File(dir, "SU0203.glb");
    assertTrue(su.isFile());
    assertArrayEquals(apkBytes(context, "SU0203.glb"), fileBytes(su));
    // our unmodified GLB was refreshed (== the APK content; the mtime moved)
    assertArrayEquals(apkBytes(context, GLB), fileBytes(glb));
    // the USER camdat survived the refresh VERBATIM
    assertEquals("{\"user\":true}",
        new String(fileBytes(camdat), StandardCharsets.UTF_8));
    // the markers were rewritten at the current version
    assertTrue(readMarker(marker) > 1L);

    // (4) the no-manifest transition: drop the manifest, stamp an old
    //     version, keep the user camdat in place — the corpus refreshes
    //     AND the user camdat is backed up (then it TOO refreshes: the
    //     transition has no proof of ownership — the documented,
    //     recoverable one-time cost)
    assertTrue(manifest.delete());
    mo = new java.io.DataOutputStream(new java.io.FileOutputStream(marker));
    mo.writeLong(1L);
    mo.close();
    Sh1WallpaperService.resetAssetsReadyForTest();
    Sh1WallpaperService.copyAssetsIfNeeded(context);
    File bak = new File(dir, "camdat.user.bak");
    assertTrue("the user camdat must be backed up at the transition", bak.isFile());
    assertEquals("{\"user\":true}",
        new String(fileBytes(bak), StandardCharsets.UTF_8));
    assertArrayEquals(apkBytes(context, CAMDAT), fileBytes(camdat));
    assertFalse(manifest.isDirectory());
    assertTrue(manifest.isFile());
  }
}
