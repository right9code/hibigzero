#!/system/bin/sh
# HiBig Zero — deep-suspend probe (read-only diagnostics)
#
# One question: with the screen off, does this ROM actually enter kernel suspend
# ("deep sleep"), for how long, and — when it does not — what is holding it up?
#
# Why this exists. Every obvious instrument is either wrong or dead on this device
# (MT6765 / Android 14, verified 2026-09-06):
#   * `dumpsys deviceidle` reports Android Doze, which is a *different* thing from
#     kernel suspend: Doze can be "deep idle" while the SoC never suspends.
#   * /sys/power/suspend_stats          — not exported by this ROM.
#   * wakeup_sources prevent_suspend_time — printed but always 0 on this kernel, so
#     it cannot name the blocker (see the 2026-09-06 ws_before/ws_after dumps).
#   * MTK SPM debugfs counters          — not available on this build.
# What DOES work here is the SPM driver's own log line, emitted on every
# suspend/resume attempt, and its wall-clock stamp:
#     PM: suspend entry (deep)
#     [name:spm&][SPM] PM: suspend entry 2026-09-06 14:06:09.502779986 UTC
# The dmesg "[sec.usec]" prefix is CLOCK_MONOTONIC, which EXCLUDES suspend time,
# while `date +%s` (and /proc/uptime) are wall/boottime and INCLUDE it. Therefore
#
#     suspended_time = (wall delta) - (monotonic delta)
#
# is exact and needs no suspend counter at all. Marker lines written into the
# kernel ring give a monotonic stamp at each end of the window.
#
# Read-only: no setting is written, no wakelock is taken, no sampling loop runs
# during the window (each adb round-trip would wake the CPU and pollute the test).
# The only writes are two "#hbz-probe" marker lines into /dev/kmsg — log entries,
# not state changes.
#
# Usage (dmesg + wakeup_sources need root):
#   adb push assets/sleep_probe.sh /data/local/tmp/
#   adb shell su -c 'sh /data/local/tmp/sleep_probe.sh 180'
#
# $1 = window in seconds (default 180). The screen must be off for the window; if it
# is on, the script says so and exits (suspend cannot happen with the display on).
# $2 = "KEY" to let it press the power key itself and turn the screen off.
#
# Result: stdout, appended to /data/local/tmp/diag/sleep_probe.log. If the device
# genuinely suspends, this adb command appears to stall for much of the window —
# that is the measurement working, not a hang.

OUT=${OUT:-/data/local/tmp/diag}
LOG=$OUT/sleep_probe.log
WINDOW=${1:-180}
KEYARG=${2:-}
KMSG=/dev/kmsg
WS=""

for p in /sys/kernel/debug/wakeup_sources /d/wakeup_sources /proc/wakeup_sources; do
  [ -r "$p" ] && WS=$p && break
done

mkdir -p "$OUT" 2>/dev/null

log() { echo "$@"; echo "$@" >> "$LOG" 2>/dev/null; }
rd()  { cat "$1" 2>/dev/null | tr -d '\r\n'; }

# --- clocks -------------------------------------------------------------------
# mono_now <tag>: current CLOCK_MONOTONIC (suspend excluded), or empty when neither
# /dev/kmsg nor dmesg is available (i.e. not root).
mono_now() {
  ( printf '#hbz-probe %s\n' "$1" > "$KMSG" ) 2>/dev/null
  dmesg 2>/dev/null | grep -F "#hbz-probe $1" | tail -1 |
    sed -n 's/^\[\([0-9][0-9.]*\)\].*/\1/p'
}

wall_now() { date +%s; }

# Cable state, as one comparable string. Attaching or removing USB mid-window wakes
# the SoC and blocks suspend, so a changed signature invalidates the measurement.
power_sig() {
  { for p in /sys/class/power_supply/*/online; do
      [ -r "$p" ] && printf '%s=%s ' "$(basename $(dirname $p))" "$(rd $p)"
    done
    printf 'battery=%s' "$(rd /sys/class/power_supply/battery/status)"; }
}

# --- suspend counters (informational only: an entry can abort in ~10 ms) -------
cnt_entry()     { dmesg 2>/dev/null | grep -c 'PM: suspend entry'; }
cnt_deep()      { dmesg 2>/dev/null | grep -c 'PM: suspend entry (deep)'; }
cnt_exit()      { dmesg 2>/dev/null | grep -c 'PM: suspend exit'; }
cnt_wakealarm() { dumpsys alarm 2>/dev/null | grep -c 'RTC_WAKEUP\|ELAPSED_WAKEUP'; }

screen_state() {
  dumpsys power 2>/dev/null | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1
}

ROOT=0
[ "$(id -u 2>/dev/null)" = "0" ] && ROOT=1

# --- wakeup sources -----------------------------------------------------------
# Column order (kernel 5.x): name active_count event_count wakeup_count expire_count
# active_since total_time max_time last_change prevent_suspend_time. The name may
# itself contain spaces ("charger suspend wakelock"), so the numeric columns are read
# from the END of the line rather than by fixed index.
waker_report() {
  if [ -z "$WS" ]; then
    log "    (wakeup_sources not readable — needs root)"
    return
  fi
  pst=$(awk 'NR>1 {s+=$NF} END{print (s>0)?"alive":"dead"}' "$WS" 2>/dev/null)
  if [ "$pst" = "dead" ]; then
    log "    source: $WS (prevent_suspend_time is all-zero on this kernel — it cannot name blockers)"
  else
    log "    source: $WS"
  fi
  log "    currently ACTIVE (active_since != 0):"
  awk 'NR>1 { n=NF-9; if (n<1) next; nm="";
              for (i=1;i<=n;i++) nm = nm (i>1?" ":"") $i;
              if ($(NF-4)+0 != 0)
                printf "      %-34s held=%sms wakeups=%s\n", nm, $(NF-3), $(NF-6) }' \
      "$WS" 2>/dev/null | head -12
  log "    top sources by total held time:"
  awk 'NR>1 { n=NF-9; if (n<1) next; nm="";
              for (i=1;i<=n;i++) nm = nm (i>1?" ":"") $i;
              printf "%s\t%s\t%s\n", $(NF-3), nm, $(NF-4) }' "$WS" 2>/dev/null |
    sort -rn | head -8 |
    awk -F'\t' '{printf "      %-34s held=%sms active_since=%s\n", $2, $1, $3}'
}

static_state() {
  log "--- DEVICE ---"
  log "    model=$(getprop ro.product.model) build=$(getprop ro.build.version.release) kernel=$(uname -r) root=$ROOT"
  log "    uptime=$(cut -d' ' -f1 /proc/uptime)s  wall=$(wall_now)"
  log "--- /sys/power ---"
  log "    state=$(rd /sys/power/state)"
  log "    mem_sleep=$(rd /sys/power/mem_sleep)   (the BRACKETED entry is the mode in use)"
  log "    pm_async=$(rd /sys/power/pm_async) pm_freeze_timeout=$(rd /sys/power/pm_freeze_timeout) wakeup_count=$(rd /sys/power/wakeup_count)"
  if [ -r /sys/power/suspend_stats/success ]; then
    log "    suspend_stats.success=$(rd /sys/power/suspend_stats/success)"
  else
    log "    suspend_stats: absent on this ROM"
  fi
  log "--- CHARGE / USB ---"
  log "    battery=$(rd /sys/class/power_supply/battery/status) cap=$(rd /sys/class/power_supply/battery/capacity)% cur=$(rd /sys/class/power_supply/battery/current_now)uA"
  for p in /sys/class/power_supply/*/online; do
    [ -r "$p" ] && log "    $(basename $(dirname $p)): online=$(rd $p)"
  done
  log "    stay_on_while_plugged_in=$(settings get global stay_on_while_plugged_in)  (0 = a cable cannot keep the display on; 'svc power stayon' overrides it)"
  log "--- FRAMEWORK ---"
  log "    wakefulness=$(screen_state)"
  log "    doze: $(dumpsys deviceidle 2>/dev/null | grep -E 'mState=|mLightState=' | tr '\n' ' ')"
  log "    pending wakeup alarms=$(cnt_wakealarm)   hibigzero alarm refs=$(dumpsys alarm 2>/dev/null | grep -c 'hibigzero')"
  blockers=$(dumpsys power 2>/dev/null | sed -n '/Suspend Blockers/,/^$/p' | grep -E 'ref count=[1-9]')
  if [ -n "$blockers" ]; then
    log "    suspend blockers still held:"
    printf '%s\n' "$blockers" | sed 's/^/      /' >> "$LOG" 2>/dev/null
    printf '%s\n' "$blockers" | sed 's/^/      /'
  fi
}

# --- episode statistics -------------------------------------------------------
# Pairs the SPM driver's UTC-stamped entry/exit lines into real sleep episodes.
# Only lines at or after the window's start marker (monotonic $T0) are counted.
# Duplicate lines (this driver logs some events twice) are collapsed, and an entry
# that is immediately followed by an exit is a genuine ~10 ms abort, not a sleep:
# that is why the entry *count* alone is not the answer — the wall-vs-monotonic
# delta is.
EPISODE_AWK='
function tosec(y, mo, d, h, mi, s,   era, yoe, doy, doe) {
  y = y - (mo <= 2 ? 1 : 0);
  era = int((y >= 0 ? y : y - 399) / 400);
  yoe = y - era * 400;
  doy = int((153 * (mo + (mo > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  doe = yoe * 365 + int(yoe / 4) - int(yoe / 100) + doy;
  return (era * 146097 + doe - 719468) * 86400 + h * 3600 + mi * 60 + s;
}
{
  L = $0;
  if (L !~ /PM: suspend (entry|exit) [0-9][0-9][0-9][0-9]-/) next;
  mono = L; sub(/^\[/, "", mono); sub(/\].*/, "", mono); mono += 0;
  if (mono < t0) next;
  kind = (L ~ /PM: suspend entry [0-9]/) ? "entry" : "exit";
  sub(/^.*PM: suspend (entry|exit) /, "", L);
  split(L, p, " ");
  split(p[1], D, "-"); split(p[2], T, ":");
  sec = T[3]; fr = 0;
  if (index(T[3], ".") > 0) {
    sec = substr(T[3], 1, index(T[3], ".") - 1);
    fr = "0." substr(T[3], index(T[3], ".") + 1, 3) + 0;
  }
  t = tosec(D[1] + 0, D[2] + 0, D[3] + 0, T[1] + 0, T[2] + 0, sec + 0) + fr;
  if (kind == "entry") {
    if (pend == 0 || t - pend > 1) pend = t;
  } else {
    if (pend != 0) { d[n++] = t - pend; pend = 0 }
  }
}
END {
  for (i = 0; i < n; i++) for (j = i + 1; j < n; j++) if (d[j] < d[i]) { s = d[i]; d[i] = d[j]; d[j] = s }
  total = 0; long = 0;
  for (i = 0; i < n; i++) { total += d[i]; if (d[i] >= 60) long++ }
  if (n > 0)
    printf "episodes=%d (>=60s: %d) total=%.0fs min=%.2fs median=%.2fs max=%.2fs\n",
           n, long, total, d[0], d[int(n / 2)], d[n - 1];
  else
    print "episodes=none";
}'

episodes() { [ -n "$1" ] && dmesg 2>/dev/null | awk -v t0="$1" "$EPISODE_AWK"; }

# --- main ---------------------------------------------------------------------
log ""
log "=== SLEEP PROBE $(date '+%Y-%m-%d %H:%M:%S')  window=${WINDOW}s ==="

if [ "$ROOT" != "1" ]; then
  log "!! Not running as root: dmesg and wakeup_sources are unreadable, the window will"
  log "   measure nothing. Re-run as: su -c 'sh $0 $WINDOW $KEYARG'"
fi

static_state

# Suspend cannot happen with the display on, so refuse to "measure" that state.
for attempt in 1 2; do
  S=$(screen_state)
  if [ "$S" = "Asleep" ] || [ "$S" = "Dozing" ]; then break; fi
  if [ "$attempt" = "1" ] && [ "$KEYARG" = "KEY" ]; then
    log "    display is '$S' — pressing the power key, waiting for it to go off..."
    input keyevent 26
    i=0
    while [ $i -lt 20 ]; do
      sleep 1
      S=$(screen_state)
      if [ "$S" = "Asleep" ] || [ "$S" = "Dozing" ]; then break; fi
      i=$((i + 1))
    done
  fi
done
S=$(screen_state)
if [ "$S" != "Asleep" ] && [ "$S" != "Dozing" ]; then
  log "!! ABORT: wakefulness is '$S'. With the display on the SoC cannot suspend, so"
  log "   this window would measure nothing. Turn the screen off (or pass KEY) and re-run."
  log "=== END (aborted) ==="
  exit 2
fi

T0_W=$(wall_now); T0_M=$(mono_now A)
E0=$(cnt_entry); D0=$(cnt_deep); X0=$(cnt_exit); A0=$(cnt_wakealarm); PS0=$(power_sig)
log "--- BASELINE (t0) ---"
log "    wall=$T0_W  mono=${T0_M:-unavailable}  entries=$E0 (deep $D0) exits=$X0 wakeup_alarms=$A0"
log "    cable state: $PS0"
log "    wakers now:"
waker_report

log "--- WINDOW ${WINDOW}s, screen off — leave the device alone ---"
sleep "$WINDOW"

T1_W=$(wall_now); T1_M=$(mono_now B)
E1=$(cnt_entry); D1=$(cnt_deep); X1=$(cnt_exit); A1=$(cnt_wakealarm); PS1=$(power_sig)
log "--- RESULT (t1) ---"
log "    wall=$T1_W  mono=${T1_M:-unavailable}  wakefulness=$(screen_state)"
log "    suspend entries +$((E1 - E0)) (deep +$((D1 - D0)))  exits +$((X1 - X0))  wakeup_alarm_lines delta=$((A1 - A0))"
if [ "$PS0" != "$PS1" ]; then
  log "    !! CABLE STATE CHANGED during the window ($PS0 -> $PS1): USB attach/detach wakes"
  log "       the SoC and blocks suspend, so a low result here is expected, not a finding."
fi

if [ -n "$T0_M" ] && [ -n "$T1_M" ]; then
  set -- $(awk -v w="$T0_W" -v w1="$T1_W" -v m="$T0_M" -v m1="$T1_M" 'BEGIN{
      wall = w1 - w; mono = m1 - m; s = wall - mono;
      if (s < 0) s = 0;
      printf "%d %.1f %.1f %.1f", wall, mono, s, (wall > 0 ? 100 * s / wall : 0) }')
  WALL=$1; MONO=$2; SUSP=$3; PCT=$4
  log "    suspended ${SUSP}s of ${WALL}s wall (${PCT}%)   [monotonic clock advanced ${MONO}s]"
  log "    $(episodes "$T0_M")"
  if [ "$(awk -v p="$PCT" 'BEGIN{print (p >= 90) ? 1 : 0}')" = "1" ]; then
    log "    VERDICT: DEEP SLEEP IS WORKING — the SoC was suspended for ${PCT}% of the window."
  elif [ "$(awk -v p="$PCT" 'BEGIN{print (p >= 25) ? 1 : 0}')" = "1" ]; then
    log "    VERDICT: PARTIAL SUSPEND (${PCT}%) — it sleeps but is woken often; see the episode stats."
  else
    log "    VERDICT: NOT SUSPENDING (${PCT}%) — the SoC stayed awake for the whole window."
    log "    Check, in this order:"
    log "      1. wakefulness above must be Asleep/Dozing, not Awake"
    log "      2. stay_on_while_plugged_in=$(settings get global stay_on_while_plugged_in) must be 0, and 'svc power stayon' false"
    log "      3. the framework suspend blockers printed above (PowerManagerService.Display / .WakeLocks)"
    log "      4. an ACTIVE entry in the wakeup_sources list above"
    log "      5. leftover userspace lock — /sys/power/wake_lock should be empty:"
    log "         '$(rd /sys/power/wake_lock)'"
  fi
  log "    note: with the gauge frozen below ~10 mA (see docs/SLEEP_CLAMP_TEST.md) do not"
  log "          expect the current_now reading to corroborate this; suspend time is the"
  log "          trustworthy signal."
else
  log "    VERDICT: UNMEASURABLE — no monotonic clock. Run as root so /dev/kmsg and dmesg work."
fi
log "    log: $LOG"
log "=== END $(date '+%Y-%m-%d %H:%M:%S') ==="