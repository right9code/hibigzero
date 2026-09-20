# Prompt: make screen-state handling survive process death

Copy everything below into the higher model.

---

## Context

Android app `com.right9code.hibigzero`, a root power-management tool for a Bigme
HiBreak e-ink phone (MediaTek MT6765, Android 14, 3.8 GB RAM).

**Hard constraints — the answer must respect these:**

- `minSdkVersion=28`, `targetSdkVersion=34`
- **No AndroidX, no Gradle, no WorkManager, no third-party libraries.** Built by a
  hand-rolled `build.py` that runs `javac` + `d8` + `aapt` + `apksigner`. Platform
  APIs only.
- Root (`su -c`) is available and is used heavily. Root can write to sysfs/procfs.
- The device is e-ink and low-power-oriented; resident RAM and wakeups matter, but
  see the measured numbers below before assuming RAM is scarce.
- No persistent notification unless you can justify it — the user has rejected it
  before on UI-cleanliness grounds.

Existing manifest permissions: `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
`BATTERY_STATS`, `WRITE_SECURE_SETTINGS`, `QUERY_ALL_PACKAGES`, `INTERNET`,
`SYSTEM_ALERT_WINDOW`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`.

Existing manifest receivers: `BootReceiver` (`BOOT_COMPLETED`),
`ShutdownAlarmReceiver` (`SHUTDOWN_TIMER` custom action), `PowerReceiver`
(`POWER_CONNECTED`, `POWER_DISCONNECTED`, `CHARGE_LIMIT_TICK`).

## The problem

The app has a "sleep governor": on screen-off it clamps the CPU hard, and on
screen-on it restores the user's configured governor profile. This is implemented
with a **dynamically registered** `BroadcastReceiver` (registered from
`Application.onCreate`) listening for `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`.

`ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` are broadcast with
`FLAG_RECEIVER_REGISTERED_ONLY`, so a manifest-declared receiver cannot receive
them. A dynamic receiver's lifetime is the process's lifetime, so when Android
reaps the process as a cached process the receiver dies with it, and:

1. the sleep clamp can be **left applied indefinitely**, or
2. the wake restore never happens.

The sleep profile is not subtle. From `buildGovernorCmd()`:

```java
if ("deep_sleep".equals(profile)) {
    return hpCmd + ppmPrep +                       // hpCmd OFF-LINES cpu4-7
           "echo 6 1 > /proc/ppm/policy_status 2>/dev/null; " +
           "echo 0 900000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
           "echo 0 900000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
           "echo 1 400000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null; " +
           "echo 1 400000 > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null; " +
           "echo 900000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; " +
           "echo 80000 > /sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us 2>/dev/null; " +
           "echo 5000 > /sys/devices/system/cpu/cpufreq/schedutil/down_rate_limit_us 2>/dev/null";
}
```

So a stuck clamp means **4 big cores offlined, little cores pinned to 400–900 MHz,
and a 4x slower ramp**. On e-ink that is a visibly crippled device, which is why
this matters rather than being cosmetic.

## What has already been established empirically (do not re-litigate)

These were all measured on the device in question, not assumed. Please take them
as given and build on them:

1. **The kill was appop-driven, not memory pressure.** The observed kill reason was
   `cached idle & background restricted`. `RUN_ANY_IN_BACKGROUND` had been set to
   `ignore`, which marks the app as a *restricted* cached process that is reaped
   aggressively. That appop is now re-asserted to `allow` on every launch and boot.
   Since that fix, the retained `am_kill` event history shows **no** kills of this
   app except `due to installPackageLI` (i.e. our own APK installs).
2. **The ROM is not eager to kill cached processes.** `max_cached_processes=1024`,
   `CUR_MAX_CACHED_PROCESSES=128`, `CUR_TRIM_CACHED_PROCESSES=21`, and
   `no_kill_cached_processes_post_boot_completed_duration_millis=600000`.
3. **Alarms survive a plain process kill.** Verified: 18 pending alarm entries
   present in `dumpsys alarm` while the app was not running. Only a `force-stop`
   cancels pending alarms.
4. **Manifest receivers start the process** even after it was killed.
5. **`ACTION_USER_PRESENT` is not on the implicit-broadcast exemption list**, so a
   manifest receiver for it will not be delivered to a cached/dead process.
6. **A `screen_toggled` event exists in the logcat events buffer** on this ROM, and
   was captured live: `screen_toggled: 1` for on, `screen_toggled: 0` for off.
   (`power_screen_state`, `power_screen_broadcast_send`,
   `power_screen_broadcast_done` and `intercept_power` also exist.)
7. **Root can deliver an explicit broadcast to an `exported="false"` receiver.**
   Verified: `su -c "am broadcast -a <action> -n <pkg>/.Receiver"` returned
   `Broadcast completed: result=0` and ActivityManager logged `Enqueued broadcast`.
8. **`inotify` cannot observe sysfs display changes** — sysfs is `kernfs`, so driver
   attribute changes do not route through VFS and generate no inotify events.
9. **`/data/local/tmp` is `drwxrwx--x` owned by `shell:shell` (0771).** The app has
   traverse-only rights there, so it **cannot unlink a file even if it owns it** —
   verified with `rm` as the app's uid. Anything that deletes a file in that
   directory must do it via root.
10. **Memory is not actually tight.** Measured: `MemAvailable` 2.5 GB,
    `SwapFree` 2.1 GB, and this app's own `TOTAL PSS` 56 MB / `TOTAL RSS` 181 MB.
    The app is already doze-whitelisted.
11. `JobScheduler` periodic minimum is 15 minutes and jobs are **deferred during
    Doze even for whitelisted apps**, so it cannot deliver prompt post-unlock
    reconciliation.

## The current code

### `HiBigApp` — where the dynamic receiver is registered

```java
public class HiBigApp extends Application {
    private static ScreenReceiver screenReceiver;
    private static boolean receiverRegistered = false;
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        Properties cfg = ConfigManager.loadConfig();
        if ("1".equals(cfg.getProperty("SLEEP_GOVERNOR_ENABLED", "1"))) {
            registerScreenReceiver();
        }
    }

    public static void registerScreenReceiver() {
        if (receiverRegistered || appContext == null) return;
        try {
            screenReceiver = new ScreenReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.SCREEN_OFF");
            filter.addAction("android.intent.action.SCREEN_ON");
            appContext.registerReceiver(screenReceiver, filter);
            receiverRegistered = true;
            ShellUtils.appendLog("ScreenReceiver registered");
        } catch (Exception e) {
            ShellUtils.appendLog("ScreenReceiver register error: " + e.getMessage());
        }
    }

    public static void unregisterScreenReceiver() { /* ... */ }
    public static boolean isReceiverRegistered() { return receiverRegistered; }
}
```

`Application.onCreate` runs for *any* process start, including a receiver-only
start, so the dynamic registration is already restored whenever the process comes
back to life. (There is currently **no** reconcile-on-start: nothing checks
whether a clamp is applied and the screen is already on.)

### `ScreenReceiver` — the screen-off / screen-on logic

```java
public class ScreenReceiver extends BroadcastReceiver {
    /** Shared with BootReceiver: after a boot the configured profile is applied, so
     *  a marker left over from before the reboot no longer describes reality. */
    public static final String ACTIVE_GOV_FILE = "/data/local/tmp/hibreak_active_gov.txt";

    public static void clearActiveGovMarker() {
        ShellUtils.execRoot("rm -f " + ACTIVE_GOV_FILE, false);   // must be root, see 9.
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        final PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Properties cfg = ConfigManager.loadConfig();
                    if (!"1".equals(cfg.getProperty("SLEEP_GOVERNOR_ENABLED", "1"))) return;
                    String hotplug4 = cfg.getProperty("HOTPLUG_4_CORES", "0");

                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        String currentGov = cfg.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");
                        String sleepGov   = cfg.getProperty("SLEEP_GOVERNOR", "deep_sleep");
                        saveActiveGov(currentGov);              // writes the marker
                        Thread.sleep(250);                      // let PowerHAL transition finish
                        ShellUtils.execRootAction(ConfigManager.buildGovernorCmd(sleepGov, hotplug4));
                        if ("1".equals(cfg.getProperty("LOCKDOWN_GBOARD"))) {
                            ShellUtils.execRootAction("am force-stop com.google.android.inputmethod.latin 2>/dev/null");
                        }
                        ShutdownAlarmReceiver.markScreenOff(context);   // monotonic stamp, SharedPreferences
                        if ("1".equals(cfg.getProperty("AUTO_SHUTDOWN_ENABLED"))) {
                            ShutdownAlarmReceiver.scheduleAlarmWithConfig(context);
                        }
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        ShutdownAlarmReceiver.cancelAlarm(context);
                        ShutdownAlarmReceiver.clearScreenOff(context);
                        String savedGov = readActiveGov();
                        if (savedGov == null || savedGov.trim().isEmpty()) {
                            savedGov = cfg.getProperty("GOVERNOR_PROFILE", "schedutil_efficient");
                        }
                        ShellUtils.execRootAction(ConfigManager.buildGovernorCmd(savedGov, hotplug4));
                        clearActiveGovMarker();
                    }
                } catch (Exception e) {
                    ShellUtils.appendLog("ScreenReceiver error: " + e.getMessage());
                } finally {
                    ShellUtils.flushLog();
                    pendingResult.finish();
                }
            }
        }).start();
    }

    private void saveActiveGov(String profile) {
        if (!ConfigManager.isValidGovernorProfile(profile)) return;   // allow-listed
        ShellUtils.execRoot("printf '%s' '" + profile + "' > " + ACTIVE_GOV_FILE +
            ConfigManager.secureFileTail(ACTIVE_GOV_FILE), false);
    }

    private String readActiveGov() {
        File file = new File(ACTIVE_GOV_FILE);
        if (!file.exists()) return null;
        // ... reads the first line, returns null on any problem
    }
}
```

`ACTIVE_GOV_FILE` is the "a clamp is currently applied" marker. `BootReceiver`
already clears it after applying the configured profile, because after a boot the
marker no longer describes reality.

## The existing precedent for surviving process death

`ShutdownAlarmReceiver` already survives a kill, using an exact alarm plus a
`SystemClock` stamp in `SharedPreferences`. **Prefer reusing this pattern over
inventing a new one.**

```java
public class ShutdownAlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_SHUTDOWN_TIMER = "com.right9code.hibigzero.SHUTDOWN_TIMER";
    private static final String RUNTIME_PREFS     = "hibreak_runtime";
    private static final String KEY_SCREEN_OFF_AT = "screen_off_elapsed_ms";

    private static boolean isInteractive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return true;   // Unknown => assume the user is present (fail safe).
        }
    }

    /** Elapsed time since the last screen-off, or -1 when we have no stamp. */
    private static long idleSinceScreenOff(Context context) { /* reads KEY_SCREEN_OFF_AT */ }

    public static void markScreenOff(Context context) {
        prefs(context).edit().putLong(KEY_SCREEN_OFF_AT, SystemClock.elapsedRealtime()).apply();
    }

    public static void clearScreenOff(Context context) {
        prefs(context).edit().remove(KEY_SCREEN_OFF_AT).apply();
    }

    private static void scheduleAlarm(Context context, long delayMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, ShutdownAlarmReceiver.class);
        intent.setAction(ACTION_SHUTDOWN_TIMER);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pi);   // inexact fallback
        }
    }
}
```

Its alarm handler already re-checks `isInteractive()` when it fires and reschedules
if the screen is on — i.e. it already self-corrects state that it was too early to
know about. That is the same shape the screen-state problem needs.

## Relevant `ConfigManager` helpers

```java
/** Governor profiles the app knows how to build. */
private static final Set<String> GOVERNOR_PROFILES = new HashSet<>(Arrays.asList(
    "schedutil_efficient", "balanced", "ereader_battery", "deep_sleep", "stock"));

public static boolean isValidGovernorProfile(String profile) {
    return profile != null && GOVERNOR_PROFILES.contains(profile.trim());
}

/**
 * Ownership/permission tail for a file root writes but this app reads back.
 * chown'd to the app's uid with 0600, falling back to 0644 if the chown is refused.
 */
public static String secureFileTail(String path) {
    String uid = String.valueOf(android.os.Process.myUid());
    return " && (chown " + uid + " " + path + " 2>/dev/null && chmod 600 " + path +
           " 2>/dev/null || chmod 644 " + path + " 2>/dev/null)";
}
```

All shell commands go through `ShellUtils`, which drains stdout and stderr on
separate threads, enforces a 120 s deadline (`waitFor(timeout)` +
`destroyForcibly()`), and short-circuits an empty command without spawning `su`.

The app also has a boot-loop guard: after 3 consecutive boots within 180 s of each
other, the boot rule pass is suspended until the user taps RESUME. Any proposal
must not reintroduce a way for a bad rule to loop the device.

## The question

Design the most robust screen-state handling possible **without** a foreground
service and **without** a persistent notification, using platform APIs only, and
give concrete code.

### Options to evaluate and rank for *this* app

- **Option A — accept it, do nothing further.** The appop fix may already be
  enough. Worst case: a clamp outlives the process until something restarts it.
- **Option B — foreground service.** Guarantees delivery. Costs ~56 MB PSS (which
  the measurements say is affordable here) plus a persistent notification the user
  has rejected.
- **Option C — delete the screen-dependent feature entirely.** Is the clamp even
  worth it? When the SoC is suspended, core count and frequency are irrelevant, so
  the clamp only matters during *awake-but-screen-off* windows, and a slower ramp
  can keep the CPU awake longer (race-to-sleep). This feature's benefit is
  currently **unmeasured**.
- **Option D — a minimal root observer daemon.** A shell loop blocked on
  `logcat -b events -v raw -s screen_toggled:I`, applying the profile in sysfs
  directly and never starting the ART process. ~1 MB, no notification, but needs a
  supervisor (PID file + watchdog) and adds a root-level audit surface.
- **Option E — reconcile on every process start.** No resident component: on any
  start (Application.onCreate, PowerReceiver, boot, alarm), if the marker exists
  **and** `PowerManager.isInteractive()` is true, restore the profile and clear the
  marker. Cheap, but does not help when the user unlocks and *nothing* starts the
  app in that session.
- **Option F — a self-rescheduling screen-off watchdog alarm.** At screen-off, arm
  a *non-wakeup* alarm for +N minutes. When it fires: if `isInteractive()` is true,
  restore the wake profile and clear the marker; if the screen is still off,
  re-arm. Zero resident components, reuses the existing `ShutdownAlarmReceiver`
  alarm pattern, and rides along on wakeups the device is taking anyway. Please
  evaluate this seriously: what is the real latency after unlock, given
  `AlarmManager` semantics for non-wakeup alarms and the fact that the device is
  doze-whitelisted? What N, and should it back off?

Recommend one, or a combination, and say plainly what the residual failure window
is. If your answer is "C", say so — do not defend a feature that the evidence does
not support.

### Specific technical questions

1. Is there **any** manifest-declarable broadcast that correlates with "the user
   picked the phone up / unlocked" and that is still delivered to a cached or dead
   process on Android 14? `ACTION_USER_PRESENT` is out. Please enumerate anything
   else worth trying.
2. For Option D: does a process blocked on `logcat` prevent suspend? Does it hold
   any wakelock implicitly? What happens if `logcat` exits, and what is the minimal
   supervision that makes it self-healing without `init` and without a Magisk
   module? Can root write an `init.rc`-style service, or is that unavailable
   without a module?
3. Is `PowerManager.OnStateChangedListener` (`isInteractive` / `isScreenOn`) usable
   in a way that outlives the process? Presumably not, but confirm.
4. If Option B is chosen anyway, what is the minimum viable foreground service
   that keeps the dynamic receiver alive, and can it avoid the notification on
   Android 14 (`foregroundServiceType` requirements, notification permission,
   `specialUse`)? Be specific about what is and is not possible.
5. Whatever you recommend, how should it interact with (a) the existing
   `ACTIVE_GOV_FILE` marker, (b) `BootReceiver` already clearing that marker, and
   (c) the boot-loop guard?

### Invariants the design must preserve

- **Fail safe**: never leave a clamp applied while the screen is on. Losing battery
  saving is acceptable; a crippled device is not.
- `isInteractive()` must be treated as "assume the user is present" on any error.
- No new dependencies, no AndroidX.
- Everything must remain verifiable from a shell.

### How we verify on this device (please write tests against this)

`adb` pinned to serial `B6BWE0W2GF4A006000461`. The app's own log is a real file at
`/data/local/tmp/hibreak.log`, and every root command is logged as
`$ <command> [exit=N]` with a 200-char preview.

The boot receiver can be driven without rebooting:

```sh
su -c "am broadcast -a android.intent.action.BOOT_COMPLETED \
        -n com.right9code.hibigzero/.BootReceiver"
```

Clamp state is observable:

```sh
su -c 'cat /sys/devices/system/cpu/cpu4/online'          # 0 = clamp applied
su -c 'cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'
su -c 'ls /data/local/tmp/hibreak_active_gov.txt'          # marker present/absent
```

Screen state is observable with `dumpsys power | grep mWakefulness=` and toggled
with `input keyevent 26`. Note the screen sleeps quickly on this device and drops
`input` events, so wake and act in one device-side shell invocation:

```sh
adb -s <serial> shell 'if dumpsys power | grep -q mWakefulness=Asleep; then \
    input keyevent 26; sleep 3; fi; input tap X Y'
```

Process death is simulated with `su -c "kill -9 $(pidof com.right9code.hibigzero)"`
(use `kill`, **not** `force-stop`, which cancels alarms and is therefore not
representative). Kill history is in `logcat -b events | grep am_kill`.

### Output format

1. A ranked recommendation with the reasoning, and an explicit statement of the
   residual failure window.
2. Complete, compiling Java for the chosen approach, matching the existing style
   (plain platform APIs, `goAsync()` + worker thread in receivers,
   `ShellUtils.execRootAction` for state changes, comments that explain *why*).
3. The exact on-device test sequence that would prove it works, including a
   negative control.



