package com.usbaudio.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootReceiver", "Boot completed - starting service");
            
            Intent serviceIntent = new Intent(context, AudioService.class);
            context.startService(serviceIntent);
        }
    }
}
