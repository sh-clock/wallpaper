package com.sh1.poc;

import android.view.Surface;

/**
 * Static facade over libsh1.so (SH1 environment renderer).
 * JNI symbol root: Java_com_sh1_poc_Sh1Native_*
 */
public final class Sh1Native {
    static { System.loadLibrary("sh1"); }

    private Sh1Native() {}

    /** spawn the engine thread once per process (--no-hud, 30fps, endless) */
    public static native void start(String filesDir);

    /**
     * publish THIS engine's surface. gen is a per-engine id (see
     * Sh1WallpaperService): the system preview and the real wallpaper are
     * separate engines in one process, and the newest one with a live
     * surface owns the output. */
    public static native void surfaceCreated(int gen, Surface surface);

    /** retract THIS engine's surface; falls back to any other live engine */
    public static native void surfaceDestroyed(int gen);

    /** per-engine visibility gate (false = engine idles, ~0 CPU) */
    public static native void visible(int gen, boolean visible);

    /** forward an engine LiveKey value (see sh1_keys.h) */
    public static native void key(int key);
}
