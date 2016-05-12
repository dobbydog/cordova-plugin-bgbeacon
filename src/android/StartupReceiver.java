package com.hinohunomi.bgbeacon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class StartupReceiver extends BroadcastReceiver {
    private static final String TAG = "StartupReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive");
        if (intent != null) {
            //test
            //adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -c android.intent.category.DEFAULT --include-stopped-packages
            //adb shell am broadcast -a android.intent.action.MY_PACKAGE_REPLACED -n com.hinohunomi.bgbeacon/com.hinohunomi.bgbeacon.StartupReceiver
            boolean canStart = false;
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Log.d(TAG, "received: BOOT_COMPLETED");
                canStart = true;
            } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                Log.d(TAG, "received: ACTION_MY_PACKAGE_REPLACED");
                canStart = true;
            }
            if (canStart) {
                StartupReceiver.StartBeaconService(context);
            }
        }
    }
    
    static public void StartBeaconService(Context context) {
        Log.d(TAG, "StartBeaconService");
        Intent i = new Intent(context, BeaconService.class);
        context.startService(i);
    }
}
