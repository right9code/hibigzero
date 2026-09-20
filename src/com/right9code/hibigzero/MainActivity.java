package com.right9code.hibigzero;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public class MainActivity extends Activity {
    private Properties currentConfig;
    private LinearLayout contentContainer;
    private ScrollView mainScrollView;
    private int activeTab = 0;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView headerBatteryView;
    // Banner mode marker: shows DRY RUN when nothing is being applied.
    private TextView authorView;
    private Runnable headerBatteryUpdater;

    /** HiBreak Li-ion pack capacity per spec sheet (used for runtime projections). */
    private static final double BATTERY_CAPACITY_MAH = 2100.0;
    private Runnable diagBatteryUpdater;
    private boolean diagBatteryRunning = false;
    private boolean diagBatteryPaused = false;
    private String debloatFilter = "ALL";
    private int debloatSortMode = 0;
    private String debloatSearch = "";
    private Runnable searchDebounceRunnable = null;
    // Refreshes the CHARGE_LIMIT state line after the controller has acted. Set up
    // when the SYSTEM tab renders; null when the device has no charge switch.
    private Runnable chargeLimitStateRefresh = null;
    private List<AppItem> cachedAppItems = null;
    // Rows are appended to the package list in slices of this size so one UI-thread
    // task never carries the whole list (220 rows measured ~610 ms in a single pass).
    private static final int DEBLOAT_ROWS_PER_CHUNK = 20;
    // Bumped on every render; a chunk that wakes up with a stale value is dropped.
    private int debloatRenderGeneration = 0;
    private boolean logDrawerVisible = false;
    private TextView logDrawerView = null;   // live log view, hosted in the banner popup
    private AlertDialog logDialog = null;    // currently-open log popup (if any)

    // Last self-protection probe result (uid|bgOp|dozeExempt|processLimit) + its
    // timestamp. Probed once on launch and on demand — never on a timer.
    private volatile String selfProtectRaw = null;
    private TextView protectStatusView = null;
    private Button tabSysBtn, tabAppsBtn, tabDiagBtn;
    private View tabSysIndicator, tabAppsIndicator, tabDiagIndicator;

    // Helper: apply system-scaled sp text size (respects user font scale)
    private void setSp(TextView tv, float sp) {
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
    }

    // Helper: dp to pixel converter
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // Helper: force strict 1-bit button styling (no system grey drawable)
    private void styleEinkButton(Button btn, boolean inverted) {
        btn.setBackground(createEinkDrawable(
            inverted ? Color.BLACK : Color.WHITE,
            Color.BLACK, inverted ? 0 : 2, 0));
        btn.setTextColor(inverted ? Color.WHITE : Color.BLACK);
    }

    // Helper: High-contrast 1-bit E-ink drawables with crisp borders
    private GradientDrawable createEinkDrawable(int bgColor, int strokeColor, int strokeWidthDp, int cornerRadiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        if (strokeWidthDp > 0) {
            int strokePx = Math.max(1, dpToPx(strokeWidthDp));
            gd.setStroke(strokePx, strokeColor);
        }
        if (cornerRadiusDp > 0) {
            gd.setCornerRadius(dpToPx(cornerRadiusDp));
        }
        return gd;
    }

    static class AppItem {
        final ApplicationInfo app;
        final String label;
        final String pkg;
        final boolean isProt;
        boolean isEnabled;
        final boolean isSystem;
        boolean isRestricted;
        int standbyBucket;
        boolean isDozeExempt;
        boolean isSystemExcidle;
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
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        currentConfig = ConfigManager.loadConfig();
        if (getIntent() != null) activeTab = getIntent().getIntExtra("tab", 0);

        // Re-arm the charge ceiling. Boot and charger-attach already do this, but
        // after an app update neither has happened yet, so opening the app is the
        // third trigger. It resumes charging when the feature is off.
        ChargeLimitController.applyConfig(this);

        // Ensure log file is writable + re-assert our own background rights.
        new Thread(new Runnable() {
            @Override
            public void run() {
                ShellUtils.execRoot("touch " + ShellUtils.LOG_PATH + " && chmod 666 " + ShellUtils.LOG_PATH + " 2>/dev/null");
                ShellUtils.execRoot("appops set com.right9code.hibigzero SYSTEM_ALERT_WINDOW allow 2>/dev/null; " +
                    "pm grant com.right9code.hibigzero android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null");
                // One-shot self-protection probe (repairs too, if needed) so the
                // PROTECTION card can show a real state without polling.
                selfProtectRaw = ShellUtils.execRoot(ConfigManager.buildSelfCheckAndFixCmd(), false).stdout.trim();
                // Warm the charge-switch probe. It needs a root shell, and doing it
                // here keeps that spawn off the UI thread when the card renders.
                ConfigManager.isChargeLimitAvailable();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (protectStatusView != null) {
                            protectStatusView.setText(formatSelfStatus(selfProtectRaw));
                        }
                    }
                });
                ShellUtils.appendLog("HiBreak Manager v2.0 launched (right9code)");
            }
        }).start();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ── Header (compact, responsive two-line status strip) ─────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        header.setBackgroundColor(Color.BLACK);

        // ── Top row: title + compact, muted log trigger (top-right) ────────
        LinearLayout headerTop = new LinearLayout(this);
        headerTop.setOrientation(LinearLayout.HORIZONTAL);
        headerTop.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("HiBiG ZERO  v" + ConfigManager.getAppVersion(this));
        setSp(title, 15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        headerTop.addView(title);

        Button updateBtn = new Button(this);
        updateBtn.setText("UPDATE");
        updateBtn.setTypeface(Typeface.DEFAULT);
        setSp(updateBtn, 9);
        updateBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, 0));
        updateBtn.setTextColor(Color.BLACK);
        updateBtn.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
        updateBtn.setMinimumHeight(0);
        updateBtn.setMinimumWidth(0);
        updateBtn.setMinWidth(0);
        updateBtn.setMinHeight(0);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.setMarginEnd(dpToPx(6));
        updateBtn.setLayoutParams(ulp);
        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showUpdateDialog();
            }
        });
        headerTop.addView(updateBtn);

        Button logBtn = new Button(this);
        logBtn.setText("LOG");
        logBtn.setTypeface(Typeface.DEFAULT);
        setSp(logBtn, 9);
        logBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, 0));
        logBtn.setTextColor(Color.BLACK);
        logBtn.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
        logBtn.setMinimumHeight(0);
        logBtn.setMinimumWidth(0);
        logBtn.setMinWidth(0);
        logBtn.setMinHeight(0);
        logBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogPopup();
            }
        });
        headerTop.addView(logBtn);
        header.addView(headerTop);

        TextView author = new TextView(this);
        authorView = author;
        author.setText("by right9code");
        setSp(author, 11);
        author.setTypeface(Typeface.DEFAULT);
        author.setTextColor(Color.WHITE);
        author.setPadding(0, dpToPx(1), 0, 0);
        header.addView(author);
        updateDryRunBadge();

        headerBatteryView = new TextView(this);
        headerBatteryView.setText("Battery: reading...");
        setSp(headerBatteryView, 12);
        headerBatteryView.setTypeface(Typeface.DEFAULT);
        headerBatteryView.setTextColor(Color.WHITE);
        headerBatteryView.setPadding(0, dpToPx(2), 0, 0);
        header.addView(headerBatteryView);

        // Boot confirmation
        String lastBoot = ConfigManager.getLastBootTime();
        TextView bootTv = new TextView(this);
        bootTv.setText(lastBoot.isEmpty() ? "[!!] BOOT: not applied yet" : "[OK] BOOT: " + lastBoot);
        setSp(bootTv, 11);
        bootTv.setTypeface(Typeface.DEFAULT);
        bootTv.setTextColor(Color.WHITE);
        bootTv.setPadding(0, dpToPx(2), 0, 0);
        header.addView(bootTv);

        root.addView(header);

        // ── Tab Bar (separated from banner and from each other) ──────────────
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

        tabSysBtn  = createTabButton("SYSTEM", 0);
        tabAppsBtn = createTabButton("DEBLOAT", 1);
        tabDiagBtn = createTabButton("BATTERY", 2);
        tabBar.addView(createTabSlot(tabSysBtn));
        tabBar.addView(createTabSlot(tabAppsBtn));
        tabBar.addView(createTabSlot(tabDiagBtn));
        root.addView(tabBar);

        // Thick divider
        View div = new View(this);
        div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(4)));
        div.setBackgroundColor(Color.BLACK);
        root.addView(div);

        // ── Scrollable content with horizontal swipe page navigation ─────────
        mainScrollView = new ScrollView(this);
        mainScrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        final GestureDetector swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    int jump = Math.max(200, mainScrollView.getHeight() - dpToPx(60));
                    if (diffX < 0) {
                        // Swipe Left -> Next Page (Page Down)
                        mainScrollView.smoothScrollBy(0, jump);
                    } else {
                        // Swipe Right -> Prev Page (Page Up)
                        mainScrollView.smoothScrollBy(0, -jump);
                    }
                    return true;
                }
                return false;
            }
        });
        mainScrollView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                swipeDetector.onTouchEvent(event);
                return false;
            }
        });

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(0, 0, 0, dpToPx(40));
        mainScrollView.addView(contentContainer);
        root.addView(mainScrollView);

        setContentView(root);
        updateTabStyles();
        renderCurrentTab();
        startHeaderBatteryUpdater();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (headerBatteryUpdater != null) mainHandler.removeCallbacks(headerBatteryUpdater);
        diagBatteryRunning = false;
        if (diagBatteryUpdater != null) mainHandler.removeCallbacks(diagBatteryUpdater);
        if (searchDebounceRunnable != null) mainHandler.removeCallbacks(searchDebounceRunnable);
    }

    // ── Auto-shutdown timeout ──────────────────────────────────────────────
    private void applyTimeoutValue(String mins, TextView tVal, TextView logDrawer) {
        currentConfig.setProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", mins);
        ConfigManager.saveConfig(currentConfig);
        if (tVal != null) tVal.setText(mins + " MIN");
        if ("1".equals(currentConfig.getProperty("AUTO_SHUTDOWN_ENABLED", "1"))) {
            // Reschedule alarm with new timeout
            long minsLong = 120;
            try { minsLong = Long.parseLong(mins); } catch (Exception ignored) {}
            ShutdownAlarmReceiver.scheduleAlarm(this, minsLong * 60 * 1000L);
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
        // current_now reports microamps — always convert to milliamps.
        try {
            long v = Long.parseLong(raw.trim().replace("-", ""));
            long ma = Math.round(v / 1000.0);
            return (int) Math.min(ma, Integer.MAX_VALUE);
        } catch (Exception e) { return 0; }
    }

    // ── Tab routing ──────────────────────────────────────────────────────────
    private Button createTabButton(String text, final int index) {
        Button btn = new Button(this);
        btn.setText(text);
        setSp(btn, 12);
        btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btn.setMinHeight(dpToPx(44));
        btn.setPadding(0, dpToPx(10), 0, dpToPx(10));
        btn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, 0));
        btn.setTextColor(Color.BLACK);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { activeTab = index; renderCurrentTab(); }
        });
        return btn;
    }

    // Wraps a tab button with a bottom indicator bar for the selected state.
    private LinearLayout createTabSlot(Button btn) {
        LinearLayout slot = new LinearLayout(this);
        slot.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        slp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        slot.setLayoutParams(slp);
        slot.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View indicator = new View(this);
        indicator.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(3)));
        indicator.setBackgroundColor(Color.TRANSPARENT);
        slot.addView(indicator);

        if (btn == tabSysBtn) tabSysIndicator = indicator;
        else if (btn == tabAppsBtn) tabAppsIndicator = indicator;
        else if (btn == tabDiagBtn) tabDiagIndicator = indicator;
        return slot;
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
        styleTab(tabSysBtn, tabSysIndicator, activeTab == 0);
        styleTab(tabAppsBtn, tabAppsIndicator, activeTab == 1);
        styleTab(tabDiagBtn, tabDiagIndicator, activeTab == 2);
    }

    private void styleTab(Button btn, View indicator, boolean selected) {
        if (btn == null) return;
        btn.setBackground(createEinkDrawable(
            selected ? Color.BLACK : Color.WHITE, Color.BLACK, selected ? 0 : 1, 0));
        btn.setTextColor(selected ? Color.WHITE : Color.BLACK);
        btn.setSelected(selected);
        if (indicator != null) {
            indicator.setBackgroundColor(selected ? Color.BLACK : Color.TRANSPARENT);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 1: SYSTEM CONTROLS
    // ─────────────────────────────────────────────────────────────────────────
    private void renderSystemControls() {
        // ── Live log view (persistent; displayed in the banner popup, not inline) ──
        if (logDrawerView == null) {
            logDrawerView = new TextView(this);
            logDrawerView.setTypeface(Typeface.DEFAULT);
            setSp(logDrawerView, 10);
            logDrawerView.setTextColor(Color.BLACK);
            logDrawerView.setBackgroundColor(Color.WHITE);
            logDrawerView.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        }
        // Re-render on tab switch can discard the view's parent; keep it reusable.
        if (logDrawerView.getParent() != null) {
            ((ViewGroup) logDrawerView.getParent()).removeView(logDrawerView);
        }
        logDrawerView.setVisibility(View.GONE);
        logDrawerView.setText("(log appears after first toggle)");
        final TextView logDrawer = logDrawerView;

        // ─ Section: PROTECTION (self) ─────────────────────────────────────
        // The manager cannot enforce anything if Android reaps it minutes after
        // screen-off, so its own background rights are checked here and repaired
        // on demand. Probed once at launch — deliberately not on a timer.
        addSectionHeader("PROTECTION", "The manager must never restrict itself");
        addProtectionCard(logDrawer);

        // ─ Section: HARDWARE ──────────────────────────────────────────────
        addSectionHeader("HARDWARE");

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



        // ── CAPACITIVE_KEYS: Freeze/Unfreeze ebook.launcher for side-button config ──
        {
            final String capPkg = "com.xrz.ebook.launcher";

            // Card with 2dp solid black border
            final LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
            card.setLayoutParams(clp);
            card.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
            card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

            // Top row: title + pill
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tTitle = new TextView(this);
            tTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            tTitle.setText("CAPACITIVE_KEYS");
            setSp(tTitle, 12);
            tTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            tTitle.setTextColor(Color.BLACK);

            final TextView pillBtn = new TextView(this);
            pillBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(pillBtn, 12);
            pillBtn.setGravity(Gravity.CENTER);
            pillBtn.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));

            topRow.addView(tTitle);
            topRow.addView(pillBtn);
            card.addView(topRow);

            // Description
            TextView descView = new TextView(this);
            setSp(descView, 10);
            descView.setTextColor(Color.BLACK);
            descView.setText("Unfreeze Bigme launcher to configure Custom Keys");
            descView.setPadding(0, dpToPx(4), 0, dpToPx(6));
            card.addView(descView);

            // Button row
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.CENTER_VERTICAL);

            final Button capToggleBtn = new Button(this);
            setSp(capToggleBtn, 10);
            capToggleBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            capToggleBtn.setTextColor(Color.BLACK);
            capToggleBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, dpToPx(2)));
            capToggleBtn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            LinearLayout.LayoutParams togLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            togLp.rightMargin = dpToPx(4);
            capToggleBtn.setLayoutParams(togLp);
            btnRow.addView(capToggleBtn);

            final Button capOpenBtn = new Button(this);
            setSp(capOpenBtn, 10);
            capOpenBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            capOpenBtn.setText("OPEN SETTINGS");
            capOpenBtn.setTextColor(Color.BLACK);
            capOpenBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, dpToPx(2)));
            capOpenBtn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            capOpenBtn.setLayoutParams(openLp);
            btnRow.addView(capOpenBtn);

            card.addView(btnRow);

            // Update UI state
            final Runnable updateCapState = new Runnable() {
                @Override
                public void run() {
                    boolean enabled = false;
                    try {
                        enabled = getPackageManager().getApplicationInfo(capPkg, 0).enabled;
                    } catch (Exception e) { /* not installed */ }
                    if (enabled) {
                        pillBtn.setText("[ ACTIVE ]");
                        pillBtn.setTextColor(Color.BLACK);
                        pillBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                        capToggleBtn.setText("FREEZE");
                        capOpenBtn.setEnabled(true);
                        capOpenBtn.setAlpha(1.0f);
                    } else {
                        pillBtn.setText("[ FROZEN ]");
                        pillBtn.setTextColor(Color.WHITE);
                        pillBtn.setBackgroundColor(Color.BLACK);
                        capToggleBtn.setText("UNFREEZE");
                        capOpenBtn.setEnabled(false);
                        capOpenBtn.setAlpha(0.4f);
                    }
                }
            };
            updateCapState.run();

            capToggleBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean enabled = false;
                    try {
                        enabled = getPackageManager().getApplicationInfo(capPkg, 0).enabled;
                    } catch (Exception e) { /* not installed */ }
                    capToggleBtn.setEnabled(false);
                    final boolean wasEnabled = enabled;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (wasEnabled) {
                                ShellUtils.execRootAction("pm disable-user --user 0 " + capPkg);
                            } else {
                                ShellUtils.execRootAction("pm enable " + capPkg);
                            }
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    capToggleBtn.setEnabled(true);
                                    updateCapState.run();
                                }
                            });
                        }
                    }).start();
                }
            });

            capOpenBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        android.content.Intent intent = new android.content.Intent();
                        intent.setComponent(new android.content.ComponentName(
                            "com.xrz.ebook.launcher",
                            "com.xrz.launcher.settings.CustomMenuActivity"));
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                            "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });

            addSectionContent(card);
        }

        // ── Section: DEBLOAT ───────────────────────────────────────────────
        addSectionHeader("DEBLOAT");

        addDebloatToggle("GOOGLE_STACK", "GOOGLE_STACK", "GOOGLE_PKGS_SEL",
            "Freeze Play Services & GSF (cloud socket wakeups)",
            ConfigManager.GOOGLE_PKGS,
            "pm list packages -d 2>/dev/null | grep -q com.google.android.gms && echo FROZEN || echo ACTIVE",
            logDrawer);

        addDebloatToggle("BIGME_BLOAT", "BIGME_BLOAT", "BIGME_PKGS_SEL",
            "Freeze 15 Bigme AI, cloud, store, demo daemons",
            ConfigManager.BIGME_PKGS,
            "pm list packages -d 2>/dev/null | grep -q com.xrz.ai && echo FROZEN || echo ACTIVE",
            logDrawer);

        addDebloatToggle("MTK_CELLULAR", "MTK_CELLULAR", "MTK_PKGS_SEL",
            "Disable baseband IMS, telephony, sim services",
            ConfigManager.MTK_PKGS,
            "pm list packages -d 2>/dev/null | grep -q com.mediatek.ims && echo FROZEN || echo ACTIVE",
            logDrawer);

        addDebloatToggle("AOSP_STUBS", "AOSP_STUBS", "AOSP_PKGS_SEL",
            "Freeze dialer, telecom, print spooler, MMS provider",
            ConfigManager.AOSP_PKGS,
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

        // ALARM_WAKEUP does not exist on API 34, so this toggle used to do
        // nothing at all. Now it uses ops that exist (verified on device).
        addToggle("SUPPRESS_ALARMS", "SUPPRESS_ALARMS",
            "Deny GMS exact alarms, background execution & wake locks",
            false,
            ConfigManager.getSuppressGmsAlarmsCmd(true),
            ConfigManager.getSuppressGmsAlarmsCmd(false),
            ConfigManager.getSuppressGmsAlarmsProbeCmd(),
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

        // Own config key: this used to write "1"/"0" into GOVERNOR_PROFILE, which
        // clobbered the wake/sleep profile name chosen below.
        addToggle("CPU_OPTIMIZER", "CPU_OPTIMIZER",
            "Let the little cluster reach full 2.2GHz dynamically (clears the PPM hard limit)",
            false,
            ConfigManager.getCpuUncapCmd(),
            ConfigManager.buildGovernorCmd("stock"),
            ConfigManager.getCpuUncapProbeCmd(),
            logDrawer);

        // ── WAKE GOVERNOR selector (screen-on profile)
        LinearLayout wakeRow = new LinearLayout(this);
        wakeRow.setOrientation(LinearLayout.HORIZONTAL);
        wakeRow.setGravity(Gravity.CENTER_VERTICAL);
        wakeRow.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        wakeRow.setBackgroundColor(Color.WHITE);

        TextView wakeLabel = new TextView(this);
        wakeLabel.setText("WAKE GOV: ");
        wakeLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(wakeLabel, 11);
        wakeLabel.setTextColor(Color.BLACK);

        final TextView wakeVal = new TextView(this);
        wakeVal.setText(ConfigManager.getGovernorLabel(currentConfig.getProperty("GOVERNOR_PROFILE", "schedutil_efficient")));
        wakeVal.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(wakeVal, 11);
        wakeVal.setTextColor(Color.BLACK);

        Button changeWake = new Button(this);
        changeWake.setText("[CHANGE]");
        changeWake.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(changeWake, 11);
        changeWake.setBackgroundColor(Color.BLACK);
        changeWake.setTextColor(Color.WHITE);
        changeWake.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] opts = {
                    "Balanced Efficient (900-2200MHz, 8 Cores)",
                    "E-Reader Battery (900-1351MHz, 4 Cores)",
                    "Deep Sleep (900/400MHz Locked, 4 Cores)",
                    "Stock MediaTek (factory 2.06GHz lock)"
                };
                final String[] vals = {"schedutil_efficient", "ereader_battery", "deep_sleep", "stock"};
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("WAKE GOVERNOR (Screen On)")
                    .setItems(opts, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            String selected = vals[which];
                            currentConfig.setProperty("GOVERNOR_PROFILE", selected);
                            // Only the E-Reader profile wants 4 cores offline; any
                            // other profile must clear the latch or buildGovernorCmd()
                            // keeps cores 4-7 offline forever (even for stock/8-core).
                            currentConfig.setProperty("HOTPLUG_4_CORES",
                                "ereader_battery".equals(selected) ? "1" : "0");
                            ConfigManager.saveConfig(currentConfig);
                            wakeVal.setText(ConfigManager.getGovernorLabel(selected));
                            ShellUtils.execRootAction(ConfigManager.buildGovernorCmd(selected, currentConfig.getProperty("HOTPLUG_4_CORES", "0")));
                            ShellUtils.appendLog("Wake Governor set to: " + selected);
                            if (logDrawer.getVisibility() == View.VISIBLE) {
                                logDrawer.setText(ShellUtils.readLog(15));
                            }
                        }
                    }).show();
            }
        });

        wakeRow.addView(wakeLabel);
        wakeRow.addView(wakeVal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        wakeRow.addView(changeWake);
        addSectionContent(wakeRow);

        TextView wakeDesc = new TextView(this);
        wakeDesc.setText("CPU profile applied when screen is ON. Controls performance during active use.");
        setSp(wakeDesc, 10);
        wakeDesc.setTypeface(Typeface.DEFAULT);
        wakeDesc.setTextColor(Color.BLACK);
        wakeDesc.setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(8));
        addSectionContent(wakeDesc);

        // ── SLEEP GOVERNOR selector (screen-off profile)
        LinearLayout sleepRow = new LinearLayout(this);
        sleepRow.setOrientation(LinearLayout.HORIZONTAL);
        sleepRow.setGravity(Gravity.CENTER_VERTICAL);
        sleepRow.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        sleepRow.setBackgroundColor(Color.WHITE);

        TextView sleepLabel = new TextView(this);
        sleepLabel.setText("SLEEP GOV: ");
        sleepLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(sleepLabel, 11);
        sleepLabel.setTextColor(Color.BLACK);

        final TextView sleepVal = new TextView(this);
        sleepVal.setText(ConfigManager.getGovernorLabel(currentConfig.getProperty("SLEEP_GOVERNOR", "ereader_battery")));
        sleepVal.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(sleepVal, 11);
        sleepVal.setTextColor(Color.BLACK);

        Button changeSleep = new Button(this);
        changeSleep.setText("[CHANGE]");
        changeSleep.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(changeSleep, 11);
        changeSleep.setBackgroundColor(Color.BLACK);
        changeSleep.setTextColor(Color.WHITE);
        changeSleep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] opts = {
                    "Deep Sleep (900/400MHz Locked, 4 Cores)",
                    "E-Reader Battery (900-1351MHz, 4 Cores)",
                    "Balanced Efficient (900-2200MHz, 8 Cores)",
                    "Stock MediaTek (factory 2.06GHz lock)"
                };
                final String[] vals = {"deep_sleep", "ereader_battery", "schedutil_efficient", "stock"};
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("SLEEP GOVERNOR (Screen Off)")
                    .setItems(opts, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            String selected = vals[which];
                            currentConfig.setProperty("SLEEP_GOVERNOR", selected);
                            ConfigManager.saveConfig(currentConfig);
                            sleepVal.setText(ConfigManager.getGovernorLabel(selected));
                            ShellUtils.appendLog("Sleep Governor set to: " + selected);
                            if (logDrawer.getVisibility() == View.VISIBLE) {
                                logDrawer.setText(ShellUtils.readLog(15));
                            }
                        }
                    }).show();
            }
        });

        sleepRow.addView(sleepLabel);
        sleepRow.addView(sleepVal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        sleepRow.addView(changeSleep);
        addSectionContent(sleepRow);

        TextView sleepDesc = new TextView(this);
        sleepDesc.setText("CPU profile applied when screen turns OFF. Lower = more battery savings while idle.");
        setSp(sleepDesc, 10);
        sleepDesc.setTypeface(Typeface.DEFAULT);
        sleepDesc.setTextColor(Color.BLACK);
        sleepDesc.setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(8));
        addSectionContent(sleepDesc);

        // ── SLEEP GOVERNOR AUTO-TOGGLE (enable/disable auto-switching)
        addToggle("SLEEP GOV AUTO", "SLEEP_GOVERNOR_ENABLED",
            "Auto-switch between Wake/Sleep profiles on screen on/off. Disable to use manual governor only.",
            false,
            "",
            "",
            "dumpsys activity services com.right9code.hibigzero 2>/dev/null | grep -q 'HiBigApp' && echo ACTIVE || echo CHECKING",
            logDrawer);

        addToggle("AGGRESSIVE_DOZE", "AGGRESSIVE_DOZE",
            "Enter doze quickly: light idle after 5 min, deep idle after 30 min",
            false,
            ConfigManager.getAggressiveDozeCmd(true),
            ConfigManager.getAggressiveDozeCmd(false),
            "dumpsys deviceidle 2>/dev/null | grep -oE 'mDeepEnabled=[a-z]+' | head -1",
            logDrawer);

        if (ConfigManager.isChargeLimitAvailable()) {
            addToggle("CHARGE_LIMIT", "BATTERY_CAP_85",
                "Stop charging at the target and run off the charger instead of "
                    + "sitting at 100% all night. Only polls while plugged in, and "
                    + "charging is restored the moment the cable comes out.",
                false,
                "", "",
                ConfigManager.getChargeLimitProbeCmd(),
                logDrawer,
                new Runnable() {
                    @Override
                    public void run() {
                        ChargeLimitController.applyConfig(MainActivity.this);
                        if (chargeLimitStateRefresh != null) chargeLimitStateRefresh.run();
                    }
                });

            // Target selector. The resume point is derived 5% below the target;
            // that gap is what stops the switch chattering at the threshold.
            LinearLayout limitRow = new LinearLayout(this);
            limitRow.setOrientation(LinearLayout.HORIZONTAL);
            limitRow.setGravity(Gravity.CENTER_VERTICAL);
            limitRow.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
            limitRow.setBackgroundColor(Color.WHITE);

            TextView lLabel = new TextView(this);
            lLabel.setText("HOLD AT: ");
            lLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(lLabel, 11);
            lLabel.setTextColor(Color.BLACK);

            final TextView lVal = new TextView(this);
            lVal.setText(ConfigManager.getChargeLimitPct() + "%"
                + "  (resume " + ConfigManager.getChargeResumePct() + "%)");
            lVal.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(lVal, 11);
            lVal.setTextColor(Color.BLACK);
            lVal.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            Button changeLimit = new Button(this);
            changeLimit.setText("[CHANGE]");
            changeLimit.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(changeLimit, 11);
            changeLimit.setBackgroundColor(Color.BLACK);
            changeLimit.setTextColor(Color.WHITE);
            changeLimit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String[] opts = {"70%", "75%", "80% (recommended)", "85%", "90%"};
                    final int[] vals = {70, 75, 80, 85, 90};
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("CHARGE CEILING")
                        .setItems(opts, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                currentConfig.setProperty("CHARGE_LIMIT_PCT",
                                    String.valueOf(vals[which]));
                                ConfigManager.saveConfig(currentConfig);
                                lVal.setText(vals[which] + "%  (resume "
                                    + Math.max(50, vals[which] - 5) + "%)");
                                ChargeLimitController.applyConfig(MainActivity.this);
                                if (chargeLimitStateRefresh != null) chargeLimitStateRefresh.run();
                            }
                        }).show();
                }
            });

            limitRow.addView(lLabel);
            limitRow.addView(lVal);
            limitRow.addView(changeLimit);
            addSectionContent(limitRow);

            // Live state line. Reads the switch node, so it reports what the device
            // is doing rather than what the app intended. Updated on render, after a
            // toggle and after a target change - never on a timer, because repainting
            // text on e-ink costs a screen refresh.
            final TextView lState = new TextView(this);
            lState.setText(ChargeLimitController.describe(this));
            setSp(lState, 10);
            lState.setTypeface(Typeface.DEFAULT);
            lState.setTextColor(Color.BLACK);
            lState.setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(8));
            lState.setBackgroundColor(Color.WHITE);
            addSectionContent(lState);

            final Runnable refreshState = new Runnable() {
                @Override
                public void run() {
                    lState.setText(ChargeLimitController.describe(MainActivity.this));
                }
            };
            chargeLimitStateRefresh = new Runnable() {
                @Override
                public void run() {
                    // The controller does its work on a background thread, so give it
                    // a moment before asking the device what it settled on.
                    mainHandler.postDelayed(refreshState, 3000);
                }
            };
        } else {
            addUnavailableToggle("CHARGE_LIMIT",
                "This device exposes no charge-control switch, and unlike the old "
                    + "implementation the app no longer pretends to cap charging: it "
                    + "checked for a charge threshold node and for the MediaTek "
                    + "current_cmd switch, and found neither.");
        }


        addToggle("AUTO_SHUTDOWN", "AUTO_SHUTDOWN_ENABLED",
            "Clean reboot -p after inactivity (E-ink retains at 0 mA). Enabling this "
                + "also disables Bigme's own power-off timer so the two cannot compete.",
            false,
            "", "", "",
            logDrawer,
            new Runnable() {
                @Override
                public void run() {
                    if ("1".equals(currentConfig.getProperty("AUTO_SHUTDOWN_ENABLED", "1"))) {
                        // Our timer is the single authority for power-off: Bigme's own
                        // PowersaveShutDownAlarmReceiver must not race it (it is also a
                        // wakeup source). Written to config so boot re-asserts it.
                        if (!"1".equals(currentConfig.getProperty("KILL_SHUTDOWN_ALARM", "0"))) {
                            currentConfig.setProperty("KILL_SHUTDOWN_ALARM", "1");
                            ConfigManager.saveConfig(currentConfig);
                            ShellUtils.execRootAction(ConfigManager.getKillShutdownAlarmCmd(true));
                            ShellUtils.appendLog("Auto-shutdown ON -> Bigme's own timer disabled");
                        }
                        ShutdownAlarmReceiver.scheduleAlarmWithConfig(MainActivity.this);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() { renderCurrentTab(); }
                        });
                    } else {
                        ShutdownAlarmReceiver.cancelAlarm(MainActivity.this);
                        ShellUtils.appendLog("Auto-shutdown OFF -> Bigme's timer left disabled "
                            + "(turn KILL_SHUTDOWN off to restore it)");
                    }
                }
            });

        // Timeout selector
        LinearLayout timeoutRow = new LinearLayout(this);
        timeoutRow.setOrientation(LinearLayout.HORIZONTAL);
        timeoutRow.setGravity(Gravity.CENTER_VERTICAL);
        timeoutRow.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        timeoutRow.setBackgroundColor(Color.WHITE);

        TextView tLabel = new TextView(this);
        tLabel.setText("TIMEOUT: ");
        tLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(tLabel, 11);
        tLabel.setTextColor(Color.BLACK);

        final TextView tVal = new TextView(this);
        tVal.setText(currentConfig.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120") + " MIN");
        tVal.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(tVal, 11);
        tVal.setTextColor(Color.BLACK);

        Button changeTimer = new Button(this);
        changeTimer.setText("[CHANGE]");
        changeTimer.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(changeTimer, 11);
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
        addSectionContent(timeoutRow);

        addToggle("SKIP_WHILE_CHARGING", "AUTO_SHUTDOWN_SKIP_WHEN_CHARGING",
            "Do not power off while a charger is attached (reschedules instead)",
            false,
            "", "",
            ConfigManager.getConfigProbeCmd("AUTO_SHUTDOWN_SKIP_WHEN_CHARGING", "SKIPPING", "ALLOWED"),
            logDrawer);

        addToggle("SHUTDOWN_TEST_MODE", "AUTO_SHUTDOWN_DRY_RUN",
            "Log the shutdown decision instead of performing it (safe testing)",
            false,
            "", "",
            ConfigManager.getConfigProbeCmd("AUTO_SHUTDOWN_DRY_RUN", "TEST-ONLY", "LIVE"),
            logDrawer);

        // ── Section: SUSPEND (Optimization #3) ─────────────────────────────
        addSectionHeader("SUSPEND",
            "Reduce suspend failures by blocking alarm wakeups during freeze");

        addToggle("KILL_SHUTDOWN", "KILL_SHUTDOWN_ALARM",
            "Disable Bigme PowersaveShutDownAlarmReceiver & Intent Firewall block",
            false,
            ConfigManager.getKillShutdownAlarmCmd(true),
            ConfigManager.getKillShutdownAlarmCmd(false),
            ConfigManager.getBigmeShutdownProbeCmd(),
            logDrawer);

        addToggle("JS_IDLE", "SUPPRESS_JS_IDLE",
            "Cancel JobScheduler idle alarms & raise idle threshold to 99",
            false,
            ConfigManager.getSuppressJsIdleCmd(true),
            ConfigManager.getSuppressJsIdleCmd(false),
            "device_config get jobscheduler min_ready_non_active_jobs_count 2>/dev/null | grep -q 99 && echo THROTTLED || echo DEFAULT",
            logDrawer);

        addToggle("WIDE_FUZZ", "WIDE_ALARM_FUZZ",
            "Widen alarm batching: 10min min fuzz, 45min max fuzz, skip TIME_TICK idle",
            false,
            ConfigManager.getWideAlarmFuzzCmd(true),
            ConfigManager.getWideAlarmFuzzCmd(false),
            "device_config get alarm_manager min_device_idle_fuzz 2>/dev/null | grep -q 600000 && echo WIDE || echo DEFAULT",
            logDrawer);

        addToggle("INSTANT_LOCK", "INSTANT_LOCK",
            "Lock screen instantly on power-off (kills DELAYED_KEYGUARD alarm race)",
            false,
            ConfigManager.getInstantLockCmd(true),
            ConfigManager.getInstantLockCmd(false),
            "settings get secure lock_screen_lock_after_timeout | grep -q 0 && echo INSTANT || echo DELAYED",
            logDrawer);

        // ── DRY RUN (global): plan-only mode ────────────────────────────────
        // Deliberately grouped with the SHUTDOWN_TEST_MODE toggle above, which
        // already established "test mode" as a concept in this app. Unlike that
        // one, this covers every state-changing action, so APPLY ALL can be
        // rehearsed in full.
        addToggle("DRY_RUN", "DRY_RUN",
            "Plan-only mode: every state-changing action is logged instead of "
                + "applied, so a whole rule set can be rehearsed safely. Status "
                + "cards still read the real device state.",
            false,
            "", "",
            ConfigManager.getConfigProbeCmd("DRY_RUN", "NOTHING APPLIED", "LIVE"),
            logDrawer,
            new Runnable() {
                @Override
                public void run() {
                    updateDryRunBadge();
                }
            });

        // ── APPLY ALL button (with confirmation dialog) ─────────────────────
        final Button applyBtn = new Button(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(dpToPx(8), dpToPx(16), dpToPx(8), dpToPx(4));
        applyBtn.setLayoutParams(alp);
        applyBtn.setText(">>> APPLY ALL RULES <<<");
        applyBtn.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, 0));
        applyBtn.setTextColor(Color.WHITE);
        applyBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(applyBtn, 13);
        applyBtn.setPadding(0, dpToPx(16), 0, dpToPx(16));
        applyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("APPLY ALL RULES?")
                    .setMessage("Enforce all configured power management policies, CPU governors, and debloat rules via root?")
                    .setPositiveButton("APPLY", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
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
                    })
                    .setNegativeButton("CANCEL", null)
                    .show();
            }
        });

        // ── Section: APPS ───────────────────────────────────────────────
        addSectionHeader("APPS");
        addAppInstallerCards(logDrawer);

        // ── APPLY ALL (pinned to the very bottom of the SYSTEM tab) ──────
        addSectionContent(applyBtn);
    }

    // ── DRY RUN banner marker ──────────────────────────────────────────────
    /**
     * Puts the plan-only mode into the banner. A mode that silently changes what
     * every button does must be visible at a glance, or it will be left on and every
     * rule will look like it stopped working.
     */
    private void updateDryRunBadge() {
        if (authorView == null) return;
        authorView.setText(ConfigManager.isDryRun()
            ? "by right9code    [ DRY RUN - NOTHING APPLIED ]"
            : "by right9code");
    }

    // ── PROTECTION card: check + self-repair, battery-first ────────────────
    // One root spawn per check. No timers, no polling: the probe runs once at
    // launch and when the user taps [CHECK & FIX].
    private void addProtectionCard(final TextView logDrawer) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        card.setLayoutParams(clp);
        card.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        protectStatusView = new TextView(this);
        setSp(protectStatusView, 11);
        protectStatusView.setTypeface(Typeface.DEFAULT);
        protectStatusView.setTextColor(Color.BLACK);
        protectStatusView.setText(selfProtectRaw == null
            ? "Checking on launch..." : formatSelfStatus(selfProtectRaw));
        card.addView(protectStatusView);

        final Button checkBtn = new Button(this);
        checkBtn.setText("[CHECK & FIX]");
        setSp(checkBtn, 11);
        checkBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        checkBtn.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        styleEinkButton(checkBtn, true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dpToPx(8);
        checkBtn.setLayoutParams(blp);
        checkBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkBtn.setEnabled(false);
                checkBtn.setText("CHECKING...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // Single root spawn: probes, repairs if needed, and reports
                        // the state it found before repairing.
                        final String res = ShellUtils.execRoot(
                            ConfigManager.buildSelfCheckAndFixCmd(), false).stdout.trim();
                        selfProtectRaw = res;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (protectStatusView != null) protectStatusView.setText(formatSelfStatus(res));
                                checkBtn.setEnabled(true);
                                checkBtn.setText("[CHECK & FIX]");
                                if (logDrawer != null) logDrawer.setText(ShellUtils.readLog(15));
                            }
                        });
                    }
                }).start();
            }
        });
        card.addView(checkBtn);

        TextView note = new TextView(this);
        note.setText("Checked once at launch, and only when you tap. No background polling.");
        setSp(note, 9);
        note.setTypeface(Typeface.DEFAULT);
        note.setTextColor(Color.BLACK);
        note.setPadding(0, dpToPx(6), 0, 0);
        card.addView(note);

        addSectionContent(card);
    }

    /** Render a probe result: uid|background_op|doze_exempt_count|process_limit */
    private String formatSelfStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "SELF-PROTECTION\nno probe result — is root granted?";
        }
        String[] p = raw.trim().split("\\|", -1);
        if (p.length < 4) return "SELF-PROTECTION\nunreadable probe result";
        // `id -u` prints 0 for root: uid 0 IS the success case here.
        boolean rootOk = "0".equals(p[0].trim());
        String  uid    = p[0].trim();
        boolean bgOk   = ConfigManager.isBgOpAllowed(p[1]);
        boolean dozeOk = p[2].trim().startsWith("1") || p[2].trim().startsWith("2");
        String limit   = p[3].trim();

        StringBuilder sb = new StringBuilder("SELF-PROTECTION\n");
        sb.append("root        : ").append(rootOk ? "granted (uid 0)" : "NOT granted (uid " + uid + ") - grant root in Magisk").append("\n");
        sb.append("background  : ").append(bgOk ? "allowed (OK)" : "was BLOCKED - repaired").append("\n");
        sb.append("doze exempt : ").append(dozeOk ? "yes (OK)" : "was missing - repaired").append("\n");
        sb.append("cached cap  : ");
        if ("-1".equals(limit))        sb.append("no limit (OK)");
        else if ("0".equals(limit))    sb.append("was 0 - repaired to no limit");
        else if (limit.isEmpty())      sb.append("unknown");
        else                           sb.append(limit).append(" (set by you)");
        return sb.toString();
    }

    /** The manager's own rules must never restrict the manager itself. */
    private boolean isSelfProtected(String pkg) {
        if (ConfigManager.SELF_PKG.equals(pkg)) {
            Toast.makeText(MainActivity.this,
                "HiBig Zero must stay unrestricted - that is what keeps your sleep timer and CPU profiles alive",
                Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    /** Open the live system log as a popup from the banner. */
    private void showLogPopup() {
        if (logDrawerView == null) return;
        // Reuse the persistent log view; detach it from any previous popup first.
        if (logDrawerView.getParent() != null) {
            ((ViewGroup) logDrawerView.getParent()).removeView(logDrawerView);
        }
        logDrawerView.setVisibility(View.VISIBLE);
        logDrawerView.setText(ShellUtils.readLog(30));

        ScrollView scroller = new ScrollView(this);
        scroller.addView(logDrawerView);

        if (logDialog != null && logDialog.isShowing()) logDialog.dismiss();
        logDialog = new AlertDialog.Builder(this)
            .setTitle("-- LIVE SYSTEM LOG --")
            .setView(scroller)
            .setPositiveButton("CLOSE", null)
            .create();
        logDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                logDrawerVisible = false;
                if (logDrawerView != null) logDrawerView.setVisibility(View.GONE);
                logDialog = null;
            }
        });
        logDrawerVisible = true;
        logDialog.show();
    }

    /** Open update dialog and check for latest HiBig Zero release from GitHub. */
    private void showUpdateDialog() {
        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));

        final TextView msgView = new TextView(this);
        setSp(msgView, 11);
        msgView.setTextColor(Color.BLACK);
        msgView.setText("Checking GitHub for HiBig Zero updates...\nTarget: " + Updater.REPO);
        container.addView(msgView);

        // Action button — hidden initially, shown when needed
        final Button actionBtn = new Button(this);
        setSp(actionBtn, 11);
        actionBtn.setTextColor(Color.BLACK);
        actionBtn.setBackgroundColor(Color.WHITE);
        actionBtn.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = dpToPx(12);
        actionBtn.setLayoutParams(btnParams);
        actionBtn.setVisibility(View.GONE);
        container.addView(actionBtn);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(container);

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("HiBig Zero Update")
            .setView(scroller)
            .setNegativeButton("CLOSE", null)
            .create();
        dialog.show();

        Updater.check(this, new Updater.UpdateListener() {
            @Override
            public void onChecking() {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog.isShowing()) {
                            msgView.setText("Connecting to GitHub API...\nChecking releases for " + Updater.REPO);
                        }
                    }
                });
            }

            @Override
            public void onUpToDate(final String currentVersion, final String latestVersion, final Runnable forceInstall) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing()) return;
                        msgView.setText("HiBig Zero is up to date!\n\n" +
                            "* Installed version: v" + currentVersion + "\n" +
                            "* Latest release:  v" + latestVersion);
                        actionBtn.setText("REINSTALL v" + latestVersion);
                        actionBtn.setVisibility(View.VISIBLE);
                        actionBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                actionBtn.setVisibility(View.GONE);
                                msgView.setText("Starting download for v" + latestVersion + "...");
                                forceInstall.run();
                            }
                        });
                    }
                });
            }

            @Override
            public void onUpdateAvailable(final String currentVersion, final String latestVersion,
                                          final String releaseNotes, final Runnable proceed) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing()) return;
                        String notes = releaseNotes.length() > 600
                            ? releaseNotes.substring(0, 600) + "..." : releaseNotes;
                        msgView.setText("New version available!\n\n" +
                            "* Installed: v" + currentVersion + "\n" +
                            "* Latest:    v" + latestVersion + "\n\n" +
                            "Release Notes:\n" + (notes.isEmpty() ? "(No notes)" : notes));
                        actionBtn.setText("UPDATE NOW");
                        actionBtn.setVisibility(View.VISIBLE);
                        actionBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                actionBtn.setVisibility(View.GONE);
                                msgView.setText("Starting download for v" + latestVersion + "...");
                                proceed.run();
                            }
                        });
                    }
                });
            }

            @Override
            public void onProgress(final String message, final int percent) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog.isShowing()) {
                            msgView.setText("Progress: " + message + (percent > 0 ? " (" + percent + "%)" : ""));
                        }
                        if (logDrawerView != null) {
                            logDrawerView.setText("Updater: " + message);
                        }
                    }
                });
            }

            @Override
            public void onCompleted(final boolean success, final String message) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing()) return;
                        msgView.setText((success ? "[SUCCESS]\n\n" : "[FAILED]\n\n") + message);
                        if (success) {
                            actionBtn.setText("RELAUNCH");
                            actionBtn.setVisibility(View.VISIBLE);
                            actionBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    ShellUtils.execRoot("am start -n com.right9code.hibigzero/.MainActivity");
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    // Active target for section content. When null, content goes to contentContainer.
    private LinearLayout sectionTarget;

    private void addSectionHeader(String text) {
        addSectionHeader(text, null);
    }

    // Adds a collapsible section header and routes subsequent addSectionContent calls into its body.
    private void addSectionHeader(String text, String subtitle) {
        View bar = new View(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(4)));
        bar.setBackgroundColor(Color.BLACK);
        contentContainer.addView(bar);

        final LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setBackgroundColor(Color.BLACK);
        headerRow.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));

        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tv.setText(text);
        setSp(tv, 12);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setTextColor(Color.WHITE);
        headerRow.addView(tv);

        final TextView chevron = new TextView(this);
        chevron.setText("[ - ]");
        setSp(chevron, 12);
        chevron.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chevron.setTextColor(Color.WHITE);
        headerRow.addView(chevron);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout bodyWrap = new LinearLayout(this);
        bodyWrap.setOrientation(LinearLayout.VERTICAL);
        bodyWrap.addView(body);

        headerRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean collapse = bodyWrap.getVisibility() == View.VISIBLE;
                bodyWrap.setVisibility(collapse ? View.GONE : View.VISIBLE);
                chevron.setText(collapse ? "[ + ]" : "[ - ]");
            }
        });

        contentContainer.addView(headerRow);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            setSp(sub, 10);
            sub.setTypeface(Typeface.DEFAULT);
            sub.setTextColor(Color.BLACK);
            sub.setPadding(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4));
            body.addView(sub);
        }

        contentContainer.addView(bodyWrap);

        final LinearLayout target = body;
        sectionTarget = target;
    }

    // Adds a view to the current section body (or contentContainer when no section is open).
    private void addSectionContent(View v) {
        if (sectionTarget != null) sectionTarget.addView(v);
        else contentContainer.addView(v);
    }

    // ── addToggle: E-ink [ ON ] / [ OFF ] Tactile Pill Toggle ───────────────
    private void addToggle(final String label, final String configKey, String desc,
                           final boolean inverted,
                           final String onCmd, final String offCmd, final String verifyCmd,
                           final TextView logDrawer) {
        addToggle(label, configKey, desc, inverted, onCmd, offCmd, verifyCmd, logDrawer, null);
    }

    private void addToggle(final String label, final String configKey, String desc,
                           final boolean inverted,
                           final String onCmd, final String offCmd, final String verifyCmd,
                           final TextView logDrawer, final Runnable onPostToggle) {

        final String val = currentConfig.getProperty(configKey, inverted ? "0" : "1");
        final boolean initialOn = inverted ? "0".equals(val) : "1".equals(val);
        final boolean[] stateHolder = new boolean[] { initialOn };

        // Card with 2dp solid black border
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        card.setLayoutParams(clp);
        card.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        // Top row: title + pill button
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tTitle = new TextView(this);
        tTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tTitle.setText(label);
        setSp(tTitle, 12);
        tTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tTitle.setTextColor(Color.BLACK);

        final TextView pillBtn = new TextView(this);

        pillBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(pillBtn, 12);
        pillBtn.setGravity(Gravity.CENTER);
        pillBtn.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));
        updatePillView(pillBtn, initialOn);

        topRow.addView(tTitle);
        topRow.addView(pillBtn);
        card.addView(topRow);

        // Description
        TextView tDesc = new TextView(this);
        tDesc.setText(desc);
        setSp(tDesc, 11);
        tDesc.setTypeface(Typeface.DEFAULT);
        tDesc.setTextColor(Color.BLACK);
        tDesc.setPadding(0, dpToPx(6), 0, dpToPx(4));
        card.addView(tDesc);



        addSectionContent(card);

        final Runnable toggleAction = new Runnable() {
            @Override
            public void run() {
                final boolean newChecked = !stateHolder[0];
                stateHolder[0] = newChecked;
                final String newVal = inverted ? (newChecked ? "0" : "1") : (newChecked ? "1" : "0");
                currentConfig.setProperty(configKey, newVal);
                ConfigManager.saveConfig(currentConfig);

                // Update pill button instantly
                updatePillView(pillBtn, newChecked);

                final String cmd = newChecked ? onCmd : offCmd;
                if (cmd != null && !cmd.isEmpty()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ShellUtils.CommandResult res = ShellUtils.execRootAction(cmd);
                            String verResult = "";
                            if (verifyCmd != null && !verifyCmd.isEmpty()) {
                                verResult = ShellUtils.execRoot(verifyCmd).stdout.trim();
                            }
                            final String statusText = verResult.isEmpty()
                                ? (res.isSuccess() ? "[OK]" : "[FAIL]")
                                : "[OK: " + verResult + "]";
                            final String log = ShellUtils.readLog(15);
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (logDrawer != null) logDrawer.setText(log);
                                }
                            });
                        }
                    }).start();
                }
                if (onPostToggle != null) {
                    onPostToggle.run();
                }
            }
        };

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleAction.run(); }
        });
        pillBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleAction.run(); }
        });
    }

    /**
     * A switch that cannot work on this device. Rendered as [ N/A ] with the
     * reason, so the feature is visible and explained rather than being a toggle
     * that silently does nothing.
     */
    private void addUnavailableToggle(String label, String desc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        card.setLayoutParams(clp);
        card.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tTitle = new TextView(this);
        tTitle.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tTitle.setText(label);
        setSp(tTitle, 12);
        tTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tTitle.setTextColor(Color.BLACK);

        TextView pill = new TextView(this);
        pill.setText("[ N/A ]");
        pill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(pill, 12);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));
        pill.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 2));
        pill.setTextColor(Color.BLACK);

        topRow.addView(tTitle);
        topRow.addView(pill);
        card.addView(topRow);

        TextView tDesc = new TextView(this);
        tDesc.setText(desc);
        setSp(tDesc, 11);
        tDesc.setTextColor(Color.BLACK);
        tDesc.setPadding(0, dpToPx(6), 0, dpToPx(4));
        card.addView(tDesc);

        addSectionContent(card);
    }

    private void updatePillView(TextView pillBtn, boolean isOn) {
        if (isOn) {
            pillBtn.setText("[  ON  ]");
            pillBtn.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, 2));
            pillBtn.setTextColor(Color.WHITE);
        } else {
            pillBtn.setText("[ OFF ]");
            pillBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 2));
            pillBtn.setTextColor(Color.BLACK);
        }
    }

    // ── addDebloatToggle: toggle + per-package checkbox selector ──────────
    /**
     * Creates a debloat toggle card with a [SELECT] button that expands a
     * per-package checkbox list underneath. Only checked packages are
     * frozen when the toggle is turned ON.
     *
     * @param label      Display name (e.g. "GOOGLE_STACK")
     * @param configKey  Config key for ON/OFF state (e.g. "GOOGLE_STACK")
     * @param selKey     Config key for package selection (e.g. "GOOGLE_PKGS_SEL")
     * @param desc       Description text
     * @param pkgList    Space-separated package list from ConfigManager
     * @param verifyCmd  Verification command for the toggle
     * @param logDrawer  Log drawer to update
     */
    private void addDebloatToggle(final String label, final String configKey,
                                  final String selKey, String desc,
                                  final String pkgList, final String verifyCmd,
                                  final TextView logDrawer) {

        final String allPkgs[] = ConfigManager.splitPkgList(pkgList);

        // Read initial ON/OFF state (inverted: "1" means OFF for debloat toggles)
        final String val = currentConfig.getProperty(configKey, "0");
        final boolean initialOn = "0".equals(val);
        final boolean[] stateHolder = new boolean[] { initialOn };

        // ── Main card ──────────────────────────────────────────────────────
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        card.setLayoutParams(clp);
        card.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        // Top row: title + pill button
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tTitle = new TextView(this);
        tTitle.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tTitle.setText(label);
        setSp(tTitle, 12);
        tTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tTitle.setTextColor(Color.BLACK);

        // ON/OFF pill
        final TextView pillBtn = new TextView(this);
        pillBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(pillBtn, 12);
        pillBtn.setGravity(Gravity.CENTER);
        pillBtn.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));
        updatePillView(pillBtn, initialOn);

        topRow.addView(tTitle);
        topRow.addView(pillBtn);
        card.addView(topRow);

        // Description
        TextView tDesc = new TextView(this);
        tDesc.setText(desc);
        setSp(tDesc, 11);
        tDesc.setTypeface(Typeface.DEFAULT);
        tDesc.setTextColor(Color.BLACK);
        tDesc.setPadding(0, dpToPx(6), 0, dpToPx(4));
        card.addView(tDesc);

        // Count of selected packages
        String[] initialSel = ConfigManager.getSelectedPkgs(currentConfig, pkgList, selKey);
        final TextView countLabel = new TextView(this);
        countLabel.setText(initialSel.length + "/" + allPkgs.length + " selected");
        setSp(countLabel, 10);
        countLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        countLabel.setTextColor(Color.BLACK);
        countLabel.setPadding(0, dpToPx(2), 0, 0);
        card.addView(countLabel);

        addSectionContent(card);

        // ── Collapsible package selector panel ─────────────────────────────
        final LinearLayout selectorPanel = new LinearLayout(this);
        selectorPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splp.setMargins(dpToPx(8), 0, dpToPx(8), dpToPx(4));
        selectorPanel.setLayoutParams(splp);
        selectorPanel.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        selectorPanel.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        selectorPanel.setVisibility(View.GONE);

        // SELECT ALL / DESELECT ALL row
        LinearLayout selBtnRow = new LinearLayout(this);
        selBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        selBtnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button selAllBtn = new Button(this);
        selAllBtn.setText("[SELECT ALL]");
        selAllBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(selAllBtn, 10);
        styleEinkButton(selAllBtn, true);

        Button deselAllBtn = new Button(this);
        deselAllBtn.setText("[DESELECT ALL]");
        deselAllBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(deselAllBtn, 10);
        styleEinkButton(deselAllBtn, true);

        LinearLayout.LayoutParams selBtnLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        selBtnLp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        selAllBtn.setLayoutParams(selBtnLp);
        deselAllBtn.setLayoutParams(selBtnLp);

        selBtnRow.addView(selAllBtn);
        selBtnRow.addView(deselAllBtn);
        selectorPanel.addView(selBtnRow);

        // Build checkbox rows — track CheckBox references
        final CheckBox[] pkgCheckBoxes = new CheckBox[allPkgs.length];

        for (int i = 0; i < allPkgs.length; i++) {
            final String pkg = allPkgs[i];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3));

            // Top: checkbox row
            LinearLayout checkRow = new LinearLayout(this);
            checkRow.setOrientation(LinearLayout.HORIZONTAL);
            checkRow.setGravity(Gravity.CENTER_VERTICAL);

            // Determine initial state
            boolean checked = false;
            for (String s : initialSel) {
                if (s.equals(pkg)) { checked = true; break; }
            }

            // E-ink checkbox style: [X] / [ ] marker
            final TextView marker = new TextView(this);
            marker.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(marker, 11);
            marker.setPadding(dpToPx(4), 0, dpToPx(4), 0);
            marker.setText(checked ? "[X]" : "[ ]");
            marker.setTextColor(Color.BLACK);

            CheckBox cb = new CheckBox(this);
            cb.setText(pkg);
            cb.setTypeface(Typeface.DEFAULT);
            setSp(cb, 10);
            cb.setTextColor(Color.BLACK);
            cb.setButtonDrawable(null);
            cb.setBackground(null);
            cb.setChecked(checked);

            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    marker.setText(isChecked ? "[X]" : "[ ]");
                    int count = 0;
                    for (CheckBox cb : pkgCheckBoxes) {
                        if (cb.isChecked()) count++;
                    }
                    countLabel.setText(count + "/" + allPkgs.length + " selected");
                    saveCurrentSelection(pkgCheckBoxes, allPkgs, selKey);
                }
            });

            checkRow.addView(marker);
            checkRow.addView(cb);
            row.addView(checkRow);

            // Package description
            String description = ConfigManager.PKG_DESCRIPTIONS.get(pkg);
            if (description != null && !description.isEmpty()) {
                TextView pkgDesc = new TextView(this);
                pkgDesc.setText("  " + description);
                setSp(pkgDesc, 9);
                pkgDesc.setTypeface(Typeface.DEFAULT);
                pkgDesc.setTextColor(Color.DKGRAY);
                pkgDesc.setPadding(dpToPx(20), 0, 0, 0);
                row.addView(pkgDesc);
            }

            selectorPanel.addView(row);
            pkgCheckBoxes[i] = cb;
        }

        addSectionContent(selectorPanel);

        // ── SELECT ALL handler ─────────────────────────────────────────────
        selAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (CheckBox cb : pkgCheckBoxes) cb.setChecked(true);
            }
        });

        // ── DESELECT ALL handler ───────────────────────────────────────────
        deselAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (CheckBox cb : pkgCheckBoxes) cb.setChecked(false);
            }
        });

        // ── Click on card (except pill) toggles package list dropdown ──────
        final boolean[] panelVisible = new boolean[] { false };
        View.OnClickListener togglePanel = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panelVisible[0] = !panelVisible[0];
                selectorPanel.setVisibility(panelVisible[0] ? View.VISIBLE : View.GONE);
            }
        };
        card.setOnClickListener(togglePanel);
        tTitle.setOnClickListener(togglePanel);
        tDesc.setOnClickListener(togglePanel);
        countLabel.setOnClickListener(togglePanel);

        // ── Toggle action (ON/OFF pill only) ────────────────────────────────
        final Runnable toggleAction = new Runnable() {
            @Override
            public void run() {
                final boolean newChecked = !stateHolder[0];
                stateHolder[0] = newChecked;
                final String newVal = newChecked ? "0" : "1";
                currentConfig.setProperty(configKey, newVal);
                ConfigManager.saveConfig(currentConfig);
                updatePillView(pillBtn, newChecked);

                if (newChecked) {
                    // FREEZE: only freeze checked packages
                    String cmd = ConfigManager.buildSelectedPmCmd(
                        currentConfig, pkgList, selKey, true);
                    if (!cmd.isEmpty()) {
                        final String freezeCmd = cmd;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ShellUtils.CommandResult res = ShellUtils.execRootAction(freezeCmd);
                                String verResult = "";
                                if (verifyCmd != null && !verifyCmd.isEmpty()) {
                                    verResult = ShellUtils.execRoot(verifyCmd).stdout.trim();
                                }
                                final String log = ShellUtils.readLog(15);
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (logDrawer != null) logDrawer.setText(log);
                                    }
                                });
                            }
                        }).start();
                    }
                } else {
                    // UNFREEZE: undo exactly what ON froze - the selected set. The
                    // old code re-enabled the entire category, which could undo
                    // freezes applied by other means.
                    String cmd = ConfigManager.buildSelectedPmCmd(
                        currentConfig, pkgList, selKey, false);
                    if (!cmd.isEmpty()) {
                        final String unfreezeCmd = cmd;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ShellUtils.CommandResult res = ShellUtils.execRootAction(unfreezeCmd);
                                String verResult = "";
                                if (verifyCmd != null && !verifyCmd.isEmpty()) {
                                    verResult = ShellUtils.execRoot(verifyCmd).stdout.trim();
                                }
                                final String log = ShellUtils.readLog(15);
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (logDrawer != null) logDrawer.setText(log);
                                    }
                                });
                            }
                        }).start();
                    }
                }
            }
        };

        // Clicking the pill toggles ON/OFF
        pillBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleAction.run(); }
        });
    }

    /** Save which packages are checked to the config. */
    private void saveCurrentSelection(CheckBox[] checkBoxes, String[] pkgs, String selKey) {
        java.util.List<String> checked = new java.util.ArrayList<>();
        for (int i = 0; i < checkBoxes.length; i++) {
            if (checkBoxes[i].isChecked()) checked.add(pkgs[i]);
        }
        ConfigManager.savePkgSelection(currentConfig, selKey,
            checked.toArray(new String[0]));
        ConfigManager.saveConfig(currentConfig);
    }

    // ── APPS section: per-app cards with install/update/progress ──────────
    private void addAppInstallerCards(final TextView logDrawer) {
        for (final AppInstaller.AppDef app : AppInstaller.APPS) {
            addAppCard(app, logDrawer);
        }
    }

    private void addAppCard(final AppInstaller.AppDef app, final TextView logDrawer) {
        // ── Card container ──────────────────────────────────────────────
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        card.setLayoutParams(clp);
        card.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        // ── Top row: name + status + install button ─────────────────────
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tName = new TextView(this);
        tName.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tName.setText(app.name);
        setSp(tName, 12);
        tName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tName.setTextColor(Color.BLACK);

        // Status pill: INSTALLED v1.2.3 / NOT INSTALLED
        final TextView statusPill = new TextView(this);
        statusPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(statusPill, 10);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMarginEnd(dpToPx(8));
        statusPill.setLayoutParams(statusLp);
        boolean installed = AppInstaller.isInstalled(this, app.packageName);
        if (installed) {
            String ver = AppInstaller.getInstalledVersion(this, app.packageName);
            statusPill.setText("INSTALLED" + versionSuffix(ver));
            statusPill.setTextColor(Color.WHITE);
            statusPill.setBackgroundColor(Color.BLACK);
        } else {
            statusPill.setText("NOT INSTALLED");
            statusPill.setTextColor(Color.BLACK);
            statusPill.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        }

        // Install button
        final Button installBtn = new Button(this);
        setSp(installBtn, 10);
        installBtn.setText(installed ? "UPDATE" : "INSTALL");
        installBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        installBtn.setAllCaps(false);
        installBtn.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        installBtn.setMinimumHeight(0);
        installBtn.setMinimumWidth(0);
        installBtn.setMinWidth(0);
        installBtn.setMinHeight(0);
        installBtn.setTextSize(11);
        styleEinkButton(installBtn, true);

        // Uninstall button (only shown when the app is installed)
        final Button uninstallBtn = new Button(this);
        setSp(uninstallBtn, 10);
        uninstallBtn.setText("UNINSTALL");
        uninstallBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        uninstallBtn.setAllCaps(false);
        uninstallBtn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        uninstallBtn.setMinimumHeight(0);
        uninstallBtn.setMinimumWidth(0);
        uninstallBtn.setMinWidth(0);
        uninstallBtn.setMinHeight(0);
        uninstallBtn.setTextSize(11);
        styleEinkButton(uninstallBtn, false);
        uninstallBtn.setVisibility(installed ? View.VISIBLE : View.GONE);

        topRow.addView(tName);
        topRow.addView(statusPill);
        topRow.addView(installBtn);
        topRow.addView(uninstallBtn);
        card.addView(topRow);

        // ── Description ─────────────────────────────────────────────────
        TextView tDesc = new TextView(this);
        tDesc.setText(app.description);
        setSp(tDesc, 11);
        tDesc.setTypeface(Typeface.DEFAULT);
        tDesc.setTextColor(Color.BLACK);
        tDesc.setPadding(0, dpToPx(6), 0, dpToPx(4));
        card.addView(tDesc);

        // ── Progress bar (hidden initially) ─────────────────────────────
        final LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setVisibility(View.GONE);

        final TextView progressLabel = new TextView(this);
        progressLabel.setText("Downloading...");
        setSp(progressLabel, 10);
        progressLabel.setTypeface(Typeface.DEFAULT);
        progressLabel.setTextColor(Color.BLACK);
        progressLabel.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        final TextView progressPct = new TextView(this);
        progressPct.setText("0%");
        setSp(progressPct, 10);
        progressPct.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        progressPct.setTextColor(Color.BLACK);

        progressRow.addView(progressLabel);
        progressRow.addView(progressPct);
        card.addView(progressRow);

        // ── Error label (hidden initially) ──────────────────────────────
        final TextView errorLabel = new TextView(this);
        errorLabel.setVisibility(View.GONE);
        setSp(errorLabel, 10);
        errorLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        errorLabel.setTextColor(Color.BLACK);
        errorLabel.setPadding(0, dpToPx(4), 0, 0);
        card.addView(errorLabel);

        addSectionContent(card);

        // ── Install button handler ──────────────────────────────────────
        final Handler mainHandler = new Handler();
        installBtn.setEnabled(true);

        installBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installBtn.setEnabled(false);
                installBtn.setText("...");
                errorLabel.setVisibility(View.GONE);
                progressRow.setVisibility(View.VISIBLE);
                progressLabel.setText("Starting...");
                progressPct.setText("0%");

                AppInstaller.install(MainActivity.this, app, new AppInstaller.InstallCallback() {
                    @Override
                    public void onProgress(final String message, final int percent) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                progressLabel.setText(message);
                                progressPct.setText(percent + "%");
                                if (logDrawer != null) {
                                    logDrawer.setText(message);
                                }
                            }
                        });
                    }

                    @Override
                    public void onResult(final boolean success, final String message) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                progressRow.setVisibility(View.GONE);
                                installBtn.setEnabled(true);

                                boolean nowInstalled = AppInstaller.isInstalled(MainActivity.this, app.packageName);
                                if (success) {
                                    if (app.isSystem && !nowInstalled) {
                                        // Magisk system app — needs reboot to activate
                                        statusPill.setText("NEEDS REBOOT");
                                        statusPill.setTextColor(Color.BLACK);
                                        statusPill.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                                        uninstallBtn.setVisibility(View.GONE);
                                        wireRebootButton(installBtn,
                                            app.name + " system app is installed. Reboot to activate it.");
                                    } else {
                                        updateAppCardState(app, nowInstalled, statusPill, installBtn, uninstallBtn);
                                    }
                                    errorLabel.setVisibility(View.VISIBLE);
                                    errorLabel.setTextColor(Color.BLACK);
                                    errorLabel.setText(message);
                                } else {
                                    updateAppCardState(app, nowInstalled, statusPill, installBtn, uninstallBtn);
                                    errorLabel.setVisibility(View.VISIBLE);
                                    errorLabel.setTextColor(Color.BLACK);
                                    errorLabel.setText("FAILED: " + message);
                                }
                                if (logDrawer != null) logDrawer.setText(message);
                            }
                        });
                    }
                });
            }
        });

        // ── Uninstall button handler ────────────────────────────────────
        uninstallBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = "Remove " + app.name + " from this device?";
                if (app.isSystem) {
                    msg = "Remove " + app.name + " system app?\n" +
                        "The stock launcher will be restored. A reboot is required to finish removal.";
                }
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Uninstall " + app.name)
                    .setMessage(msg)
                    .setPositiveButton("UNINSTALL", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            runUninstall(app, statusPill, installBtn, uninstallBtn,
                                progressRow, progressLabel, progressPct,
                                errorLabel, logDrawer, mainHandler);
                        }
                    })
                    .setNegativeButton("CANCEL", null)
                    .show();
            }
        });
    }

    /** " v1.2.3", or " v2026.07.1" when the versionName already has its own v. */
    private static String versionSuffix(String ver) {
        if (ver == null || ver.trim().isEmpty()) return "";
        String v = ver.trim();
        return (v.startsWith("v") || v.startsWith("V")) ? " " + v : " v" + v;
    }

    /** Refresh a card's pill + buttons to match the installed state. */
    private void updateAppCardState(AppInstaller.AppDef app, boolean installed,
                                    TextView statusPill, Button installBtn, Button uninstallBtn) {
        if (installed) {
            String ver = AppInstaller.getInstalledVersion(this, app.packageName);
            statusPill.setText("INSTALLED" + versionSuffix(ver));
            statusPill.setTextColor(Color.WHITE);
            statusPill.setBackgroundColor(Color.BLACK);
            installBtn.setText("UPDATE");
            uninstallBtn.setVisibility(View.VISIBLE);
            uninstallBtn.setText("UNINSTALL");
        } else {
            statusPill.setText("NOT INSTALLED");
            statusPill.setTextColor(Color.BLACK);
            statusPill.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
            installBtn.setText("INSTALL");
            uninstallBtn.setVisibility(View.GONE);
        }
    }

    /** Turn a button into a confirm-then-reboot button. */
    private void wireRebootButton(Button btn, final String message) {
        btn.setText("REBOOT");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRebootDialog(message);
            }
        });
    }

    /** Ask the user whether to reboot now. */
    private void showRebootDialog(final String message) {
        new AlertDialog.Builder(MainActivity.this)
            .setTitle("Reboot?")
            .setMessage(message)
            .setPositiveButton("REBOOT", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    ShellUtils.execRootAction("svc power reboot");
                }
            })
            .setNegativeButton("LATER", null)
            .show();
    }

    /** Run the uninstall flow on a background thread and update the card. */
    private void runUninstall(final AppInstaller.AppDef app, final TextView statusPill,
                              final Button installBtn, final Button uninstallBtn,
                              final LinearLayout progressRow, final TextView progressLabel,
                              final TextView progressPct, final TextView errorLabel,
                              final TextView logDrawer, final Handler mainHandler) {
        installBtn.setEnabled(false);
        uninstallBtn.setEnabled(false);
        errorLabel.setVisibility(View.GONE);
        progressRow.setVisibility(View.VISIBLE);
        progressLabel.setText("Uninstalling...");
        progressPct.setText("0%");

        // A system-app (Magisk overlay) removal needs a reboot to fully release the
        // /system path, so remember it now — the package may be gone by the time the
        // result callback runs.
        final boolean wasSystem = AppInstaller.isInstalledAsSystem(this, app.packageName);

        AppInstaller.uninstall(MainActivity.this, app, new AppInstaller.InstallCallback() {
            @Override
            public void onProgress(final String message, final int percent) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        progressLabel.setText(message);
                        progressPct.setText(percent + "%");
                        if (logDrawer != null) logDrawer.setText(message);
                    }
                });
            }

            @Override
            public void onResult(final boolean success, final String message) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        progressRow.setVisibility(View.GONE);
                        installBtn.setEnabled(true);
                        uninstallBtn.setEnabled(true);
                        errorLabel.setVisibility(View.VISIBLE);
                        errorLabel.setTextColor(Color.BLACK);

                        if (success) {
                            if (wasSystem) {
                                // Overlay removed from disk; a reboot fully releases the
                                // /system path and clears the mounted copy.
                                statusPill.setText("NEEDS REBOOT");
                                statusPill.setTextColor(Color.BLACK);
                                statusPill.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                                uninstallBtn.setVisibility(View.GONE);
                                String rebootMsg = app.name + " removed. Reboot to finish uninstalling it.";
                                wireRebootButton(installBtn, rebootMsg);
                                showRebootDialog(rebootMsg);
                            } else {
                                updateAppCardState(app, false, statusPill, installBtn, uninstallBtn);
                            }
                            errorLabel.setText(message);
                        } else {
                            errorLabel.setText("FAILED: " + message);
                        }
                        if (logDrawer != null) logDrawer.setText(message);
                    }
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 2: APP DEBLOAT
    // ─────────────────────────────────────────────────────────────────────────
    // Filter options: internal codes -> human-readable labels.
    private String[] getFilterValues() {
        return new String[] {"ALL", "USER", "SYSTEM", "FROZEN", "RSTR", "PROT"};
    }

    private String[] getFilterLabels() {
        return new String[] {
            "All packages",
            "User apps only",
            "System apps only",
            "Frozen apps only",
            "Background restricted",
            "Protected apps"
        };
    }

    private String getFilterLabel(String value) {
        String[] values = getFilterValues();
        String[] labels = getFilterLabels();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return labels[i];
        }
        return value;
    }

    private String getSortName(int mode) {
        switch (mode) {
            case 0: return "A-Z";
            case 1: return "Z-A";
            case 2: return "FROZEN FIRST";
            case 3: return "ACTIVE FIRST";
            case 4: return "PROTECTED FIRST";
            default: return "A-Z";
        }
    }

    private void renderAppDebloat() {
        final List<String> protected_pkgs = new ArrayList<String>();
        protected_pkgs.add("com.right9code.hibigzero");
        protected_pkgs.add("com.right9code.anyhome");
        protected_pkgs.add("org.koreader.launcher");
        protected_pkgs.add("android");
        protected_pkgs.add("com.android.systemui");
        protected_pkgs.add("com.topjohnwu.magisk");
        protected_pkgs.add("com.xrz.sys.control");
        protected_pkgs.add("com.xrz.settings");
        protected_pkgs.add("com.google.android.webview");
        protected_pkgs.add("com.termux");
        protected_pkgs.add("com.tailscale.ipn");
        protected_pkgs.add("md.obsidian");
        protected_pkgs.add("com.syncthing.android");
        protected_pkgs.add("com.wireguard.android");

        addSectionHeader("PACKAGE MANAGER", "FREEZE, UNFREEZE, OR RESTRICT");

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        final PackageManager pm = getPackageManager();

        // Search bar
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(4));

        final EditText searchBox = new EditText(this);
        searchBox.setHint("SEARCH...");
        searchBox.setTypeface(Typeface.DEFAULT);
        setSp(searchBox, 11);
        searchBox.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        searchBox.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, 0));
        searchBox.setTextColor(Color.BLACK);
        searchBox.setHintTextColor(Color.BLACK);
        searchBox.setText(debloatSearch);
        searchBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        searchRow.addView(searchBox);
        addSectionContent(searchRow);

        // Filter & Sort bar
        LinearLayout filterSortRow = new LinearLayout(this);
        filterSortRow.setOrientation(LinearLayout.HORIZONTAL);
        filterSortRow.setGravity(Gravity.CENTER_VERTICAL);
        filterSortRow.setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(8));

        final Button filterBtn = new Button(this);
        filterBtn.setText("FILTER: " + getFilterLabel(debloatFilter));
        setSp(filterBtn, 10);
        filterBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        styleEinkButton(filterBtn, false);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        flp.setMargins(0, 0, dpToPx(4), 0);
        filterBtn.setLayoutParams(flp);
        filterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] labels = getFilterLabels();
                final String[] values = getFilterValues();
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Filter Packages")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            debloatFilter = values[which];
                            filterBtn.setText("FILTER: " + getFilterLabel(debloatFilter));
                            renderDebloatList(listContainer, pm, protected_pkgs);
                        }
                    }).show();
            }
        });
        filterSortRow.addView(filterBtn);

        final Button sortBtn = new Button(this);
        sortBtn.setText("SORT: " + getSortName(debloatSortMode));
        setSp(sortBtn, 10);
        sortBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        styleEinkButton(sortBtn, false);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        stlp.setMargins(dpToPx(4), 0, 0, 0);
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
        filterSortRow.addView(sortBtn);
        addSectionContent(filterSortRow);

        addSectionContent(listContainer);

        // 350ms Debounced search watcher to prevent keyboard stutter and screen flashing on E-ink
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                debloatSearch = s.toString();
                if (searchDebounceRunnable != null) mainHandler.removeCallbacks(searchDebounceRunnable);
                searchDebounceRunnable = new Runnable() {
                    @Override
                    public void run() {
                        renderDebloatList(listContainer, pm, protected_pkgs);
                    }
                };
                mainHandler.postDelayed(searchDebounceRunnable, 350);
            }
            public void afterTextChanged(android.text.Editable s) {}
        });

        if (cachedAppItems == null) {
            final TextView loading = new TextView(this);
            loading.setText("Loading packages...");
            loading.setTypeface(Typeface.DEFAULT);
            setSp(loading, 11);
            loading.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
            listContainer.addView(loading);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                    java.util.Set<String> restrictedPkgs = ConfigManager.loadRestrictedPkgs();

                    // Parse dumpsys usagestats live for Standby Buckets (MUST USE ROOT for DUMP permission)
                    final java.util.Map<String, Integer> bucketMap = new java.util.HashMap<String, Integer>();
                    ShellUtils.CommandResult usgRes = ShellUtils.execRoot("dumpsys usagestats | grep -E 'package=.*bucket='", false);
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

                    // Parse dumpsys deviceidle whitelist live for Doze exemptions (MUST USE ROOT for DUMP permission)
                    final java.util.Set<String> dozeWhitelist = new java.util.HashSet<String>();
                    final java.util.Set<String> systemExcidle = new java.util.HashSet<String>();
                    ShellUtils.CommandResult dozeRes = ShellUtils.execRoot("dumpsys deviceidle whitelist", false);
                    if (dozeRes.isSuccess() && dozeRes.stdout != null) {
                        for (String line : dozeRes.stdout.split("\n")) {
                            String[] parts = line.split(",");
                            if (parts.length >= 2) {
                                dozeWhitelist.add(parts[1].trim());
                                if (line.startsWith("system-excidle")) {
                                    systemExcidle.add(parts[1].trim());
                                }
                            }
                        }
                    }

                    final java.util.Set<String> finalSystemExcidle = systemExcidle;
                    final List<AppItem> items = new ArrayList<AppItem>();
                    for (ApplicationInfo app : apps) {
                        String label = app.loadLabel(pm).toString();
                        boolean isProt = protected_pkgs.contains(app.packageName);
                        boolean isSys = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        boolean isRestr = restrictedPkgs.contains(app.packageName);
                        int bkt = bucketMap.containsKey(app.packageName) ? bucketMap.get(app.packageName) : (app.enabled ? 10 : 50);
                        boolean isDoze = dozeWhitelist.contains(app.packageName);
                        AppItem item = new AppItem(app, label, app.packageName, isProt, app.enabled, isSys, isRestr, bkt, isDoze);
                        item.isSystemExcidle = finalSystemExcidle.contains(app.packageName);
                        items.add(item);
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

    // Plain-language status line so no two concepts share a label.
    private void updateDebloatStatusLine(TextView statusLine, AppItem item) {
        String freeze = item.isEnabled ? "Not frozen" : "Frozen";
        String bg = item.isRestricted ? "restricted" : "allowed";
        String doze;
        if (item.isSystemExcidle) {
            doze = "doze system-exempt";
        } else if (item.isDozeExempt) {
            doze = "doze exempt";
        } else {
            doze = "doze optimized";
        }
        statusLine.setText(freeze + "  |  background: " + bg +
            "  |  " + doze + "  |  usage: " + bucketDescription(item.standbyBucket));
    }

    // Human-readable meaning of the Android standby bucket value.
    private String bucketDescription(int bucket) {
        switch (bucket) {
            case 5:  return "exempt (unrestricted)";
            case 10: return "active (in use now)";
            case 20: return "working set (used recently)";
            case 30: return "frequent (used often)";
            case 40: return "rare (throttled)";
            case 45: return "restricted (silenced)";
            case 50: return "never (not used)";
            default: return "bucket " + bucket;
        }
    }

    private void refreshDebloatListWithScroll() {
        final int scrollY = mainScrollView != null ? mainScrollView.getScrollY() : 0;
        sectionTarget = null;
        contentContainer.removeAllViews();
        renderAppDebloat();
        if (mainScrollView != null) {
            mainScrollView.post(new Runnable() {
                @Override
                public void run() {
                    mainScrollView.scrollTo(0, scrollY);
                }
            });
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
            if ("RSTR".equals(debloatFilter) && !item.isRestricted) continue;

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
        // Show the same readable label the FILTER button and its dialog use, instead
        // of the internal code (a header reading "[RSTR]" is what those codes were
        // meant to hide).
        hdr.setText(filtered.size() + "/" + cachedAppItems.size() + " PKGS [" + getFilterLabel(debloatFilter) + "]");
        hdr.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(hdr, 10);
        hdr.setTextColor(Color.BLACK);
        hdr.setBackgroundColor(Color.WHITE);
        hdr.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        listContainer.addView(hdr);

        // Rows are added in chunks instead of one pass: building all 220 in a single
        // UI-thread task measured ~610 ms here, which froze the tab the whole time.
        // Each chunk is its own task, so taps and scrolling keep working while the
        // rest stream in. Rows that land below the fold do not change the visible
        // area, so this costs no extra e-ink refreshes.
        final int generation = ++debloatRenderGeneration;
        renderDebloatChunk(listContainer, pm, protected_pkgs, filtered, 0, generation);
    }

    // Builds one slice of rows, then re-posts itself for the next slice.
    private void renderDebloatChunk(final LinearLayout listContainer, final PackageManager pm,
                                    final List<String> protected_pkgs, final List<AppItem> filtered,
                                    final int start, final int generation) {
        // A tab switch, filter or search change supersedes this render.
        if (generation != debloatRenderGeneration || activeTab != 1) return;

        final int end = Math.min(start + DEBLOAT_ROWS_PER_CHUNK, filtered.size());
        long chunkStart = System.currentTimeMillis();
        for (int index = start; index < end; index++) {
            final AppItem item = filtered.get(index);
            final String pkg = item.pkg;
            final boolean isProt = item.isProt;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));
            row.setLayoutParams(rlp);

            // Clean white card with 1.5dp black border (eliminates zebra scrolling ghosting)
            row.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, 0));

            // Left column: app name, package, status
            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView pName = new TextView(this);
            pName.setText(item.label);
            setSp(pName, 12);
            pName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            pName.setTextColor(Color.BLACK);
            pName.setPadding(dpToPx(12), dpToPx(8), dpToPx(6), 0);
            infoCol.addView(pName);

            TextView pPkg = new TextView(this);
            pPkg.setText(pkg);
            setSp(pPkg, 10);
            pPkg.setTypeface(Typeface.DEFAULT);
            pPkg.setTextColor(Color.BLACK);
            pPkg.setPadding(dpToPx(12), dpToPx(1), dpToPx(6), dpToPx(3));
            infoCol.addView(pPkg);

            // Status detail line (explicit, plain-language labels)
            final TextView statusLine = new TextView(this);
            setSp(statusLine, 10);
            statusLine.setTypeface(Typeface.DEFAULT);
            statusLine.setTextColor(Color.BLACK);
            statusLine.setPadding(dpToPx(12), 0, dpToPx(6), dpToPx(8));
            updateDebloatStatusLine(statusLine, item);
            infoCol.addView(statusLine);

            row.addView(infoCol);

            // Right column: stacked actions with a gap between them
            LinearLayout actionCol = new LinearLayout(this);
            actionCol.setOrientation(LinearLayout.VERTICAL);
            actionCol.setGravity(Gravity.CENTER_VERTICAL);
            actionCol.setPadding(0, dpToPx(6), dpToPx(12), dpToPx(6));

            // Quick Freeze/Unfreeze button with in-place update (no scroll kick-to-top)
            if (!isProt) {
                final Button quickBtn = new Button(this);
                quickBtn.setText(item.isEnabled ? "FREEZE" : "UNFREEZE");
                setSp(quickBtn, 11);
                quickBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                quickBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, 0));
                quickBtn.setTextColor(Color.BLACK);
                quickBtn.setMinWidth(dpToPx(104));
                quickBtn.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
                LinearLayout.LayoutParams qblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                qblp.setMargins(0, 0, 0, dpToPx(6));
                quickBtn.setLayoutParams(qblp);
                quickBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        quickBtn.setEnabled(false);
                        final boolean targetEnabled = !item.isEnabled;
                        item.isEnabled = targetEnabled;
                        updateDebloatStatusLine(statusLine, item);
                        quickBtn.setText(targetEnabled ? "FREEZE" : "UNFREEZE");

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                if (!targetEnabled) {
                                    ShellUtils.execRootAction("pm disable-user --user 0 " + pkg + " 2>/dev/null");
                                } else {
                                    ShellUtils.execRootAction("pm enable " + pkg + " 2>/dev/null");
                                }
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        quickBtn.setEnabled(true);
                                        Toast.makeText(MainActivity.this, (targetEnabled ? "Unfroze " : "Froze ") + pkg, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }).start();
                    }
                });
                actionCol.addView(quickBtn);
            } else {
                TextView protBadge = new TextView(this);
                protBadge.setText("PROTECTED");
                setSp(protBadge, 10);
                protBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                protBadge.setTextColor(Color.WHITE);
                protBadge.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, 2));
                protBadge.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                pblp.setMargins(0, 0, 0, dpToPx(6));
                protBadge.setLayoutParams(pblp);
                actionCol.addView(protBadge);
            }

            // Dropdown menu button: OPTIONS
            final Button optBtn = new Button(this);
            optBtn.setText("OPTIONS");
            setSp(optBtn, 11);
            optBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            optBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, 0));
            optBtn.setTextColor(Color.BLACK);
            optBtn.setMinWidth(dpToPx(104));
            optBtn.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
            optBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAppActionDialog(item, pm, protected_pkgs, listContainer);
                }
            });
            actionCol.addView(optBtn);

            row.addView(actionCol);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAppActionDialog(item, pm, protected_pkgs, listContainer);
                }
            });

            listContainer.addView(row);
        }

        Log.i("MainActivity", "debloat list: rows " + start + "-" + end + " of "
            + filtered.size() + " built in " + (System.currentTimeMillis() - chunkStart) + " ms");

        // Hand the next slice back to the looper, so anything the user taps in the
        // meantime is processed first.
        if (end < filtered.size()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    renderDebloatChunk(listContainer, pm, protected_pkgs, filtered, end, generation);
                }
            });
        }
    }

    private void showAppActionDialog(final AppItem item, final PackageManager pm, final List<String> protected_pkgs, final LinearLayout listContainer) {
        final String pkg = item.pkg;
        final boolean isEnabled = item.isEnabled;
        final boolean isProt = item.isProt;

        List<String> options = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();

        // Action 0: Enable / Disable
        if (!isProt) {
            options.add((item.isEnabled ? "DISABLE" : "ENABLE") + "  [" + (item.isEnabled ? "Active" : "Frozen") + "]");
            actions.add(0);
        }

        // Action 1: Background activity
        options.add((item.isRestricted ? "ALLOW" : "BLOCK") + " BACKGROUND  [" + (item.isRestricted ? "Restricted" : "Free") + "]");
        actions.add(1);

        // Action 2: Standby bucket
        options.add("SET PRIORITY  [" + ConfigManager.getBucketLabel(item.standbyBucket) + "]");
        actions.add(2);

        // Action 3: Doze exemption — skip for system-excidle (framework re-adds it)
        if (!item.isSystemExcidle) {
            options.add((item.isDozeExempt ? "BLOCK" : "ALLOW") + " DURING DOZE  [" + (item.isDozeExempt ? "Allowed" : "Blocked") + "]");
            actions.add(3);
        }

        // Action 4: Launch
        options.add("LAUNCH");
        actions.add(4);

        // Action 5: Info
        options.add("INFO");
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
                            ShellUtils.execRootAction("pm disable-user --user 0 " + pkg + " 2>/dev/null");
                        } else {
                            ShellUtils.execRootAction("pm enable " + pkg + " 2>/dev/null");
                        }
                        item.isEnabled = !item.isEnabled;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, (item.isEnabled ? "Unfroze " : "Froze ") + pkg, Toast.LENGTH_SHORT).show();
                                renderDebloatList(listContainer, pm, protected_pkgs);
                            }
                        });
                    }
                }).start();
                break;

            case 1: // Restrict AppOps
                if (isSelfProtected(pkg)) break;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        java.util.Set<String> restricted = ConfigManager.loadRestrictedPkgs();
                        if (item.isRestricted) {
                            ShellUtils.execRootAction(ConfigManager.buildUnrestrictCmd(pkg));
                            restricted.remove(pkg);
                        } else {
                            ShellUtils.execRootAction(ConfigManager.buildRestrictCmd(pkg));
                            restricted.add(pkg);
                        }
                        ConfigManager.saveRestrictedPkgs(restricted);
                        item.isRestricted = !item.isRestricted;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, (item.isRestricted ? "Restricted " : "Unrestricted ") + item.label, Toast.LENGTH_SHORT).show();
                                renderDebloatList(listContainer, pm, protected_pkgs);
                            }
                        });
                    }
                }).start();
                break;

            case 2: // Set Standby Bucket
                if (isSelfProtected(pkg)) break;
                showStandbyBucketPicker(item, pm, protected_pkgs, listContainer);
                break;

            case 3: // Toggle Doze Exemption
                if (isSelfProtected(pkg)) break;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ShellUtils.execRootAction(ConfigManager.buildDozeWhitelistCmd(pkg, !item.isDozeExempt));
                        item.isDozeExempt = !item.isDozeExempt;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, (item.isDozeExempt ? "Exempted " : "Unexempted ") + item.label, Toast.LENGTH_SHORT).show();
                                renderDebloatList(listContainer, pm, protected_pkgs);
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
                            ShellUtils.execRootAction("pm enable " + pkg + " 2>/dev/null");
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
                                                        ShellUtils.execRootAction("am force-stop " + pkg + " 2>/dev/null; pm disable-user --user 0 " + pkg + " 2>/dev/null");
                                                        item.isEnabled = false;
                                                        mainHandler.post(new Runnable() {
                                                            @Override
                                                            public void run() { renderDebloatList(listContainer, pm, protected_pkgs); }
                                                        });
                                                    }
                                                }).start();
                                            }
                                        })
                                        .setNegativeButton("LEAVE ACTIVE", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d, int which) {
                                                renderDebloatList(listContainer, pm, protected_pkgs);
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
            "ACTIVE (10) - Unrestricted",
            "WORKING SET (20) - Active in recent hours",
            "FREQUENT (30) - Used regularly, light delay",
            "RARE (40) - Throttled to 24h jobs & delayed alarms",
            "RESTRICTED (45) - Silenced in background"
        };
        final String[] bucketCodes = new String[] { "active", "working_set", "frequent", "rare", "restricted" };
        final int[] bucketInts = new int[] { 10, 20, 30, 40, 45 };

        new AlertDialog.Builder(this)
            .setTitle("STANDBY BUCKET: " + item.label)
            .setItems(buckets, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    final String bCode = bucketCodes[which];
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ShellUtils.execRootAction(ConfigManager.buildSetStandbyBucketCmd(item.pkg, bCode));
                            item.standbyBucket = bucketInts[which];
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, item.label + " -> " + bCode.toUpperCase(), Toast.LENGTH_SHORT).show();
                                    renderDebloatList(listContainer, pm, protected_pkgs);
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
        powerCard.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20));
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcLp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(8));
        powerCard.setLayoutParams(pcLp);

        final TextView currentBox = new TextView(this);
        setSp(currentBox, 26);
        currentBox.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        currentBox.setTextColor(Color.WHITE);
        currentBox.setText("Reading...");
        powerCard.addView(currentBox);

        final TextView batteryDetailsBox = new TextView(this);
        setSp(batteryDetailsBox, 11);
        batteryDetailsBox.setTypeface(Typeface.DEFAULT);
        batteryDetailsBox.setTextColor(Color.WHITE);
        batteryDetailsBox.setPadding(0, dpToPx(4), 0, dpToPx(8));
        batteryDetailsBox.setText("Waiting for battery data...");
        powerCard.addView(batteryDetailsBox);

        final TextView gaugeBox = new TextView(this);
        setSp(gaugeBox, 12);
        gaugeBox.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        gaugeBox.setTextColor(Color.WHITE);
        gaugeBox.setPadding(0, dpToPx(4), 0, dpToPx(4));
        gaugeBox.setText("BATTERY: --");
        powerCard.addView(gaugeBox);

        final TextView projBox = new TextView(this);
        setSp(projBox, 11);
        projBox.setTypeface(Typeface.DEFAULT);
        projBox.setTextColor(Color.WHITE);
        projBox.setPadding(0, dpToPx(6), 0, 0);
        powerCard.addView(projBox);

        addSectionContent(powerCard);

        addSectionHeader("LIVE CPU & HARDWARE STATUS");

        // Monospace CPU status card
        final LinearLayout cpuCard = new LinearLayout(this);
        cpuCard.setOrientation(LinearLayout.VERTICAL);
        cpuCard.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        cpuCard.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        LinearLayout.LayoutParams cpuCardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cpuCardLp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(8));
        cpuCard.setLayoutParams(cpuCardLp);

        final TextView cpuBox = new TextView(this);
        setSp(cpuBox, 11);
        cpuBox.setTypeface(Typeface.DEFAULT);
        cpuBox.setTextColor(Color.BLACK);
        cpuBox.setText("Querying CPU and hardware status...");
        cpuCard.addView(cpuBox);

        addSectionContent(cpuCard);

        // Pause / Resume and Manual Refresh Toolbar
        LinearLayout diagToolbar = new LinearLayout(this);
        diagToolbar.setOrientation(LinearLayout.HORIZONTAL);
        diagToolbar.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));

        final Button pauseBtn = new Button(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        plp.setMargins(0, 0, dpToPx(4), 0);
        pauseBtn.setLayoutParams(plp);
        pauseBtn.setText("[START]");
        pauseBtn.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, 0));
        pauseBtn.setTextColor(Color.WHITE);
        pauseBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(pauseBtn, 11);
        pauseBtn.setPadding(0, dpToPx(10), 0, dpToPx(10));
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!diagBatteryRunning) {
                    // First tap — start polling
                    diagBatteryRunning = true;
                    diagBatteryPaused = false;
                    pauseBtn.setText("[PAUSE]");
                    triggerSingleDiagQuery(currentBox, batteryDetailsBox, gaugeBox, projBox, cpuBox);
                    mainHandler.post(diagBatteryUpdater);
                } else {
                    diagBatteryPaused = !diagBatteryPaused;
                    pauseBtn.setText(diagBatteryPaused ? "[RESUME]" : "[PAUSE]");
                    if (!diagBatteryPaused && diagBatteryUpdater != null) {
                        mainHandler.post(diagBatteryUpdater);
                    }
                }
            }
        });
        diagToolbar.addView(pauseBtn);

        final Button refreshBtn = new Button(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        rlp.setMargins(dpToPx(4), 0, 0, 0);
        refreshBtn.setLayoutParams(rlp);
        refreshBtn.setText("[REFRESH NOW]");
        refreshBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        refreshBtn.setTextColor(Color.BLACK);
        refreshBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(refreshBtn, 11);
        refreshBtn.setPadding(0, dpToPx(10), 0, dpToPx(10));
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerSingleDiagQuery(currentBox, batteryDetailsBox, gaugeBox, projBox, cpuBox);
            }
        });
        diagToolbar.addView(refreshBtn);
        addSectionContent(diagToolbar);

        // Live 10-second polling — starts paused, user taps START to begin
        diagBatteryRunning = false;
        diagBatteryPaused = true;
        diagBatteryUpdater = new Runnable() {
            @Override
            public void run() {
                 if (!diagBatteryRunning) return;
                 if (!diagBatteryPaused) {
                     new Thread(new Runnable() {
                         @Override
                         public void run() {
                             ShellUtils.CommandResult res = ShellUtils.execRoot(buildDiagShellCmd(), false);
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
                }
                mainHandler.postDelayed(this, 10000);
            }
        };
        mainHandler.post(diagBatteryUpdater);

        // Sleep test button
        addSectionHeader("DIAGNOSTICS");

        Button sleepTest = new Button(this);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        sleepTest.setLayoutParams(stlp);
        sleepTest.setText("[>>] RUN 10-MIN DEEP SLEEP TEST");
        sleepTest.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(sleepTest, 11);
        sleepTest.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        sleepTest.setTextColor(Color.BLACK);
        sleepTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // The sampler ships as an app asset — stage it to
                        // /data/local/tmp (root-only path) before launching.
                        if (!deployAsset("drain_sampler.sh", "/data/local/tmp/drain_sampler.sh")) {
                            ShellUtils.appendLog("Deep sleep test: failed to stage drain_sampler.sh");
                            return;
                        }
                        ShellUtils.execRoot("nohup sh /data/local/tmp/drain_sampler.sh 600 > /data/local/tmp/diag.log 2>&1 &", false);
                        ShellUtils.appendLog("Deep sleep test started — log: /data/local/tmp/diag/drain_sample.log");
                    }
                }).start();
            }
        });
        addSectionContent(sleepTest);

        // Power off button (inverted, danger)
        Button powerOff = new Button(this);
        LinearLayout.LayoutParams polp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        polp.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        powerOff.setLayoutParams(polp);
        powerOff.setText(">>> POWER OFF (0.00 mA) <<<");
        powerOff.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, 0));
        powerOff.setTextColor(Color.WHITE);
        powerOff.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(powerOff, 13);
        powerOff.setPadding(0, dpToPx(16), 0, dpToPx(16));
        powerOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(">>> POWER OFF <<<")
                    .setMessage("Shut down to 0.00 mA? E-ink retains current image.")
                    .setPositiveButton(">>> OFF <<<", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            ShellUtils.execRootAction("sync && reboot -p");
                        }
                    })
                    .setNegativeButton("CANCEL", null)
                    .show();
            }
        });
        addSectionContent(powerOff);
    }

    private void triggerSingleDiagQuery(final TextView currentBox, final TextView batteryDetailsBox,
                                        final TextView gaugeBox, final TextView projBox, final TextView cpuBox) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ShellUtils.CommandResult res = ShellUtils.execRoot(buildDiagShellCmd(), false);
                final String out = (res != null && res.stdout != null) ? res.stdout.trim() : "";
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateDiagUi(out, currentBox, batteryDetailsBox, gaugeBox, projBox, cpuBox);
                    }
                });
            }
        }).start();
    }

    /**
     * Single source of truth for the diagnostics probe. Fields are pipe-separated:
     * cur|cap|status|volt|temp|cpus|f0|g0|f4|g4|ppm
     */
    private String buildDiagShellCmd() {
        return "CUR=$(cat /sys/class/power_supply/battery/current_now 2>/dev/null); " +
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
               "echo \"$CUR|$CAP|$STA|$VOLT|$TEMP|$ONLINE|$F0|$G0|$F4|$G4|$PPM\"";
    }

    private void updateDiagUi(String raw, TextView currentBox, TextView batteryDetailsBox,
                              TextView gaugeBox, TextView projBox, TextView cpuBox) {
        if (raw == null || raw.isEmpty()) return;
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 11) return;

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
        for (int i = 0; i < filled; i++) bar.append("#");
        for (int i = 0; i < empty; i++) bar.append(".");
        gaugeBox.setText("BATTERY: " + cap + "%  " + bar.toString() + "  [" + st + "]");

        // HiBreak spec-sheet capacity (2100 mAh). Used for the runtime projection.
        double capacityMah = BATTERY_CAPACITY_MAH;

        double hrs = ma > 0 ? (capacityMah / ma) : 0;
        int standbyDays = (int) Math.round(capacityMah / 2.5 / 24);
        String proj = isCharging
            ? "STATE: CHARGING  |  CAPACITY: " + cap + "%\nPOWER OFF: 0.00 mA (infinite retention)"
            : "ACTIVE RUNTIME: ~" + String.format(java.util.Locale.US, "%.1f", hrs) + " hrs  (" + ma + " mA @ " + (int) capacityMah + " mAh)\n" +
              "STANDBY: ~2.5 mA (~" + standbyDays + " days)  |  POWER OFF: 0.00 mA";
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

        // Auto-shutdown status
        String shutdownEnabled = currentConfig.getProperty("AUTO_SHUTDOWN_ENABLED", "1");
        String timeoutMin = currentConfig.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120");
        cpuSb.append("AUTO-SHUTDOWN: ").append("1".equals(shutdownEnabled) ? "[ALARM] " + timeoutMin + "m timeout" : "[DISABLED]").append("\n");

        cpuBox.setText(cpuSb.toString());
    }

    /**
     * Copy an app asset to a root-only destination path (e.g. /data/local/tmp)
     * and make it executable. The app process itself cannot write there.
     * Returns true on success.
     */
    private boolean deployAsset(String assetName, String destPath) {
        java.io.File staged = null;
        try {
            staged = new java.io.File(getCacheDir(), assetName);
            java.io.InputStream is = getAssets().open(assetName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(staged);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            fos.close();
            is.close();
            ShellUtils.CommandResult r = ShellUtils.execRoot(
                "cp " + staged.getAbsolutePath() + " " + destPath +
                " && chmod 755 " + destPath, false);
            return r.isSuccess();
        } catch (Exception e) {
            ShellUtils.appendLog("deployAsset error: " + e.getMessage());
            return false;
        } finally {
            if (staged != null) staged.delete();
        }
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
