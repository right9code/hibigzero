#!/system/bin/sh
# HiBreak Manager — on-battery drain sampler (read-only diagnostics)
#
# Designed to NOT disturb suspend: the sampling loop wakes the CPU only every
# LIGHT_SEC seconds (default 900 = 15 min), reading cheap sysfs nodes only.
# Heavy dumps run at start and end. Changes no settings. Safe to kill.

OUT=/data/local/tmp/diag
LOG=$OUT/drain_sample.log
WS=/sys/kernel/debug/wakeup_sources
LIGHT_SEC=900     # 15 min between cheap samples
HEAVY_EVERY=2     # heavy snapshot every 2 light samples (30 min)

mkdir -p "$OUT"

snapshot_heavy() {
  {
    echo "--- WAKERS $(date '+%Y-%m-%d %H:%M:%S') (prevent_suspend_time>0) ---"
    awk 'NR>1 && NF>=10 && $10>0 {printf "%s prevent=%s wakeups=%s events=%s\n", $1, $10, $4, $3}' "$WS" 2>/dev/null
    echo "--- KERNEL WAKELOCKS ---"
    dumpsys batterystats --charged 2>/dev/null | grep -E "Kernel Wake lock" | head -20
    echo "--- PARTIAL WAKELOCKS ---"
    dumpsys batterystats --charged 2>/dev/null | sed -n '/All partial wake locks/,/All wakeup reasons/p' | head -25
    echo "--- DOZE ---"
    dumpsys deviceidle 2>/dev/null | grep -E "mState=|mLightState=|mDeepEnabled|mLightEnabled"
    echo "--- UART LOOP ---"
    echo "uart_svc=$(getprop init.svc.uart2serport) crash=$(getprop sys.init.updatable_crashing) flags_spawn_total=$(logcat -d 2>/dev/null | grep -c ServerConfigurableFlagsReset)"
    echo "--- ALARMS ---"
    dumpsys alarm 2>/dev/null | sed -n '/Alarm Stats:/,/Alarm manager stats/p' | head -40
  } >> "$LOG"
}

light_sample() {
  TS=$(date '+%Y-%m-%d %H:%M:%S')
  STATUS=$(cat /sys/class/power_supply/battery/status 2>/dev/null)
  CAP=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)
  CUR=$(cat /sys/class/power_supply/battery/current_now 2>/dev/null)
  VOL=$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null)
  TMP=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)
  UPT=$(cat /proc/uptime | cut -d' ' -f1)
  echo "$TS | $STATUS | cap=${CAP}% | cur=${CUR}uA | v=${VOL}uV | t=${TMP} | uptime=${UPT}" >> "$LOG"
}

{
  echo "=============================================="
  echo "BASELINE START $(date '+%Y-%m-%d %H:%M:%S')"
  echo "uptime: $(cat /proc/uptime)"
  echo "boot_reason: $(getprop ro.boot.bootreason)"
} >> "$LOG"

# Wait for USB to be physically removed before timing starts.
# (Charging samples are meaningless for battery drain.)
while [ "$(cat /sys/class/power_supply/battery/status 2>/dev/null)" != "Discharging" ]; do
  sleep 30
done
echo "=== DISCHARGING DETECTED $(date '+%Y-%m-%d %H:%M:%S') ===" >> "$LOG"
snapshot_heavy

i=0
while true; do
  # stop logging once plugged back in
  ST=$(cat /sys/class/power_supply/battery/status 2>/dev/null)
  if [ "$ST" = "Charging" ] || [ "$ST" = "Full" ]; then
    echo "=== CHARGER RE-ATTACHED $(date '+%Y-%m-%d %H:%M:%S') ===" >> "$LOG"
    snapshot_heavy
    echo "=== BASELINE END ===" >> "$LOG"
    exit 0
  fi

  light_sample

  if [ $(( i % HEAVY_EVERY )) -eq 1 ]; then
    snapshot_heavy
  fi

  i=$(( i + 1 ))
  sleep "$LIGHT_SEC"
done
