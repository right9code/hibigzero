package com.right9code.hibigzero;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Properties;

public class ScreenReceiver extends BroadcastReceiver {
    /** Kept as an alias so existing callers keep compiling; the marker is owned by
     *  GovernorReconciler, which also holds the semantics for clearing it. */
    public static final String ACTIVE_GOV_FILE = GovernorReconciler.ACTIVE_GOV_FILE;

    public static void clearActiveGovMarker() {
        GovernorReconciler.clearActiveGovMarker();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        Log.i("ScreenReceiver", "onReceive: " + action);
        final PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Properties cfg = ConfigManager.loadConfig();
                    String enabled = cfg.getProperty("SLEEP_GOVERNOR_ENABLED", "1");
                    Log.i("ScreenReceiver", "SLEEP_GOVERNOR_ENABLED=" + enabled);
                    if (!"1".equals(enabled)) {
                        Log.i("ScreenReceiver", "Sleep governor disabled, skipping");
                        return;
                    }

                    String hotplug4 = cfg.getProperty("HOTPLUG_4_CORES", "0");
                    Log.i("ScreenReceiver", "HOTPLUG_4=" + hotplug4);

                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        String currentGov = cfg.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");
                        String sleepGov = cfg.getProperty("SLEEP_GOVERNOR", "deep_sleep");
                        Log.i("ScreenReceiver", "Screen OFF: currentGov=" + currentGov + " sleepGov=" + sleepGov);
                        GovernorReconciler.saveActiveGovMarker(currentGov);
                        // Allow PowerHAL display transition to complete before clamping PPM
                        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                        String cmd = ConfigManager.buildGovernorCmd(sleepGov, hotplug4);
                        Log.i("ScreenReceiver", "Executing: " + cmd.substring(0, Math.min(cmd.length(), 100)));
                        ShellUtils.execRootAction(cmd);
                        // Arm the repair before anything else can kill this process:
                        // from here on the clamp exists, and this alarm is what gets it
                        // lifted if this receiver never runs again.
                        SleepWatchdogReceiver.scheduleWatchdog(context);
                        Log.i("ScreenReceiver", "Sleep governor applied via PPM hard_userlimit");
                        // Force-stop Gboard on screen-off to kill WorkManager wakelocks
                        if ("1".equals(cfg.getProperty("LOCKDOWN_GBOARD"))) {
                            ShellUtils.execRootAction("am force-stop com.google.android.inputmethod.latin 2>/dev/null");
                            Log.i("ScreenReceiver", "Gboard force-stopped on screen-off");
                        }
                        // Schedule auto-shutdown alarm (zero-drain, AlarmManager-based)
                        // Monotonic stamp so the alarm can verify real idle time
                        // even after a process restart.
                        ShutdownAlarmReceiver.markScreenOff(context);
                        if ("1".equals(cfg.getProperty("AUTO_SHUTDOWN_ENABLED"))) {
                            ShutdownAlarmReceiver.scheduleAlarmWithConfig(context);
                            Log.i("ScreenReceiver", "Shutdown alarm scheduled");
                        }
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        // Cancel shutdown alarm — user is active
                        ShutdownAlarmReceiver.cancelAlarm(context);
                        ShutdownAlarmReceiver.clearScreenOff(context);
                        SleepWatchdogReceiver.cancelWatchdog(context);
                        Log.i("ScreenReceiver", "Shutdown alarm cancelled (screen on)");
                        String savedGov = GovernorReconciler.readActiveGovMarker();
                        if (savedGov == null || savedGov.trim().isEmpty()) {
                            savedGov = cfg.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");
                        }
                        Log.i("ScreenReceiver", "Screen ON: restoring gov=" + savedGov);
                        String cmd = ConfigManager.buildGovernorCmd(savedGov, hotplug4);
                        Log.i("ScreenReceiver", "Executing: " + cmd.substring(0, Math.min(cmd.length(), 100)));
                        ShellUtils.execRootAction(cmd);
                        GovernorReconciler.clearActiveGovMarker();
                        Log.i("ScreenReceiver", "Wake governor restored");
                    }
                } catch (Exception e) {
                    Log.e("ScreenReceiver", "Error: " + e.getMessage(), e);
                    ShellUtils.appendLog("ScreenReceiver error: " + e.getMessage());
                } finally {
                    // The process can be reaped the moment finish() returns, so force
                    // the queued log lines to disk before handing control back.
                    ShellUtils.flushLog();
                    pendingResult.finish();
                }
            }
        }).start();
    }

    }
