package com.veloster.carplayhub.usb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The USB update bundle: a folder vn-update/ on a USB stick with manifest.txt, manifest.sig and the APKs it names.
 * No Android: host-tested. The Android side (UsbUpdateActivity) copies each APK into the app's own storage, checks it
 * here, then installs it.
 *
 * Why a signature of our own: both apps are signed with the AOSP platform test key, which the head unit accepts and
 * which anyone can download. An APK signed with it proves nothing, so a stick is trusted only when manifest.txt is
 * signed with the update key (RSA 2048, SHA-256; UsbUpdateKey holds the public half) and every APK matches the
 * manifest's size, SHA-256 and signer.
 *
 * manifest.txt (ASCII, LF, this order):
 *   VNUSB1
 *   release=1.15.0
 *   product=jsn_kr
 *   sdk=17
 *   signer=<SHA-256 of the APK signing certificate, hex>
 *   app=<package>,<versionCode>,<bytes>,<sha256>,<file name>      (one or two: the dashboard and / or My Apps)
 */
public final class UsbBundle {
    public static final String DASHBOARD = "com.veloster.dashboard", HUB = "com.veloster.carplayhub";
    public static final String FOLDER = "vn-update", MANIFEST = "manifest.txt", SIGNATURE = "manifest.sig";
    public static final int MAX_MANIFEST = 2048, MAX_APK = 64 * 1024 * 1024;
    static final long MAX_EXPANDED = 256L * 1024 * 1024;

    public static final class App {
        public final String packageName, sha256, file;
        public final int version, bytes;
        App(String packageName, int version, int bytes, String sha256, String file) {
            this.packageName = packageName; this.version = version; this.bytes = bytes; this.sha256 = sha256; this.file = file;
        }
    }

    public final String release, product, signer;
    public final int sdk;
    /** In install order: the dashboard before My Apps (installing My Apps ends the process doing the install). */
    public final List<App> apps;

    private UsbBundle(String release, String product, int sdk, String signer, List<App> apps) {
        this.release = release; this.product = product; this.sdk = sdk; this.signer = signer; this.apps = apps;
    }

    public static void require(boolean ok, String reason) throws IOException { if (!ok) throw new IOException("USB update rejected: " + reason); }

    /** The signed manifest, or an IOException naming why not. */
    public static UsbBundle verify(byte[] data, byte[] signature, PublicKey key) throws IOException, GeneralSecurityException {
        require(data != null && data.length > 0 && data.length <= MAX_MANIFEST, "manifest size");
        require(signature != null && signature.length == 256, "signature size");
        Signature v = Signature.getInstance("SHA256withRSA"); v.initVerify(key); v.update(data);
        require(v.verify(signature), "signature");
        for (byte b : data) require(b == 10 || (b >= 32 && b <= 126), "ASCII manifest");
        String[] lines = new String(data, "US-ASCII").split("\n", -1);
        require(lines.length >= 7 && lines.length <= 8 && lines[lines.length - 1].length() == 0 && "VNUSB1".equals(lines[0]), "format");
        String release = field(lines[1], "release"), product = field(lines[2], "product"), sdk = field(lines[3], "sdk"), signer = field(lines[4], "signer");
        require(release.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}"), "release");
        require(product.matches("[a-z0-9_]{1,32}"), "product");
        require(signer.matches("[0-9a-f]{64}"), "signer");
        List<App> apps = new ArrayList<App>();
        Set<String> seen = new HashSet<String>();
        for (int i = 5; i < lines.length - 1; i++) {
            String[] f = field(lines[i], "app").split(",", -1);
            require(f.length == 5, "app fields");
            require(DASHBOARD.equals(f[0]) || HUB.equals(f[0]), "package");
            require(seen.add(f[0]), "package twice");
            int bytes = number(f[2]);
            require(bytes <= MAX_APK, "APK size");
            require(f[3].matches("[0-9a-f]{64}"), "APK digest");
            require(f[4].matches("[a-z0-9][a-z0-9.-]{0,40}\\.apk"), "APK file name");
            apps.add(new App(f[0], number(f[1]), bytes, f[3], f[4]));
        }
        require(!apps.isEmpty(), "no app");
        if (apps.size() == 2 && HUB.equals(apps.get(0).packageName)) apps = Arrays.asList(apps.get(1), apps.get(0));
        return new UsbBundle(release, product, number(sdk), signer, apps);
    }

    private static String field(String line, String name) throws IOException {
        require(line.startsWith(name + "="), "field " + name);
        return line.substring(name.length() + 1);
    }

    private static int number(String s) throws IOException {
        require(s.matches("[1-9][0-9]{0,8}"), "number");
        return Integer.parseInt(s);
    }

    /** The bundle is for this head unit. */
    public void checkDevice(String liveProduct, int liveSdk) throws IOException {
        require(product.equals(liveProduct) && sdk == liveSdk, "made for " + product + " / API " + sdk);
    }

    /** An app worth installing: newer than the one installed (versionCode; 0 = not installed is refused) and signed the same. */
    public static boolean newer(App app, int installedVersion) { return installedVersion > 0 && app.version > installedVersion; }

    /** A copied APK: the manifest's size and SHA-256, a single signer whose certificate hashes to the manifest's signer
     *  on every entry (V1 JAR signatures, the only kind API 17 knows), a classes.dex and an AndroidManifest.xml. */
    public void checkApk(File file, App app) throws IOException, GeneralSecurityException {
        require(file.length() == app.bytes && sha256(file).equals(app.sha256), "APK changed");
        JarFile jar = new JarFile(file, true);
        boolean dex = false, manifest = false; long expanded = 0; Set<String> names = new HashSet<String>();
        try {
            byte[] buffer = new byte[8192];
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement(); String name = e.getName();
                require(names.add(name) && names.size() <= 8192 && !name.startsWith("/") && !name.contains("..") && !name.contains("\\"), "ZIP entry");
                if (e.isDirectory()) continue;
                InputStream in = jar.getInputStream(e);
                try { int n; while ((n = in.read(buffer)) != -1) { expanded += n; require(expanded <= MAX_EXPANDED, "expanded size"); } } finally { in.close(); }
                if (name.startsWith("META-INF/")) continue;
                Certificate[] certs = e.getCertificates();
                require(certs != null && certs.length == 1, "unsigned entry " + name);
                require(hex(MessageDigest.getInstance("SHA-256").digest(certs[0].getEncoded())).equals(signer), "APK signer");
                if (name.equals("classes.dex")) dex = true;
                if (name.equals("AndroidManifest.xml")) manifest = true;
            }
        } catch (SecurityException e) {
            throw new IOException("USB update rejected: APK signature");
        } finally { jar.close(); }
        require(dex && manifest, "APK structure");
    }

    public static String sha256(File file) throws IOException, GeneralSecurityException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(file);
        try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) md.update(b, 0, n); } finally { in.close(); }
        return hex(md.digest());
    }

    public static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) { int n = b & 255; if (n < 16) s.append('0'); s.append(Integer.toHexString(n)); }
        return s.toString();
    }

    public static byte[] read(File file, int max) throws IOException {
        require(file.isFile() && file.length() <= max, file.getName() + " size");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(file);
        try { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) { out.write(b, 0, n); require(out.size() <= max, file.getName() + " size"); } } finally { in.close(); }
        return out.toByteArray();
    }

    /** An X.509 SubjectPublicKeyInfo (DER, hex) as a key. */
    public static PublicKey key(String derHex) throws GeneralSecurityException {
        byte[] b = new byte[derHex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(derHex.substring(i * 2, i * 2 + 2), 16);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(b));
    }
}
