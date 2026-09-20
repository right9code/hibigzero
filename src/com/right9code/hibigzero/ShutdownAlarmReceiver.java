package com.right9code.hibigzero;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.util.Properties;

public class ShutdownAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_SHUTDOWN_TIMER = "com.right9code.hibigzero.SHUTDOWN_TIMER";
    private static final String TAG = "ShutdownAlarm";
    private static final String RUNTIME_PREFS = "hibreak_runtime";
    private static final String KEY_SCREEN_OFF_AT = "screen_off_elapsed_ms";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!ACTION_SHUTDOWN_TIMER.equals(intent.getAction())) return;
        final Context appCtx = context.getApplicationContext();
        final PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    decide(appCtx);
                } catch (Throwable t) {
                    // FAIL SAFE: never power the device off on uncertainty. The old
                    // implementation fell through to shutdown on any read/parse error.
                    Log.e(TAG, "decision failed, rescheduling instead of shutting down", t);
                    ShellUtils.appendLog("ShutdownAlarm: error (" + t + ") - rescheduled, NOT shutting down");
                    try { scheduleAlarmWithConfig(appCtx); } catch (Throwable ignored) {}
                } finally {
                    pendingResult.finish();
                }
            }
        }).start();
    }

    /**
     * Decide what to do when the inactivity alarm fires.
     *
     * The alarm is armed as (screen-off + timeout) and cancelled on screen-on, so
     * the only thing that can invalidate "the user has been idle long enough" is
     * the screen being on. The cancel is duplicated here because it does not run
     * when our process has been killed - which is how the device previously ended
     * up holding a stale alarm.
     *
     * NOTE: the old implementation compared `dumpsys power`'s mLastUserActivityTime
     * (SystemClock.uptimeMillis domain) against System.currentTimeMillis() (epoch
     * domain). That difference measures ~56 years, so the "user is active,
     * reschedule" branch was mathematically unreachable and every firing powered
     * the device off - including while the user was reading.
     */
    private static void decide(Context context) {
        Properties cfg = ConfigManager.loadConfig();
        if (!"1".equals(cfg.getProperty("AUTO_SHUTDOWN_ENABLED", "0"))) {
            cancelAlarm(context);
            ShellUtils.appendLog("ShutdownAlarm: disabled in config - alarm cancelled");
            return;
        }
        long timeoutMs = timeoutMs(cfg);

        // 1. Charging is checked FIRST so the decision log is unambiguous, and a
        //    plugged-in device (where a power-off saves nothing) is left alone.
        if (!"0".equals(cfg.getProperty("AUTO_SHUTDOWN_SKIP_WHEN_CHARGING", "1"))) {
            if (isCharging(context)) {
                Log.i(TAG, "on charger -> rescheduling");
                ShellUtils.appendLog("ShutdownAlarm: on charger - rescheduled " + (timeoutMs / 60000) + "m");
                scheduleAlarm(context, timeoutMs);
                return;
            }
        }

        // 2. Screen on = the user came back.
        if (isInteractive(context)) {
            Log.i(TAG, "screen on -> rescheduling");
            ShellUtils.appendLog("ShutdownAlarm: screen is on (user active) - rescheduled "
                + (timeoutMs / 60000) + "m");
            scheduleAlarm(context, timeoutMs);
            return;
        }

        // 3. Cross-check our own monotonic screen-off stamp when we have one, so a
        //    leftover alarm from before a process restart cannot fire early.
        long idleMs = idleSinceScreenOff(context);
        if (idleMs >= 0 && idleMs < timeoutMs) {
            long remaining = timeoutMs - idleMs;
            Log.i(TAG, "idle " + (idleMs / 60000) + "m -> rescheduling " + (remaining / 60000) + "m");
            ShellUtils.appendLog("ShutdownAlarm: idle only " + (idleMs / 60000)
                + "m - rescheduled " + (remaining / 60000) + "m");
            scheduleAlarm(context, remaining);
            return;
        }

        // 4. Idle long enough -> power off.
        String idleText = (idleMs >= 0) ? (idleMs / 60000) + "m" : "unknown (no screen-off stamp)";
        if ("1".equals(cfg.getProperty("AUTO_SHUTDOWN_DRY_RUN", "0"))) {
            Log.i(TAG, "dry run -> would shut down");
            ShellUtils.appendLog("ShutdownAlarm: DRY RUN - would power off (idle " + idleText + ")");
            scheduleAlarm(context, timeoutMs);
            return;
        }
        Log.i(TAG, "firing shutdown");
        ShellUtils.appendLog("ShutdownAlarm: firing shutdown (idle " + idleText + ")");
        cancelAlarm(context);
        // Flush disk and power off (E-ink retains the last image at 0 mA).
        ShellUtils.execRoot("sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null");
        ShellUtils.execRoot("/system/bin/reboot -p || setprop sys.powerctl shutdown || svc power shutdown");
    }

    private static long timeoutMs(Properties cfg) {
        long mins = 120;
        try { mins = Long.parseLong(cfg.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120")); } catch (Exception ignored) {}
        if (mins <= 0) mins = 120;
        return mins * 60 * 1000L;
    }

    /** true while a charger is attached or the battery reports full. No root, no shell. */
    private static boolean isCharging(Context context) {
        try {
            Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky == null) return false;
            int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isInteractive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            // Unknown => assume the user is present (fail safe).
            return true;
        }
    }

    /** Elapsed time since the last screen-off, or -1 when we have no stamp. */
    private static long idleSinceScreenOff(Context context) {
        try {
            long at = prefs(context).getLong(KEY_SCREEN_OFF_AT, -1L);
            if (at <= 0) return -1;
            long idle = SystemClock.elapsedRealtime() - at;
            return idle < 0 ? -1 : idle;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Called by ScreenReceiver on ACTION_SCREEN_OFF (monotonic clock, survives doze). */
    public static void markScreenOff(Context context) {
        try {
            prefs(context).edit().putLong(KEY_SCREEN_OFF_AT, SystemClock.elapsedRealtime()).apply();
        } catch (Throwable ignored) {}
    }

    /** Called by ScreenReceiver on ACTION_SCREEN_ON. */
    public static void clearScreenOff(Context context) {
        try {
            prefs(context).edit().remove(KEY_SCREEN_OFF_AT).apply();
        } catch (Throwable ignored) {}
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE);
    }

    public static void scheduleAlarm(Context context, long delayMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, ShutdownAlarmReceiver.class);
        intent.setAction(ACTION_SHUTDOWN_TIMER);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (SecurityException e) {
            // Fallback to inexact alarm if exact alarm permission not granted
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pi);
            ShellUtils.appendLog("ShutdownAlarm: fell back to inexact alarm");
        }
    }

    public static void scheduleAlarmWithConfig(Context context) {
        Properties cfg = ConfigManager.loadConfig();
        if (!"1".equals(cfg.getProperty("AUTO_SHUTDOWN_ENABLED"))) return;
        long mins = 120;
        try { mins = Long.parseLong(cfg.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120")); } catch (Exception ignored) {}
        scheduleAlarm(context, mins * 60 * 1000L);
    }

    public static void cancelAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, ShutdownAlarmReceiver.class);
        intent.setAction(ACTION_SHUTDOWN_TIMER);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
    }
}
