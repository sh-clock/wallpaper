package com.sh1.poc;

import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Silent Hill 1 environment renderer as a live wallpaper.
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
 */
public class Sh1WallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new Sh1Engine();
    }

    /* v8 (aim 7): the scene list is DYNAMIC — every file under assets/sh1/
     * is enumerated and copied at first use. Inserting a new scene = drop
     * the GLB + authored camdat into assets/sh1/ + one entry in scenes.json
     * (the native side rotates through the manifest, persisted index in
     * filesDir/sh1/scene.idx). No Java edit needed, ever. (The v7.1 list
     * was hardcoded 10 names; new assets required a Java code change and
     * app updates needed the list kept in sync by hand.) */
    private static String[] assetList(Sh1WallpaperService svc) {
        try {
            String[] names = svc.getAssets().list("sh1");
            if (names != null && names.length > 0) return names;
        } catch (IOException e) {
            throw new RuntimeException("asset listing failed", e);
        }
        return new String[0];
    }

    private static volatile boolean sAssetsReady = false;
    private static final AtomicInteger sGen = new AtomicInteger(0);
    private static volatile boolean sEngineStarted = false;

    /** copy the bundled SH1 data files into app-private storage.
     *  v8.1 (audit C-M3): copies go to a temp file + ATOMIC rename (an
     *  interrupted copy used to leave a permanently-truncated GLB that
     *  the existence-only check then treated as complete — a black
     *  wallpaper forever). A completed copy is stamped .ok; only fully
     *  stamped files are skipped, so MODIFIED assets re-copy on app
     *  updates (the v8 existence-only check skipped them: stale scenes
     *  forever). */
    private static void copyAssetsIfNeeded(Sh1WallpaperService svc) {
        synchronized (Sh1WallpaperService.class) {
            if (sAssetsReady) return;
            File dst = new File(svc.getFilesDir(), "sh1");
            String[] names = assetList(svc);
            dst.mkdirs();
            for (String f : names) {
                if (f.endsWith(".ok")) continue;
                /* v10.1 (audit A-D1 CRITICAL): the .ok stamp now carries
                 * the asset's SHA-256 — an EXISTING stamp whose hash no
                 * longer matches the APK asset means the asset CHANGED
                 * (an app update re-shipping scenes.json/scenes) and is
                 * RE-COPIED. The v8/v10 existence-only stamp skipped
                 * modified assets forever: updating users kept the stale
                 * 3-entry scenes.json and the 18 new scenes never ran.
                 * Stamps written by older versions (empty .ok files)
                 * migrate automatically (empty != hash -> re-copy).     */
                String want;
                try (InputStream in = svc.getAssets().open("sh1/" + f)) {
                    want = sha256Hex(in);
                } catch (IOException e) {
                    throw new RuntimeException("asset read failed: " + f, e);
                }
                File ok = new File(dst, f + ".ok");
                File out = new File(dst, f);
                String have = readTextIfSmall(ok);   /* null when absent */
                if (want.equals(have)) continue;      /* identical — keep */
                File tmp = new File(dst, f + ".tmp");
                try (InputStream in = svc.getAssets().open("sh1/" + f);
                     OutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } catch (IOException e) {
                    tmp.delete();
                    throw new RuntimeException("asset copy failed: " + f, e);
                }
                /* atomic: a half-written file can never be observed
                 * (Linux rename replaces an existing target — the
                 * pre-delete is belt-and-braces for other kernels) */
                out.delete();
                if (!tmp.renameTo(out)) {
                    tmp.delete();
                    throw new RuntimeException("asset rename failed: " + f);
                }
                /* the content stamp: written AFTER the rename, so an
                 * interrupted stamp write re-copies next boot (safe) */
                try {
                    FileOutputStream fs = new FileOutputStream(ok);
                    fs.write(want.getBytes("UTF-8"));
                    fs.close();
                } catch (IOException e) {
                    throw new RuntimeException("stamp failed: " + f, e);
                }
            }
            /* stale outputs from an older asset set (removed from the
             * APK) are left in place: the manifest only references
             * shipped names, so they are inert. */
            sAssetsReady = true;
        }
    }

    private static String sha256Hex(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String readTextIfSmall(File f) {
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 256) bo.write(buf, 0, n);
            return bo.toString("UTF-8").trim();
        } catch (IOException e) {
            return null;              /* unreadable stamp -> re-copy */
        }
    }

    private final class Sh1Engine extends Engine {

        /** identity of this engine instance for the native side */
        private final int mGen = sGen.incrementAndGet();

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            copyAssetsIfNeeded(Sh1WallpaperService.this);
            Sh1Native.surfaceCreated(mGen, holder.getSurface());
            if (!sEngineStarted) {
                sEngineStarted = true;
                Sh1Native.start(getFilesDir().getAbsolutePath());
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Sh1Native.visible(mGen, visible);
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
            super.onDestroy();
        }
    }
}
