package xyz.zedler.patrick.doodle.service;

import android.view.Surface;

/**
 * Static facade over libsh1.so (SH1 environment renderer).
 * JNI symbol root: Java_xyz_zedler_patrick_doodle_service_Sh1Native_*
 */
public final class Sh1Native {
    static { System.loadLibrary("sh1"); }

    private Sh1Native() {}

    /**
     * spawn the engine thread once per process (--no-hud, endless).
     *
     * @param filesDir    app-private storage with the sh1 assets
     * @param bootPreset  effective env mode at boot, 0..20 (passed to the
     *                    engine as --preset N: Gfx_MapEnvSet(N,N) at boot);
     *                    -1 keeps the scene's own camdat env pair
     * @param bootWeather runtime weather override 0..8 (passed as
     *                    --weather-target N: sharedFunc_800D0B18 at boot);
     *                    -1 keeps the scene's own camdat weather recipe
     * @param glbFile     basename of the scene GLB inside
     *                    &lt;filesDir&gt;/sh1 (camdat models[].file);
     *                    null keeps the PoC default THRF9FD.glb
     * @param trackIdx    camera track index (camdat models[].tracks[]);
     *                    -1 keeps the first enabled track (PoC default)
     * @param resW        render width for the engine's own --res flag
     *                    (v7 F17); 0 together with resH keeps the engine
     *                    default 320x224 (the authentic PS1 raster)
     * @param resH        render height; see resW
     * @param fps         presentation cap for the engine's own --fps flag
     *                    (v7 F18 / v9 F26, 1..60); &lt;= 0 keeps 30, the
     *                    authentic rate; 60 selects display-duplicate mode;
     *                    sub-15 rates sample the presentation (real-time world)
     * @param wobble      v9 F24 PSX wobble boot state: -1 = absent (the
     *                    scene's camdat psxWobble + engine default — the
     *                    AUTHENTIC behavior), 1 = forced on (--ps1),
     *                    0 = forced off (--modern). Changed values need an
     *                    engine restart, exactly like --res.
     * @param backend     v9.9 rendering backend: 0 = software (the DEFAULT
     *                    and the accuracy source of truth), 1 = OpenGL ES
     *                    (hardware, experimental opt-in), 2 = Vulkan
     *                    (hardware, experimental opt-in). A change needs an
     *                    engine restart exactly like --res; the engine falls
     *                    back to software automatically when the chosen
     *                    backend is unavailable on the device.
     * @param dither      v10.0 resolution-aware dither state: 1 = dither ON
     *                    (pass NO flag — the engine default + the scene's
     *                    own camdat envDither rule, the authentic chain,
     *                    exactly like wobble's absent state); 0 = forced off
     *                    (--no-dither, the automatic default above the
     *                    native 320x224 raster — see
     *                    Sh1WallpaperService.getEffectiveDither). Applies
     *                    to ALL backends (the engine's single dither global
     *                    gates the software fb_put path and the GLES/Vulkan
     *                    shader flag). A change needs an engine restart
     *                    exactly like --res.
     */
    public static native void start(String filesDir, int bootPreset,
                                    int bootWeather, String glbFile, int trackIdx,
                                    int resW, int resH, double fps, int wobble,
                                    int dither, int backend);

    /**
     * stop the engine thread through its own K_QUIT path and join it.
     * Call from a BACKGROUND thread only (the join takes about one loop
     * pass, ~33 ms, plus teardown). After it returns, start() can boot a
     * different scene/track.
     *
     * @return true when the engine is no longer running
     */
    public static native boolean stop();

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

    /**
     * apply an environment preset at runtime (Gfx_MapEnvSet(idx,idx)) —
     * consumed by the engine's realtime loop through its own
     * mode-application path; ignored while the engine is not running
     * (the boot arguments carry the persisted state instead).
     *
     * @param presetIdx MAP_EFFECTS_INFOS index, 0..20
     */
    public static native void env(int presetIdx);

    /**
     * v9 F20: apply an environment preset through the engine's own
     * GRADUAL transition — the Gfx_MapEnvUpdate(idx, idx,
     * PrimitiveType_S32, &amp;progress, 0, Q12(100)) progress blend the
     * game itself uses at its day/night boundaries (map2_s00.c:1352
     * DoorOfEclipse, map3_s02_2.c:309). The verbatim func_8003F838
     * blend runs on the engine's 30 Hz tick grid; the raised-cosine
     * driver completes in ~3 s. This supersedes sudden env switches at
     * the Auto-Mode day/night boundary.
     *
     * @param presetIdx MAP_EFFECTS_INFOS index, 0..20 (the TARGET env)
     */
    public static native void envTransition(int presetIdx);

    /**
     * v9 F23: device-tilt camera offset in DEGREES (yaw = device roll
     * deviation, pitch = device pitch deviation; both clamped to the
     * user's max shift, 1-3 deg). Applied to the RENDER POSE ONLY —
     * the engine's authentic camtrack camera, turn filter, snow and
     * light state are untouched. (0, 0) = neutral.
     */
    public static native void setTilt(double yawDeg, double pitchDeg);

    /**
     * set the runtime weather target (the engine's sharedFunc_800D0B18
     * path with the 512-tick sine cross-fade).
     *
     * @param weather 0..8 (0 none, 1-4 snow wind 0-3, 5-8 rain wind 0-3)
     */
    public static native void weather(int weather);

    /**
     * battery/power-save freeze: the engine renders the CURRENT frame
     * exactly ONCE and then holds the Surface at 0 FPS (the frozen image
     * IS the software-rasterized output — never a static image swap).
     * false resumes the normal 30 Hz lockstep.
     */
    public static native void freeze(boolean on);

    /**
     * v10.1 TASK 4: the user's crop-window offsets for the STRICT
     * CENTER-CROP presentation (normalized -1.0..1.0, 0.0 = symmetric
     * center). Presentation-layer only — the engine's presentation blit
     * reads them at EVERY frame, so a live change applies on the next
     * presented frame with NO engine restart. The axis that is not
     * cropped (the axis that fills the surface exactly) has no room and
     * the offset is inert there: portrait phones pan X, landscape
     * screens pan Y. Applies to ALL backends (the GLES/Vulkan readback
     * presents through the same blit).
     */
    public static native void setCropOffset(float x, float y);

    /**
     * v7 F6: true while the native engine thread is alive (g_started).
     * Read-only — used by the first-frame watchdog to detect a corrupt
     * BYOA import that killed main() before any frame was presented.
     */
    public static native boolean isRunning();

    /**
     * v7 F8 diagnostics (read-only): {fps, frameMs, frozen, fbW, fbH}.
     * fps = presented frames in the last second (0 while frozen),
     * frameMs = smoothed render+present duration, frozen = 1 while the
     * freeze gate holds the surface, fbW/fbH = current raster size.
     * Never null; all zeros when the engine has not presented a frame.
     */
    public static native double[] stats();

    /**
     * v7 F6/F9: copy of the last PRESENTED engine frame as ARGB_8888
     * packed ints (Bitmap.createBitmap colors[] layout, fbW*fbH pixels).
     * Null when the engine has not presented anything yet.
     */
    public static native int[] grabFrame();

    /**
     * v7 F1/F12/F18: runtime PRESENTATION cap. Only ever steps BELOW the
     * boot --fps value (the seam clamps to min(cap, boot)). The engine's
     * 30 Hz whole-tick lockstep is untouched; a lower present rate only
     * duplicates tick state in the output. &lt;= 0 restores the boot cap.
     */
    public static native void setFpsCap(double fps);

    /**
     * v7 F4: IO-only read-ahead of a scene GLB (posix_fadvise WILLNEED
     * with a sequential-read fallback). Safe from any thread; touches no
     * engine state.
     */
    public static native void prefetch(String path);

    /**
     * v9.7 Camtrack editor: scrub the live preview to an absolute track
     * phase (seconds inside the cycle; the engine loop wraps it at the
     * cycle duration). Consumed through the engine's own exact-pose-on-
     * seek rule (playback_step dt=0 — the paused/scrubbed branch), so the
     * next presented frame is the exact camera pose at that phase.
     */
    public static native void seek(double t);

    /**
     * v9.7 Camtrack editor: pause/resume the camtrack playback — the
     * engine's own K_SPACE pause field (the held tick pose keeps
     * rendering while paused).
     */
    public static native void setPaused(boolean paused);

    /**
     * v9.7 Camtrack editor: playback rate (the engine's own K_PLUS /
     * K_MINUS speed field; accepted range 0.125..8 — values outside are
     * ignored by the seam).
     */
    public static native void setRate(double rate);
}
