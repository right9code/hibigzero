package com.right9code.hibigzero;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;

/**
 * Holds the battery at or below a target percentage by switching the MediaTek
 * charge input off and on. Roughly what ACC and the Magisk charge-limiter modules
 * do - they do not add kernel support, they flip an existing switch and poll.
 *
 * Safety comes first, because a switch left in the "off" position would mean a
 * phone that never charges again:
 *
 *  - Resume is immediate and is NEVER rate-limited. Only the stop direction waits
 *    for the minimum toggle interval and a confirming second reading.
 *  - Charging is always resumed on unplug, before any evaluation on plug-in, when
 *    the feature is turned off, and on every boot (the kernel resets the node at
 *    boot anyway, so a reboot also clears a stuck state).
 *  - Nothing polls while unplugged. The power broadcast arms it; there is no timer
 *    and no daemon running when the phone is on battery.
 *  - While plugged in the device cannot enter Doze (idle requires "not charging"),
 *    so an ordinary inexact alarm is delivered close to on time.
 */
public class ChargeLimitController {

    public static final String ACTION_TICK = "com.right9code.hibigzero.CHARGE_LIMIT_TICK";

    private static final String TAG = "ChargeLimit";
    private static final long POLL_MS = 60_000L;
    /** The charger IC dislikes chattering, so never stop twice inside this window. */
    private static final long MIN_TOGGLE_MS = 30_000L;
    /** A level reading must repeat this many polls before charging is stopped.
     *  The fuel gauge was observed reporting a bogus 50% mid-cycle on this device. */
    private static final int CONFIRM_POLLS = 2;

    /** Whether we believe charging is currently held off. The switch node is the
     *  real source of truth, but reading it costs a root shell, so this is updated
     *  whenever we change the switch ourselves. */
    private static boolean holding = false;
    private static long lastToggleAt = 0L;
    private static int stopStreak = 0;
    private static String lastAction = "idle";
    private static long lastRunAt = 0L;

    // ── Config cache (refreshed on every event, not on every poll) ─────────
    private static boolean enabled = false;
    private static int target = 80;
    private static int resumeAt = 75;

    private static void refreshConfig(Context ctx) {
        java.util.Properties cfg = ConfigManager.loadConfig();
        enabled = "1".equals(cfg.getProperty("BATTERY_CAP_85", "0"));
        target = ConfigManager.getChargeLimitPct();
        resumeAt = ConfigManager.getChargeResumePct();
    }

    public static boolean isEnabled() { return enabled; }
    public static int getTarget() { return target; }
    public static int getResume() { return resumeAt; }
    public static boolean isHolding() { return holding; }
    public static String getLastAction() { return lastAction; }
    public static long getLastRunAt() { return lastRunAt; }

    // ── Battery state, no root needed ─────────────────────────────────────
    private static Intent batteryIntent(Context ctx) {
        return ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /** Current percentage, or -1 when the framework cannot tell us. */
    public static int batteryLevel(Context ctx) {
        Intent i = batteryIntent(ctx);
        if (i == null) return -1;
        int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (lvl < 0 || scale <= 0) return -1;
        return Math.round(lvl * 100f / scale);
    }

    public static boolean isPlugged(Context ctx) {
        Intent i = batteryIntent(ctx);
        return i != null && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    // ── Entry points ──────────────────────────────────────────────────────

    /** Plugged or unplugged. Registered in the manifest, so this also wakes the
     *  app when the charger is attached to a killed process. */
    public static void onPowerEvent(Context ctx, boolean plugged) {
        final Context app = ctx.getApplicationContext();
        refreshConfig(app);
        if (!plugged) {
            // Charging must work again the moment the cable comes out.
            holding = false;
            stopStreak = 0;
            cancelTick(app);
            note("unplugged - charging restored");
            ShellUtils.appendLog("Charge limit: unplugged, charging restored");
            Log.i(TAG, "unplugged: resuming charging, polling stopped");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ShellUtils.execRoot(ConfigManager.getChargeResumeCmd());
                }
            }).start();
            return;
        }
        // Plugged in: allow charging first, then decide. In this order a replug can
        // never be silently refused because the switch was still off.
        holding = false;
        stopStreak = 0;
        Log.i(TAG, "plugged in: charging allowed, target " + target);
        new Thread(new Runnable() {
            @Override
            public void run() {
                ShellUtils.execRoot(ConfigManager.getChargeResumeCmd());
                evaluateAndReschedule(app);
            }
        }).start();
    }

    /** The periodic poll. Only ever scheduled while plugged in and enabled. */
    public static void onTick(Context ctx) {
        final Context app = ctx.getApplicationContext();
        refreshConfig(app);
        if (!enabled || !isPlugged(app)) {
            cancelTick(app);
            Log.i(TAG, "tick: stopping poll (enabled=" + enabled + ")");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                evaluateAndReschedule(app);
            }
        }).start();
    }

    /** Called when the user flips the switch or changes the target, and at boot. */
    public static void applyConfig(Context ctx) {
        final Context app = ctx.getApplicationContext();
        refreshConfig(app);
        if (!enabled) {
            cancelTick(app);
            holding = false;
            note("disabled - charging restored");
            ShellUtils.appendLog("Charge limit: switched off, charging restored");
            Log.i(TAG, "applyConfig: disabled, charging restored");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ShellUtils.execRoot(ConfigManager.getChargeResumeCmd());
                }
            }).start();
            return;
        }
        if (!isPlugged(app)) {
            cancelTick(app);
            note("enabled - waiting for charger");
            ShellUtils.appendLog("Charge limit: armed, waiting for a charger");
            Log.i(TAG, "applyConfig: enabled, unplugged, waiting for charger");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                evaluateAndReschedule(app);
            }
        }).start();
    }

    // ── The decision ─────────────────────────────────────────────────────

    private static void evaluateAndReschedule(Context app) {
        lastRunAt = System.currentTimeMillis();
        int level = batteryLevel(app);
        if (level < 0) {
            note("level unreadable - leaving charging alone");
            Log.w(TAG, "evaluate: battery level unreadable");
            scheduleTick(app);
            return;
        }

        if (holding) {
            // Resume on the first qualifying reading: this direction is the safety
            // net, so it is neither confirmed nor rate-limited.
            if (level <= resumeAt) {
                ShellUtils.execRoot(ConfigManager.getChargeResumeCmd());
                holding = false;
                stopStreak = 0;
                note("resumed at " + level + "%");
                ShellUtils.appendLog("Charge limit: resumed charging at " + level + "%");
                Log.i(TAG, "resumed charging at " + level + "% (resume " + resumeAt + ")");
            } else {
                note("holding at " + level + "%");
            }
        } else if (level >= target) {
            stopStreak++;
            if (stopStreak < CONFIRM_POLLS) {
                note("confirming " + level + "% before stopping");
                Log.i(TAG, "level " + level + "% >= target " + target + ", confirming");
            } else if (System.currentTimeMillis() - lastToggleAt < MIN_TOGGLE_MS) {
                note("waiting out toggle interval at " + level + "%");
            } else {
                ShellUtils.execRoot(ConfigManager.getChargeStopCmd());
                holding = true;
                lastToggleAt = System.currentTimeMillis();
                stopStreak = 0;
                note("holding at " + level + "%");
                ShellUtils.appendLog("Charge limit: holding at " + level
                    + "% (target " + target + "%)");
                Log.i(TAG, "stopped charging at " + level + "% (target " + target + ")");
            }
        } else {
            stopStreak = 0;
            note("charging to " + target + "% (now " + level + "%)");
        }
        scheduleTick(app);
    }

    private static void note(String action) {
        lastAction = action;
    }

    /**
     * Reads the switch node itself rather than trusting the in-memory flag, so what
     * the UI shows is what the device is actually doing - including after a reboot
     * or when another process moved the switch.
     */
    private static boolean switchStopped() {
        for (String[] sw : ConfigManager.CHARGE_SWITCHES) {
            String out = ShellUtils.execRoot("cat " + sw[0] + " 2>/dev/null", false).stdout.trim();
            if (out.isEmpty()) continue;
            return out.equals(sw[1]);
        }
        return false;
    }

    /**
     * One-line plain-language state for the UI card.
     *
     * The "unplug briefly once" hint exists because a ceiling can only hold a level
     * it can reach: stopping the charger does not pull a full pack down on its own,
     * since the phone runs off the charger while charging is stopped. Without the
     * hint, sitting at 100% looks like the feature is broken.
     */
    public static String describe(Context ctx) {
        refreshConfig(ctx);
        int level = batteryLevel(ctx);
        String lvl = level < 0 ? "?" : level + "%";

        if (!isPlugged(ctx)) {
            return "ON BATTERY - charging restored, waiting for a charger";
        }
        boolean stopped;
        try {
            stopped = switchStopped();
        } catch (Throwable t) {
            stopped = false;
        }
        if (stopped) {
            if (level > target + 3) {
                return "HOLDING at " + lvl + " (target " + target
                    + "%) - unplug briefly once to settle into the band";
            }
            return "HOLDING at " + lvl + " (target " + target + "%, resume " + resumeAt + "%)";
        }
        return "CHARGING allowed - " + lvl + " up to " + target + "%";
    }

    // ── Alarm plumbing ───────────────────────────────────────────────────

    private static PendingIntent tickIntent(Context ctx) {
        Intent i = new Intent(ctx, PowerReceiver.class).setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, 1, i, flags);
    }

    private static void scheduleTick(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + POLL_MS, tickIntent(ctx));
        } catch (Exception e) {
            Log.e(TAG, "scheduleTick failed: " + e.getMessage());
        }
    }

    public static void cancelTick(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(tickIntent(ctx));
        } catch (Exception ignored) {}
    }
}