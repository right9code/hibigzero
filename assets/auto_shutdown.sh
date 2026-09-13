#!/system/bin/sh
# HiBreak Manager - Auto-Shutdown Daemon v2.1
# Triggers clean power-off (reboot -p) after inactivity so E-ink retains 0.00 mA
CONF="/data/local/tmp/hibreak.conf"
PID_FILE="/data/local/tmp/autoshutdown.pid"
TIMEOUT_MIN="${1:-120}"

echo $$ > "$PID_FILE"
trap 'rm -f "$PID_FILE"' EXIT INT TERM

get_config() {
    grep "^$1=" "$CONF" 2>/dev/null | cut -d '=' -f2 | tr -d ' \r\n'
}
is_screen_on() {
    dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"
}
is_charging() {
    STATUS=$(cat /sys/class/power_supply/battery/status 2>/dev/null)
    [ "$STATUS" = "Charging" ] || [ "$STATUS" = "Full" ]
}
is_inhibited() {
    dumpsys power 2>/dev/null | grep -qi "termux" && return 0
    ip link show tailscale0 >/dev/null 2>&1 && return 0
    return 1
}

SLEEP_COUNT=0
while true; do
    CFG_ENABLE="$(get_config AUTO_SHUTDOWN_ENABLED)"
    [ "$CFG_ENABLE" = "0" ] && exit 0
    CFG_MIN="$(get_config AUTO_SHUTDOWN_TIMEOUT_MIN)"
    [ -n "$CFG_MIN" ] && TIMEOUT_MIN="$CFG_MIN"
    TIMEOUT_SEC=$(( TIMEOUT_MIN * 60 ))
    sleep 30
    if is_charging || is_screen_on || is_inhibited; then
        SLEEP_COUNT=0
    else
        SLEEP_COUNT=$(( SLEEP_COUNT + 30 ))
        if [ "$SLEEP_COUNT" -ge "$TIMEOUT_SEC" ]; then
            am broadcast -a com.xrz.action.STANDBY_SCREEN 2>/dev/null
            sleep 1
            sync
            echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
            /system/bin/reboot -p || svc power shutdown
            exit 0
        fi
    fi
done
