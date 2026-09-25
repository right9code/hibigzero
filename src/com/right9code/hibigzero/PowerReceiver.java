package com.right9code.hibigzero;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Wakes on charger attach/detach and on the charge-limit poll alarm.
 *
 * Uses goAsync() to ensure the background thread finishes executing
 * the required root sysfs commands before Android reaps the broadcast transaction.
 */
public class PowerReceiver extends BroadcastReceiver {
    private static final String TAG = "PowerReceiver";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        final PendingResult pendingResult = goAsync();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ChargeLimitController.ACTION_TICK.equals(action)) {
                        ChargeLimitController.onTickSync(context);
                    } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                        ChargeLimitController.onPowerEventSync(context, true);
                    } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                        ChargeLimitController.onPowerEventSync(context, false);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in PowerReceiver: " + e.getMessage(), e);
                } finally {
                    ShellUtils.flushLog();
                    pendingResult.finish();
                }
            }
        }).start();
    }
}