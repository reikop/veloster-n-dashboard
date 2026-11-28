package com.veloster.carplayhub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.veloster.carplayhub.usb.UsbInstaller;
import java.io.File;

/**
 * A USB stick mounted: when it carries a vn-update bundle newer than the installed apps, open USB 업데이트 to offer it
 * (nothing installs without a tap there). A stick without the folder, or with an update already installed, is left
 * alone. If the head unit does not send this broadcast, 내 앱 → USB 업데이트 does the same by hand.
 */
public final class UsbMountReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context context, Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        final String root = data == null ? null : data.getPath();
        if (root == null || !new File(root, "vn-update/manifest.txt").isFile()) return;
        final PendingResult pending = goAsync();
        new Thread(new Runnable() { public void run() {
            try {
                if (new UsbInstaller(context).find(root) != null)
                    context.startActivity(new Intent(context, UsbUpdateActivity.class).putExtra(UsbUpdateActivity.EXTRA_ROOT, root)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (RuntimeException e) {
                com.veloster.diagnostics.ErrorLog.record("usb_update_mount", e);
            } finally { pending.finish(); }
        }}, "usb-update-mount").start();
    }
}
