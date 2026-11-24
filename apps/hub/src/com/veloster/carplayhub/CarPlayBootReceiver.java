package com.veloster.carplayhub;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System startup wake only. Persisted opt-in, Stop and budget are checked by the app. */
public final class CarPlayBootReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent){
        if(intent==null)return;String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        // Start an owned Service while this receiver is alive; do not leave
        // unprotected asynchronous work after onReceive returns.
        context.startService(new Intent(context,com.veloster.carplay.coordinator.WirelessCarPlayService.class)
            .setAction(com.veloster.carplay.coordinator.WirelessCarPlayService.ACTION_AUTOMATIC_WAKE));
    }
}
