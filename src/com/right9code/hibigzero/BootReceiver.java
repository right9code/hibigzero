package com.right9code.hibigzero;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Properties;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final BroadcastReceiver.PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Boot-loop guard. Records this boot before anything is applied,
                    // and after MAX_FAST_BOOTS consecutive fast boots stops applying
                    // rules entirely - including self-protection, because "suspended"
                    // has to mean the app touches nothing. The app re-asserts its own
                    // appops on launch anyway, so it stays reachable to fix things.
                    if (!ConfigManager.shouldApplyBootRules()) {
                        ShellUtils.appendLog("=== BootReceiver: " + ConfigManager.getBootStreak()
                            + " fast boots in a row - rules SUSPENDED, nothing applied ===");
                        ShellUtils.appendLog("Open HiBig Zero and tap RESUME RULES to apply again");
                    } else {
                        applyAllRules(context);
                    }
                    writeBootTime();
                } finally {
                    // Boot is a short-lived receiver process: flush before returning.
                    ShellUtils.flushLog();
                    result.finish();
                }
            }
        }).start();
    }

    /**
     * Manual entry point for "APPLY ALL RULES". A manual apply is an explicit
     * instruction from the user, so it also clears the boot-loop suspension and
     * starts a fresh streak - which is why it does not go through
     * {@link ConfigManager#shouldApplyBootRules()}: only a real boot counts as a
     * boot.
     */
    public void applyAllRulesPublic(android.content.Context ctx) {
        ConfigManager.resetBootStreak();
        ConfigManager.setRulesSuspended(false);
        applyAllRules(ctx);
        writeBootTime();
    }

    /**
     * The package categories, in application order. These were four copy-pasted
     * blocks, and the OFF branch carries a guard that has to be identical in all
     * four - which is exactly the duplication that already produced two bugs here
     * (freeze-by-default, and OFF re-enabling a whole category). One table, one
     * implementation, so the guard cannot drift apart.
     */
    private static final PkgCategory[] PKG_CATEGORIES = {
        // { config key, package list, per-package selection key }
        new PkgCategory("GOOGLE_STACK", ConfigManager.GOOGLE_PKGS, "GOOGLE_PKGS_SEL"),
        new PkgCategory("BIGME_BLOAT",  ConfigManager.BIGME_PKGS,  "BIGME_PKGS_SEL"),
        new PkgCategory("MTK_CELLULAR", ConfigManager.MTK_PKGS,    "MTK_PKGS_SEL"),
        new PkgCategory("AOSP_STUBS",   ConfigManager.AOSP_PKGS,   "AOSP_PKGS_SEL"),
    };

    /**
     * Rules that are exactly "<key>=1 -> this command", in application order. The
     * ConfigManager builders below are pure string builders, so the table can hold
     * their output directly.
     */
    private static final OnRule[] ON_RULES = {
        new OnRule("KILL_SHUTDOWN_ALARM", ConfigManager.getKillShutdownAlarmCmd(true)),
        new OnRule("SUPPRESS_JS_IDLE",    ConfigManager.getSuppressJsIdleCmd(true)),
        new OnRule("WIDE_ALARM_FUZZ",     ConfigManager.getWideAlarmFuzzCmd(true)),
        new OnRule("INSTANT_LOCK",        ConfigManager.getInstantLockCmd(true)),
    };

    private static final class PkgCategory {
        final String key, list, selKey;
        PkgCategory(String key, String list, String selKey) {
            this.key = key; this.list = list; this.selKey = selKey;
        }

        /**
         * Key "0" freezes the selected set; anything else unfreezes exactly that
         * set. OFF must undo exactly what ON did - the selected set - rather than
         * re-enabling the whole category, which could undo freezes applied by other
         * means.
         *
         * Only act when a selection was actually saved: an empty *_PKGS_SEL means
         * "every package in the category", so without this guard an OFF category
         * (the default) would re-enable ~120 packages on every boot, undoing
         * deliberate vendor freezes and burning four root spawns for nothing.
         */
        void apply(Properties cfg) {
            if ("0".equals(cfg.getProperty(key))) {
                String cmd = ConfigManager.buildSelectedPmCmd(cfg, list, selKey, true);
                if (!cmd.isEmpty()) ShellUtils.execRootAction(cmd);
            } else if (!cfg.getProperty(selKey, "").trim().isEmpty()) {
                String cmd = ConfigManager.buildSelectedPmCmd(cfg, list, selKey, false);
                if (!cmd.isEmpty()) ShellUtils.execRootAction(cmd);
            }
        }
    }

    private static final class OnRule {
        final String key, cmd;
        OnRule(String key, String cmd) { this.key = key; this.cmd = cmd; }
    }

    private void applyAllRules(android.content.Context context) {
        ShellUtils.appendLog("=== BootReceiver: applying all rules ===");
        Properties cfg = ConfigManager.loadConfig();

        // 0. Self-protection — the manager must never be background-restricted by
        //    its own rules, or Android reaps it minutes after screen-off and every
        //    rule applied below loses its owner. Idempotent, single root spawn.
        ShellUtils.execRootAction(ConfigManager.buildSelfCheckAndFixCmd());

        // 1. Fix UART - passive monitor with fallback
        if ("1".equals(cfg.getProperty("FIX_UART"))) {
            String svcStatus = ShellUtils.execRoot("getprop init.svc.uart2serport").stdout.trim();
            if ("running".equals(svcStatus)) {
                ShellUtils.appendLog("uart2serport already running (Magisk module active)");
            } else {
                ShellUtils.appendLog("uart2serport status: " + svcStatus + " — applying fallback");
                // Inject SELinux rule so the sleep stub can execute
                ShellUtils.execRootAction(
                    "magiskpolicy --live \"allow uart2serport toolbox_exec file { read open getattr execute execute_no_trans map }\" 2>/dev/null");
                // Bind-mount sleep stub over system script
                ShellUtils.execRootAction(
                    "mkdir -p /data/local/tmp/uart_fix && " +
                    "echo '#!/system/bin/sh' > /data/local/tmp/uart_fix/stub.sh && " +
                    "echo 'exec sleep 2147483647' >> /data/local/tmp/uart_fix/stub.sh && " +
                    "chmod 755 /data/local/tmp/uart_fix/stub.sh && " +
                    "chcon u:object_r:system_file:s0 /data/local/tmp/uart_fix/stub.sh && " +
                    "mount -o bind /data/local/tmp/uart_fix/stub.sh /system/bin/start_uart2serport.sh 2>/dev/null");
                // Restart service into running state
                ShellUtils.execRootAction("setprop ctl.restart uart2serport");
            }
        }
        // 2-5. Package categories (respect per-package selection)
        for (PkgCategory cat : PKG_CATEGORIES) cat.apply(cfg);
        // 6. GBoard Lockdown
        if ("1".equals(cfg.getProperty("LOCKDOWN_GBOARD"))) {
            ShellUtils.execRootAction(
                "cmd appops set com.google.android.inputmethod.latin RUN_IN_BACKGROUND ignore 2>/dev/null; " +
                "cmd appops set com.google.android.inputmethod.latin RUN_ANY_IN_BACKGROUND ignore 2>/dev/null; " +
                "cmd appops set com.google.android.inputmethod.latin START_FOREGROUND ignore 2>/dev/null; " +
                "cmd appops set com.google.android.inputmethod.latin WAKE_LOCK ignore 2>/dev/null; " +
                "cmd jobscheduler cancel com.google.android.inputmethod.latin 2>/dev/null; " +
                "am set-standby-bucket com.google.android.inputmethod.latin rare 2>/dev/null; " +
                "dumpsys deviceidle whitelist -com.google.android.inputmethod.latin 2>/dev/null");
        }
        // 7. Doze: ON shortens the entry times, OFF restores normal doze. Never
        //    leave doze disabled — the old OFF branch did exactly that and the
        //    device ended up with 0 min of deep idle while `idle_to` said 24 h.
        ShellUtils.execRootAction(ConfigManager.getAggressiveDozeCmd(
            "1".equals(cfg.getProperty("AGGRESSIVE_DOZE"))));
        // 8. Suppress GMS alarms. The old command set ALARM_WAKEUP, which does
        //    not exist on API 34, so this was a silent no-op every boot.
        if ("1".equals(cfg.getProperty("SUPPRESS_ALARMS"))) {
            ShellUtils.execRootAction(ConfigManager.getSuppressGmsAlarmsCmd(true));
        }
        // 9. Kernel & Sensor Settings
        ShellUtils.execRootAction(ConfigManager.buildAllSensorsApplyCmd(cfg));
        // 10. Governor Profile & PPM Uncap
        String govProfile = cfg.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");
        String hotplug4   = cfg.getProperty("HOTPLUG_4_CORES", "0");
        ShellUtils.execRootAction(ConfigManager.buildGovernorCmd(govProfile, hotplug4));
        // The profile just applied IS the configured one, so any screen-off clamp
        // from before the reboot is gone. Clear its marker, or it would claim a
        // clamp is in effect when it is not - the boot pass re-applies the wake
        // profile but does not go through the screen-on path that clears it.
        GovernorReconciler.clearActiveGovMarker();

        // 10b. CPU uncap. Own key now (it used to overwrite GOVERNOR_PROFILE with
        //      "1"). Applied after the profile so it clears any stale PPM clamp.
        if ("1".equals(cfg.getProperty("CPU_OPTIMIZER"))) {
            ShellUtils.execRootAction(ConfigManager.getCpuUncapCmd());
        }

        // 11. Sleep Governor Service (screen-off CPU frequency scaling)
        if ("1".equals(cfg.getProperty("SLEEP_GOVERNOR_ENABLED", "1"))) {
            HiBigApp.registerScreenReceiver();
        }

        // 12. WiFi Sleep Zero
        if ("1".equals(cfg.getProperty("WIFI_SLEEP_ZERO"))) {
            ShellUtils.execRootAction("settings put global wifi_sleep_policy 0 2>/dev/null; " +
                "settings put global wifi_idle_ms 5000 2>/dev/null; " +
                "settings put global wifi_scan_always_enabled 0 2>/dev/null; " +
                "cmd wifi set-scan-always-available 0 2>/dev/null");
        }
        // 13. Charge ceiling. The kernel resets the MTK charge switch at boot, so a
        //     stuck "charging off" cannot survive a reboot. This re-arms the poll
        //     when the ceiling is enabled and the charger is attached, and makes
        //     sure charging is allowed when it is not.
        ChargeLimitController.applyConfig(context);
        // 14. Animations
        if ("1".equals(cfg.getProperty("DISABLE_ANIMATIONS"))) {
            ShellUtils.execRootAction("settings put global window_animation_scale 0.0 2>/dev/null; " +
                "settings put global transition_animation_scale 0.0 2>/dev/null; " +
                "settings put global animator_duration_scale 0.0 2>/dev/null; " +
                "settings put global disable_window_blurs 1 2>/dev/null; " +
                "setprop persist.sys.sf.disable_blurs 1 2>/dev/null");
        }
        // 15. Auto-shutdown alarm (zero-drain, AlarmManager-based)
        if ("1".equals(cfg.getProperty("AUTO_SHUTDOWN_ENABLED"))) {
            // Our timer is the single authority for power-off, so Bigme's own
            // PowersaveShutDownAlarmReceiver is re-disabled on every boot - that way
            // an OTA or firmware reset that restores it gets corrected here.
            ShellUtils.execRootAction(ConfigManager.getKillShutdownAlarmCmd(true));
            ShutdownAlarmReceiver.scheduleAlarmWithConfig(context);
            ShellUtils.appendLog("auto-shutdown armed at boot (Bigme timer disabled)");
        } else {
            ShutdownAlarmReceiver.cancelAlarm(context);
        }
        // 16. Suspend failure reduction (Optimization #3)
        for (OnRule rule : ON_RULES) {
            if ("1".equals(cfg.getProperty(rule.key))) ShellUtils.execRootAction(rule.cmd);
        }
        // 17. Reapply user restricted packages (AppOps & Standby Buckets)
        java.util.Set<String> restricted = ConfigManager.loadRestrictedPkgs();
        for (String rPkg : restricted) {
            ShellUtils.execRootAction(ConfigManager.buildRestrictCmd(rPkg));
        }
        ShellUtils.appendLog("=== BootReceiver: all rules applied ===");
    }

    private void writeBootTime() {
        ShellUtils.execRoot("date '+%Y-%m-%d %H:%M' > " + ConfigManager.BOOT_TS_PATH);
    }
}
