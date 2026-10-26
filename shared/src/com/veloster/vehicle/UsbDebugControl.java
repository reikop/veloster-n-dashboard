package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.content.Context;

/** Exact OEM Factory Test USB mode operations. No shell, TCP ADB or settings writes. */
public final class UsbDebugControl {
    public static boolean request(Context context,boolean enabled)throws Exception {
        Class<?> type=Class.forName("com.hkmc.misc.MiscManager");
        Object manager=type.getConstructor(Context.class).newInstance(context);
        Object accepted=type.getMethod("setSystem",Integer.TYPE).invoke(manager,Integer.valueOf(enabled?5:6));
        return Boolean.TRUE.equals(accepted);
    }
    public static String status() {
        try {
            Class<?> feature=Class.forName("com.hkmc.Feature");
            boolean ium=Boolean.TRUE.equals(feature.getMethod("hasFeature",String.class).invoke(null,"com.hkmc.software.ium"));
            String key=ium?"sys.usb.config":"persist.sys.dipo.state";
            String value=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class,String.class).invoke(null,key,"");
            if((ium&&"adb".equals(value)) || (!ium&&"debug".equals(value)))return UiText.t("설정: ON");
            if((ium&&"host".equals(value)) || (!ium&&"carplay".equals(value)))return UiText.t("설정: OFF");
        } catch(Exception e) { /* Unknown is distinct from OFF. */ }
        return UiText.t("설정 상태 확인 불가");
    }
}
