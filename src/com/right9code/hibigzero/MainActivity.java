package com.right9code.hibigzero;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public class MainActivity extends Activity {
    private Properties currentConfig;
    private LinearLayout contentContainer;
    private int activeTab = 0;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView headerBatteryView;
    private Runnable headerBatteryUpdater;
    private Runnable diagBatteryUpdater;
    private boolean diagBatteryRunning = false;
    private String debloatFilter = "ALL";
    private int debloatSortMode = 0;
    private String debloatSearch = "";
    private List<AppItem> cachedAppItems = null;
    private Button tabSysBtn, tabAppsBtn, tabDiagBtn;

    static class AppItem {
        final ApplicationInfo app;
        final String label;
        final String pkg;
        final boolean isProt;
        final boolean isEnabled;
        final boolean isSystem;
        boolean isRestricted;
        int standbyBucket;
        boolean isDozeExempt;
        AppItem(ApplicationInfo app, String label, String pkg, boolean isProt, boolean isEnabled, boolean isSystem, boolean isRestricted, int standbyBucket, boolean isDozeExempt) {
            this.app = app;
            this.label = label;
            this.pkg = pkg;
            this.isProt = isProt;
            this.isEnabled = isEnabled;
            this.isSystem = isSystem;
            this.isRestricted = isRestricted;
            this.standbyBucket = standbyBucket;
            this.isDozeExempt = isDozeExempt;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentConfig = ConfigManager.loadConfig();
        if (getIntent() != null) activeTab = getIntent().getIntExtra("tab", 0);

        // Ensure log file is writable
        new Thread(new Runnable() {
            @Override
            public void run() {
                ShellUtils.execRoot("touch " + ShellUtils.LOG_PATH + " && chmod 666 " + ShellUtils.LOG_PATH + " 2>/dev/null");
                installAutoShutdownScript();
                ShellUtils.appendLog("HiBreak Manager v2.0 launched (right9code)");
            }
        }).start();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ── Header (inverted: white on black) ──────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(16, 12, 16, 8);
        header.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("HIBIG ZERO");
        title.setTextSize(22);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        header.addView(title);

        TextView title2 = new TextView(this);
        title2.setText("ZERO-DRAIN MANAGER | right9code");
        title2.setTextSize(13);
        title2.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title2.setTextColor(Color.WHITE);
        header.addView(title2);

        TextView sub = new TextView(this);
        sub.setText("Bigme HiBreak | Helio P35 | Android 14");
        sub.setTextSize(10);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setTextColor(Color.WHITE);
        sub.setPadding(0, 4, 0, 0);
        header.addView(sub);

        headerBatteryView = new TextView(this);
        headerBatteryView.setText("BATTERY: reading...");
        headerBatteryView.setTextSize(11);
        headerBatteryView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        headerBatteryView.setTextColor(Color.WHITE);
        headerBatteryView.setPadding(0, 4, 0, 0);
        header.addView(headerBatteryView);

        // Boot confirmation
        String lastBoot = ConfigManager.getLastBootTime();
        TextView bootTv = new TextView(this);
        bootTv.setText(lastBoot.isEmpty() ? "[!!] BOOT: not applied yet" : "[OK] BOOT: " + lastBoot);
        bootTv.setTextSize(10);
        bootTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        bootTv.setTextColor(Color.WHITE);
        bootTv.setPadding(0, 4, 0, 0);
        header.addView(bootTv);

        root.addView(header);

        // ── Tab Bar ─────────────────────────────────────────────────────────
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(0, 0, 0, 0);

        tabSysBtn  = createTabButton(">> SYSTEM", 0);
        tabAppsBtn = createTabButton("DEBLOAT", 1);
        tabDiagBtn = createTabButton("BATTERY", 2);
        tabBar.addView(tabSysBtn);
        tabBar.addView(tabAppsBtn);
        tabBar.addView(tabDiagBtn);
        root.addView(tabBar);

        // Thick divider
        View div = new View(this);
        div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4));
        div.setBackgroundColor(Color.BLACK);
        root.addView(div);

        // ── Scrollable content ───────────────────────────────────────────────
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(0, 0, 0, 40);
        scroll.addView(contentContainer);
        root.addView(scroll);

        setContentView(root);
        renderCurrentTab();
        startHeaderBatteryUpdater();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (headerBatteryUpdater != null) mainHandler.removeCallbacks(headerBatteryUpdater);
        diagBatteryRunning = false;
        if (diagBatteryUpdater != null) mainHandler.removeCallbacks(diagBatteryUpdater);
    }

    // ── Install auto_shutdown.sh from assets ─────────────────────────────────
    private void installAutoShutdownScript() {
        try {
            java.io.InputStream is = getAssets().open("auto_shutdown.sh");
            java.io.File internalFile = new java.io.File(getFilesDir(), "auto_shutdown.sh");
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
            ShellUtils.appendLog("auto_shutdown.sh installed to " + ConfigManager.SHUTDOWN_SCRIPT);
        } catch (Exception e) {
            ShellUtils.appendLog("auto_shutdown.sh install err: " + e.getMessage());
        }
    }

    private void applyTimeoutValue(String mins, TextView tVal, TextView logDrawer) {
        currentConfig.setProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", mins);
        ConfigManager.saveConfig(currentConfig);
        if (tVal != null) tVal.setText(mins + " MIN");
        if ("1".equals(currentConfig.getProperty("AUTO_SHUTDOWN_ENABLED", "1"))) {
            ShellUtils.execRoot("[ -f /data/local/tmp/autoshutdown.pid ] && kill -9 $(cat /data/local/tmp/autoshutdown.pid 2>/dev/null) 2>/dev/null; rm -f /data/local/tmp/autoshutdown.pid; " +
                "nohup sh " + ConfigManager.SHUTDOWN_SCRIPT + " " + mins +
                " > /data/local/tmp/autoshutdown.log 2>&1 &");
        }
        ShellUtils.appendLog("Auto-shutdown timeout set to: " + mins + " min");
        if (logDrawer != null && logDrawer.getVisibility() == View.VISIBLE) {
            logDrawer.setText(ShellUtils.readLog(15));
        }
    }

    // ── Header battery updater (30s) ─────────────────────────────────────────
    private void startHeaderBatteryUpdater() {
        headerBatteryUpdater = new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ShellUtils.CommandResult res = ShellUtils.execRoot(
                            "echo \"$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)|$(cat /sys/class/power_supply/battery/current_now 2>/dev/null)|$(cat /sys/class/power_supply/battery/status 2>/dev/null)\"", false);
                        String[] parts = (res != null && res.stdout != null) ? res.stdout.trim().split("\\|", -1) : new String[0];
                        String cap = (parts.length > 0 && !parts[0].isEmpty()) ? parts[0] : "?";
                        int ma = (parts.length > 1) ? parseCurrent(parts[1]) : 0;
                        String st = (parts.length > 2 && !parts[2].isEmpty()) ? " [" + parts[2] + "]" : "";
                        final String txt = "Battery: " + cap + "%  " + ma + " mA" + st;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() { headerBatteryView.setText(txt); }
                        });
                    }
                }).start();
                mainHandler.postDelayed(this, 30000);
            }
        };
        mainHandler.post(headerBatteryUpdater);
    }

    private int parseCurrent(String raw) {
        try {
            int v = Integer.parseInt(raw.trim().replace("-", ""));
            return v > 10000 ? v / 1000 : v;
        } catch (Exception e) { return 0; }
    }

    // ── Tab routing ──────────────────────────────────────────────────────────
    private Button createTabButton(String text, final int index) {
        Button btn = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(0, 0, 0, 0);
        btn.setLayoutParams(lp);
        btn.setText(text);
        btn.setTextSize(11);
        btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btn.setPadding(0, 12, 0, 12);
        if (index == activeTab) {
            btn.setBackgroundColor(Color.BLACK);
            btn.setTextColor(Color.WHITE);
        } else {
            btn.setBackgroundColor(Color.WHITE);
            btn.setTextColor(Color.BLACK);
        }
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { activeTab = index; renderCurrentTab(); }
        });
        return btn;
    }

    private void renderCurrentTab() {
        diagBatteryRunning = false;
        if (diagBatteryUpdater != null) mainHandler.removeCallbacks(diagBatteryUpdater);
        updateTabStyles();
        contentContainer.removeAllViews();
        if (activeTab == 0)      renderSystemControls();
        else if (activeTab == 1) renderAppDebloat();
        else                     renderDiagnostics();
    }

    private void updateTabStyles() {
        if (tabSysBtn != null) {
            tabSysBtn.setBackgroundColor(activeTab == 0 ? Color.BLACK : Color.WHITE);
            tabSysBtn.setTextColor(activeTab == 0 ? Color.WHITE : Color.BLACK);
            tabSysBtn.setText(activeTab == 0 ? ">> SYSTEM" : "SYSTEM");
        }
        if (tabAppsBtn != null) {
            tabAppsBtn.setBackgroundColor(activeTab == 1 ? Color.BLACK : Color.WHITE);
            tabAppsBtn.setTextColor(activeTab == 1 ? Color.WHITE : Color.BLACK);
            tabAppsBtn.setText(activeTab == 1 ? ">> DEBLOAT" : "DEBLOAT");
        }
        if (tabDiagBtn != null) {
            tabDiagBtn.setBackgroundColor(activeTab == 2 ? Color.BLACK : Color.WHITE);
            tabDiagBtn.setTextColor(activeTab == 2 ? Color.WHITE : Color.BLACK);
            tabDiagBtn.setText(activeTab == 2 ? ">> BATTERY" : "BATTERY");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 1: SYSTEM CONTROLS
    // ─────────────────────────────────────────────────────────────────────────
    private void renderSystemControls() {
        // ── Log drawer (collapsible, hidden by default) ────────────────────
        final TextView logDrawer = new TextView(this);
        logDrawer.setTypeface(Typeface.MONOSPACE);
        logDrawer.setTextSize(10);
        logDrawer.setTextColor(Color.BLACK);
        logDrawer.setBackgroundColor(Color.WHITE);
        logDrawer.setPadding(12, 8, 12, 8);
        logDrawer.setText("(log appears after first toggle)");
        logDrawer.setVisibility(View.GONE);

        final Button logToggle = new Button(this);
        logToggle.setText("[>>] SHOW LOG");
        logToggle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        logToggle.setTextSize(11);
        logToggle.setBackgroundColor(Color.WHITE);
        logToggle.setTextColor(Color.BLACK);
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (logDrawer.getVisibility() == View.GONE) {
                    logDrawer.setVisibility(View.VISIBLE);
                    logDrawer.setText(ShellUtils.readLog(15));
                    logToggle.setText("[<<] HIDE LOG");
                } else {
                    logDrawer.setVisibility(View.GONE);
                    logToggle.setText("[>>] SHOW LOG");
                }
            }
        });

        // ── Section: HARDWARE ──────────────────────────────────────────────
        addSectionHeader("HARDWARE");

        // Pre-build pm command strings
        String frGoogle    = ConfigManager.buildPmCmd(ConfigManager.GOOGLE_PKGS, true);
        String unGoogle    = ConfigManager.buildPmCmd(ConfigManager.GOOGLE_PKGS, false);
        String frBigme     = ConfigManager.buildPmCmd(ConfigManager.BIGME_PKGS, true);
        String unBigme     = ConfigManager.buildPmCmd(ConfigManager.BIGME_PKGS, false);
        String frMtk       = ConfigManager.buildPmCmd(ConfigManager.MTK_PKGS, true);
        String unMtk       = ConfigManager.buildPmCmd(ConfigManager.MTK_PKGS, false);
        String frAosp      = ConfigManager.buildPmCmd(ConfigManager.AOSP_PKGS, true);
        String unAosp      = ConfigManager.buildPmCmd(ConfigManager.AOSP_PKGS, false);

        addToggle("FIX_UART", "FIX_UART",
            "Kill runaway uart2serport daemon & clear early-boot crash latch",
            false,
            "setprop sys.init.updatable_crashing \"\" 2>/dev/null; setprop sys.init.updatable_crashing_process_name \"\" 2>/dev/null; setprop ctl.stop uart2serport 2>/dev/null; setprop persist.vendor.uart2serport.enable 0 2>/dev/null; kill -9 $(pidof uart2serport) 2>/dev/null; chmod 755 /system/bin/start_uart2serport.sh 2>/dev/null",
            "setprop persist.vendor.uart2serport.enable 1 2>/dev/null",
            "[ -f /system/bin/start_uart2serport.sh ] && [ -z \"$(getprop sys.init.updatable_crashing)\" ] && echo 'STUBBED_OK' || echo 'CRASH_LOOP'",
            logDrawer);

        addToggle("KERNEL_SENSOR", "KERNEL_SENSOR",
            "Lock rotation, disable 50Hz sensor polling, PowerHAL powersave",
            false,
            "settings put system accelerometer_rotation 0 2>/dev/null; settings put system user_rotation 0 2>/dev/null; device_config put power face_down_detector_enabled false 2>/dev/null; echo 0 > /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/disable_nafg 2>/dev/null; echo 0 > /sys/devices/platform/1000d000.pwrap/1000d000.pwrap:main_pmic/mt6357-gauge/ntc_disable_nafg 2>/dev/null; setprop vendor.powerhal.smart.powersave 1 2>/dev/null; setprop persist.vendor.powerhal.mode 1 2>/dev/null",
            "settings put system accelerometer_rotation 1 2>/dev/null; device_config put power face_down_detector_enabled true 2>/dev/null; setprop vendor.powerhal.smart.powersave 0 2>/dev/null",
            "settings get system accelerometer_rotation",
            logDrawer);

        addToggle("ANIMATIONS_0", "DISABLE_ANIMATIONS",
            "Window, transition, animator scales to 0.0x for crisp E-ink",
            false,
            "settings put global window_animation_scale 0.0 2>/dev/null; settings put global transition_animation_scale 0.0 2>/dev/null; settings put global animator_duration_scale 0.0 2>/dev/null; settings put global disable_window_blurs 1 2>/dev/null; setprop persist.sys.sf.disable_blurs 1 2>/dev/null",
            "settings put global window_animation_scale 1.0 2>/dev/null; settings put global transition_animation_scale 1.0 2>/dev/null; settings put global animator_duration_scale 1.0 2>/dev/null; settings put global disable_window_blurs 0 2>/dev/null",
            "settings get global window_animation_scale",
            logDrawer);

        // ── Section: DEBLOAT ───────────────────────────────────────────────
        addSectionHeader("DEBLOAT");

        addToggle("GOOGLE_STACK", "GOOGLE_STACK",
            "Freeze Play Services & GSF (cloud socket wakeups)",
            true,
            frGoogle, unGoogle,
            "pm list packages -d 2>/dev/null | grep -q com.google.android.gms && echo FROZEN || echo ACTIVE",
            logDrawer);

        addToggle("BIGME_BLOAT", "BIGME_BLOAT",
            "Freeze 15 Bigme AI, cloud, store, demo daemons",
            true,
            frBigme, unBigme,
            "pm list packages -d 2>/dev/null | grep -q com.xrz.ai && echo FROZEN || echo ACTIVE",
            logDrawer);

        addToggle("MTK_CELLULAR", "MTK_CELLULAR",
            "Disable baseband IMS, telephony, sim services",
            true,
            frMtk, unMtk,
            "pm list packages -d 2>/dev/null | grep -q com.mediatek.ims && echo FROZEN || echo ACTIVE",
            logDrawer);

        addToggle("AOSP_STUBS", "AOSP_STUBS",
            "Freeze dialer, telecom, print spooler, MMS provider",
            true,
            frAosp, unAosp,
            "pm list packages -d 2>/dev/null | grep -q com.android.phone && echo FROZEN || echo ACTIVE",
            logDrawer);

        // ── Section: NETWORK & TELEMETRY ───────────────────────────────────
        addSectionHeader("NETWORK");

        addToggle("LOCKDOWN_GBOARD", "LOCKDOWN_GBOARD",
            "Block background jobs, Superpacks sync & wakelocks for Gboard",
            false,
            "cmd appops set com.google.android.inputmethod.latin RUN_IN_BACKGROUND ignore 2>/dev/null; cmd appops set com.google.android.inputmethod.latin RUN_ANY_IN_BACKGROUND ignore 2>/dev/null; cmd appops set com.google.android.inputmethod.latin START_FOREGROUND ignore 2>/dev/null; cmd appops set com.google.android.inputmethod.latin WAKE_LOCK ignore 2>/dev/null; cmd jobscheduler cancel com.google.android.inputmethod.latin 2>/dev/null; am set-standby-bucket com.google.android.inputmethod.latin rare 2>/dev/null; dumpsys deviceidle whitelist -com.google.android.inputmethod.latin 2>/dev/null",
            "cmd appops set com.google.android.inputmethod.latin RUN_IN_BACKGROUND allow 2>/dev/null; cmd appops set com.google.android.inputmethod.latin RUN_ANY_IN_BACKGROUND allow 2>/dev/null; cmd appops set com.google.android.inputmethod.latin START_FOREGROUND allow 2>/dev/null; cmd appops set com.google.android.inputmethod.latin WAKE_LOCK allow 2>/dev/null; am set-standby-bucket com.google.android.inputmethod.latin active 2>/dev/null",
            "cmd appops get com.google.android.inputmethod.latin RUN_ANY_IN_BACKGROUND 2>/dev/null | head -1",
            logDrawer);

        addToggle("SUPPRESS_ALARMS", "SUPPRESS_ALARMS",
            "Block GMS alarm wakeups & background scheduling",
            false,
            "cmd appops set com.google.android.gms ALARM_WAKEUP ignore 2>/dev/null",
            "cmd appops set com.google.android.gms ALARM_WAKEUP allow 2>/dev/null",
            "cmd appops get com.google.android.gms ALARM_WAKEUP 2>/dev/null | head -1",
            logDrawer);

        addToggle("WIFI_SLEEP", "WIFI_SLEEP_ZERO",
            "Disable Wi-Fi scans & interfaces in standby (~5 mA)",
            false,
            "settings put global wifi_sleep_policy 0 2>/dev/null; settings put global wifi_idle_ms 5000 2>/dev/null; settings put global wifi_scan_always_enabled 0 2>/dev/null; cmd wifi set-scan-always-available 0 2>/dev/null",
            "settings put global wifi_sleep_policy 2 2>/dev/null; settings put global wifi_scan_always_enabled 1 2>/dev/null",
            "settings get global wifi_sleep_policy",
            logDrawer);

        // ── Section: POWER ─────────────────────────────────────────────────
        addSectionHeader("POWER");

        addToggle("CPU_OPTIMIZER", "GOVERNOR_PROFILE",
            "Uncap MediaTek PPM 2.06GHz lock & enable dynamic CPU scaling",
            false,
            ConfigManager.buildGovernorCmd(currentConfig.getProperty("GOVERNOR_PROFILE", "schedutil_efficient")),
            ConfigManager.buildGovernorCmd("stock"),
            "cat /proc/ppm/policy_status 2>/dev/null | grep -q 'PPM_POLICY_USER_LIMIT: enabled' && echo 'LOCKED_2.06G' || echo 'UNCAPPED_DYNAMIC'",
            logDrawer);

        // Governor Profile selector
        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setGravity(Gravity.CENTER_VERTICAL);
        profileRow.setPadding(16, 8, 16, 8);
        profileRow.setBackgroundColor(Color.WHITE);

        TextView pLabel = new TextView(this);
        pLabel.setText("PROFILE: ");
        pLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pLabel.setTextSize(11);
        pLabel.setTextColor(Color.BLACK);

        final TextView pVal = new TextView(this);
        pVal.setText(ConfigManager.getGovernorLabel(currentConfig.getProperty("GOVERNOR_PROFILE", "schedutil_efficient")));
        pVal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pVal.setTextSize(11);
        pVal.setTextColor(Color.BLACK);

        Button changeProfile = new Button(this);
        changeProfile.setText("[CHANGE]");
        changeProfile.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        changeProfile.setTextSize(11);
        changeProfile.setBackgroundColor(Color.BLACK);
        changeProfile.setTextColor(Color.WHITE);
        changeProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] opts = {
                    "Balanced Efficient (900MHz - 2.2GHz on demand)",
                    "E-Reader Battery (900MHz - 1.35GHz, 4 Cores)",
                    "Stock MediaTek (factory 2.06GHz lock)"
                };
                final String[] vals = {"schedutil_efficient", "ereader_battery", "stock"};
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("CPU GOVERNOR PROFILE")
                    .setItems(opts, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            String selected = vals[which];
                            currentConfig.setProperty("GOVERNOR_PROFILE", selected);
                            if ("ereader_battery".equals(selected)) {
                                currentConfig.setProperty("HOTPLUG_4_CORES", "1");
                            }
                            ConfigManager.saveConfig(currentConfig);
                            pVal.setText(ConfigManager.getGovernorLabel(selected));
                            ShellUtils.execRoot(ConfigManager.buildGovernorCmd(selected, currentConfig.getProperty("HOTPLUG_4_CORES", "0")));
                            ShellUtils.appendLog("CPU Governor set to: " + selected);
                            if (logDrawer.getVisibility() == View.VISIBLE) {
                                logDrawer.setText(ShellUtils.readLog(15));
                            }
                        }
                    }).show();
            }
        });

        profileRow.addView(pLabel);
        profileRow.addView(pVal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        profileRow.addView(changeProfile);
        contentContainer.addView(profileRow);

        addToggle("4_CORE_MODE", "HOTPLUG_4_CORES",
            "Power down Cores 4-7 to reduce silicon leakage (Auto-enforced in E-Reader Battery)",
            false,
            ConfigManager.buildHotplugCmd(true),
            ConfigManager.buildHotplugCmd(false),
            "[ \"$(cat /sys/devices/system/cpu/online 2>/dev/null)\" = '0-3' ] && echo '4_CORES' || echo '8_CORES'",
            logDrawer);

        addToggle("AGGRESSIVE_DOZE", "AGGRESSIVE_DOZE",
            "Deep idle within 5s of display off",
            false,
            "dumpsys deviceidle enable 2>/dev/null; device_config put device_idle quick_doze_delay_to 5000 2>/dev/null; device_config put device_idle inactive_to 5000 2>/dev/null; device_config put device_idle sensing_to 0 2>/dev/null; device_config put device_idle locating_to 0 2>/dev/null; device_config put device_idle motion_inactive_to 0 2>/dev/null; device_config put device_idle idle_to 86400000 2>/dev/null; device_config put device_idle max_idle_to 86400000 2>/dev/null",
            "dumpsys deviceidle disable 2>/dev/null",
            "dumpsys deviceidle 2>/dev/null | grep -i 'mEnabled' | head -1",
            logDrawer);

        addToggle("BATTERY_85", "BATTERY_CAP_85",
            "Charge ceiling 85% to protect Li-Ion cell",
            false,
            "echo 85 > /sys/class/power_supply/battery/charging_limit 2>/dev/null",
            "echo 100 > /sys/class/power_supply/battery/charging_limit 2>/dev/null",
            "cat /sys/class/power_supply/battery/charging_limit 2>/dev/null || echo N/A",
            logDrawer);

        addToggle("AUTO_SHUTDOWN", "AUTO_SHUTDOWN_ENABLED",
            "Clean reboot -p after inactivity (E-ink retains at 0 mA)",
            false,
            "[ -f /data/local/tmp/autoshutdown.pid ] && kill -9 $(cat /data/local/tmp/autoshutdown.pid 2>/dev/null) 2>/dev/null; rm -f /data/local/tmp/autoshutdown.pid; nohup sh " + ConfigManager.SHUTDOWN_SCRIPT + " $(grep AUTO_SHUTDOWN_TIMEOUT_MIN " + ConfigManager.CONF_PATH + " | cut -d= -f2) > /data/local/tmp/autoshutdown.log 2>&1 &",
            "[ -f /data/local/tmp/autoshutdown.pid ] && kill -9 $(cat /data/local/tmp/autoshutdown.pid 2>/dev/null) 2>/dev/null; rm -f /data/local/tmp/autoshutdown.pid",
            "[ -f /data/local/tmp/autoshutdown.pid ] && kill -0 $(cat /data/local/tmp/autoshutdown.pid 2>/dev/null) 2>/dev/null && echo DAEMON_RUNNING || echo STOPPED",
            logDrawer);

        // Timeout selector
        LinearLayout timeoutRow = new LinearLayout(this);
        timeoutRow.setOrientation(LinearLayout.HORIZONTAL);
        timeoutRow.setGravity(Gravity.CENTER_VERTICAL);
        timeoutRow.setPadding(16, 8, 16, 8);
        timeoutRow.setBackgroundColor(Color.WHITE);

        TextView tLabel = new TextView(this);
        tLabel.setText("TIMEOUT: ");
        tLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tLabel.setTextSize(11);
        tLabel.setTextColor(Color.BLACK);

        final TextView tVal = new TextView(this);
        tVal.setText(currentConfig.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120") + " MIN");
        tVal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tVal.setTextSize(11);
        tVal.setTextColor(Color.BLACK);

        Button changeTimer = new Button(this);
        changeTimer.setText("[CHANGE]");
        changeTimer.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        changeTimer.setTextSize(11);
        changeTimer.setBackgroundColor(Color.BLACK);
        changeTimer.setTextColor(Color.WHITE);
        changeTimer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] opts = {
                    "60 min (1h)",
                    "120 min (2h)",
                    "240 min (4h - Break-Even)",
                    "480 min (8h - Overnight)",
                    "Custom (enter minutes)..."
                };
                final String[] vals = {"60","120","240","480"};
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("INACTIVITY TIMEOUT")
                    .setItems(opts, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            if (which < vals.length) {
                                applyTimeoutValue(vals[which], tVal, logDrawer);
                            } else {
                                final EditText input = new EditText(MainActivity.this);
                                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                                input.setHint("Minutes (e.g. 180)");
                                input.setText(currentConfig.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120"));
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("CUSTOM TIMEOUT")
                                    .setMessage("Enter sleep duration before power-off in minutes:")
                                    .setView(input)
                                    .setPositiveButton("SET", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface di, int btn) {
                                            String val = input.getText().toString().trim();
                                            if (!val.isEmpty()) {
                                                try {
                                                    int m = Integer.parseInt(val);
                                                    if (m > 0) {
                                                        applyTimeoutValue(String.valueOf(m), tVal, logDrawer);
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        }
                                    })
                                    .setNegativeButton("CANCEL", null)
                                    .show();
                            }
                        }
                    }).show();
            }
        });

        timeoutRow.addView(tLabel);
        timeoutRow.addView(tVal);
        timeoutRow.addView(changeTimer);
        contentContainer.addView(timeoutRow);

        // ── APPLY ALL button ───────────────────────────────────────────────
        Button applyBtn = new Button(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(0, 16, 0, 0);
        applyBtn.setLayoutParams(alp);
        applyBtn.setText(">>> APPLY ALL RULES <<<");
        applyBtn.setBackgroundColor(Color.BLACK);
        applyBtn.setTextColor(Color.WHITE);
        applyBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        applyBtn.setTextSize(14);
        applyBtn.setPadding(0, 16, 0, 16);
        applyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyBtn.setEnabled(false);
                applyBtn.setText("APPLYING...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ConfigManager.saveConfig(currentConfig);
                        new BootReceiver().applyAllRulesPublic(MainActivity.this);
                        final String log = ShellUtils.readLog(15);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                applyBtn.setEnabled(true);
                                applyBtn.setText(">>> APPLY ALL RULES <<<");
                                logDrawer.setText(log);
                            }
                        });
                    }
                }).start();
            }
        });
        contentContainer.addView(applyBtn);

        // ── Log drawer (collapsible) ───────────────────────────────────────
        contentContainer.addView(logToggle);
        contentContainer.addView(logDrawer);
    }

    private void addSectionHeader(String text) {
        View bar = new View(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4));
        bar.setBackgroundColor(Color.BLACK);
        contentContainer.addView(bar);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.BLACK);
        tv.setPadding(16, 8, 16, 8);
        contentContainer.addView(tv);
    }

    // ── addToggle ─────────────────────────────────────────────────────────────
    private void addToggle(final String label, final String configKey, String desc,
                           final boolean inverted,
                           final String onCmd, final String offCmd, final String verifyCmd,
                           final TextView logDrawer) {

        final String val = currentConfig.getProperty(configKey, inverted ? "0" : "1");
        final boolean isOn = inverted ? "0".equals(val) : "1".equals(val);

        // Card with thick black border
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(8, 4, 8, 4);
        card.setLayoutParams(clp);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(12, 10, 12, 10);

        // Top row: status badge + title + switch
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView badge = new TextView(this);
        badge.setText(isOn ? "[+]" : "[-]");
        badge.setTextSize(14);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setTextColor(isOn ? Color.BLACK : Color.WHITE);
        badge.setBackgroundColor(isOn ? Color.WHITE : Color.BLACK);
        badge.setPadding(6, 2, 6, 2);

        TextView tTitle = new TextView(this);
        tTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tTitle.setText(" " + label);
        tTitle.setTextSize(12);
        tTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tTitle.setTextColor(Color.BLACK);
        tTitle.setPadding(8, 0, 0, 0);

        final Switch sw = new Switch(this);
        sw.setChecked(isOn);

        topRow.addView(badge);
        topRow.addView(tTitle);
        topRow.addView(sw);
        card.addView(topRow);

        // Description
        TextView tDesc = new TextView(this);
        tDesc.setText(desc);
        tDesc.setTextSize(10);
        tDesc.setTypeface(Typeface.MONOSPACE);
        tDesc.setTextColor(Color.BLACK);
        tDesc.setPadding(0, 4, 0, 2);
        card.addView(tDesc);

        // Status line
        final TextView statusLine = new TextView(this);
        statusLine.setText(isOn ? "[ON]  tap toggle to flip" : "[OFF]  tap toggle to flip");
        statusLine.setTextSize(10);
        statusLine.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        statusLine.setTextColor(Color.BLACK);
        card.addView(statusLine);

        // Bottom border
        View cardBorder = new View(this);
        cardBorder.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));
        cardBorder.setBackgroundColor(Color.BLACK);
        card.addView(cardBorder);

        contentContainer.addView(card);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                final String newVal = inverted ? (checked ? "0" : "1") : (checked ? "1" : "0");
                currentConfig.setProperty(configKey, newVal);
                ConfigManager.saveConfig(currentConfig);

                // Update badge
                badge.setText(checked ? "[+]" : "[-]");
                badge.setTextColor(checked ? Color.BLACK : Color.WHITE);
                badge.setBackgroundColor(checked ? Color.WHITE : Color.BLACK);
                statusLine.setText("[>>> APPLYING...]");

                final String cmd = checked ? onCmd : offCmd;
                if (cmd != null && !cmd.isEmpty()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ShellUtils.CommandResult res = ShellUtils.execRoot(cmd);
                            String verResult = "";
                            if (verifyCmd != null && !verifyCmd.isEmpty()) {
                                verResult = ShellUtils.execRoot(verifyCmd).stdout.trim();
                            }
                            final String statusText = verResult.isEmpty()
                                ? (res.isSuccess() ? "[OK]" : "[FAIL]")
                                : "[OK: " + verResult + "]";
                            final boolean ok = res.isSuccess();
                            final String log = ShellUtils.readLog(15);
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    statusLine.setText(statusText + "  tap toggle to flip");
                                    statusLine.setTextColor(ok ? Color.BLACK : Color.BLACK);
                                    statusLine.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                                    if (logDrawer != null) logDrawer.setText(log);
                                }
                            });
                        }
                    }).start();
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 2: APP DEBLOAT
    // ─────────────────────────────────────────────────────────────────────────
    private String getSortName(int mode) {
        switch (mode) {
            case 0: return "A-Z";
            case 1: return "Z-A";
            case 2: return "FROZEN";
            case 3: return "ACTIVE";
            case 4: return "PROT";
            default: return "A-Z";
        }
    }

    private Button createFilterButton(final String name, final LinearLayout listContainer, final PackageManager pm, final List<String> protected_pkgs, final LinearLayout filterBar) {
        final Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(2, 0, 2, 0);
        b.setLayoutParams(lp);
        b.setText(name);
        b.setTextSize(10);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setPadding(0, 6, 0, 6);
        boolean isSel = debloatFilter.equals(name);
        b.setBackgroundColor(isSel ? Color.BLACK : Color.WHITE);
        b.setTextColor(isSel ? Color.WHITE : Color.BLACK);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                debloatFilter = name;
                for (int i = 0; i < filterBar.getChildCount(); i++) {
                    View child = filterBar.getChildAt(i);
                    if (child instanceof Button) {
                        Button cb = (Button) child;
                        boolean s = cb.getText().toString().equals(debloatFilter);
                        cb.setBackgroundColor(s ? Color.BLACK : Color.WHITE);
                        cb.setTextColor(s ? Color.WHITE : Color.BLACK);
                    }
                }
                renderDebloatList(listContainer, pm, protected_pkgs);
            }
        });
        return b;
    }

    private void renderAppDebloat() {
        final List<String> protected_pkgs = new ArrayList<String>();
        protected_pkgs.add("com.right9code.hibigzero");
        protected_pkgs.add("com.right9code.hibreakmanager");
        protected_pkgs.add("com.right9code.anyhome");
        protected_pkgs.add("org.koreader.launcher");
        protected_pkgs.add("android");
        protected_pkgs.add("com.android.systemui");
        protected_pkgs.add("com.topjohnwu.magisk");
        protected_pkgs.add("com.xrz.sys.control");
        protected_pkgs.add("com.xrz.settings");
        protected_pkgs.add("com.xrz.standby");
        protected_pkgs.add("com.xrz.input");
        protected_pkgs.add("com.google.android.webview");
        protected_pkgs.add("com.termux");
        protected_pkgs.add("com.tailscale.ipn");
        protected_pkgs.add("md.obsidian");
        protected_pkgs.add("com.syncthing.android");
        protected_pkgs.add("com.wireguard.android");

        addSectionHeader("PACKAGE FREEZER");

        TextView sTitle = new TextView(this);
        sTitle.setText("FREEZE, UNFREEZE, OR RESTRICT");
        sTitle.setTextSize(10);
        sTitle.setTypeface(Typeface.MONOSPACE);
        sTitle.setTextColor(Color.BLACK);
        sTitle.setPadding(16, 8, 16, 8);
        contentContainer.addView(sTitle);

        // Quick action buttons
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setPadding(0, 0, 0, 8);

        Button freezeAll = new Button(this);
        LinearLayout.LayoutParams falp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        falp.setMargins(0, 0, 6, 0);
        freezeAll.setLayoutParams(falp);
        freezeAll.setText("FREEZE ALL");
        freezeAll.setBackgroundColor(Color.BLACK);
        freezeAll.setTextColor(Color.WHITE);
        freezeAll.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        freezeAll.setTextSize(11);
        freezeAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Freeze All Non-Protected Packages?")
                    .setMessage("This will freeze all Google, Bigme, MTK and AOSP bloat packages immediately via root.")
                    .setPositiveButton("Freeze All", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            Toast.makeText(MainActivity.this, "Freezing all packages...", Toast.LENGTH_SHORT).show();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    String allPkgs = ConfigManager.GOOGLE_PKGS + " " + ConfigManager.BIGME_PKGS + " " + ConfigManager.MTK_PKGS + " " + ConfigManager.AOSP_PKGS;
                                    ShellUtils.execRoot(ConfigManager.buildPmCmd(allPkgs, true));
                                    currentConfig.setProperty("GOOGLE_STACK", "0");
                                    currentConfig.setProperty("BIGME_BLOAT", "0");
                                    currentConfig.setProperty("MTK_CELLULAR", "0");
                                    currentConfig.setProperty("AOSP_STUBS", "0");
                                    ConfigManager.saveConfig(currentConfig);
                                    cachedAppItems = null;
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(MainActivity.this, "All packages frozen!", Toast.LENGTH_LONG).show();
                                            renderAppDebloat();
                                        }
                                    });
                                }
                            }).start();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            }
        });
        quickRow.addView(freezeAll);

        Button unfreezeAll = new Button(this);
        LinearLayout.LayoutParams ualp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        unfreezeAll.setLayoutParams(ualp);
        unfreezeAll.setText("UNFREEZE ALL");
        unfreezeAll.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        unfreezeAll.setTextSize(11);
        unfreezeAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Unfreezing all packages...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String allPkgs = ConfigManager.GOOGLE_PKGS + " " + ConfigManager.BIGME_PKGS + " " + ConfigManager.MTK_PKGS + " " + ConfigManager.AOSP_PKGS;
                        ShellUtils.execRoot(ConfigManager.buildPmCmd(allPkgs, false));
                        currentConfig.setProperty("GOOGLE_STACK", "1");
                        currentConfig.setProperty("BIGME_BLOAT", "1");
                        currentConfig.setProperty("MTK_CELLULAR", "1");
                        currentConfig.setProperty("AOSP_STUBS", "1");
                        ConfigManager.saveConfig(currentConfig);
                        cachedAppItems = null;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "All packages unfrozen.", Toast.LENGTH_LONG).show();
                                renderAppDebloat();
                            }
                        });
                    }
                }).start();
            }
        });
        quickRow.addView(unfreezeAll);

        Button restrictUser = new Button(this);
        LinearLayout.LayoutParams rualp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        rualp.setMargins(6, 0, 0, 0);
        restrictUser.setLayoutParams(rualp);
        restrictUser.setText("RESTRICT USR");
        restrictUser.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        restrictUser.setTextSize(11);
        restrictUser.setBackgroundColor(Color.WHITE);
        restrictUser.setTextColor(Color.BLACK);
        restrictUser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Restrict Third-Party User Apps?")
                    .setMessage("Set all non-protected user apps to Standby Bucket RARE (40) and silence background wakelocks/alarms.\n\nProtected apps (like KOReader) will not be affected.")
                    .setPositiveButton("RESTRICT", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            Toast.makeText(MainActivity.this, "Restricting user apps...", Toast.LENGTH_SHORT).show();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    if (cachedAppItems != null) {
                                        java.util.Set<String> restricted = ConfigManager.loadRestrictedPkgs();
                                        for (AppItem it : cachedAppItems) {
                                            if (!it.isSystem && !it.isProt && it.isEnabled) {
                                                ShellUtils.execRoot(ConfigManager.buildRestrictCmd(it.pkg));
                                                restricted.add(it.pkg);
                                            }
                                        }
                                        ConfigManager.saveRestrictedPkgs(restricted);
                                    }
                                    cachedAppItems = null;
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(MainActivity.this, "User apps restricted!", Toast.LENGTH_LONG).show();
                                            renderAppDebloat();
                                        }
                                    });
                                }
                            }).start();
                        }
                    })
                    .setNegativeButton("CANCEL", null)
                    .show();
            }
        });
        quickRow.addView(restrictUser);
        contentContainer.addView(quickRow);

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        final PackageManager pm = getPackageManager();

        // Filter chips bar
        final LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setPadding(0, 0, 0, 8);

        filterBar.addView(createFilterButton("ALL", listContainer, pm, protected_pkgs, filterBar));
        filterBar.addView(createFilterButton("USER", listContainer, pm, protected_pkgs, filterBar));
        filterBar.addView(createFilterButton("SYSTEM", listContainer, pm, protected_pkgs, filterBar));
        filterBar.addView(createFilterButton("FROZEN", listContainer, pm, protected_pkgs, filterBar));
        filterBar.addView(createFilterButton("RSTR", listContainer, pm, protected_pkgs, filterBar));
        filterBar.addView(createFilterButton("PROT", listContainer, pm, protected_pkgs, filterBar));
        contentContainer.addView(filterBar);

        // Search & Sort bar
        LinearLayout searchSortRow = new LinearLayout(this);
        searchSortRow.setOrientation(LinearLayout.HORIZONTAL);
        searchSortRow.setGravity(Gravity.CENTER_VERTICAL);
        searchSortRow.setPadding(8, 0, 8, 8);

        final EditText searchBox = new EditText(this);
        searchBox.setHint("SEARCH...");
        searchBox.setTypeface(Typeface.MONOSPACE);
        searchBox.setTextSize(11);
        searchBox.setPadding(12, 10, 12, 10);
        searchBox.setBackgroundColor(Color.WHITE);
        searchBox.setText(debloatSearch);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        searchBox.setLayoutParams(slp);
        searchSortRow.addView(searchBox);

        final Button sortBtn = new Button(this);
        sortBtn.setText("SORT: " + getSortName(debloatSortMode));
        sortBtn.setTextSize(10);
        sortBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.setMargins(6, 0, 0, 0);
        sortBtn.setLayoutParams(stlp);
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] opts = {"Alphabetical (A - Z)", "Alphabetical (Z - A)", "Status (Frozen First)", "Status (Active First)", "Protected Apps First"};
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Sort Package List")
                    .setItems(opts, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            debloatSortMode = which;
                            sortBtn.setText("SORT: " + getSortName(debloatSortMode));
                            renderDebloatList(listContainer, pm, protected_pkgs);
                        }
                    }).show();
            }
        });
        searchSortRow.addView(sortBtn);
        contentContainer.addView(searchSortRow);

        contentContainer.addView(listContainer);

        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                debloatSearch = s.toString();
                renderDebloatList(listContainer, pm, protected_pkgs);
            }
            public void afterTextChanged(android.text.Editable s) {}
        });

        if (cachedAppItems == null) {
            final TextView loading = new TextView(this);
            loading.setText("Loading packages...");
            loading.setTypeface(Typeface.MONOSPACE);
            listContainer.addView(loading);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                    java.util.Set<String> restrictedPkgs = ConfigManager.loadRestrictedPkgs();

                    // Parse dumpsys usagestats live for Standby Buckets
                    final java.util.Map<String, Integer> bucketMap = new java.util.HashMap<String, Integer>();
                    ShellUtils.CommandResult usgRes = ShellUtils.exec("dumpsys usagestats | grep -E 'package=.*bucket='");
                    if (usgRes.isSuccess() && usgRes.stdout != null) {
                        for (String line : usgRes.stdout.split("\n")) {
                            int pkgIdx = line.indexOf("package=");
                            int bktIdx = line.indexOf("bucket=");
                            if (pkgIdx != -1 && bktIdx != -1) {
                                int pkgEnd = line.indexOf(' ', pkgIdx);
                                int bktEnd = line.indexOf(' ', bktIdx);
                                if (pkgEnd != -1) {
                                    String p = line.substring(pkgIdx + 8, pkgEnd).trim();
                                    String bStr = (bktEnd != -1) ? line.substring(bktIdx + 7, bktEnd).trim() : line.substring(bktIdx + 7).trim();
                                    try {
                                        bucketMap.put(p, Integer.parseInt(bStr));
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    // Parse dumpsys deviceidle whitelist live for Doze exemptions
                    final java.util.Set<String> dozeWhitelist = new java.util.HashSet<String>();
                    ShellUtils.CommandResult dozeRes = ShellUtils.exec("dumpsys deviceidle whitelist");
                    if (dozeRes.isSuccess() && dozeRes.stdout != null) {
                        for (String line : dozeRes.stdout.split("\n")) {
                            String[] parts = line.split(",");
                            if (parts.length >= 2) {
                                dozeWhitelist.add(parts[1].trim());
                            }
                        }
                    }

                    final List<AppItem> items = new ArrayList<AppItem>();
                    for (ApplicationInfo app : apps) {
                        String label = app.loadLabel(pm).toString();
                        boolean isProt = protected_pkgs.contains(app.packageName);
                        boolean isSys = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        boolean isRestr = restrictedPkgs.contains(app.packageName);
                        int bkt = bucketMap.containsKey(app.packageName) ? bucketMap.get(app.packageName) : (app.enabled ? 10 : 50);
                        boolean isDoze = dozeWhitelist.contains(app.packageName);
                        items.add(new AppItem(app, label, app.packageName, isProt, app.enabled, isSys, isRestr, bkt, isDoze));
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            cachedAppItems = items;
                            renderDebloatList(listContainer, pm, protected_pkgs);
                        }
                    });
                }
            }).start();
        } else {
            renderDebloatList(listContainer, pm, protected_pkgs);
        }
    }

    private void renderDebloatList(final LinearLayout listContainer, final PackageManager pm, final List<String> protected_pkgs) {
        if (cachedAppItems == null) return;
        listContainer.removeAllViews();

        List<AppItem> filtered = new ArrayList<AppItem>();
        String q = debloatSearch == null ? "" : debloatSearch.trim().toLowerCase();

        for (AppItem item : cachedAppItems) {
            if ("USER".equals(debloatFilter) && item.isSystem) continue;
            if ("SYSTEM".equals(debloatFilter) && !item.isSystem) continue;
            if ("FROZEN".equals(debloatFilter) && item.isEnabled) continue;
            if ("PROT".equals(debloatFilter) && !item.isProt) continue;

            if (!q.isEmpty()) {
                if (!item.pkg.toLowerCase().contains(q) && !item.label.toLowerCase().contains(q)) {
                    continue;
                }
            }
            filtered.add(item);
        }

        Collections.sort(filtered, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                switch (debloatSortMode) {
                    case 0: // A-Z
                        return a.label.compareToIgnoreCase(b.label);
                    case 1: // Z-A
                        return b.label.compareToIgnoreCase(a.label);
                    case 2: // Frozen first
                        if (a.isEnabled != b.isEnabled) return a.isEnabled ? 1 : -1;
                        return a.label.compareToIgnoreCase(b.label);
                    case 3: // Active first
                        if (a.isEnabled != b.isEnabled) return a.isEnabled ? -1 : 1;
                        return a.label.compareToIgnoreCase(b.label);
                    case 4: // Prot first
                        if (a.isProt != b.isProt) return a.isProt ? -1 : 1;
                        return a.label.compareToIgnoreCase(b.label);
                    default:
                        return a.label.compareToIgnoreCase(b.label);
                }
            }
        });

        TextView hdr = new TextView(this);
        hdr.setText(filtered.size() + "/" + cachedAppItems.size() + " PKGS [" + debloatFilter + "]");
        hdr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hdr.setTextSize(10);
        hdr.setTextColor(Color.BLACK);
        hdr.setBackgroundColor(Color.WHITE);
        hdr.setPadding(16, 6, 16, 6);
        listContainer.addView(hdr);

        for (final AppItem item : filtered) {
            final String pkg = item.pkg;
            final boolean isProt = item.isProt;
            final boolean isEnabled = item.isEnabled;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(8, 3, 8, 3);
            row.setLayoutParams(rlp);

            // Frozen rows are inverted (white on black)
            if (!isEnabled) {
                row.setBackgroundColor(Color.BLACK);
            } else {
                row.setBackgroundColor(Color.WHITE);
            }

            // Top row: App Name + Status Badges
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            topRow.setPadding(12, 8, 12, 2);

            TextView pName = new TextView(this);
            pName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            pName.setText(item.label);
            pName.setTextSize(12);
            pName.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            pName.setTextColor(isEnabled ? Color.BLACK : Color.WHITE);
            topRow.addView(pName);

            // Badges container
            LinearLayout badges = new LinearLayout(this);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            badges.setGravity(Gravity.CENTER_VERTICAL);

            // Active / Frozen badge
            TextView stateBadge = new TextView(this);
            stateBadge.setText(isEnabled ? "[ACTIVE]" : "[FROZEN]");
            stateBadge.setTextSize(10);
            stateBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            stateBadge.setTextColor(isEnabled ? Color.BLACK : Color.WHITE);
            stateBadge.setPadding(4, 0, 4, 0);
            badges.addView(stateBadge);

            // Standby Bucket badge
            TextView bktBadge = new TextView(this);
            String bLabel = ConfigManager.getBucketLabel(item.standbyBucket);
            bktBadge.setText("[" + bLabel + "]");
            bktBadge.setTextSize(10);
            bktBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            bktBadge.setTextColor(isEnabled ? Color.BLACK : Color.WHITE);
            bktBadge.setPadding(4, 0, 4, 0);
            badges.addView(bktBadge);

            if (item.isRestricted) {
                TextView rBadge = new TextView(this);
                rBadge.setText("[RSTR]");
                rBadge.setTextSize(10);
                rBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                rBadge.setTextColor(isEnabled ? Color.BLACK : Color.WHITE);
                rBadge.setPadding(4, 0, 4, 0);
                badges.addView(rBadge);
            }

            if (item.isDozeExempt) {
                TextView dzBadge = new TextView(this);
                dzBadge.setText("[EXEMPT]");
                dzBadge.setTextSize(10);
                dzBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                dzBadge.setTextColor(isEnabled ? Color.BLACK : Color.WHITE);
                dzBadge.setPadding(4, 0, 4, 0);
                badges.addView(dzBadge);
            }

            topRow.addView(badges);
            row.addView(topRow);

            // Bottom row: Package Name + Action Buttons
            LinearLayout botRow = new LinearLayout(this);
            botRow.setOrientation(LinearLayout.HORIZONTAL);
            botRow.setGravity(Gravity.CENTER_VERTICAL);
            botRow.setPadding(12, 2, 12, 8);

            TextView pPkg = new TextView(this);
            pPkg.setText(pkg);
            pPkg.setTextSize(9);
            pPkg.setTypeface(Typeface.MONOSPACE);
            pPkg.setTextColor(isEnabled ? Color.BLACK : Color.WHITE);
            pPkg.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            botRow.addView(pPkg);

            // Quick Freeze/Unfreeze button
            if (!isProt) {
                final Button quickBtn = new Button(this);
                quickBtn.setText(isEnabled ? "FREEZE" : "UNFREEZE");
                quickBtn.setTextSize(9);
                quickBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                quickBtn.setPadding(8, 4, 8, 4);
                quickBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        quickBtn.setEnabled(false);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                if (isEnabled) {
                                    ShellUtils.execRoot("pm disable-user --user 0 " + pkg + " 2>/dev/null");
                                } else {
                                    ShellUtils.execRoot("pm enable " + pkg + " 2>/dev/null");
                                }
                                cachedAppItems = null;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, (isEnabled ? "Froze " : "Unfroze ") + pkg, Toast.LENGTH_SHORT).show();
                                        renderAppDebloat();
                                    }
                                });
                            }
                        }).start();
                    }
                });
                botRow.addView(quickBtn);
            } else {
                TextView protBadge = new TextView(this);
                protBadge.setText("[PROT]");
                protBadge.setTextSize(9);
                protBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                protBadge.setTextColor(Color.WHITE);
                protBadge.setBackgroundColor(Color.BLACK);
                protBadge.setPadding(6, 2, 6, 2);
                botRow.addView(protBadge);
            }

            // Dropdown menu button: [OPTIONS ▾]
            final Button optBtn = new Button(this);
            optBtn.setText("OPTIONS ▾");
            optBtn.setTextSize(9);
            optBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            optBtn.setPadding(8, 4, 8, 4);
            optBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAppActionDialog(item, pm, protected_pkgs, listContainer);
                }
            });
            botRow.addView(optBtn);

            row.addView(botRow);

            // Tapping card opens the options menu as well
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAppActionDialog(item, pm, protected_pkgs, listContainer);
                }
            });

            listContainer.addView(row);
        }
    }

    private void showAppActionDialog(final AppItem item, final PackageManager pm, final List<String> protected_pkgs, final LinearLayout listContainer) {
        final String pkg = item.pkg;
        final boolean isEnabled = item.isEnabled;
        final boolean isProt = item.isProt;

        List<String> options = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();

        // Action 0: Freeze / Unfreeze
        if (!isProt) {
            options.add(isEnabled ? "❄️ FREEZE APP (pm disable)" : "☀️ UNFREEZE APP (pm enable)");
            actions.add(0);
        }

        // Action 1: Restrict AppOps
        options.add(item.isRestricted ? "🔓 UNRESTRICT APPOPS (Allow background)" : "🔒 RESTRICT APPOPS (Silence wakelocks/alarms)");
        actions.add(1);

        // Action 2: Standby Bucket
        options.add("⏱️ SET STANDBY BUCKET: [" + ConfigManager.getBucketLabel(item.standbyBucket) + "] ▾");
        actions.add(2);

        // Action 3: Doze Whitelist
        options.add(item.isDozeExempt ? "🔋 REMOVE DOZE EXEMPTION (Optimize battery)" : "🔋 EXEMPT FROM DOZE (Allow background sync)");
        actions.add(3);

        // Action 4: Launch App
        options.add("🚀 RUN / LAUNCH APP");
        actions.add(4);

        // Action 5: Details
        options.add("ℹ️ VIEW LIVE APP DETAILS");
        actions.add(5);

        String[] optArr = options.toArray(new String[0]);

        new AlertDialog.Builder(this)
            .setTitle(item.label)
            .setItems(optArr, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    int act = actions.get(which);
                    handleAppAction(act, item, pm, protected_pkgs, listContainer);
                }
            })
            .setNegativeButton("CANCEL", null)
            .show();
    }

    private void handleAppAction(int act, final AppItem item, final PackageManager pm, final List<String> protected_pkgs, final LinearLayout listContainer) {
        final String pkg = item.pkg;
        switch (act) {
            case 0: // Freeze / Unfreeze
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (item.isEnabled) {
                            ShellUtils.execRoot("pm disable-user --user 0 " + pkg + " 2>/dev/null");
                        } else {
                            ShellUtils.execRoot("pm enable " + pkg + " 2>/dev/null");
                        }
                        cachedAppItems = null;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, (item.isEnabled ? "Froze " : "Unfroze ") + pkg, Toast.LENGTH_SHORT).show();
                                renderAppDebloat();
                            }
                        });
                    }
                }).start();
                break;

            case 1: // Restrict AppOps
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        java.util.Set<String> restricted = ConfigManager.loadRestrictedPkgs();
                        if (item.isRestricted) {
                            ShellUtils.execRoot(ConfigManager.buildUnrestrictCmd(pkg));
                            restricted.remove(pkg);
                        } else {
                            ShellUtils.execRoot(ConfigManager.buildRestrictCmd(pkg));
                            restricted.add(pkg);
                        }
                        ConfigManager.saveRestrictedPkgs(restricted);
                        cachedAppItems = null;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, (item.isRestricted ? "Unrestricted " : "Restricted ") + item.label, Toast.LENGTH_SHORT).show();
                                renderAppDebloat();
                            }
                        });
                    }
                }).start();
                break;

            case 2: // Set Standby Bucket
                showStandbyBucketPicker(item, pm, protected_pkgs, listContainer);
                break;

            case 3: // Toggle Doze Exemption
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ShellUtils.execRoot(ConfigManager.buildDozeWhitelistCmd(pkg, !item.isDozeExempt));
                        cachedAppItems = null;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, (!item.isDozeExempt ? "Exempted " : "Unexempted ") + item.label, Toast.LENGTH_SHORT).show();
                                renderAppDebloat();
                            }
                        });
                    }
                }).start();
                break;

            case 4: // Run/Launch
                if (!item.isEnabled) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ShellUtils.execRoot("pm enable " + pkg + " 2>/dev/null");
                            Intent launch = pm.getLaunchIntentForPackage(pkg);
                            if (launch != null) startActivity(launch);
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("Temporary Run: " + item.label)
                                        .setMessage("App was enabled and launched.\nWhen finished, tap STOP & RE-FREEZE.")
                                        .setCancelable(false)
                                        .setPositiveButton("STOP & RE-FREEZE", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int which) {
                                                new Thread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        ShellUtils.execRoot("am force-stop " + pkg + " 2>/dev/null; pm disable-user --user 0 " + pkg + " 2>/dev/null");
                                                        cachedAppItems = null;
                                                        mainHandler.post(new Runnable() {
                                                            @Override
                                                            public void run() { renderAppDebloat(); }
                                                        });
                                                    }
                                                }).start();
                                            }
                                        })
                                        .setNegativeButton("LEAVE ACTIVE", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int which) {
                                                cachedAppItems = null;
                                                renderAppDebloat();
                                            }
                                        })
                                        .show();
                                }
                            });
                        }
                    }).start();
                } else {
                    Intent launch = pm.getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        startActivity(launch);
                    } else {
                        Toast.makeText(MainActivity.this, "No launchable activity found for " + pkg, Toast.LENGTH_SHORT).show();
                    }
                }
                break;

            case 5: // View Details
                showAppDetailsDialog(item);
                break;
        }
    }

    private void showStandbyBucketPicker(final AppItem item, final PackageManager pm, final List<String> protected_pkgs, final LinearLayout listContainer) {
        final String[] buckets = new String[] {
            "ACTIVE (10) — Unrestricted",
            "WORKING SET (20) — Active in recent hours",
            "FREQUENT (30) — Used regularly, light delay",
            "RARE (40) — Throttled to 24h jobs & delayed alarms",
            "RESTRICTED (45) — Silenced in background"
        };
        final String[] bucketCodes = new String[] { "active", "working_set", "frequent", "rare", "restricted" };

        new AlertDialog.Builder(this)
            .setTitle("STANDBY BUCKET: " + item.label)
            .setItems(buckets, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final String bCode = bucketCodes[which];
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ShellUtils.execRoot(ConfigManager.buildSetStandbyBucketCmd(item.pkg, bCode));
                            cachedAppItems = null;
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, item.label + " -> " + bCode.toUpperCase(), Toast.LENGTH_SHORT).show();
                                    renderAppDebloat();
                                }
                            });
                        }
                    }).start();
                }
            })
            .setNegativeButton("CANCEL", null)
            .show();
    }

    private void showAppDetailsDialog(AppItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("Package: ").append(item.pkg).append("\n");
        sb.append("UID: ").append(item.app.uid).append("\n");
        sb.append("Type: ").append(item.isSystem ? "System App" : "User App").append("\n");
        sb.append("Status: ").append(item.isEnabled ? "ACTIVE" : "FROZEN (Disabled)").append("\n");
        sb.append("Standby Bucket: ").append(ConfigManager.getBucketLabel(item.standbyBucket)).append(" (code: ").append(item.standbyBucket).append(")\n");
        sb.append("Doze Exemption: ").append(item.isDozeExempt ? "EXEMPT (Whitelisted)" : "STANDARD (Optimized)").append("\n");
        sb.append("AppOps Restricted: ").append(item.isRestricted ? "YES (Background Silenced)" : "NO").append("\n");
        sb.append("Protected from Freeze: ").append(item.isProt ? "YES" : "NO");

        new AlertDialog.Builder(this)
            .setTitle(item.label)
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 3: BATTERY / DIAGNOSTICS
    // ─────────────────────────────────────────────────────────────────────────
    private void renderDiagnostics() {
        addSectionHeader("LIVE POWER & BATTERY");

        // Big inverted current & battery card
        final LinearLayout powerCard = new LinearLayout(this);
        powerCard.setOrientation(LinearLayout.VERTICAL);
        powerCard.setBackgroundColor(Color.BLACK);
        powerCard.setPadding(20, 20, 20, 20);
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcLp.setMargins(8, 4, 8, 8);
        powerCard.setLayoutParams(pcLp);

        final TextView currentBox = new TextView(this);
        currentBox.setTextSize(26);
        currentBox.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        currentBox.setTextColor(Color.WHITE);
        currentBox.setText("-- mA");
        powerCard.addView(currentBox);

        final TextView batteryDetailsBox = new TextView(this);
        batteryDetailsBox.setTextSize(11);
        batteryDetailsBox.setTypeface(Typeface.MONOSPACE);
        batteryDetailsBox.setTextColor(Color.LTGRAY);
        batteryDetailsBox.setPadding(0, 4, 0, 8);
        batteryDetailsBox.setText("VOLTAGE: -- V  |  TEMP: -- °C");
        powerCard.addView(batteryDetailsBox);

        final TextView gaugeBox = new TextView(this);
        gaugeBox.setTextSize(12);
        gaugeBox.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        gaugeBox.setTextColor(Color.WHITE);
        gaugeBox.setPadding(0, 4, 0, 4);
        gaugeBox.setText("BATTERY: ??%  ??????....");
        powerCard.addView(gaugeBox);

        final TextView projBox = new TextView(this);
        projBox.setTextSize(10);
        projBox.setTypeface(Typeface.MONOSPACE);
        projBox.setTextColor(Color.LTGRAY);
        projBox.setPadding(0, 6, 0, 0);
        powerCard.addView(projBox);

        contentContainer.addView(powerCard);

        addSectionHeader("LIVE CPU & HARDWARE STATUS");

        // Monospace CPU status card
        final LinearLayout cpuCard = new LinearLayout(this);
        cpuCard.setOrientation(LinearLayout.VERTICAL);
        cpuCard.setBackgroundColor(Color.WHITE);
        cpuCard.setPadding(16, 16, 16, 16);
        LinearLayout.LayoutParams cpuCardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cpuCardLp.setMargins(8, 4, 8, 8);
        cpuCard.setLayoutParams(cpuCardLp);

        final TextView cpuBox = new TextView(this);
        cpuBox.setTextSize(11);
        cpuBox.setTypeface(Typeface.MONOSPACE);
        cpuBox.setTextColor(Color.BLACK);
        cpuBox.setText("Querying CPU & hardware status...");
        cpuCard.addView(cpuBox);

        View cpuBorder = new View(this);
        cpuBorder.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));
        cpuBorder.setBackgroundColor(Color.BLACK);
        cpuCard.addView(cpuBorder);

        contentContainer.addView(cpuCard);

        // Live 3-second polling
        diagBatteryRunning = true;
        diagBatteryUpdater = new Runnable() {
            @Override
            public void run() {
                if (!diagBatteryRunning) return;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String cmd = "CUR=$(cat /sys/class/power_supply/battery/current_now 2>/dev/null); " +
                                     "CAP=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null); " +
                                     "STA=$(cat /sys/class/power_supply/battery/status 2>/dev/null); " +
                                     "VOLT=$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null); " +
                                     "TEMP=$(cat /sys/class/power_supply/battery/temp 2>/dev/null); " +
                                     "ONLINE=$(cat /sys/devices/system/cpu/online 2>/dev/null); " +
                                     "F0=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq 2>/dev/null); " +
                                     "G0=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null); " +
                                     "F4=$(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq 2>/dev/null); " +
                                     "G4=$(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_governor 2>/dev/null); " +
                                     "PPM=$(cat /proc/ppm/policy_status 2>/dev/null | grep PPM_POLICY_USER_LIMIT); " +
                                     "PID=$(cat /data/local/tmp/autoshutdown.pid 2>/dev/null); " +
                                     "echo \"$CUR|$CAP|$STA|$VOLT|$TEMP|$ONLINE|$F0|$G0|$F4|$G4|$PPM|$PID\"";
                        ShellUtils.CommandResult res = ShellUtils.execRoot(cmd, false);
                        final String out = (res != null && res.stdout != null) ? res.stdout.trim() : "";
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!diagBatteryRunning) return;
                                updateDiagUi(out, currentBox, batteryDetailsBox, gaugeBox, projBox, cpuBox);
                            }
                        });
                    }
                }).start();
                mainHandler.postDelayed(this, 3000);
            }
        };
        mainHandler.post(diagBatteryUpdater);

        // Sleep test button
        addSectionHeader("DIAGNOSTICS");

        Button sleepTest = new Button(this);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.setMargins(8, 4, 8, 4);
        sleepTest.setLayoutParams(stlp);
        sleepTest.setText("[>>] RUN 10-MIN DEEP SLEEP TEST");
        sleepTest.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sleepTest.setTextSize(11);
        sleepTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ShellUtils.execRoot("nohup sh /data/local/tmp/full_diag.sh 600 > /data/local/tmp/diag.log 2>&1 &");
            }
        });
        contentContainer.addView(sleepTest);

        // Power off button (inverted, danger)
        Button powerOff = new Button(this);
        LinearLayout.LayoutParams polp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        polp.setMargins(8, 8, 8, 8);
        powerOff.setLayoutParams(polp);
        powerOff.setText(">>> POWER OFF (0.00 mA) <<<");
        powerOff.setBackgroundColor(Color.BLACK);
        powerOff.setTextColor(Color.WHITE);
        powerOff.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        powerOff.setTextSize(14);
        powerOff.setPadding(0, 16, 0, 16);
        powerOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(">>> POWER OFF <<<")
                    .setMessage("Shut down to 0.00 mA? E-ink retains current image.")
                    .setPositiveButton(">>> OFF <<<", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            ShellUtils.execRoot("sync && reboot -p");
                        }
                    })
                    .setNegativeButton("CANCEL", null)
                    .show();
            }
        });
        contentContainer.addView(powerOff);
    }

    private void updateDiagUi(String raw, TextView currentBox, TextView batteryDetailsBox,
                              TextView gaugeBox, TextView projBox, TextView cpuBox) {
        if (raw == null || raw.isEmpty()) return;
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 12) return;

        String rawCur = parts[0];
        String cap    = parts[1].isEmpty() ? "?" : parts[1];
        String st     = parts[2].isEmpty() ? "Unknown" : parts[2];
        String volt   = parts[3];
        String temp   = parts[4];
        String online = parts[5].isEmpty() ? "0-7" : parts[5];
        String f0     = parts[6];
        String g0     = parts[7].isEmpty() ? "?" : parts[7];
        String f4     = parts[8];
        String g4     = parts[9].isEmpty() ? "?" : parts[9];
        String ppm    = parts[10];
        String pid    = parts[11];

        int ma = parseCurrent(rawCur);
        boolean isCharging = st.equalsIgnoreCase("Charging") || st.equalsIgnoreCase("Full");
        String curPrefix = isCharging ? "+" : "-";
        currentBox.setText(curPrefix + ma + " mA");

        // Voltage & Temp
        String voltStr = "?";
        try {
            double v = Double.parseDouble(volt) / 1000000.0;
            voltStr = String.format(java.util.Locale.US, "%.2f V", v);
        } catch (Exception e) {}

        String tempStr = "?";
        try {
            double t = Double.parseDouble(temp) / 10.0;
            tempStr = String.format(java.util.Locale.US, "%.1f °C", t);
        } catch (Exception e) {}

        batteryDetailsBox.setText("VOLTAGE: " + voltStr + "  |  TEMP: " + tempStr + "  |  " + st.toUpperCase(java.util.Locale.US));

        // ASCII Battery gauge
        int capInt = 0;
        try { capInt = Integer.parseInt(cap); } catch (Exception e) {}
        int filled = Math.min(10, Math.max(0, capInt / 10));
        int empty = 10 - filled;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < filled; i++) bar.append("█");
        for (int i = 0; i < empty; i++) bar.append(".");
        gaugeBox.setText("BATTERY: " + cap + "%  " + bar.toString() + "  [" + st + "]");

        double hrs = ma > 0 ? (2100.0 / ma) : 0;
        String proj = isCharging
            ? "STATE: CHARGING  |  CAPACITY: " + cap + "%\nPOWER OFF: 0.00 mA (infinite retention)"
            : "ACTIVE RUNTIME: ~" + String.format(java.util.Locale.US, "%.1f", hrs) + " hrs  (" + ma + " mA)\n" +
              "STANDBY: ~2.5 mA (~35 days)  |  POWER OFF: 0.00 mA";
        projBox.setText(proj);

        // CPU & SoC Status
        StringBuilder cpuSb = new StringBuilder();
        int onlineCount = 8;
        if (online.equals("0-3")) onlineCount = 4;
        else if (online.contains("-")) {
            try {
                String[] p = online.split("-");
                onlineCount = Integer.parseInt(p[1]) - Integer.parseInt(p[0]) + 1;
            } catch (Exception e) {}
        }
        cpuSb.append("CORES ONLINE:  ").append(online).append(" (").append(onlineCount).append(" Cores");
        if (onlineCount == 4) cpuSb.append(" - E-Reader Mode");
        cpuSb.append(")\n\n");

        // Cluster 0
        String freq0Str = formatFreq(f0);
        cpuSb.append("CLUSTER 0:     ").append(freq0Str).append("  [").append(g0).append("]\n");

        // Cluster 1
        if (onlineCount <= 4 && !online.contains("4")) {
            cpuSb.append("CLUSTER 1:     [OFFLINE] (Cores 4-7 Hotplugged Off)\n");
        } else {
            String freq4Str = formatFreq(f4);
            cpuSb.append("CLUSTER 1:     ").append(freq4Str).append("  [").append(g4).append("]\n");
        }

        // PPM Policy 7
        boolean uncapped = ppm.toLowerCase(java.util.Locale.US).contains("disabled");
        cpuSb.append("\nPPM FREQ CAP:  ").append(uncapped ? "[UNCAPPED] Policy 7 Disabled" : "[LOCKED] Policy 7 Active").append("\n");

        // Daemon
        boolean daemonOk = !pid.trim().isEmpty();
        String timeoutMin = currentConfig.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120");
        cpuSb.append("AUTO-SHUTDOWN: ").append(daemonOk ? "[ACTIVE] PID " + pid.trim() + " (" + timeoutMin + "m)" : "[INACTIVE]").append("\n");

        cpuBox.setText(cpuSb.toString());
    }

    private String formatFreq(String khz) {
        if (khz == null || khz.trim().isEmpty()) return "?";
        try {
            int k = Integer.parseInt(khz.trim());
            if (k >= 1000000) {
                return String.format(java.util.Locale.US, "%.2f GHz", k / 1000000.0);
            } else {
                return (k / 1000) + " MHz";
            }
        } catch (Exception e) {
            return khz.trim();
        }
    }
}
