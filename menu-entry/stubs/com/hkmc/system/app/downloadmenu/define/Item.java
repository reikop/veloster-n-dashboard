package com.hkmc.system.app.downloadmenu.define;

import android.graphics.Bitmap;

/** Compile-only API surface; the OEM Item class is always retained in the APK. */
public final class Item {
    private final String packageName;
    private final String className;
    public Item(int id, String pkg, String cls, String title, Bitmap icon, Bitmap disabledIcon) {
        packageName = pkg;
        className = cls;
    }
    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }
}
