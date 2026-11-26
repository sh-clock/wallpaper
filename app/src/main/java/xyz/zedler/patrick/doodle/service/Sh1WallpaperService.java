package xyz.zedler.patrick.doodle.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;
import androidx.annotation.VisibleForTesting;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import xyz.zedler.patrick.doodle.Constants.ACTION;
import xyz.zedler.patrick.doodle.Constants.DEF;
import xyz.zedler.patrick.doodle.Constants.PREF;
import xyz.zedler.patrick.doodle.Constants.SH1;
import xyz.zedler.patrick.doodle.util.PrefsUtil;
import xyz.zedler.patrick.doodle.util.Sh1Camdat;
import xyz.zedler.patrick.doodle.util.Sh1Storage;

/**
 * Silent Hill 1 environment renderer as the live wallpaper of this app.
 *
 * The engine is a software rasterizer on its own native thread; it
 * survives surface destroy/recreate and idles whenever the wallpaper
 * is not visible (screen off = ~0 CPU, no wakelocks, fully offline).
 *
 * v3 lifecycle mapping (per-engine bindings, fixes the black screen):
 *   Android runs SEVERAL engines of this service in one process — the
 *   system preview (WallpaperPreviewActivity) AND the real wallpaper.
 *   While "Set wallpaper" is applied the real engine attaches first and
 *   only afterwards does the preview finish; a naive single-surface
 *   bridge let the preview's teardown null the live wallpaper's window
 *   (black screen, engine keeps running silently). Every engine here
 *   gets a generation id and all native callbacks are tagged with it,
 *   so the native side can keep the surfaces apart and fall back to the
 *   surviving engine when the preview goes away.
 *
 *   onSurfaceCreated    -> assets bootstrap (once) -> engine start (once)
 *                          -> publish surface (tagged with gen)
 *   onVisibilityChanged -> visible(gen, true/false) battery gate
 *   onSurfaceDestroyed  -> retract THIS engine's surface (before returning)
 *   onDestroy           -> free the binding (hygiene)
 *
 * No key injection: the HUD is disabled at the engine level (--no-hud)
 * and the camera tracks cycle on their own — the wallpaper is purely
 * ambient.
 *
 * v5 settings integration:
 *   - The engine boots with --preset N (Gfx_MapEnvSet(N,N)) carrying the
 *     effective env mode from the app settings: Auto Mode computes
 *     day/night on the DEVICE clock with the USER'S schedule times
 *     (default 06:00-18:00) -> preset 1 / preset 6; manual mode uses the
 *     persisted preset choice. A user weather override boots as
 *     --weather-target N (the engine's sharedFunc_800D0B18); -1 keeps the
 *     scene's own camdat recipe.
 *   - ACTION.SETTINGS_CHANGED re-applies changed settings to the RUNNING
 *     engine through Sh1Native.env/weather — the engine's own
 *     mode-application paths.
 *   - ACTION_TIME_TICK / TIME_CHANGED / TIMEZONE_CHANGED keep Auto Mode
 *     honest: each device-clock minute the effective day/night preset is
 *     re-evaluated and pushed if it flipped.
 *
 * v6 Phase D/E:
 *   - SCENE selection: the engine boots with --glb <camdat models[].file>
 *     --track N; switching scenes stops the engine thread through its
 *     own K_QUIT path and boots the new scene (single background
 *     executor serializes stop/start).
 *   - SCENE RANDOMIZER: daily / hourly / on-phone-lock (ACTION_SCREEN_OFF)
 *     / custom-interval triggers pick a random (model, track) pair from
 *     the ACTIVE camdat.json and switch to it.
 *   - BATTERY AWARE RENDERING: ACTION_BATTERY_CHANGED; at or below the
 *     user's critical percentage (and not charging) the engine renders
 *     the current frame exactly ONCE and holds the Surface at 0 FPS —
 *     never a static image, the frozen output IS the software-rasterized
 *     frame. Resumes when above the threshold or charging.
 *   - POWER SAVE COMPLIANCY: PowerManager.ACTION_POWER_SAVE_MODE_CHANGED
 *     applies the same render-once freeze while the system battery
 *     saver is on (a UI toggle disables the compliance).
 *
 * v7 Phase G/H (see worklog):
 *   - G-1 fix: SETTINGS_CHANGED now re-evaluates battery AND power-save
 *     freeze sources from the cached battery state — threshold/toggle
 *     changes apply immediately instead of at the next system broadcast.
 *   - G-2 fix (native): freeze(1) always marks the frame dirty, so a
 *     restart while frozen paints the new scene's single frozen frame.
 *   - F1 adaptive pacing governor: Java measures the engine's frame time
 *     (read-only stats seam) and steps the presentation cap 30 -> 24 ->
 *     20 (or 60 -> 48 -> 40) below the user-selected cap only.
 *   - F2 shuffle bag: the randomizer draws from a shuffled queue so
 *     every pool entry appears once before repeats.
 *   - F3 favorites: the randomizer pool narrows to starred scenes.
 *   - F4 pre-warm: posix_fadvise WILLNEED on the next random scene GLB
 *     while the wallpaper is invisible (IO-only native seam).
 *   - F6 first-frame watchdog: all-black presented frame (or a dead
 *     engine thread) twice in a row after a scene start falls back to
 *     the bundled scene; read-only grabFrame/isRunning seams.
 *   - F9 thumbnails: the engine's own presented frame is snapshotted
 *     ~2 s after each scene start and cached as PNG in filesDir.
 *   - F10 charging exemption toggle for the battery freeze.
 *   - F12 mid-battery half-rate pacing: presentation capped at 15 FPS
 *     between the mid threshold and the critical freeze threshold.
 *   - F15 Quick Settings tiles call back into this service's statics
 *     (requestNextScene / setManualFreeze) — same safe entry points.
 *   - F16 AOD/ambient freeze: screen-off OR-combines into the freeze
 *     state while enabled (the engine already idles invisible; this
 *     additionally stretches the idle wakeup cadence).
 *   - F17/F18: the engine boots with the user's --res / --fps choice
 *     through the existing argv mechanism (restart on change).
 *   - F19 Auto-Mode preset mapping: day/night presets selectable from
 *     the verbatim 21-preset table; defaults stay day 1 / night 6.
 */
public class Sh1WallpaperService extends WallpaperService {

  private static final String TAG = Sh1WallpaperService.class.getSimpleName();

  @Override
  public Engine onCreateEngine() {
    return new Sh1Engine();
  }

  /**
   * v11.0 (aim 7): the bundled scene corpus is enumerated DYNAMICALLY
   * from assets/sh1 (AssetManager.list) — every FILE is copied into
   * app-private storage; subdirectories (thumbs/) are skipped (they are
   * read straight from the APK). A new scene = drop the GLB into
   * assets/sh1 + a models[] entry in camdat.json — no Java edit, no
   * ASSETS-array maintenance; CI rebuilds + bumps the version.
   *
   * v41 (F-2): RESTORED — the 6856b9a base reset restored the v10.7
   * static 4-file ASSETS array, and ce0879b re-added the 44-scene
   * corpus WITHOUT the dynamic copy, so 43/44 bundled scenes were
   * unreachable on device (the scene picker gates on the data dir:
   * Sh1Camdat checks new File(dir, m.file).exists()). Verbatim from
   * the v11.2 implementation (94e37b7).
   */
  private static String[] listSceneAssets(Context context) {
    try {
      String[] names = context.getAssets().list("sh1");
      if (names == null || names.length == 0) {
        Log.e(TAG, "asset copy: assets/sh1 lists empty");
        return new String[0];
      }
      java.util.ArrayList<String> files = new java.util.ArrayList<>();
      for (String f : names) {
        if (f.indexOf('/') >= 0) {
          // V44 (the red-CI root cause): Robolectric's AssetManager.list()
          // walks the tree RECURSIVELY (it returns "thumbs/pair_0.png" for
          // list("sh1")), while real Android lists the IMMEDIATE children
          // only ("thumbs" as a single dir entry). A nested path is a
          // subdirectory tree entry in either semantics — the scene corpus
          // is FLAT by contract, and writing a nested name would target an
          // uncreated subdirectory (the FileNotFoundException aborted the
          // whole copy + left no markers — the v1.10.0 red CI). Filter by
          // path separator: stable and identical in BOTH environments.
          continue;
        }
        String[] sub = context.getAssets().list("sh1/" + f);
        if (sub != null && sub.length > 0) {
          continue;   // a directory (thumbs/) — read from the APK directly
        }
        files.add(f);
      }
      return files.toArray(new String[0]);
    } catch (IOException e) {
      Log.e(TAG, "asset copy: cannot list assets/sh1", e);
      return new String[0];
    }
  }

  private static volatile boolean sAssetsReady = false;
  private static final AtomicInteger sGen = new AtomicInteger(0);
  private static volatile boolean sEngineStarted = false;

  /**
   * V43 (the v1.10.0 red-CI fix): test isolation for the process-global
   * copy latch. Robolectric gives EVERY test class (and every test
   * method) a fresh data dir, while this latch is process-global —
   * correct in production, where the data dirs are stable for the
   * process lifetime, but fatal for the unit-test suite: Gradle runs
   * test classes in an unspecified JVM order, so whichever class
   * touched the copy SECOND saw the latch already set and skipped the
   * whole flow (AssetRefreshTest then found no .asset_version marker).
   * The tests reset the latch before each copy invocation that must
   * perform real work — every refresh phase then exercises the ACTUAL
   * guard logic (version marker + hash manifest), not the latch
   * shortcut. NEVER call from production code.
   */
  @VisibleForTesting
  static void resetAssetsReadyForTest() {
    synchronized (Sh1WallpaperService.class) {
      sAssetsReady = false;
    }
  }

  /**
   * v10.1 BUG 2: the number of LIVE Sh1Engine instances (the system
   * creates one per wallpaper surface: the real wallpaper + the picker
   * preview). Incremented in the Sh1Engine constructor, decremented in
   * its onDestroy. The editor's standalone session teardown consults
   * this: with ZERO live engines there is no wallpaper consumer to
   * hand the raster back to, so the engine is STOPPED instead of
   * restarted (a restart would boot a headless engine that renders
   * into no surface — CPU burn + a stale booted scene conflicting
   * with the wallpaper's own boot when it is later enabled).
   */
  private static final AtomicInteger sEngineInstances = new AtomicInteger(0);

  /** last values pushed to the RUNNING engine (delta detection) */
  private static volatile int sAppliedEnv = Integer.MIN_VALUE;
  private static volatile int sAppliedWeather = Integer.MIN_VALUE;

  /** the scene the RUNNING engine was last booted with (per-scene env, F21) */
  private static volatile String sAppliedScene = null;

  /** v10.1 TASK 4: last crop offsets pushed to the RUNNING engine */
  private static volatile float sAppliedCropX = 0.0f;
  private static volatile float sAppliedCropY = 0.0f;

  /** v9.2 F30: the track id of the RUNNING wallpaper (per-track env overrides) */
  private static volatile String sAppliedTrackId = null;

  /** engine restarts run serialized on one background thread */
  private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

  /**
   * v9.7: true while the native Camtrack editor holds the engine for its
   * LIVE PREVIEW session (beginEditorPreview..endEditorPreview). While a
   * session is active every other engine-state writer in this service
   * early-returns (applySettingsToEngine / refreshAutoMode / restartEngine
   * / freeze / pacing / observer), so nothing can fight the preview; the
   * wallpaper's own selection is restored by endEditorPreview through the
   * normal restartEngine path. All session mutations run on sExecutor —
   * the SAME serialization the restarts use, so there is no new locking.
   */
  private static volatile boolean sEditorSession = false;

  /**
   * v7 observer tasks (watchdog + thumbnail grabs) run on their OWN
   * single thread: they sleep between samples and must never block a
   * pending engine restart on sExecutor.
   */
  private static final ExecutorService sObserver = Executors.newSingleThreadExecutor();

  /** invalidates in-flight observer tasks when the engine (re)boots */
  private static final AtomicInteger sBootGen = new AtomicInteger(0);

  /** OR-combined freeze state: battery + power saver + AOD + manual tile */
  private static volatile boolean sFreezeBattery = false;
  private static volatile boolean sFreezePowerSave = false;
  private static volatile boolean sFreezeAod = false;
  private static volatile boolean sFreezeManual = false;

  /** v7 battery cache (G-1): last ACTION_BATTERY_CHANGED state */
  private static volatile int sBatteryPct = -1;
  private static volatile boolean sBatteryCharging = false;

  /** v7 pacing state (F1/F12): the boot --fps value + governor ladder */
  private static volatile double sUserCap = 30.0;
  private static volatile int sGovernorStep = 0;       // 0 = user cap, 1 = 0.8x, 2 = 2/3x
  private static volatile int sGovernorGood = 0;       // consecutive headroom samples
  private static volatile boolean sHalfRate = false;   // F12 active

  /** v48 WAJ-8: the live wallpaper engine instances (was the process-global
   *  sAnyVisible boolean — with multi-engine a supported state (mGen /
   *  sEngineInstances), a second engine (preview/keyguard) reporting
   *  invisible clobbered the live engine's visibility and killed its
   *  watchdog + tilt listener). Visibility is PER-ENGINE state now;
   *  CopyOnWriteArrayList: written on the main thread, read from the
   *  watchdog executor. */
  private static final java.util.concurrent.CopyOnWriteArrayList<Sh1Engine>
      sLiveEngines = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** randomizer scratch state (not persisted; triggers persist anchors) */
  private static final Random sRandom = new Random();

  /** F2 shuffle bag over the (model, track) pool (process lifetime) */
  private static final ArrayDeque<int[]> sBag = new ArrayDeque<>();

  private BroadcastReceiver receiver;
  private boolean receiverRegistered = false;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable randomizerIntervalTick = this::checkIntervalRandomizer;
  private final Runnable governorTick = this::checkGovernor;

  /**
   * copy the bundled SH1 data files into app-private storage (one time).
   * v10.1 BUG 2: takes a plain Context (not the service) — the Camtrack
   * editor's standalone preview session needs it BEFORE Sh1Native.start
   * even when the wallpaper service has never run (fresh install, app
   * not yet set as the live wallpaper): without the copy, the data dir
   * lacks the GLB/IPD/PLM and the engine boots with no scene data =
   * the reported black preview. Idempotent + double-checked under the
   * class lock, so calling it from both the wallpaper path and the
   * editor session path is free.
   *
   * v10.6 DIRECT BOOT: the destination is the DEVICE-PROTECTED data dir
   * (Sh1Storage), which is writable before first unlock (BFU) — the CE
   * filesDir copy used to throw a RuntimeException there and crashed
   * the wallpaper with the "Fog Wallpaper has stopped" dialog. This
   * funnel also folds the pre-v10.6 CE data into DE once (migration),
   * and a failing copy is now LOGGED, never thrown: the engine's own
   * watchdog handles missing scene data; a crash dialog at boot is the
   * one failure mode that must never happen.
   */
  public static void copyAssetsIfNeeded(Context context) {
    if (sAssetsReady) {
      return;
    }
    synchronized (Sh1WallpaperService.class) {
      if (sAssetsReady) {
        return;
      }
      // pre-v10.6 installs keep their engine data in the CE filesDir:
      // fold it into the device-protected home while CE is readable
      Sh1Storage.migrateLegacyCeData(context);
      File dst = Sh1Storage.sh1Dir(context);
      // v11.0 (aim 7): DYNAMIC corpus — every file currently bundled in
      // assets/sh1 (GLBs, the IPD/PLM, camdat.json); no hardcoded array
      String[] assets = listSceneAssets(context);
      // V42 E-1 (the GATE form: version-keyed refresh + the user-data
      // guard): APK updates CHANGE bundled assets (the v1.7.0 night-only
      // camdat re-derivation, the v1.8.0 DR01FE drop-in) — the old
      // exists()-only check left upgraders on stale data-dir copies
      // forever (new files copied, CHANGED files never refreshed).
      // The refresh is keyed on versionCode (the deprecated int field is
      // used deliberately: minSdk 23, getLongVersionCode needs 28; the CI
      // bumps versionCode on every releasable change including
      // asset-only PATCH bumps). A .asset_hashes manifest (per-file
      // SHA-256, MessageDigest — the platform's own) records what WE
      // copied; on refresh, a data-dir file whose hash still matches the
      // manifest is OUR unmodified copy -> refresh it from the APK; a
      // file whose hash differs is USER data (the Camtrack editor
      // persists its doc to this exact camdat.json path) -> PRESERVED,
      // never clobbered. The one-time no-manifest transition (upgraders
      // from a pre-guard build) backs up a differing camdat.json to
      // camdat.user.bak before refreshing it (recovery), and refreshes
      // the binary corpus (GLBs are never user-editable surfaces).
      long currentVersion = -1L;
      try {
        currentVersion = context.getPackageManager()
            .getPackageInfo(context.getPackageName(), 0).versionCode;
      } catch (Exception e) {
        Log.w(TAG, "asset copy: versionCode read failed", e);
      }
      File versionMarker = new File(dst, ".asset_version");
      File hashManifest = new File(dst, ".asset_hashes");
      long copiedVersion = readVersionMarker(versionMarker);
      boolean versionKnown = currentVersion > 0;
      boolean refreshAll = versionKnown && currentVersion != copiedVersion;
      java.util.Map<String, String> hashes =
          refreshAll ? readHashManifest(hashManifest) : java.util.Collections.emptyMap();
      boolean missing = false;
      for (String f : assets) {
        if (!new File(dst, f).exists()) {
          missing = true;
          break;
        }
      }
      if (!missing && !refreshAll) {
        sAssetsReady = true;
        return;
      }
      if (!dst.exists() && !dst.mkdirs()) {
        Log.e(TAG, "asset copy: cannot create " + dst);
        return;
      }
      // the no-manifest transition: preserve a recovery copy of a
      // user-touched camdat before the one-time full refresh
      if (refreshAll && hashes.isEmpty()) {
        preserveUserCamdat(context, dst);
      }
      java.util.Map<String, String> newHashes = new java.util.HashMap<>();
      for (String f : assets) {
        File out = new File(dst, f);
        boolean refresh = false;
        if (!out.exists()) {
          refresh = true;                       // missing -> copy
        } else if (refreshAll) {
          String recorded = hashes.get(f);
          if (recorded == null) {
            refresh = true;                     // no manifest record (the
                                                // transition) -> refresh
          } else {
            String onDisk = sha256Hex(out);
            if (onDisk.equals(recorded)) {
              refresh = true;                   // OUR unmodified copy
            } else {
              Log.i(TAG, "asset refresh: keeping user-modified " + f);
            }
          }
        }
        if (!refresh) {
          // w10: record a sentinel that can NEVER equal a sha256 hex — a user
          // file preserved on one refresh used to have its (USER) hash written
          // here, and the NEXT refresh then classified it as "OUR unmodified
          // copy" (onDisk.equals(recorded)) and clobbered it from the APK —
          // the user's camdat.json/BYOA GLB survived exactly one version bump.
          // "USER" makes the user-owned state sticky forever.
          newHashes.put(f, "USER");
          continue;
        }
        try (InputStream in = context.getAssets().open("sh1/" + f);
            OutputStream os = new FileOutputStream(out)) {
          byte[] buf = new byte[64 * 1024];
          int n;
          while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
          }
        } catch (IOException e) {
          Log.e(TAG, "asset copy failed: " + f, e);
          return;   // v10.6: NEVER throw here — the watchdog degrades to
                    // the bundled scene; a boot-time crash dialog is worse
        }
        newHashes.put(f, sha256Hex(out));
      }
      if (versionKnown) {
        writeVersionMarker(versionMarker, currentVersion);
      }
      writeHashManifest(hashManifest, newHashes);
      sAssetsReady = true;
    }
  }

  /** V42 E-1: the versionCode of the last successful copy (8 bytes LE). */
  private static long readVersionMarker(File marker) {
    try (java.io.DataInputStream mi = new java.io.DataInputStream(
        new java.io.FileInputStream(marker))) {
      return mi.readLong();
    } catch (Exception ignored) {
      return -1L;
    }
  }

  private static void writeVersionMarker(File marker, long version) {
    try (java.io.DataOutputStream mo = new java.io.DataOutputStream(
        new java.io.FileOutputStream(marker))) {
      mo.writeLong(version);
    } catch (Exception e) {
      Log.w(TAG, "asset copy: version marker write failed", e);
    }
  }

  /** V42 E-1: name -> sha256 hex of every file WE own in the data dir. */
  private static java.util.Map<String, String> readHashManifest(File manifest) {
    java.util.Map<String, String> out = new java.util.HashMap<>();
    try (java.io.BufferedReader br = new java.io.BufferedReader(
        new java.io.InputStreamReader(new java.io.FileInputStream(manifest), "UTF-8"))) {
      String line;
      while ((line = br.readLine()) != null) {
        int eq = line.indexOf('=');
        if (eq > 0 && line.length() > eq + 1) {
          out.put(line.substring(0, eq), line.substring(eq + 1));
        }
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  private static void writeHashManifest(File manifest,
      java.util.Map<String, String> hashes) {
    try (java.io.PrintWriter pw = new java.io.PrintWriter(
        new java.io.OutputStreamWriter(new java.io.FileOutputStream(manifest), "UTF-8"))) {
      for (java.util.Map.Entry<String, String> e : hashes.entrySet()) {
        pw.println(e.getKey() + "=" + e.getValue());
      }
    } catch (Exception e) {
      // degraded mode: the next refresh treats unrecorded files as
      // transition files — the user-data guard weakens to the backup
      Log.w(TAG, "asset copy: hash manifest write failed (next refresh "
          + "degrades to the transition form)", e);
    }
  }

  private static String sha256Hex(File f) {
    try (InputStream in = new java.io.FileInputStream(f)) {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        md.update(buf, 0, n);
      }
      StringBuilder sb = new StringBuilder(64);
      for (byte b : md.digest()) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16))
            .append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
  }

  /** the one-time transition backup of a user-touched camdat.json */
  private static void preserveUserCamdat(Context context, File dst) {
    File camdat = new File(dst, "camdat.json");
    if (!camdat.isFile()) {
      return;
    }
    try (InputStream in = context.getAssets().open("sh1/camdat.json")) {
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        bos.write(buf, 0, n);
      }
      byte[] apk = bos.toByteArray();
      byte[] disk = new byte[(int) camdat.length()];
      try (java.io.DataInputStream di = new java.io.DataInputStream(
          new java.io.FileInputStream(camdat))) {
        di.readFully(disk);
      }
      if (!java.util.Arrays.equals(apk, disk)) {
        File bak = new File(dst, "camdat.user.bak");
        java.nio.file.Files.copy(camdat.toPath(), bak.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Log.i(TAG, "asset refresh (transition): user camdat.json differs "
            + "from the shipped one — backed up to camdat.user.bak");
      }
    } catch (Exception e) {
      Log.w(TAG, "asset refresh: the transition camdat backup failed", e);
    }
  }

  /* ---- settings facade (shared with the settings UI process) ---- */

  private static SharedPreferences sPrefsCache;

  public static SharedPreferences getPrefs(Context context) {
    // cached: PrefsUtil's storage-context migration is a one-time move;
    // re-running it per broadcast (TIME_TICK fires every minute) would
    // only spam the migration-failed warning
    if (sPrefsCache == null) {
      sPrefsCache = new PrefsUtil(context).checkForMigrations().getSharedPrefs();
    }
    return sPrefsCache;
  }

  /** Auto Mode off: the persisted manual preset (0..20) */
  public static int getManualEnv(SharedPreferences prefs) {
    int env = prefs.getInt(PREF.SH1_ENV, DEF.SH1_ENV);
    if (env < 0 || env >= SH1.ENV_COUNT) {
      return DEF.SH1_ENV;
    }
    return env;
  }

  /** persisted day-schedule time (Material TimePicker values) */
  public static int[] getDaySchedule(SharedPreferences prefs) {
    return new int[]{
        clampPref(prefs.getInt(PREF.SH1_DAY_HOUR, DEF.SH1_DAY_HOUR), 0, 23),
        clampPref(prefs.getInt(PREF.SH1_DAY_MINUTE, DEF.SH1_DAY_MINUTE), 0, 59)
    };
  }

  /** persisted night-schedule time (Material TimePicker values) */
  public static int[] getNightSchedule(SharedPreferences prefs) {
    return new int[]{
        clampPref(prefs.getInt(PREF.SH1_NIGHT_HOUR, DEF.SH1_NIGHT_HOUR), 0, 23),
        clampPref(prefs.getInt(PREF.SH1_NIGHT_MINUTE, DEF.SH1_NIGHT_MINUTE), 0, 59)
    };
  }

  private static int clampPref(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  /**
   * v7 F19 + v9 F21/F27 + v9.2 F30: the Auto-Mode DAY preset — the
   * per-WALLPAPER (track) override first (sh1_ovr_day_t<id>), then the
   * per-scene (model) override (sh1_ovr_day_<glb>, F21), then the
   * customized global mapping (F19), else the authentic ENV_DAY (preset
   * 1, "THR day street" of the verbatim 21-preset table). The global
   * default stays modifiable; a -1/absent override falls back to it.
   */
  public static int getAutoDayEnv(SharedPreferences prefs, String glbFile, String trackId) {
    return getAutoDayEnv(null, prefs, glbFile, trackId);
  }

  /**
   * v9.6 (Camtrack v13): the context-aware resolution — the camdat-authored
   * layers (the wallpaper's OWN day/night env presets) slot between the
   * app-side overrides and the global custom mapping. The context-less
   * overloads above resolve WITHOUT them (the "global default" rows and
   * the pickers show the app-side state).
   */
  public static int getAutoDayEnv(
      Context context, SharedPreferences prefs, String glbFile, String trackId) {
    if (prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      int ovr = getTrackDayEnvOverride(prefs, trackId);
      if (ovr >= 0 && ovr < SH1.ENV_COUNT) {
        return ovr;
      }
      if (glbFile != null) {
        ovr = prefs.getInt(sceneDayEnvOverrideKey(glbFile), -1);
        if (ovr >= 0 && ovr < SH1.ENV_COUNT) {
          return ovr;
        }
      }
    }
    /* v9.6 (Camtrack v13): the camdat layer — track envDay > model dayEnv */
    if (context != null) {
      int camdat = getCamdatDayEnv(context, glbFile, trackId);
      if (camdat >= 0 && camdat < SH1.ENV_COUNT) {
        return camdat;
      }
    }
    if (prefs.getBoolean(PREF.SH1_AUTO_CUSTOM, DEF.SH1_AUTO_CUSTOM)) {
      return clampPref(
          prefs.getInt(PREF.SH1_AUTO_DAY_ENV, DEF.SH1_AUTO_DAY_ENV), 0, SH1.ENV_COUNT - 1);
    }
    return SH1.ENV_DAY;
  }

  /** v7 F19 + v9 F27: the global Auto-Mode DAY preset (scene-less callers) */
  public static int getAutoDayEnv(SharedPreferences prefs) {
    return getAutoDayEnv(prefs, null, null);
  }

  /** scene-aware variant (the global-default row of the override picker) */
  public static int getAutoDayEnv(SharedPreferences prefs, String glbFile) {
    return getAutoDayEnv(prefs, glbFile, null);
  }

  /**
   * v7 F19 + v9 F21/F27 + v9.2 F30: the Auto-Mode NIGHT preset —
   * per-wallpaper (track) override > per-scene (model) override (F21) >
   * custom mapping (F19) > the authentic default, which v9 F27 mandates
   * as preset 3 "night street + FLASHLIGHT" (was 6).
   */
  public static int getAutoNightEnv(SharedPreferences prefs, String glbFile, String trackId) {
    return getAutoNightEnv(null, prefs, glbFile, trackId);
  }

  /** v9.6 (Camtrack v13): the context-aware NIGHT resolution (see day) */
  public static int getAutoNightEnv(
      Context context, SharedPreferences prefs, String glbFile, String trackId) {
    if (prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      int ovr = getTrackNightEnvOverride(prefs, trackId);
      if (ovr >= 0 && ovr < SH1.ENV_COUNT) {
        return ovr;
      }
      if (glbFile != null) {
        ovr = prefs.getInt(sceneNightEnvOverrideKey(glbFile), -1);
        if (ovr >= 0 && ovr < SH1.ENV_COUNT) {
          return ovr;
        }
      }
    }
    /* v9.6 (Camtrack v13): the camdat layer — track envNight > model nightEnv */
    if (context != null) {
      int camdat = getCamdatNightEnv(context, glbFile, trackId);
      if (camdat >= 0 && camdat < SH1.ENV_COUNT) {
        return camdat;
      }
    }
    if (prefs.getBoolean(PREF.SH1_AUTO_CUSTOM, DEF.SH1_AUTO_CUSTOM)) {
      return clampPref(
          prefs.getInt(PREF.SH1_AUTO_NIGHT_ENV, DEF.SH1_AUTO_NIGHT_ENV), 0, SH1.ENV_COUNT - 1);
    }
    return SH1.ENV_NIGHT;
  }

  /** v7 F19 + v9 F27: the global Auto-Mode NIGHT preset (scene-less callers) */
  public static int getAutoNightEnv(SharedPreferences prefs) {
    return getAutoNightEnv(prefs, null, null);
  }

  /** scene-aware variant (the global-default row of the override picker) */
  public static int getAutoNightEnv(SharedPreferences prefs, String glbFile) {
    return getAutoNightEnv(prefs, glbFile, null);
  }

  /**
   * The effective env mode RIGHT NOW: Auto Mode follows the SUN-TIME
   * state (v9 F22 — NOAA civil twilight at the selected city; falls back
   * to the device clock with the user's schedule when no city is set)
   * with the per-wallpaper day/night presets (v9 F21 + v9.2 F30 — track
   * override > model override > global mapping > authentic default);
   * manual mode returns the persisted preset.
   */
  public static int getEffectiveEnv(SharedPreferences prefs) {
    return getEffectiveEnv(prefs, sAppliedScene, sAppliedTrackId);
  }

  /** scene-aware variant (the boot/restart paths know the GLB already) */
  public static int getEffectiveEnv(SharedPreferences prefs, String glbFile) {
    return getEffectiveEnv(prefs, glbFile, null);
  }

  /** v9.6 (Camtrack v13): the context-aware current resolution — the
   * camdat day/night presets of the RUNNING wallpaper participate */
  public static int getEffectiveEnv(Context context, SharedPreferences prefs) {
    return getEffectiveEnv(context, prefs, sAppliedScene, sAppliedTrackId);
  }

  /** v9.2 F30: wallpaper-aware variant (boot/restart know the track id) */
  public static int getEffectiveEnv(SharedPreferences prefs, String glbFile, String trackId) {
    return getEffectiveEnv(null, prefs, glbFile, trackId);
  }

  /**
   * v9.6 (Camtrack v13): the context-aware effective env — the camdat
   * day/night presets participate (context-less callers resolve the
   * app-side layers only).
   */
  public static int getEffectiveEnv(
      Context context, SharedPreferences prefs, String glbFile, String trackId) {
    if (prefs.getBoolean(PREF.SH1_AUTO, DEF.SH1_AUTO)) {
      boolean day = prefs.getBoolean(PREF.SH1_SUN_MODE, DEF.SH1_SUN_MODE)
          ? SH1.isSunTimeDay(prefs)
          : isScheduleDay(prefs);
      return day ? getAutoDayEnv(context, prefs, glbFile, trackId)
                 : getAutoNightEnv(context, prefs, glbFile, trackId);
    }
    return getManualEnv(prefs);
  }

  /** the device-clock schedule day state (v8 behavior, F22 fallback) */
  private static boolean isScheduleDay(SharedPreferences prefs) {
    int[] day = getDaySchedule(prefs);
    int[] night = getNightSchedule(prefs);
    return SH1.isDaytime(day[0], day[1], night[0], night[1]);
  }

  /**
   * v9.6 (Camtrack v13): the wallpaper's CURRENT daylight phase — the
   * same resolution getEffectiveEnv uses (sun-time at the selected city,
   * else the device-clock schedule). Public for the in-app Camtrack
   * editor's bridge (the editor's preview lens boots on this state).
   */
  public static boolean isDaytimeNow(SharedPreferences prefs) {
    return prefs.getBoolean(PREF.SH1_SUN_MODE, DEF.SH1_SUN_MODE)
        ? SH1.isSunTimeDay(prefs)
        : isScheduleDay(prefs);
  }

  /** user weather override: -1 scene default, else 0..8 */
  public static int getUserWeather(SharedPreferences prefs) {
    int weather = prefs.getInt(PREF.SH1_WEATHER, DEF.SH1_WEATHER);
    if (weather != SH1.WEATHER_SCENE
        && (weather < 0 || weather >= SH1.WEATHER_COUNT)) {
      return SH1.WEATHER_SCENE;
    }
    return weather;
  }

  /**
   * The scene's own weather recipe — v9.5 (Camtrack v12): PER-TRACK.
   * Resolves the ACTIVE wallpaper's camdat weather: the track's OWN
   * weatherTarget (camdat.json models[].tracks[].weatherTarget) when
   * present, else the model env's (models[].env.weatherTarget), else the
   * engine's map0 default. This is what "Scene default" means at RUNTIME:
   * the value the engine boots with when no user override is passed — the
   * same resolution the engine's own camdat fold performs (track > model),
   * so the Java bookkeeping (sAppliedWeather, the weather pickers' "Scene
   * default" rows) always mirrors the engine.
   * v6 FIX: models is a JSON ARRAY (the v5 code used
   * getJSONObject and always fell into the catch block).
   * v9.4 FIX (retained): when no weatherTarget applies the engine boots
   * the map0_s00 default (EnvironmentSet(2,1) = snow wind 1 = the
   * D0B18-arg equivalent 2 — D0B18(2) repacks to the exact map0 boot
   * packing 0x5B40), so an absent field resolves to 2 everywhere.
   */
  public static int getSceneWeather(Context context) {
    try {
      JSONObject root = readActiveCamdat(context);
      SharedPreferences prefs = getPrefs(context);
      return sceneTrackWeather(root, getActiveSceneIndex(prefs),
          prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK));
    } catch (Exception e) {
      Log.e(TAG, "getSceneWeather: camdat parse failed", e);
    }
    return MAP0_DEFAULT_WEATHER;
  }

  /**
   * v48 WAJ-11: camdat.json parse cache. ACTION_TIME_TICK (every minute,
   * on by default) -> refreshAutoMode -> getEffectiveEnv -> getCamdatEnv
   * -> readActiveCamdat re-read the file from disk and re-built the
   * whole JSONObject tree each tick even when the file never changed.
   * Keyed on (source, length, lastModified): any mismatch re-parses, so
   * a CHANGED file behaves exactly as before (the Camtrack editor's
   * save changes both). The asset fallback is immutable within a
   * process (an APK update restarts it), so it is cached for the
   * process lifetime.
   *
   * The cached instance is shared, NOT defensively copied: both callers
   * here (getSceneWeather / getCamdatEnv) only READ the tree — a future
   * caller that MUTATES the returned JSONObject must re-parse its own
   * copy. Un-synchronized on purpose: a racing double parse is benign
   * (last writer wins with an equivalent tree).
   */
  private static JSONObject sCamdatCacheRoot;
  private static long sCamdatCacheLen = -1L;
  private static long sCamdatCacheMtime = -1L;
  private static boolean sCamdatCacheIsAsset = false;

  /**
   * v9.6 (Camtrack v13): the ACTIVE camdat.json root — the user-editable
   * copy in filesDir/sh1 first (the in-app Camtrack editor writes there),
   * the bundled asset as the fallback. Extracted from getSceneWeather so
   * the v13 env readers share the identical source and precedence.
   */
  private static JSONObject readActiveCamdat(Context context) throws Exception {
    File camdat = new File(Sh1Storage.sh1Dir(context), "camdat.json");
    boolean isAsset = !camdat.exists();
    long len = isAsset ? -1L : camdat.length();
    long mtime = isAsset ? -1L : camdat.lastModified();
    if (sCamdatCacheRoot != null && sCamdatCacheIsAsset == isAsset
        && sCamdatCacheLen == len && sCamdatCacheMtime == mtime) {
      return sCamdatCacheRoot;   // unchanged since the last parse
    }
    JSONObject root;
    if (!isAsset) {
      try (InputStream in = new java.io.FileInputStream(camdat)) {
        root = readJson(in);
      }
    } else {
      // fall back to the bundled asset (engine not booted yet)
      try (InputStream in = context.getAssets().open("sh1/camdat.json")) {
        root = readJson(in);
      }
    }
    sCamdatCacheRoot = root;
    sCamdatCacheIsAsset = isAsset;
    sCamdatCacheLen = len;
    sCamdatCacheMtime = mtime;
    return root;
  }

  /**
   * v9.6 (Camtrack v13): the camdat-authored DAY env for a wallpaper —
   * resolved by the model FILE name + the stable editor track id (the
   * same keys the app-side override layer uses); null keys resolve the
   * PERSISTED selection (the UI display paths). The track's envDay
   * overrides the model's env.dayEnv; -1 when neither is authored.
   */
  public static int getCamdatDayEnv(Context context, String glbFile, String trackId) {
    return getCamdatEnv(context, glbFile, trackId, true);
  }

  /** v9.6 (Camtrack v13): the NIGHT env (envNight > env.nightEnv; -1 absent) */
  public static int getCamdatNightEnv(Context context, String glbFile, String trackId) {
    return getCamdatEnv(context, glbFile, trackId, false);
  }

  private static int getCamdatEnv(Context context, String glbFile, String trackId,
      boolean day) {
    try {
      JSONObject root = readActiveCamdat(context);
      SharedPreferences prefs = getPrefs(context);
      JSONArray models = root.getJSONArray("models");
      JSONObject model = null;
      if (glbFile != null) {
        for (int i = 0; i < models.length(); i++) {
          JSONObject m = models.optJSONObject(i);
          if (m != null && glbFile.equals(m.optString("file", ""))) {
            model = m;
            break;
          }
        }
      } else {
        int idx = Math.max(0, Math.min(getActiveSceneIndex(prefs), models.length() - 1));
        model = models.optJSONObject(idx);
      }
      if (model == null) {
        return -1;
      }
      JSONObject track = null;
      JSONArray tracks = model.optJSONArray("tracks");
      if (tracks != null && tracks.length() > 0) {
        if (trackId != null) {
          for (int t = 0; t < tracks.length(); t++) {
            JSONObject tr = tracks.optJSONObject(t);
            if (tr != null && trackId.equals(tr.optString("id", ""))) {
              track = tr;
              break;
            }
          }
        } else {
          int ti = prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK);
          track = tracks.optJSONObject(Math.max(0, Math.min(ti, tracks.length() - 1)));
        }
      }
      if (track != null) {
        int v = track.optInt(day ? "envDay" : "envNight", -1);
        if (v >= 0 && v < SH1.ENV_COUNT) {
          return v;   // the track's OWN override (Camtrack v13)
        }
      }
      JSONObject env = model.optJSONObject("env");
      if (env != null) {
        int v = env.optInt(day ? "dayEnv" : "nightEnv", -1);
        if (v >= 0 && v < SH1.ENV_COUNT) {
          return v;   // the model's Auto-Mode preset (Camtrack v13)
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "getCamdatEnv: camdat parse failed", e);
    }
    return -1;
  }

  /**
   * The engine's effective weather when no camdat weatherTarget applies:
   * the map0_s00 boot recipe (snow wind 1) as a D0B18 argument.
   */
  public static final int MAP0_DEFAULT_WEATHER = 2;

  /**
   * v9.5 (Camtrack v12): the PER-TRACK resolution — the ACTIVE track's own
   * weatherTarget (models[].tracks[].weatherTarget) overrides the model
   * env's weatherTarget, mirroring the engine's own camdat fold (track >
   * model). An out-of-range track selection falls back to the model env
   * (the engine clamps the track the same way before its fold).
   */
  private static int sceneTrackWeather(JSONObject root, int modelIndex, int trackIndex) {
    try {
      JSONArray models = root.getJSONArray("models");
      JSONObject model = models.getJSONObject(
          Math.max(0, Math.min(modelIndex, models.length() - 1)));
      JSONArray tracks = model.optJSONArray("tracks");
      if (tracks != null && trackIndex >= 0 && trackIndex < tracks.length()) {
        JSONObject tr = tracks.optJSONObject(trackIndex);
        if (tr != null) {
          int w = tr.optInt("weatherTarget", -1);
          if (w >= 0 && w < SH1.WEATHER_COUNT) {
            return w;   // the track's OWN weather (Camtrack v12)
          }
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "sceneTrackWeather: failed", e);
    }
    return modelEnvWeather(root, modelIndex);   // the model env fallback
  }

  /** weatherTarget of models[idx] (bounded to the engine's 0..8 range) */
  private static int modelEnvWeather(JSONObject root, int modelIndex) {
    try {
      JSONArray models = root.getJSONArray("models");
      JSONObject model = models.getJSONObject(
          Math.max(0, Math.min(modelIndex, models.length() - 1)));
      JSONObject env = model.optJSONObject("env");
      if (env != null) {
        // absent weatherTarget -> the engine's map0 default (see above)
        int w = env.optInt("weatherTarget", MAP0_DEFAULT_WEATHER);
        if (w >= 0 && w < SH1.WEATHER_COUNT) {
          return w;
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "modelEnvWeather: failed", e);
    }
    return MAP0_DEFAULT_WEATHER;
  }

  private static JSONObject readJson(InputStream in) throws Exception {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[64 * 1024];
    int n;
    while ((n = in.read(buf)) > 0) {
      bos.write(buf, 0, n);
    }
    return new JSONObject(bos.toString("UTF-8"));
  }

  /** the persisted active scene index, clamped to the camdat model list */
  public static int getActiveSceneIndex(SharedPreferences prefs) {
    return prefs.getInt(PREF.SH1_SCENE, DEF.SH1_SCENE);
  }

  /* ---- v7 F11: per-scene env/weather overrides (prefs keyed by the
   * camdat model FILE name — stable across camdat edits) ---- */

  private static final String OVR_ENV_PREFIX = "sh1_ovr_env_";
  private static final String OVR_WEATHER_PREFIX = "sh1_ovr_weather_";
  /* v9 F21: per-scene Auto-Mode day/night env overrides */
  private static final String OVR_DAY_PREFIX = "sh1_ovr_day_";
  private static final String OVR_NIGHT_PREFIX = "sh1_ovr_night_";
  /* v9.2 Feature 30: PER-WALLPAPER (per-track) overrides — keyed by the
   * camdat editor's stable track id, resolved TRACK > MODEL (the v7/F11
   * per-model keys stay the fallback, so v9.1 selections keep working).
   * The camdat format itself defines env at the models[] level, so a
   * track without its own override inherits the model's — exactly the
   * editor's own layering.                                                 */
  private static final String OVR_TRACK_ENV_PREFIX = "sh1_ovr_env_t";
  private static final String OVR_TRACK_WEATHER_PREFIX = "sh1_ovr_weather_t";
  private static final String OVR_TRACK_DAY_PREFIX = "sh1_ovr_day_t";
  private static final String OVR_TRACK_NIGHT_PREFIX = "sh1_ovr_night_t";

  public static String sceneEnvOverrideKey(String glbFile) {
    return OVR_ENV_PREFIX + glbFile;
  }

  public static String sceneWeatherOverrideKey(String glbFile) {
    return OVR_WEATHER_PREFIX + glbFile;
  }

  /** v9 F21: per-scene Auto-Mode DAY env pref key ("name|..." stable across camdat edits) */
  public static String sceneDayEnvOverrideKey(String glbFile) {
    return OVR_DAY_PREFIX + glbFile;
  }

  /** v9 F21: per-scene Auto-Mode NIGHT env pref key */
  public static String sceneNightEnvOverrideKey(String glbFile) {
    return OVR_NIGHT_PREFIX + glbFile;
  }

  /** v9.2 F30: per-WALLPAPER (track) override keys — the editor track id */
  public static String trackEnvOverrideKey(String trackId) {
    return OVR_TRACK_ENV_PREFIX + trackId;
  }

  public static String trackWeatherOverrideKey(String trackId) {
    return OVR_TRACK_WEATHER_PREFIX + trackId;
  }

  public static String trackDayEnvOverrideKey(String trackId) {
    return OVR_TRACK_DAY_PREFIX + trackId;
  }

  public static String trackNightEnvOverrideKey(String trackId) {
    return OVR_TRACK_NIGHT_PREFIX + trackId;
  }

  /** v9.2 F30: the ACTIVE wallpaper's track id (null while unbooted) */
  public static String activeTrackId(Context context, SharedPreferences prefs) {
    Sh1Camdat camdat = Sh1Camdat.load(context);
    Sh1Camdat.Wallpaper w = camdat.wallpaper(
        getActiveSceneIndex(prefs), prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK));
    return w != null && !w.trackId.isEmpty() ? w.trackId : null;
  }

  /** v9.2 F30: -1 = no per-track override configured for this wallpaper */
  public static int getTrackEnvOverride(SharedPreferences prefs, String trackId) {
    if (trackId == null || trackId.isEmpty()
        || !prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      return -1;
    }
    return prefs.getInt(trackEnvOverrideKey(trackId), -1);
  }

  public static int getTrackWeatherOverride(SharedPreferences prefs, String trackId) {
    if (trackId == null || trackId.isEmpty()
        || !prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      return -1;
    }
    return prefs.getInt(trackWeatherOverrideKey(trackId), -1);
  }

  /** v9.2 F30: -1 = no per-track day env override configured */
  public static int getTrackDayEnvOverride(SharedPreferences prefs, String trackId) {
    if (trackId == null || trackId.isEmpty()
        || !prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      return -1;
    }
    return prefs.getInt(trackDayEnvOverrideKey(trackId), -1);
  }

  /** v9.2 F30: -1 = no per-track night env override configured */
  public static int getTrackNightEnvOverride(SharedPreferences prefs, String trackId) {
    if (trackId == null || trackId.isEmpty()
        || !prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      return -1;
    }
    return prefs.getInt(trackNightEnvOverrideKey(trackId), -1);
  }

  /** v9 F21: -1 = no per-scene day env override configured */
  public static int getSceneDayEnvOverride(SharedPreferences prefs, String glbFile) {
    if (!prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      return -1;
    }
    return prefs.getInt(sceneDayEnvOverrideKey(glbFile), -1);
  }

  /** v9 F21: -1 = no per-scene night env override configured */
  public static int getSceneNightEnvOverride(SharedPreferences prefs, String glbFile) {
    if (!prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      return -1;
    }
    return prefs.getInt(sceneNightEnvOverrideKey(glbFile), -1);
  }

  /** -1 = no override configured for this scene */
  public static int getSceneEnvOverride(SharedPreferences prefs, String glbFile) {
    if (!prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      return -1;
    }
    return prefs.getInt(sceneEnvOverrideKey(glbFile), -1);
  }

  public static int getSceneWeatherOverride(SharedPreferences prefs, String glbFile) {
    if (!prefs.getBoolean(PREF.SH1_SCENE_OVERRIDES, DEF.SH1_SCENE_OVERRIDES)) {
      return -1;
    }
    return prefs.getInt(sceneWeatherOverrideKey(glbFile), -1);
  }

  /* ---- v7 F17/F18 + v9 F24/F25/F26: boot argv from the persisted prefs ---- */

  /**
   * v9 F25: the boot --res value from the resolution ladder; index 0
   * (the authentic 320x224 PS1 raster) passes {0,0} = NO --res flag —
   * exactly the engine default. High resolutions are never the default.
   */
  public static int[] getBootResolution(SharedPreferences prefs) {
    int res = clampPref(prefs.getInt(PREF.SH1_RESOLUTION, DEF.SH1_RESOLUTION),
        0, SH1.RES_COUNT - 1);
    if (res == SH1.RES_AUTHENTIC) {
      return new int[]{0, 0};
    }
    return new int[]{SH1.RES_MODES[res][0], SH1.RES_MODES[res][1]};
  }

  /**
   * v10.1 TASK 4: the user's crop-window pan offsets, clamped to the
   * normalized -1..1 range (0,0 = the symmetric center crop — the
   * authentic default). Consumed at engine BOOT (the fresh engine's
   * blit state) and live through applySettingsToEngine.
   */
  public static float[] getCropOffsets(SharedPreferences prefs) {
    float x = prefs.getFloat(PREF.SH1_CROP_X, DEF.SH1_CROP_X);
    float y = prefs.getFloat(PREF.SH1_CROP_Y, DEF.SH1_CROP_Y);
    if (!(x >= -1.0f) || x > 1.0f) {
      x = 0.0f;   // NaN / out-of-range -> the center default
    }
    if (!(y >= -1.0f) || y > 1.0f) {
      y = 0.0f;
    }
    return new float[]{x, y};
  }

  /**
   * v9 F26: the boot --fps value — the pref stores the NUMBER (1..60;
   * the v8 enum values 15/24/30/60 read identically). 30 = authentic.
   */
  public static double getBootFps(SharedPreferences prefs) {
    int fps = prefs.getInt(PREF.SH1_FPS_CAP, DEF.SH1_FPS_CAP);
    if (fps < SH1.FPS_MIN || fps > SH1.FPS_MAX) {
      return 30.0;
    }
    return fps;
  }

  /**
   * v9 F24: the boot PSX-wobble argv state — -1 = absent (the scene's
   * camdat psxWobble + engine default, the AUTHENTIC per-scene rule);
   * 0 = forced off (--modern); 1 = forced on (--ps1). The toggle only
   * ever forces OFF (default ON = authentic absence).
   */
  public static int getBootWobble(SharedPreferences prefs) {
    return prefs.getBoolean(PREF.SH1_WOBBLE, DEF.SH1_WOBBLE) ? -1 : 0;
  }

  /**
   * v9.9: the boot RENDERING backend — 0 = software (the DEFAULT and the
   * accuracy source of truth), 1 = OpenGL ES, 2 = Vulkan (both hardware,
   * experimental opt-ins). A boot argv value (--hw): a change needs the
   * engine restart exactly like --res. The engine falls back to software
   * automatically when the chosen backend is unavailable on the device
   * (no ES3+EXT_color_buffer_float / no Vulkan / no R32F targets).
   */
  public static int getBootBackend(SharedPreferences prefs) {
    int be = prefs.getInt(PREF.SH1_HW_BACKEND, DEF.SH1_HW_BACKEND);
    return (be >= 0 && be <= 2) ? be : 0;
  }

  /**
   * v10.0 Task 3 — RESOLUTION-AWARE DITHER POLICY (all three backends:
   * the engine's single {@code dither} global gates the software fb_put
   * path, the GLES shader flag bit and the Vulkan pipeline's identical
   * shader block):
   *
   * <p>The authentic PS1 ordered-dither looks unnatural when the raster
   * is scaled beyond the native 320x224, so the AUTOMATIC default at any
   * higher resolution is dither OFF. A user who explicitly toggles the
   * Dither setting gets their choice respected at every resolution; a
   * user who has never touched the toggle rides the automatic rule
   * (ON at the native raster — the authentic engine default).
   *
   * <p>Layering (exactly the mission's wording): an explicit user choice
   * (SH1_DITHER_USER_SET + SH1_DITHER) &gt; the resolution rule
   * (RES_AUTHENTIC 320x224 -&gt; ON, anything higher -&gt; OFF).
   */
  public static boolean getEffectiveDither(SharedPreferences prefs) {
    if (prefs.getBoolean(PREF.SH1_DITHER_USER_SET, DEF.SH1_DITHER_USER_SET)) {
      return prefs.getBoolean(PREF.SH1_DITHER, DEF.SH1_DITHER);
    }
    int res = clampPref(prefs.getInt(PREF.SH1_RESOLUTION, DEF.SH1_RESOLUTION),
        0, SH1.RES_COUNT - 1);
    return res == SH1.RES_AUTHENTIC;   // native 320x224: ON; any upscale: OFF
  }

  /**
   * v10.0 Task 3: the boot dither argv state — 1 = dither ON (pass NO
   * flag: the engine default + the scene's own camdat envDither rule —
   * the authentic chain, exactly like wobble's -1); 0 = forced off
   * (--no-dither). The policy resolution is {@link #getEffectiveDither};
   * a change needs the engine restart exactly like --res.
   */
  public static int getBootDither(SharedPreferences prefs) {
    return getEffectiveDither(prefs) ? 1 : 0;
  }

  /**
   * Resolve the boot scene: the persisted (scene, track) selection is
   * validated against the ACTIVE camdat.json; a model whose GLB is
   * missing (BYOA not yet loaded) falls back to the first model whose
   * GLB is present, then to the bundled THRF9FD.glb. Returns {glbName,
   * trackIdx, trackId} for Sh1Native.start + the per-wallpaper env
   * overrides (v9.2 F30 — the track id is null when the camdat carries
   * no id for the resolved track).
   */
  private static Object[] resolveBootScene(Context context, SharedPreferences prefs) {
    Sh1Camdat camdat = Sh1Camdat.load(context);
    String glb = "THRF9FD.glb";
    int track = -1;
    String trackId = null;
    if (camdat.isValid()) {
      int scene = getActiveSceneIndex(prefs);
      if (scene < 0 || scene >= camdat.modelCount()) {
        scene = 0;
      }
      Sh1Camdat.Model model = camdat.getModel(scene);
      if (model != null && model.glbPresent) {
        glb = model.file;
        track = resolveTrack(prefs, model);
        Sh1Camdat.Track t = model.track(track);
        trackId = t != null && !t.id.isEmpty() ? t.id : null;
      } else {
        // BYOA: GLB not present — first present model, else the bundled scene
        Sh1Camdat.Model fallback = null;
        for (int i = 0; i < camdat.modelCount(); i++) {
          if (camdat.getModel(i).glbPresent) {
            fallback = camdat.getModel(i);
            break;
          }
        }
        if (fallback == null) {
          // none of the camdat models has a GLB — the bundled default keeps
          // the wallpaper alive; the settings UI prompts for the assets
          glb = "THRF9FD.glb";
          track = -1;
          trackId = null;
        } else {
          glb = fallback.file;
          track = fallback.firstEnabledTrack();
          Sh1Camdat.Track t = fallback.track(track);
          trackId = t != null && !t.id.isEmpty() ? t.id : null;
        }
      }
    }
    return new Object[]{glb, track, trackId};
  }

  private static int resolveTrack(SharedPreferences prefs, Sh1Camdat.Model model) {
    int track = prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK);
    int n = model.tracks.size();
    if (track < 0 || track >= n) {
      track = model.firstEnabledTrack();
    }
    return track;
  }

  /**
   * Push changed settings to the running engine (delta-only: unchanged
   * values are never re-sent, so unrelated settings changes can not
   * trigger a needless weather cross-fade). Scene/track changes are NOT
   * handled here — they need an engine restart (restartEngine()).
   * v9 F20: when Auto Mode is on and the gradual-transition toggle is
   * enabled, the env delta is delivered through the engine's own
   * Gfx_MapEnvUpdate(S32) blend (envTransition); manual preset picks and
   * explicit settings edits stay INSTANT (the engine's own mode-change
   * behavior — only the day/night boundary is the game's gradual path).
   */
  private static void applySettingsToEngine(Context context, SharedPreferences prefs) {
    if (!sEngineStarted || sEditorSession) {
      return;  // not running (or the editor preview owns the engine)
    }
    /* w10: the runtime delta must resolve through the SAME override chain
     * the boot path uses (restartEngine:1394-1405 — the F11 per-track /
     * per-scene pins FIRST, then the effective chain) — the old runtime
     * form resolved getEffectiveEnv/getUserWeather alone, so any pinned
     * env/weather silently reverted within a minute (the default Auto
     * Mode's TIME_TICK) or on every settings edit. With the pin honored,
     * a pinned engine simply stops producing deltas. */
    int ovrEnv = getTrackEnvOverride(prefs, sAppliedTrackId);
    if (ovrEnv < 0) { ovrEnv = getSceneEnvOverride(prefs, sAppliedScene); }
    int env = ovrEnv >= 0 ? ovrEnv : getEffectiveEnv(context, prefs);
    if (env != sAppliedEnv) {
      boolean gradual = prefs.getBoolean(PREF.SH1_AUTO, DEF.SH1_AUTO)
          && prefs.getBoolean(PREF.SH1_TRANSITION, DEF.SH1_TRANSITION);
      if (gradual) {
        Sh1Native.envTransition(env);
      } else {
        Sh1Native.env(env);
      }
      sAppliedEnv = env;
      Log.i(TAG, "applySettingsToEngine: env -> preset " + env
          + " (" + SH1.getEnvModeName(env) + ")"
          + (gradual ? " [gradual ~3 s blend]" : ""));
    }
    int ovrWeather = getTrackWeatherOverride(prefs, sAppliedTrackId);
    if (ovrWeather < 0) { ovrWeather = getSceneWeatherOverride(prefs, sAppliedScene); }
    int weather = ovrWeather >= 0 ? ovrWeather : getUserWeather(prefs);
    int target = weather != SH1.WEATHER_SCENE ? weather : getSceneWeather(context);
    if (target != sAppliedWeather) {
      Sh1Native.weather(target);
      sAppliedWeather = target;
      Log.i(TAG, "applySettingsToEngine: weather -> " + target);
    }
    // v10.1 TASK 4: the crop-window offsets are LIVE-settable presentation
    // state (the engine's blit reads them every frame) — apply on every
    // settings change so the Settings-page sliders and the editor's drag
    // both take effect on the very next presented frame
    float[] crop = getCropOffsets(prefs);
    if (crop[0] != sAppliedCropX || crop[1] != sAppliedCropY) {
      Sh1Native.setCropOffset(crop[0], crop[1]);
      sAppliedCropX = crop[0];
      sAppliedCropY = crop[1];
    }
  }

  /**
   * Re-evaluate Auto Mode (sun-time or device clock, whichever is
   * active) and push the boundary flip through the engine's GRADUAL
   * transition (v9 F20 — the game's own day/night blend; instant only
   * when the user disabled the gradual toggle).
   */
  private static void refreshAutoMode(Context context, SharedPreferences prefs) {
    if (!sEngineStarted || sEditorSession
        || !prefs.getBoolean(PREF.SH1_AUTO, DEF.SH1_AUTO)) {
      return;
    }
    /* w10: the runtime delta must resolve through the SAME override chain
     * the boot path uses (restartEngine:1394-1405 — the F11 per-track /
     * per-scene pins FIRST, then the effective chain) — the old runtime
     * form resolved getEffectiveEnv/getUserWeather alone, so any pinned
     * env/weather silently reverted within a minute (the default Auto
     * Mode's TIME_TICK) or on every settings edit. With the pin honored,
     * a pinned engine simply stops producing deltas. */
    int ovrEnv = getTrackEnvOverride(prefs, sAppliedTrackId);
    if (ovrEnv < 0) { ovrEnv = getSceneEnvOverride(prefs, sAppliedScene); }
    int env = ovrEnv >= 0 ? ovrEnv : getEffectiveEnv(context, prefs);
    if (env != sAppliedEnv) {
      boolean gradual = prefs.getBoolean(PREF.SH1_TRANSITION, DEF.SH1_TRANSITION);
      if (gradual) {
        Sh1Native.envTransition(env);
      } else {
        Sh1Native.env(env);
      }
      sAppliedEnv = env;
      boolean sun = prefs.getBoolean(PREF.SH1_SUN_MODE, DEF.SH1_SUN_MODE);
      Log.i(TAG, "refreshAutoMode: " + (sun ? "sun-time" : "schedule")
          + " boundary -> preset " + env + " (" + SH1.getEnvModeName(env) + ")"
          + (gradual ? " [gradual ~3 s blend]" : ""));
    }
  }

  /* ---- engine restart (scene switching, Phase D; v7: res/fps argv) ---- */

  /**
   * Stop the engine thread through its own K_QUIT path and boot the
   * persisted (scene, track) with the current env/weather settings.
   * Serialized on the single background executor; the surface bindings
   * survive (per-engine gen table) and are re-published by the native
   * start() so the new engine renders into the live Surface.
   */
  private static void restartEngine(Context context) {
    if (sEditorSession) {
      // v9.7: deferred while the Camtrack editor preview owns the engine;
      // endEditorPreview performs the deferred restart onto the wallpaper's
      // own persisted selection with whatever camdat is on disk by then.
      Log.i(TAG, "restart deferred: camtrack editor session active");
      return;
    }
    SharedPreferences prefs = getPrefs(context);
    sExecutor.execute(() -> {
      boolean wasRunning = sEngineStarted;
      if (wasRunning) {
        Sh1Native.stop();
        sEngineStarted = false;
      }
      Object[] scene = resolveBootScene(context, prefs);
      String glb = (String) scene[0];
      int track = (Integer) scene[1];
      String trackId = (String) scene[2];
      // v7 F11 + v9.2 F30: a per-wallpaper (track) override replaces the
      // per-scene (model) override, which replaces the global env/weather
      // at BOOT (argv --preset / --weather-target) — the engine's own boot
      // mechanism, no runtime cross-fade needed for the override case.
      int ovrEnv = getTrackEnvOverride(prefs, trackId);
      if (ovrEnv < 0) {
        ovrEnv = getSceneEnvOverride(prefs, glb);
      }
      int ovrWeather = getTrackWeatherOverride(prefs, trackId);
      if (ovrWeather < 0) {
        ovrWeather = getSceneWeatherOverride(prefs, glb);
      }
      // v9.6 (Camtrack v13): context-aware — the camdat day/night presets
      // participate in the boot resolution
      int bootPreset = ovrEnv >= 0 ? ovrEnv : getEffectiveEnv(context, prefs, glb, trackId);
      int bootWeather = ovrWeather >= 0 ? ovrWeather : getUserWeather(prefs);
      int[] res = getBootResolution(prefs);
      double fps = getBootFps(prefs);
      int wobble = getBootWobble(prefs);
      int dither = getBootDither(prefs);
      Sh1Native.start(Sh1Storage.engineDataPath(context),
          bootPreset, bootWeather, glb, track, res[0], res[1], fps, wobble,
          dither, getBootBackend(prefs));
      sEngineStarted = true;
      sUserCap = fps;
      sAppliedEnv = bootPreset;
      sAppliedScene = glb;
      sAppliedTrackId = trackId;
      sAppliedWeather = bootWeather != SH1.WEATHER_SCENE
          ? bootWeather : getSceneWeather(context);
      // v10.1 TASK 4: the fresh engine presents with the user's crop window
      float[] crop = getCropOffsets(prefs);
      Sh1Native.setCropOffset(crop[0], crop[1]);
      sAppliedCropX = crop[0];
      sAppliedCropY = crop[1];
      Log.i(TAG, "engine restart: scene " + glb + " track " + track
          + " preset " + bootPreset + " weather " + bootWeather
          + " res " + (res[0] > 0 ? res[0] + "x" + res[1] : "default")
          + " fps " + (int) fps);
      // re-assert the freeze state on the FRESH engine (a frozen restart
      // boots straight into the frozen single-frame state)
      applyFreezeState();
      // v7: pacing + post-start observer (watchdog/thumbnail)
      evaluatePacing(context, prefs);
      scheduleObserver(context, glb, track);
    });
  }

  /* ---- freeze control (Phase E-4/E-5 + v7 F10/F16/F15) ---- */

  private static void applyFreezeState() {
    if (sEditorSession) {
      return;   // v9.7: the editor preview is foreground interactive use —
                // the battery freeze would blank the preview mid-edit; the
                // state re-asserts when the session ends (restartEngine)
    }
    boolean freeze = sFreezeBattery || sFreezePowerSave || sFreezeAod || sFreezeManual;
    Sh1Native.freeze(freeze);
    if (freeze) {
      Log.i(TAG, "freeze: ON (battery=" + sFreezeBattery
          + ", powerSave=" + sFreezePowerSave
          + ", aod=" + sFreezeAod
          + ", manual=" + sFreezeManual + ") — one frame, 0 fps");
    }
  }

  /**
   * v7 G-1 fix + F10 + F12: (re)compute the battery-derived freeze AND
   * half-rate state from the CACHED battery broadcast. Called on
   * ACTION_BATTERY_CHANGED and on every SETTINGS_CHANGED so threshold /
   * exemption changes apply immediately.
   *
   * Battery precedence (documented, deterministic):
   *   critical freeze  >  mid-battery half-rate  >  normal pacing
   * The charging exemption (F10, default ON = the verified v6 behavior)
   * suspends BOTH battery-derived measures while plugged in — users
   * charging their device generally want motion.
   */
  private static void updateBatteryState(Context context, SharedPreferences prefs) {
    if (sBatteryPct < 0) {
      return;   // no battery data yet (sticky broadcast pending)
    }
    int critical = prefs.getInt(PREF.SH1_BATTERY_CRITICAL, DEF.SH1_BATTERY_CRITICAL);
    int mid = prefs.getInt(PREF.SH1_BATTERY_MID, DEF.SH1_BATTERY_MID);
    boolean chargeExempt = prefs.getBoolean(PREF.SH1_CHARGE_EXEMPT, DEF.SH1_CHARGE_EXEMPT);
    boolean halfrate = prefs.getBoolean(PREF.SH1_BATTERY_HALFRATE, DEF.SH1_BATTERY_HALFRATE);
    boolean charging = sBatteryCharging && chargeExempt;

    boolean shouldFreeze = sBatteryPct <= critical && !charging;
    if (shouldFreeze != sFreezeBattery) {
      sFreezeBattery = shouldFreeze;
      Log.i(TAG, "battery: " + sBatteryPct + "% (critical " + critical
          + ", charging " + sBatteryCharging + ") -> freeze " + shouldFreeze);
    }
    boolean halfRate = halfrate && !charging && !shouldFreeze && sBatteryPct <= mid;
    if (halfRate != sHalfRate) {
      sHalfRate = halfRate;
      Log.i(TAG, "battery: " + sBatteryPct + "% (mid " + mid + ", halfrate "
          + halfrate + ") -> 15 fps pacing " + halfRate);
    }
    applyFreezeState();
    evaluatePacing(context, prefs);
  }

  /** power-save compliance (Phase E-5) — queried, never assumed */
  private static void updatePowerSaveState(Context context, SharedPreferences prefs) {
    PowerManager pm = (PowerManager) context.getSystemService(POWER_SERVICE);
    boolean comply = prefs.getBoolean(PREF.SH1_POWER_SAVE_COMPLY, DEF.SH1_POWER_SAVE_COMPLY);
    boolean powerSave = pm != null && pm.isPowerSaveMode();
    sFreezePowerSave = powerSave && comply;
    applyFreezeState();
  }

  /* ---- v7 pacing (F1 governor + F12 half-rate + F18 cap) ---- */

  /**
   * The governor ladder: cap * 0.8, cap * 2/3 (30 -> 24 -> 20; for the
   * 60 fps duplicate mode 60 -> 48 -> 40). Only ever steps BELOW the
   * user-selected boot cap — the seam clamps to min(runtime cap, boot).
   */
  private static double governorCap() {
    double cap = sUserCap;
    switch (sGovernorStep) {
      case 1: return cap * 0.8;
      case 2: return cap * 2.0 / 3.0;
      default: return cap;
    }
  }

  /** push the effective runtime presentation cap to the native seam */
  private static void evaluatePacing(Context context, SharedPreferences prefs) {
    if (sEditorSession) {
      return;   // v9.7: the editor session runs at the boot cap
    }
    double cap = sUserCap;
    boolean adaptive = prefs.getBoolean(PREF.SH1_ADAPTIVE_PACING, DEF.SH1_ADAPTIVE_PACING);
    if (sHalfRate) {
      cap = Math.min(cap, SH1.HALFRATE_FPS);
      sGovernorStep = 0;   // half-rate wins over the governor (precedence)
    } else if (adaptive) {
      cap = Math.min(cap, governorCap());
    } else {
      sGovernorStep = 0;   // adaptive off: exactly the user cap
    }
    Sh1Native.setFpsCap(cap);
    Log.i(TAG, "pacing: presentation cap " + (int) cap + " fps"
        + (sHalfRate ? " (battery half-rate)" : "")
        + (sGovernorStep > 0 ? " (governor step " + sGovernorStep + ")" : ""));
  }

  /**
   * F1 adaptive pacing governor, on a 2 s main-thread cadence: measures
   * the engine's smoothed frame time (read-only stats seam) against the
   * current cap's budget. Steps DOWN when the budget is exceeded; steps
   * back UP only after ~10 s of clear headroom.
   */
  private void checkGovernor() {
    SharedPreferences prefs = getPrefs(this);
    boolean frozen = sFreezeBattery || sFreezePowerSave || sFreezeAod || sFreezeManual;
    if (sEngineStarted && !frozen && !sHalfRate
        && prefs.getBoolean(PREF.SH1_ADAPTIVE_PACING, DEF.SH1_ADAPTIVE_PACING)) {
      double[] st = Sh1Native.stats();
      double frameMs = st[1];
      double cap = governorCap();
      if (frameMs > 0.0 && cap > 0.0) {
        double budget = 1000.0 / cap;
        if (frameMs >= budget * 0.95) {
          if (sGovernorStep < 2) {
            sGovernorStep++;
            sGovernorGood = 0;
            Log.i(TAG, "governor: frame " + (int) frameMs + " ms over budget "
                + (int) budget + " ms -> step " + sGovernorStep
                + " (" + (int) governorCap() + " fps)");
            evaluatePacing(this, prefs);
          }
        } else if (frameMs <= budget * 0.60) {
          sGovernorGood++;
          if (sGovernorGood >= 5 && sGovernorStep > 0) {
            sGovernorStep--;
            sGovernorGood = 0;
            Log.i(TAG, "governor: headroom " + (int) frameMs + " ms -> step "
                + sGovernorStep + " (" + (int) governorCap() + " fps)");
            evaluatePacing(this, prefs);
          }
        } else {
          sGovernorGood = 0;
        }
      }
    }
    mainHandler.postDelayed(governorTick, 2000L);
  }

  /* ---- scene randomizer (Phase E-3 + v7 F2 shuffle bag / F3 favorites) ---- */

  /** the randomizer pool: every (model, track) with an enabled track */
  private static int[][] randomizerPool(Sh1Camdat camdat, SharedPreferences prefs) {
    java.util.HashSet<String> favorites = favoriteSet(prefs);
    boolean favoritesOnly = prefs.getBoolean(
        PREF.SH1_FAVORITES_ONLY, DEF.SH1_FAVORITES_ONLY) && !favorites.isEmpty();
    java.util.ArrayList<int[]> pool = new java.util.ArrayList<>();
    for (int m = 0; m < camdat.modelCount(); m++) {
      Sh1Camdat.Model model = camdat.getModel(m);
      if (!model.glbPresent) {
        continue;   // BYOA: no GLB, not playable
      }
      if (favoritesOnly && !favorites.contains(model.file)) {
        continue;   // F3: narrow the pool to starred scenes
      }
      for (int t = 0; t < model.tracks.size(); t++) {
        if (model.tracks.get(t).enabled) {
          pool.add(new int[]{m, t});
        }
      }
    }
    if (pool.isEmpty() && favoritesOnly) {
      // favorites point at scenes that are no longer playable: fall back
      // to the full pool rather than silently disabling the randomizer
      return randomizerPool(camdat, prefs, true);
    }
    return pool.toArray(new int[0][]);
  }

  private static int[][] randomizerPool(Sh1Camdat camdat, SharedPreferences prefs,
      boolean ignoreFavorites) {
    java.util.ArrayList<int[]> pool = new java.util.ArrayList<>();
    for (int m = 0; m < camdat.modelCount(); m++) {
      Sh1Camdat.Model model = camdat.getModel(m);
      if (!model.glbPresent) {
        continue;
      }
      for (int t = 0; t < model.tracks.size(); t++) {
        if (model.tracks.get(t).enabled) {
          pool.add(new int[]{m, t});
        }
      }
    }
    return pool.toArray(new int[0][]);
  }

  private static java.util.HashSet<String> favoriteSet(SharedPreferences prefs) {
    java.util.HashSet<String> set = new java.util.HashSet<>();
    String raw = prefs.getString(PREF.SH1_FAVORITES, DEF.SH1_FAVORITES);
    if (raw != null && !raw.isEmpty()) {
      for (String s : raw.split(",")) {
        if (!s.isEmpty()) {
          set.add(s);
        }
      }
    }
    return set;
  }

  /**
   * Draw the next randomizer pick: F2 shuffle bag (every pool entry once
   * before repeats) or the v6 plain uniform random, per the setting.
   */
  private int[] drawNext(int[][] pool, int curScene, int curTrack) {
    if (pool.length == 0) {
      return null;
    }
    boolean bag = getPrefs(this).getBoolean(PREF.SH1_SHUFFLE_BAG, DEF.SH1_SHUFFLE_BAG);
    if (!bag) {
      int[] pick = pool[sRandom.nextInt(pool.length)];
      if (pool.length > 1) {
        while (pick[0] == curScene && pick[1] == curTrack) {
          pick = pool[sRandom.nextInt(pool.length)];
        }
      }
      return pick;
    }
    synchronized (sBag) {
      if (sBag.isEmpty()) {
        refillBagLocked(pool, curScene, curTrack);
      }
      return sBag.pollFirst();
    }
  }

  /**
   * F2 refill: the whole pool, shuffled, with the CURRENT entry moved
   * to the END so an immediate repeat is impossible when possible.
   * Caller must hold sBag's monitor.
   */
  private static void refillBagLocked(int[][] pool, int curScene, int curTrack) {
    ArrayList<int[]> shuffled = new ArrayList<>(Arrays.asList(pool));
    for (int i = shuffled.size() - 1; i > 0; i--) {
      int j = sRandom.nextInt(i + 1);
      int[] tmp = shuffled.get(i);
      shuffled.set(i, shuffled.get(j));
      shuffled.set(j, tmp);
    }
    for (int i = 0; i < shuffled.size(); i++) {
      if (shuffled.get(i)[0] == curScene && shuffled.get(i)[1] == curTrack) {
        int[] cur = shuffled.remove(i);
        shuffled.add(cur);
        break;
      }
    }
    sBag.addAll(shuffled);
  }

  /** F4: the next pick WITHOUT applying it (pre-warm peek) */
  private int[] peekNext(int[][] pool, int curScene, int curTrack) {
    if (pool.length == 0) {
      return null;
    }
    boolean bag = getPrefs(this).getBoolean(PREF.SH1_SHUFFLE_BAG, DEF.SH1_SHUFFLE_BAG);
    if (!bag) {
      int[] pick = pool[sRandom.nextInt(pool.length)];
      if (pool.length > 1) {
        while (pick[0] == curScene && pick[1] == curTrack) {
          pick = pool[sRandom.nextInt(pool.length)];
        }
      }
      return pick;
    }
    synchronized (sBag) {
      if (sBag.isEmpty()) {
        // v48 WAJ-9: a peek must never CONSUME — the old drawNext() call
        // here refilled the bag and POLLED its head, so prewarmNextScene
        // warmed a scene the next randomizeScene would not pick (and the
        // bag sequence was perturbed). Refill exactly like drawNext,
        // then PEEK without polling.
        refillBagLocked(pool, curScene, curTrack);
      }
      return sBag.peekFirst();
    }
  }

  /**
   * Pick a random pool entry different from the current one (when the
   * pool allows it), persist it and switch the engine. Runs on the main
   * thread; the engine restart itself is serialized on the executor.
   */
  private void randomizeScene() {
    SharedPreferences prefs = getPrefs(this);
    Sh1Camdat camdat = Sh1Camdat.load(this);
    if (!camdat.isValid()) {
      return;
    }
    int[][] pool = randomizerPool(camdat, prefs);
    if (pool.length == 0) {
      Log.i(TAG, "randomizer: empty pool (no GLB present) — skipped");
      return;
    }
    int curScene = getActiveSceneIndex(prefs);
    int curTrack = prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK);
    int[] pick = drawNext(pool, curScene, curTrack);
    if (pick == null) {
      return;
    }
    prefs.edit()
        .putInt(PREF.SH1_SCENE, pick[0])
        .putInt(PREF.SH1_TRACK, pick[1])
        .apply();
    Log.i(TAG, "randomizer: scene " + camdat.getModel(pick[0]).file
        + " track " + pick[1] + " (" + pool.length + " options)");
    restartEngine(this);
  }

  /** daily/hourly anchors + the custom-interval schedule, on TIME_TICK */
  private void checkScheduledRandomizer(SharedPreferences prefs) {
    int mode = prefs.getInt(PREF.SH1_RANDOMIZER, DEF.SH1_RANDOMIZER);
    if (mode == SH1.RANDOM_OFF || !sEngineStarted) {
      return;
    }
    java.util.Calendar now = java.util.Calendar.getInstance();
    long minuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60L
        + now.get(java.util.Calendar.MINUTE);
    long dayAnchor = now.get(java.util.Calendar.YEAR) * 1000L
        + now.get(java.util.Calendar.DAY_OF_YEAR);
    long hourAnchor = dayAnchor * 24L + now.get(java.util.Calendar.HOUR_OF_DAY);
    if (mode == SH1.RANDOM_DAILY
        && prefs.getLong("sh1_random_last_day", -1) != dayAnchor) {
      prefs.edit().putLong("sh1_random_last_day", dayAnchor)
          .putLong("sh1_random_last_hour", hourAnchor).apply();
      randomizeScene();
    } else if (mode == SH1.RANDOM_HOURLY
        && prefs.getLong("sh1_random_last_hour", -1) != hourAnchor) {
      prefs.edit().putLong("sh1_random_last_hour", hourAnchor).apply();
      randomizeScene();
    } else if (mode == SH1.RANDOM_INTERVAL) {
      long interval = Math.max(1, prefs.getInt(
          PREF.SH1_RANDOM_INTERVAL, DEF.SH1_RANDOM_INTERVAL));
      long last = prefs.getLong("sh1_random_next", 0);
      long nowMin = System.currentTimeMillis() / 60000L;
      if (last == 0) {
        prefs.edit().putLong("sh1_random_next", nowMin + interval).apply();
      } else if (nowMin >= last) {
        prefs.edit().putLong("sh1_random_next", nowMin + interval).apply();
        randomizeScene();
      }
    }
  }

  private void checkIntervalRandomizer() {
    SharedPreferences prefs = getPrefs(this);
    int mode = prefs.getInt(PREF.SH1_RANDOMIZER, DEF.SH1_RANDOMIZER);
    if (mode == SH1.RANDOM_INTERVAL && sEngineStarted) {
      long interval = Math.max(1, prefs.getInt(
          PREF.SH1_RANDOM_INTERVAL, DEF.SH1_RANDOM_INTERVAL));
      long last = prefs.getLong("sh1_random_next", 0);
      long nowMin = System.currentTimeMillis() / 60000L;
      if (last == 0) {
        prefs.edit().putLong("sh1_random_next", nowMin + interval).apply();
      } else if (nowMin >= last) {
        prefs.edit().putLong("sh1_random_next", nowMin + interval).apply();
        randomizeScene();
      }
    }
    scheduleIntervalTick();
  }

  private void scheduleIntervalTick() {
    mainHandler.removeCallbacks(randomizerIntervalTick);
    mainHandler.postDelayed(randomizerIntervalTick, 60000L);   // 1 min cadence
  }

  /* ---- v7 F6 watchdog + F9 thumbnails (post-start observer) ---- */

  /** v48 WAJ-8: true when ANY live engine reports visible — the shared
   *  native engine presents while any of its surface consumers shows it,
   *  so one engine's invisible report must not disable the watchdog or
   *  unregister the tilt sensor for the still-visible one. */
  private static boolean anyEngineVisible() {
    for (Sh1Engine engine : sLiveEngines) {
      if (engine.anyVisible) {
        return true;
      }
    }
    return false;
  }

  private static void scheduleObserver(Context context, String glb, int track) {
    if (sEditorSession) {
      return;   // v9.7: the watchdog/thumbnail observer tracks the
                // WALLPAPER, not the editor preview session
    }
    final int gen = sBootGen.incrementAndGet();
    final String scene = glb;
    final int trackIdx = track;
    sObserver.execute(() -> {
      try {
        Thread.sleep(2000L);
      } catch (InterruptedException e) {
        return;
      }
      if (gen != sBootGen.get() || !sEngineStarted) {
        return;   // superseded by a newer boot
      }
      SharedPreferences prefs = getPrefs(context);
      // F9 + v9.2 F30: cache the engine's own presented frame as the
      // PER-WALLPAPER (per-track) thumbnail
      if (prefs.getBoolean(PREF.SH1_THUMBNAILS, DEF.SH1_THUMBNAILS)) {
        saveThumbnail(context, scene, trackIdx);
      }
      // F6: two consecutive all-black (or missing/dead) frames ~3.0 s and
      // ~4.5 s after start -> fall back to the bundled scene. Guarded by
      // visibility so an engine that simply never became visible (preview
      // edge case) can not trigger a false fallback.
      if (!prefs.getBoolean(PREF.SH1_WATCHDOG, DEF.SH1_WATCHDOG) || !anyEngineVisible()) {
        return;
      }
      if (!frameLooksBroken()) {
        return;
      }
      try {
        Thread.sleep(1500L);
      } catch (InterruptedException e) {
        return;
      }
      if (gen != sBootGen.get() || !sEngineStarted || !frameLooksBroken()) {
        return;
      }
      fallbackToBundledScene(context, scene);
    });
  }

  /** true when the engine is dead or its presented frame is entirely black */
  private static boolean frameLooksBroken() {
    if (!Sh1Native.isRunning()) {
      Log.w(TAG, "watchdog: engine thread not running");
      return true;
    }
    int[] px = Sh1Native.grabFrame();
    if (px == null) {
      return true;   // nothing presented at all
    }
    for (int p : px) {
      if (p != 0) {
        return false;   // any non-black pixel = a live picture
      }
    }
    return true;
  }

  /**
   * F6 fallback: switch to the bundled/default scene (THRF9FD.glb). A
   * genuinely broken import is abandoned; an already-bundled selection
   * only logs (no fallback loop).
   */
  private static void fallbackToBundledScene(Context context, String brokenScene) {
    Log.w(TAG, "watchdog: scene " + brokenScene
        + " presents no picture — falling back to the bundled scene");
    SharedPreferences prefs = getPrefs(context);
    Sh1Camdat camdat = Sh1Camdat.load(context);
    int bundled = -1;
    for (int i = 0; i < camdat.modelCount(); i++) {
      if ("THRF9FD.glb".equals(camdat.getModel(i).file)) {
        bundled = i;
        break;
      }
    }
    if (bundled < 0 || bundled == getActiveSceneIndex(prefs)) {
      Log.w(TAG, "watchdog: already on the bundled/default scene — no fallback");
      return;
    }
    prefs.edit()
        .putInt(PREF.SH1_SCENE, bundled)
        .putInt(PREF.SH1_TRACK, DEF.SH1_TRACK)
        .apply();
    restartEngine(context);
  }

  /**
   * F9 + v9.2 F30: snapshot the presented frame into
   * filesDir/thumbnails/<scene>_t<track>.png — ONE cache file per
   * individual wallpaper (track), so every circle in the selector can
   * carry its own real render.
   */
  private static void saveThumbnail(Context context, String glb, int track) {
    try {
      int[] px = Sh1Native.grabFrame();
      if (px == null) {
        return;
      }
      double[] st = Sh1Native.stats();
      int w = (int) st[3];
      int h = (int) st[4];
      if (w <= 0 || h <= 0 || px.length < w * h) {
        return;
      }
      Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
      File dir = Sh1Storage.thumbnailsDir(context);
      if (!dir.exists()) {
        dir.mkdirs();
      }
      File out = new File(dir, glb + "_t" + track + ".png");
      try (FileOutputStream fos = new FileOutputStream(out)) {
        bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
      }
      bmp.recycle();
      Log.i(TAG, "thumbnail: cached " + out.getName());
    } catch (Exception e) {
      Log.e(TAG, "saveThumbnail: failed", e);
    }
  }

  /**
   * F9 + v9.2 F30: the cached thumbnail of ONE wallpaper (its track
   * index inside the model); null when not yet rendered
   */
  public static File thumbnailFile(Context context, String glbFile, int trackIndex) {
    return Sh1Storage.thumbnailFile(context, glbFile, trackIndex);
  }

  /* ---- v7 F15 tile entry points (same safe statics the UI uses) ---- */

  /**
   * Deterministic "next scene": v9.2 F30 — every track is an individual
   * wallpaper, so this now cycles the flattened (model, track) list
   * (GLB-present models, ALL their tracks, camdat order) instead of only
   * the models. With a single-model camdat the tile therefore still
   * advances through that model's distinct wallpapers. Used by the Quick
   * Settings tile and reusable elsewhere.
   */
  public static void requestNextScene(Context context) {
    SharedPreferences prefs = getPrefs(context);
    Sh1Camdat camdat = Sh1Camdat.load(context);
    if (!camdat.isValid()) {
      restartEngine(context);
      return;
    }
    int cur = getActiveSceneIndex(prefs);
    int curTrack = prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK);
    java.util.ArrayList<int[]> list = new java.util.ArrayList<>();
    for (int m = 0; m < camdat.modelCount(); m++) {
      Sh1Camdat.Model model = camdat.getModel(m);
      if (model == null || !model.glbPresent) {
        continue;
      }
      for (int t = 0; t < model.tracks.size(); t++) {
        list.add(new int[]{m, t});
      }
    }
    if (list.isEmpty()) {
      restartEngine(context);
      return;
    }
    int pos = -1;
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i)[0] == cur && list.get(i)[1] == curTrack) {
        pos = i;
        break;
      }
    }
    int[] next = list.get((pos + 1 + list.size()) % list.size());
    if (next[0] != cur || next[1] != curTrack) {
      prefs.edit()
          .putInt(PREF.SH1_SCENE, next[0])
          .putInt(PREF.SH1_TRACK, next[1])
          .apply();
    }
    Sh1Camdat.Model model = camdat.getModel(next[0]);
    Sh1Camdat.Track track = model != null ? model.track(next[1]) : null;
    Log.i(TAG, "next scene: " + (model != null ? model.file : "?")
        + " track " + next[1] + (track != null ? " '" + track.name + "'" : ""));
    restartEngine(context);
  }

  /** F15 manual freeze tile: toggle the user freeze contributor */
  public static void setManualFreeze(Context context, boolean on) {
    getPrefs(context).edit().putBoolean(PREF.SH1_MANUAL_FREEZE, on).apply();
    sFreezeManual = on;
    applyFreezeState();
    Log.i(TAG, "manual freeze (tile): " + on);
  }

  public static boolean isManuallyFrozen(SharedPreferences prefs) {
    return prefs.getBoolean(PREF.SH1_MANUAL_FREEZE, DEF.SH1_MANUAL_FREEZE);
  }

  /* ---- v9 F23 + v9.1 Fix 3: device-tilt camera pipeline ------------ */

  private SensorManager mSensorManager;
  private SensorEventListener mTiltListener;
  private boolean mTiltRegistered = false;

  /** reference orientation (radians) captured when tracking starts */
  private float mTiltRefPitch = 0f, mTiltRefRoll = 0f;
  private boolean mTiltHasRef = false;
  /**
   * v9.1 Fix 3 (sensor-data smoothing): one-pole LOW-PASS-FILTERED angle
   * deviations from the reference, degrees. Raw device sensor data
   * (accelerometer/gyroscope fusion) is notoriously noisy; filtering the
   * INPUTS (before the deadband) keeps minute noise from chattering the
   * engage/release boundary AND keeps the mapped pan free of noise wobble.
   * tau = 0.25 s: rejects even pessimistic (sub-degree) sensor noise at
   * any polling rate while staying responsive. Event-rate independent
   * weight k = 1 - exp(-dt / 0.25 s).
   */
  private double mTiltFiltPitchDev = 0.0, mTiltFiltRollDev = 0.0;
  /**
   * v9.1 Fix 3 (frame interpolation): the TARGET offsets handed to the
   * engine — the ENGINE glides toward them every rendered frame
   * (lerp in fog_poc.c, tau 0.15 s), so the camera pans buttery-smooth at
   * 30 fps regardless of the sensor polling rate (1-20 Hz).
   */
  private double mTiltYawTarget = 0.0, mTiltPitchTarget = 0.0;
  /** hysteresis state (engaged per axis) */
  private boolean mTiltYawEngaged = false, mTiltPitchEngaged = false;
  /** last event time for the low-pass dt */
  private long mTiltLastNanos = 0L;

  /**
   * The deadband + hysteresis + input-low-pass + engine-lerp pipeline,
   * per the F23/Fix-3 rules: ONLY significant tilts (default 15 deg, user
   * adjustable 5..45) move the camera; minute changes are ignored
   * entirely. Beyond the threshold the camera shift rises from 1 deg
   * toward the user's max (default 3, range 1..20 per v9.1 Fix 3) with
   * the significance of the deviation (full intensity at +30 deg).
   * Smoothing is TWO-STAGE: (1) this callback low-passes the raw sensor
   * ANGLES (tau 0.25 s — noise rejection); (2) the engine frame-
   * interpolates the rendered offset toward the published target (lerp,
   * tau 0.15 s — glide). The engine-side seam applies the offset to the
   * RENDERED pose only; the authentic camtrack camera is untouched.
   */
  private void handleTiltEvent(SensorEvent event) {
    if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
      return;
    }
    /* frozen (battery saver / critical battery / AOD / manual tile): the
     * surface renders nothing — tilt is fully disabled per the F23 rule */
    if (sFreezeBattery || sFreezePowerSave || sFreezeAod || sFreezeManual) {
      return;
    }
    SharedPreferences prefs = getPrefs(this);
    int threshold = clampPref(prefs.getInt(PREF.SH1_TILT_THRESHOLD, DEF.SH1_TILT_THRESHOLD), 5, 45);
    int maxShift = clampPref(prefs.getInt(PREF.SH1_TILT_MAX, DEF.SH1_TILT_MAX), 1, 20);
    int minShift = 1;   /* the F23 floor: just past the threshold ~1 deg */

    float[] rot = new float[9];
    float[] orientation = new float[3];
    try {
      SensorManager.getRotationMatrixFromVector(rot, event.values);
      SensorManager.getOrientation(rot, orientation);
    } catch (Exception e) {
      return;
    }
    // wrap the angles to [-pi, pi] for stable deviation math
    float pitch = wrapPi(orientation[1]);
    float roll = wrapPi(orientation[2]);

    if (!mTiltHasRef) {
      mTiltRefPitch = pitch;
      mTiltRefRoll = roll;
      mTiltHasRef = true;
      mTiltFiltPitchDev = 0.0;
      mTiltFiltRollDev = 0.0;
    }

    // raw wrapped deviations from the reference, degrees
    double pitchDevRaw = Math.toDegrees(wrapPi(pitch - mTiltRefPitch));
    double rollDevRaw = Math.toDegrees(wrapPi(roll - mTiltRefRoll));

    // v9.1 Fix 3 stage 1: sensor-data smoothing — one-pole low-pass
    // (tau = 0.25 s) on the deviation inputs, event-rate independent
    long now = event.timestamp;
    double dt = mTiltLastNanos == 0L ? 0.05 : (now - mTiltLastNanos) / 1e9;
    mTiltLastNanos = now;
    if (dt > 0 && dt < 1.0) {
      double k = 1.0 - Math.exp(-dt / 0.25);
      mTiltFiltPitchDev += (pitchDevRaw - mTiltFiltPitchDev) * k;
      mTiltFiltRollDev += (rollDevRaw - mTiltFiltRollDev) * k;
    } else {
      mTiltFiltPitchDev = pitchDevRaw;
      mTiltFiltRollDev = rollDevRaw;
    }
    double pitchDev = mTiltFiltPitchDev;
    double rollDev = mTiltFiltRollDev;

    // per-axis deadband + hysteresis on the FILTERED deviations: engage
    // at the threshold, release 3 degrees below it (kills chatter)
    double hyst = 3.0;
    mTiltPitchEngaged = mTiltPitchEngaged
        ? Math.abs(pitchDev) > threshold - hyst
        : Math.abs(pitchDev) >= threshold;
    mTiltYawEngaged = mTiltYawEngaged
        ? Math.abs(rollDev) > threshold - hyst
        : Math.abs(rollDev) >= threshold;

    // significance beyond the threshold; full intensity at +30 degrees
    double targetYaw = 0.0, targetPitch = 0.0;
    if (mTiltYawEngaged) {
      double mag = Math.min(Math.abs(rollDev) - threshold, 30.0) / 30.0;
      double shift = minShift + (maxShift - minShift) * mag;
      targetYaw = -Math.signum(rollDev) * shift;   /* tilt right -> look right */
    }
    if (mTiltPitchEngaged) {
      double mag = Math.min(Math.abs(pitchDev) - threshold, 30.0) / 30.0;
      double shift = minShift + (maxShift - minShift) * mag;
      targetPitch = -Math.signum(pitchDev) * shift;  /* tilt up -> look up */
    }

    // stage 2 happens in the ENGINE: the render loop lerps toward these
    // targets every frame (fog_poc.c and_tilt_* locals) — the published
    // value is a TARGET, not a step the raster snaps to
    mTiltYawTarget = targetYaw;
    mTiltPitchTarget = targetPitch;
    Sh1Native.setTilt(mTiltYawTarget, mTiltPitchTarget);
  }

  private static float wrapPi(float a) {
    while (a > Math.PI) a -= (float) (2 * Math.PI);
    while (a < -Math.PI) a += (float) (2 * Math.PI);
    return a;
  }

  /**
   * Register/unregister the rotation-vector sensor by the F23 rules:
   * tracking runs ONLY while the wallpaper is visible, NOT frozen (any
   * contributor — battery, power saver, AOD, manual tile: a frozen
   * surface renders nothing anyway, and the mandate requires full
   * battery-saver disablement) and the toggle is on. Unregistering also
   * neutralizes the engine offset.
   */
  private void updateTiltPipelines() {
    if (mSensorManager == null || mTiltListener == null) {
      return;
    }
    SharedPreferences prefs = getPrefs(this);
    boolean frozen = sFreezeBattery || sFreezePowerSave || sFreezeAod || sFreezeManual;
    boolean want = prefs.getBoolean(PREF.SH1_TILT, DEF.SH1_TILT)
        && sEngineStarted && anyEngineVisible() && !frozen;
    if (want && !mTiltRegistered) {
      Sensor rot = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
      if (rot == null) {
        Log.w(TAG, "tilt: no rotation-vector sensor on this device");
        return;
      }
      int rate = clampPref(prefs.getInt(PREF.SH1_TILT_RATE, DEF.SH1_TILT_RATE), 1, 20);
      mSensorManager.registerListener(
          mTiltListener, rot, (int) (1_000_000 / rate));
      mTiltRegistered = true;
      mTiltHasRef = false;        // capture a fresh reference on activation
      mTiltLastNanos = 0L;
      Log.i(TAG, "tilt: tracking at " + rate + " Hz");
    } else if (!want && mTiltRegistered) {
      mSensorManager.unregisterListener(mTiltListener);
      mTiltRegistered = false;
      mTiltFiltPitchDev = 0.0;
      mTiltFiltRollDev = 0.0;
      mTiltYawTarget = 0.0;
      mTiltPitchTarget = 0.0;
      mTiltYawEngaged = mTiltPitchEngaged = false;
      Sh1Native.setTilt(0.0, 0.0);   // neutralize the engine offset
      Log.i(TAG, "tilt: tracking off (offset neutralized)");
    }
  }

  /* ---- service lifecycle ---- */

  @Override
  public void onCreate() {
    super.onCreate();

    mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
    mTiltListener = new SensorEventListener() {
      @Override
      public void onSensorChanged(SensorEvent event) {
        handleTiltEvent(event);
      }

      @Override
      public void onAccuracyChanged(Sensor sensor, int accuracy) {
      }
    };

    receiver = new BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
          return;
        }
        SharedPreferences prefs = getPrefs(Sh1WallpaperService.this);
        switch (intent.getAction()) {
          case ACTION.SETTINGS_CHANGED:
            applySettingsToEngine(Sh1WallpaperService.this, prefs);
            checkScheduledRandomizer(prefs);
            // v7 G-1 fix: threshold/toggle changes re-evaluate the
            // battery + power-save freeze IMMEDIATELY (cached state)
            updateBatteryState(Sh1WallpaperService.this, prefs);
            updatePowerSaveState(Sh1WallpaperService.this, prefs);
            evaluatePacing(Sh1WallpaperService.this, prefs);
            updateTiltPipelines();
            break;
          case Intent.ACTION_TIME_TICK:
          case Intent.ACTION_TIME_CHANGED:
          case Intent.ACTION_TIMEZONE_CHANGED:
            refreshAutoMode(Sh1WallpaperService.this, prefs);
            checkScheduledRandomizer(prefs);
            break;
          case Intent.ACTION_SCREEN_OFF:
            if (prefs.getInt(PREF.SH1_RANDOMIZER, DEF.SH1_RANDOMIZER)
                == SH1.RANDOM_LOCK && sEngineStarted) {
              randomizeScene();
            }
            // v7 F16: AOD/ambient freeze contributor (trigger addition —
            // the render-once/freeze mechanism is the existing one)
            if (prefs.getBoolean(PREF.SH1_AOD_FREEZE, DEF.SH1_AOD_FREEZE)) {
              sFreezeAod = true;
              applyFreezeState();
            }
            break;
          case Intent.ACTION_SCREEN_ON:
            // F16: leaving the ambient state re-evaluates the freeze
            sFreezeAod = false;
            if (prefs.getBoolean(PREF.SH1_AOD_FREEZE, DEF.SH1_AOD_FREEZE)) {
              applyFreezeState();
            }
            break;
          case Intent.ACTION_BATTERY_CHANGED: {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
            if (level >= 0 && scale > 0) {
              // v7: cache the state so SETTINGS_CHANGED can re-evaluate
              sBatteryPct = level * 100 / scale;
              sBatteryCharging = charging;
              updateBatteryState(Sh1WallpaperService.this, prefs);
              updateTiltPipelines();
            }
            break;
          }
          case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
            updatePowerSaveState(Sh1WallpaperService.this, prefs);
            updateTiltPipelines();
            break;
          case Intent.ACTION_USER_UNLOCKED:
            // v10.6 DIRECT BOOT: credential-encrypted storage just became
            // readable. A pre-v10.6 install keeps its engine data in CE —
            // fold it into the device-protected home and, when anything
            // actually moved (custom camdat / imported GLBs replaced the
            // bundled fallback that booted at BFU), re-boot the wallpaper
            // onto the user's real scene. Guarded by sEngineStarted so a
            // never-booted engine is never started headlessly (v10.1 BUG 2).
            if (Sh1Storage.migrateLegacyCeData(Sh1WallpaperService.this)) {
              Log.i(TAG, "unlock: legacy engine data migrated"
                  + (sEngineStarted ? " — restarting the wallpaper" : ""));
              if (sEngineStarted) {
                restartEngine(Sh1WallpaperService.this);
              }
            }
            break;
        }
      }
    };

    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION.SETTINGS_CHANGED);
    filter.addAction(Intent.ACTION_TIME_TICK);
    filter.addAction(Intent.ACTION_TIME_CHANGED);
    filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    filter.addAction(Intent.ACTION_SCREEN_OFF);
    filter.addAction(Intent.ACTION_SCREEN_ON);
    filter.addAction(Intent.ACTION_BATTERY_CHANGED);
    filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
    filter.addAction(Intent.ACTION_USER_UNLOCKED);   // v10.6: CE data migration
    try {
      if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
        // SETTINGS_CHANGED is app-internal (every sender uses
        // setPackage(getPackageName())); the rest are protected system
        // broadcasts, which are still delivered to NOT_EXPORTED receivers.
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        registerReceiver(receiver, filter);
      }
      receiverRegistered = true;
    } catch (Exception e) {
      Log.e(TAG, "onCreate: registerReceiver failed", e);
    }

    // initial freeze state (sticky battery broadcast arrives right away;
    // the power-save state is queried directly)
    updatePowerSaveState(this, getPrefs(this));
    sFreezeManual = isManuallyFrozen(getPrefs(this));
    applyFreezeState();
    scheduleIntervalTick();
    mainHandler.postDelayed(governorTick, 2000L);
    updateTiltPipelines();
  }

  @Override
  public void onDestroy() {
    mainHandler.removeCallbacks(randomizerIntervalTick);
    mainHandler.removeCallbacks(governorTick);
    updateTiltPipelines();   // unregisters the sensor + neutralizes the offset
    if (receiverRegistered) {
      try {
        unregisterReceiver(receiver);
      } catch (Exception e) {
        Log.e(TAG, "onDestroy: unregisterReceiver failed", e);
      }
      receiverRegistered = false;
    }
    super.onDestroy();
  }

  public static boolean isEngineRunning() {
    return sEngineStarted;
  }

  /** restart the engine for the persisted scene (used by the settings UI) */
  public static void requestSceneRestart(Context context) {
    restartEngine(context);
  }

  /* ---- v9.7: the Camtrack editor's LIVE-PREVIEW engine session -------- */

  /**
   * A fresh surface-binding generation for the editor's preview Surface
   * (the native side's newest-live-wins table: the editor's binding is
   * always newer than any wallpaper engine's, so the preview owns the
   * raster while the editor is open).
   */
  public static int newBindingGen() {
    return sGen.incrementAndGet();
  }

  /**
   * The editor takes over the process engine for its live preview: stop
   * the wallpaper instance (its own K_QUIT path) and boot the EDITOR's
   * scene/track/env. Serialized on sExecutor exactly like every other
   * engine lifecycle mutation; while the session is active all other
   * engine-state writers in this service early-return.
   *
   * @param glb the model file basename from the edited camdat
   * @param trackIdx the track index within that model (the engine's own
   *                 --track N, engine-valid-track indexing)
   * @param preset -1 = the camdat env pair wins (the \"scene\" phase);
   *                0..20 = the Day/Night lens preset (applied as {N,N} —
   *                the v13 editor's lens semantics)
   * @param weather -1 = the camdat recipe wins (the engine folds the
   *                 SELECTED track's own weatherTarget at boot — the
   *                 authentic path); 0..8 = an explicit boot target
   */
  public static void beginEditorPreview(
      Context context, String glb, int trackIdx, int preset, int weather) {
    SharedPreferences prefs = getPrefs(context);
    sExecutor.execute(() -> {
      sEditorSession = true;
      // v10.1 BUG 2: the standalone preview session must be able to boot
      // with the wallpaper NEVER set: ensure the engine's data files
      // exist in filesDir/sh1 BEFORE Sh1Native.start (the wallpaper
      // path did this in Sh1Engine.onSurfaceCreated — which never ran)
      try {
        copyAssetsIfNeeded(context);
      } catch (RuntimeException e) {
        Log.e(TAG, "editor preview: asset copy failed", e);
      }
      if (sEngineStarted) {
        Sh1Native.stop();
      }
      int[] res = getBootResolution(prefs);
      double fps = getBootFps(prefs);
      int wobble = getBootWobble(prefs);
      int dither = getBootDither(prefs);
      Sh1Native.start(Sh1Storage.engineDataPath(context),
          preset, weather, glb, trackIdx, res[0], res[1], fps, wobble,
          dither, getBootBackend(prefs));
      sEngineStarted = true;
      sUserCap = fps;
      // v10.1 TASK 4: the preview engine carries the crop state too (the
      // preview surface's aspect == the raster's, so the offsets are inert
      // while previewing — the full frame + the overlay shows the crop —
      // but the state is consistent for the session handover)
      float[] crop = getCropOffsets(prefs);
      Sh1Native.setCropOffset(crop[0], crop[1]);
      sAppliedCropX = crop[0];
      sAppliedCropY = crop[1];
      // the editor preview is foreground interactive use: never frozen,
      // never governor-paced; the wallpaper re-asserts both after the
      // session ends (endEditorPreview -> restartEngine)
      Sh1Native.freeze(false);
      Sh1Native.setFpsCap(-1);
      Log.i(TAG, "editor preview: scene " + glb + " track " + trackIdx
          + " preset " + (preset >= 0 ? preset : "camdat-pair")
          + " weather " + (weather >= 0 ? weather : "camdat-recipe"));
    });
  }

  /**
   * Re-boot the preview onto a new (scene, track) or phase preset — the
   * editor's committed-edit / track-switch path. Same serialization; the
   * session flag stays set.
   */
  public static void updateEditorPreview(
      Context context, String glb, int trackIdx, int preset, int weather) {
    if (!sEditorSession) {
      return;
    }
    SharedPreferences prefs = getPrefs(context);
    sExecutor.execute(() -> {
      // v48 WAJ-12: re-check the session INSIDE the serialized task — the
      // caller-thread check above races a session-end flip: endEditorPreview
      // (its own sExecutor task) could restore the wallpaper BETWEEN the
      // check and this task's run, leaving a stale task stopping the
      // wallpaper engine and booting the preview after the session ended.
      if (!sEditorSession) {
        return;
      }
      // v10.1 BUG 2: same standalone-session guarantee as begin (the
      // idempotent copy is a no-op after the first call)
      try {
        copyAssetsIfNeeded(context);
      } catch (RuntimeException e) {
        Log.e(TAG, "editor preview update: asset copy failed", e);
      }
      if (sEngineStarted) {
        Sh1Native.stop();
      }
      int[] res = getBootResolution(prefs);
      double fps = getBootFps(prefs);
      int wobble = getBootWobble(prefs);
      int dither = getBootDither(prefs);
      Sh1Native.start(Sh1Storage.engineDataPath(context),
          preset, weather, glb, trackIdx, res[0], res[1], fps, wobble,
          dither, getBootBackend(prefs));
      sEngineStarted = true;
      Sh1Native.freeze(false);
      Sh1Native.setFpsCap(-1);
      // v10.1 TASK 4: re-assert the crop window on the rebooted preview
      // engine (the native presentation statics survive stop/start within
      // the process, but the explicit push keeps the session state
      // canonical — identical to the begin path)
      float[] crop = getCropOffsets(prefs);
      Sh1Native.setCropOffset(crop[0], crop[1]);
      sAppliedCropX = crop[0];
      sAppliedCropY = crop[1];
      Log.i(TAG, "editor preview update: scene " + glb + " track " + trackIdx
          + " preset " + (preset >= 0 ? preset : "camdat-pair")
          + " weather " + (weather >= 0 ? weather : "camdat-recipe"));
    });
  }

  /**
   * End the session and hand the engine back to the wallpaper: the normal
   * restartEngine boots the persisted selection with whatever camdat is
   * on disk (the editor saved its doc before ending). The flag flip and
   * the restart run in ONE queued task — restartEngine's guard reads the
   * flag (already false) and its stop() takes down the preview engine
   * through its own K_QUIT path before the wallpaper's argv boots.
   *
   * v10.1 BUG 2: with ZERO live wallpaper engines (the wallpaper is not
   * set / not active) there is NO consumer to hand the raster to — a
   * restart would leave a headless engine burning CPU at full fps with
   * no surface. The engine is STOPPED cleanly instead; when the user
   * later enables the wallpaper, its own Sh1Engine.onSurfaceCreated
   * boots fresh from the persisted selection (assets are guaranteed on
   * disk by the session's begin path, or by the wallpaper's own path).
   */
  public static void endEditorPreview(Context context) {
    sExecutor.execute(() -> {
      if (!sEditorSession) {
        return;
      }
      sEditorSession = false;
      if (sEngineInstances.get() > 0) {
        // sEngineStarted stays TRUE so the restart below stops the preview
        // engine instead of skipping the stop (JNI start is idempotent-
        // guarded by g_started — a skipped stop would swallow the restart)
        restartEngine(context);
      } else {
        // standalone session (wallpaper never active): stop the engine
        // outright — no headless restart
        if (sEngineStarted) {
          Sh1Native.stop();
          sEngineStarted = false;
        }
        Log.i(TAG, "editor preview: standalone session ended — engine stopped "
            + "(no live wallpaper engine to restore)");
      }
    });
  }

  private final class Sh1Engine extends Engine {

    /** identity of this engine instance for the native side */
    private final int mGen = sGen.incrementAndGet();

    /** v48 WAJ-8: THIS engine's last reported visibility (per-instance;
     *  read via anyEngineVisible()) */
    volatile boolean anyVisible = false;

    Sh1Engine() {
      // v10.1 BUG 2: track live wallpaper engines so the editor's
      // standalone session teardown knows whether any consumer exists
      sEngineInstances.incrementAndGet();
      // v48 WAJ-8: per-engine visibility registry (see anyEngineVisible)
      sLiveEngines.add(this);
    }

    @Override
    public void onSurfaceCreated(SurfaceHolder holder) {
      super.onSurfaceCreated(holder);
      Sh1Native.surfaceCreated(mGen, holder.getSurface());
      // v48 WAJ-10: the first-run asset copy (per-file SHA-256 + stream
      // copy of the ~61 MB bundled corpus) ran on the MAIN thread here —
      // WallpaperService engine callbacks are main-thread, an ANR risk.
      // Mirror the editor path (beginEditorPreview): copy + boot on the
      // single engine executor (sExecutor), the copy BEFORE the start,
      // so the engine never boots without its data files and
      // Sh1Native.start still happens exactly once (the !sEngineStarted
      // guard now runs INSIDE the serialized task).
      // v9.7: while the Camtrack editor preview session owns the engine,
      // a (re)created wallpaper surface only BINDS (a lower generation —
      // newest-live-wins keeps the editor's preview on the raster); the
      // wallpaper's own boot happens at endEditorPreview -> restartEngine
      sExecutor.execute(() -> {
        try {
          copyAssetsIfNeeded(Sh1WallpaperService.this);
        } catch (RuntimeException e) {
          Log.e(TAG, "engine boot: asset copy failed", e);
        }
        if (!sEngineStarted && !sEditorSession) {
          sEngineStarted = true;
          SharedPreferences prefs = getPrefs(Sh1WallpaperService.this);
          // Boot argv: --glb <scene> --track N --preset <effective env>
          // + optional --weather-target (user override; -1 keeps the
          // scene's own camdat recipe) + v7 --res/--fps (F17/F18).
          // The native side re-reads nothing: these ARE the persisted prefs.
          Object[] scene = resolveBootScene(Sh1WallpaperService.this, prefs);
          String glb = (String) scene[0];
          int track = (Integer) scene[1];
          String trackId = (String) scene[2];
          int ovrEnv = getTrackEnvOverride(prefs, trackId);
          if (ovrEnv < 0) {
            ovrEnv = getSceneEnvOverride(prefs, glb);
          }
          int ovrWeather = getTrackWeatherOverride(prefs, trackId);
          if (ovrWeather < 0) {
            ovrWeather = getSceneWeatherOverride(prefs, glb);
          }
          int bootPreset = ovrEnv >= 0 ? ovrEnv
              : getEffectiveEnv(Sh1WallpaperService.this, prefs, glb, trackId);
          int bootWeather = ovrWeather >= 0 ? ovrWeather : getUserWeather(prefs);
          int[] res = getBootResolution(prefs);
          double fps = getBootFps(prefs);
          int wobble = getBootWobble(prefs);
          int dither = getBootDither(prefs);
          Sh1Native.start(Sh1Storage.engineDataPath(Sh1WallpaperService.this),
              bootPreset, bootWeather, glb, track, res[0], res[1], fps, wobble,
              dither, getBootBackend(prefs));
          sUserCap = fps;
          sAppliedEnv = bootPreset;
          sAppliedScene = glb;
          sAppliedTrackId = trackId;
          sAppliedWeather = bootWeather != SH1.WEATHER_SCENE
              ? bootWeather : getSceneWeather(Sh1WallpaperService.this);
          // v10.1 TASK 4: the fresh engine presents with the user's crop window
          float[] crop = getCropOffsets(prefs);
          Sh1Native.setCropOffset(crop[0], crop[1]);
          sAppliedCropX = crop[0];
          sAppliedCropY = crop[1];
          Log.i(TAG, "engine boot: scene " + glb + " track " + track
              + ", preset " + bootPreset + " (" + SH1.getEnvModeName(bootPreset)
              + "), weather " + (bootWeather == SH1.WEATHER_SCENE
              ? "scene default" : bootWeather)
              + ", res " + (res[0] > 0 ? res[0] + "x" + res[1] : "default")
              + ", fps " + (int) fps);
          // apply the current freeze state to the fresh engine
          applyFreezeState();
          evaluatePacing(Sh1WallpaperService.this, prefs);
          scheduleObserver(Sh1WallpaperService.this, glb, track);
        }
      });
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
      super.onVisibilityChanged(visible);
      Sh1Native.visible(mGen, visible);
      anyVisible = visible;
      if (visible) {
        // catch up on boundaries missed while the screen was off
        refreshAutoMode(Sh1WallpaperService.this, getPrefs(Sh1WallpaperService.this));
        checkScheduledRandomizer(getPrefs(Sh1WallpaperService.this));
        updateTiltPipelines();
      } else {
        updateTiltPipelines();   // invisible: unregister the tilt sensor
        // v7 F4: pre-warm the NEXT random scene's GLB while invisible
        prewarmNextScene();
      }
    }

    @Override
    public void onSurfaceDestroyed(SurfaceHolder holder) {
      super.onSurfaceDestroyed(holder);
      Sh1Native.surfaceDestroyed(mGen);   // only retracts THIS engine
    }

    @Override
    public void onDestroy() {
      // free the native binding even if the surface callbacks were
      // skipped; harmless if the slot is already gone
      Sh1Native.surfaceDestroyed(mGen);
      // v10.1 BUG 2: this wallpaper engine instance is gone
      sEngineInstances.decrementAndGet();
      // v48 WAJ-8: its visibility no longer counts (anyEngineVisible)
      sLiveEngines.remove(this);
      super.onDestroy();
    }
  }

  /** v7 F4: read-ahead the next randomizer scene's GLB (IO-only seam) */
  private void prewarmNextScene() {
    SharedPreferences prefs = getPrefs(this);
    int mode = prefs.getInt(PREF.SH1_RANDOMIZER, DEF.SH1_RANDOMIZER);
    if (mode == SH1.RANDOM_OFF || !sEngineStarted
        || !prefs.getBoolean(PREF.SH1_PREWARM, DEF.SH1_PREWARM)) {
      return;
    }
    Sh1Camdat camdat = Sh1Camdat.load(this);
    if (!camdat.isValid()) {
      return;
    }
    int[][] pool = randomizerPool(camdat, prefs);
    if (pool.length == 0) {
      return;
    }
    int[] next = peekNext(pool, getActiveSceneIndex(prefs),
        prefs.getInt(PREF.SH1_TRACK, DEF.SH1_TRACK));
    if (next == null) {
      return;
    }
    Sh1Camdat.Model model = camdat.getModel(next[0]);
    if (model == null || !model.glbPresent || "THRF9FD.glb".equals(model.file)) {
      return;   // bundled scene ships in the APK (already page-cached)
    }
    final String path = new File(Sh1Storage.sh1Dir(this), model.file).getAbsolutePath();
    sObserver.execute(() -> {
      Log.i(TAG, "pre-warm: " + path);
      Sh1Native.prefetch(path);
    });
  }
}
