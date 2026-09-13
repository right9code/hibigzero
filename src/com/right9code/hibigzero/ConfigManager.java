package com.right9code.hibigzero;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public class ConfigManager {
    public static final String CONF_PATH          = "/data/local/tmp/hibreak.conf";
    public static final String BOOT_TS_PATH       = "/data/local/tmp/hibreak_last_boot.txt";
    public static final String SHUTDOWN_SCRIPT     = "/data/local/tmp/auto_shutdown.sh";
    public static final String RESTRICTED_PATH     = "/data/local/tmp/hibreak_restricted.txt";

    public static final String[] CONF_KEYS = {
        "FIX_UART","GOOGLE_STACK","BIGME_BLOAT","MTK_CELLULAR","AOSP_STUBS",
        "LOCKDOWN_GBOARD","AGGRESSIVE_DOZE","SUPPRESS_ALARMS","KERNEL_SENSOR",
        "BATTERY_CAP_85","GOVERNOR_PROFILE","HOTPLUG_4_CORES","WIFI_SLEEP_ZERO",
        "AUTO_SHUTDOWN_ENABLED","AUTO_SHUTDOWN_TIMEOUT_MIN","DISABLE_ANIMATIONS"
    };

    public static final String GOOGLE_PKGS =
        "com.google.android.gms com.android.vending com.google.android.gsf " +
        "com.google.android.configupdater com.google.android.apps.turbo " +
        "com.google.android.as com.google.android.as.oss " +
        "com.google.android.adservices.api com.google.mainline.adservices " +
        "com.google.android.federatedcompute " +
        "com.google.android.nearby.halfsheet " +
        "com.google.android.tts com.google.android.apps.photos";

    public static final String BIGME_PKGS =
        "com.xrz.ai com.example.test com.test.logcollect com.xrz.bigmecloud " +
        "com.xrz.hoverballdemo com.xrz.globalwritingservice com.xrz.appstore " +
        "com.xrz.bookmall com.xrz.ebook com.xrz.xreaderV3 com.xrz.music " +
        "com.xrz.video com.xrz.voice.text com.xrz.doc.translate com.xrz.ebook.launcher " +
        "com.xrz.soundrecord com.xrz.btranslate com.xrz.dictapp com.b300.xrz.web " +
        "com.xrz.mutidisplay com.xrz.res.service com.xrz.tts.service";

    public static final String MTK_PKGS =
        "com.mediatek.ims com.mediatek.simprocessor com.mediatek.telephony " +
        "com.mediatek.callrecorder com.mediatek.duraspeed com.mediatek.location.mtkgeofence " +
        "com.mediatek.voicecommand com.mediatek.omacp com.mediatek.smartratswitch.service " +
        "com.mediatek.gnss.nonframeworklbs com.mediatek.location.lppe.main " +
        "com.mediatek.miravision.ui com.mediatek.gbaservice com.mediatek.factorymode " +
        "com.mediatek.batterywarning com.mediatek.aovtestapp com.mediatek.voiceunlock";

    public static final String AOSP_PKGS =
        "com.android.phone com.android.server.telecom com.android.providers.telephony " +
        "com.android.mms com.android.printspooler com.android.bips com.android.quicksearchbox " +
        "com.android.providers.calendar";

    public static String buildPmCmd(String pkgList, boolean freeze) {
        String action = freeze ? "pm disable-user --user 0 " : "pm enable ";
        StringBuilder sb = new StringBuilder();
        for (String pkg : pkgList.trim().split("\\s+")) {
            if (!pkg.isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(action).append(pkg).append(" 2>/dev/null");
            }
        }
        return sb.toString();
    }

    public static String buildHotplugCmd(boolean offline4Cores) {
        if (offline4Cores) {
            return "echo 0 > /sys/devices/system/cpu/cpu4/online 2>/dev/null; " +
                   "echo 0 > /sys/devices/system/cpu/cpu5/online 2>/dev/null; " +
                   "echo 0 > /sys/devices/system/cpu/cpu6/online 2>/dev/null; " +
                   "echo 0 > /sys/devices/system/cpu/cpu7/online 2>/dev/null";
        } else {
            return "echo 1 > /sys/devices/system/cpu/cpu4/online 2>/dev/null; " +
                   "echo 1 > /sys/devices/system/cpu/cpu5/online 2>/dev/null; " +
                   "echo 1 > /sys/devices/system/cpu/cpu6/online 2>/dev/null; " +
                   "echo 1 > /sys/devices/system/cpu/cpu7/online 2>/dev/null";
        }
    }

    public static String buildGovernorCmd(String profile) {
        return buildGovernorCmd(profile, "0");
    }

    public static String buildGovernorCmd(String profile, String hotplug4Cores) {
        boolean offline = "ereader_battery".equals(profile) || "1".equals(hotplug4Cores);
        String hpCmd = buildHotplugCmd(offline) + "; ";

        if ("ereader_battery".equals(profile)) {
            return hpCmd +
                   "magiskpolicy --live \"allow magisk proc_ppm file { read write open getattr }\" 2>/dev/null; " +
                   "echo 7 0 > /proc/ppm/policy_status 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null; " +
                   "echo 900000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 1351000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 2>/dev/null; " +
                   "echo 745000 > /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
                   "echo 1244000 > /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
                   "echo 40000 > /sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us 2>/dev/null; " +
                   "echo 10000 > /sys/devices/system/cpu/cpufreq/schedutil/down_rate_limit_us 2>/dev/null";
        } else if ("stock".equals(profile)) {
            return hpCmd +
                   "echo 7 1 > /proc/ppm/policy_status 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 2200000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
                   "echo 1800000 > /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null";
        } else {
            // "schedutil_efficient" / "balanced" (Default)
            return hpCmd +
                   "magiskpolicy --live \"allow magisk proc_ppm file { read write open getattr }\" 2>/dev/null; " +
                   "echo 7 0 > /proc/ppm/policy_status 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null; " +
                   "echo 900000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 2200000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 2>/dev/null; " +
                   "echo 745000 > /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
                   "echo 1800000 > /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
                   "echo 20000 > /sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us 2>/dev/null; " +
                   "echo 10000 > /sys/devices/system/cpu/cpufreq/schedutil/down_rate_limit_us 2>/dev/null";
        }
    }

    public static String getGovernorLabel(String profile) {
        if ("ereader_battery".equals(profile)) return "E-READER BATTERY (1.35GHz MAX)";
        if ("stock".equals(profile))           return "STOCK (2.06GHz LOCKED)";
        return "BALANCED EFFICIENT (2.2GHz PEAK)";
    }

    public static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("FIX_UART", "1");
        p.setProperty("GOOGLE_STACK", "0");
        p.setProperty("BIGME_BLOAT", "0");
        p.setProperty("MTK_CELLULAR", "0");
        p.setProperty("AOSP_STUBS", "0");
        p.setProperty("LOCKDOWN_GBOARD", "1");
        p.setProperty("AGGRESSIVE_DOZE", "1");
        p.setProperty("SUPPRESS_ALARMS", "1");
        p.setProperty("KERNEL_SENSOR", "1");
        p.setProperty("BATTERY_CAP_85", "0");
        p.setProperty("GOVERNOR_PROFILE", "schedutil_efficient");
        p.setProperty("HOTPLUG_4_CORES", "0");
        p.setProperty("WIFI_SLEEP_ZERO", "1");
        p.setProperty("AUTO_SHUTDOWN_ENABLED", "1");
        p.setProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120");
        p.setProperty("DISABLE_ANIMATIONS", "1");
        return p;
    }

    public static Properties loadConfig() {
        Properties p = defaults();
        try {
            BufferedReader br = new BufferedReader(new FileReader(CONF_PATH));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf('=');
                if (idx > 0) {
                    p.setProperty(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
            br.close();
        } catch (Exception ignored) {}
        return p;
    }

    public static void saveConfig(Properties p) {
        try {
            StringBuilder sb = new StringBuilder("# HiBreak Manager Config v2.0\\n");
            for (String key : CONF_KEYS) {
                sb.append(key).append("=").append(p.getProperty(key, "")).append("\\n");
            }
            FileWriter fw = new FileWriter(CONF_PATH);
            fw.write(sb.toString());
            fw.close();
        } catch (Exception e) {
            ShellUtils.appendLog("saveConfig error: " + e.getMessage());
        }
    }

    public static String getLastBootTime() {
        try {
            BufferedReader br = new BufferedReader(new FileReader(BOOT_TS_PATH));
            String line = br.readLine();
            br.close();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static Set<String> loadRestrictedPkgs() {
        Set<String> pkgs = new HashSet<String>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(RESTRICTED_PATH));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    pkgs.add(line);
                }
            }
            br.close();
        } catch (Exception ignored) {}
        return pkgs;
    }

    public static void saveRestrictedPkgs(Set<String> pkgs) {
        try {
            StringBuilder sb = new StringBuilder("# HiBreak Manager Restricted Packages\\n");
            for (String pkg : pkgs) {
                sb.append(pkg).append("\\n");
            }
            FileWriter fw = new FileWriter(RESTRICTED_PATH);
            fw.write(sb.toString());
            fw.close();
        } catch (Exception e) {
            ShellUtils.appendLog("saveRestrictedPkgs error: " + e.getMessage());
        }
    }

    public static String buildRestrictCmd(String pkg) {
        return "am force-stop " + pkg + " 2>/dev/null; " +
            "cmd appops set " + pkg + " RUN_IN_BACKGROUND ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " WAKE_LOCK ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " ALARM_WAKEUP ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " SCHEDULE_EXACT_ALARM ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " BOOT_COMPLETED ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " RECEIVE_BOOT_COMPLETED ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " INTERNET ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " ACCESS_NETWORK_STATE ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " JOB ignore 2>/dev/null; " +
            "am set-standby-bucket " + pkg + " restricted 2>/dev/null; " +
            "dumpsys deviceidle whitelist -" + pkg + " 2>/dev/null; " +
            "settings put global hidden_api_policy_p_apps 1 2>/dev/null";
    }

    public static String buildUnrestrictCmd(String pkg) {
        return "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow 2>/dev/null; " +
            "cmd appops set " + pkg + " WAKE_LOCK allow 2>/dev/null; " +
            "cmd appops set " + pkg + " ALARM_WAKEUP allow 2>/dev/null; " +
            "cmd appops set " + pkg + " SCHEDULE_EXACT_ALARM allow 2>/dev/null; " +
            "cmd appops set " + pkg + " BOOT_COMPLETED allow 2>/dev/null; " +
            "cmd appops set " + pkg + " RECEIVE_BOOT_COMPLETED allow 2>/dev/null; " +
            "cmd appops set " + pkg + " INTERNET allow 2>/dev/null; " +
            "cmd appops set " + pkg + " ACCESS_NETWORK_STATE allow 2>/dev/null; " +
            "cmd appops set " + pkg + " JOB allow 2>/dev/null; " +
            "am set-standby-bucket " + pkg + " active 2>/dev/null";
    }

    public static String buildSetStandbyBucketCmd(String pkg, String bucket) {
        return "am set-standby-bucket " + pkg + " " + bucket + " 2>/dev/null";
    }

    public static String buildDozeWhitelistCmd(String pkg, boolean exempt) {
        return "dumpsys deviceidle whitelist " + (exempt ? "+" : "-") + pkg + " 2>/dev/null";
    }

    public static String getBucketLabel(int bucket) {
        switch (bucket) {
            case 5:  return "EXEMPT";
            case 10: return "ACTIVE";
            case 20: return "WORKING";
            case 30: return "FREQUENT";
            case 40: return "RARE";
            case 45: return "RESTRICTED";
            case 50: return "NEVER";
            default: return "B:" + bucket;
        }
    }
}
