# Process Durability & Screen-State Profile — Analysis

**Status:** analysis complete, implementation decision **blocked on measurement**
**Date:** 2026-09-20
**Device:** Bigme HiBreak (serial `B6BWE0W2GF4A006000461`), Android 14, 3.8 GB RAM

---

## 1. The problem

`ScreenReceiver` is registered **dynamically** (`registerReceiver`) for
`ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`, and applies a "sleep" CPU profile on
screen-off and a "wake" profile on screen-on.

Android kills the process as a **cached process** a few minutes after screen-off.
A dynamic receiver dies with the process, so:

- the sleep profile can be left applied indefinitely, and
- the wake profile never restores.

`ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` are broadcast with
`FLAG_RECEIVER_REGISTERED_ONLY`, so a **manifest** receiver cannot substitute.
A foreground service is the textbook fix, but it costs resident RAM plus a
persistent notification the user does not want.

### Root cause of the kill was *not* memory pressure

The kill observed in `logcat` was:

```
cached idle & background restricted
```

`background restricted` is an **appop-driven** state (`RUN_ANY_IN_BACKGROUND`),
**not** low-memory-killer pressure. The app now re-asserts its own appops on
every launch and on boot. **The original bug may already be fixed**, which is
why the first experiment below is simply "does it survive".

---

## 2. Mechanisms — what survives what

| Mechanism | Survives a plain memory kill? | Notes |
|---|---|---|
| **PendingIntent alarms** (auto-shutdown) | ✅ **Yes** | Verified: 18 entries live in the alarm queue. Only a **force-stop** cancels them |
| **Manifest receivers** (`BOOT_COMPLETED`, `POWER_CONNECTED`/`DISCONNECTED`) | ✅ Yes | A broadcast **starts the process** even if it was killed |
| **Dynamically-registered `SCREEN_ON`/`OFF`** | ❌ **No** | The **only** thing lost when the process dies |
| **`ACTION_USER_PRESENT` as a manifest receiver** | ❌ No | Not on the [implicit-broadcast exemption list](https://developer.android.com/guide/components/broadcast-exceptions); silently discarded when the process is cached/dead |

**Therefore the real loss is only the exact moment of screen-on/off** — not the
shutdown alarm, not boot, not the charge ceiling (`PowerReceiver` is already
manifest-declared).

---

## 3. Options evaluated

### Option A — `JobScheduler` / `AlarmManager` reconciliation

| Metric | `JobScheduler` periodic | `AlarmManager` non-wakeup (`ELAPSED_REALTIME`) |
|---|---|---|
| Minimum interval | hard-clamped to **15 min** (`JobInfo.getMinPeriodMillis()`) | arbitrary |
| Wakeups in Doze | **0** — but jobs are **deferred**; whitelisting does **not** exempt them | **0** |
| Latency after screen-on | **very poor** (minutes; OS batching) | near zero *only if* the timer expired while asleep |
| Latency for a quick off→on | n/a | **fails** — the alarm has not expired yet |

**Verdict: not viable** as the primary mechanism. Multi-minute latency leaves an
e-ink UI visibly unresponsive after unlock, and the quick-toggle case cannot be
covered at all.

### Option B — Foreground service

Guarantees delivery, but costs resident RAM and a persistent notification.

**RAM objection partially withdrawn.** Measured on-device:

```
com.right9code.hibigzero   TOTAL PSS: 57,704 KB      TOTAL RSS: 185,633 KB
MemFree:        1,028,144 kB
MemAvailable:   2,594,696 kB
SwapFree:       2,185,252 kB
```

An earlier note in this project claimed "only ~248 MB free RAM", citing `top`'s
free column. That was a **transient page-cache reading**, not real availability.
With **2.5 GB `MemAvailable` and 2.1 GB free swap**, a ~56 MB PSS service is
affordable on this device.

The remaining objection is the **persistent notification**, which the user
explicitly does not want.

### Option C — Remove the screen-dependent CPU profile entirely

Android's `schedutil` governor and the kernel CPU-idle subsystem already collapse
cores to their minimum frequency and enter low-power C-states within
milliseconds of screen-off. Manual clamping can be **net-negative** under the
**race-to-sleep** principle: background work takes proportionally longer at a
clamped frequency, keeping the CPU awake *longer*.

**Cheapest option. Deletes the fragility instead of shielding it.** Blocked on
the same measurement as everything else.

### Option D — Lightweight root screen-observer daemon (no ART, no notification)

A tiny root process blocking on the **event log** instead of an ART receiver.

**Why `inotify` does not work:** sysfs is backed by `kernfs`. `inotify` hooks
standard VFS operations, but kernel drivers mutate attributes internally without
routing through `vfs_write`, so hardware-driven display changes generate **zero
inotify events**. Some sysfs nodes do support `poll()`/`POLLPRI` via
`sysfs_notify()`, but e-ink EPDC display blanking rarely calls it.

**Empirically verified screen-state event** (live capture; tag confirmed present
on this ROM):

```
17:21:24.223  1095  1095 I screen_toggled: 1      <- screen ON
17:21:31.410  1095  1095 I screen_toggled: 0      <- screen OFF
```

Also present: `power_screen_state`, `power_screen_broadcast_send`,
`power_screen_broadcast_done`, `intercept_power`.

So the daemon blocks on:

```sh
logcat -b events -v raw -s screen_toggled:I
```

**Cost / benefit:**

| | Root daemon | Foreground service |
|---|---|---|
| Resident RAM | ~0.3–1 MB | ~56 MB PSS |
| Wakeups in suspend | **0** (blocked on a pipe; holds no wakelock, prevents no suspend) | ~0 |
| Persistent notification | **none** | required |
| Response | sub-second | sub-second |
| Applies the CPU profile | **directly in sysfs — never starts the ART process** | via the app |

**Waking the app from the daemon is verified to work.** An *explicit* broadcast
(`-n package/class`) is exempt from implicit-broadcast bans, and **root can
reach an `exported="false"` receiver**:

```
$ su -c "am broadcast -a com.right9code.hibigzero.CHARGE_LIMIT_TICK \
          -n com.right9code.hibigzero/.PowerReceiver"
Broadcasting: Intent { act=…CHARGE_LIMIT_TICK flg=0x400000 cmp=…/.PowerReceiver }
Broadcast completed: result=0
ActivityManager: Enqueued broadcast Intent { … cmp=…/.PowerReceiver }: 0
```

**Risks to mitigate if built:** a persistent root process needs supervision (PID
file + watchdog); the `logcat | while read` loop dies if `logcat` exits;
`logcat -b events -c` clears a buffer shared with the rest of the system; it adds
a root-level audit/security surface.

---

## 4. Claim verification log

| Claim | Verdict | Evidence |
|---|---|---|
| `screen_toggled` event tag exists | ✅ **true** | live capture above. An earlier negative result came from a **rotated** `logcat -b events -d` dump and was wrong |
| Root can wake a dead app via explicit broadcast | ✅ true | `result=0`, `Enqueued broadcast`; works to `exported=false` |
| `inotify` cannot observe sysfs display changes | ✅ true | kernfs does not notify VFS |
| `USER_PRESENT` not manifest-deliverable | ✅ consistent with docs | not on the exemption list |
| FGS ≈ 45–70 MB | ✅ approximately | measured PSS 56 MB (RSS 181 MB includes shared libs) |
| "Low RAM makes the FGS expensive" | ❌ **false on this device** | `MemAvailable` 2.5 GB, swap 2.1 GB free |
| "Removing the clamp is net-positive (race-to-sleep)" | ⚠️ **unverified** | plausible and testable; no local measurement yet |

---

## 5. Decision procedure

One overnight measurement decides between all four options. Phone unplugged,
`adb` disconnected, auto-shutdown off, sampling capacity + current every 60 s.

1. **Does the app survive the night?**
   → confirms the appop fix alone was sufficient. **No daemon and no service
   needed.**
2. **Does the sleep CPU clamp measurably reduce drain?**
   → if **no / marginal**: take **Option C**, delete the feature, and the entire
   durability problem disappears.
   → if **clearly yes**: build **Option D** (the root daemon) — strictly better
   than the FGS here (no notification, ~1 MB instead of ~56 MB).
3. **Option B** is the fallback only if root scripting is unavailable.
4. **Option A** is rejected.

---

## 6. Decision taken (2026-09-20)

Option A was rejected on evidence. The failure was **reproduced on the device**,
not merely theorised:

```sh
# clamp applied, screen off, process alive
cpu4=0  marker=/data/local/tmp/hibreak_active_gov.txt=schedutil_efficient
su -c 'kill -9 $(pidof com.right9code.hibigzero)'
input keyevent 26                      # wake the screen
-> screen=Awake, cpu4=0, policy0 scaling_max_freq=900000, marker PRESENT
```

A screen-on device left at 400–900 MHz with four cores offlined. Launching the app
did not repair it either (nothing reconciled on start), so only a further screen
toggle or a reboot would clear it. That makes the window reachable in practice, not
a corner case — this app's own APK installs were already the only kills in the
`am_kill` history.

A hybrid, chosen because each tier alone leaves an obvious hole:

| Tier | Mechanism | Covers |
|---|---|---|
| 1 | dynamic receiver, unchanged | the normal case, sub-second |
| 2 | reconcile in `HiBigApp.onCreate` (`GovernorReconciler`) | any process start: boot, power events, alarms, app launch |
| 3 | `SleepWatchdogReceiver`, non-wakeup `ELAPSED_REALTIME` alarm armed at screen-off, re-armed while off | the user simply unlocks and nothing starts the app |

Tier 3 is the key insight and it works better than the alarm analysis above
suggested. A `PendingIntent` lives in the system alarm queue, so it survives a
process kill and restarts the process to be delivered. Because it is
`ELAPSED_REALTIME` and **not** `..._WAKEUP`, an alarm that expired during suspend is
delivered as soon as the device wakes, and it never holds the SoC out of suspend to
ask a question whose answer only matters once awake. Measured: the framework
registers it as `type=ELAPSED`, and an overdue one is delivered **~2 s after the
power key**.

Measured outcomes:

| Path | Result |
|---|---|
| without the fix | clamped indefinitely |
| cold start while screen on | repaired |
| overdue watchdog delivered on wake | repaired in **2 s** |
| normal off→on, process alive | unchanged, immediate |
| `cancelWatchdog` on screen-on | verified: 0 live watchdog alarms remain |

Residual window: **≤ 90 s**, and only when the process was killed *and* the screen
returns before that interval expires *and* nothing starts the app. A phone woken
after a longer sleep is repaired immediately, because its alarm is already overdue.

Options B (foreground service) and D (root daemon) were **not** needed and were not
built: `docs/PROMPT_DURABILITY.md` records the question put to a higher model, whose
kernel reasoning on suspend current matched the outcome, but whose design no longer
was required once tier 3 proved out.

**Still open:** whether the clamp earns its keep at all (Option C, the race-to-sleep
concern). That answer is unchanged and still needs the overnight A/B — if the clamp
does not measurably help, the right fix is to delete the feature rather than keep
this recovery machinery.


