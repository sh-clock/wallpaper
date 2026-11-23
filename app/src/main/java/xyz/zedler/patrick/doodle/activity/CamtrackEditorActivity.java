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

package xyz.zedler.patrick.doodle.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import xyz.zedler.patrick.doodle.Constants.ACTION;
import xyz.zedler.patrick.doodle.Constants.SH1;
import xyz.zedler.patrick.doodle.R;
import xyz.zedler.patrick.doodle.adapter.EditorTrackAdapter;
import xyz.zedler.patrick.doodle.adapter.Sh1OptionAdapter;
import xyz.zedler.patrick.doodle.databinding.ActivityCamtrackEditorBinding;
import xyz.zedler.patrick.doodle.databinding.DialogEditorNumberBinding;
import xyz.zedler.patrick.doodle.databinding.RowEditorSliderBinding;
import xyz.zedler.patrick.doodle.databinding.RowEditorSwitchBinding;
import xyz.zedler.patrick.doodle.databinding.RowEditorValueBinding;
import xyz.zedler.patrick.doodle.databinding.SheetEditorPropsBinding;
import xyz.zedler.patrick.doodle.model.Sh1Option;
import xyz.zedler.patrick.doodle.service.Sh1WallpaperService;
import xyz.zedler.patrick.doodle.util.CamtrackDoc;
import xyz.zedler.patrick.doodle.util.CamtrackMath;
import xyz.zedler.patrick.doodle.util.HapticUtil;
import xyz.zedler.patrick.doodle.util.PrefsUtil;
import xyz.zedler.patrick.doodle.util.Sh1Storage;
import xyz.zedler.patrick.doodle.util.UiUtil;
import xyz.zedler.patrick.doodle.view.CamtrackPathView;

/**
 * v9.7: the NATIVE Camtrack editor — a full functional replica of the
 * CamTrack v13 HTML editor, built from Material 3 Expressive components
 * (Views + Material Components 1.13, the app's own MD3 theme). NO
 * WebView: every field of the camdat (model env day/night presets,
 * per-track env/weather overrides, all 8 track types' path/look fields,
 * keyframe coordinates, loop modes) is edited with native MD3 controls —
 * bottom sheets for property editing, a drag-reorderable track list,
 * MD3 sliders for numerical input and a bottom navigation bar for the
 * TRACKS / PATH / WORLD / MODELS destinations.
 *
 * <p><b>The live preview is the wallpaper engine itself</b> — the actual
 * software rasterizer (libsh1.so) renders into this activity's SurfaceView
 * through the per-engine surface-binding table (newest-live-wins), driven
 * by the editor-session API in {@link Sh1WallpaperService} (all engine
 * lifecycle mutations stay serialized on the service's single executor;
 * every other engine-state writer defers while the session is active).
 * Scrubbing seeks the engine through the new Android-gated seam with the
 * v13 editor's exact-pose-on-seek rule; play/pause and rate ride the
 * engine's own K_SPACE / K_PLUS fields. Track switches and committed
 * edits re-boot the preview through the engine's own argv path (the
 * camdat on disk), debounced ~600 ms after the last edit — the auto-
 * preview persistence model (crash-safe: the file is the state).</p>
 *
 * <p><b>Accuracy notes</b> (the Authenticity Scale applies): weather
 * changes preview through the ENGINE's authentic runtime transition (the
 * 512-tick D0B18 sine cross-fade — never short-circuited here); env
 * preset changes apply instantly through the engine's own Gfx_MapEnvSet
 * mode path; the Day/Night phase lens is session-only and never
 * serialized (the v13 rule — the ENGINE transitions day/night through its
 * own environment paths). The data layer is {@link CamtrackDoc}: the
 * parse mirrors the engine's camdat parser and the serialization is
 * byte-compatible with the v13 editor's export (unit-proven).</p>
 */
public class CamtrackEditorActivity extends AppCompatActivity
    implements EditorTrackAdapter.TrackAdapterListener, CamtrackPathView.AnchorListener {

  private static final String TAG = CamtrackEditorActivity.class.getSimpleName();

  /** the K_L key value (sh1_keys.h enum order) — the engine's lightning */
  private static final int KEY_LIGHTNING = 16;

  /** sanity cap for a camdat payload (a hand-edited camdat is ~5 KB) */
  private static final int MAX_CAMDAT_BYTES = 5 * 1024 * 1024;

  /** committed-edit → preview restart debounce (the auto-preview path) */
  private static final long PREVIEW_REFRESH_DELAY_MS = 600;

  private static final String PHASE_SCENE = "scene";
  private static final String PHASE_DAY = "day";
  private static final String PHASE_NIGHT = "night";

  /* ---- view binding + shared chrome ---- */
  private ActivityCamtrackEditorBinding binding;
  private HapticUtil haptic;

  /* ---- the document ---- */
  private CamtrackDoc doc;
  private CamtrackDoc.Model model;       // the ACTIVE model being edited
  private String selectedTrackId;
  private byte[] snapshot;               // the camdat as it was at open
  private boolean changedSinceOpen;      // any committed edit (the back guard)
  private boolean saveFailed;

  /* v10.0 BUG 1 FIX: the STATE-DRIVEN back callback (replaces the
     always-enabled one). Enabled ONLY while it will actually act:
     when there are unsaved changes / a failed save (the exit dialog)
     or while a non-TRACKS editor page is showing (back steps back to
     the TRACKS page first). While disabled, the SYSTEM default back
     handling runs — including the predictive-back exit animation the
     always-enabled callback suppressed (the "first press appears
     dead" signature on the real device). */
  private androidx.activity.OnBackPressedCallback backCallback;

  /* ---- the session-only preview state ---- */
  private String phase = PHASE_SCENE;

  /* ---- playback chrome (the Java-side clock; the engine holds truth) -- */
  private boolean playing = true;
  private boolean scrubbing;
  private double trackTime;
  private double rate = 1.0;
  private long lastFrameNs;
  /* v10.3: the last frame timestamp the callback re-posted itself at —
     guards the synchronous-loop hazard (see the frameTick comment) */
  private long lastPostedFrameNs;
  private final Choreographer choreographer = Choreographer.getInstance();
  private Choreographer.FrameCallback frameTick;

  /* ---- the engine preview session ---- */
  private int previewGen;                // this activity's binding generation
  private boolean sessionBooted;         // beginEditorPreview ran
  private boolean surfaceReady;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable previewRefresh = this::runPreviewRefresh;

  /* ---- v10.1 TASK 4: the crop overlay's fixed geometry inputs ----
     the REAL full-display size (the surface the live wallpaper crops
     into — captured once; the raster size is re-synced on every aspect
     application). */
  private int overlayScreenW = 1080;
  private int overlayScreenH = 2400;

  /* ---- adapters ---- */
  private EditorTrackAdapter trackAdapter;
  private ItemTouchHelper dragHelper;

  /* ------------------------------------------------------------------ */
  /* lifecycle                                                            */

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // edge-to-edge over the app's own MD3 surface
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    binding = ActivityCamtrackEditorBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    ViewCompat.setOnApplyWindowInsetsListener(binding.coordinatorEditor, (v, insets) -> {
      androidx.core.graphics.Insets bars = insets.getInsets(
          WindowInsetsCompat.Type.systemBars());
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return WindowInsetsCompat.CONSUMED;
    });
    if (VERSION.SDK_INT >= VERSION_CODES.M) {
      WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(
          getWindow(), getWindow().getDecorView());
      boolean dark = (getResources().getConfiguration().uiMode
          & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
      bars.setAppearanceLightStatusBars(!dark);
      bars.setAppearanceLightNavigationBars(!dark);
    }
    haptic = new HapticUtil(this);

    // v10.3 BUG 2 — the UNIVERSAL TERMINAL back callback. Registered FIRST
    // (= the LOWEST LIFO priority: every higher layer — the exit-guard
    // callback below, any sheet dismissal — consumes back BEFORE it) and
    // ALWAYS ENABLED, so it does two things at once:
    //   1. It keeps the OnBackPressedDispatcher's platform bridge
    //      PERMANENTLY REGISTERED (hasEnabledCallbacks stays true from
    //      onCreate, so androidx.activity 1.9.x never unregisters the
    //      window's OnBackInvokedCallback — the system always delivers
    //      back INTO the app instead of dropping it on the predictive-back
    //      opt-in path; a bridge-level silent death can no longer occur).
    //   2. It TERMINATES every back press with a visible action: when the
    //      editor's own state-driven callback is disabled (clean editor on
    //      the TRACKS page), this callback runs and explicitly finishes
    //      the activity — back can NEVER fall through silently.
    getOnBackPressedDispatcher().addCallback(this,
        new androidx.activity.OnBackPressedCallback(true) {
          @Override
          public void handleOnBackPressed() {
            finish();
          }
        });

    // v10.0 BUG 1 FIX: the dispatcher callback serves the three-button
    // back key, the gesture AND predictive back identically — but it is
    // now STATE-DRIVEN (enabled only while it acts; see updateBackCallback)
    // instead of always-enabled; with the v10.3 terminal callback beneath
    // it, the disabled state simply falls through to the guaranteed finish
    // instead of relying on the system-default path
    backCallback = new androidx.activity.OnBackPressedCallback(false) {
      @Override
      public void handleOnBackPressed() {
        onBackRequested();
      }
    };
    getOnBackPressedDispatcher().addCallback(this, backCallback);

    if (!loadDocument()) {
      Toast.makeText(this, R.string.sh1_camtrack_editor_error, Toast.LENGTH_LONG).show();
      finish();
      return;
    }

    binding.toolbarEditor.setNavigationOnClickListener(v -> onBackRequested());
    binding.toolbarEditor.inflateMenu(R.menu.menu_editor_top);
    binding.toolbarEditor.setOnMenuItemClickListener(item -> {
      if (item.getItemId() == R.id.menu_editor_save) {
        saveNow(true);
        return true;
      }
      return false;
    });

    initPreviewSurface();
    initCropOverlay();
    initPlaybar();
    initTrackList();
    initPathView();
    initNavigation();
    buildWorldPage();
    buildModelsPage();
    refreshTrackUi();

    // the playback chrome clock (initialized here so the callback can
    // safely re-post itself — a field initializer would self-reference)
    // v10.3: the callback re-posts ONLY on an ADVANCING frame clock. A
    // real display's frame timestamps strictly increase, so the loop
    // runs exactly as before on device; a frozen/simulated clock (Robolectric
    // executes posted frame callbacks synchronously at the SAME timestamp)
    // terminates after one pass instead of spinning forever inside the
    // initial post — this also hardens the callback against any
    // non-advancing doFrame source.
    frameTick = frameTimeNs -> {
      if (playing) {
        if (lastFrameNs > 0 && frameTimeNs > lastFrameNs) {
          double dt = (frameTimeNs - lastFrameNs) / 1.0e9;
          advanceClock(dt);
        }
      }
      lastFrameNs = frameTimeNs;
      updatePlaybarChrome();
      updatePoseMarker();
      if (frameTimeNs > lastPostedFrameNs) {
        lastPostedFrameNs = frameTimeNs;
        choreographer.postFrameCallback(frameTick);
      }
    };
    choreographer.postFrameCallback(frameTick);
  }

  @Override
  protected void onDestroy() {
    choreographer.removeFrameCallback(frameTick);
    main.removeCallbacks(previewRefresh);
    if (sessionBooted) {
      // the engine goes back to the wallpaper's persisted selection with
      // whatever camdat is on disk (auto-persisted edits survive a kill)
      Sh1WallpaperService.endEditorPreview(this);
      sessionBooted = false;
    }
    if (surfaceReady) {
      Sh1NativeSafe.surfaceDestroyed(previewGen);
      surfaceReady = false;
    }
    binding = null;
    super.onDestroy();
  }

  /* ------------------------------------------------------------------ */
  /* the document                                                         */

  private boolean loadDocument() {
    byte[] data = readActiveCamdat();
    if (data == null || data.length == 0) {
      return false;
    }
    snapshot = data;
    try {
      doc = CamtrackDoc.parse(new String(data, StandardCharsets.UTF_8));
    } catch (Exception e) {
      return false;
    }
    if (doc == null || doc.models.isEmpty()) {
      return false;
    }
    // start where the wallpaper is: the persisted scene's model index
    int sceneIdx = 0;
    try {
      sceneIdx = getPrefs().getInt(
          xyz.zedler.patrick.doodle.Constants.PREF.SH1_SCENE, 0);
    } catch (Exception ignored) {
    }
    if (sceneIdx < 0 || sceneIdx >= doc.models.size()) {
      sceneIdx = 0;
    }
    model = doc.models.get(sceneIdx);
    CamtrackDoc.Track first = firstEnabledTrack(model);
    selectedTrackId = first != null ? first.id : null;
    return true;
  }

  private static CamtrackDoc.Track firstEnabledTrack(CamtrackDoc.Model m) {
    for (CamtrackDoc.Track t : m.tracks) {
      if (t.enabled) {
        return t;
      }
    }
    return m.tracks.isEmpty() ? null : m.tracks.get(0);
  }

  private CamtrackDoc.Track selectedTrack() {
    if (model == null || selectedTrackId == null) {
      return null;
    }
    for (CamtrackDoc.Track t : model.tracks) {
      if (selectedTrackId.equals(t.id)) {
        return t;
      }
    }
    return null;
  }

  /**
   * The ENGINE's track index for --track N: the index among the model's
   * engine-valid tracks (the engine's camdat parser skips invalid ones;
   * the doc model drops them at parse, so this equals the list index —
   * recomputed defensively anyway).
   */
  private static int engineTrackIndex(CamtrackDoc.Model m, CamtrackDoc.Track target) {
    int n = 0;
    for (CamtrackDoc.Track t : m.tracks) {
      if (t.engineValid()) {
        if (t == target) {
          return n;
        }
        n++;
      }
    }
    return -1;
  }

  private byte[] readActiveCamdat() {
    File camdat = new File(Sh1Storage.sh1Dir(this), "camdat.json");
    if (camdat.exists()) {
      try (InputStream in = new FileInputStream(camdat)) {
        return readFile(in);
      } catch (Exception e) {
        return null;
      }
    }
    try (InputStream in = getAssets().open("sh1/camdat.json")) {
      return readFile(in);
    } catch (Exception e) {
      return null;
    }
  }

  private static byte[] readFile(InputStream in) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[64 * 1024];
    int n;
    while ((n = in.read(buf)) > 0) {
      bos.write(buf, 0, n);
    }
    in.close();
    return bos.toByteArray();
  }

  private SharedPreferences getPrefs() {
    return new PrefsUtil(this).checkForMigrations().getSharedPrefs();
  }

  /* ------------------------------------------------------------------ */
  /* the engine preview session                                           */

  private void initPreviewSurface() {
    previewGen = Sh1WallpaperService.newBindingGen();
    SurfaceHolder holder = binding.surfaceEditorPreview.getHolder();
    holder.addCallback(new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(@NonNull SurfaceHolder h) {
        surfaceReady = true;
        Sh1NativeSafe.surfaceCreated(previewGen, h.getSurface());
        applyPreviewAspect();
        bootPreview(false);
      }

      @Override
      public void surfaceChanged(@NonNull SurfaceHolder h, int format, int w, int hh) {
        applyPreviewAspect();
      }

      @Override
      public void surfaceDestroyed(@NonNull SurfaceHolder h) {
        surfaceReady = false;
        Sh1NativeSafe.surfaceDestroyed(previewGen);
      }
    });
  }

  /**
   * v10.4 — the v10.2 FULL-CONTEXT PREVIEW, restored.
   *
   * The v10.3 WYSIWYG preview (device-display aspect, live-cropped) is
   * REVERTED per the user's real-device verdict: with the rendered CONTENT
   * panning under a fixed phone-shaped window, a narrow slice of the
   * perspective render reads as "the camera angle rotated" — the exact
   * opposite of the wanted interaction. The v10.2 model puts the motion
   * where it belongs: the preview shows the engine's FULL framebuffer at
   * the raster's aspect (the whole rendered scene, always visible, never
   * re-cropped), and the CROP WINDOW (the "camera box") is the thing that
   * TRANSLATES — drawn by Sh1CropOverlayView with a scrim over what the
   * screen crops away. Letterbox semantics: the visible window slides,
   * the bars' sizes shift, the image and the 3D camera never move.
   *
   * The wallpaper itself still applies the true 2D crop (fog_poc.c
   * and_blit pans the blit source rect over the fully rendered
   * framebuffer; the raster is provably offset-invariant) — the scene IS
   * actually cropped on the phone; only the editor preview stays
   * full-frame so the user always sees the full context.
   */
  private void applyPreviewAspect() {
    int[] res = Sh1WallpaperService.getBootResolution(getPrefs());
    int rw = res[0] > 0 ? res[0] : 320;
    int rh = res[1] > 0 ? res[1] : 224;
    int w = binding.frameEditorPreview.getWidth();
    if (w <= 0) {
      return;
    }
    int h = Math.max(1, (int) (w * (long) rh / rw));
    LinearLayout.LayoutParams lp =
        (LinearLayout.LayoutParams) binding.frameEditorPreview.getLayoutParams();
    if (lp.width != LinearLayout.LayoutParams.MATCH_PARENT || lp.height != h) {
      lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
      lp.height = h;
      lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
      binding.frameEditorPreview.setLayoutParams(lp);
    }
    // keep the crop overlay's raster + screen geometry in sync (the frame
    // re-layout re-draws the overlay against the fresh bounds; its window
    // math runs at draw time)
    updateOverlayGeometry(rw, rh);
  }

  /* ------------------------------------------------------------------ */
  /* v10.1 TASK 4 / v10.4: the CROP OVERLAY + the crop-window control   */

  /**
   * Wire the crop overlay (the full-context window visualization + its
   * drag gesture), the collapsible control panel (X/Y offset sliders +
   * reset) and the persisted state.
   *
   * v10.4 — the v10.2 model, restored: the preview surface is letterboxed
   * to the RASTER's aspect, so the engine's strict center-crop
   * presentation degenerates to an exact fit there (the offsets are
   * inert ON THE PREVIEW — the FULL frame is always visible); the
   * overlay is the faithful visualization of what the phone screen WILL
   * show — the window rect TRANSLATES over the fixed scene (drag /
   * sliders), with a scrim over what is left out. Drag/sliders edit the
   * same normalized -1..1 offsets the wallpaper's 2D presentation blit
   * consumes (the actual crop on the phone is REAL — and_blit pans the
   * blit source rect; the raster/camera is offset-invariant). They are
   * persisted on release and re-asserted on the wallpaper engine at
   * session end (endEditorPreview -> restartEngine reads the prefs).
   */
  private void initCropOverlay() {
    int[] screen = UiUtil.getRealDisplaySize(this);
    overlayScreenW = Math.max(1, screen[0]);
    overlayScreenH = Math.max(1, screen[1]);
    int[] res = Sh1WallpaperService.getBootResolution(getPrefs());
    updateOverlayGeometry(res[0] > 0 ? res[0] : 320, res[1] > 0 ? res[1] : 224);

    float[] crop = Sh1WallpaperService.getCropOffsets(getPrefs());
    binding.cropEditorOverlay.setOffsets(crop[0], crop[1]);
    binding.sliderEditorCropX.setValue(crop[0]);
    binding.sliderEditorCropY.setValue(crop[1]);

    binding.cropEditorOverlay.setCropListener(this::onCropFromOverlay);

    // the panel toggle — collapsed by default (the overlay + the drag
    // gesture stay fully functional either way); tapping expands the
    // precise X/Y sliders
    binding.linearEditorCropToggle.setOnClickListener(v -> {
      boolean expand = binding.panelEditorCrop.getVisibility() != View.VISIBLE;
      binding.panelEditorCrop.setVisibility(expand ? View.VISIBLE : View.GONE);
      haptic.click();
    });

    // CONTINUOUS sliders (no stepSize — the v10.0 slider-crash lesson:
    // programmatic floats must never hit a step lattice). Live while
    // dragging (the presentation blit reads the offsets every frame);
    // persisted on release.
    binding.sliderEditorCropX.addOnChangeListener((slider, value, fromUser) -> {
      if (fromUser) {
        binding.cropEditorOverlay.setOffsets(value,
            binding.cropEditorOverlay.getOffsetY());
        Sh1NativeSafe.setCropOffset(value,
            binding.cropEditorOverlay.getOffsetY());
      }
    });
    binding.sliderEditorCropX.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(@NonNull Slider slider) {
          }

          @Override
          public void onStopTrackingTouch(@NonNull Slider slider) {
            persistCropOffsets(binding.sliderEditorCropX.getValue(),
                binding.sliderEditorCropY.getValue());
          }
        });
    binding.sliderEditorCropY.addOnChangeListener((slider, value, fromUser) -> {
      if (fromUser) {
        binding.cropEditorOverlay.setOffsets(
            binding.cropEditorOverlay.getOffsetX(), value);
        Sh1NativeSafe.setCropOffset(
            binding.cropEditorOverlay.getOffsetX(), value);
      }
    });
    binding.sliderEditorCropY.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(@NonNull Slider slider) {
          }

          @Override
          public void onStopTrackingTouch(@NonNull Slider slider) {
            persistCropOffsets(binding.sliderEditorCropX.getValue(),
                binding.sliderEditorCropY.getValue());
          }
        });

    binding.buttonEditorCropReset.setOnClickListener(v -> {
      haptic.click();
      binding.cropEditorOverlay.setOffsets(0f, 0f);
      binding.sliderEditorCropX.setValue(0f);
      binding.sliderEditorCropY.setValue(0f);
      Sh1NativeSafe.setCropOffset(0f, 0f);
      persistCropOffsets(0f, 0f);
    });
  }

  /** sync the overlay's raster + screen geometry (the drag math input) */
  private void updateOverlayGeometry(int rw, int rh) {
    binding.cropEditorOverlay.setGeometry(rw, rh, overlayScreenW, overlayScreenH);
  }

  /**
   * The overlay's drag callback. While dragging: the offsets go straight
   * to the engine's presentation seam (volatile pair — next-frame effect,
   * no restart) and the sliders follow (programmatic sets do not re-fire
   * the fromUser-guarded listeners). On release: persist. On the preview
   * surface itself the offsets are inert (exact-fit aspect — the full
   * frame stays visible); the overlay IS the visualization (the window
   * box translates, the scene never moves); the state carries to the
   * wallpaper engine — the REAL 2D crop — at session end.
   */
  private void onCropFromOverlay(float x, float y, boolean dragging) {
    Sh1NativeSafe.setCropOffset(x, y);
    binding.sliderEditorCropX.setValue(x);
    binding.sliderEditorCropY.setValue(y);
    if (!dragging) {
      persistCropOffsets(x, y);
    }
  }

  /**
   * Commit the crop window (release / reset paths). No SETTINGS_CHANGED
   * broadcast from the editor: no wallpaper engine is running during the
   * session (beginEditorPreview stopped it) — endEditorPreview's restart
   * re-reads the prefs and pushes the offsets to the fresh engine; with
   * no wallpaper active the next boot applies them.
   */
  private void persistCropOffsets(float x, float y) {
    SharedPreferences prefs = getPrefs();
    if (prefs.getFloat(xyz.zedler.patrick.doodle.Constants.PREF.SH1_CROP_X,
        xyz.zedler.patrick.doodle.Constants.DEF.SH1_CROP_X) != x
        || prefs.getFloat(xyz.zedler.patrick.doodle.Constants.PREF.SH1_CROP_Y,
        xyz.zedler.patrick.doodle.Constants.DEF.SH1_CROP_Y) != y) {
      prefs.edit()
          .putFloat(xyz.zedler.patrick.doodle.Constants.PREF.SH1_CROP_X, x)
          .putFloat(xyz.zedler.patrick.doodle.Constants.PREF.SH1_CROP_Y, y)
          .apply();
    }
  }

  /**
   * Boot (or re-boot) the preview onto the active model + the selected
   * track. The phase lens resolves to a boot preset ({N,N} — the v13
   * lens semantics; scene = the camdat's own env pair); the weather stays
   * the camdat's (the engine folds the SELECTED track's weatherTarget at
   * boot through the authentic path).
   */
  private void bootPreview(boolean update) {
    if (!surfaceReady || model == null) {
      return;
    }
    CamtrackDoc.Track tr = selectedTrack();
    int trackIdx = tr != null ? engineTrackIndex(model, tr) : -1;
    if (trackIdx < 0 && !model.tracks.isEmpty()) {
      trackIdx = engineTrackIndex(model, firstEnabledTrack(model));
    }
    int preset = phasePresetArg();
    if (!sessionBooted) {
      Sh1WallpaperService.beginEditorPreview(this, model.file, trackIdx,
          preset, -1);
      sessionBooted = true;
    } else {
      Sh1WallpaperService.updateEditorPreview(this, model.file, trackIdx,
          preset, -1);
    }
    // a fresh engine boots playing at 1x from t=0
    playing = true;
    rate = 1.0;
    trackTime = 0;
    updatePlayButtonIcon();
  }

  /**
   * The phase lens → boot preset argument: scene = -1 (the camdat env
   * pair wins); day/night = the resolved preset (track override &gt;
   * model preset &gt; the app's authentic 1/3).
   */
  private int phasePresetArg() {
    if (PHASE_SCENE.equals(phase)) {
      return -1;
    }
    CamtrackDoc.Track tr = selectedTrack();
    if (PHASE_DAY.equals(phase)) {
      if (tr != null && tr.envDay != null) {
        return tr.envDay;
      }
      if (model.env != null && model.env.dayEnv != null) {
        return model.env.dayEnv;
      }
      return SH1.ENV_DAY;
    }
    if (tr != null && tr.envNight != null) {
      return tr.envNight;
    }
    if (model.env != null && model.env.nightEnv != null) {
      return model.env.nightEnv;
    }
    return SH1.ENV_NIGHT;
  }

  /** committed edits → debounced preview restart on the new camdat */
  private void schedulePreviewRefresh() {
    main.removeCallbacks(previewRefresh);
    main.postDelayed(previewRefresh, PREVIEW_REFRESH_DELAY_MS);
  }

  private void runPreviewRefresh() {
    String error = doc.validateForSave();
    if (error != null) {
      Toast.makeText(this, getString(R.string.sh1_camtrack_save_failed)
          + " (" + error + ")", Toast.LENGTH_SHORT).show();
      return;
    }
    if (persistDoc()) {
      bootPreview(true);
      // re-apply the phase lens on the fresh engine (instant env mode)
      if (!PHASE_SCENE.equals(phase)) {
        Sh1NativeSafe.env(phasePresetArg());
      }
    }
  }

  /* ------------------------------------------------------------------ */
  /* persistence                                                          */

  /** serialize + validate + write filesDir/sh1/camdat.json atomically */
  private boolean persistDoc() {
    String error = doc.validateForSave();
    if (error != null) {
      saveFailed = true;
      return false;
    }
    String json = doc.serialize();
    if (json.length() > MAX_CAMDAT_BYTES) {
      saveFailed = true;
      return false;
    }
    try {
      File dir = Sh1Storage.sh1Dir(this);
      if (!dir.exists() && !dir.mkdirs()) {
        saveFailed = true;
        return false;
      }
      File out = new File(dir, "camdat.json");
      File tmp = new File(dir, "camdat.json.tmp");
      try (OutputStream os = new FileOutputStream(tmp)) {
        os.write(json.getBytes(StandardCharsets.UTF_8));
        os.flush();
        try {
          java.nio.channels.FileChannel ch = ((FileOutputStream) os).getChannel();
          if (ch != null) {
            ch.force(true);
          }
        } catch (Exception ignored) {
          // fsync best-effort — the atomic rename below still guards
        }
      }
      if (!tmp.renameTo(out)) {
        tmp.delete();
        saveFailed = true;
        return false;
      }
      saveFailed = false;
      return true;
    } catch (Exception e) {
      saveFailed = true;
      return false;
    }
  }

  /** the explicit Save action: persist + re-boot the preview onto it +
   *  broadcast (the wallpaper itself re-boots when the editor closes) */
  private void saveNow(boolean toast) {
    if (persistDoc()) {
      changedSinceOpen = false;
      binding.toolbarEditor.setSubtitle(null);
      bootPreview(true);   // the shared engine IS the wallpaper engine
      Intent changed = new Intent();
      changed.setPackage(getPackageName());
      changed.setAction(ACTION.SETTINGS_CHANGED);
      sendBroadcast(changed);
      if (toast) {
        Toast.makeText(this, R.string.sh1_camtrack_saved, Toast.LENGTH_SHORT).show();
      }
      updateBackCallback();   // v10.0: clean again — the exit guard re-arms
    } else {
      saveFailed = true;      // v10.0: keep the exit guard armed on failure
      updateBackCallback();
      Toast.makeText(this, R.string.sh1_camtrack_save_failed, Toast.LENGTH_SHORT).show();
    }
  }

  /* ------------------------------------------------------------------ */
  /* back handling (v10.0: the STATE-DRIVEN dispatcher pattern)          */

  /**
   * v10.0 BUG 1 FIX: enables the back callback exactly when it will
   * act — (unsaved changes or a failed save) OR a non-TRACKS page is
   * showing. In every other state the callback is DISABLED and the
   * SYSTEM default back handling runs (with the real predictive-back
   * exit animation — the always-enabled v9.9 callback suppressed it,
   * the "first press appears dead" signature on the real device).
   * Called at every dirty-state transition and every page flip.
   */
  private void updateBackCallback() {
    if (backCallback == null) {
      return;
    }
    boolean onTracksPage = binding == null
        || binding.flipperEditorPages.getDisplayedChild() == 0;
    backCallback.setEnabled(changedSinceOpen || saveFailed || !onTracksPage);
  }

  private void onBackRequested() {
    // USER FIRST: back steps back to the TRACKS page from any deeper
    // editor page (path/world/models) before ever offering the exit
    if (binding != null && binding.flipperEditorPages.getDisplayedChild() != 0) {
      binding.navEditor.setSelectedItemId(R.id.menu_editor_tracks);
      updateBackCallback();
      return;
    }
    if (!changedSinceOpen && !saveFailed) {
      // (normally unreachable — the callback is disabled in this state
      // and the system default finishes the activity — kept as a guard)
      finish();
      return;
    }
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
        this, R.style.ThemeOverlay_Doodle_AlertDialog
    );
    builder.setTitle(R.string.sh1_camtrack_editor);
    builder.setMessage(R.string.sh1_camtrack_unsaved_msg);
    builder.setPositiveButton(R.string.sh1_camtrack_unsaved_save, (dialog, which) -> {
      saveNow(false);
      finish();
    });
    builder.setNegativeButton(R.string.sh1_camtrack_unsaved_discard, (dialog, which) -> {
      restoreSnapshot();
      finish();
    });
    builder.setOnCancelListener(dialog -> { /* stay in the editor */ });
    builder.show();
  }

  /** put the camdat back exactly as it was when the editor opened */
  private void restoreSnapshot() {
    if (snapshot == null) {
      return;
    }
    try {
      File dir = Sh1Storage.sh1Dir(this);
      File out = new File(dir, "camdat.json");
      File tmp = new File(dir, "camdat.json.tmp");
      try (OutputStream os = new FileOutputStream(tmp)) {
        os.write(snapshot);
        os.flush();
      }
      if (!tmp.renameTo(out)) {
        tmp.delete();
      }
      changedSinceOpen = false;
      updateBackCallback();   // v10.0: the snapshot is back — clean state
      Toast.makeText(this, R.string.sh1_camtrack_discarded, Toast.LENGTH_SHORT).show();
    } catch (Exception ignored) {
    }
  }

  /* ------------------------------------------------------------------ */
  /* the playbar                                                          */

  private void initPlaybar() {
    binding.imageEditorPlay.setOnClickListener(v -> {
      playing = !playing;
      Sh1NativeSafe.setPaused(!playing);
      updatePlayButtonIcon();
      haptic.click();
    });
    binding.sliderEditorScrub.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(@NonNull Slider slider) {
            scrubbing = true;
          }

          @Override
          public void onStopTrackingTouch(@NonNull Slider slider) {
            scrubbing = false;
          }
        });
    binding.sliderEditorScrub.addOnChangeListener((slider, value, fromUser) -> {
      if (!fromUser) {
        return;
      }
      CamtrackDoc.Track tr = selectedTrack();
      if (tr == null) {
        return;
      }
      double cycle = CamtrackMath.cycleDuration(tr);
      double playable = playableCycle(cycle);
      if (playable <= 0) {
        return;
      }
      trackTime = value / 1000.0 * playable;
      Sh1NativeSafe.seek(trackTime);
      updatePoseMarker();
    });
    binding.textEditorRate.setOnClickListener(v -> {
      // the v13 editor's rate options, cycled
      if (rate == 0.25) {
        rate = 0.5;
      } else if (rate == 0.5) {
        rate = 1;
      } else if (rate == 1) {
        rate = 2;
      } else {
        rate = 0.25;
      }
      Sh1NativeSafe.setRate(rate);
      binding.textEditorRate.setText(rateLabel(rate));
      haptic.click();
    });
    binding.textEditorRate.setText(rateLabel(rate));
    updatePlayButtonIcon();
  }

  private static String rateLabel(double r) {
    if (r == 0.25) {
      return "0.25x";
    }
    if (r == 0.5) {
      return "0.5x";
    }
    if (r == 2) {
      return "2x";
    }
    return "1x";
  }

  /** playCycle(): the 8 s preview window for static/infinite tracks */
  private static double playableCycle(double cycle) {
    if (Double.isInfinite(cycle)) {
      return 8;
    }
    if (Double.isFinite(cycle) && cycle <= 0) {
      return 8;
    }
    return cycle;
  }

  private void advanceClock(double dt) {
    CamtrackDoc.Track tr = selectedTrack();
    if (tr == null) {
      return;
    }
    double playable = playableCycle(CamtrackMath.cycleDuration(tr));
    trackTime += dt * rate;
    if (trackTime >= playable) {
      trackTime %= playable;
    }
  }

  private void updatePlaybarChrome() {
    CamtrackDoc.Track tr = selectedTrack();
    if (tr == null) {
      binding.textEditorTime.setText("— / —");
      return;
    }
    double playable = playableCycle(CamtrackMath.cycleDuration(tr));
    binding.textEditorTime.setText(String.format(Locale.ROOT,
        "%.1f / %.1f s", trackTime, playable));
    if (!scrubbing) {
      binding.sliderEditorScrub.setValue(
          (float) (trackTime / playable * 1000.0));
    }
  }

  private void updatePlayButtonIcon() {
    binding.imageEditorPlay.setImageResource(
        playing ? R.drawable.ic_rounded_pause : R.drawable.ic_rounded_play);
  }

  private void updatePoseMarker() {
    CamtrackDoc.Track tr = selectedTrack();
    if (tr == null) {
      binding.pathEditorCanvas.setPoseMarker(null);
      return;
    }
    binding.pathEditorCanvas.setPoseMarker(CamtrackMath.evalPose(tr, trackTime));
  }

  /* ------------------------------------------------------------------ */
  /* the track list (TRACKS page)                                        */

  private void initTrackList() {
    trackAdapter = new EditorTrackAdapter(model, this);
    binding.recyclerEditorTracks.setLayoutManager(
        new LinearLayoutManager(this));
    binding.recyclerEditorTracks.setAdapter(trackAdapter);
    // MD3 drag-reordering: the drag handle starts the move
    dragHelper = new ItemTouchHelper(
        new ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
          @Override
          public boolean isLongPressDragEnabled() {
            return false;   // handle-only (the visible affordance)
          }

          @Override
          public boolean onMove(
              @NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
              @NonNull RecyclerView.ViewHolder target) {
            int from = vh.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from < 0 || to < 0 || from == to) {
              return false;
            }
            CamtrackDoc.Track moved = model.tracks.remove(from);
            model.tracks.add(to, moved);
            trackAdapter.moved(from, to);
            commitEdit();
            return true;
          }

          @Override
          public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
          }
        });
    dragHelper.attachToRecyclerView(binding.recyclerEditorTracks);
  }

  @Override
  public void onDragHandleRequested(RecyclerView.ViewHolder viewHolder) {
    // ItemTouchHelper drag start from the row's visible handle affordance
    dragHelper.startDrag(viewHolder);
    haptic.click();
  }

  private void showAddTrackMenu() {
    if (model == null) {
      return;
    }
    List<Sh1Option> options = new ArrayList<>();
    String[] types = CamtrackDoc.TYPES;
    String[] labels = CamtrackDoc.TYPE_LABELS;
    for (int i = 0; i < types.length; i++) {
      options.add(new Sh1Option(i, labels[i], types[i]));
    }
    PickerSheet.show(getSupportFragmentManager(),
        getString(R.string.sh1_camtrack_add_track), null, options, -1,
        value -> {
          CamtrackDoc.Track tr = CamtrackDoc.makeTrack(
              CamtrackDoc.TYPES[value], model);
          model.tracks.add(tr);
          selectedTrackId = tr.id;
          refreshTrackUi();
          commitEdit();
          showPropsSheet(tr);
        });
  }

  @Override
  public void onTrackClick(CamtrackDoc.Track track, int position) {
    selectedTrackId = track.id;
    refreshTrackUi();
    showPropsSheet(track);
    haptic.click();
  }

  @Override
  public void onTrackPlay(CamtrackDoc.Track track, int position) {
    selectedTrackId = track.id;
    refreshTrackUi();
    trackTime = 0;
    playing = true;
    Sh1NativeSafe.setPaused(false);
    Sh1NativeSafe.setRate(rate);
    commitEdit();     // persists + re-boots the preview on this track
    haptic.click();
  }

  @Override
  public void onTrackEnabled(CamtrackDoc.Track track, boolean enabled) {
    track.enabled = enabled;
    commitEdit();
    haptic.click();
  }

  @Override
  public void onTrackMoved(int from, int to) {
    // handled inside the ItemTouchHelper callback
  }

  /** refresh every track-dependent surface after a structural change */
  private void refreshTrackUi() {
    if (trackAdapter != null) {
      trackAdapter.setSelected(selectedTrackId);
      trackAdapter.reload();
    }
    binding.textEditorTracksEmpty.setVisibility(
        model == null || model.tracks.isEmpty() ? View.VISIBLE : View.GONE);
    binding.pathEditorCanvas.setDocument(doc, model, selectedTrackId);
    updatePlaybarChrome();
    updatePoseMarker();
    if (worldBody != null) {
      buildWorldPage();   // the weather grid tracks the selection
    }
  }

  /* ------------------------------------------------------------------ */
  /* the PATH page                                                        */

  private void initPathView() {
    binding.pathEditorCanvas.setDocument(doc, model, selectedTrackId);
    binding.pathEditorCanvas.setAnchorListener(this);
    binding.pathEditorCanvas.post(() -> {
      if (binding != null) {
        binding.pathEditorCanvas.frameModel();
      }
    });
  }

  @Override
  public void onAnchorMoved(CamtrackDoc.Track track, String kind, int index,
      double x, double z) {
    switch (kind) {
      case "start": setVec3(track.start, x, null, z); break;
      case "end": setVec3(track.end, x, null, z); break;
      case "center": setVec3(track.center, x, null, z); break;
      case "position": setVec3(track.position, x, null, z); break;
      case "lookAt": setVec3(track.lookAt, x, null, z); break;
      case "point":
        if (index >= 0 && index < track.points.size()) {
          setVec3(track.points.get(index), x, null, z);
        }
        break;
      default: break;
    }
    commitEdit();
  }

  private static void setVec3(double[] target, double x, Double y, Double z) {
    if (target == null) {
      return;
    }
    target[0] = x;
    if (y != null) {
      target[1] = y;
    }
    if (z != null) {
      target[2] = z;
    }
  }

  @Override
  public void onTrackPick(@Nullable String trackId) {
    if (trackId == null || trackId.equals(selectedTrackId)) {
      return;
    }
    selectedTrackId = trackId;
    refreshTrackUi();
    haptic.click();
  }

  /* ------------------------------------------------------------------ */
  /* the navigation                                                       */

  private void initNavigation() {
    BottomNavigationView nav = binding.navEditor;
    nav.setOnItemSelectedListener(item -> {
      int id = item.getItemId();
      if (id == R.id.menu_editor_tracks) {
        binding.flipperEditorPages.setDisplayedChild(0);
      } else if (id == R.id.menu_editor_path) {
        binding.flipperEditorPages.setDisplayedChild(1);
      } else if (id == R.id.menu_editor_world) {
        binding.flipperEditorPages.setDisplayedChild(2);
      } else if (id == R.id.menu_editor_models) {
        binding.flipperEditorPages.setDisplayedChild(3);
      }
      haptic.click();
      updateBackCallback();   // v10.0: page depth drives the back guard
      return true;
    });
    nav.setSelectedItemId(R.id.menu_editor_tracks);
  }

  /* ------------------------------------------------------------------ */
  /* the WORLD page (the SHUI World & Weather replica)                   */

  private LinearLayout worldBody;

  private void buildWorldPage() {
    if (binding == null) {
      return;
    }
    worldBody = binding.linearEditorWorld;
    worldBody.removeAllViews();
    CamtrackDoc.Track tr = selectedTrack();

    // ---- Scene environment ----
    addGroup(worldBody, getString(R.string.sh1_camtrack_group_sceneenv));
    addChipsRow(worldBody, sceneChips(), null);
    addValueRow(worldBody, getString(R.string.sh1_camtrack_env_pair),
        pairSummary(), v -> showPairSheet());
    addValueRow(worldBody, getString(R.string.sh1_camtrack_env_slot_a),
        presetLabel(pairSlot(0)), v -> showPresetPicker(
        getString(R.string.sh1_camtrack_env_slot_a), pairSlot(0),
        value -> setPairSlot(0, value)));
    addValueRow(worldBody, getString(R.string.sh1_camtrack_env_slot_b),
        presetLabel(pairSlot(1)), v -> showPresetPicker(
        getString(R.string.sh1_camtrack_env_slot_b), pairSlot(1),
        value -> setPairSlot(1, value)));

    // ---- Day / Night environments (v13) ----
    addGroup(worldBody, getString(R.string.sh1_camtrack_group_daynight));
    addChipsRow(worldBody, phaseChips(), null);
    addValueRow(worldBody, getString(R.string.sh1_camtrack_day_env),
        presetLabel(dayEnvResolved()), v -> showPresetPicker(
        getString(R.string.sh1_camtrack_day_env), dayEnvResolved(),
        value -> setModelEnvDay(value)));
    addValueRow(worldBody, getString(R.string.sh1_camtrack_night_env),
        presetLabel(nightEnvResolved()), v -> showPresetPicker(
        getString(R.string.sh1_camtrack_night_env), nightEnvResolved(),
        value -> setModelEnvNight(value)));
    addValueRow(worldBody, getString(R.string.sh1_camtrack_track_day_env),
        tr != null && tr.envDay != null
            ? presetLabel(tr.envDay) : getString(R.string.sh1_camtrack_inherit_model),
        v -> showPresetPicker(
            getString(R.string.sh1_camtrack_track_day_env),
            tr != null && tr.envDay != null ? tr.envDay : -1,
            true, value -> setTrackEnvOverride(true, value)));
    addValueRow(worldBody, getString(R.string.sh1_camtrack_track_night_env),
        tr != null && tr.envNight != null
            ? presetLabel(tr.envNight) : getString(R.string.sh1_camtrack_inherit_model),
        v -> showPresetPicker(
            getString(R.string.sh1_camtrack_track_night_env),
            tr != null && tr.envNight != null ? tr.envNight : -1,
            true, value -> setTrackEnvOverride(false, value)));

    // ---- Weather — per track ----
    addGroup(worldBody, getString(R.string.sh1_camtrack_group_weather));
    addWeatherGrid(worldBody);
    addValueRow(worldBody, getString(R.string.sh1_camtrack_model_weather),
        weatherLabel(modelWeather()), v -> showWeatherPicker(
        getString(R.string.sh1_camtrack_model_weather), modelWeather(),
        value -> setModelWeather(value)));
    addValueRow(worldBody, getString(R.string.sh1_camtrack_boot_recipe),
        bootWeatherLabel(bootWeather()), v -> showBootWeatherPicker(
        getString(R.string.sh1_camtrack_boot_recipe), bootWeather(),
        value -> setBootWeather(value)));

    // ---- Atmosphere ----
    addGroup(worldBody, getString(R.string.sh1_camtrack_group_atmo));
    addValueRow(worldBody, getString(R.string.sh1_camtrack_lens_flare),
        lensFlareLabel(lensFlare()), v -> showLensFlarePicker(
        getString(R.string.sh1_camtrack_lens_flare), lensFlare(),
        value -> setLensFlare(value)));
    addButton(worldBody, "⚡ Lightning strike", v -> {
      // session-only: the engine's own L-key lightning (never serialized)
      Sh1NativeSafe.key(KEY_LIGHTNING);
      haptic.click();
    });

    // ---- Rendering ----
    addGroup(worldBody, getString(R.string.sh1_camtrack_group_rendering));
    addSwitchRow(worldBody, getString(R.string.sh1_camtrack_flashlight),
        null, flashlightOn(), checked -> setEnvFlag("flashlightOn", checked));
    addSwitchRow(worldBody, getString(R.string.sh1_camtrack_psx_wobble),
        "GTE snap + 15-bit", psxWobble(), checked -> setEnvFlag("psxWobble", checked));
    addSwitchRow(worldBody, getString(R.string.sh1_camtrack_dither),
        "PSX dither matrix", dither(), checked -> setEnvFlag("dither", checked));
    addSwitchRow(worldBody, getString(R.string.sh1_camtrack_particles),
        "engine particles", particles(), checked -> setEnvFlag("particles", checked));
    addSwitchRow(worldBody, getString(R.string.sh1_camtrack_engine),
        "master (--nofog look off)", engine(), checked -> setEnvFlag("engine", checked));
  }

  /** the scene-env quick chips (the SHUI panel's presets) */
  private List<ChipSpec> sceneChips() {
    List<ChipSpec> chips = new ArrayList<>();
    chips.add(new ChipSpec("Day {1,1}", () -> setPairBoth(1)));
    chips.add(new ChipSpec("Night {6,3}", () -> setPairBoth(6, 3)));
    chips.add(new ChipSpec("Nightmare {7,4}", () -> setPairBoth(7, 4)));
    chips.add(new ChipSpec("Pure night {6,6}", () -> setPairBoth(6)));
    chips.add(new ChipSpec("Dynamic fog {5,5}", () -> setPairBoth(5)));
    return chips;
  }

  /** the phase lens chips (session-only; the v13 Day/Night lens) */
  private List<ChipSpec> phaseChips() {
    List<ChipSpec> chips = new ArrayList<>();
    chips.add(new ChipSpec(getString(R.string.sh1_camtrack_phase_scene), () -> {
      phase = PHASE_SCENE;
      onPhaseChanged();
    }));
    chips.add(new ChipSpec(getString(R.string.sh1_camtrack_phase_day), () -> {
      phase = PHASE_DAY;
      onPhaseChanged();
    }));
    chips.add(new ChipSpec(getString(R.string.sh1_camtrack_phase_night), () -> {
      phase = PHASE_NIGHT;
      onPhaseChanged();
    }));
    return chips;
  }

  /**
   * Phase change: session-only (never serialized). The lens applies the
   * resolved preset INSTANTLY through the engine's own env mode path —
   * the v13 editor's lens semantics (the ENGINE transitions day/night
   * through its own environment paths at runtime).
   */
  private void onPhaseChanged() {
    if (!PHASE_SCENE.equals(phase)) {
      Sh1NativeSafe.env(phasePresetArg());
    } else {
      // back to the authored pair: re-boot onto the camdat's own env
      bootPreview(true);
    }
    buildWorldPage();
    haptic.click();
  }

  /* ---- world field accessors (env-object create-on-touch) -------- */

  private CamtrackDoc.Env ensureEnv() {
    if (model.env == null) {
      model.env = new CamtrackDoc.Env();
    }
    return model.env;
  }

  private int pairSlot(int idx) {
    if (model.env == null) {
      return idx == 0 ? 1 : 1;    // the shipped default {1,1}
    }
    Integer v = idx == 0 ? model.env.pair0 : model.env.pair1;
    return v != null ? v : 1;
  }

  private String pairSummary() {
    return "pair {" + pairSlot(0) + ", " + pairSlot(1) + "}";
  }

  private void setPairSlot(int idx, int preset) {
    CamtrackDoc.Env env = ensureEnv();
    if (idx == 0) {
      env.pair0 = preset;
    } else {
      env.pair1 = preset;
    }
    model.deriveLegacyFlags();
    commitEdit();
    buildWorldPage();
  }

  private void setPairBoth(int a) {
    setPairSlot(0, a);
    setPairSlot(1, a);
  }

  private void setPairBoth(int a, int b) {
    setPairSlot(0, a);
    setPairSlot(1, b);
  }

  private int dayEnvResolved() {
    if (model.env != null && model.env.dayEnv != null) {
      return model.env.dayEnv;
    }
    return SH1.ENV_DAY;
  }

  private int nightEnvResolved() {
    if (model.env != null && model.env.nightEnv != null) {
      return model.env.nightEnv;
    }
    return SH1.ENV_NIGHT;
  }

  private void setModelEnvDay(int value) {
    ensureEnv().dayEnv = value;
    commitEdit();
    buildWorldPage();
  }

  private void setModelEnvNight(int value) {
    ensureEnv().nightEnv = value;
    commitEdit();
    buildWorldPage();
  }

  private void setTrackEnvOverride(boolean day, int value) {
    CamtrackDoc.Track tr = selectedTrack();
    if (tr == null) {
      return;
    }
    // -1 = Inherit (clear) — the v13 editor's rule
    if (day) {
      tr.envDay = value >= 0 ? value : null;
    } else {
      tr.envNight = value >= 0 ? value : null;
    }
    commitEdit();
    buildWorldPage();
  }

  private int modelWeather() {
    if (model.env != null && model.env.weatherTarget != null) {
      return model.env.weatherTarget;
    }
    return 2;   // the SHUI panel's absent default (map0 snow)
  }

  private void setModelWeather(int value) {
    ensureEnv().weatherTarget = value;
    commitEdit();
    buildWorldPage();
  }

  private int bootWeather() {
    if (model.env != null && model.env.bootWeather != null) {
      return model.env.bootWeather;
    }
    return -1;
  }

  private void setBootWeather(int value) {
    ensureEnv().bootWeather = value;
    commitEdit();
    buildWorldPage();
  }

  private int lensFlare() {
    if (model.env != null && model.env.lensFlare != null) {
      return model.env.lensFlare;
    }
    return 0;
  }

  private void setLensFlare(int value) {
    ensureEnv().lensFlare = value;
    commitEdit();
    buildWorldPage();
  }

  private boolean flashlightOn() {
    return model.env != null && Boolean.TRUE.equals(model.env.flashlightOn);
  }

  private boolean psxWobble() {
    return model.env == null || model.env.psxWobble == null
        || model.env.psxWobble;
  }

  private boolean dither() {
    return model.env == null || model.env.dither == null || model.env.dither;
  }

  private boolean particles() {
    return model.env == null || model.env.particles == null
        || model.env.particles;
  }

  private boolean engine() {
    return model.env == null || model.env.engine == null || model.env.engine;
  }

  private void setEnvFlag(String field, boolean value) {
    CamtrackDoc.Env env = ensureEnv();
    switch (field) {
      case "flashlightOn": env.flashlightOn = value; break;
      case "psxWobble": env.psxWobble = value; break;
      case "dither": env.dither = value; break;
      case "particles": env.particles = value; break;
      case "engine": env.engine = value; break;
      default: break;
    }
    model.deriveLegacyFlags();
    commitEdit();
  }

  /* ---- labels (the SHUI panel's own texts) ------------------------ */

  private String presetLabel(int idx) {
    if (idx >= 0 && idx < SH1.ENV_MODES.length) {
      return "[" + idx + "] " + SH1.ENV_MODES[idx];
    }
    return "—";
  }

  private static String weatherLabel(int w) {
    switch (w) {
      case 0: return "0 — none";
      case 1: return "1 — snow wind 0";
      case 2: return "2 — snow wind 1";
      case 3: return "3 — snow wind 2";
      case 4: return "4 — snow wind 3";
      case 5: return "5 — rain wind 0";
      case 6: return "6 — rain wind 1 (the game's event arg)";
      case 7: return "7 — rain wind 2";
      default: return "8 — rain wind 3";
    }
  }

  private static String bootWeatherLabel(int b) {
    switch (b) {
      case -1: return "map0_s00 default (snow, Heavy)";
      case 0: return "0 — none";
      case 1: return "1 — snow";
      case 2: return "2 — snow + wind flag";
      case 5: return "5 — rain";
      default: return "6 — rain + wind flag";
    }
  }

  private static String lensFlareLabel(int l) {
    switch (l) {
      case 0: return "0 — Default (ambient only)";
      case 1: return "1 — Custom (flashlight)";
      case 2: return "2 — Preset1 (-20, 195)";
      case 3: return "3 — Preset2 (-20, -75)";
      case 4: return "4 — Preset3 (-20, 15)";
      default: return "5 — Preset4 (-20, 105)";
    }
  }

  /* ---- the weather grid (the SHUI wgrid replica) ------------------ */

  private void addWeatherGrid(LinearLayout parent) {
    LinearLayout grid = new LinearLayout(this);
    grid.setOrientation(LinearLayout.VERTICAL);
    grid.setPadding(dp(16), dp(4), dp(16), dp(4));
    int[] weatherValues = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8};
    int rowLen = 5;
    CamtrackDoc.Track tr = selectedTrack();
    int current = tr != null && tr.weatherTarget != null
        ? tr.weatherTarget : modelWeather();
    for (int r = 0; r < weatherValues.length; r += rowLen) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(4);
      row.setLayoutParams(lp);
      for (int c = 0; c < rowLen && r + c < weatherValues.length; c++) {
        int w = weatherValues[r + c];
        row.addView(makeWeatherCell(w, w == current));
      }
      grid.addView(row);
    }
    parent.addView(grid);
  }

  @SuppressLint("SetTextI18n")
  private View makeWeatherCell(int w, boolean selected) {
    com.google.android.material.button.MaterialButton btn =
        new com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    lp.setMargins(0, 0, dp(4), 0);
    btn.setLayoutParams(lp);
    btn.setTextSize(11);
    btn.setPadding(0, dp(6), 0, dp(6));
    if (w < 0) {
      btn.setText("Inherit\nmodel default");
      btn.setMinLines(2);
    } else if (w == 0) {
      btn.setText("None");
    } else if (w == 6) {
      btn.setText("Rain\nwind 1 ★");
      btn.setMinLines(2);
    } else if (w < 5) {
      btn.setText("Snow\nwind " + (w - 1));
      btn.setMinLines(2);
    } else {
      btn.setText("Rain\nwind " + (w - 5));
      btn.setMinLines(2);
    }
    if (selected) {
      btn.setBackgroundColor(
          xyz.zedler.patrick.doodle.util.ResUtil.getColor(this, R.attr.colorSecondaryContainer));
    }
    btn.setOnClickListener(v -> {
      CamtrackDoc.Track tr = selectedTrack();
      if (tr != null) {
        // the grid sets the SELECTED track's weather (-1 = Inherit)
        tr.weatherTarget = w >= 0 ? w : null;
      } else {
        // with no selection the grid edits the model default (the v13 rule)
        setModelWeather(Math.max(0, w));
      }
      commitEdit();
      buildWorldPage();
      refreshTrackUi();
      haptic.click();
    });
    return btn;
  }

  /* ------------------------------------------------------------------ */
  /* the MODELS page                                                      */

  private void buildModelsPage() {
    if (binding == null) {
      return;
    }
    LinearLayout body = binding.linearEditorModels;
    body.removeAllViews();
    if (doc == null || doc.models.isEmpty()) {
      addNote(body, getString(R.string.sh1_camtrack_models_empty));
      return;
    }
    for (CamtrackDoc.Model m : doc.models) {
      addValueRow(body, m.file, modelSummary(m), v -> {
        if (model != m) {
          model = m;
          CamtrackDoc.Track first = firstEnabledTrack(m);
          selectedTrackId = first != null ? first.id : null;
          if (trackAdapter != null) {
            trackAdapter.swapModel(m);
          }
          refreshTrackUi();
          buildModelsPage();
          commitEdit();     // the preview re-boots onto this model
          haptic.click();
        }
      });
    }
  }

  private String modelSummary(CamtrackDoc.Model m) {
    StringBuilder sb = new StringBuilder();
    sb.append(getResources().getQuantityString(
        R.plurals.plurals_tracks, m.tracks.size(), m.tracks.size()));
    if (m.env != null && m.env.pair0 != null && m.env.pair1 != null) {
      sb.append(" · pair {").append(m.env.pair0).append(',')
          .append(m.env.pair1).append('}');
    }
    sb.append(" · fogon ").append(m.fogon)
        .append(" · nighton ").append(m.nighton)
        .append(" · flashlighton ").append(m.flashlighton);
    return sb.toString();
  }

  /* ------------------------------------------------------------------ */
  /* the props sheet (the renderProps replica)                           */

  private void showPropsSheet(CamtrackDoc.Track tr) {
    PropsSheet sheet = new PropsSheet();
    sheet.provide(this, tr);
    sheet.show(getSupportFragmentManager(), "props");
  }

  /** build the props rows for the track (the renderProps field spec) */
  void buildPropsRows(LinearLayout body, CamtrackDoc.Track tr) {
    body.removeAllViews();
    if (tr == null) {
      addNote(body, getString(R.string.sh1_camtrack_props_empty));
      return;
    }
    addNote(body, typeNote(tr.type));

    // name
    RowEditorValueBinding nameRow = inflateValue(body);
    nameRow.textEditorValueLabel.setText(R.string.sh1_camtrack_name);
    nameRow.textEditorValueSummary.setText(tr.name);
    nameRow.linearEditorValue.setOnClickListener(v ->
        showTextInput(getString(R.string.sh1_camtrack_name), tr.name,
            text -> {
              tr.name = text;
              nameRow.textEditorValueSummary.setText(text);
              refreshTrackUi();
              commitEdit();
            }));

    // common
    addGroup(body, getString(R.string.sh1_camtrack_group_common));
    addSliderRow(body, getString(R.string.sh1_camtrack_fov), tr.fovDeg,
        5, 140, 1, "%.0f°", v -> {
          tr.fovDeg = v;
          commitEdit();
        });
    List<Sh1Option> loopOptions = new ArrayList<>();
    for (String lm : new String[]{CamtrackDoc.LOOP_LOOP,
        CamtrackDoc.LOOP_PINGPONG, CamtrackDoc.LOOP_ONCE}) {
      // v48 WAJ-4: decorated TITLE + raw-constant payload (the lookMode
      // pattern) — the row summary shows loopLabel(tr.loopMode), which
      // could never equal the raw-constant titles the old options carried,
      // so the sheet never opened with the current mode checked
      loopOptions.add(new Sh1Option(0, loopLabel(lm), lm));
    }
    addPickerRow(body, getString(R.string.sh1_camtrack_loop),
        loopLabel(tr.loopMode), loopOptions, value -> {
          tr.loopMode = value;
          commitEdit();
        });

    // per-type path fields
    switch (tr.type) {
      case CamtrackDoc.TYPE_LINE:
        addGroup(body, getString(R.string.sh1_camtrack_group_path));
        addVec3Row(body, getString(R.string.sh1_camtrack_start), tr.start);
        addVec3Row(body, getString(R.string.sh1_camtrack_end), tr.end);
        addSliderRow(body, getString(R.string.sh1_camtrack_speed_ms),
            tr.speedMS, 0.01, 20, 0.01, "%.2f m/s", v -> {
              tr.speedMS = v;
              commitEdit();
            });
        break;
      case CamtrackDoc.TYPE_CIRCLE:
        addGroup(body, getString(R.string.sh1_camtrack_group_path));
        addVec3Row(body, getString(R.string.sh1_camtrack_center), tr.center);
        addSliderRow(body, getString(R.string.sh1_camtrack_radius_x),
            tr.radiusX, 0.02, 60, 0.1, "%.2f m", v -> {
              tr.radiusX = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_radius_z),
            tr.radiusZ, 0.02, 60, 0.1, "%.2f m", v -> {
              tr.radiusZ = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_start_angle),
            tr.startAngleDeg, -360, 360, 1, "%.0f°", v -> {
              tr.startAngleDeg = v;
              commitEdit();
            });
        addPickerRow(body, getString(R.string.sh1_camtrack_direction),
            directionLabel(tr.direction), options(DIR_LABELS), value -> {
              tr.direction = value.contains("ccw")
                  ? CamtrackDoc.DIR_CCW : CamtrackDoc.DIR_CW;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_speed_degs),
            tr.speedDegS, 0.01, 180, 0.5, "%.1f°/s", v -> {
              tr.speedDegS = v;
              commitEdit();
            });
        addSwitchRow(body, getString(R.string.sh1_camtrack_arc_bounds),
            null, tr.arcBounds, checked -> {
              tr.arcBounds = checked;
              commitEdit();
              rebuildProps(tr);
            });
        if (tr.arcBounds) {
          addSliderRow(body, getString(R.string.sh1_camtrack_arc_min),
              tr.arcMinDeg, -360, 360, 1, "%.0f°", v -> {
                tr.arcMinDeg = v;
                commitEdit();
              });
          addSliderRow(body, getString(R.string.sh1_camtrack_arc_max),
              tr.arcMaxDeg, -360, 360, 1, "%.0f°", v -> {
                tr.arcMaxDeg = v;
                commitEdit();
              });
        }
        break;
      case CamtrackDoc.TYPE_CURVE:
      case CamtrackDoc.TYPE_POLYLINE:
        addGroup(body, getString(R.string.sh1_camtrack_group_points));
        for (int i = 0; i < tr.points.size(); i++) {
          final int idx = i;
          RowEditorValueBinding row = inflateValue(body);
          row.textEditorValueLabel.setText(
              getString(R.string.sh1_camtrack_point, i + 1));
          row.textEditorValueSummary.setText(vec3Summary(tr.points.get(i)));
          row.linearEditorValue.setOnClickListener(v ->
              showVec3Sheet(getString(R.string.sh1_camtrack_point, idx + 1),
                  tr.points.get(idx), () -> {
                    row.textEditorValueSummary.setText(
                        vec3Summary(tr.points.get(idx)));
                    commitEdit();
                  }));
        }
        addButton(body, "+ " + getString(R.string.sh1_camtrack_add_point),
            v -> appendCurvePoint(tr));
        addSliderRow(body, getString(R.string.sh1_camtrack_speed_ms),
            tr.speedMS, 0.01, 20, 0.01, "%.2f m/s", v -> {
              tr.speedMS = v;
              commitEdit();
            });
        break;
      case CamtrackDoc.TYPE_SPIRAL:
        addGroup(body, getString(R.string.sh1_camtrack_group_path));
        addVec3Row(body, getString(R.string.sh1_camtrack_center), tr.center);
        addSliderRow(body, getString(R.string.sh1_camtrack_radius_start),
            tr.radiusStart, 0.02, 60, 0.1, "%.2f m", v -> {
              tr.radiusStart = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_radius_end),
            tr.radiusEnd, 0.02, 60, 0.1, "%.2f m", v -> {
              tr.radiusEnd = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_height_start),
            tr.yStart, -50, 50, 0.1, "%.2f m", v -> {
              tr.yStart = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_height_end),
            tr.yEnd, -50, 50, 0.1, "%.2f m", v -> {
              tr.yEnd = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_start_angle),
            tr.startAngleDeg, -360, 360, 1, "%.0f°", v -> {
              tr.startAngleDeg = v;
              commitEdit();
            });
        addPickerRow(body, getString(R.string.sh1_camtrack_direction),
            directionLabel(tr.direction), options(DIR_LABELS), value -> {
              tr.direction = value.contains("ccw")
                  ? CamtrackDoc.DIR_CCW : CamtrackDoc.DIR_CW;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_turns),
            tr.turns, 0.05, 20, 0.05, "%.2f rev", v -> {
              tr.turns = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_speed_degs),
            tr.speedDegS, 0.01, 360, 0.5, "%.1f°/s", v -> {
              tr.speedDegS = v;
              commitEdit();
            });
        break;
      case CamtrackDoc.TYPE_ZIGZAG:
        addGroup(body, getString(R.string.sh1_camtrack_group_path));
        addVec3Row(body, getString(R.string.sh1_camtrack_center), tr.center);
        addSliderRow(body, getString(R.string.sh1_camtrack_size_x),
            tr.sizeX, 0.05, 200, 0.1, "%.2f m", v -> {
              tr.sizeX = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_size_z),
            tr.sizeZ, 0.05, 200, 0.1, "%.2f m", v -> {
              tr.sizeZ = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_lanes),
            tr.lanes, 2, 64, 1, "%.0f passes", v -> {
              tr.lanes = (int) Math.round(v);
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_speed_ms),
            tr.speedMS, 0.01, 20, 0.01, "%.2f m/s", v -> {
              tr.speedMS = v;
              commitEdit();
            });
        break;
      case CamtrackDoc.TYPE_FIGURE8:
        addGroup(body, getString(R.string.sh1_camtrack_group_path));
        addVec3Row(body, getString(R.string.sh1_camtrack_center), tr.center);
        addSliderRow(body, getString(R.string.sh1_camtrack_semi_x),
            tr.semiX, 0.02, 60, 0.1, "%.2f m", v -> {
              tr.semiX = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_semi_z),
            tr.semiZ, 0.02, 60, 0.1, "%.2f m", v -> {
              tr.semiZ = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_start_angle),
            tr.startAngleDeg, -360, 360, 1, "%.0f°", v -> {
              tr.startAngleDeg = v;
              commitEdit();
            });
        addPickerRow(body, getString(R.string.sh1_camtrack_direction),
            directionLabel(tr.direction), options(DIR_LABELS), value -> {
              tr.direction = value.contains("ccw")
                  ? CamtrackDoc.DIR_CCW : CamtrackDoc.DIR_CW;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_speed_degs),
            tr.speedDegS, 0.01, 360, 0.5, "%.1f°/s", v -> {
              tr.speedDegS = v;
              commitEdit();
            });
        break;
      default:  // fixed
        addGroup(body, "Position & rotation");
        addVec3Row(body, getString(R.string.sh1_camtrack_position), tr.position);
        addSliderRow(body, getString(R.string.sh1_camtrack_yaw),
            tr.yawDeg, -360, 360, 1, "%.1f°", v -> {
              tr.yawDeg = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_pitch),
            tr.pitchDeg, -90, 90, 1, "%.1f°", v -> {
              tr.pitchDeg = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_yaw_speed),
            tr.yawSpeedDegS, -180, 180, 0.5, "%.1f°/s", v -> {
              tr.yawSpeedDegS = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_pitch_speed),
            tr.pitchSpeedDegS, -90, 90, 0.5, "%.1f°/s", v -> {
              tr.pitchSpeedDegS = v;
              commitEdit();
            });
        addSwitchRow(body, getString(R.string.sh1_camtrack_yaw_bounds),
            null, tr.yawBounds, checked -> {
              tr.yawBounds = checked;
              commitEdit();
              rebuildProps(tr);
            });
        if (tr.yawBounds) {
          addSliderRow(body, getString(R.string.sh1_camtrack_yaw_min),
              tr.yawMinDeg, -360, 360, 1, "%.0f°", v -> {
                tr.yawMinDeg = v;
                commitEdit();
              });
          addSliderRow(body, getString(R.string.sh1_camtrack_yaw_max),
              tr.yawMaxDeg, -360, 360, 1, "%.0f°", v -> {
                tr.yawMaxDeg = v;
                commitEdit();
              });
        }
        addSwitchRow(body, getString(R.string.sh1_camtrack_pitch_bounds),
            null, tr.pitchBounds, checked -> {
              tr.pitchBounds = checked;
              commitEdit();
              rebuildProps(tr);
            });
        if (tr.pitchBounds) {
          addSliderRow(body, getString(R.string.sh1_camtrack_pitch_min),
              tr.pitchMinDeg, -90, 90, 1, "%.0f°", v -> {
                tr.pitchMinDeg = v;
                commitEdit();
              });
          addSliderRow(body, getString(R.string.sh1_camtrack_pitch_max),
              tr.pitchMaxDeg, -90, 90, 1, "%.0f°", v -> {
                tr.pitchMaxDeg = v;
                commitEdit();
              });
        }
        break;
    }

    // the look direction section (all types except fixed)
    if (!CamtrackDoc.TYPE_FIXED.equals(tr.type)) {
      addGroup(body, getString(R.string.sh1_camtrack_group_look));
      String[] lookModes = CamtrackDoc.Track.lookModesFor(tr.type);
      List<Sh1Option> lookOptions = new ArrayList<>();
      for (String lm : lookModes) {
        lookOptions.add(new Sh1Option(0, lookModeLabel(lm), lm));
      }
      addPickerRow(body, getString(R.string.sh1_camtrack_look_mode),
          lookModeLabel(tr.lookMode), lookOptions, value -> {
            tr.lookMode = value;
            commitEdit();
            rebuildProps(tr);
          });
      if (CamtrackDoc.LOOK_NORMAL.equals(tr.lookMode)) {
        addPickerRow(body, getString(R.string.sh1_camtrack_normal_side),
            tr.normalDirection, options(
                CamtrackDoc.SIDE_FORWARD, CamtrackDoc.SIDE_BACKWARD),
            value -> {
              tr.normalDirection = value;
              commitEdit();
            });
      }
      if (CamtrackDoc.LOOK_POINT.equals(tr.lookMode)) {
        addVec3Row(body, getString(R.string.sh1_camtrack_look_at), tr.lookAt);
      }
      if (CamtrackDoc.LOOK_FIXED.equals(tr.lookMode)) {
        addSliderRow(body, getString(R.string.sh1_camtrack_yaw),
            tr.yawDeg, -360, 360, 1, "%.1f°", v -> {
              tr.yawDeg = v;
              commitEdit();
            });
        addSliderRow(body, getString(R.string.sh1_camtrack_pitch),
            tr.pitchDeg, -90, 90, 1, "%.1f°", v -> {
              tr.pitchDeg = v;
              commitEdit();
            });
      }
      if (CamtrackDoc.LOOK_LAZY.equals(tr.lookMode)) {
        addSliderRow(body, getString(R.string.sh1_camtrack_lazy_rate),
            tr.lazyRateDegS, 1, 720, 1, "%.0f°/s", v -> {
              tr.lazyRateDegS = v;
              commitEdit();
            });
      }
    }

    // actions
    addButton(body, getString(R.string.sh1_camtrack_duplicate_track),
        v -> duplicateTrack(tr));
    addDangerButton(body, getString(R.string.sh1_camtrack_delete_track),
        v -> deleteTrack(tr));
  }

  private void rebuildProps(CamtrackDoc.Track tr) {
    // a structural toggle re-rendered the sheet (bounds/lookMode rows)
    PropsSheet sheet = (PropsSheet) getSupportFragmentManager()
        .findFragmentByTag("props");
    if (sheet != null && sheet.isActiveFor(tr)) {
      sheet.rebuild();
    }
  }

  private void appendCurvePoint(CamtrackDoc.Track tr) {
    if (tr.points == null || tr.points.size() < 2) {
      return;
    }
    int n = tr.points.size();
    double[] last = tr.points.get(n - 1);
    double[] prev = tr.points.get(n - 2);
    double dx = last[0] - prev[0];
    double dy = last[1] - prev[1];
    double dz = last[2] - prev[2];
    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 1e-6) {
      dx = 0.5;
      dy = 0;
      dz = 0;
      len = 0.5;
    }
    double step = Math.max(len * 0.5, 0.1);
    double[] p = new double[]{
        CamtrackDoc.r4(last[0] + dx / len * step),
        CamtrackDoc.r4(last[1] + dy / len * step),
        CamtrackDoc.r4(last[2] + dz / len * step)
    };
    if (tr.points.size() >= 64) {
      return;
    }
    tr.points.add(p);
    commitEdit();
    rebuildProps(tr);
    refreshTrackUi();
  }

  private void duplicateTrack(CamtrackDoc.Track tr) {
    CamtrackDoc.Track copy = CamtrackDoc.makeTrack(tr.type, model);
    // deep-copy the source fields (the v13 duplicateTrack semantics:
    // same parameters, fresh id, "<name> copy")
    copyTrackFields(tr, copy);
    copy.id = CamtrackDoc.newIdPublic();
    copy.name = (tr.name == null || tr.name.isEmpty() ? tr.type : tr.name) + " copy";
    model.tracks.add(copy);
    selectedTrackId = copy.id;
    refreshTrackUi();
    commitEdit();
  }

  private void deleteTrack(CamtrackDoc.Track tr) {
    if (model.tracks.size() <= 1) {
      Snackbar.make(binding.coordinatorEditor,
          R.string.sh1_camtrack_delete_last_track, Snackbar.LENGTH_SHORT).show();
      return;
    }
    int idx = model.tracks.indexOf(tr);
    model.tracks.remove(tr);
    if (selectedTrackId != null && selectedTrackId.equals(tr.id)) {
      CamtrackDoc.Track next = firstEnabledTrack(model);
      selectedTrackId = next != null ? next.id : null;
    }
    refreshTrackUi();
    commitEdit();
    PropsSheet sheet = (PropsSheet) getSupportFragmentManager()
        .findFragmentByTag("props");
    if (sheet != null && sheet.isActiveFor(tr)) {
      sheet.dismiss();
    }
  }

  /* ------------------------------------------------------------------ */
  /* the generic row builders (MD3 templates)                            */

  private LayoutInflater inflater() {
    return getLayoutInflater();
  }

  private int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
  }

  private void addGroup(LinearLayout parent, String label) {
    TextViewLike group = new TextViewLike(this);
    group.setText(label);
    parent.addView(group);
  }

  private void addNote(LinearLayout parent, String text) {
    NoteLike note = new NoteLike(this);
    note.setText(text);
    parent.addView(note);
  }

  private RowEditorValueBinding inflateValue(LinearLayout parent) {
    RowEditorValueBinding row = RowEditorValueBinding.inflate(inflater());
    parent.addView(row.getRoot());
    return row;
  }

  private void addValueRow(LinearLayout parent, String label, String summary,
      View.OnClickListener click) {
    RowEditorValueBinding row = inflateValue(parent);
    row.textEditorValueLabel.setText(label);
    row.textEditorValueSummary.setText(summary);
    row.linearEditorValue.setOnClickListener(click);
  }

  private void addPickerRow(LinearLayout parent, String label, String value,
      List<Sh1Option> options, PickConsumer onPick) {
    // value carries the display string; options map label->payload
    List<Sh1Option> opt = new ArrayList<>();
    for (int i = 0; i < options.size(); i++) {
      Sh1Option o = options.get(i);
      // v48 WAJ-4: DISTINCT values — Sh1OptionAdapter highlights by VALUE
      // and the old constant 0 let at most the FIRST row ever match
      opt.add(new Sh1Option(i, o.getTitle(), o.getSummary()));
    }
    addValueRow(parent, label, value, v ->
        PickerSheet.show(getSupportFragmentManager(), label, null, opt,
            indexOfTitle(opt, value),
            pos -> onPick.accept(opt.get(pos).getSummary())));
  }

  private static int indexOfTitle(List<Sh1Option> options, String title) {
    for (int i = 0; i < options.size(); i++) {
      if (options.get(i).getTitle().equals(title)) {
        return i;
      }
    }
    return -1;
  }

  private void addVec3Row(LinearLayout parent, String label, double[] value) {
    RowEditorValueBinding row = inflateValue(parent);
    row.textEditorValueLabel.setText(label);
    row.textEditorValueSummary.setText(vec3Summary(value));
    row.linearEditorValue.setOnClickListener(v ->
        showVec3Sheet(label, value, () -> {
          row.textEditorValueSummary.setText(vec3Summary(value));
          commitEdit();
        }));
  }

  private static String vec3Summary(double[] v) {
    return String.format(Locale.ROOT, "x %.3f · y %.3f · z %.3f", v[0], v[1], v[2]);
  }

  /**
   * v10.0 BUG 2 FIX (the editor crash class): every property slider is
   * CONTINUOUS (stepSize 0). Material Slider validates lazily at DRAW time
   * (BaseSlider.validateConfigurationIfDirty <- onDraw) and programmatic
   * setValue() does NOT snap to the step lattice — so a camdat value that
   * is not an exact multiple of the declared step (fov 67.5 on step 1,
   * speed 1.1254324 on step 0.01) threw IllegalStateException at the next
   * frame. Track coordinates, radii, speeds and angles are CONTINUOUS
   * physical quantities — the M3 Slider guidance reserves discrete steps
   * for enumerated semantic values (the "lanes" row, whose callback
   * already rounds to int, is the only quasi-discrete field). The exact
   * numbers path (tap the value) remains the precision entry; the passed
   * {@code step} stays in the signature as the intended display quantum
   * but the widget itself stays continuous so ANY float from the camdat
   * or the keyboard is legal.
   */
  private void addSliderRow(LinearLayout parent, String label, double value,
      double min, double max, double step, String format,
      DoubleConsumer onValue) {
    RowEditorSliderBinding row = RowEditorSliderBinding.inflate(inflater());
    parent.addView(row.getRoot());
    row.textEditorSliderLabel.setText(label);
    Slider slider = row.sliderEditorValue;
    slider.setValueFrom((float) min);
    slider.setValueTo((float) max);
    slider.setStepSize(0f);   // v10.0: continuous (see javadoc above)
    float clamped = (float) Math.min(Math.max(value, min), max);
    slider.setValue(clamped);
    row.textEditorSliderValue.setText(
        String.format(Locale.ROOT, format, (double) clamped));
    slider.addOnChangeListener((s, v, fromUser) -> {
      if (fromUser) {
        row.textEditorSliderValue.setText(String.format(Locale.ROOT, format,
            (double) v));
        onValue.accept(v);
      }
    });
    // precise numeric entry on the value tap (USER FIRST: exact numbers)
    row.textEditorSliderValue.setOnClickListener(v ->
        showNumberInput(label, String.format(Locale.ROOT, "%.4f",
            (double) slider.getValue()), text -> {
          try {
            double parsed = Double.parseDouble(text.replace(',', '.'));
            double clamped2 = Math.min(Math.max(parsed, min), max);
            slider.setValue((float) clamped2);
            row.textEditorSliderValue.setText(
                String.format(Locale.ROOT, format, clamped2));
            onValue.accept(clamped2);
          } catch (NumberFormatException ignored) {
          }
        }));
  }

  private void addSwitchRow(LinearLayout parent, String label, String sub,
      boolean checked, BoolConsumer onChange) {
    RowEditorSwitchBinding row = RowEditorSwitchBinding.inflate(inflater());
    parent.addView(row.getRoot());
    row.textEditorSwitchLabel.setText(label);
    if (sub != null) {
      row.textEditorSwitchSub.setVisibility(View.VISIBLE);
      row.textEditorSwitchSub.setText(sub);
    }
    row.switchEditorToggle.setChecked(checked);
    row.switchEditorToggle.setOnCheckedChangeListener(
        (v, c) -> onChange.accept(c));
  }

  @SuppressLint("SetTextI18n")
  private void addButton(LinearLayout parent, String label,
      View.OnClickListener click) {
    com.google.android.material.button.MaterialButton btn =
        new com.google.android.material.button.MaterialButton(this);
    btn.setText(label);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMargins(dp(16), dp(8), dp(16), dp(8));
    btn.setLayoutParams(lp);
    btn.setOnClickListener(click);
    parent.addView(btn);
  }

  @SuppressLint("SetTextI18n")
  private void addDangerButton(LinearLayout parent, String label,
      View.OnClickListener click) {
    com.google.android.material.button.MaterialButton btn =
        new com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    btn.setText(label);
    btn.setTextColor(
        xyz.zedler.patrick.doodle.util.ResUtil.getColor(this, R.attr.colorError));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMargins(dp(16), dp(8), dp(16), dp(8));
    btn.setLayoutParams(lp);
    btn.setOnClickListener(click);
    parent.addView(btn);
  }

  /** the SHUI-style chip row */
  private void addChipsRow(LinearLayout parent, List<ChipSpec> chips,
      @Nullable String note) {
    android.widget.HorizontalScrollView scroll =
        new android.widget.HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(dp(12), dp(4), dp(12), dp(4));
    scroll.addView(row);
    for (ChipSpec chip : chips) {
      com.google.android.material.chip.Chip c =
          new com.google.android.material.chip.Chip(this);
      c.setText(chip.label);
      c.setCheckable(false);
      c.setOnClickListener(v -> chip.action.run());
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.setMargins(0, 0, dp(8), 0);
      c.setLayoutParams(lp);
      row.addView(c);
    }
    parent.addView(scroll);
    if (note != null) {
      addNote(parent, note);
    }
  }

  /* ---- small views (programmatic group headers + notes) ---------- */

  private static final class TextViewLike extends android.widget.TextView {
    TextViewLike(Context context) {
      super(context);
      setTextSize(12);
      setLetterSpacing(0.08f);
      setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
      setAllCaps(true);
      setPadding(dpStatic(context, 16), dpStatic(context, 20), dpStatic(context, 16),
          dpStatic(context, 8));
      setTextColor(xyz.zedler.patrick.doodle.util.ResUtil.getColor(
          context, R.attr.colorPrimary));
    }

    private static int dpStatic(Context c, int v) {
      return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
  }

  private static final class NoteLike extends android.widget.TextView {
    NoteLike(Context context) {
      super(context);
      setTextSize(12);
      setLineSpacing(0, 1.25f);
      setPadding(dpStatic(context, 16), dpStatic(context, 4),
          dpStatic(context, 16), dpStatic(context, 4));
      setTextColor(xyz.zedler.patrick.doodle.util.ResUtil.getColor(
          context, R.attr.colorOnSurfaceVariant));
    }

    private static int dpStatic(Context c, int v) {
      return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
  }

  /* ------------------------------------------------------------------ */
  /* dialogs + sheets                                                     */

  /** the generic option picker sheet (the app's own single-choice rows) */
  public static class PickerSheet extends BaseSheetHost implements
      Sh1OptionAdapter.Sh1OptionAdapterListener {

    List<Sh1Option> options = new ArrayList<>();
    int selected = -1;
    IntConsumer onPick;

    static void show(androidx.fragment.app.FragmentManager fm, String title,
        String description, List<Sh1Option> options, int selected,
        IntConsumer onPick) {
      PickerSheet sheet = new PickerSheet();
      sheet.title = title;
      sheet.description = description;
      sheet.options = options;
      sheet.selected = selected;
      sheet.onPick = onPick;
      sheet.show(fm, "picker");
    }

    @Override
    public void onItemRowClicked(Sh1Option option) {
      if (onPick != null) {
        onPick.accept(indexOf(option));
      }
      dismiss();
    }

    @Override
    void buildRows(LinearLayout body) {
      Sh1OptionAdapter adapter = new Sh1OptionAdapter(options, selected, this);
      RecyclerView recycler = new RecyclerView(requireContext());
      recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
      recycler.setAdapter(adapter);
      recycler.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
      body.addView(recycler);
    }

    int indexOf(Sh1Option option) {
      for (int i = 0; i < options.size(); i++) {
        if (options.get(i) == option) {
          return i;
        }
      }
      return -1;
    }
  }

  /** the per-track property sheet (renderProps replica) */
  public static class PropsSheet extends BaseSheetHost {

    private CamtrackEditorActivity host;
    private CamtrackDoc.Track track;

    void provide(CamtrackEditorActivity host, CamtrackDoc.Track track) {
      this.host = host;
      this.track = track;
    }

    boolean isActiveFor(CamtrackDoc.Track t) {
      return track == t;
    }

    void rebuild() {
      if (host != null && body != null) {
        host.buildPropsRows(body, track);
      }
    }

    @Override
    void buildRows(LinearLayout body) {
      if (host != null) {
        host.buildPropsRows(body, track);
      }
    }
  }

  /** the base sheet with the shared options/props chrome */
  abstract static class BaseSheetHost
      extends com.google.android.material.bottomsheet.BottomSheetDialogFragment {

    String title;
    String description;
    LinearLayout body;

    /** subclasses populate the sheet body here (called once, on create) */
    abstract void buildRows(LinearLayout body);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
        @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
      SheetEditorPropsBinding binding = SheetEditorPropsBinding.inflate(
          inflater, container, false);
      body = binding.linearEditorSheetBody;
      binding.textEditorSheetTitle.setText(title != null ? title : "");
      if (description != null) {
        binding.textEditorSheetSubtitle.setVisibility(View.VISIBLE);
        binding.textEditorSheetSubtitle.setText(description);
      } else {
        binding.textEditorSheetSubtitle.setVisibility(View.GONE);
      }
      buildRows(body);
      return binding.getRoot();
    }
  }

  /** a coordinate editor: three MD3 sliders + precise entry */
  private void showVec3Sheet(String label, double[] value, Runnable onChange) {
    Vec3Sheet sheet = new Vec3Sheet();
    sheet.host = this;
    sheet.title = label;
    sheet.description = vec3Summary(value);
    sheet.value = value;
    sheet.onChange = onChange;
    sheet.show(getSupportFragmentManager(), "vec3");
  }

  /** the vec3 editor sheet (three sliders over the model bounds range) */
  public static class Vec3Sheet extends BaseSheetHost {

    CamtrackEditorActivity host;
    double[] value;
    Runnable onChange;

    @Override
    void buildRows(LinearLayout body) {
      if (host == null || value == null) {
        return;
      }
      double[] range = host.vec3Range();
      String[] names = {"x", "y", "z"};
      for (int axis = 0; axis < 3; axis++) {
        final int ax = axis;
        host.addSliderRow(body, names[ax], value[ax],
            range[0], range[1], 0.01, "%.3f m", v -> {
              value[ax] = v;
              if (onChange != null) {
                onChange.run();
              }
            });
      }
    }
  }

  /** the axis range: the model bounds padded by 25% of the diagonal
   *  (or a generous corridor when the camdat carries no bounds) */
  double[] vec3Range() {
    if (model != null && model.boundsMin != null && model.boundsMax != null) {
      double margin = 0.25 * Math.max(1, model.diag());
      double lo = Double.MAX_VALUE;
      double hi = -Double.MAX_VALUE;
      for (int i = 0; i < 3; i++) {
        lo = Math.min(lo, model.boundsMin[i]);
        hi = Math.max(hi, model.boundsMax[i]);
      }
      return new double[]{lo - margin, hi + margin};
    }
    return new double[]{-320, 320};
  }

  /** a plain text/number input (name fields, precise slider entry) */
  private void showTextInput(String title, String current,
      TextConsumer onDone) {
    DialogEditorNumberBinding dialogBinding =
        DialogEditorNumberBinding.inflate(getLayoutInflater());
    dialogBinding.inputEditorNumber.setText(current);
    dialogBinding.layoutInputEditorNumber.setHint(title);
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
        this, R.style.ThemeOverlay_Doodle_AlertDialog);
    builder.setTitle(title);
    builder.setView(dialogBinding.getRoot());
    androidx.appcompat.app.AlertDialog dialog = builder.create();
    builder.setPositiveButton(android.R.string.ok, (d, which) ->
        onDone.accept(dialogBinding.inputEditorNumber.getText().toString()));
    builder.setNegativeButton(android.R.string.cancel, null);
    dialogBinding.inputEditorNumber.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_DONE) {
            onDone.accept(v.getText().toString());
            dialog.dismiss();
            return true;
          }
          return false;
        });
    dialog.show();
  }

  /** a precise numeric input (slider value taps) */
  private void showNumberInput(String title, String current,
      TextConsumer onDone) {
    showTextInput(title, current, onDone);
  }

  /* ---- the pickers ------------------------------------------------ */

  private void showPresetPicker(String title, int current,
      IntConsumer onPick) {
    showPresetPicker(title, current, false, onPick);
  }

  private void showPresetPicker(String title, int current,
      boolean inheritFirst, IntConsumer onPick) {
    List<Sh1Option> options = new ArrayList<>();
    // v48 WAJ-4: Sh1OptionAdapter highlights by option VALUE (not by
    // position — the sheet pattern of Sh1EnvModesBottomSheetDialogFragment):
    // the Inherit row carries -1 and every preset its index, so `current`
    // IS the value to match. The old 0/1 pseudo-indices highlighted
    // preset [0] for Inherit and preset [1] for every override >= 0.
    int selected = current;
    if (inheritFirst) {
      options.add(new Sh1Option(-1,
          getString(R.string.sh1_camtrack_inherit_model), ""));
    }
    for (int i = 0; i < SH1.ENV_COUNT; i++) {
      Sh1Option opt = new Sh1Option(i,
          "[" + i + "] " + SH1.ENV_MODES[i], SH1.ENV_MODES_SUMMARY[i]);
      opt.setAssetThumb("sh1/thumbs/preset_" + i + ".png");
      options.add(opt);
    }
    PickerSheet.show(getSupportFragmentManager(), title,
        getString(R.string.sh1_camtrack_pick_preset), options, selected,
        value -> onPick.accept(options.get(value).getValue()));
  }

  private void showPairSheet() {
    // the 24 authentic pairs (the SHUI panel's grouped list)
    List<Sh1Option> options = new ArrayList<>();
    Object[][] pairs = Sh1Pairs.PAIRS;
    int selected = -1;
    for (int pos = 0; pos < pairs.length; pos++) {
      Object[] pair = pairs[pos];
      int a = (Integer) pair[0];
      int b = (Integer) pair[1];
      String label = (String) pair[2];
      String cite = (String) pair[3];
      // v48 WAJ-4: the adapter matches by VALUE and `a` alone is not a
      // unique row id ({5,5} and {5,0} share 5, {12,12} and {12,5} share
      // 12) — the row's position is the unique value, which is exactly
      // what the pick callback already indexes `pairs` by
      options.add(new Sh1Option(pos, label, cite));
      if (a == pairSlot(0) && b == pairSlot(1)) {
        selected = pos;
      }
    }
    PickerSheet.show(getSupportFragmentManager(),
        getString(R.string.sh1_camtrack_env_pair),
        getString(R.string.sh1_camtrack_pick_pair), options, selected,
        value -> {
          Object[] pair = pairs[value];
          setPairBoth((Integer) pair[0], (Integer) pair[1]);
        });
  }

  private void showWeatherPicker(String title, int current,
      IntConsumer onPick) {
    List<Sh1Option> options = new ArrayList<>();
    for (int w = 0; w <= 8; w++) {
      options.add(new Sh1Option(w, weatherLabel(w), ""));
    }
    PickerSheet.show(getSupportFragmentManager(), title, null, options,
        Math.max(0, current), value -> onPick.accept(value));
  }

  private void showBootWeatherPicker(String title, int current,
      IntConsumer onPick) {
    List<Sh1Option> options = new ArrayList<>();
    int[] values = {-1, 0, 1, 2, 5, 6};
    // v48 WAJ-4: the adapter matches by option VALUE — the rows carry
    // their weather constant (-1..6), not their position, so `current`
    // selects the boot recipe's own row. The old pseudo-index highlighted
    // "0 — none" for the -1 default and "5 — rain" for "6 — rain + wind".
    int selected = current;
    for (int i = 0; i < values.length; i++) {
      options.add(new Sh1Option(values[i], bootWeatherLabel(values[i]), ""));
    }
    PickerSheet.show(getSupportFragmentManager(), title, null, options,
        selected, value -> onPick.accept(values[value]));
  }

  private void showLensFlarePicker(String title, int current,
      IntConsumer onPick) {
    List<Sh1Option> options = new ArrayList<>();
    for (int l = 0; l <= 5; l++) {
      options.add(new Sh1Option(l, lensFlareLabel(l), ""));
    }
    PickerSheet.show(getSupportFragmentManager(), title, null, options,
        current, value -> onPick.accept(value));
  }

  /* ------------------------------------------------------------------ */
  /* small helpers                                                        */

  private static final String[] DIR_LABELS = {"cw — clockwise from above",
      "ccw — counter-clockwise"};

  private static String directionLabel(String dir) {
    return CamtrackDoc.DIR_CCW.equals(dir)
        ? "ccw — counter-clockwise" : "cw — clockwise from above";
  }

  private static String loopLabel(String mode) {
    switch (mode) {
      case CamtrackDoc.LOOP_LOOP: return "loop — wraps at the end";
      case CamtrackDoc.LOOP_ONCE: return "once — stop at the end";
      default: return "pingPong — mirrors at the ends";
    }
  }

  private static String lookModeLabel(String mode) {
    if (mode == null) {
      return "";
    }
    switch (mode) {
      case CamtrackDoc.LOOK_PATH: return "path — along travel";
      case CamtrackDoc.LOOK_LAZY: return "lazy — path direction, held gently";
      case CamtrackDoc.LOOK_NORMAL: return "normal — sideways of travel";
      case CamtrackDoc.LOOK_POINT: return "point — at a target";
      case CamtrackDoc.LOOK_CENTER: return "center — at the shape center";
      default: return "fixed — hold direction";
    }
  }

  private static String typeNote(String type) {
    switch (type) {
      case CamtrackDoc.TYPE_LINE:
        return "A straight move between two points at constant speed.";
      case CamtrackDoc.TYPE_CIRCLE:
        return "An elliptical orbit around a center.";
      case CamtrackDoc.TYPE_FIXED:
        return "Stands at a position; yaw/pitch rotate at their speeds.";
      case CamtrackDoc.TYPE_CURVE:
        return "A smooth Catmull-Rom spline through the anchor points.";
      case CamtrackDoc.TYPE_POLYLINE:
        return "Straight segments through the waypoints, corner by corner.";
      case CamtrackDoc.TYPE_SPIRAL:
        return "Spirals in or out while changing height.";
      case CamtrackDoc.TYPE_ZIGZAG:
        return "Lawnmower sweep across the rectangle.";
      default:
        return "A figure-8 (Gerono lemniscate) around the center.";
    }
  }

  private static List<Sh1Option> options(String... values) {
    List<Sh1Option> out = new ArrayList<>();
    for (String v : values) {
      out.add(new Sh1Option(0, v, v));
    }
    return out;
  }

  /** track field deep copy (duplicateTrack) */
  private static void copyTrackFields(CamtrackDoc.Track src, CamtrackDoc.Track dst) {
    dst.name = src.name;
    dst.type = src.type;
    dst.enabled = src.enabled;
    dst.fovDeg = src.fovDeg;
    dst.weatherTarget = src.weatherTarget;
    dst.envDay = src.envDay;
    dst.envNight = src.envNight;
    dst.loopMode = src.loopMode;
    dst.start = cloneOrNull(src.start);
    dst.end = cloneOrNull(src.end);
    dst.center = cloneOrNull(src.center);
    dst.position = cloneOrNull(src.position);
    dst.lookAt = cloneOrNull(src.lookAt);
    if (src.points != null) {
      dst.points = new ArrayList<>();
      for (double[] p : src.points) {
        dst.points.add(p.clone());
      }
    }
    dst.radiusX = src.radiusX;
    dst.radiusZ = src.radiusZ;
    dst.radiusStart = src.radiusStart;
    dst.radiusEnd = src.radiusEnd;
    dst.turns = src.turns;
    dst.yStart = src.yStart;
    dst.yEnd = src.yEnd;
    dst.sizeX = src.sizeX;
    dst.sizeZ = src.sizeZ;
    dst.lanes = src.lanes;
    dst.semiX = src.semiX;
    dst.semiZ = src.semiZ;
    dst.startAngleDeg = src.startAngleDeg;
    dst.direction = src.direction;
    dst.speedMS = src.speedMS;
    dst.speedDegS = src.speedDegS;
    dst.lookMode = src.lookMode;
    dst.normalDirection = src.normalDirection;
    dst.yawDeg = src.yawDeg;
    dst.pitchDeg = src.pitchDeg;
    dst.lazyRateDegS = src.lazyRateDegS;
    dst.arcBounds = src.arcBounds;
    dst.arcMinDeg = src.arcMinDeg;
    dst.arcMaxDeg = src.arcMaxDeg;
    dst.yawBounds = src.yawBounds;
    dst.yawMinDeg = src.yawMinDeg;
    dst.yawMaxDeg = src.yawMaxDeg;
    dst.pitchBounds = src.pitchBounds;
    dst.pitchMinDeg = src.pitchMinDeg;
    dst.pitchMaxDeg = src.pitchMaxDeg;
    dst.yawSpeedDegS = src.yawSpeedDegS;
    dst.pitchSpeedDegS = src.pitchSpeedDegS;
  }

  private static double[] cloneOrNull(double[] a) {
    return a != null ? a.clone() : null;
  }

  /** a commit: mark changed, flag the toolbar, schedule the live preview */
  private void commitEdit() {
    changedSinceOpen = true;
    binding.toolbarEditor.setSubtitle(getString(R.string.sh1_camtrack_dirty));
    schedulePreviewRefresh();
    if (trackAdapter != null) {
      trackAdapter.reload();
    }
    binding.pathEditorCanvas.setDocument(doc, model, selectedTrackId);
    updatePlaybarChrome();
    updatePoseMarker();
    updateBackCallback();   // v10.0: the exit guard is now live
  }

  /* ---- lambdas-era functional shapes (minSdk 23: no java.util.function
   * guarantee on android.jar's java.util.function? — it exists since API
   * 24. Define our own tiny shapes for API 23 safety) ----------------- */

  public interface IntConsumer {
    void accept(int value);
  }

  public interface DoubleConsumer {
    void accept(double value);
  }

  public interface BoolConsumer {
    void accept(boolean value);
  }

  public interface TextConsumer {
    void accept(String text);
  }

  public interface PickConsumer {
    void accept(String value);
  }

  private static final class ChipSpec {
    final String label;
    final Runnable action;

    ChipSpec(String label, Runnable action) {
      this.label = label;
      this.action = action;
    }
  }

  /* ---- the Sh1Native calls with session awareness -------------------- */

  private static final class Sh1NativeSafe {
    static void surfaceCreated(int gen, Surface surface) {
      try {
        xyz.zedler.patrick.doodle.service.Sh1Native.surfaceCreated(gen, surface);
      } catch (Throwable ignored) {
      }
    }

    static void surfaceDestroyed(int gen) {
      try {
        xyz.zedler.patrick.doodle.service.Sh1Native.surfaceDestroyed(gen);
      } catch (Throwable ignored) {
      }
    }

    static void setPaused(boolean paused) {
      try {
        xyz.zedler.patrick.doodle.service.Sh1Native.setPaused(paused);
      } catch (Throwable ignored) {
      }
    }

    static void setRate(double rate) {
      try {
        xyz.zedler.patrick.doodle.service.Sh1Native.setRate(rate);
      } catch (Throwable ignored) {
      }
    }

    static void seek(double t) {
      try {
        xyz.zedler.patrick.doodle.service.Sh1Native.seek(t);
      } catch (Throwable ignored) {
      }
    }

    static void env(int preset) {
      try {
        xyz.zedler.patrick.doodle.service.Sh1Native.env(preset);
      } catch (Throwable ignored) {
      }
    }

    static void key(int key) {
      try {
        xyz.zedler.patrick.doodle.service.Sh1Native.key(key);
      } catch (Throwable ignored) {
      }
    }

    // v10.1 TASK 4: the crop-window offsets (presentation-layer only)
    static void setCropOffset(float x, float y) {
      try {
        xyz.zedler.patrick.doodle.service.Sh1Native.setCropOffset(x, y);
      } catch (Throwable ignored) {
      }
    }
  }

  /** the 24 authentic env pairs with their decomp citations (SHUI's list) */
  static final class Sh1Pairs {
    static final Object[][] PAIRS = {
        {1, 1, "Day {1,1} — THR day street", "map_info.c D_800A9F80"},
        {3, 3, "Night {3,3}", "event Gfx_MapEnvSet"},
        {6, 3, "Night {6,3} — early night street", "map0_s01_events.c:920"},
        {7, 4, "Nightmare {7,4} — later street", "map0_s02_events.c:102"},
        {6, 6, "Pure night {6,6}", "event Gfx_MapEnvSet"},
        {5, 5, "Dynamic fog {5,5}", "load-screen blend"},
        {2, 2, "Interior day {2,2}", "map_info.c D_800A9F84"},
        {8, 8, "Lightning {8,8}", "event Gfx_MapEnvSet"},
        {9, 9, "Nowheres {9,9}", "map7_s03"},
        {10, 10, "Lighter {10,10}", "event Gfx_MapEnvSet"},
        {11, 11, "Red darkness {11,11}", "map6_s01"},
        {12, 12, "Interior flash {12,12}", "map_info.c"},
        {13, 13, "Dusk {13,13}", "map_info.c"},
        {14, 14, "Rust {14,14}", "map_info.c"},
        {15, 15, "Dark red {15,15}", "map_info.c"},
        {16, 16, "Dim {16,16}", "map_info.c"},
        {17, 17, "Thick fog {17,17}", "map_info.c"},
        {18, 18, "Spotlight {18,18}", "map_info.c"},
        {19, 19, "Blue night {19,19}", "map_info.c"},
        {20, 20, "map1_s06 {20,20}", "map_info.c"},
        {4, 4, "Nightmare flash {4,4}", "event Gfx_MapEnvSet"},
        {0, 0, "Fog-off ambient {0,0}", "map_info.c D_800A9F80[0]"},
        {5, 0, "Blend {5,0}", "load-screen target"},
        {12, 5, "Blend {12,5}", "load-screen target"}
    };
  }
}
