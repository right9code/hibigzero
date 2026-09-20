package com.right9code.hibigzero;

import android.content.Context;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Properties;

/**
 * Keeps the CPU clamp in step with the screen, and repairs it when the two drift
 * apart.
 *
 * The clamp is applied by ScreenReceiver, a dynamically registered receiver, so it
 * exists only while this process does. ACTION_SCREEN_ON/OFF carry
 * FLAG_RECEIVER_REGISTERED_ONLY, so a manifest receiver cannot take over that job.
 * When the process is killed while the screen is off, nothing is left to restore
 * the wake profile - and the device is left with the big cores offlined and the
 * little cores pinned at 400-900 MHz while the screen is on. That is a visibly
 * crippled phone, and it was reproduced on the device: kill the process while
 * asleep, press the power button, and cpu4 stays offline with scaling_max_freq
 * still 900000.
 *
 * So the clamp is never left to depend on one receiver's lifetime. A marker file
 * records "a clamp is applied", written just before the clamp and removed
 * whenever the clamp is lifted or the profile is re-applied. Every process start
 * calls reconcileIfInteractive(), and so does the non-wakeup watchdog armed at
 * screen-off. Both are cheap when there is nothing to do: the marker test is a
 * plain file stat with no root spawn.
 */
public final class GovernorReconciler {

    /**
     * The marker: holds the profile that was active when the clamp went on, so the
     * restore can put back what the user actually had rather than the default.
     * Single definition - ScreenReceiver and BootReceiver both go through here.
     */
    public static final String ACTIVE_GOV_FILE = "/data/local/tmp/hibreak_active_gov.txt";

    private GovernorReconciler() {}

    /**
     * Fails safe towards "the user is present": leaving the clamp on while the
     * screen is on is the bad outcome, so an unreadable PowerManager must lift it.
     */
    public static boolean isInteractive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            ShellUtils.appendLog("isInteractive check failed (" + t.getMessage() + ") - assuming present");
            return true;
        }
    }

    /** A plain file stat, so this is safe to call on every process start. */
    public static boolean hasActiveGovMarker() {
        try {
            File f = new File(ACTIVE_GOV_FILE);
            return f.exists() && f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Deletes the marker. Via root, not File.delete(): /data/local/tmp is 0771
     * owned by shell, so this app has no write permission on the directory and
     * unlinking there fails even for a file it owns.
     */
    public static void clearActiveGovMarker() {
        ShellUtils.execRoot("rm -f " + ACTIVE_GOV_FILE, false);
    }

    /** Records the profile to restore when the clamp is lifted. */
    public static void saveActiveGovMarker(String profile) {
        if (!ConfigManager.isValidGovernorProfile(profile)) {
            ShellUtils.appendLog("saveActiveGovMarker: refusing invalid profile: " + profile);
            return;
        }
        ShellUtils.execRoot("printf '%s' '" + profile + "' > " + ACTIVE_GOV_FILE +
            ConfigManager.secureFileTail(ACTIVE_GOV_FILE), false);
    }

    /** The recorded profile, or null when the marker is absent or unreadable. */
    public static String readActiveGovMarker() {
        File file = new File(ACTIVE_GOV_FILE);
        if (!file.exists()) return null;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * The repair. If the screen is on and the marker says a clamp is applied, put
     * the user's profile back and clear the marker. Returns true only when it
     * actually repaired something.
     *
     * Safe to call from any entry point: with no marker it does a file stat and
     * returns, so a normal launch costs no root shell. Synchronized so two entry
     * points cannot both start a restore and leave one marking the other's work.
     */
    public static synchronized boolean reconcileIfInteractive(Context context, String source) {
        try {
            if (!isInteractive(context)) return false;
            if (!hasActiveGovMarker()) return false;

            Properties cfg = ConfigManager.loadConfig();
            String hotplug4 = cfg.getProperty("HOTPLUG_4_CORES", "0");
            String restoreGov = readActiveGovMarker();
            if (!ConfigManager.isValidGovernorProfile(restoreGov)) {
                restoreGov = cfg.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");
            }

            ShellUtils.appendLog("Reconcile (" + source + "): screen is on but the CPU is still"
                + " clamped - restoring " + restoreGov);
            ShellUtils.execRootAction(ConfigManager.buildGovernorCmd(restoreGov, hotplug4));
            clearActiveGovMarker();
            // The clamp implies the screen went off, so the shutdown timer was armed
            // and the screen-off stamp set; neither is true any more.
            SleepWatchdogReceiver.cancelWatchdog(context);
            ShutdownAlarmReceiver.clearScreenOff(context);
            return true;
        } catch (Throwable t) {
            ShellUtils.appendLog("reconcile error from " + source + ": " + t);
            return false;
        }
    }
}