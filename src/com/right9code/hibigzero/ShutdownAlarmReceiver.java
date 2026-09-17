package com.right9code.hibigzero;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.util.Properties;

public class ShutdownAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_SHUTDOWN_TIMER = "com.right9code.hibigzero.SHUTDOWN_TIMER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ACTION_SHUTDOWN_TIMER.equals(intent.getAction())) {
                        // Alarm fired — verify device is truly idle before shutting down
                        String activity = ShellUtils.execRoot(
                            "dumpsys power 2>/dev/null | grep mLastUserActivityTime | sed 's/.*=//' | head -1",
                            false).stdout.trim();
                        long lastActivity = 0;
                        try { lastActivity = Long.parseLong(activity); } catch (Exception ignored) {}

                        long now = System.currentTimeMillis();
                        long elapsed = now - lastActivity;

                        Properties cfg = ConfigManager.loadConfig();
                        long timeoutMs = 120 * 60 * 1000L;
                        try {
                            timeoutMs = Long.parseLong(cfg.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120")) * 60 * 1000L;
                        } catch (Exception ignored) {}

                        if (lastActivity > 0 && elapsed < timeoutMs) {
                            // User became active since alarm was set — reschedule for remaining time
                            long remaining = timeoutMs - elapsed;
                            scheduleAlarm(context, remaining);
                            ShellUtils.appendLog("ShutdownAlarm: user still active, rescheduled " + (remaining / 60000) + "m");
                        } else {
                            // User has been inactive long enough — shut down
                            ShellUtils.appendLog("ShutdownAlarm: firing shutdown (inactive " + (elapsed / 60000) + "m)");
                            cancelAlarm(context);
                            // Flush disk and shut down
                            ShellUtils.execRoot("sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null");
                            ShellUtils.execRoot("/system/bin/reboot -p || setprop sys.powerctl shutdown || svc power shutdown");
                        }
                    }
                } catch (Exception e) {
                    ShellUtils.appendLog("ShutdownAlarm error: " + e.getMessage());
                } finally {
                    pendingResult.finish();
                }
            }
        }).start();
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
