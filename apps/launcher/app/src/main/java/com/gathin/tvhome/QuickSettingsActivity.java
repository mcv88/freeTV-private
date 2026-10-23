package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a few glass rows under the Settings pill, over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Palette.loadDarkMode(this);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, 0, TvSettings.main(this));
        addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, TvSettings.picture(this));
        addRow(rows, R.drawable.ic_sound, R.string.quick_sound, 0, TvSettings.sound(this));
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        LinearLayout rows = findViewById(R.id.rows);
        int count = rows.getChildCount();

        if (count == 0) return super.onKeyDown(keyCode, event);

        View focused = getCurrentFocus();
        int index = -1;
        for (int i = 0; i < count; i++) {
            if (rows.getChildAt(i) == focused) {
                index = i;
                break;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getRepeatCount() > 0) return true;

            if (index < 0) {
                index = 0;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                index = Math.min(index + 1, count - 1);
            } else {
                index = Math.max(index - 1, 0);
            }

            rows.getChildAt(index).requestFocus();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (index < 0) index = 0;
            rows.getChildAt(index).performClick();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private void addRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            v.setSelected(hasFocus);
            v.setAlpha(hasFocus ? 1.0f : 0.82f);
        });
        row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        rows.addView(row);
    }
}
