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
                    applyAllRules();
                    installAutoShutdown(context);
                    writeBootTime();
                } finally {
                    result.finish();
                }
            }
        }).start();
    }

    public void applyAllRulesPublic(android.content.Context ctx) { installAutoShutdown(ctx); applyAllRules(); writeBootTime(); }

    private void applyAllRules() {
        ShellUtils.appendLog("=== BootReceiver: applying all rules ===");
        Properties cfg = ConfigManager.loadConfig();

        // 1. Fix UART - kill crash loop & clear early-boot init latch
        if ("1".equals(cfg.getProperty("FIX_UART"))) {
            ShellUtils.execRoot(
                "setprop sys.init.updatable_crashing \"\" 2>/dev/null; " +
                "setprop sys.init.updatable_crashing_process_name \"\" 2>/dev/null; " +
                "setprop ctl.stop uart2serport 2>/dev/null; " +
                "setprop persist.vendor.uart2serport.enable 0 2>/dev/null; " +
                "kill -9 $(pidof uart2serport) 2>/dev/null; " +
                "echo '#!/system/bin/sh\\nexit 0' > /system/bin/start_uart2serport.sh 2>/dev/null; " +
                "chmod 755 /system/bin/start_uart2serport.sh 2>/dev/null");
        }
        // 2. Google Stack
        if ("0".equals(cfg.getProperty("GOOGLE_STACK"))) {
            ShellUtils.execRoot(ConfigManager.buildPmCmd(ConfigManager.GOOGLE_PKGS, true));
        } else {
            ShellUtils.execRoot(ConfigManager.buildPmCmd(ConfigManager.GOOGLE_PKGS, false));
        }
        // 3. Bigme Bloat
        if ("0".equals(cfg.getProperty("BIGME_BLOAT"))) {
            ShellUtils.execRoot(ConfigManager.buildPmCmd(ConfigManager.BIGME_PKGS, true));
        } else {
            ShellUtils.execRoot(ConfigManager.buildPmCmd(ConfigManager.BIGME_PKGS, false));
        }
        // 4. MTK Cellular
        if ("0".equals(cfg.getProperty("MTK_CELLULAR"))) {
            ShellUtils.execRoot(ConfigManager.buildPmCmd(ConfigManager.MTK_PKGS, true));
        } else {
            ShellUtils.execRoot(ConfigManager.buildPmCmd(ConfigManager.MTK_PKGS, false));
        }
        // 5. AOSP Stubs
        if ("0".equals(cfg.getProperty("AOSP_STUBS"))) {
            ShellUtils.execRoot(ConfigManager.buildPmCmd(ConfigManager.AOSP_PKGS, true));
        } else {
            ShellUtils.execRoot(ConfigManager.buildPmCmd(ConfigManager.AOSP_PKGS, false));
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
        // 7. Aggressive Doze
        if ("1".equals(cfg.getProperty("AGGRESSIVE_DOZE"))) {
            ShellUtils.execRoot("dumpsys deviceidle enable 2>/dev/null; " +
                "device_config put device_idle quick_doze_delay_to 5000 2>/dev/null; " +
                "device_config put device_idle inactive_to 5000 2>/dev/null; " +
                "device_config put device_idle sensing_to 0 2>/dev/null; " +
                "device_config put device_idle locating_to 0 2>/dev/null; " +
                "device_config put device_idle motion_inactive_to 0 2>/dev/null; " +
                "device_config put device_idle idle_to 86400000 2>/dev/null; " +
                "device_config put device_idle max_idle_to 86400000 2>/dev/null; " +
                "device_config put device_idle min_time_to_alarm 3600000 2>/dev/null");
        }
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

        // 11. WiFi Sleep Zero
        if ("1".equals(cfg.getProperty("WIFI_SLEEP_ZERO"))) {
            ShellUtils.execRoot("settings put global wifi_sleep_policy 0 2>/dev/null; " +
                "settings put global wifi_idle_ms 5000 2>/dev/null; " +
                "settings put global wifi_scan_always_enabled 0 2>/dev/null; " +
                "cmd wifi set-scan-always-available 0 2>/dev/null");
        }
        // 11. Battery Cap
        if ("1".equals(cfg.getProperty("BATTERY_CAP_85"))) {
            ShellUtils.execRoot("echo 85 > /sys/class/power_supply/battery/charging_limit 2>/dev/null");
        }
        // 12. Animations
        if ("1".equals(cfg.getProperty("DISABLE_ANIMATIONS"))) {
            ShellUtils.execRoot("settings put global window_animation_scale 0.0 2>/dev/null; " +
                "settings put global transition_animation_scale 0.0 2>/dev/null; " +
                "settings put global animator_duration_scale 0.0 2>/dev/null; " +
                "settings put global disable_window_blurs 1 2>/dev/null; " +
                "setprop persist.sys.sf.disable_blurs 1 2>/dev/null");
        }
        // Auto-shutdown daemon
        if ("1".equals(cfg.getProperty("AUTO_SHUTDOWN_ENABLED"))) {
            String timeout = cfg.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120");
            ShellUtils.execRoot("[ -f /data/local/tmp/autoshutdown.pid ] && kill -9 $(cat /data/local/tmp/autoshutdown.pid 2>/dev/null) 2>/dev/null; rm -f /data/local/tmp/autoshutdown.pid; " +
                "nohup sh " + ConfigManager.SHUTDOWN_SCRIPT + " " + timeout +
                " > /data/local/tmp/autoshutdown.log 2>&1 &");
        }
        // 13. Reapply user restricted packages (AppOps & Standby Buckets)
        java.util.Set<String> restricted = ConfigManager.loadRestrictedPkgs();
        for (String rPkg : restricted) {
            ShellUtils.execRoot(ConfigManager.buildRestrictCmd(rPkg));
        }
        ShellUtils.appendLog("=== BootReceiver: all rules applied ===");
    }

    private void installAutoShutdown(Context context) {
        try {
            java.io.InputStream is = context.getAssets().open("auto_shutdown.sh");
            java.io.File internalFile = new java.io.File(context.getFilesDir(), "auto_shutdown.sh");
            if (internalFile.getParentFile() != null && !internalFile.getParentFile().exists()) {
                internalFile.getParentFile().mkdirs();
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(internalFile);
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            is.close();
            fos.close();
            ShellUtils.execRoot("cp " + internalFile.getAbsolutePath() + " " + ConfigManager.SHUTDOWN_SCRIPT +
                " && chmod 755 " + ConfigManager.SHUTDOWN_SCRIPT);
        } catch (Exception e) {
            ShellUtils.appendLog("installAutoShutdown error: " + e.getMessage());
        }
    }

    private void writeBootTime() {
        ShellUtils.exec("date '+%Y-%m-%d %H:%M' > " + ConfigManager.BOOT_TS_PATH);
    }
}
