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
                    applyAllRules(context);
                    writeBootTime();
                } finally {
                    result.finish();
                }
            }
        }).start();
    }

    public void applyAllRulesPublic(android.content.Context ctx) { applyAllRules(ctx); writeBootTime(); }

    private void applyAllRules(android.content.Context context) {
        ShellUtils.appendLog("=== BootReceiver: applying all rules ===");
        Properties cfg = ConfigManager.loadConfig();

        // 0. Self-protection — the manager must never be background-restricted by
        //    its own rules, or Android reaps it minutes after screen-off and every
        //    rule applied below loses its owner. Idempotent, single root spawn.
        ShellUtils.execRoot(ConfigManager.buildSelfCheckAndFixCmd());

        // 1. Fix UART - passive monitor with fallback
        if ("1".equals(cfg.getProperty("FIX_UART"))) {
            String svcStatus = ShellUtils.execRoot("getprop init.svc.uart2serport").stdout.trim();
            if ("running".equals(svcStatus)) {
                ShellUtils.appendLog("uart2serport already running (Magisk module active)");
            } else {
                ShellUtils.appendLog("uart2serport status: " + svcStatus + " — applying fallback");
                // Inject SELinux rule so the sleep stub can execute
                ShellUtils.execRoot(
                    "magiskpolicy --live \"allow uart2serport toolbox_exec file { read open getattr execute execute_no_trans map }\" 2>/dev/null");
                // Bind-mount sleep stub over system script
                ShellUtils.execRoot(
                    "mkdir -p /data/local/tmp/uart_fix && " +
                    "echo '#!/system/bin/sh' > /data/local/tmp/uart_fix/stub.sh && " +
                    "echo 'exec sleep 2147483647' >> /data/local/tmp/uart_fix/stub.sh && " +
                    "chmod 755 /data/local/tmp/uart_fix/stub.sh && " +
                    "chcon u:object_r:system_file:s0 /data/local/tmp/uart_fix/stub.sh && " +
                    "mount -o bind /data/local/tmp/uart_fix/stub.sh /system/bin/start_uart2serport.sh 2>/dev/null");
                // Restart service into running state
                ShellUtils.execRoot("setprop ctl.restart uart2serport");
            }
        }
        // 2. Google Stack (respect per-package selection)
        if ("0".equals(cfg.getProperty("GOOGLE_STACK"))) {
            String cmd = ConfigManager.buildSelectedPmCmd(cfg, ConfigManager.GOOGLE_PKGS, "GOOGLE_PKGS_SEL", true);
            if (!cmd.isEmpty()) ShellUtils.execRoot(cmd);
        } else {
            // OFF must undo exactly what ON did - the selected set - rather than
            // re-enabling the whole category, which could undo freezes applied
            // by other means.
            //
            // Only act when a selection was actually saved: an empty *_PKGS_SEL
            // means "every package in the category", so without this guard an
            // OFF category (now the default) would re-enable ~120 packages on
            // every single boot, undoing deliberate vendor freezes and burning
            // four root spawns for nothing.
            if (!cfg.getProperty("GOOGLE_PKGS_SEL", "").trim().isEmpty()) {
                String cmd = ConfigManager.buildSelectedPmCmd(cfg, ConfigManager.GOOGLE_PKGS, "GOOGLE_PKGS_SEL", false);
                if (!cmd.isEmpty()) ShellUtils.execRoot(cmd);
            }
        }
        // 3. Bigme Bloat (respect per-package selection)
        if ("0".equals(cfg.getProperty("BIGME_BLOAT"))) {
            String cmd = ConfigManager.buildSelectedPmCmd(cfg, ConfigManager.BIGME_PKGS, "BIGME_PKGS_SEL", true);
            if (!cmd.isEmpty()) ShellUtils.execRoot(cmd);
        } else {
            // OFF must undo exactly what ON did - the selected set - rather than
            // re-enabling the whole category, which could undo freezes applied
            // by other means.
            //
            // Only act when a selection was actually saved: an empty *_PKGS_SEL
            // means "every package in the category", so without this guard an
            // OFF category (now the default) would re-enable ~120 packages on
            // every single boot, undoing deliberate vendor freezes and burning
            // four root spawns for nothing.
            if (!cfg.getProperty("BIGME_PKGS_SEL", "").trim().isEmpty()) {
                String cmd = ConfigManager.buildSelectedPmCmd(cfg, ConfigManager.BIGME_PKGS, "BIGME_PKGS_SEL", false);
                if (!cmd.isEmpty()) ShellUtils.execRoot(cmd);
            }
        }
        // 4. MTK Cellular (respect per-package selection)
        if ("0".equals(cfg.getProperty("MTK_CELLULAR"))) {
            String cmd = ConfigManager.buildSelectedPmCmd(cfg, ConfigManager.MTK_PKGS, "MTK_PKGS_SEL", true);
            if (!cmd.isEmpty()) ShellUtils.execRoot(cmd);
        } else {
            // OFF must undo exactly what ON did - the selected set - rather than
            // re-enabling the whole category, which could undo freezes applied
            // by other means.
            //
            // Only act when a selection was actually saved: an empty *_PKGS_SEL
            // means "every package in the category", so without this guard an
            // OFF category (now the default) would re-enable ~120 packages on
            // every single boot, undoing deliberate vendor freezes and burning
            // four root spawns for nothing.
            if (!cfg.getProperty("MTK_PKGS_SEL", "").trim().isEmpty()) {
                String cmd = ConfigManager.buildSelectedPmCmd(cfg, ConfigManager.MTK_PKGS, "MTK_PKGS_SEL", false);
                if (!cmd.isEmpty()) ShellUtils.execRoot(cmd);
            }
        }
        // 5. AOSP Stubs (respect per-package selection)
        if ("0".equals(cfg.getProperty("AOSP_STUBS"))) {
            String cmd = ConfigManager.buildSelectedPmCmd(cfg, ConfigManager.AOSP_PKGS, "AOSP_PKGS_SEL", true);
            if (!cmd.isEmpty()) ShellUtils.execRoot(cmd);
        } else {
            // OFF must undo exactly what ON did - the selected set - rather than
            // re-enabling the whole category, which could undo freezes applied
            // by other means.
            //
            // Only act when a selection was actually saved: an empty *_PKGS_SEL
            // means "every package in the category", so without this guard an
            // OFF category (now the default) would re-enable ~120 packages on
            // every single boot, undoing deliberate vendor freezes and burning
            // four root spawns for nothing.
            if (!cfg.getProperty("AOSP_PKGS_SEL", "").trim().isEmpty()) {
                String cmd = ConfigManager.buildSelectedPmCmd(cfg, ConfigManager.AOSP_PKGS, "AOSP_PKGS_SEL", false);
                if (!cmd.isEmpty()) ShellUtils.execRoot(cmd);
            }
        }
        // 6. GBoard Lockdown
        if ("1".equals(cfg.getProperty("LOCKDOWN_GBOARD"))) {
            ShellUtils.execRoot(
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
        ShellUtils.execRoot(ConfigManager.getAggressiveDozeCmd(
            "1".equals(cfg.getProperty("AGGRESSIVE_DOZE"))));
        // 8. Suppress Alarms
        if ("1".equals(cfg.getProperty("SUPPRESS_ALARMS"))) {
            ShellUtils.execRoot("cmd appops set com.google.android.gms ALARM_WAKEUP ignore 2>/dev/null");
        }
        // 9. Kernel & Sensor
        if ("1".equals(cfg.getProperty("KERNEL_SENSOR"))) {
            ShellUtils.execRoot("settings put system accelerometer_rotation 0 2>/dev/null; " +
                "settings put system user_rotation 0 2>/dev/null; " +
                "device_config put power face_down_detector_enabled false 2>/dev/null; " +
                "echo 0 > /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/disable_nafg 2>/dev/null; " +
                "echo 0 > /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/ntc_disable_nafg 2>/dev/null; " +
                "setprop vendor.powerhal.smart.powersave 1 2>/dev/null; " +
                "setprop persist.vendor.powerhal.mode 1 2>/dev/null");
        }
        // 10. Governor Profile & PPM Uncap
        String govProfile = cfg.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");
        String hotplug4   = cfg.getProperty("HOTPLUG_4_CORES", "0");
        ShellUtils.execRoot(ConfigManager.buildGovernorCmd(govProfile, hotplug4));

        // 11. Sleep Governor Service (screen-off CPU frequency scaling)
        if ("1".equals(cfg.getProperty("SLEEP_GOVERNOR_ENABLED", "1"))) {
            HiBigApp.registerScreenReceiver();
        }

        // 12. WiFi Sleep Zero
        if ("1".equals(cfg.getProperty("WIFI_SLEEP_ZERO"))) {
            ShellUtils.execRoot("settings put global wifi_sleep_policy 0 2>/dev/null; " +
                "settings put global wifi_idle_ms 5000 2>/dev/null; " +
                "settings put global wifi_scan_always_enabled 0 2>/dev/null; " +
                "cmd wifi set-scan-always-available 0 2>/dev/null");
        }
        // 13. Battery Cap 85%
        if ("1".equals(cfg.getProperty("BATTERY_CAP_85"))) {
            ShellUtils.execRoot("echo 85 > /sys/class/power_supply/battery/charging_limit 2>/dev/null");
        }
        // 14. Animations
        if ("1".equals(cfg.getProperty("DISABLE_ANIMATIONS"))) {
            ShellUtils.execRoot("settings put global window_animation_scale 0.0 2>/dev/null; " +
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
            ShellUtils.execRoot(ConfigManager.getKillShutdownAlarmCmd(true));
            ShutdownAlarmReceiver.scheduleAlarmWithConfig(context);
            ShellUtils.appendLog("auto-shutdown armed at boot (Bigme timer disabled)");
        } else {
            ShutdownAlarmReceiver.cancelAlarm(context);
        }
        // 16. Suspend failure reduction (Optimization #3)
        if ("1".equals(cfg.getProperty("KILL_SHUTDOWN_ALARM"))) {
            ShellUtils.execRoot(ConfigManager.getKillShutdownAlarmCmd(true));
        }
        if ("1".equals(cfg.getProperty("SUPPRESS_JS_IDLE"))) {
            ShellUtils.execRoot(ConfigManager.getSuppressJsIdleCmd(true));
        }
        if ("1".equals(cfg.getProperty("WIDE_ALARM_FUZZ"))) {
            ShellUtils.execRoot(ConfigManager.getWideAlarmFuzzCmd(true));
        }
        if ("1".equals(cfg.getProperty("INSTANT_LOCK"))) {
            ShellUtils.execRoot(ConfigManager.getInstantLockCmd(true));
        }
        // 17. Reapply user restricted packages (AppOps & Standby Buckets)
        java.util.Set<String> restricted = ConfigManager.loadRestrictedPkgs();
        for (String rPkg : restricted) {
            ShellUtils.execRoot(ConfigManager.buildRestrictCmd(rPkg));
        }
        ShellUtils.appendLog("=== BootReceiver: all rules applied ===");
    }

    private void writeBootTime() {
        ShellUtils.execRoot("date '+%Y-%m-%d %H:%M' > " + ConfigManager.BOOT_TS_PATH);
    }
}
