package com.right9code.hibigzero;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class ConfigManager {
    public static final String CONF_PATH          = "/data/local/tmp/hibreak.conf";
    public static final String BOOT_TS_PATH       = "/data/local/tmp/hibreak_last_boot.txt";
    public static final String RESTRICTED_PATH     = "/data/local/tmp/hibreak_restricted.txt";
    /** Boot-loop guard state: line 1 = epoch millis of the last boot, line 2 = streak. */
    public static final String BOOT_STREAK_PATH    = "/data/local/tmp/hibreak_boot_streak.txt";

    public static final String[] CONF_KEYS = {
        "FIX_UART","GOOGLE_STACK","BIGME_BLOAT","MTK_CELLULAR","AOSP_STUBS",
        "LOCKDOWN_GBOARD","AGGRESSIVE_DOZE","SUPPRESS_ALARMS","KERNEL_SENSOR",
        "SENSOR_ALL_MUTE","SENSOR_PREV_STATE","SENSOR_ACCEL_ROTATION","SENSOR_FACE_DOWN",
        "SENSOR_LIGHT_AUTO","SENSOR_TILT_WAKE","SENSOR_PROXIMITY","SENSOR_NAFG_GAUGE",
        "BATTERY_CAP_85","CHARGE_LIMIT_PCT","GOVERNOR_PROFILE","CPU_OPTIMIZER","HOTPLUG_4_CORES","WIFI_SLEEP_ZERO",
        "SLEEP_GOVERNOR","SLEEP_GOVERNOR_ENABLED",
        "AUTO_SHUTDOWN_ENABLED","AUTO_SHUTDOWN_TIMEOUT_MIN","AUTO_SHUTDOWN_SKIP_WHEN_CHARGING",
        "AUTO_SHUTDOWN_DRY_RUN","DISABLE_ANIMATIONS",
        "KILL_SHUTDOWN_ALARM","SUPPRESS_JS_IDLE","WIDE_ALARM_FUZZ","INSTANT_LOCK",
        "GOOGLE_PKGS_SEL","BIGME_PKGS_SEL","MTK_PKGS_SEL","AOSP_PKGS_SEL","DRY_RUN",
        "RULES_SUSPENDED"
    };

    public static final String GOOGLE_PKGS =
        "com.google.android.gms com.android.vending com.google.android.gsf " +
        "com.google.android.configupdater com.google.android.apps.turbo " +
        "com.google.android.as com.google.android.as.oss " +
        "com.google.android.adservices.api com.google.mainline.adservices " +
        "com.google.android.federatedcompute " +
        "com.google.android.nearby.halfsheet " +
        "com.google.android.tts com.google.android.apps.photos " +
        "com.google.android.cellbroadcastreceiver com.google.android.ext.services " +
        "com.google.android.hotspot2.osulogin com.google.android.ext.shared " +
        "com.google.android.syncadapters.calendar com.google.android.projection.gearhead " +
        "com.google.android.apps.restore com.google.android.apps.safetyhub " +
        "com.google.android.healthconnect.controller com.google.android.health.connect.backuprestore " +
        "com.google.android.ondevicepersonalization.services " +
        "com.google.android.gms.location.history " +
        "com.google.android.gms.supervision com.google.mainline.telemetry " +
        "com.google.android.printservice.recommendation " +
        "com.google.android.uwb.resources " +
        "com.google.android.cellbroadcastservice com.google.android.partnersetup " +
        "com.google.android.onetimeinitializer com.google.android.feedback " +
        "com.google.android.modulemetadata";

    public static final String BIGME_PKGS =
        "com.xrz.ai com.example.test com.test.logcollect com.xrz.bigmecloud " +
        "com.xrz.hoverballdemo com.xrz.globalwritingservice com.xrz.appstore " +
        "com.xrz.bookmall com.xrz.ebook com.xrz.xreaderV3 com.xrz.music " +
        "com.xrz.video com.xrz.voice.text com.xrz.doc.translate com.xrz.ebook.launcher " +
        "com.xrz.soundrecord com.xrz.btranslate com.xrz.dictapp com.b300.xrz.web " +
        "com.xrz.mutidisplay com.xrz.res.service com.xrz.tts.service " +
        "com.xrz.input com.xrz.standby com.xrz.appmanager com.xrz.ebook.shelf";

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
        "com.android.providers.calendar com.android.localtransport " +
        // com.android.deskclock deliberately NOT frozen: it is the only alarm
        // clock on this device, so freezing it silently kills alarms and timers.
        // It stays available in the Debloat tab if the user wants it gone.
        "com.android.se " +
        "com.android.calllogbackup com.android.cts.ctsshim com.android.cts.priv.ctsshim " +
        "com.android.dreams.basic com.android.emergency " +
        "com.android.pacprocessor " +
        "com.android.wallpapercropper com.android.wallpaperpicker com.android.wallpaperbackup " +
        "com.android.bookmarkprovider com.android.backupconfirm com.android.bluetoothmidiservice " +
        "com.android.sharedstoragebackup " +
        "com.android.location.fused com.android.role.notes.enabled " +
        "com.android.carrierconfig com.android.egg com.android.mms.service " +
        "com.android.ons com.android.simappdialog com.android.cameraextensions " +
        "com.android.nfc com.android.dialer com.android.contacts " +
        "com.mediatek.capctrl.service";

    // ── Package descriptions for the per-package checkbox UI ───────────────
    public static final java.util.Map<String, String> PKG_DESCRIPTIONS = new java.util.LinkedHashMap<>();
    static {
        // Google
        PKG_DESCRIPTIONS.put("com.google.android.gms", "Play Services — constant background wakeups");
        PKG_DESCRIPTIONS.put("com.android.vending", "Google Play Store — app updates & telemetry");
        PKG_DESCRIPTIONS.put("com.google.android.gsf", "Google Services Framework — cloud sync daemon");
        PKG_DESCRIPTIONS.put("com.google.android.configupdater", "Auto-downloads config updates silently");
        PKG_DESCRIPTIONS.put("com.google.android.apps.turbo", "Digital Wellbeing & battery stats reporter");
        PKG_DESCRIPTIONS.put("com.google.android.as", "Android System Intelligence — ML on-device");
        PKG_DESCRIPTIONS.put("com.google.android.as.oss", "On-device personalization — scans your data");
        PKG_DESCRIPTIONS.put("com.google.android.adservices.api", "Ad Services — ad tracking & profiling");
        PKG_DESCRIPTIONS.put("com.google.mainline.adservices", "Mainline AdServices module");
        PKG_DESCRIPTIONS.put("com.google.android.federatedcompute", "Federated learning — trains models on device");
        PKG_DESCRIPTIONS.put("com.google.android.nearby.halfsheet", "Nearby sharing popup service");
        PKG_DESCRIPTIONS.put("com.google.android.tts", "Text-to-Speech engine");
        PKG_DESCRIPTIONS.put("com.google.android.apps.photos", "Google Photos — background backup & ML");
        PKG_DESCRIPTIONS.put("com.google.android.cellbroadcastreceiver", "Emergency alert receiver");
        PKG_DESCRIPTIONS.put("com.google.android.ext.services", "Android Extensions — background helper");
        PKG_DESCRIPTIONS.put("com.google.android.hotspot2.osulogin", "WiFi hotspot login service");
        PKG_DESCRIPTIONS.put("com.google.android.ext.shared", "Android Extensions shared lib");
        PKG_DESCRIPTIONS.put("com.google.android.syncadapters.calendar", "Calendar sync adapter");
        PKG_DESCRIPTIONS.put("com.google.android.projection.gearhead", "Android Auto projection");
        PKG_DESCRIPTIONS.put("com.google.android.apps.restore", "Google data restore wizard");
        PKG_DESCRIPTIONS.put("com.google.android.apps.safetyhub", "Safety Hub — emergency features");
        PKG_DESCRIPTIONS.put("com.google.android.healthconnect.controller", "Health Connect controller");
        PKG_DESCRIPTIONS.put("com.google.android.health.connect.backuprestore", "Health Connect backup");
        PKG_DESCRIPTIONS.put("com.google.android.ondevicepersonalization.services", "On-device personalization");
        PKG_DESCRIPTIONS.put("com.google.android.gms.location.history", "Location history tracker");
        PKG_DESCRIPTIONS.put("com.google.android.gms.supervision", "Digital Wellbeing supervision");
        PKG_DESCRIPTIONS.put("com.google.mainline.telemetry", "Google telemetry data collector");
        PKG_DESCRIPTIONS.put("com.google.android.printservice.recommendation", "Print service discovery");
        PKG_DESCRIPTIONS.put("com.google.android.uwb.resources", "Ultra-Wideband resources");
        PKG_DESCRIPTIONS.put("com.google.android.cellbroadcastservice", "Cell broadcast service");
        PKG_DESCRIPTIONS.put("com.google.android.partnersetup", "Partner setup wizard");
        PKG_DESCRIPTIONS.put("com.google.android.onetimeinitializer", "First-run initializer");
        PKG_DESCRIPTIONS.put("com.google.android.feedback", "Google feedback reporter");
        PKG_DESCRIPTIONS.put("com.google.android.modulemetadata", "Module metadata updater");
        // Bigme
        PKG_DESCRIPTIONS.put("com.xrz.ai", "Bigme AI assistant — always running");
        PKG_DESCRIPTIONS.put("com.example.test", "Bigme factory test app");
        PKG_DESCRIPTIONS.put("com.test.logcollect", "Bigme log collector daemon");
        PKG_DESCRIPTIONS.put("com.xrz.bigmecloud", "Bigme Cloud sync — background data");
        PKG_DESCRIPTIONS.put("com.xrz.hoverballdemo", "Hover ball UI demo");
        PKG_DESCRIPTIONS.put("com.xrz.globalwritingservice", "Global writing service daemon");
        PKG_DESCRIPTIONS.put("com.xrz.appstore", "Bigme App Store — background updates");
        PKG_DESCRIPTIONS.put("com.xrz.bookmall", "Bigme Book Mall — store daemon");
        PKG_DESCRIPTIONS.put("com.xrz.ebook", "Bigme ebook reader engine");
        PKG_DESCRIPTIONS.put("com.xrz.xreaderV3", "Bigme X-Reader v3");
        PKG_DESCRIPTIONS.put("com.xrz.music", "Bigme Music player");
        PKG_DESCRIPTIONS.put("com.xrz.video", "Bigme Video player");
        PKG_DESCRIPTIONS.put("com.xrz.voice.text", "Voice-to-text service");
        PKG_DESCRIPTIONS.put("com.xrz.doc.translate", "Document translator daemon");
        PKG_DESCRIPTIONS.put("com.xrz.ebook.launcher", "Ebook launcher shortcut");
        PKG_DESCRIPTIONS.put("com.xrz.soundrecord", "Sound recorder app");
        PKG_DESCRIPTIONS.put("com.xrz.btranslate", "Bigme translate service");
        PKG_DESCRIPTIONS.put("com.xrz.dictapp", "Bigme dictionary app");
        PKG_DESCRIPTIONS.put("com.b300.xrz.web", "Bigme web browser");
        PKG_DESCRIPTIONS.put("com.xrz.mutidisplay", "Multi-display manager daemon");
        PKG_DESCRIPTIONS.put("com.xrz.res.service", "Bigme resource service");
        PKG_DESCRIPTIONS.put("com.xrz.tts.service", "Bigme TTS service daemon");
        PKG_DESCRIPTIONS.put("com.xrz.input", "Bigme input method");
        PKG_DESCRIPTIONS.put("com.xrz.standby", "Bigme standby manager daemon");
        PKG_DESCRIPTIONS.put("com.xrz.appmanager", "Bigme app manager");
        PKG_DESCRIPTIONS.put("com.xrz.ebook.shelf", "Bigme ebook shelf widget");
        // MTK
        PKG_DESCRIPTIONS.put("com.mediatek.ims", "IMS — VoLTE & VoWiFi daemon");
        PKG_DESCRIPTIONS.put("com.mediatek.simprocessor", "SIM card processor service");
        PKG_DESCRIPTIONS.put("com.mediatek.telephony", "Telephony framework service");
        PKG_DESCRIPTIONS.put("com.mediatek.callrecorder", "Call recording service");
        PKG_DESCRIPTIONS.put("com.mediatek.duraspeed", "App standby optimizer");
        PKG_DESCRIPTIONS.put("com.mediatek.location.mtkgeofence", "Geofencing service");
        PKG_DESCRIPTIONS.put("com.mediatek.voicecommand", "Voice command daemon");
        PKG_DESCRIPTIONS.put("com.mediatek.omacp", "OMA carrier provisioning");
        PKG_DESCRIPTIONS.put("com.mediatek.smartratswitch.service", "Smart radio switching");
        PKG_DESCRIPTIONS.put("com.mediatek.gnss.nonframeworklbs", "GNSS location service");
        PKG_DESCRIPTIONS.put("com.mediatek.location.lppe.main", "LPPe location service");
        PKG_DESCRIPTIONS.put("com.mediatek.miravision.ui", "MiraVision display tuning");
        PKG_DESCRIPTIONS.put("com.mediatek.gbaservice", "GBA auth service");
        PKG_DESCRIPTIONS.put("com.mediatek.factorymode", "Factory test mode");
        PKG_DESCRIPTIONS.put("com.mediatek.batterywarning", "Battery warning popup");
        PKG_DESCRIPTIONS.put("com.mediatek.aovtestapp", "Always-on vision test app");
        PKG_DESCRIPTIONS.put("com.mediatek.voiceunlock", "Voice unlock daemon");
        // AOSP
        PKG_DESCRIPTIONS.put("com.android.phone", "Phone process — telephony daemon");
        PKG_DESCRIPTIONS.put("com.android.server.telecom", "Telecom framework service");
        PKG_DESCRIPTIONS.put("com.android.providers.telephony", "Telephony database provider");
        PKG_DESCRIPTIONS.put("com.android.mms", "SMS/MMS messaging app");
        PKG_DESCRIPTIONS.put("com.android.printspooler", "Print spooler service");
        PKG_DESCRIPTIONS.put("com.android.bips", "Built-in print service");
        PKG_DESCRIPTIONS.put("com.android.quicksearchbox", "Google search bar widget");
        PKG_DESCRIPTIONS.put("com.android.providers.calendar", "Calendar database provider");
        PKG_DESCRIPTIONS.put("com.android.localtransport", "Local backup transport");
        PKG_DESCRIPTIONS.put("com.android.deskclock", "Clock/Alarm/Timer app");
        PKG_DESCRIPTIONS.put("com.android.se", "Secure Element service");
        PKG_DESCRIPTIONS.put("com.android.calllogbackup", "Call log backup service");
        PKG_DESCRIPTIONS.put("com.android.cts.ctsshim", "CTS test shim");
        PKG_DESCRIPTIONS.put("com.android.cts.priv.ctsshim", "CTS privileged test shim");
        PKG_DESCRIPTIONS.put("com.android.dreams.basic", "Screen saver provider");
        PKG_DESCRIPTIONS.put("com.android.emergency", "Emergency info app");
        PKG_DESCRIPTIONS.put("com.android.pacprocessor", "PAC proxy auto-config");
        PKG_DESCRIPTIONS.put("com.android.wallpapercropper", "Wallpaper cropper tool");
        PKG_DESCRIPTIONS.put("com.android.wallpaperpicker", "Wallpaper picker");
        PKG_DESCRIPTIONS.put("com.android.wallpaperbackup", "Wallpaper backup service");
        PKG_DESCRIPTIONS.put("com.android.bookmarkprovider", "Bookmark database provider");
        PKG_DESCRIPTIONS.put("com.android.backupconfirm", "Backup confirmation UI");
        PKG_DESCRIPTIONS.put("com.android.bluetoothmidiservice", "Bluetooth MIDI service");
        PKG_DESCRIPTIONS.put("com.android.sharedstoragebackup", "Shared storage backup");
        PKG_DESCRIPTIONS.put("com.android.location.fused", "Fused location provider");
        PKG_DESCRIPTIONS.put("com.android.role.notes.enabled", "Notes role provider");
        PKG_DESCRIPTIONS.put("com.android.carrierconfig", "Carrier config service");
        PKG_DESCRIPTIONS.put("com.android.egg", "Android Easter egg");
        PKG_DESCRIPTIONS.put("com.android.mms.service", "MMS send/receive service");
        PKG_DESCRIPTIONS.put("com.android.ons", "Opportunistic network service");
        PKG_DESCRIPTIONS.put("com.android.simappdialog", "SIM app dialog");
        PKG_DESCRIPTIONS.put("com.android.cameraextensions", "Camera extensions service");
        PKG_DESCRIPTIONS.put("com.android.nfc", "NFC service daemon");
        PKG_DESCRIPTIONS.put("com.android.dialer", "Phone dialer app");
        PKG_DESCRIPTIONS.put("com.android.contacts", "Contacts/People app");
        PKG_DESCRIPTIONS.put("com.mediatek.capctrl.service", "MediaTek capture control service");
    }

    /**
     * A package name, as this app is willing to put one into a root command.
     *
     * Every package name here ends up inside a {@code su -c} command, and some of
     * them are read back from hibreak.conf, which lives in /data/local/tmp - a
     * world-writable directory. That makes the name attacker-controlled input as
     * far as the shell is concerned: a value containing ';', '$(...)', a backtick
     * or a newline would execute as root on the next boot rule pass. The shape
     * below is deliberately loose (OEM images ship odd names, and the framework
     * package is just "android") but it admits no shell metacharacter, no
     * whitespace, and no leading '-' that could be mistaken for a flag.
     */
    private static final java.util.regex.Pattern PACKAGE_NAME =
        java.util.regex.Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.]{0,254}$");

    public static boolean isValidPackageName(String pkg) {
        return pkg != null && PACKAGE_NAME.matcher(pkg.trim()).matches();
    }

    /** Governor profiles the app knows how to build. The marker file is written
     *  from a config value, so an unknown profile is refused rather than quoted
     *  into a root command. */
    private static final Set<String> GOVERNOR_PROFILES = new HashSet<>(Arrays.asList(
        "schedutil_efficient", "balanced", "ereader_battery", "deep_sleep", "stock"));

    public static boolean isValidGovernorProfile(String profile) {
        return profile != null && GOVERNOR_PROFILES.contains(profile.trim());
    }

    /**
     * Ownership/permission tail for a file root writes but this app reads back
     * (the config, the frozen ledger, the active-governor marker).
     *
     * These files used to be chmod 666, because root owns them and the app has to
     * read them. That is a bad trade: it lets any local app rewrite the package
     * lists that this app feeds to su -c on every boot. Handing the file to our
     * own uid with 0600 keeps the app's access and removes everyone else's; if the
     * chown is refused we fall back to 0644, which still cannot be tampered with
     * by a non-root process.
     */
    public static String secureFileTail(String path) {
        String uid;
        try {
            uid = String.valueOf(android.os.Process.myUid());
        } catch (Throwable t) {
            return " 2>/dev/null; chmod 644 " + path + " 2>/dev/null";
        }
        return " && (chown " + uid + " " + path + " 2>/dev/null && chmod 600 " + path +
               " 2>/dev/null || chmod 644 " + path + " 2>/dev/null)";
    }

    public static String buildPmCmd(String pkgList, boolean freeze) {
        String action = freeze ? "pm disable-user --user 0 " : "pm enable ";
        Set<String> pkgs = new HashSet<>();
        for (String p : pkgList.trim().split("\\s+")) {
            // Some categories take their list from the config file, so anything that
            // is not a package name is dropped here rather than reaching the shell.
            if (isValidPackageName(p)) pkgs.add(p);
            else if (!p.isEmpty()) ShellUtils.appendLog("buildPmCmd: ignoring invalid package name: " + p);
        }
        if (freeze) {
            // Dynamically discover all packages that must never be frozen
            // (PMS required roles + APEX-installed packages)
            Set<String> blocked = getBlockedPackages(pkgs);
            pkgs.removeAll(blocked);
            ShellUtils.appendLog("Frozen " + pkgs.size() + " packages, skipped " + blocked.size() + " required/APEX");
        }
        StringBuilder sb = new StringBuilder();
        for (String pkg : pkgs) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(action).append(pkg).append(" 2>/dev/null");
        }
        return sb.toString();
    }

    // ── Per-package selection helpers ─────────────────────────────────────

    /** Split a space-separated package list into a clean array, dropping anything
     *  that is not a package name. Shared by the category builders and the UI, so
     *  both see the same filtered set. */
    public static String[] splitPkgList(String pkgList) {
        List<String> result = new ArrayList<>();
        for (String p : pkgList.trim().split("\\s+")) {
            if (isValidPackageName(p)) result.add(p);
            else if (!p.isEmpty()) ShellUtils.appendLog("splitPkgList: ignoring invalid package name: " + p);
        }
        return result.toArray(new String[0]);
    }

    /**
     * Get the selected (checked) subset of packages for a category.
     * The selKey stores a comma-separated list of selected package names.
     * If the key is empty/missing, all packages default to selected.
     */
    public static String[] getSelectedPkgs(Properties config, String pkgList, String selKey) {
        String all[] = splitPkgList(pkgList);
        String saved = config.getProperty(selKey, "").trim();
        if (saved.isEmpty()) {
            // Nothing saved yet — default: all packages selected
            return all;
        }
        Set<String> selected = new HashSet<>(Arrays.asList(saved.split(",")));
        // Filter to only packages that exist in the full list and are selected
        List<String> result = new ArrayList<>();
        for (String pkg : all) {
            if (selected.contains(pkg)) result.add(pkg);
        }
        return result.toArray(new String[0]);
    }

    /**
     * Build pm command for only the selected (checked) packages.
     * Delegates to buildPmCmd for the blocked-packages safety filter.
     */
    public static String buildSelectedPmCmd(Properties config, String pkgList, String selKey, boolean freeze) {
        String selected[] = getSelectedPkgs(config, pkgList, selKey);
        if (selected.length == 0) return "";
        // Join selected packages back into a space-separated string for buildPmCmd
        String joined = String.join(" ", selected);
        return buildPmCmd(joined, freeze);
    }

    /**
     * Save the selected package set for a category.
     * Pass an empty string to reset to "all selected" (the default).
     */
    public static void savePkgSelection(Properties config, String selKey, String[] selectedPkgs) {
        // If all packages are selected, store empty string (meaning "all")
        config.setProperty(selKey, String.join(",", selectedPkgs));
    }

    // Cache: discovered blocked packages for this boot session.
    // Initialized once on first call to avoid repeating expensive dumpsys/pm path.
    private static Set<String> sCachedBlocked = null;

    private static Set<String> getBlockedPackages(Set<String> candidates) {
        if (sCachedBlocked != null) return sCachedBlocked;
        Set<String> blocked = new HashSet<>();

        // 1. Known non-PMS crashes: services that bind to specific packages at boot
        //    and crash system_server if the target is disabled.
        blocked.add("com.android.microdroid.empty_payload");  // virtualization framework
        blocked.add("com.android.location.fused");             // LocationManager binding

        // 2. Query PMS for required role packages.
        //    Format on Android 14:
        //      Installer:
        //        com.google.android.packageinstaller
        //      Uninstaller:
        //        com.google.android.packageinstaller
        //      Permission Controller:
        //        com.android.permissioncontroller
        //      Verifier:
        //        none
        String dump = ShellUtils.execRoot("dumpsys package").stdout;
        String[] lines = dump.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if ("Installer:".equals(trimmed) || "Uninstaller:".equals(trimmed) ||
                "Permission Controller:".equals(trimmed) || "Verifier:".equals(trimmed) ||
                "Wellbeing:".equals(trimmed) || "Sdk Sandbox:".equals(trimmed)) {
                // Next non-empty indented line is the package name (or "none")
                if (i + 1 < lines.length) {
                    String pkg = lines[i + 1].trim();
                    if (!pkg.isEmpty() && !"none".equals(pkg)) {
                        blocked.add(pkg);
                    }
                }
            }
        }

        // 3. Detect APEX-installed packages in one batch (never safe to freeze)
        Set<String> allCandidates = new HashSet<>();
        for (String p : GOOGLE_PKGS.trim().split("\\s+")) allCandidates.add(p);
        for (String p : AOSP_PKGS.trim().split("\\s+")) allCandidates.add(p);
        for (String p : BIGME_PKGS.trim().split("\\s+")) allCandidates.add(p);
        for (String p : MTK_PKGS.trim().split("\\s+")) allCandidates.add(p);        StringBuilder pmCmd = new StringBuilder("pm path");
        for (String p : allCandidates) pmCmd.append(" ").append(p);
        String pmOut = ShellUtils.execRoot(pmCmd.toString()).stdout;
        for (String line : pmOut.split("\n")) {
            if (line.contains("/apex/")) {
                int eq = line.lastIndexOf('=');
                if (eq > 0) blocked.add(line.substring(eq + 1).trim());
            }
        }

        // The Verifier role resolves to com.android.vending (Play Store). The user
        // explicitly wants it gone as part of GOOGLE_STACK, and root `pm install`
        // bypasses the verification flow, so freezing it is safe here.
        blocked.remove("com.android.vending");

        sCachedBlocked = blocked;
        ShellUtils.appendLog("Blocked " + blocked.size() + " required/APEX packages from freezing");
        return blocked;
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
        boolean offline = "ereader_battery".equals(profile) || "deep_sleep".equals(profile) || "1".equals(hotplug4Cores);
        String hpCmd = buildHotplugCmd(offline) + "; ";
        String ppmPrep = "magiskpolicy --live \"allow magisk proc_ppm file { read write open getattr }\" 2>/dev/null; " +
                         "chmod 666 /proc/ppm/policy_status /proc/ppm/policy/hard_userlimit* 2>/dev/null; ";

        if ("deep_sleep".equals(profile)) {
            return hpCmd + ppmPrep +
                   "echo 6 1 > /proc/ppm/policy_status 2>/dev/null; " +
                   "echo 0 900000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
                   "echo 0 900000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
                   "echo 1 400000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
                   "echo 1 400000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 900000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 80000 > /sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us 2>/dev/null; " +
                   "echo 5000 > /sys/devices/system/cpu/cpufreq/schedutil/down_rate_limit_us 2>/dev/null";
        } else if ("ereader_battery".equals(profile)) {
            return hpCmd + ppmPrep +
                   "echo 6 1 > /proc/ppm/policy_status 2>/dev/null; " +
                   "echo 0 1351000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
                   "echo 0 900000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
                   "echo 1 1244000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
                   "echo 1 745000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 1351000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 40000 > /sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us 2>/dev/null; " +
                   "echo 10000 > /sys/devices/system/cpu/cpufreq/schedutil/down_rate_limit_us 2>/dev/null";
        } else if ("stock".equals(profile)) {
            return hpCmd + ppmPrep +
                   "echo 0 -1 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
                   "echo 0 -1 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
                   "echo 1 -1 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
                   "echo 1 -1 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
                   "echo 6 0 > /proc/ppm/policy_status 2>/dev/null; " +
                   "echo 7 1 > /proc/ppm/policy_status 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 2200000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
                   "echo 1600000 > /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null";
        } else {
            // "schedutil_efficient" / balanced
            return hpCmd + ppmPrep +
                   "echo 6 1 > /proc/ppm/policy_status 2>/dev/null; " +
                   "echo 0 2200000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
                   "echo 0 900000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
                   "echo 1 1600000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
                   "echo 1 745000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "echo 2200000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
                   "chmod 666 /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
                   "echo 1600000 > /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
                   "echo 20000 > /sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us 2>/dev/null; " +
                   "echo 10000 > /sys/devices/system/cpu/cpufreq/schedutil/down_rate_limit_us 2>/dev/null";
        }
    }

    public static String getGovernorLabel(String profile) {
        if ("deep_sleep".equals(profile))      return "DEEP SLEEP (900/400MHz LOCKED)";
        if ("ereader_battery".equals(profile)) return "E-READER BATTERY (1.35GHz MAX)";
        if ("stock".equals(profile))           return "STOCK (2.06GHz LOCKED)";
        return "BALANCED EFFICIENT (2.2GHz PEAK)";
    }

    // All defaults OFF — the user enables features manually on first launch.
    //
    // NOTE: the four debloat keys below are INVERTED relative to every other
    // key: "0" means ON (freeze the category) and "1" means OFF. They used to
    // default to "0", so the first boot after a fresh install silently froze
    // the whole Google/Bigme/MTK/AOSP lists (~120 packages, including the
    // telephony stack and the only alarm clock) even though the toggle had
    // never been touched. An empty *_PKGS_SEL means "every package in the
    // category", which is what made it so wide.
    public static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("FIX_UART", "0");
        p.setProperty("GOOGLE_STACK", "1");
        p.setProperty("BIGME_BLOAT", "1");
        p.setProperty("MTK_CELLULAR", "1");
        p.setProperty("AOSP_STUBS", "1");
        p.setProperty("LOCKDOWN_GBOARD", "0");
        p.setProperty("AGGRESSIVE_DOZE", "0");
        p.setProperty("SUPPRESS_ALARMS", "0");
        p.setProperty("KERNEL_SENSOR", "0");
        p.setProperty("SENSOR_ALL_MUTE", "0");
        p.setProperty("SENSOR_PREV_STATE", "");
        for (SensorItem item : SENSORS) {
            p.setProperty(item.key, item.defaultVal);
        }
        // Enable flag for the charge ceiling. The key name is legacy and narrower
        // than the feature: the target lives in CHARGE_LIMIT_PCT. Kept because it
        // already exists in people's configs and means the same thing, so an old
        // config that asked for a ceiling now actually gets one.
        p.setProperty("BATTERY_CAP_85", "0");
        p.setProperty("CHARGE_LIMIT_PCT", "80");
        p.setProperty("GOVERNOR_PROFILE", "schedutil_efficient");
        // CPU_OPTIMIZER has its own key. It used to write into GOVERNOR_PROFILE,
        // which clobbered the wake/sleep profile name with "1".
        p.setProperty("CPU_OPTIMIZER", "0");
        p.setProperty("HOTPLUG_4_CORES", "0");
        p.setProperty("WIFI_SLEEP_ZERO", "0");
        p.setProperty("SLEEP_GOVERNOR", "deep_sleep");
        p.setProperty("SLEEP_GOVERNOR_ENABLED", "0");
        p.setProperty("AUTO_SHUTDOWN_ENABLED", "0");
        p.setProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120");
        // Powering off a charging device saves nothing, so skip it by default.
        p.setProperty("AUTO_SHUTDOWN_SKIP_WHEN_CHARGING", "1");
        // "1" logs the shutdown decision instead of performing it (safe testing).
        p.setProperty("AUTO_SHUTDOWN_DRY_RUN", "0");
        // Global "plan, don't act": state-changing operations are logged instead of
        // applied, so a whole rule set can be rehearsed. Read-only probes still run,
        // so every card keeps showing the real current state.
        p.setProperty("DRY_RUN", "0");
        // Set by the boot-loop guard when consecutive fast boots suspend the rule
        // pass. The UI reads it to show the notice and the RESUME button.
        p.setProperty("RULES_SUSPENDED", "0");
        p.setProperty("DISABLE_ANIMATIONS", "0");
        p.setProperty("KILL_SHUTDOWN_ALARM", "0");
        p.setProperty("SUPPRESS_JS_IDLE", "0");
        p.setProperty("WIDE_ALARM_FUZZ", "0");
        p.setProperty("INSTANT_LOCK", "0");
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
            StringBuilder sb = new StringBuilder("# HiBreak Manager Config v2.0\n");
            for (String key : CONF_KEYS) {
                sb.append(key).append("=").append(p.getProperty(key, "")).append("\n");
            }
            // Write via root — Java FileWriter can't write to /data/local/tmp (owned by shell:shell)
            ShellUtils.CommandResult r = ShellUtils.execRoot(
                "printf '" + sb.toString().replace("'", "'\\''") + "' > " + CONF_PATH +
                secureFileTail(CONF_PATH), false);
            if (!r.isSuccess()) {
                ShellUtils.appendLog("saveConfig failed (exit=" + r.exitCode + "): " + r.stderr);
            }
        } catch (Exception e) {
            ShellUtils.appendLog("saveConfig error: " + e.getMessage());
        }
    }

    // ── Dry-run (DRY_RUN) ────────────────────────────────────────────────
    // Global "plan, don't act". Every state-changing operation consults this, so a
    // whole rule set can be rehearsed without touching the device.
    //
    // The gate sits at the point of application instead of inside execRoot(): about
    // a third of the root calls are read-only probes (dumpsys, cat, getprop), and
    // the cards depend on those still returning real values while dry-run is on.
    // Gating execRoot() would have blanked every status card, which is exactly the
    // kind of lying this app is being cleaned of.
    //
    // It re-reads the file on every check rather than caching. loadConfig() is a
    // plain ~30-line file read with no root spawn, and reading the same source of
    // truth as the gate is what stops the UI and the gate drifting apart.
    public static final String DRY_TAG = "[DRY-RUN] would ";

    public static boolean isDryRun() {
        try {
            return "1".equals(loadConfig().getProperty("DRY_RUN", "0").trim());
        } catch (Throwable t) {
            return false;   // if the flag cannot be read, behave normally rather than
                            // silently doing nothing
        }
    }

    // ── Frozen-package ledger (basis for UNDO / RESTORE) ──────────────────
    // Records the packages THIS app disabled with `pm disable-user`, as it goes.
    //
    // The device cannot tell us which freezes are ours. `pm list packages -d`
    // reports 112 packages here - a mix of our work and pre-existing vendor
    // freezes, including com.android.launcher3, com.android.dialer, the telephony
    // stack and com.google.android.gms - and nothing marks which is which. A
    // restore that guessed "enable everything frozen" would re-enable the vendor's
    // own freezes, bring GMS back (undoing the point of this app) and could put a
    // second launcher on the device.
    //
    // So the ledger is the only safe source, and it comes with an honest limit: it
    // can undo what HiBig Zero does from now on. Freezes applied before the ledger
    // existed cannot be attributed, except for the category rules, which ARE
    // reconstructable from the *_PKGS_SEL selections in the config.
    public static final String FROZEN_LEDGER_PATH = "/data/local/tmp/hibreak_frozen.txt";

    /**
     * Never re-enabled by a restore, whatever the ledger says. These are on the
     * device's frozen list for a reason - the user's launcher, the telephony stack,
     * Bluetooth, and Google Play services, which this app deliberately keeps frozen.
     * Re-enabling GMS would undo the point of the app, and re-enabling
     * com.android.launcher3 would put a second launcher on the device.
     */
    public static final Set<String> NEVER_UNFREEZE = new HashSet<String>(Arrays.asList(
        "com.android.launcher3",
        "com.right9code.anyhome",
        "com.right9code.hibigzero",
        "android",
        "com.android.systemui",
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.gsf",
        "com.android.phone",
        "com.android.dialer",
        "com.android.contacts",
        "com.android.mms",
        "com.android.mms.service",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.settings",
        "com.android.providers.telephony"
    ));

    public static Set<String> loadFrozenLedger() {
        Set<String> pkgs = new HashSet<String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(FROZEN_LEDGER_PATH));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // The ledger is read back from a root-written file, so a line that is
                // not a package name is dropped before it can reach a command.
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (isValidPackageName(line)) pkgs.add(line);
                else ShellUtils.appendLog("loadFrozenLedger: ignoring invalid entry: " + line);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
        return pkgs;
    }

    public static void saveFrozenLedger(Set<String> pkgs) {
        StringBuilder sb = new StringBuilder("# Packages frozen by HiBig Zero\n");
        for (String pkg : pkgs) sb.append(pkg).append("\n");
        ShellUtils.execRoot(
            "printf '" + sb.toString().replace("'", "'\\''") + "' > " + FROZEN_LEDGER_PATH +
            secureFileTail(FROZEN_LEDGER_PATH), false);
    }

    /** Adds or removes a package from the ledger. Call after a successful freeze.
     *  Packages in NEVER_UNFREEZE are refused, so a restore can never re-enable
     *  them even if they somehow reach the ledger. */
    public static void recordFrozen(String pkg, boolean frozen) {
        try {
            if (!isValidPackageName(pkg)) {
                ShellUtils.appendLog("recordFrozen: ignoring invalid package name: " + pkg);
                return;
            }
            if (frozen && NEVER_UNFREEZE.contains(pkg)) return;
            Set<String> pkgs = loadFrozenLedger();
            boolean changed = frozen ? pkgs.add(pkg) : pkgs.remove(pkg);
            if (changed) saveFrozenLedger(pkgs);
        } catch (Exception e) {
            ShellUtils.appendLog("frozen ledger error: " + e.getMessage());
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

    // ── Boot-loop guard ───────────────────────────────────────────────────
    // A rule that loops the device is the worst failure this app can produce, and
    // it is the one failure the user cannot fix from inside the app, because the
    // app is what is looping. So consecutive fast boots suspend the boot rule
    // pass. The count is persisted BEFORE any rule runs: if the write came after
    // the pass, the one boot that matters most would be the one never recorded.

    /** A boot this soon after the previous one counts as a loop, not a restart. */
    public static final long FAST_BOOT_SECONDS = 180L;
    /** This many consecutive fast boots suspends the boot rule pass. */
    public static final int MAX_FAST_BOOTS = 3;

    /**
     * Records this boot and returns the streak of consecutive fast boots.
     *
     * If the state file cannot be read the streak restarts at zero: a fresh
     * install has no file, and suspending rules on every new install would be a
     * far worse failure than missing one detection. The guard fails open.
     */
    public static int recordBootAndGetStreak() {
        long now = System.currentTimeMillis();
        long[] prev = readBootStreakFile();
        long gapSec = prev[0] > 0 ? (now - prev[0]) / 1000L : -1L;
        int streak = (gapSec >= 0 && gapSec <= FAST_BOOT_SECONDS) ? (int) prev[1] + 1 : 0;
        writeBootStreakFile(now, streak);
        return streak;
    }

    public static int getBootStreak() {
        return (int) readBootStreakFile()[1];
    }

    /** Clears the streak, so the next boot starts counting from scratch. */
    public static void resetBootStreak() {
        writeBootStreakFile(System.currentTimeMillis(), 0);
    }

    private static long[] readBootStreakFile() {
        long at = 0, streak = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(BOOT_STREAK_PATH));
            String l1 = br.readLine();
            String l2 = br.readLine();
            if (l1 != null) at = Long.parseLong(l1.trim());
            if (l2 != null) streak = Long.parseLong(l2.trim());
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
        return new long[] { at, streak };
    }

    private static void writeBootStreakFile(long at, int streak) {
        // execRoot, not execRootAction: this is the guard's own bookkeeping, like
        // the log file, so it has to be recorded even while dry-run is on.
        ShellUtils.execRoot(
            "printf '%s\\n%s\\n' '" + at + "' '" + streak + "' > " + BOOT_STREAK_PATH +
            secureFileTail(BOOT_STREAK_PATH), false);
    }

    public static boolean areRulesSuspended() {
        try {
            return "1".equals(loadConfig().getProperty("RULES_SUSPENDED", "0").trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Writes the flag only when it actually changes, so a normal boot costs no write. */
    public static void setRulesSuspended(boolean suspended) {
        try {
            Properties p = loadConfig();
            String want = suspended ? "1" : "0";
            if (want.equals(p.getProperty("RULES_SUSPENDED", "0").trim())) return;
            p.setProperty("RULES_SUSPENDED", want);
            saveConfig(p);
        } catch (Exception e) {
            ShellUtils.appendLog("setRulesSuspended error: " + e.getMessage());
        }
    }

    /**
     * Called once per REAL boot: records it and reports whether the rule pass may
     * run. Manual "APPLY ALL RULES" does not come through here, because a manual
     * apply is not a boot and must not count toward the streak.
     *
     * @return true when the boot rules should be applied
     */
    public static boolean shouldApplyBootRules() {
        boolean suspend = recordBootAndGetStreak() >= MAX_FAST_BOOTS;
        setRulesSuspended(suspend);
        return !suspend;
    }

    public static Set<String> loadRestrictedPkgs() {
        Set<String> pkgs = new HashSet<String>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(RESTRICTED_PATH));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // This file lives in a world-writable directory and every line in it
                // is passed to buildRestrictCmd() at boot, so a line that is not a
                // package name is dropped here as well as at the command builder.
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (isValidPackageName(line)) pkgs.add(line);
                else ShellUtils.appendLog("loadRestrictedPkgs: ignoring invalid entry: " + line);
            }
            br.close();
        } catch (Exception ignored) {}
        return pkgs;
    }

    public static void saveRestrictedPkgs(Set<String> pkgs) {
        StringBuilder sb = new StringBuilder("# HiBreak Manager Restricted Packages\n");
        for (String pkg : pkgs) {
            sb.append(pkg).append("\n");
        }
        // Write via root — Java FileWriter can't write to /data/local/tmp (owned by shell:shell)
        ShellUtils.execRoot(
            "printf '" + sb.toString().replace("'", "'\\''") + "' > " + RESTRICTED_PATH +
            secureFileTail(RESTRICTED_PATH), false);
    }

    // Only appops that actually exist on this platform are issued. The previous
    // list also set ALARM_WAKEUP, BOOT_COMPLETED, RECEIVE_BOOT_COMPLETED, INTERNET,
    // ACCESS_NETWORK_STATE and JOB. None of those are op names here, so each died
    // with "Unknown operation string" inside 2>/dev/null and the command looked
    // like it was blocking alarms, boot receivers, network and jobs when it was
    // not. RUN_ANY_IN_BACKGROUND and START_FOREGROUND are the API 34 ops that carry
    // the same intent and were missing. There is no appop for network access; real
    // background data blocking needs `cmd netpolicy add restrict-background-blacklist
    // <UID>`, which is deliberately not done here (it takes a UID, not a package,
    // and it changes what the app can do, not just how often it runs).
    //
    // SCHEDULE_EXACT_ALARM is kept because it is a real op, but note it is
    // permission-backed: for a package that does not hold the permission the write
    // is accepted, exits 0, and is simply never recorded, so it does nothing there.
    // It only bites packages that actually requested exact-alarm access.
    //
    // The trailing `settings put global hidden_api_policy_p_apps 1` that used to sit
    // here is gone: it flipped a *global* setting (hidden API enforcement, warn-only)
    // as a side effect of restricting one package, unrestrict never undid it, and it
    // has nothing to do with restricting an app.
    public static String buildRestrictCmd(String pkg) {
        if (!isValidPackageName(pkg)) {
            ShellUtils.appendLog("buildRestrictCmd: refusing invalid package name: " + pkg);
            return "";
        }
        return "am force-stop " + pkg + " 2>/dev/null; " +
            "cmd appops set " + pkg + " RUN_IN_BACKGROUND ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " WAKE_LOCK ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " SCHEDULE_EXACT_ALARM ignore 2>/dev/null; " +
            "cmd appops set " + pkg + " START_FOREGROUND ignore 2>/dev/null; " +
            "am set-standby-bucket " + pkg + " restricted 2>/dev/null; " +
            "dumpsys deviceidle whitelist -" + pkg + " 2>/dev/null";
    }

    public static String buildUnrestrictCmd(String pkg) {
        if (!isValidPackageName(pkg)) {
            ShellUtils.appendLog("buildUnrestrictCmd: refusing invalid package name: " + pkg);
            return "";
        }
        return "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow 2>/dev/null; " +
            "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow 2>/dev/null; " +
            "cmd appops set " + pkg + " WAKE_LOCK allow 2>/dev/null; " +
            "cmd appops set " + pkg + " SCHEDULE_EXACT_ALARM allow 2>/dev/null; " +
            "cmd appops set " + pkg + " START_FOREGROUND allow 2>/dev/null; " +
            "am set-standby-bucket " + pkg + " active 2>/dev/null";
    }

    // ── Self-protection ───────────────────────────────────────────────────
    // Android reaps a background-restricted app a few minutes after screen-off
    // ("Killing ...: cached idle & background restricted"), taking the sleep
    // governor restore, the auto-shutdown alarm and the boot rules with it.
    // These three rights are therefore re-asserted at launch, at boot, and from
    // the PROTECTION card's [CHECK & FIX] button — one root spawn each time.

    public static final String SELF_PKG = "com.right9code.hibigzero";

    /**
     * Read-only status probe; fields are pipe-separated:
     * uid|background_op|doze_exempt_count|process_limit
     */
    public static String buildSelfStatusCmd() {
        return "echo \"$(id -u)|" +
            "$(cmd appops get " + SELF_PKG + " RUN_ANY_IN_BACKGROUND 2>/dev/null | head -1)|" +
            "$(dumpsys deviceidle whitelist 2>/dev/null | grep -c " + SELF_PKG + ")|" +
            "$(settings get global background_process_limit 2>/dev/null)\"";
    }

    /**
     * Repair this package's own background rights, and echo the state found
     * BEFORE the repair (same field order as buildSelfStatusCmd) so the UI can
     * report what was wrong. Idempotent, one root spawn, safe on every launch.
     */
    public static String buildSelfCheckAndFixCmd() {
        return "A=$(cmd appops get " + SELF_PKG + " RUN_ANY_IN_BACKGROUND 2>/dev/null | head -1); " +
            "D=$(dumpsys deviceidle whitelist 2>/dev/null | grep -c " + SELF_PKG + "); " +
            "P=$(settings get global background_process_limit 2>/dev/null); " +
            "cmd appops set " + SELF_PKG + " RUN_ANY_IN_BACKGROUND allow 2>/dev/null; " +
            "dumpsys deviceidle whitelist +" + SELF_PKG + " >/dev/null 2>&1; " +
            "[ \"$P\" = \"0\" ] && settings put global background_process_limit -1 2>/dev/null; " +
            "echo \"$(id -u)|$A|$D|$P\"";
    }

    /**
     * true when the appops read-back shows no active restriction.
     * Android prints "No operations.\nDefault mode: allow" when nothing is
     * overridden, and "RUN_ANY_IN_BACKGROUND: ignore" when it is restricted —
     * so the reliable signal is the presence of "ignore", not the presence of
     * "allow".
     */
    public static boolean isBgOpAllowed(String opLine) {
        if (opLine == null) return true;
        String s = opLine.trim();
        if (s.isEmpty()) return true;
        return !s.contains("ignore");
    }

    /** The standby buckets `am set-standby-bucket` accepts. Anything else is
     *  refused rather than interpolated into the command. */
    private static final Set<String> STANDBY_BUCKETS = new HashSet<>(Arrays.asList(
        "active", "working_set", "frequent", "rare", "restricted", "never"));

    public static String buildSetStandbyBucketCmd(String pkg, String bucket) {
        if (!isValidPackageName(pkg) || bucket == null || !STANDBY_BUCKETS.contains(bucket.trim())) {
            ShellUtils.appendLog("buildSetStandbyBucketCmd: refusing pkg=" + pkg + " bucket=" + bucket);
            return "";
        }
        return "am set-standby-bucket " + pkg + " " + bucket.trim() + " 2>/dev/null";
    }

    public static String buildDozeWhitelistCmd(String pkg, boolean exempt) {
        if (!isValidPackageName(pkg)) {
            ShellUtils.appendLog("buildDozeWhitelistCmd: refusing invalid package name: " + pkg);
            return "";
        }
        if (exempt) {
            return "dumpsys deviceidle whitelist +" + pkg + " 2>/dev/null";
        } else {
            // For system-excidle packages, whitelist removal re-adds immediately.
            // Use appops to silence the app instead — achieves the same effect.
            // Only app-ops that exist on API 34: ALARM_WAKEUP, BOOT_COMPLETED and
            // RECEIVE_BOOT_COMPLETED were removed and made these calls no-ops.
            return "dumpsys deviceidle whitelist -" + pkg + " 2>/dev/null; " +
                "cmd appops set " + pkg + " RUN_IN_BACKGROUND ignore 2>/dev/null; " +
                "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore 2>/dev/null; " +
                "cmd appops set " + pkg + " WAKE_LOCK ignore 2>/dev/null; " +
                "cmd appops set " + pkg + " SCHEDULE_EXACT_ALARM ignore 2>/dev/null; " +
                "cmd appops set " + pkg + " START_FOREGROUND ignore 2>/dev/null; " +
                "am set-standby-bucket " + pkg + " restricted 2>/dev/null";
        }
    }

    // ── Optimization #3: Suspend failure reduction commands ──────────────

    public static String getKillShutdownAlarmCmd(boolean enable) {
        if (enable) {
            return "pm disable com.android.settings/com.xrz.settings.receiver.PowersaveShutDownAlarmReceiver 2>/dev/null; " +
                "am force-stop com.android.settings 2>/dev/null; " +
                "mkdir -p /data/system/ifw 2>/dev/null; " +
                "echo '<rules><broadcast block=\"true\" log=\"true\"><component name=\"com.android.settings/com.xrz.settings.receiver.PowersaveShutDownAlarmReceiver\"/></broadcast></rules>' > /data/system/ifw/block_bigme_shutdown.xml 2>/dev/null; " +
                "chmod 644 /data/system/ifw/block_bigme_shutdown.xml 2>/dev/null; " +
                "chown system:system /data/system/ifw/block_bigme_shutdown.xml 2>/dev/null; " +
                "cmd appops set com.android.settings SCHEDULE_EXACT_ALARM ignore 2>/dev/null";
        } else {
            return "pm enable com.android.settings/com.xrz.settings.receiver.PowersaveShutDownAlarmReceiver 2>/dev/null; " +
                "rm -f /data/system/ifw/block_bigme_shutdown.xml 2>/dev/null; " +
                "cmd appops set com.android.settings SCHEDULE_EXACT_ALARM allow 2>/dev/null";
        }
    }

    /**
     * Our own versionName, straight from PackageManager. Never hardcode the
     * version in the UI again - that is how the banner drifted out of sync.
     */
    public static String getAppVersion(android.content.Context ctx) {
        try {
            if (ctx != null) {
                return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            }
        } catch (Exception ignored) {}
        return "0.0.0";
    }

    /**
     * Real probe for Bigme's own power-off receiver. The previous test only
     * checked that the IntentFirewall XML existed, but this firmware loads 0
     * rules from that file, so its presence proves nothing. The component's
     * disabled state is the only trustworthy signal.
     */
    public static String getBigmeShutdownProbeCmd() {
        return "dumpsys package com.android.settings 2>/dev/null | " +
            "sed -n '/disabledComponents:/,/enabledComponents:/p' | " +
            "grep -q PowersaveShutDownAlarmReceiver && echo DISABLED || echo ACTIVE";
    }

    /** Reads a boolean config key straight from the on-disk config file. */
    public static String getConfigProbeCmd(String key, String onToken, String offToken) {
        return "grep -q '^" + key + "=1' " + CONF_PATH + " 2>/dev/null && echo " +
            onToken + " || echo " + offToken;
    }

    // ── CPU uncap (CPU_OPTIMIZER) ─────────────────────────────────────────
    /**
     * Release MediaTek's 2.06 GHz ceiling and let the little cluster scale to
     * 2.2 GHz. The ceiling is PPM_POLICY_USER_LIMIT (policy 7), isolated on
     * device with every other variable held constant:
     *
     *   policy6 ON,  policy7 ON   -> p0 max 2068000
     *   policy6 OFF, policy7 ON   -> p0 max 2068000, and 2068000 under 4 busy threads
     *   policy6 OFF, policy7 OFF  -> p0 max 2200000, and 2200000 under load
     *
     * So `echo 7 0` is the unlock; the profiles set the hard limits per profile.
     * CAUTION: scaling_max_freq echoes whatever you write even while an effective
     * clamp is in force, so it is not a reliable test on its own - policy 7's
     * state is the truth, which is what the probe reads.
     */
    public static String getCpuUncapCmd() {
        return "magiskpolicy --live \"allow magisk proc_ppm file { read write open getattr }\" 2>/dev/null; " +
            "chmod 666 /proc/ppm/policy_status /proc/ppm/policy/hard_userlimit* 2>/dev/null; " +
            "echo 7 0 > /proc/ppm/policy_status 2>/dev/null; " +
            "echo 0 2200000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
            "echo 1 1600000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
            "echo 0 900000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
            "echo 1 745000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
            "chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
            "echo 2200000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
            "chmod 666 /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null; " +
            "echo 1600000 > /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null";
    }
    /** Little-cluster ceiling: 2200000 = uncapped, 2068000 = 2.06GHz lock on. */
    public static String getCpuUncapProbeCmd() {
        return "echo p0max=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null) " +
            "p4max=$(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null)";
    }

    // ── Charge ceiling (BATTERY_CAP_85 + CHARGE_LIMIT_PCT) ────────────────
    // "BATTERY_CAP_85" is the on/off flag; the target percentage lives in
    // CHARGE_LIMIT_PCT (resume is derived 5% below it).
    //
    // This firmware exposes no charge *threshold* node, which is what the old
    // implementation looked for (charging_limit, charge_control_limit, ...) and
    // why it silently did nothing. It does expose the MediaTek charge switch, and
    // that is all a ceiling needs: ACC and the Magisk charge-limiter modules do not
    // add kernel support either, they flip an existing switch and poll. Verified
    // on this device:
    //   echo "1 1" > /proc/mtk_battery_cmd/current_cmd  -> status Charging -> Not charging
    //   echo "0 0" > /proc/mtk_battery_cmd/current_cmd  -> status back to Charging
    // The node reads back exactly what was written, so it is self-describing and
    // no state file is needed.
    //
    // battery/disable also exists and is writable here, but it is a NO-OP: with it
    // set to 1 the battery kept charging at 334 mA. It is deliberately not used.
    public static final String[][] CHARGE_SWITCHES = {
        // { node, value that STOPS charging, value that RESUMES charging }
        { "/proc/mtk_battery_cmd/current_cmd", "1 1", "0 0" }
    };

    /**
     * Whether this device has the charge switch. Cached, because the answer cannot
     * change while the device is running - the node is either in the kernel or not.
     *
     * The probe MUST run as root. /proc/mtk_battery_cmd is not traversable by a
     * normal UID, so a plain File.exists() from an app reports false on a device
     * that does have the node and that root can read and write. That is exactly the
     * mistake this check made first: it hid the feature on a phone where it works.
     * Verified on device - `ls /proc/mtk_battery_cmd/` as the shell user is
     * "Permission denied", while root lists it and the node reads normally.
     */
    private static volatile Boolean chargeLimitAvailable = null;

    public static boolean isChargeLimitAvailable() {
        Boolean cached = chargeLimitAvailable;
        if (cached != null) return cached;
        try {
            StringBuilder sb = new StringBuilder();
            for (String[] sw : CHARGE_SWITCHES) {
                sb.append("[ -e ").append(sw[0]).append(" ] && { echo YES; exit 0; }; ");
            }
            sb.append("echo NO");
            String out = ShellUtils.execRoot(sb.toString(), false).stdout;
            chargeLimitAvailable = Boolean.valueOf(out.contains("YES"));
        } catch (Throwable t) {
            return true;   // fail open: never lose the feature because the check erred
        }
        return chargeLimitAvailable;
    }

    /** Target percentage, clamped to a range that is useful for a battery ceiling. */
    public static int getChargeLimitPct() {
        try {
            int pct = Integer.parseInt(
                loadConfig().getProperty("CHARGE_LIMIT_PCT", "80").trim());
            if (pct >= 50 && pct <= 100) return pct;
        } catch (Exception ignored) {}
        return 80;
    }

    /** Resume 5% below the target: the gap is what stops the switch chattering. */
    public static int getChargeResumePct() {
        return Math.max(50, getChargeLimitPct() - 5);
    }

    /** Stops charging on whichever switch this device has. */
    public static String getChargeStopCmd() {
        StringBuilder sb = new StringBuilder();
        for (String[] sw : CHARGE_SWITCHES) {
            sb.append("[ -e ").append(sw[0]).append(" ] && { echo \"")
              .append(sw[1]).append("\" > ").append(sw[0])
              .append(" 2>/dev/null; echo STOPPED; exit 0; }; ");
        }
        return sb.append("echo NO_CHARGE_SWITCH").toString();
    }

    /** Resumes charging. Written on every unplug and before every evaluate. */
    public static String getChargeResumeCmd() {
        StringBuilder sb = new StringBuilder();
        for (String[] sw : CHARGE_SWITCHES) {
            sb.append("[ -e ").append(sw[0]).append(" ] && { echo \"")
              .append(sw[2]).append("\" > ").append(sw[0])
              .append(" 2>/dev/null; echo RESUMED; exit 0; }; ");
        }
        return sb.append("echo NO_CHARGE_SWITCH").toString();
    }

    /** Reports the switch value so the UI can show whether charging is held or not. */
    public static String getChargeLimitProbeCmd() {
        StringBuilder sb = new StringBuilder();
        for (String[] sw : CHARGE_SWITCHES) {
            sb.append("[ -e ").append(sw[0]).append(" ] && { cat ")
              .append(sw[0]).append(" 2>/dev/null; exit 0; }; ");
        }
        return sb.append("echo N/A").toString();
    }

    // ── Suppress GMS alarms (SUPPRESS_ALARMS) ─────────────────────────────
    /**
     * Real app-ops only. This used to set ALARM_WAKEUP, which does not exist on
     * API 34 ("Error: Unknown operation string: ALARM_WAKEUP"), so the toggle
     * silently did nothing. Verified present on device: SCHEDULE_EXACT_ALARM,
     * RUN_IN_BACKGROUND, WAKE_LOCK.
     */
    public static String getSuppressGmsAlarmsCmd(boolean enable) {
        String m = enable ? "ignore" : "allow";
        String cmd = "cmd appops set com.google.android.gms RUN_IN_BACKGROUND " + m + " 2>/dev/null; " +
            "cmd appops set com.google.android.gms RUN_ANY_IN_BACKGROUND " + m + " 2>/dev/null; " +
            "cmd appops set com.google.android.gms WAKE_LOCK " + m + " 2>/dev/null; " +
            "cmd appops set com.google.android.gms START_FOREGROUND " + m + " 2>/dev/null";
        // NOTE: SCHEDULE_EXACT_ALARM is deliberately absent - it is backed by the
        // "Alarms & reminders" permission and `appops set` on it is immediately
        // dropped for GMS (read-back: "No operations. Default mode: default").
        // `jobscheduler cancel` (not cancel-all, which is not a valid verb on
        // API 34) drops GMS's already-queued jobs.
        return enable ? cmd + "; cmd jobscheduler cancel com.google.android.gms 2>/dev/null" : cmd;
    }

    /** Reads the op that provably reflects the state (RUN_IN_BACKGROUND). */
    public static String getSuppressGmsAlarmsProbeCmd() {
        return "cmd appops get com.google.android.gms RUN_IN_BACKGROUND 2>/dev/null | head -1";
    }

    public static String getSuppressJsIdleCmd(boolean enable) {
        if (enable) {
            return "cmd jobscheduler cancel cn.wps.moffice_eng 2>/dev/null; " +
                "cmd jobscheduler cancel org.koreader.launcher 2>/dev/null; " +
                "device_config put jobscheduler min_ready_non_active_jobs_count 99 2>/dev/null; " +
                "settings put global job_scheduler_constants min_ready_non_active_jobs_count=99 2>/dev/null";
        } else {
            return "device_config put jobscheduler min_ready_non_active_jobs_count 10 2>/dev/null; " +
                "settings put global job_scheduler_constants min_ready_non_active_jobs_count=10 2>/dev/null";
        }
    }

    public static String getWideAlarmFuzzCmd(boolean enable) {
        if (enable) {
            return "device_config put alarm_manager min_futurity 60000 2>/dev/null; " +
                "device_config put alarm_manager min_device_idle_fuzz 600000 2>/dev/null; " +
                "device_config put alarm_manager max_device_idle_fuzz 2700000 2>/dev/null; " +
                "device_config put alarm_manager time_tick_allowed_while_idle false 2>/dev/null; " +
                "device_config put alarm_manager allow_while_idle_quota 6 2>/dev/null; " +
                "settings put global alarm_manager_constants min_futurity=60000,min_device_idle_fuzz=600000,max_device_idle_fuzz=2700000,time_tick_allowed_while_idle=false,allow_while_idle_quota=6,delay_nonwakeup_alarms_while_screen_off=true 2>/dev/null";
        } else {
            return "device_config put alarm_manager min_futurity 60000 2>/dev/null; " +
                "device_config put alarm_manager min_device_idle_fuzz 300000 2>/dev/null; " +
                "device_config put alarm_manager max_device_idle_fuzz 600000 2>/dev/null; " +
                "settings delete global alarm_manager_constants 2>/dev/null";
        }
    }

    public static String getInstantLockCmd(boolean enable) {
        if (enable) {
            return "settings put secure lock_screen_lock_after_timeout 0 2>/dev/null";
        } else {
            return "settings put secure lock_screen_lock_after_timeout 5000 2>/dev/null";
        }
    }

    // ── Aggressive Doze ───────────────────────────────────────────────────
    // ON  = shorten the path into doze, but keep deep idle reachable.
    // OFF = RESTORE normal doze (enable) and drop our overrides. The old OFF
    //       branch ran `dumpsys deviceidle disable`, which switched doze off
    //       entirely and left the aggressive constants behind — the worst of
    //       both worlds (0 min deep doze measured on device, `idle_to=24h`).
    public static String getAggressiveDozeCmd(boolean enable) {
        final String KEYS = "quick_doze_delay_to inactive_to sensing_to locating_to " +
            "motion_inactive_to idle_to max_idle_to min_time_to_alarm";
        if (enable) {
            return "dumpsys deviceidle enable 2>/dev/null; " +
                "device_config put device_idle quick_doze_delay_to 5000 2>/dev/null; " +
                "device_config put device_idle inactive_to 300000 2>/dev/null; " +
                "device_config put device_idle sensing_to 60000 2>/dev/null; " +
                "device_config put device_idle locating_to 60000 2>/dev/null; " +
                "device_config put device_idle motion_inactive_to 60000 2>/dev/null; " +
                "device_config put device_idle idle_to 1800000 2>/dev/null; " +
                "device_config put device_idle max_idle_to 21600000 2>/dev/null; " +
                "device_config put device_idle min_time_to_alarm 3600000 2>/dev/null";
        }
        StringBuilder sb = new StringBuilder("dumpsys deviceidle enable 2>/dev/null; ");
        for (String k : KEYS.split(" ")) {
            sb.append("device_config delete device_idle ").append(k).append(" 2>/dev/null; ");
        }
        return sb.toString();
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

    // ── Modular Sensor Management ──────────────────────────────────────────
    public static class SensorItem {
        public final String key;
        public final String title;
        public final String desc;
        public final String defaultVal; // "0" = muted/disabled, "1" = active

        public SensorItem(String key, String title, String desc, String defaultVal) {
            this.key = key;
            this.title = title;
            this.desc = desc;
            this.defaultVal = defaultVal;
        }
    }

    public static final SensorItem[] SENSORS = new SensorItem[] {
        new SensorItem("SENSOR_ACCEL_ROTATION", "Auto-Rotate / Accelerometer",
            "Polls accelerometer at 50Hz to auto-rotate display. Disabling locks orientation.", "0"),
        new SensorItem("SENSOR_FACE_DOWN", "Face-Down Sleep Trigger",
            "PowerManager orientation checking to enter sleep when placed face-down.", "0"),
        new SensorItem("SENSOR_LIGHT_AUTO", "Ambient Light (Auto-Brightness)",
            "Optical lux sensor continuous sampling for dynamic screen backlight adjustment.", "0"),
        new SensorItem("SENSOR_TILT_WAKE", "Tilt & Wake Gestures (Lift-to-Wake)",
            "Hardware sensor interrupts waking CPU on movement or pick-up gestures.", "0"),
        new SensorItem("SENSOR_PROXIMITY", "Proximity & In-Call Sensor",
            "Infrared proximity sensor polling for ear detection during calls.", "0"),
        new SensorItem("SENSOR_NAFG_GAUGE", "Kernel NAFG Gauge Polling",
            "MediaTek PMIC battery fuel gauge continuous high-frequency sampling.", "0")
    };

    public static String getSensorApplyCmd(String key, boolean enable) {
        if ("SENSOR_ACCEL_ROTATION".equals(key)) {
            return enable ? "settings put system accelerometer_rotation 1 2>/dev/null"
                          : "settings put system accelerometer_rotation 0 2>/dev/null; settings put system user_rotation 0 2>/dev/null";
        } else if ("SENSOR_FACE_DOWN".equals(key)) {
            return enable ? "device_config put power face_down_detector_enabled true 2>/dev/null"
                          : "device_config put power face_down_detector_enabled false 2>/dev/null";
        } else if ("SENSOR_LIGHT_AUTO".equals(key)) {
            return enable ? "settings put system screen_brightness_mode 1 2>/dev/null"
                          : "settings put system screen_brightness_mode 0 2>/dev/null";
        } else if ("SENSOR_TILT_WAKE".equals(key)) {
            return enable ? "settings put secure wake_gesture_enabled 1 2>/dev/null; settings put secure doze_tilt_to_wake 1 2>/dev/null; settings put secure doze_wake_screen_gesture 1 2>/dev/null"
                          : "settings put secure wake_gesture_enabled 0 2>/dev/null; settings put secure doze_tilt_to_wake 0 2>/dev/null; settings put secure doze_wake_screen_gesture 0 2>/dev/null";
        } else if ("SENSOR_PROXIMITY".equals(key)) {
            return enable ? "settings put system proximity_sensor 1 2>/dev/null"
                          : "settings put system proximity_sensor 0 2>/dev/null";
        } else if ("SENSOR_NAFG_GAUGE".equals(key)) {
            return enable ? "echo 1 > /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/disable_nafg 2>/dev/null; echo 1 > /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/ntc_disable_nafg 2>/dev/null"
                          : "echo 0 > /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/disable_nafg 2>/dev/null; echo 0 > /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/ntc_disable_nafg 2>/dev/null";
        }
        return "";
    }

    public static String getSensorProbeCmd(String key) {
        if ("SENSOR_ACCEL_ROTATION".equals(key)) {
            return "settings get system accelerometer_rotation 2>/dev/null";
        } else if ("SENSOR_FACE_DOWN".equals(key)) {
            return "device_config get power face_down_detector_enabled 2>/dev/null";
        } else if ("SENSOR_LIGHT_AUTO".equals(key)) {
            return "settings get system screen_brightness_mode 2>/dev/null";
        } else if ("SENSOR_TILT_WAKE".equals(key)) {
            return "settings get secure wake_gesture_enabled 2>/dev/null";
        } else if ("SENSOR_PROXIMITY".equals(key)) {
            return "settings get system proximity_sensor 2>/dev/null";
        } else if ("SENSOR_NAFG_GAUGE".equals(key)) {
            return "cat /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/disable_nafg 2>/dev/null";
        }
        return "";
    }

    public static String buildAllSensorsApplyCmd(Properties cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("setprop vendor.powerhal.smart.powersave 1 2>/dev/null; ");
        sb.append("setprop persist.vendor.powerhal.mode 1 2>/dev/null; ");
        for (SensorItem item : SENSORS) {
            boolean enable = "1".equals(cfg.getProperty(item.key, item.defaultVal));
            String cmd = getSensorApplyCmd(item.key, enable);
            if (!cmd.isEmpty()) {
                sb.append(cmd).append("; ");
            }
        }
        return sb.toString();
    }
}
