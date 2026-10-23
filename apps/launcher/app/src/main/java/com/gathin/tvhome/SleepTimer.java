package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

final class SleepTimer {
    private static final String PREFS = "sleep_timer";
    private static final String END_AT = "end_at";
    private static final int REQUEST_CODE = 7341;

    private SleepTimer() {}

    static long remainingMillis(Context c) {
        return Math.max(0L, prefs(c).getLong(END_AT, 0L) - System.currentTimeMillis());
    }

    static void start(Context c, int minutes) {
        long end = System.currentTimeMillis() + minutes * 60_000L;
        prefs(c).edit().putLong(END_AT, end).apply();
        schedule(c, end);
    }

    static void cancel(Context c) {
        AlarmManager a = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (a != null) a.cancel(pending(c));
        prefs(c).edit().remove(END_AT).apply();
    }

    static void schedule(Context c, long at) {
        AlarmManager a = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (a == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c));
        else
            a.set(AlarmManager.RTC_WAKEUP, at, pending(c));
    }

    static void clearExpired(Context c) {
        prefs(c).edit().remove(END_AT).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, SleepTimerReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQUEST_CODE, i, flags);
    }
}
