/*
 * v10.6 DIRECT BOOT (BFU) — the regression tests for the before-first-unlock
 * contract.
 *
 * The reported defect: right after a reboot, BEFORE the user unlocked the
 * phone, the wallpaper showed black and the system raised "Fog Wallpaper
 * has stopped". Root cause: the service is android:directBootAware (the
 * system binds it at BFU), but the SH1 engine data lived in the
 * credential-encrypted filesDir, where the asset copy threw a
 * RuntimeException. Prefs were already device-protected (upstream Doodle's
 * PrefsUtil) — only the engine data home was wrong.
 *
 * These tests pin the v10.6 contract:
 *   1. the manifest keeps the service direct-boot-aware;
 *   2. every engine-visible path resolves under DEVICE-PROTECTED storage;
 *   3. a legacy credential-encrypted install migrates into the
 *      device-protected home, with the legacy file winning over a
 *      same-named DE twin (the bundled fallback a pre-migration BFU boot
 *      may have copied);
 *   4. the migration is idempotent and never throws.
 */
package xyz.zedler.patrick.doodle.service;

import android.content.Context;
import android.os.UserManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import androidx.test.core.app.ApplicationProvider;
import xyz.zedler.patrick.doodle.util.Sh1Storage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DirectBootTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  /* ---- 1. the manifest contract -------------------------------------- */

  /**
   * Robolectric's PackageManager does not map android:directBootAware
   * into ServiceInfo.flags (probed: always 0x0), so the manifest contract
   * is pinned against the SOURCE manifest — the one aapt2 compiles into
   * the APK. The build-time badging audit re-verifies the shipped binary.
   */
  @Test
  public void wallpaperServiceIsDirectBootAware() throws Exception {
    File manifest = new File("src/main/AndroidManifest.xml");
    assumeTrue("source manifest not visible from the test working dir",
        manifest.isFile());
    Document doc = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(new FileInputStream(manifest));
    NodeList services = doc.getElementsByTagName("service");
    Element target = null;
    for (int i = 0; i < services.getLength(); i++) {
      Element el = (Element) services.item(i);
      if (el.getAttribute("android:name").endsWith(".Sh1WallpaperService")) {
        target = el;
        break;
      }
    }
    assertNotNull("the manifest must declare Sh1WallpaperService", target);
    assertEquals("the wallpaper service MUST stay android:directBootAware — "
            + "without it the wallpaper cannot render before first unlock",
        "true", target.getAttribute("android:directBootAware"));
  }

  /* ---- 2. every engine-visible path is device-protected -------------- */

  @Test
  public void storageContextIsDeviceProtected() {
    Context storage = Sh1Storage.storageContext(context);
    assertTrue("the storage context must report device-protected storage",
        storage.isDeviceProtectedStorage());
  }

  @Test
  public void enginePathsLiveUnderDeviceProtectedFilesDir() {
    File deFiles = Sh1Storage.storageContext(context).getFilesDir();
    assertEquals("sh1Dir must sit in the device-protected filesDir",
        new File(deFiles, "sh1").getAbsolutePath(),
        Sh1Storage.sh1Dir(context).getAbsolutePath());
    assertEquals("thumbnailsDir must sit in the device-protected filesDir",
        new File(deFiles, "thumbnails").getAbsolutePath(),
        Sh1Storage.thumbnailsDir(context).getAbsolutePath());
    assertEquals("engineDataPath must BE the device-protected filesDir",
        deFiles.getAbsolutePath(), Sh1Storage.engineDataPath(context));
    assertEquals("thumbnailFile must sit in the device-protected thumbnails",
        new File(new File(deFiles, "thumbnails"), "X_t0.png").getAbsolutePath(),
        Sh1Storage.thumbnailFile(context, "X", 0).getAbsolutePath());
  }

  @Test
  public void deviceProtectedFilesDirDiffersFromCredentialEncrypted() {
    // the whole migration story only exists because the two homes differ;
    // pin that Robolectric models them as distinct directories
    assertNotEquals("DE and CE filesDir must be distinct roots",
        Sh1Storage.storageContext(context).getFilesDir().getAbsolutePath(),
        context.getFilesDir().getAbsolutePath());
  }

  /* ---- 3. the legacy CE -> DE migration ------------------------------- */

  /**
   * A pre-v10.6 install keeps its engine data (custom camdat, imported
   * GLBs) in the CE filesDir. The migration must move it into DE — and the
   * legacy file WINS over a same-named DE twin, because a BFU boot before
   * the migration copies the BUNDLED assets into DE.
   */
  @Test
  public void legacyCeDataMigratesIntoDeviceProtectedHome() throws Exception {
    assumeTrue("migration only runs while unlocked (the real BFU case "
        + "migrates on ACTION_USER_UNLOCKED)", Sh1Storage.isUserUnlocked(context));

    // the legacy install: a custom camdat + a BYOA GLB in the CE filesDir
    File legacyDir = new File(context.getFilesDir(), "sh1");
    assertTrue(legacyDir.mkdirs());
    write(new File(legacyDir, "camdat.json"), "{\"models\":[]}");
    write(new File(legacyDir, "CUSTOM.glb"), "custom-glb-bytes");

    // a pre-migration BFU boot copied the BUNDLED camdat into DE
    File deDir = Sh1Storage.sh1Dir(context);
    assertTrue(deDir.mkdirs());
    write(new File(deDir, "camdat.json"), "{\"models\":[\"bundled\"]}");

    boolean moved = Sh1Storage.migrateLegacyCeData(context);

    assertTrue("the migration must report that files moved", moved);
    assertEquals("the legacy (user's) camdat must WIN over the bundled twin",
        "{\"models\":[]}", read(new File(deDir, "camdat.json")));
    assertEquals("imported GLBs must survive the migration",
        "custom-glb-bytes", read(new File(deDir, "CUSTOM.glb")));
    assertFalse("the legacy CE dir must be gone after the move",
        legacyDir.exists());
  }

  /** idempotence: a second run is a no-op that reports nothing moved */
  @Test
  public void migrationIsIdempotent() throws Exception {
    assumeTrue(Sh1Storage.isUserUnlocked(context));
    File legacyDir = new File(context.getFilesDir(), "sh1");
    assertTrue(legacyDir.mkdirs());
    write(new File(legacyDir, "camdat.json"), "{}");

    assertTrue(Sh1Storage.migrateLegacyCeData(context));
    assertFalse("second run: nothing left to move",
        Sh1Storage.migrateLegacyCeData(context));
    assertFalse("the CE dir is deleted after the first run",
        legacyDir.exists());
  }

  /** an install without legacy data reports nothing moved, never throws */
  @Test
  public void migrationWithoutLegacyDataIsANoOp() {
    assertFalse(Sh1Storage.migrateLegacyCeData(context));
  }

  /* ---- 4. UserManager plumbing (informational pin) --------------------- */

  @Test
  public void userManagerReportsUnlockedInTests() {
    // Robolectric models an unlocked user; the BFU state itself cannot be
    // simulated here — the unlocked/locked split is exercised on-device
    UserManager um = context.getSystemService(UserManager.class);
    assumeFalse("no UserManager shadow -> the code falls back to true",
        um == null);
    assertTrue(um.isUserUnlocked());
  }

  /* ---- helpers --------------------------------------------------------- */

  private static void write(File file, String content) throws Exception {
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String read(File file) throws Exception {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }
}
