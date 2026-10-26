package com.veloster.carplayhub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Manifest receiver accepts system boot/package-replacement events, never opens an Activity. */
public final class QuickEntryReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent){
        String action=intent==null?null:intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||"android.intent.action.MY_PACKAGE_REPLACED".equals(action))
            QuickEntryService.restore(context);
    }
}
