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
    // Banner mode marker: shows DRY RUN when nothing is being applied.
    private TextView authorView;

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
    // Boot-loop guard banner. Hidden unless rules are actually suspended, so the
    // header stays as it was in the normal case.
    private TextView bootSuspendView = null;
    private Button bootResumeBtn = null;
    private TextView bootStatusView = null;   // "[OK] BOOT: <stamp>" header line

    // Last self-protection probe result (uid|bgOp|dozeExempt|processLimit) + its
    // timestamp. Probed once on launch and on demand — never on a timer.
    private volatile String selfProtectRaw = null;
    private TextView protectStatusView = null;
    private Button tabSysBtn, tabAppsBtn, tabDiagBtn;
    private View tabSysIndicator, tabAppsIndicator, tabDiagIndicator;

    // Sub-tabs inside Tab 0 (SYSTEM)
    private int currentSubTab = 0; // 0 = READING, 1 = SENSORS, 2 = STANDBY, 3 = DEBLOAT, 4 = APPS
    private LinearLayout pinnedBottomContainer = null;
    private TextView telemetrySensorsView = null;
    private TextView telemetryDozeView = null;
    private TextView telemetryCpuView = null;
    private TextView telemetryBattView = null;

    private interface ChipSelectListener {
        void onChipSelected(String value);
    }

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

    private LinearLayout createStepperChips(String[] labels, final String[] values, String currentVal, final ChipSelectListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dpToPx(6);
        rlp.bottomMargin = dpToPx(4);
        row.setLayoutParams(rlp);

        final TextView[] chipViews = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(chip, 10);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6));

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) clp.leftMargin = dpToPx(4);
            chip.setLayoutParams(clp);

            boolean isSelected = values[i].equals(currentVal);
            styleChip(chip, isSelected);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < chipViews.length; j++) {
                        styleChip(chipViews[j], j == idx);
                    }
                    listener.onChipSelected(values[idx]);
                }
            });

            chipViews[i] = chip;
            row.addView(chip);
        }
        return row;
    }

    private void styleChip(TextView chip, boolean isSelected) {
        if (isSelected) {
            chip.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, dpToPx(2)));
            chip.setTextColor(Color.WHITE);
        } else {
            chip.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, dpToPx(2)));
            chip.setTextColor(Color.BLACK);
        }
    }

    private TextView createTelemetryText(String initial) {
        TextView tv = new TextView(this);
        tv.setText(initial);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(tv, 9.5f);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tv.setPadding(dpToPx(2), dpToPx(3), dpToPx(2), dpToPx(3));
        return tv;
    }

    private void updateTelemetryStrip() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ShellUtils.CommandResult res = ShellUtils.execRoot(
                    "echo \"$(cat /sys/devices/system/cpu/cpu*/online 2>/dev/null | grep -c 1)|" +
                    "$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null)|" +
                    "$(dumpsys deviceidle 2>/dev/null | grep -o 'mState=[A-Z_]*' | head -1)|" +
                    "$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)|" +
                    "$(cat /sys/class/power_supply/battery/current_now 2>/dev/null)\"", false);
                String out = (res != null && res.stdout != null) ? res.stdout.trim() : "";
                String[] parts = out.split("\\|", -1);
                int onlineCount = 8;
                try { if (parts.length > 0 && !parts[0].isEmpty()) onlineCount = Integer.parseInt(parts[0]); } catch (Exception ignored) {}
                String freqStr = (parts.length > 1 && !parts[1].isEmpty()) ? (Integer.parseInt(parts[1]) / 1000 + "MHz") : "--";
                String dozeState = (parts.length > 2 && !parts[2].isEmpty()) ? parts[2].replace("mState=", "") : "ACTIVE";
                String capStr = (parts.length > 3 && !parts[3].isEmpty()) ? parts[3] + "%" : "--";
                int curMa = (parts.length > 4 && !parts[4].isEmpty()) ? parseCurrent(parts[4]) : 0;

                int activeSensors = 0;
                for (ConfigManager.SensorItem si : ConfigManager.SENSORS) {
                    if ("1".equals(currentConfig.getProperty(si.key, si.defaultVal))) activeSensors++;
                }

                final String cpuTxt = onlineCount + "C@" + freqStr;
                final String dozeTxt = "DOZE:" + ("1".equals(currentConfig.getProperty("INSTANT_DEEP_DOZE", "0")) ? "Instant" : dozeState);
                final String sensTxt = "SENS:" + (activeSensors == 0 ? "Muted" : activeSensors + "/6");
                final String battTxt = capStr + "(" + (curMa >= 0 ? "+" : "") + curMa + "mA)";

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (telemetryCpuView != null) telemetryCpuView.setText(cpuTxt);
                        if (telemetryDozeView != null) telemetryDozeView.setText(dozeTxt);
                        if (telemetrySensorsView != null) telemetrySensorsView.setText(sensTxt);
                        if (telemetryBattView != null) telemetryBattView.setText(battTxt);
                    }
                });
            }
        }).start();
    }

    private TextView addShieldRow(LinearLayout parent, String label, final String configKey, final String onCmd, final String offCmd) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(6), 0, dpToPx(6));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTypeface(Typeface.DEFAULT);
        setSp(tv, 10);
        tv.setTextColor(Color.BLACK);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        row.addView(tv);

        final TextView pillBtn = new TextView(this);
        pillBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(pillBtn, 10);
        pillBtn.setGravity(Gravity.CENTER);
        pillBtn.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));

        boolean checked = "1".equals(currentConfig.getProperty(configKey, "0"));
        updatePillView(pillBtn, checked);
        row.addView(pillBtn);

        final Runnable toggle = new Runnable() {
            @Override
            public void run() {
                boolean cur = "1".equals(currentConfig.getProperty(configKey, "0"));
                boolean next = !cur;
                currentConfig.setProperty(configKey, next ? "1" : "0");
                ConfigManager.saveConfig(currentConfig);
                updatePillView(pillBtn, next);
                final String cmd = next ? onCmd : offCmd;
                if (cmd != null && !cmd.isEmpty()) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() { ShellUtils.execRootAction(cmd); }
                    }).start();
                }
            }
        };

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggle.run(); }
        });
        pillBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggle.run(); }
        });

        parent.addView(row);
        return pillBtn;
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
                // 0600, not 0666: this log records package names and root commands,
                // and /data/local/tmp is world-traversable.
                ShellUtils.execRoot("touch " + ShellUtils.LOG_PATH + " && chmod 600 " + ShellUtils.LOG_PATH + " 2>/dev/null", false);
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
        // Long-press title = APPLY ALL RULES (zero screen cost)
        title.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showApplyAllDialog();
                return true;
            }
        });
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

        // ── Telemetry row (merged into header) ──────────────────────────────
        LinearLayout telemRow = new LinearLayout(this);
        telemRow.setOrientation(LinearLayout.HORIZONTAL);
        telemRow.setPadding(0, dpToPx(4), 0, 0);
        telemRow.setGravity(Gravity.CENTER_VERTICAL);

        telemetryCpuView = createTelemetryText("CPU: --");
        telemetryDozeView = createTelemetryText("DOZE: --");
        telemetrySensorsView = createTelemetryText("SENS: --");
        telemetryBattView = createTelemetryText("BATT: --");

        telemRow.addView(telemetryCpuView);
        telemRow.addView(telemetryDozeView);
        telemRow.addView(telemetrySensorsView);
        telemRow.addView(telemetryBattView);

        telemRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { updateTelemetryStrip(); }
        });

        header.addView(telemRow);
        updateTelemetryStrip();

        // Boot confirmation (refreshed after a resume, which rewrites the stamp)
        bootStatusView = new TextView(this);
        bootStatusView.setText(bootStatusText());
        setSp(bootStatusView, 11);
        bootStatusView.setTypeface(Typeface.DEFAULT);
        bootStatusView.setTextColor(Color.WHITE);
        bootStatusView.setPadding(0, dpToPx(2), 0, 0);
        header.addView(bootStatusView);

        // ── Boot-loop suspension notice ────────────────────────────────────
        // Suspension is invisible unless it is said out loud, and a silently
        // skipped rule pass just looks like the app stopped working. This is the
        // only place the user learns about it - and the only way back.
        bootSuspendView = new TextView(this);
        setSp(bootSuspendView, 11);
        bootSuspendView.setTypeface(Typeface.DEFAULT_BOLD);
        bootSuspendView.setTextColor(Color.WHITE);
        bootSuspendView.setPadding(0, dpToPx(4), 0, 0);
        header.addView(bootSuspendView);

        bootResumeBtn = new Button(this);
        bootResumeBtn.setText("RESUME RULES");
        setSp(bootResumeBtn, 11);
        styleEinkButton(bootResumeBtn, false);
        bootResumeBtn.setPadding(0, dpToPx(6), 0, dpToPx(6));
        bootResumeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bootResumeBtn.setEnabled(false);
                bootResumeBtn.setText("RESUMING...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // applyAllRulesPublic already clears the streak and the flag,
                        // so RESUME and APPLY ALL mean exactly the same thing.
                        ShellUtils.appendLog("Boot-loop guard cleared by user, re-applying rules");
                        new BootReceiver().applyAllRulesPublic(MainActivity.this);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                bootResumeBtn.setEnabled(true);
                                bootResumeBtn.setText("RESUME RULES");
                                updateBootSuspensionBanner();
                                updateBootStatusLine();
                                if (logDrawerView != null && logDrawerVisible) {
                                    logDrawerView.setText(ShellUtils.readLog(15));
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        header.addView(bootResumeBtn);
        updateBootSuspensionBanner();

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
        contentContainer.setPadding(0, 0, 0, dpToPx(16));
        mainScrollView.addView(contentContainer);
        root.addView(mainScrollView);

        // ── Pinned Bottom Bar (Sub-tabs + Apply Button always visible on SYSTEM tab) ──
        pinnedBottomContainer = new LinearLayout(this);
        pinnedBottomContainer.setOrientation(LinearLayout.VERTICAL);
        pinnedBottomContainer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pinnedBottomContainer.setBackgroundColor(Color.WHITE);
        root.addView(pinnedBottomContainer);

        setContentView(root);
        updateTabStyles();
        renderCurrentTab();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        if (pinnedBottomContainer != null) {
            pinnedBottomContainer.removeAllViews();
            pinnedBottomContainer.setVisibility(activeTab == 0 ? View.VISIBLE : View.GONE);
        }
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

        // ── 1. Manager Self-Protection Card (Collapsed by default to save screen space) ──
        addSectionHeader("PROTECTION", "The manager must never restrict itself", false);
        addProtectionCard(logDrawer);

        // ── 3. Sub-Tabs Bar (Replaces 10-screen deep vertical scrolling) ───
        final String[] subTabNames = {"READING", "SENSORS", "STANDBY", "DEBLOAT", "APPS"};
        LinearLayout subTabBar = new LinearLayout(this);
        subTabBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(6));
        subTabBar.setLayoutParams(stlp);
        subTabBar.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));

        for (int i = 0; i < subTabNames.length; i++) {
            final int sIdx = i;
            TextView sTab = new TextView(this);
            sTab.setText(subTabNames[i]);
            sTab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(sTab, 11);
            sTab.setGravity(Gravity.CENTER);
            sTab.setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            sTab.setLayoutParams(tlp);

            boolean isCurrent = (currentSubTab == sIdx);
            if (isCurrent) {
                sTab.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, 0));
                sTab.setTextColor(Color.WHITE);
            } else {
                sTab.setBackgroundColor(Color.TRANSPARENT);
                sTab.setTextColor(Color.BLACK);
            }

            sTab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentSubTab = sIdx;
                    activeTab = 0;
                    renderCurrentTab();
                    mainScrollView.post(new Runnable() {
                        @Override
                        public void run() { mainScrollView.scrollTo(0, 0); }
                    });
                }
            });
            subTabBar.addView(sTab);
        }

        if (pinnedBottomContainer != null) {
            View borderTop = new View(this);
            borderTop.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(2)));
            borderTop.setBackgroundColor(Color.BLACK);
            pinnedBottomContainer.addView(borderTop);
            pinnedBottomContainer.addView(subTabBar);
        } else {
            contentContainer.addView(subTabBar);
        }

        // ── 4. Active Sub-Tab Content ──────────────────────────────────────
        switch (currentSubTab) {
            case 0: // ── READING ──
                addSectionHeader("READING MODE", "Optimizations for active screen-on reading");

                // Wake Governor with 3 inline stepper chips
                {
                    final LinearLayout wakeCard = new LinearLayout(this);
                    wakeCard.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams wclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    wclp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                    wakeCard.setLayoutParams(wclp);
                    wakeCard.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                    wakeCard.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

                    TextView wTitle = new TextView(this);
                    wTitle.setText("WAKE CPU GOVERNOR");
                    setSp(wTitle, 12);
                    wTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    wTitle.setTextColor(Color.BLACK);
                    wakeCard.addView(wTitle);

                    final TextView wDesc = new TextView(this);
                    setSp(wDesc, 10);
                    wDesc.setTextColor(Color.BLACK);
                    wDesc.setPadding(0, dpToPx(4), 0, dpToPx(6));
                    wakeCard.addView(wDesc);

                    final String[] wakeLabels = {"E-READER (4C)", "BALANCED (8C)", "STOCK"};
                    final String[] wakeVals = {"ereader_battery", "schedutil_efficient", "stock"};
                    final String curWake = currentConfig.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");

                    final Runnable updateWakeDesc = new Runnable() {
                        @Override
                        public void run() {
                            String p = currentConfig.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");
                            if ("ereader_battery".equals(p)) {
                                wDesc.setText("4 Little cores (cpu0-3 @ 900-1351MHz). Big cores 4-7 offline. Zero leakage.");
                            } else if ("stock".equals(p)) {
                                wDesc.setText("Factory MediaTek policy (Policy 7 locked at 2.06GHz, all 8 cores).");
                            } else {
                                wDesc.setText("Dynamic 8 cores (900-2200MHz PPM uncapped). Smooth multitasking.");
                            }
                        }
                    };
                    updateWakeDesc.run();

                    wakeCard.addView(createStepperChips(wakeLabels, wakeVals, curWake, new ChipSelectListener() {
                        @Override
                        public void onChipSelected(String selected) {
                            currentConfig.setProperty("GOVERNOR_PROFILE", selected);
                            currentConfig.setProperty("HOTPLUG_4_CORES", "ereader_battery".equals(selected) ? "1" : "0");
                            currentConfig.setProperty("CPU_OPTIMIZER", "stock".equals(selected) ? "0" : "1");
                            ConfigManager.saveConfig(currentConfig);
                            updateWakeDesc.run();
                            ShellUtils.execRootAction(ConfigManager.buildGovernorCmd(selected, currentConfig.getProperty("HOTPLUG_4_CORES", "0")));
                            if (!"stock".equals(selected)) {
                                ShellUtils.execRootAction(ConfigManager.getCpuUncapCmd());
                            }
                            updateTelemetryStrip();
                        }
                    }));
                    addSectionContent(wakeCard);
                }

                addToggle("TOUCH_BOOST_SAVER", "TOUCH_BOOST_SAVER",
                    "Prevent 2.06GHz frequency spikes when tapping to flip pages (clamps EAS uclamp boost)",
                    false,
                    ConfigManager.getTouchBoostSaverCmd(true),
                    ConfigManager.getTouchBoostSaverCmd(false),
                    "cat /sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us 2>/dev/null | grep -q 100000 && echo THROTTLED || echo DEFAULT",
                    logDrawer);

                addToggle("ANIMATIONS_0", "DISABLE_ANIMATIONS",
                    "Window, transition, animator scales to 0.0x and blurs disabled for instant E-ink",
                    false,
                    "settings put global window_animation_scale 0.0 2>/dev/null; settings put global transition_animation_scale 0.0 2>/dev/null; settings put global animator_duration_scale 0.0 2>/dev/null; settings put global disable_window_blurs 1 2>/dev/null; setprop persist.sys.sf.disable_blurs 1 2>/dev/null",
                    "settings put global window_animation_scale 1.0 2>/dev/null; settings put global transition_animation_scale 1.0 2>/dev/null; settings put global animator_duration_scale 1.0 2>/dev/null; settings put global disable_window_blurs 0 2>/dev/null",
                    "settings get global window_animation_scale",
                    logDrawer);

                // Keys & Gestures
                {
                    final String capPkg = "com.xrz.ebook.launcher";
                    final LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    clp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                    card.setLayoutParams(clp);
                    card.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                    card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

                    LinearLayout topRow = new LinearLayout(this);
                    topRow.setOrientation(LinearLayout.HORIZONTAL);
                    topRow.setGravity(Gravity.CENTER_VERTICAL);

                    TextView tTitle = new TextView(this);
                    tTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                    tTitle.setText("KEYS & GESTURES");
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

                    TextView descView = new TextView(this);
                    setSp(descView, 10);
                    descView.setTextColor(Color.BLACK);
                    descView.setText("Unfreeze Bigme launcher to customize hardware side keys or edge gestures");
                    descView.setPadding(0, dpToPx(4), 0, dpToPx(6));
                    card.addView(descView);

                    LinearLayout toggleRow = new LinearLayout(this);
                    toggleRow.setOrientation(LinearLayout.HORIZONTAL);
                    toggleRow.setGravity(Gravity.CENTER_VERTICAL);

                    final Button capToggleBtn = new Button(this);
                    setSp(capToggleBtn, 10);
                    capToggleBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    capToggleBtn.setTextColor(Color.BLACK);
                    capToggleBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, dpToPx(2)));
                    capToggleBtn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
                    LinearLayout.LayoutParams togLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    capToggleBtn.setLayoutParams(togLp);
                    toggleRow.addView(capToggleBtn);
                    card.addView(toggleRow);

                    LinearLayout shortcutRow = new LinearLayout(this);
                    shortcutRow.setOrientation(LinearLayout.HORIZONTAL);
                    shortcutRow.setGravity(Gravity.CENTER_VERTICAL);
                    shortcutRow.setPadding(0, dpToPx(6), 0, 0);

                    final Button capKeysBtn = new Button(this);
                    setSp(capKeysBtn, 10);
                    capKeysBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    capKeysBtn.setText("[ CUSTOM KEYS ]");
                    capKeysBtn.setTextColor(Color.BLACK);
                    capKeysBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, dpToPx(2)));
                    capKeysBtn.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
                    LinearLayout.LayoutParams keysLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                    keysLp.rightMargin = dpToPx(4);
                    capKeysBtn.setLayoutParams(keysLp);
                    shortcutRow.addView(capKeysBtn);

                    final Button capGesturesBtn = new Button(this);
                    setSp(capGesturesBtn, 10);
                    capGesturesBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    capGesturesBtn.setText("[ GESTURE SETTINGS ]");
                    capGesturesBtn.setTextColor(Color.BLACK);
                    capGesturesBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, dpToPx(2)));
                    capGesturesBtn.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
                    LinearLayout.LayoutParams gestLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                    capGesturesBtn.setLayoutParams(gestLp);
                    shortcutRow.addView(capGesturesBtn);

                    card.addView(shortcutRow);

                    final Runnable updateCapState = new Runnable() {
                        @Override
                        public void run() {
                            boolean enabled = false;
                            try {
                                enabled = getPackageManager().getApplicationInfo(capPkg, 0).enabled;
                            } catch (Exception e) { /* not installed */ }
                            if (enabled) {
                                pillBtn.setText("[ ○ ACTIVE ]");
                                pillBtn.setTextColor(Color.BLACK);
                                pillBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, dpToPx(2)));
                                capToggleBtn.setText("FREEZE LAUNCHER NOW");
                                capKeysBtn.setEnabled(true);
                                capKeysBtn.setAlpha(1.0f);
                                capGesturesBtn.setEnabled(true);
                                capGesturesBtn.setAlpha(1.0f);
                            } else {
                                pillBtn.setText("[ ● FROZEN ]");
                                pillBtn.setTextColor(Color.WHITE);
                                pillBtn.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, dpToPx(2)));
                                capToggleBtn.setText("UNFREEZE TO CONFIGURE");
                                capKeysBtn.setEnabled(false);
                                capKeysBtn.setAlpha(0.35f);
                                capGesturesBtn.setEnabled(false);
                                capGesturesBtn.setAlpha(0.35f);
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

                    capKeysBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                android.content.Intent intent = new android.content.Intent("com.xrz.xlauncher.action.custom_menu");
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });

                    capGesturesBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                android.content.Intent intent = new android.content.Intent("com.xrz.xlauncher.action.settings_func");
                                intent.putExtra("model", 11);
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });

                    addSectionContent(card);
                }

                addToggle("FIX_UART", "FIX_UART",
                    "Kill runaway uart2serport daemon & clear early-boot crash latch",
                    false,
                    "setprop sys.init.updatable_crashing \"\" 2>/dev/null; setprop sys.init.updatable_crashing_process_name \"\" 2>/dev/null; setprop ctl.stop uart2serport 2>/dev/null; setprop persist.vendor.uart2serport.enable 0 2>/dev/null; kill -9 $(pidof uart2serport) 2>/dev/null; chmod 755 /system/bin/start_uart2serport.sh 2>/dev/null",
                    "setprop persist.vendor.uart2serport.enable 1 2>/dev/null",
                    "[ -f /system/bin/start_uart2serport.sh ] && [ -z \"$(getprop sys.init.updatable_crashing)\" ] && echo 'STUBBED_OK' || echo 'CRASH_LOOP'",
                    logDrawer);
                break;

            case 1: // ── SENSORS ──
                addSectionHeader("SENSORS", "Mute background sensor listeners to drop silicon into 0.001mA sleep");
                addSensorsControlCard(logDrawer);
                break;

            case 2: // ── STANDBY & SUSPEND ──
                addSectionHeader("STANDBY & SUSPEND", "Eliminate background wakeups and drain while screen is off");

                // Sleep Governor card with 3 inline chips
                {
                    final LinearLayout sleepCard = new LinearLayout(this);
                    sleepCard.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    sclp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                    sleepCard.setLayoutParams(sclp);
                    sleepCard.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                    sleepCard.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

                    TextView sTitle = new TextView(this);
                    sTitle.setText("SLEEP CPU GOVERNOR (STANDBY)");
                    setSp(sTitle, 12);
                    sTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    sTitle.setTextColor(Color.BLACK);
                    sleepCard.addView(sTitle);

                    final TextView sDesc = new TextView(this);
                    setSp(sDesc, 10);
                    sDesc.setTextColor(Color.BLACK);
                    sDesc.setPadding(0, dpToPx(4), 0, dpToPx(6));
                    sleepCard.addView(sDesc);

                    final String[] sleepLabels = {"DEEP SLEEP", "LIGHT SYNC", "STOCK"};
                    final String[] sleepVals = {"deep_sleep", "ereader_battery", "stock"};
                    final String curSleep = currentConfig.getProperty("SLEEP_GOVERNOR", "deep_sleep");

                    final Runnable updateSleepDesc = new Runnable() {
                        @Override
                        public void run() {
                            String p = currentConfig.getProperty("SLEEP_GOVERNOR", "deep_sleep");
                            if ("ereader_battery".equals(p)) {
                                sDesc.setText("4 Cores (900-1351MHz). Allows background downloads/sync to finish.");
                            } else if ("stock".equals(p)) {
                                sDesc.setText("Factory MediaTek policy in standby.");
                            } else {
                                sDesc.setText("Hard locked to 900/400MHz floor. Max standby battery savings.");
                            }
                        }
                    };
                    updateSleepDesc.run();

                    sleepCard.addView(createStepperChips(sleepLabels, sleepVals, curSleep, new ChipSelectListener() {
                        @Override
                        public void onChipSelected(String selected) {
                            currentConfig.setProperty("SLEEP_GOVERNOR", selected);
                            currentConfig.setProperty("SLEEP_GOVERNOR_ENABLED", "1");
                            ConfigManager.saveConfig(currentConfig);
                            updateSleepDesc.run();
                            HiBigApp.registerScreenReceiver();
                            ShellUtils.appendLog("Sleep Governor set to: " + selected);
                            updateTelemetryStrip();
                        }
                    }));
                    addSectionContent(sleepCard);
                }

                // Doze Standby Speed with 3 inline chips
                {
                    final LinearLayout dozeCard = new LinearLayout(this);
                    dozeCard.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    dclp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                    dozeCard.setLayoutParams(dclp);
                    dozeCard.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                    dozeCard.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

                    TextView dTitle = new TextView(this);
                    dTitle.setText("DOZE STANDBY SPEED");
                    setSp(dTitle, 12);
                    dTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    dTitle.setTextColor(Color.BLACK);
                    dozeCard.addView(dTitle);

                    final TextView dDesc = new TextView(this);
                    setSp(dDesc, 10);
                    dDesc.setTextColor(Color.BLACK);
                    dDesc.setPadding(0, dpToPx(4), 0, dpToPx(6));
                    dozeCard.addView(dDesc);

                    final String[] dozeLabels = {"INSTANT (15s)", "AGGRESSIVE (5m)", "STOCK"};
                    final String[] dozeVals = {"instant", "aggressive", "stock"};
                    String initialDoze = "1".equals(currentConfig.getProperty("INSTANT_DEEP_DOZE", "0")) ? "instant"
                        : ("1".equals(currentConfig.getProperty("AGGRESSIVE_DOZE", "0")) ? "aggressive" : "stock");

                    final Runnable updateDozeDesc = new Runnable() {
                        @Override
                        public void run() {
                            if ("1".equals(currentConfig.getProperty("INSTANT_DEEP_DOZE", "0"))) {
                                dDesc.setText("Drops to deep sleep in 15 seconds. Standby current falls to ~2-5 mA immediately. (Alarms still ring)");
                            } else if ("1".equals(currentConfig.getProperty("AGGRESSIVE_DOZE", "0"))) {
                                dDesc.setText("Shortens inactive delays: deep doze in 5 minutes instead of 30 minutes.");
                            } else {
                                dDesc.setText("Stock Android 14 timing: waits 30 minutes of motionless inactivity before deep sleep.");
                            }
                        }
                    };
                    updateDozeDesc.run();

                    dozeCard.addView(createStepperChips(dozeLabels, dozeVals, initialDoze, new ChipSelectListener() {
                        @Override
                        public void onChipSelected(String selected) {
                            if ("instant".equals(selected)) {
                                currentConfig.setProperty("INSTANT_DEEP_DOZE", "1");
                                currentConfig.setProperty("AGGRESSIVE_DOZE", "1");
                                ShellUtils.execRootAction(ConfigManager.getAggressiveDozeCmd(true));
                            } else if ("aggressive".equals(selected)) {
                                currentConfig.setProperty("INSTANT_DEEP_DOZE", "0");
                                currentConfig.setProperty("AGGRESSIVE_DOZE", "1");
                                ShellUtils.execRootAction(ConfigManager.getInstantDeepDozeCmd(false));
                                ShellUtils.execRootAction(ConfigManager.getAggressiveDozeCmd(true));
                            } else {
                                currentConfig.setProperty("INSTANT_DEEP_DOZE", "0");
                                currentConfig.setProperty("AGGRESSIVE_DOZE", "0");
                                ShellUtils.execRootAction(ConfigManager.getInstantDeepDozeCmd(false));
                                ShellUtils.execRootAction(ConfigManager.getAggressiveDozeCmd(false));
                            }
                            ConfigManager.saveConfig(currentConfig);
                            updateDozeDesc.run();
                            updateTelemetryStrip();
                        }
                    }));
                    addSectionContent(dozeCard);
                }

                addToggle("WIFI_SLEEP", "WIFI_SLEEP_ZERO",
                    "Disable Wi-Fi scans & interfaces in standby (~5 mA)",
                    false,
                    "settings put global wifi_sleep_policy 0 2>/dev/null; settings put global wifi_idle_ms 5000 2>/dev/null; settings put global wifi_scan_always_enabled 0 2>/dev/null; cmd wifi set-scan-always-available 0 2>/dev/null",
                    "settings put global wifi_sleep_policy 2 2>/dev/null; settings put global wifi_scan_always_enabled 1 2>/dev/null",
                    "settings get global wifi_sleep_policy",
                    logDrawer);

                // Suspend & Alarm Shield
                {
                    final LinearLayout shieldCard = new LinearLayout(this);
                    shieldCard.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    shlp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                    shieldCard.setLayoutParams(shlp);
                    shieldCard.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                    shieldCard.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

                    LinearLayout shTop = new LinearLayout(this);
                    shTop.setOrientation(LinearLayout.HORIZONTAL);
                    shTop.setGravity(Gravity.CENTER_VERTICAL);

                    TextView shTitle = new TextView(this);
                    shTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                    shTitle.setText("SUSPEND & ALARM SHIELD");
                    setSp(shTitle, 12);
                    shTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    shTitle.setTextColor(Color.BLACK);

                    final TextView shPill = new TextView(this);
                    shPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    setSp(shPill, 12);
                    shPill.setGravity(Gravity.CENTER);
                    shPill.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));

                    shTop.addView(shTitle);
                    shTop.addView(shPill);
                    shieldCard.addView(shTop);

                    TextView shDesc = new TextView(this);
                    shDesc.setText("Protects kernel suspend and batches alarms to prevent sleep interruptions");
                    setSp(shDesc, 10);
                    shDesc.setTextColor(Color.BLACK);
                    shDesc.setPadding(0, dpToPx(4), 0, dpToPx(6));
                    shieldCard.addView(shDesc);

                    final TextView p1 = addShieldRow(shieldCard, "Kill Bigme shutdown timer (Intent Firewall)", "KILL_SHUTDOWN_ALARM", ConfigManager.getKillShutdownAlarmCmd(true), ConfigManager.getKillShutdownAlarmCmd(false));
                    final TextView p2 = addShieldRow(shieldCard, "Batch background alarms (10-45m fuzz, skip time_tick)", "WIDE_ALARM_FUZZ", ConfigManager.getWideAlarmFuzzCmd(true), ConfigManager.getWideAlarmFuzzCmd(false));
                    final TextView p3 = addShieldRow(shieldCard, "Throttle JobScheduler idle alarms (threshold 99)", "SUPPRESS_JS_IDLE", ConfigManager.getSuppressJsIdleCmd(true), ConfigManager.getSuppressJsIdleCmd(false));
                    final TextView p4 = addShieldRow(shieldCard, "Instant keyguard lock on power-off (zero timer race)", "INSTANT_LOCK", ConfigManager.getInstantLockCmd(true), ConfigManager.getInstantLockCmd(false));

                    final Runnable updateShieldPill = new Runnable() {
                        @Override
                        public void run() {
                            boolean allOn = "1".equals(currentConfig.getProperty("KILL_SHUTDOWN_ALARM", "0")) &&
                                            "1".equals(currentConfig.getProperty("WIDE_ALARM_FUZZ", "0")) &&
                                            "1".equals(currentConfig.getProperty("SUPPRESS_JS_IDLE", "0")) &&
                                            "1".equals(currentConfig.getProperty("INSTANT_LOCK", "0"));
                            updatePillView(shPill, allOn);
                        }
                    };
                    updateShieldPill.run();

                    shPill.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean anyOn = "1".equals(currentConfig.getProperty("KILL_SHUTDOWN_ALARM", "0")) ||
                                            "1".equals(currentConfig.getProperty("WIDE_ALARM_FUZZ", "0")) ||
                                            "1".equals(currentConfig.getProperty("SUPPRESS_JS_IDLE", "0")) ||
                                            "1".equals(currentConfig.getProperty("INSTANT_LOCK", "0"));
                            boolean target = !anyOn;
                            String val = target ? "1" : "0";
                            currentConfig.setProperty("KILL_SHUTDOWN_ALARM", val);
                            currentConfig.setProperty("WIDE_ALARM_FUZZ", val);
                            currentConfig.setProperty("SUPPRESS_JS_IDLE", val);
                            currentConfig.setProperty("INSTANT_LOCK", val);
                            ConfigManager.saveConfig(currentConfig);
                            updatePillView(p1, target);
                            updatePillView(p2, target);
                            updatePillView(p3, target);
                            updatePillView(p4, target);
                            updateShieldPill.run();
                            ShellUtils.execRootAction(ConfigManager.getKillShutdownAlarmCmd(target) + "; " +
                                                      ConfigManager.getWideAlarmFuzzCmd(target) + "; " +
                                                      ConfigManager.getSuppressJsIdleCmd(target) + "; " +
                                                      ConfigManager.getInstantLockCmd(target));
                        }
                    });
                    addSectionContent(shieldCard);
                }

                // Charge Ceiling with 5 inline chips
                if (ConfigManager.isChargeLimitAvailable()) {
                    final LinearLayout limitCard = new LinearLayout(this);
                    limitCard.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lclp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                    limitCard.setLayoutParams(lclp);
                    limitCard.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                    limitCard.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

                    LinearLayout lTop = new LinearLayout(this);
                    lTop.setOrientation(LinearLayout.HORIZONTAL);
                    lTop.setGravity(Gravity.CENTER_VERTICAL);

                    TextView lTitle = new TextView(this);
                    lTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                    lTitle.setText("CHARGE CEILING");
                    setSp(lTitle, 12);
                    lTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    lTitle.setTextColor(Color.BLACK);

                    final TextView lPill = new TextView(this);
                    lPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    setSp(lPill, 12);
                    lPill.setGravity(Gravity.CENTER);
                    lPill.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));

                    lTop.addView(lTitle);
                    lTop.addView(lPill);
                    limitCard.addView(lTop);

                    TextView lDesc = new TextView(this);
                    lDesc.setText("Holds battery at target; phone runs off charger instead of sitting at 100% all night");
                    setSp(lDesc, 10);
                    lDesc.setTextColor(Color.BLACK);
                    lDesc.setPadding(0, dpToPx(4), 0, dpToPx(6));
                    limitCard.addView(lDesc);

                    final String[] limitLabels = {"75%", "80%", "85%", "90%", "CUSTOM"};
                    final String[] limitVals = {"75", "80", "85", "90", "custom"};
                    String curLimit = String.valueOf(ConfigManager.getChargeLimitPct());

                    final TextView lState = new TextView(this);
                    setSp(lState, 10);
                    lState.setTypeface(Typeface.DEFAULT);
                    lState.setTextColor(Color.BLACK);
                    lState.setPadding(0, dpToPx(4), 0, dpToPx(2));
                    lState.setText(ChargeLimitController.describe(this));

                    final Runnable updateLimitPill = new Runnable() {
                        @Override
                        public void run() {
                            boolean on = "1".equals(currentConfig.getProperty("BATTERY_CAP_85", "0"));
                            updatePillView(lPill, on);
                            lState.setText(ChargeLimitController.describe(MainActivity.this));
                        }
                    };
                    updateLimitPill.run();

                    lPill.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean on = "1".equals(currentConfig.getProperty("BATTERY_CAP_85", "0"));
                            currentConfig.setProperty("BATTERY_CAP_85", on ? "0" : "1");
                            ConfigManager.saveConfig(currentConfig);
                            ChargeLimitController.applyConfig(MainActivity.this);
                            updateLimitPill.run();
                        }
                    });

                    limitCard.addView(createStepperChips(limitLabels, limitVals, curLimit, new ChipSelectListener() {
                        @Override
                        public void onChipSelected(String selected) {
                            if ("custom".equals(selected)) {
                                final EditText input = new EditText(MainActivity.this);
                                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                                input.setHint("Percentage (e.g. 78)");
                                input.setText(String.valueOf(ConfigManager.getChargeLimitPct()));
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("CUSTOM CHARGE LIMIT")
                                    .setView(input)
                                    .setPositiveButton("SET", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface di, int btn) {
                                            String val = input.getText().toString().trim();
                                            if (!val.isEmpty()) {
                                                try {
                                                    int pct = Integer.parseInt(val);
                                                    if (pct >= 50 && pct <= 95) {
                                                        currentConfig.setProperty("CHARGE_LIMIT_PCT", String.valueOf(pct));
                                                        ConfigManager.saveConfig(currentConfig);
                                                        ChargeLimitController.applyConfig(MainActivity.this);
                                                        updateLimitPill.run();
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        }
                                    })
                                    .setNegativeButton("CANCEL", null).show();
                            } else {
                                currentConfig.setProperty("CHARGE_LIMIT_PCT", selected);
                                ConfigManager.saveConfig(currentConfig);
                                ChargeLimitController.applyConfig(MainActivity.this);
                                updateLimitPill.run();
                            }
                        }
                    }));

                    limitCard.addView(lState);
                    addSectionContent(limitCard);
                }

                // Inactivity Auto-Shutdown card
                {
                    final LinearLayout shutCard = new LinearLayout(this);
                    shutCard.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    sclp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                    shutCard.setLayoutParams(sclp);
                    shutCard.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
                    shutCard.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

                    LinearLayout sTop = new LinearLayout(this);
                    sTop.setOrientation(LinearLayout.HORIZONTAL);
                    sTop.setGravity(Gravity.CENTER_VERTICAL);

                    TextView sTitle = new TextView(this);
                    sTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                    sTitle.setText("INACTIVITY AUTO-SHUTDOWN");
                    setSp(sTitle, 12);
                    sTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    sTitle.setTextColor(Color.BLACK);

                    final TextView sPill = new TextView(this);
                    sPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    setSp(sPill, 12);
                    sPill.setGravity(Gravity.CENTER);
                    sPill.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));

                    sTop.addView(sTitle);
                    sTop.addView(sPill);
                    shutCard.addView(sTop);

                    TextView sDesc = new TextView(this);
                    sDesc.setText("Clean reboot -p after inactivity (retains current E-ink page at 0 mA permanently)");
                    setSp(sDesc, 10);
                    sDesc.setTextColor(Color.BLACK);
                    sDesc.setPadding(0, dpToPx(4), 0, dpToPx(6));
                    shutCard.addView(sDesc);

                    final String[] timeLabels = {"1H", "2H", "4H", "8H", "CUSTOM"};
                    final String[] timeVals = {"60", "120", "240", "480", "custom"};
                    String curTime = currentConfig.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120");

                    final Runnable updateShutPill = new Runnable() {
                        @Override
                        public void run() {
                            boolean on = "1".equals(currentConfig.getProperty("AUTO_SHUTDOWN_ENABLED", "0"));
                            updatePillView(sPill, on);
                        }
                    };
                    updateShutPill.run();

                    sPill.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean on = "1".equals(currentConfig.getProperty("AUTO_SHUTDOWN_ENABLED", "0"));
                            currentConfig.setProperty("AUTO_SHUTDOWN_ENABLED", on ? "0" : "1");
                            ConfigManager.saveConfig(currentConfig);
                            if (!on) {
                                ShutdownAlarmReceiver.scheduleAlarmWithConfig(MainActivity.this);
                            } else {
                                ShutdownAlarmReceiver.cancelAlarm(MainActivity.this);
                            }
                            updateShutPill.run();
                        }
                    });

                    shutCard.addView(createStepperChips(timeLabels, timeVals, curTime, new ChipSelectListener() {
                        @Override
                        public void onChipSelected(String selected) {
                            if ("custom".equals(selected)) {
                                final EditText input = new EditText(MainActivity.this);
                                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                                input.setHint("Minutes (e.g. 180)");
                                input.setText(currentConfig.getProperty("AUTO_SHUTDOWN_TIMEOUT_MIN", "120"));
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("CUSTOM INACTIVITY TIMEOUT")
                                    .setView(input)
                                    .setPositiveButton("SET", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface di, int btn) {
                                            String val = input.getText().toString().trim();
                                            if (!val.isEmpty()) {
                                                try {
                                                    int m = Integer.parseInt(val);
                                                    if (m > 0) {
                                                        applyTimeoutValue(String.valueOf(m), null, logDrawer);
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        }
                                    })
                                    .setNegativeButton("CANCEL", null).show();
                            } else {
                                applyTimeoutValue(selected, null, logDrawer);
                            }
                        }
                    }));

                    addShieldRow(shutCard, "Skip shutdown while plugged into charger", "AUTO_SHUTDOWN_SKIP_WHEN_CHARGING", "", "");
                    addShieldRow(shutCard, "Test Mode (log shutdown decision without rebooting)", "AUTO_SHUTDOWN_DRY_RUN", "", "");

                    addSectionContent(shutCard);
                }
                break;

            case 3: // ── DEBLOAT ──
                addSectionHeader("DEBLOAT", "Freeze background vendor, Google & cellular bloatware");

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

                addSectionHeader("BACKGROUND LEASHES", "Restrict apps without freezing them");

                addToggle("LOCKDOWN_GBOARD", "LOCKDOWN_GBOARD",
                    "Block background jobs, Superpacks sync & wakelocks for Gboard",
                    false,
                    "cmd appops set com.google.android.inputmethod.latin RUN_IN_BACKGROUND ignore 2>/dev/null; cmd appops set com.google.android.inputmethod.latin RUN_ANY_IN_BACKGROUND ignore 2>/dev/null; cmd appops set com.google.android.inputmethod.latin START_FOREGROUND ignore 2>/dev/null; cmd appops set com.google.android.inputmethod.latin WAKE_LOCK ignore 2>/dev/null; cmd jobscheduler cancel com.google.android.inputmethod.latin 2>/dev/null; am set-standby-bucket com.google.android.inputmethod.latin rare 2>/dev/null; dumpsys deviceidle whitelist -com.google.android.inputmethod.latin 2>/dev/null",
                    "cmd appops set com.google.android.inputmethod.latin RUN_IN_BACKGROUND allow 2>/dev/null; cmd appops set com.google.android.inputmethod.latin RUN_ANY_IN_BACKGROUND allow 2>/dev/null; cmd appops set com.google.android.inputmethod.latin START_FOREGROUND allow 2>/dev/null; cmd appops set com.google.android.inputmethod.latin WAKE_LOCK allow 2>/dev/null; am set-standby-bucket com.google.android.inputmethod.latin active 2>/dev/null",
                    "cmd appops get com.google.android.inputmethod.latin RUN_ANY_IN_BACKGROUND 2>/dev/null | head -1",
                    logDrawer);

                addToggle("RESTRICT_GMS", "SUPPRESS_ALARMS",
                    "Deny GMS exact alarms, background execution & wake locks",
                    false,
                    ConfigManager.getSuppressGmsAlarmsCmd(true),
                    ConfigManager.getSuppressGmsAlarmsCmd(false),
                    ConfigManager.getSuppressGmsAlarmsProbeCmd(),
                    logDrawer);
                break;

            case 4: // ── APPS ──
                addSectionHeader("APPS", "Essential E-Ink apps installer from GitHub releases");
                addAppInstallerCards(logDrawer);
                break;
        }
    }

    /** Long-press on header title triggers APPLY ALL RULES. */
    private void showApplyAllDialog() {
        new AlertDialog.Builder(this)
            .setTitle("APPLY ALL RULES?")
            .setMessage("Enforce all configured power management policies, CPU governors, and debloat rules via root?")
            .setPositiveButton("APPLY", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    Toast.makeText(MainActivity.this, "Applying all rules...", Toast.LENGTH_SHORT).show();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ConfigManager.saveConfig(currentConfig);
                            new BootReceiver().applyAllRulesPublic(MainActivity.this);
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    updateTelemetryStrip();
                                    Toast.makeText(MainActivity.this, "All rules applied", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }).start();
                }
            })
            .setNegativeButton("CANCEL", null)
            .show();
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

    /**
     * Shows the boot-loop suspension notice only while rules are actually
     * suspended, so the header is unchanged in the normal case.
     */
    private void updateBootSuspensionBanner() {
        if (bootSuspendView == null || bootResumeBtn == null) return;
        boolean suspended = ConfigManager.areRulesSuspended();
        int streak = ConfigManager.getBootStreak();
        bootSuspendView.setVisibility(suspended ? View.VISIBLE : View.GONE);
        bootResumeBtn.setVisibility(suspended ? View.VISIBLE : View.GONE);
        if (suspended) {
            bootSuspendView.setText("[!!] RULES SUSPENDED: " + streak
                + " fast boots in a row. Nothing is applied at boot.");
        }
    }

    private String bootStatusText() {
        String lastBoot = ConfigManager.getLastBootTime();
        return lastBoot.isEmpty() ? "[!!] BOOT: not applied yet" : "[OK] BOOT: " + lastBoot;
    }

    /** The resume path rewrites the stamp this line shows, so it has to be re-read. */
    private void updateBootStatusLine() {
        if (bootStatusView != null) bootStatusView.setText(bootStatusText());
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

    /**
     * Replaces the popup text with the persisted log, read as root on a background
     * thread. The in-memory list is shown first so the popup opens instantly; the
     * file version also carries entries from before this process started - the
     * boot-time rule pass, and anything logged while the app was dead.
     */
    private void loadPersistedLogInto(final TextView view) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String merged = ShellUtils.readLogMerged(200);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (view != null && view.getVisibility() == View.VISIBLE) {
                            view.setText(merged);
                        }
                    }
                });
            }
        }).start();
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
        loadPersistedLogInto(logDrawerView);

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
        addSectionHeader(text, null, true);
    }

    private void addSectionHeader(String text, String subtitle) {
        addSectionHeader(text, subtitle, true);
    }

    // Adds a collapsible section header and routes subsequent addSectionContent calls into its body.
    private void addSectionHeader(String text, String subtitle, boolean expandedByDefault) {
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
        chevron.setText(expandedByDefault ? "[ - ]" : "[ + ]");
        setSp(chevron, 12);
        chevron.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chevron.setTextColor(Color.WHITE);
        headerRow.addView(chevron);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout bodyWrap = new LinearLayout(this);
        bodyWrap.setOrientation(LinearLayout.VERTICAL);
        bodyWrap.setVisibility(expandedByDefault ? View.VISIBLE : View.GONE);
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
            pillBtn.setText("[ ● ON ]");
            pillBtn.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, dpToPx(2)));
            pillBtn.setTextColor(Color.WHITE);
        } else {
            pillBtn.setText("[ ○ OFF ]");
            pillBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, dpToPx(2)));
            pillBtn.setTextColor(Color.BLACK);
        }
    }

    private void updateDebloatPillView(TextView pillBtn, boolean isFrozen) {
        if (isFrozen) {
            pillBtn.setText("[ ● FROZEN ]");
            pillBtn.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, dpToPx(2)));
            pillBtn.setTextColor(Color.WHITE);
        } else {
            pillBtn.setText("[ ○ ACTIVE ]");
            pillBtn.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, dpToPx(2)));
            pillBtn.setTextColor(Color.BLACK);
        }
    }

    // ── addSensorsControlCard: Master toggle + permanently unfolded individual sensor controls ──
    private void addSensorsControlCard(final TextView logDrawer) {
        final ConfigManager.SensorItem[] sensors = ConfigManager.SENSORS;
        final TextView[] sensorPills = new TextView[sensors.length];

        // Main card
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        card.setLayoutParams(clp);
        card.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 2, 0));
        card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        // Top row: Title + Master Pill
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tTitle = new TextView(this);
        tTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        tTitle.setText("SENSOR_CONTROLS");
        setSp(tTitle, 12);
        tTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tTitle.setTextColor(Color.BLACK);

        final TextView masterPill = new TextView(this);
        masterPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(masterPill, 12);
        masterPill.setGravity(Gravity.CENTER);
        masterPill.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));

        topRow.addView(tTitle);
        topRow.addView(masterPill);
        card.addView(topRow);

        // Description
        TextView tDesc = new TextView(this);
        tDesc.setText("Disconnect sensor listeners so hardware silicon drops to 0.001mA sleep");
        setSp(tDesc, 10);
        tDesc.setTypeface(Typeface.DEFAULT);
        tDesc.setTextColor(Color.BLACK);
        tDesc.setPadding(0, dpToPx(4), 0, dpToPx(6));
        card.addView(tDesc);

        // Action row: Summary + MUTE ALL / RESTORE button
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dpToPx(2), 0, dpToPx(8));

        final TextView summaryLabel = new TextView(this);
        summaryLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSp(summaryLabel, 10);
        summaryLabel.setTextColor(Color.BLACK);
        summaryLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        actionRow.addView(summaryLabel);

        final Button muteAllBtn = new Button(this);
        setSp(muteAllBtn, 10);
        muteAllBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        styleEinkButton(muteAllBtn, true);
        muteAllBtn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        muteAllBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        actionRow.addView(muteAllBtn);

        card.addView(actionRow);

        // Divider
        View div = new View(this);
        div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        div.setBackgroundColor(Color.LTGRAY);
        card.addView(div);

        // Helper to update overall UI state
        final Runnable updateUiState = new Runnable() {
            @Override
            public void run() {
                int activeCount = 0;
                for (int i = 0; i < sensors.length; i++) {
                    boolean act = "1".equals(currentConfig.getProperty(sensors[i].key, sensors[i].defaultVal));
                    if (act) activeCount++;
                    if (sensorPills[i] != null) updatePillView(sensorPills[i], act);
                }
                if (activeCount == 0) {
                    masterPill.setText("[ ● ALL MUTED ]");
                    masterPill.setBackground(createEinkDrawable(Color.BLACK, Color.BLACK, 0, dpToPx(2)));
                    masterPill.setTextColor(Color.WHITE);
                    muteAllBtn.setText("RESTORE DEFAULTS");
                    summaryLabel.setText("0/6 active (Silicon Asleep)");
                } else if (activeCount == sensors.length) {
                    masterPill.setText("[ ○ ALL ACTIVE ]");
                    masterPill.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, dpToPx(2)));
                    masterPill.setTextColor(Color.BLACK);
                    muteAllBtn.setText("MUTE ALL SENSORS");
                    summaryLabel.setText("6/6 active (Stock)");
                } else {
                    masterPill.setText("[ " + activeCount + "/6 ACTIVE ]");
                    masterPill.setBackground(createEinkDrawable(Color.WHITE, Color.BLACK, 1, dpToPx(2)));
                    masterPill.setTextColor(Color.BLACK);
                    muteAllBtn.setText("MUTE ALL SENSORS");
                    summaryLabel.setText(activeCount + "/6 active (Custom)");
                }
                updateTelemetryStrip();
            }
        };

        // Populate permanently unfolded individual sensor rows
        for (int i = 0; i < sensors.length; i++) {
            final int idx = i;
            final ConfigManager.SensorItem item = sensors[i];

            LinearLayout itemRow = new LinearLayout(this);
            itemRow.setOrientation(LinearLayout.HORIZONTAL);
            itemRow.setGravity(Gravity.CENTER_VERTICAL);
            itemRow.setPadding(0, dpToPx(6), 0, dpToPx(6));

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            infoCol.setPadding(0, 0, dpToPx(6), 0);

            TextView titleView = new TextView(this);
            titleView.setText(item.title);
            titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(titleView, 11);
            titleView.setTextColor(Color.BLACK);
            infoCol.addView(titleView);

            TextView descView = new TextView(this);
            descView.setText(item.desc);
            setSp(descView, 9);
            descView.setTextColor(Color.GRAY);
            infoCol.addView(descView);

            itemRow.addView(infoCol);

            final TextView rowPill = new TextView(this);
            rowPill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSp(rowPill, 10);
            rowPill.setGravity(Gravity.CENTER);
            rowPill.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
            sensorPills[idx] = rowPill;

            boolean initialActive = "1".equals(currentConfig.getProperty(item.key, item.defaultVal));
            updatePillView(rowPill, initialActive);
            itemRow.addView(rowPill);

            final Runnable toggleRow = new Runnable() {
                @Override
                public void run() {
                    boolean cur = "1".equals(currentConfig.getProperty(item.key, item.defaultVal));
                    boolean next = !cur;
                    currentConfig.setProperty(item.key, next ? "1" : "0");
                    ConfigManager.saveConfig(currentConfig);
                    updateUiState.run();

                    final String cmd = ConfigManager.getSensorApplyCmd(item.key, next);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ShellUtils.execRootAction(cmd);
                            ShellUtils.appendLog("Sensor " + item.title + " set to " + (next ? "ENABLED" : "DISABLED"));
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (logDrawer != null && logDrawer.getVisibility() == View.VISIBLE) {
                                        logDrawer.setText(ShellUtils.readLog(15));
                                    }
                                }
                            });
                        }
                    }).start();
                }
            };

            itemRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { toggleRow.run(); }
            });
            rowPill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { toggleRow.run(); }
            });

            card.addView(itemRow);

            if (i < sensors.length - 1) {
                View sep = new View(this);
                sep.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                sep.setBackgroundColor(Color.LTGRAY);
                card.addView(sep);
            }
        }

        // Master Pill and MUTE ALL button action
        final View.OnClickListener masterToggleListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int activeCount = 0;
                for (int i = 0; i < sensors.length; i++) {
                    if ("1".equals(currentConfig.getProperty(sensors[i].key, sensors[i].defaultVal))) {
                        activeCount++;
                    }
                }
                final boolean doMute = (activeCount > 0);
                if (doMute) {
                    StringBuilder prevSb = new StringBuilder();
                    for (int i = 0; i < sensors.length; i++) {
                        if (i > 0) prevSb.append(",");
                        prevSb.append(currentConfig.getProperty(sensors[i].key, sensors[i].defaultVal));
                        currentConfig.setProperty(sensors[i].key, "0");
                    }
                    currentConfig.setProperty("SENSOR_PREV_STATE", prevSb.toString());
                    currentConfig.setProperty("SENSOR_ALL_MUTE", "1");
                } else {
                    String prev = currentConfig.getProperty("SENSOR_PREV_STATE", "");
                    String[] parts = (prev != null && !prev.isEmpty()) ? prev.split(",") : new String[0];
                    for (int i = 0; i < sensors.length; i++) {
                        String restoredVal = (i < parts.length && !parts[i].isEmpty()) ? parts[i] : "1";
                        currentConfig.setProperty(sensors[i].key, restoredVal);
                    }
                    currentConfig.setProperty("SENSOR_ALL_MUTE", "0");
                }
                ConfigManager.saveConfig(currentConfig);
                updateUiState.run();

                final String fullCmd = ConfigManager.buildAllSensorsApplyCmd(currentConfig);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ShellUtils.execRootAction(fullCmd);
                        ShellUtils.appendLog(doMute ? "All sensors muted (max savings applied)" : "Sensors unmuted (previous configuration restored)");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (logDrawer != null && logDrawer.getVisibility() == View.VISIBLE) {
                                    logDrawer.setText(ShellUtils.readLog(15));
                                }
                            }
                        });
                    }
                }).start();
            }
        };

        masterPill.setOnClickListener(masterToggleListener);
        muteAllBtn.setOnClickListener(masterToggleListener);

        addSectionContent(card);
        updateUiState.run();
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
        updateDebloatPillView(pillBtn, initialOn);

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
                updateDebloatPillView(pillBtn, newChecked);

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
                                    ConfigManager.recordFrozen(pkg, true);
                                } else {
                                    ShellUtils.execRootAction("pm enable " + pkg + " 2>/dev/null");
                                    ConfigManager.recordFrozen(pkg, false);
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
                            ConfigManager.recordFrozen(pkg, true);
                        } else {
                            ShellUtils.execRootAction("pm enable " + pkg + " 2>/dev/null");
                            ConfigManager.recordFrozen(pkg, false);
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
