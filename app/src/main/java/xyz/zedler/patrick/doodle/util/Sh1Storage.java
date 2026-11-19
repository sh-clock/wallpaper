/*
 * This file is part of Doodle Android.
 *
 * Doodle Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Doodle Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Doodle Android. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2019-2025 by Patrick Zedler
 */

package xyz.zedler.patrick.doodle.util;

import android.content.Context;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.UserManager;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v10.6 DIRECT BOOT — the engine data home in device-protected storage.
 *
 * <p>The wallpaper service is {@code android:directBootAware}: the system
 * binds it right at boot, BEFORE the user unlocks the phone (BFU — before
 * first unlock). In that state only device-encrypted (DE) storage is
 * readable; the credential-encrypted (CE) {@code getFilesDir()} is not.
 * Upstream Doodle keeps its entire state in DE storage for exactly this
 * reason ({@link PrefsUtil} moves the SharedPreferences there); the SH1
 * fork's engine data — the GLB/IPD/PLM scene files, camdat.json and the
 * scene thumbnails — used to live in the CE filesDir instead, so at BFU
 * the asset copy failed with a RuntimeException ("Fog Wallpaper has
 * stopped" dialog) and the lock screen stayed black until unlock.
 *
 * <p>This class restores the direct-boot contract: ALL engine-visible
 * files live under the device-protected filesDir, which is readable and
 * writable in BFU <em>and</em> after unlock. The engine itself is not
 * involved — the JNI {@code start()} takes this root path and joins
 * {@code /sh1/&lt;name&gt;} on its own, exactly as before.
 *
 * <p>Devices without file-based encryption (API &lt; 24) have no separate
 * DE storage; every method then transparently falls back to the plain
 * context, whose storage is always available.
 */
public final class Sh1Storage {

  private static final String TAG = Sh1Storage.class.getSimpleName();

  private static final String SH1_DIR = "sh1";
  private static final String THUMBNAILS_DIR = "thumbnails";

  private Sh1Storage() {
  }

  /**
   * The always-available storage context: the device-protected context on
   * FBE devices (API 24+), the plain context otherwise. Never null.
   */
  public static Context storageContext(Context context) {
    if (VERSION.SDK_INT >= VERSION_CODES.N) {
      try {
        Context device = context.createDeviceProtectedStorageContext();
        if (device != null) {
          return device;
        }
      } catch (Exception e) {
        Log.w(TAG, "storageContext: device-protected context unavailable", e);
      }
    }
    return context;
  }

  /**
   * True while the user's credential-encrypted storage is readable —
   * i.e. NOT in the BFU state. Pre-N devices have no FBE and report true.
   */
  public static boolean isUserUnlocked(Context context) {
    if (VERSION.SDK_INT >= VERSION_CODES.N) {
      try {
        UserManager um = context.getSystemService(UserManager.class);
        if (um != null) {
          return um.isUserUnlocked();
        }
      } catch (Exception e) {
        Log.w(TAG, "isUserUnlocked: UserManager unavailable", e);
      }
    }
    return true;
  }

  /** The engine's data directory: {@code <deviceProtectedFilesDir>/sh1} */
  public static File sh1Dir(Context context) {
    return new File(storageContext(context).getFilesDir(), SH1_DIR);
  }

  /** The F9 scene preview cache: {@code <deviceProtectedFilesDir>/thumbnails} */
  public static File thumbnailsDir(Context context) {
    return new File(storageContext(context).getFilesDir(), THUMBNAILS_DIR);
  }

  /**
   * The root path handed to {@code Sh1Native.start()} — the device-protected
   * filesDir. The engine joins {@code /sh1/<name>} itself (sh1_jni.c), so
   * every scene file it opens lands in DE storage without a single engine
   * change.
   */
  public static String engineDataPath(Context context) {
    return storageContext(context).getFilesDir().getAbsolutePath();
  }

  /**
   * The cached engine thumbnail of ONE wallpaper (F9 + v9.2 F30): one PNG
   * per individual track, in the device-protected thumbnails dir.
   */
  public static File thumbnailFile(Context context, String glbFile, int trackIndex) {
    return new File(thumbnailsDir(context), glbFile + "_t" + trackIndex + ".png");
  }

  /**
   * One-time CE → DE migration for installs that predate v10.6: moves the
   * legacy credential-encrypted {@code filesDir/sh1} (scene data, custom
   * camdat, imported BYOA GLBs) and {@code filesDir/thumbnails} into the
   * device-protected home. Idempotent and never throws; call it only while
   * unlocked ({@link #isUserUnlocked}).
   *
   * <p>A legacy file ALWAYS wins over a same-named DE file: a BFU boot that
   * ran before the migration copies the BUNDLED assets into DE, and the
   * user's actual custom camdat / imported GLB in CE must replace them.
   *
   * @return true when any file was moved (the caller should re-boot the
   *         engine so the migrated scene takes effect)
   */
  public static boolean migrateLegacyCeData(Context context) {
    if (VERSION.SDK_INT < VERSION_CODES.N) {
      return false;   // no FBE: the "legacy" dir IS the data home already
    }
    try {
      File ceFiles = context.getFilesDir();
      File deFiles = storageContext(context).getFilesDir();
      if (ceFiles.getAbsolutePath().equals(deFiles.getAbsolutePath())) {
        return false;   // same physical dir (non-FBE) — nothing to migrate
      }
      if (!isUserUnlocked(context)) {
        return false;   // CE not readable (BFU) — retry after unlock
      }
      boolean moved = moveDirContents(new File(ceFiles, SH1_DIR), sh1Dir(context));
      moved |= moveDirContents(new File(ceFiles, THUMBNAILS_DIR), thumbnailsDir(context));
      return moved;
    } catch (Exception e) {
      Log.e(TAG, "migrateLegacyCeData: failed", e);
      return false;
    }
  }

  /**
   * Move every regular file of {@code src} into {@code dst} (flat dirs
   * only — both directories hold plain files). Tries rename first, falls
   * back to copy+delete across the CE/DE mount boundary. Removes the
   * source directory when it ends up empty.
   */
  private static boolean moveDirContents(File src, File dst) {
    File[] files = src.listFiles();
    if (files == null) {
      return false;   // no legacy dir at all — nothing to do
    }
    if (files.length == 0) {
      //noinspection ResultOfMethodCallIgnored
      src.delete();   // an empty leftover dir: remove and report nothing moved
      return false;
    }
    if (!dst.exists() && !dst.mkdirs()) {
      Log.e(TAG, "moveDirContents: cannot create " + dst);
      return false;
    }
    boolean moved = false;
    for (File file : files) {
      if (file.isDirectory()) {
        continue;
      }
      File to = new File(dst, file.getName());
      if (to.exists()) {
        // the legacy file is authoritative — the DE twin (e.g. a bundled
        // asset copied during a pre-migration BFU boot) must yield
        //noinspection ResultOfMethodCallIgnored
        to.delete();
      }
      if (file.renameTo(to)) {
        moved = true;
        continue;
      }
      if (copyFile(file, to)) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        moved = true;
      }
    }
    File[] rest = src.listFiles();
    if (rest != null && rest.length == 0) {
      //noinspection ResultOfMethodCallIgnored
      src.delete();
    }
    return moved;
  }

  private static boolean copyFile(File from, File to) {
    try (InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to)) {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return true;
    } catch (IOException e) {
      Log.e(TAG, "copyFile: " + from + " -> " + to, e);
      //noinspection ResultOfMethodCallIgnored
      to.delete();   // no half-written files
      return false;
    }
  }
}
