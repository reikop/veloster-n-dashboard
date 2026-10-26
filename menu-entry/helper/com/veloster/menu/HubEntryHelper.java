package com.veloster.menu;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import com.hkmc.system.app.downloadmenu.define.Item;
import java.util.ArrayList;

/** Called only from the OEM menu's existing main-thread initialization. */
public final class HubEntryHelper {
    private static final String PACKAGE = "com.veloster.carplayhub";
    private static final String ACTIVITY = "com.veloster.carplayhub.HubActivity";
    private static Item cachedItem;
    private static int oemCount;

    private HubEntryHelper() {}

    public static void prepare(Context context) {
        cachedItem = null;
        oemCount = 0;
        try {
            PackageManager pm = context.getPackageManager();
            ActivityInfo info = pm.getActivityInfo(new ComponentName(PACKAGE, ACTIVITY), 0);
            if (!info.enabled || !info.exported || !info.applicationInfo.enabled) return;
            if (info.permission != null && pm.checkPermission(info.permission, context.getPackageName())
                    != PackageManager.PERMISSION_GRANTED) return;
            Drawable drawable = info.loadIcon(pm);
            if (drawable == null) return;
            Bitmap icon = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, 128, 128);
            drawable.draw(new Canvas(icon));
            cachedItem = new Item(0x10000, PACKAGE, ACTIVITY, "내 앱", icon, icon);
        } catch (Exception unavailable) {
            // Missing, disabled or inaccessible Hub preserves the original OEM list.
            cachedItem = null;
        }
    }

    public static int adjustDisplayCount(int original) {
        oemCount = original;
        return original + (cachedItem == null ? 0 : 1);
    }

    public static int originalCount() { return oemCount; }

    public static void append(ArrayList<Item> items) {
        if (cachedItem == null) return;
        for (Item item : items) {
            if (PACKAGE.equals(item.getPackageName()) && ACTIVITY.equals(item.getClassName())) return;
        }
        items.add(cachedItem);
    }
}
