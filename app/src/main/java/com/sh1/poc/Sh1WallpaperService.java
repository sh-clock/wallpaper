package com.sh1.poc;

import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private static final String[] ASSETS = {
            "THRF9FD.glb", "THRF9FD.IPD", "THR_GLB.PLM", "camdat.json"
    };

    private static volatile boolean sAssetsReady = false;
    private static final AtomicInteger sGen = new AtomicInteger(0);
    private static volatile boolean sEngineStarted = false;

    /** copy the bundled SH1 data files into app-private storage (one time) */
    private static void copyAssetsIfNeeded(Sh1WallpaperService svc) {
        if (sAssetsReady) return;
        synchronized (Sh1WallpaperService.class) {
            if (sAssetsReady) return;
            File dst = new File(svc.getFilesDir(), "sh1");
            boolean missing = false;
            for (String f : ASSETS) if (!new File(dst, f).exists()) { missing = true; break; }
            if (!missing) { sAssetsReady = true; return; }
            dst.mkdirs();
            for (String f : ASSETS) {
                File out = new File(dst, f);
                if (out.exists()) continue;
                try (InputStream in = svc.getAssets().open("sh1/" + f);
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } catch (IOException e) {
                    throw new RuntimeException("asset copy failed: " + f, e);
                }
            }
            sAssetsReady = true;
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
