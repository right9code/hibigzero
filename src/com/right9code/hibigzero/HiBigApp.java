package com.right9code.hibigzero;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.util.Log;
import java.util.Properties;

public class HiBigApp extends Application {
    private static ScreenReceiver screenReceiver;
    private static boolean receiverRegistered = false;
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        Properties cfg = ConfigManager.loadConfig();
        String enabled = cfg.getProperty("SLEEP_GOVERNOR_ENABLED", "1");
        Log.i("HiBigApp", "onCreate: SLEEP_GOVERNOR_ENABLED=" + enabled);
        if ("1".equals(enabled)) {
            registerScreenReceiver();
        }
    }

    public static void registerScreenReceiver() {
        Log.i("HiBigApp", "registerScreenReceiver: registered=" + receiverRegistered + " ctx=" + (appContext != null));
        if (receiverRegistered || appContext == null) return;
        try {
            screenReceiver = new ScreenReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.SCREEN_OFF");
            filter.addAction("android.intent.action.SCREEN_ON");
            appContext.registerReceiver(screenReceiver, filter);
            receiverRegistered = true;
            Log.i("HiBigApp", "ScreenReceiver registered successfully");
            ShellUtils.appendLog("ScreenReceiver registered");
        } catch (Exception e) {
            Log.e("HiBigApp", "ScreenReceiver register FAILED: " + e.getMessage(), e);
            ShellUtils.appendLog("ScreenReceiver register error: " + e.getMessage());
        }
    }

    public static void unregisterScreenReceiver() {
        if (!receiverRegistered || screenReceiver == null || appContext == null) return;
        try {
            appContext.unregisterReceiver(screenReceiver);
            screenReceiver = null;
            receiverRegistered = false;
            ShellUtils.appendLog("ScreenReceiver unregistered");
        } catch (Exception ignored) {}
    }

    public static boolean isReceiverRegistered() {
        return receiverRegistered;
    }
}
