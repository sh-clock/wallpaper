package xyz.zedler.patrick.doodle.activity;

import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.LEGACY)
public class EditorBootSmokeTest {

  @Test
  public void activityBoots() {
    ActivityController<CamtrackEditorActivity> c =
        Robolectric.buildActivity(CamtrackEditorActivity.class).setup();
    System.out.println("booted: " + (c.get() != null));
    c.destroy();
  }
}
