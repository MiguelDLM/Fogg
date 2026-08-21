package com.example.dialsender.ble;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores the background connection after a reboot, like the original app
 * (which also declares RECEIVE_BOOT_COMPLETED). Only starts the service if the
 * user actually has a bound watch and has not explicitly disconnected.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        BleManager ble = BleManager.getInstance(context);
        if (ble.getVerifiedDeviceAddress() == null || !ble.isAutoConnectEnabled())
            return;
        BleForegroundService.start(context);
    }
}
