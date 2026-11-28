package com.veloster.carplayhub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import com.veloster.carplayhub.usb.UsbInstaller;
import com.veloster.i18n.UiText;

/** USB 업데이트 replaced My Apps itself (the process ended mid-install): say it finished, once. */
public final class UsbUpdateDone extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (UsbInstaller.takePendingSelf(context) != 0)
            Toast.makeText(context, UiText.t("업데이트 완료") + " · " + UiText.t("내 앱"), Toast.LENGTH_LONG).show();
    }
}
