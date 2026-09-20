package com.right9code.hibigzero;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Wakes on charger attach/detach and on the charge-limit poll alarm.
 *
 * Registered in the manifest rather than dynamically: ACTION_POWER_CONNECTED and
 * ACTION_POWER_DISCONNECTED are on the implicit-broadcast exemption list, so they
 * are still delivered after the process has been killed. That matters, because
 * attaching the charger has to resume charging even when nothing else of ours is
 * alive - otherwise a switch left "off" would look like a broken charger.
 */
public class PowerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (ChargeLimitController.ACTION_TICK.equals(action)) {
            ChargeLimitController.onTick(context);
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            ChargeLimitController.onPowerEvent(context, true);
        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            ChargeLimitController.onPowerEvent(context, false);
        }
    }
}