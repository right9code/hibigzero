#!/system/bin/sh
# HiBreak Manager - Auto-Shutdown Daemon v3.0
# Uses mLastUserActivityTime to detect REAL user touch, not passive screen wakes
# Escapes Android 14 app freezer by moving to root cgroup
echo $$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null
echo $$ > /dev/cpuset/cgroup.procs 2>/dev/null
echo $$ > /dev/freezer/cgroup.procs 2>/dev/null

CONF="/data/local/tmp/hibreak.conf"
PID_FILE="/data/local/tmp/autoshutdown.pid"
LOG_FILE="/data/local/tmp/autoshutdown.log"
TIMEOUT_MIN="${1:-120}"

# Single-instance guard
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && [ "$OLD_PID" -ne "$$" ] && [ -d "/proc/$OLD_PID" ]; then
        if grep -q "auto_shutdown" "/proc/$OLD_PID/cmdline" 2>/dev/null; then
            exit 0
        fi
    fi
fi

echo $$ > "$PID_FILE"
trap 'rm -f "$PID_FILE"' EXIT INT TERM

echo "$(date '+%Y-%m-%d %H:%M:%S'): Daemon started (PID: $$, timeout: ${TIMEOUT_MIN}m)" >> "$LOG_FILE"

get_config() {
    [ -f "$CONF" ] || return 1
    sed 's/\\n/\n/g' "$CONF" 2>/dev/null | tr -d '\r' | grep "^$1=" | tail -n 1 | cut -d '=' -f2- | tr -d ' '
}

# Returns the last user activity timestamp (touch/button press only)
get_last_user_activity() {
    dumpsys power 2>/dev/null | grep "mLastUserActivityTime" | sed 's/.*=//' | head -1
}

is_charging() {
    STATUS=$(cat /sys/class/power_supply/battery/status 2>/dev/null)
    [ "$STATUS" = "Charging" ] || [ "$STATUS" = "Full" ]
}

is_inhibited() {
    ip link show tailscale0 >/dev/null 2>&1 && return 0
    return 1
}

SLEEP_COUNT=0
LAST_ACTIVITY="$(get_last_user_activity)"

while true; do
    CFG_ENABLE="$(get_config AUTO_SHUTDOWN_ENABLED)"
    if [ "$CFG_ENABLE" = "0" ]; then
        echo "$(date '+%Y-%m-%d %H:%M:%S'): Daemon disabled in config. Exiting." >> "$LOG_FILE"
        exit 0
    fi

    CFG_MIN="$(get_config AUTO_SHUTDOWN_TIMEOUT_MIN)"
    case "$CFG_MIN" in
        ''|*[!0-9]*) ;;
        *) TIMEOUT_MIN="$CFG_MIN" ;;
    esac

    TIMEOUT_SEC=$(( TIMEOUT_MIN * 60 ))
    sleep 30

    # Check if real user activity occurred (touch/button, not notification/sensor)
    CURRENT_ACTIVITY="$(get_last_user_activity)"
    USER_INTERACTED=0
    if [ -n "$CURRENT_ACTIVITY" ] && [ "$CURRENT_ACTIVITY" != "$LAST_ACTIVITY" ]; then
        USER_INTERACTED=1
        LAST_ACTIVITY="$CURRENT_ACTIVITY"
    fi

    CHRG="no"; is_charging && CHRG="yes"
    INHIB="no"; is_inhibited && INHIB="yes"

    if [ "$CHRG" = "yes" ] || [ "$INHIB" = "yes" ] || [ "$USER_INTERACTED" -eq 1 ]; then
        if [ "$SLEEP_COUNT" -gt 0 ]; then
            echo "$(date '+%Y-%m-%d %H:%M:%S'): RESET chrg=$CHRG inhib=$INHIB touch=$USER_INTERACTED (was ${SLEEP_COUNT}s) lastAct=$CURRENT_ACTIVITY" >> "$LOG_FILE"
        fi
        SLEEP_COUNT=0
    else
        SLEEP_COUNT=$(( SLEEP_COUNT + 30 ))
        echo "$(date '+%Y-%m-%d %H:%M:%S'): TICK ${SLEEP_COUNT}s/${TIMEOUT_SEC}s act=$CURRENT_ACTIVITY prev=$LAST_ACTIVITY" >> "$LOG_FILE"
        if [ "$SLEEP_COUNT" -ge "$TIMEOUT_SEC" ]; then
            echo "$(date '+%Y-%m-%d %H:%M:%S'): SHUTDOWN ($TIMEOUT_MIN min reached)" >> "$LOG_FILE"
            sync
            echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
            /system/bin/reboot -p || setprop sys.powerctl shutdown || svc power shutdown
            exit 0
        fi
    fi
done
