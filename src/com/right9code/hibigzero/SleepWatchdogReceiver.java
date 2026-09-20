package com.right9code.hibigzero;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Armed at screen-off, and the reason the clamp cannot outlive the process.
 *
 * The receiver that applies the clamp dies with the process, so the repair needs
 * something that does not. An alarm is the cheapest such thing: a PendingIntent
 * lives in the system's alarm queue, survives the process being killed, and
 * restarts the process to deliver it. Only a force-stop cancels it, and a
 * force-stop also kills the app that would otherwise be left clamped.
 *
 * ELAPSED_REALTIME, deliberately, not ELAPSED_REALTIME_WAKEUP. A wakeup alarm
 * would hold the SoC out of suspend to ask a question whose answer only matters
 * once the device is awake anyway. An elapsed-realtime alarm never wakes the
 * device; it is delivered when the device next wakes, which is exactly the moment
 * the answer changes. So this costs no wakeups at all while the phone sleeps, and
 * a phone that was asleep for hours reports the clamp as soon as the power button
 * is pressed.
 *
 * While the screen is still off, each firing re-arms itself. That is a cheap poll,
 * not a busy loop: it is a non-wakeup alarm, and Android rate-limits exact alarms
 * during Doze, so in deep sleep it settles to the idle quota. The path that
 * matters - a clamp found while the screen is on - is handled on the first firing
 * after wake.
 */
public class SleepWatchdogReceiver extends BroadcastReceiver {

    public static final String ACTION_SLEEP_WATCHDOG = "com.right9code.hibigzero.SLEEP_WATCHDOG";

    /** Long enough that a brief screen-off does not re-arm the chain for nothing,
     *  short enough that a fast unlock is repaired almost immediately. */
    private static final long WATCHDOG_DELAY_MS = 90_000L;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_SLEEP_WATCHDOG.equals(intent.getAction())) return;
        final Context appCtx = context.getApplicationContext();
        final PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Repair first: if the screen is on and the marker is still set,
                    // this is the case the watchdog exists for. It also cancels us.
                    if (GovernorReconciler.reconcileIfInteractive(appCtx, "watchdog")) return;

                    // Still asleep with the clamp applied, so stay armed for the wake.
                    if (GovernorReconciler.hasActiveGovMarker()) {
                        scheduleWatchdog(appCtx);
                    } else {
                        // No clamp to watch - nothing to do until the next screen-off.
                        cancelWatchdog(appCtx);
                    }
                } catch (Throwable t) {
                    ShellUtils.appendLog("SleepWatchdog error: " + t);
                } finally {
                    // This process may be reaped the moment we return, so the log
                    // lines have to be on disk before that can happen.
                    ShellUtils.flushLog();
                    pendingResult.finish();
                }
            }
        }).start();
    }

    /**
     * One PendingIntent identity, re-armed in place. Re-arming must not create a
     * second alarm, so the request code, component and action are fixed and
     * FLAG_UPDATE_CURRENT replaces the existing entry. cancelWatchdog() then
     * matches it with FLAG_NO_CREATE.
     */
    private static PendingIntent watchdogIntent(Context context, boolean create) {
        Intent intent = new Intent(context, SleepWatchdogReceiver.class);
        intent.setAction(ACTION_SLEEP_WATCHDOG);
        int flags = PendingIntent.FLAG_IMMUTABLE
            | (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    public static void scheduleWatchdog(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = watchdogIntent(context, true);
            if (pi == null) return;
            long at = SystemClock.elapsedRealtime() + WATCHDOG_DELAY_MS;
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pi);
            } catch (SecurityException e) {
                // No exact-alarm permission: an inexact non-wakeup alarm still
                // repairs on the next wake, it just may do so later.
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pi);
                ShellUtils.appendLog("SleepWatchdog: exact alarm refused, using inexact");
            }
        } catch (Throwable t) {
            ShellUtils.appendLog("SleepWatchdog schedule error: " + t);
        }
    }

    public static void cancelWatchdog(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = watchdogIntent(context, false);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Throwable ignored) {}
    }
}