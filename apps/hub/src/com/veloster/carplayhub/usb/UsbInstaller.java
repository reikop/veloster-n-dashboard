package com.veloster.carplayhub.usb;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * USB update on the head unit: finds vn-update/ on a mounted stick, checks the signed manifest against this unit and
 * the installed apps, copies each APK into this app's own storage and checks the copy (UsbBundle), then installs it.
 *
 * Installing: both apps carry the platform signature the head unit accepts, so My Apps may hold INSTALL_PACKAGES and
 * use the hidden PackageManager.installPackage (Android 4.2) - no installer screen. When that is refused, the copy
 * goes to the system installer screen instead (one tap per app). The dashboard goes first; My Apps last, since
 * replacing itself ends this process (MY_PACKAGE_REPLACED then reports it).
 */
public final class UsbInstaller {
    /** Where the head unit mounts USB sticks (the stock log export writes to /storage/usb0). */
    public static final String[] ROOTS = {"/storage/usb0", "/storage/usb1", "/storage/usb2", "/storage/usb3", "/mnt/usb", "/mnt/usb0", "/mnt/usb_storage", "/storage/sdcard1"};
    static final String PREFS = "usb_update";
    private static final int INSTALL_REPLACE_EXISTING = 2;

    /** A stick worth offering: the bundle, its folder, and the apps in it that are newer than what is installed. */
    public static final class Offer {
        public final UsbBundle bundle; public final File folder; public final List<UsbBundle.App> apps = new ArrayList<UsbBundle.App>();
        public final List<Integer> installed = new ArrayList<Integer>();
        Offer(UsbBundle b, File f) { bundle = b; folder = f; }
    }

    public interface Progress { void step(String message); }

    private final Context context;
    public UsbInstaller(Context c) { context = c.getApplicationContext(); }

    /** The first mounted stick (the given root first) with a valid bundle newer than the installed apps; null when none. */
    public Offer find(String preferredRoot) {
        List<String> roots = new ArrayList<String>();
        if (preferredRoot != null) roots.add(preferredRoot);
        for (String r : ROOTS) if (!roots.contains(r)) roots.add(r);
        for (String r : roots) {
            File folder = new File(r, UsbBundle.FOLDER);
            if (!new File(folder, UsbBundle.MANIFEST).isFile()) continue;
            try { Offer o = check(folder); if (!o.apps.isEmpty()) return o; } catch (Exception e) { lastError = e.getMessage(); }
        }
        return null;
    }

    /** Why the last stick with a vn-update folder was not offered (null when none was seen). */
    public String lastError;

    public Offer check(File folder) throws Exception {
        UsbBundle b = UsbBundle.verify(UsbBundle.read(new File(folder, UsbBundle.MANIFEST), UsbBundle.MAX_MANIFEST),
            UsbBundle.read(new File(folder, UsbBundle.SIGNATURE), 256), UsbBundle.key(UsbUpdateKey.DER));
        b.checkDevice(Build.PRODUCT, Build.VERSION.SDK_INT);
        Offer o = new Offer(b, folder);
        for (UsbBundle.App a : b.apps) {
            PackageInfo p = installedInfo(a.packageName);
            int have = p == null ? 0 : p.versionCode;
            if (!UsbBundle.newer(a, have)) continue;
            UsbBundle.require(b.signer.equals(signer(p)), "the installed " + a.packageName + " is signed differently");
            o.apps.add(a); o.installed.add(have);
        }
        return o;
    }

    private PackageInfo installedInfo(String name) {
        try { return context.getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNATURES); } catch (PackageManager.NameNotFoundException e) { return null; }
    }

    private static String signer(PackageInfo p) throws Exception {
        UsbBundle.require(p != null && p.signatures != null && p.signatures.length == 1, "installed signer");
        return UsbBundle.hex(MessageDigest.getInstance("SHA-256").digest(p.signatures[0].toByteArray()));
    }

    /** Copy and check every APK of the offer, then install them in order. Returns a line per app. Background thread. */
    public List<String> install(Offer o, Progress progress) throws Exception {
        List<File> staged = new ArrayList<File>();
        try {
            for (UsbBundle.App a : o.apps) {
                progress.step(a.packageName + ": copy");
                File f = stage(new File(o.folder, a.file), a);
                o.bundle.checkApk(f, a);
                PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                UsbBundle.require(archive != null && a.packageName.equals(archive.packageName) && archive.versionCode == a.version, "APK package / version");
                staged.add(f);
            }
            List<String> done = new ArrayList<String>();
            for (int i = 0; i < o.apps.size(); i++) {
                UsbBundle.App a = o.apps.get(i); File f = staged.get(i);
                progress.step(a.packageName + ": install");
                if (a.packageName.equals(context.getPackageName()))   // replacing ourselves: the report comes after the restart
                    context.getSharedPreferences(PREFS, 0).edit().putInt("pending_self", a.version).putString("release", o.bundle.release).commit();
                silent(f);
                if (!waitFor(a.packageName, a.version, 180000)) throw new IOException("install of " + a.packageName + " did not finish");
                done.add(a.packageName + " " + a.version);
            }
            return done;
        } finally {
            for (File f : staged) f.delete();
        }
    }

    /** True when the silent path is refused (no INSTALL_PACKAGES): the caller then hands each copy to installerIntent. */
    public static boolean refused(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof SecurityException || t instanceof NoSuchMethodException) return true;
        return false;
    }

    private void silent(File f) throws Exception {
        Class<?> observer = Class.forName("android.content.pm.IPackageInstallObserver");
        PackageManager pm = context.getPackageManager();
        try {
            pm.getClass().getMethod("installPackage", Uri.class, observer, int.class, String.class)
                .invoke(pm, Uri.fromFile(f), null, INSTALL_REPLACE_EXISTING, context.getPackageName());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause(); if (c instanceof Exception) throw (Exception) c; throw e;
        }
    }

    private boolean waitFor(String name, int version, long timeoutMs) {
        long until = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < until) {
            PackageInfo p = installedInfo(name);
            if (p != null && p.versionCode == version) return true;
            SystemClock.sleep(500);
        }
        return false;
    }

    /** Fallback: the checked copy of one app for the system installer screen (API 17 takes file:// URIs). */
    public Intent installerIntent(Offer o, UsbBundle.App a) throws Exception {
        File f = stage(new File(o.folder, a.file), a);
        o.bundle.checkApk(f, a);
        UsbBundle.require(f.setReadable(true, false), "installer file access");
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** Copies an APK from the stick into files/ (readable by the package manager), checking its size on the way. */
    private File stage(File source, UsbBundle.App a) throws Exception {
        UsbBundle.require(source.isFile() && source.length() == a.bytes, a.file + " missing or a different size");
        File dir = context.getFilesDir();
        dir.setExecutable(true, false);   // the package manager's copier reads the file by path
        File out = new File(dir, "usb-" + a.packageName + ".apk");
        out.delete();
        InputStream in = new FileInputStream(source);
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] b = new byte[65536]; int n; long total = 0;
            while ((n = in.read(b)) != -1) { total += n; UsbBundle.require(total <= a.bytes, a.file + " grew"); os.write(b, 0, n); }
        } finally { in.close(); os.close(); }
        out.setReadable(true, false);
        return out;
    }

    /** After My Apps replaced itself: the version it asked for, or 0. Cleared once read. */
    public static int takePendingSelf(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        int v = p.getInt("pending_self", 0);
        if (v != 0) p.edit().remove("pending_self").apply();
        return v;
    }
}
