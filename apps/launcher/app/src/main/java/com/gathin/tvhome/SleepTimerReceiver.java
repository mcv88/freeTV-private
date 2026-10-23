package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.IOException;

public final class SleepTimerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                long remaining = SleepTimer.remainingMillis(app);
                if (remaining > 0L) {
                    SleepTimer.schedule(app, System.currentTimeMillis() + remaining);
                    return;
                }
                SleepTimer.clearExpired(app);
                new LocalAdb(app).shell("input keyevent KEYCODE_SLEEP");
            } catch (LocalAdb.OffException e) {
                toast(app, R.string.sleep_timer_adb_off);
            } catch (LocalAdb.NotAllowedException e) {
                toast(app, R.string.sleep_timer_adb_not_allowed);
            } catch (IOException | RuntimeException e) {
                toast(app, R.string.sleep_timer_failed);
            } finally {
                pending.finish();
            }
        }, "SleepTimerAdb").start();
    }

    private static void toast(Context c, int id) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(c, id, Toast.LENGTH_LONG).show());
    }
}
