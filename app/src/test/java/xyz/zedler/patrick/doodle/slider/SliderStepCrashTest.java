/*
 * v10.0 — the CAMTRACK EDITOR SLIDER CRASH mechanism test (Phase G BUG 2).
 *
 * The reported crash:
 *   java.lang.IllegalStateException: Value(1.1254324) must be equal to
 *   valueFrom(0.0) plus a multiple of stepSize(1.0) when using stepSize(1.0)
 *       at BaseSlider.validateConfigurationIfDirty(SourceFile:182)
 *       at BaseSlider.onDraw(SourceFile:11)
 *
 * Material Slider validates LAZILY AT DRAW TIME; a programmatic setValue()
 * of an off-lattice float is accepted silently and kills the app at the
 * next frame. This test reproduces the mechanism with the REAL Material
 * Slider on the simulated framework, then proves the shipped v10.0
 * configuration (the editor's scrub/property sliders, CONTINUOUS since
 * the fix) survives the exact reported value — including an actual
 * measure/layout/draw pass.
 */
package xyz.zedler.patrick.doodle.slider;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.material.slider.Slider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SliderStepCrashTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    context.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight);
  }

  /** measure/layout/draw — the pass whose onDraw triggers validation */
  private static void exerciseView(View view) {
    int spec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
    view.measure(spec, spec);
    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    view.draw(new Canvas());
  }

  /**
   * RED (the v9.9 defect): the scrub slider's shipped XML values
   * (valueFrom=0, valueTo=1000, stepSize=1) plus the playhead float
   * updatePlaybarChrome sets EVERY Choreographer frame —
   * IllegalStateException at the next draw.
   */
  @Test
  public void v99Defect_stepLatticeCrashesAtDraw() {
    Slider slider = new Slider(context);
    slider.setValueFrom(0f);
    slider.setValueTo(1000f);
    slider.setStepSize(1f);          // the v9.9 scrub slider XML
    slider.setValue(1.1254324f);     // trackTime / cycle * 1000.0
    try {
      exerciseView(slider);
      fail("the v9.9 configuration must crash at draw time — the lazy "
          + "validateConfigurationIfDirty path is the reported defect");
    } catch (IllegalStateException expected) {
      // exactly the reported exception class + message shape
      assertEquals(true, expected.getMessage() != null
          && expected.getMessage().contains("must be equal to valueFrom"));
    }
  }

  /**
   * GREEN (the v10.0 fix): the CONTINUOUS scrub slider (stepSize removed
   * from the XML) accepts the SAME playhead float and survives a full
   * measure/layout/draw pass. Every per-mille value of every frame is
   * legal on a continuous slider.
   */
  @Test
  public void v10Fix_continuousScrubSurvivesEveryPlayheadFloat() {
    for (float v = 0f; v <= 1000f; v += 0.33333337f) {   // arbitrary floats
      Slider slider = new Slider(context);
      slider.setValueFrom(0f);
      slider.setValueTo(1000f);
      slider.setStepSize(0f);        // v10.0: no stepSize attribute
      slider.setValue(v);
      exerciseView(slider);          // must NOT throw
      assertEquals(v, slider.getValue(), 1e-4f);
    }
  }

  /**
   * GREEN (the v10.0 fix): the editor PROPERTY sliders (addSliderRow's
   * setStepSize(0f)) accept arbitrary camdat-derived floats — the exact
   * reported value 1.1254324 across the physical-quantity ranges
   * (speed 0.01..20, fov 5..140, angle -360..360, radius 0.02..60).
   */
  @Test
  public void v10Fix_continuousPropertySlidersAcceptCamdatFloats() {
    float[][] ranges = {
        {0.01f, 20f}, {5f, 140f}, {-360f, 360f}, {0.02f, 60f},
        {-90f, 90f}, {0.05f, 200f}, {1f, 720f}, {2f, 64f}
    };
    for (float[] range : ranges) {
      Slider slider = new Slider(context);
      slider.setValueFrom(range[0]);
      slider.setValueTo(range[1]);
      slider.setStepSize(0f);        // v10.0 addSliderRow: continuous
      float v = (float) Math.min(Math.max(1.1254324, range[0]), range[1]);
      slider.setValue(v);
      exerciseView(slider);          // must NOT throw
    }
  }

  /**
   * The shared style no longer carries a blanket stepSize default:
   * a Widget.Doodle.Slider-styled slider with no explicit android:stepSize
   * is CONTINUOUS (the latent draw-time landmine is gone).
   */
  @Test
  public void v10Fix_styleDefaultIsContinuous() {
    LinearLayout host = new LinearLayout(context);
    View inflated = android.view.LayoutInflater.from(context)
        .inflate(xyz.zedler.patrick.doodle.R.layout.row_editor_slider, host, false);
    Slider slider = inflated.findViewById(
        xyz.zedler.patrick.doodle.R.id.slider_editor_value);
    assertEquals(0f, slider.getStepSize(), 0f);
    slider.setValueFrom(0.01f);
    slider.setValueTo(20f);
    slider.setValue(1.1254324f);
    exerciseView(slider);            // must NOT throw
  }

  /**
   * REGRESSION GUARD: the discrete app sliders (settings resolution
   * 0..6 step 1, fps 1..60 step 1; parallax tilt 1..20 step 0.5) still
   * validate — their values are always integers (lattice-safe).
   */
  @Test
  public void discreteAppSliders_stillValid() {
    Slider res = new Slider(context);
    res.setValueFrom(0f);
    res.setValueTo(6f);
    res.setStepSize(1f);
    res.setValue(3f);
    exerciseView(res);

    Slider fps = new Slider(context);
    fps.setValueFrom(1f);
    fps.setValueTo(60f);
    fps.setStepSize(1f);
    fps.setValue(30f);
    exerciseView(fps);

    Slider tilt = new Slider(context);
    tilt.setValueFrom(1f);
    tilt.setValueTo(20f);
    tilt.setStepSize(0.5f);
    tilt.setValue(3f);
    exerciseView(tilt);
  }
}
