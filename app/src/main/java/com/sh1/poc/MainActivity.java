package com.sh1.poc;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Launcher stub: the renderer itself lives in Sh1WallpaperService.
 * This screen only routes the user to the system live-wallpaper preview.
 */
public class MainActivity extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (28 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF07080A);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("SH1 FOG");
        title.setTextColor(0xFFB9BEC7);
        title.setTextSize(30);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Silent Hill 1 environment renderer\n"
                + "live wallpaper · software-rasterized · fully offline");
        sub.setTextColor(0xFF6E747E);
        sub.setTextSize(13);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, (int) (10 * dp), 0, (int) (24 * dp));
        root.addView(sub);

        Button set = new Button(this);
        set.setText("Set as live wallpaper");
        set.setOnClickListener(v -> {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, Sh1WallpaperService.class));
            startActivity(i);
        });
        root.addView(set, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }
}
