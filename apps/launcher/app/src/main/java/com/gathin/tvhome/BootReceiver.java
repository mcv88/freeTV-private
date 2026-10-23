package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Opens the app the owner picked in Launcher settings when the TV boots: the one they want on
 * screen when the TV turns on, FPT Play say, instead of the home screen alone.
 */
public final class BootReceiver extends BroadcastReceiver {

    static final String PREF_KEY = "boot_app";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !"android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences(
                Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String pkg = prefs.getString(PREF_KEY, "");
        if (pkg == null || pkg.isEmpty()) return;
        Intent open = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (open == null) return;
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(open);
        } catch (RuntimeException refused) {
            // The app went away or the TV refuses the start: the home screen stays, nothing to do.
        }
    }
}
