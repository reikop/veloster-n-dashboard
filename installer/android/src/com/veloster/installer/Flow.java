// Adapted from sung9155/i30-pd-dashboard (i30pd_port/installer/android, MIT) for the Veloster N public repo.
package com.veloster.installer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The phone installer's steps, the same as installer/install.ps1 and installer/설치.command: check the bundled APKs
 * (assets/bundle: the two APKs + bundle.txt, put in by build.sh; only bytes whose SHA-256 bundle.txt names), find the car
 * (product jsn_kr only), check the build (API 17 + the verified build, otherwise stop), push + pm install (a signature
 * clash alone is fixed by uninstalling), open '내 앱'. Also removes both apps (any build), or only reads the car for a
 * diagnostics file. No Android imports: MainActivity supplies the USB link and the screen, the desktop test a TCP link.
 */
final class Flow {
    interface Ui {
        void say(String m);
        boolean ask(String q);
        /** One line that shows how long this wait has lasted; null ends it. */
        default void waiting(String label) {}
        default boolean cancelled() { return false; }
    }
    interface Car { Adb.Transport waitCar(Ui ui) throws Exception; }
    enum Mode { INSTALL, UNINSTALL, DIAG }

    /** A failure the user should read as is (no stack trace). */
    static final class Stop extends Exception { Stop(String m) { super(m); } }

    static final String VERSION = "1.11.3";   // the release version (MAJOR.MINOR.PATCH), shown on screen and compared with the published source; raise on every change
    static final String RELEASES = "https://github.com/reikop/veloster-n-dashboard/releases";
    static final String RAW = "https://raw.githubusercontent.com/reikop/veloster-n-dashboard/";
    static final String APK_NAME = "vn-installer-android.apk";
    /** The same target as the desktop installers: exactly this product, API 17 and this one build, or no install. */
    static final String PRODUCT = "jsn_kr", VERIFIED_BUILD = "eng.vmadmin.20250902.081552", SDK = "17";
    static final String[][] APPS = {
            {"N-Dashboard-API17.apk", "com.veloster.dashboard", "N 계기판"},
            {"My-Apps-API17.apk", "com.veloster.carplayhub", "'내 앱'"}};
    /** What '내 앱' sets persist.sys.key.custom to when ☆ opens the dashboard. Read only: no installer changes ☆. */
    static final String STAR = "com.veloster.carplayhub.STAR_KEY";
    /** Only these properties: a full getprop has the serial and Bluetooth addresses. */
    static final String[] PROPS = {"ro.product.name", "ro.product.device", "ro.build.product", "ro.build.version.incremental",
            "ro.build.fingerprint", "sys.usb.config", "sys.usb.state", "persist.sys.usb.config", "persist.sys.dipo.state", "persist.sys.key.custom"};
    /** Our apps' tags, crashes and installs only: the whole log has phone names, numbers and contacts from Bluetooth. */
    static final String LOG_TAGS = "VehicleDash:V VNCarPlayHub:V VNQuickEntry:V NDashboard:V AndroidRuntime:E PackageManager:I";
    /** The car's serial (ro.serialno), masked as <기기번호> like the desktop installers mask the adb serial. */
    static volatile String serial = "";

    /** bundle.txt (scripts/apk_info.py): file, package, versionCode, versionName, sha256 per line; '#' lines are comments. */
    static String[][] readBundle(File bundleDir) throws IOException {
        File f = new File(bundleDir, "bundle.txt");
        if (!f.isFile()) return null;
        String[][] rows = new String[APPS.length][];
        for (String l : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
            String[] c = l.trim().split("\t");
            if (l.startsWith("#") || c.length < 5) continue;
            for (int i = 0; i < APPS.length; i++) if (c[0].equals(APPS[i][0])) rows[i] = c;
        }
        return rows;
    }
    /** "N 계기판 1.24 · '내 앱' 1.10" from bundle.txt, or null. */
    static String bundled(File bundleDir) {
        try {
            String[][] rows = readBundle(bundleDir);
            if (rows == null) return null;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < APPS.length; i++)
                b.append(i == 0 ? "" : " · ").append(APPS[i][2]).append(' ').append(rows[i] == null ? "?" : rows[i][3]);
            return b.toString();
        } catch (IOException e) { return null; }
    }
    /** The two bundled APKs, each checked against bundle.txt (package, SHA-256) before anything reaches the car. */
    static File[] checkBundle(File bundleDir, Ui ui) throws Exception {
        String[][] rows = readBundle(bundleDir);
        if (rows == null) throw new Stop("이 설치 앱에 든 앱 목록(bundle.txt)이 없습니다. 휴대폰 설치 앱을 다시 받아 설치하세요.");
        File[] apk = new File[APPS.length];
        for (int i = 0; i < APPS.length; i++) {
            if (rows[i] == null || !rows[i][1].equals(APPS[i][1]))
                throw new Stop("bundle.txt에 " + APPS[i][0] + " 이(가) 없거나 패키지가 다릅니다. 휴대폰 설치 앱을 다시 받아 설치하세요.");
            apk[i] = new File(bundleDir, APPS[i][0]);
            if (!apk[i].isFile()) throw new Stop(APPS[i][0] + " 이(가) 이 설치 앱 안에 없습니다. 휴대폰 설치 앱을 다시 받아 설치하세요.");
            if (!sha256(apk[i]).equalsIgnoreCase(rows[i][4].trim()))
                throw new Stop(APPS[i][0] + " 이(가) 이 설치 앱에 든 파일과 다릅니다(SHA-256 불일치). 설치하지 않습니다.\n휴대폰 설치 앱을 다시 받아 설치하세요.");
        }
        ui.say("이 설치 앱에 든 버전: " + bundled(bundleDir));
        return apk;
    }
    static String sha256(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[16 * 1024]; for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n); }
        StringBuilder h = new StringBuilder();
        for (byte x : md.digest()) h.append(String.format("%02x", x));
        return h.toString();
    }

    /** Returns the diagnostics file in DIAG mode, otherwise null. bundleDir: the extracted assets/bundle. */
    static File run(File bundleDir, Mode mode, File dir, Car car, Ui ui) throws Exception {
        File[] apk = new File[APPS.length];
        if (mode == Mode.INSTALL) {
            ui.say("■ 앱 파일 확인");
            apk = checkBundle(bundleDir, ui);
        }

        ui.say("\n■ 차 화면에서 먼저 (직접)");
        ui.say("  (1) 설정 → SW 정보/업데이트 화면");
        ui.say("  (2) 아래 업데이트 버튼 줄의 왼쪽 숨은 영역 5회 → 오른쪽 숨은 영역 1회 (10초 안에, 엔지니어링 모드)");
        ui.say("  (3) 암호(2026년 0567) → Diagnosis → Factory Test 에서  0A 01 → 65 01 → 0A 02  를 한 줄씩 SEND");
        ui.say("      ('내 앱'이 이미 깔려 있으면 '내 앱' → USB 디버깅 → ON 으로 대신할 수 있습니다)");
        ui.say("  (4) 폰 — OTG 어댑터(USB-C→A 암) — A-A 케이블 — 차 USB 포트 연결");

        Adb adb = connect(car, dir, ui);
        try {
            String build = prop(adb, "ro.build.version.incremental"), sdk = prop(adb, "ro.build.version.sdk");
            String onCar = versions(adb);
            ui.say("차에 깔린 버전: " + onCar);
            if (mode == Mode.INSTALL) ui.say("설치할 버전: " + bundled(bundleDir));
            if (mode == Mode.DIAG) return diag(adb, dir, ui);
            // The desktop installers' rule: only the checked target (jsn_kr · API 17 · verified build) installs. Uninstall is never blocked.
            if (mode == Mode.INSTALL && (!build.equals(VERIFIED_BUILD) || !sdk.equals(SDK)))
                throw new Stop("확인된 내비 빌드가 아닙니다(API " + sdk + " / " + build + "). 이 설치기는 API 17 · " + VERIFIED_BUILD
                        + " 에서만 설치합니다.\n다른 빌드의 호환성은 확인되지 않았습니다(docs/INSTALL.md). 차에는 아무것도 하지 않았습니다.");

            if (mode == Mode.UNINSTALL) {
                ui.say("\n■ 앱 삭제");
                boolean star = prop(adb, "persist.sys.key.custom").equals(STAR);
                for (String[] app : APPS) {
                    String o = adb.shell("pm uninstall " + app[1], 60_000);
                    if (o.contains("Success")) { ui.say(app[2] + ": 삭제됨"); continue; }
                    if (adb.shell("pm path " + app[1], 30_000).contains("package:"))
                        throw new Stop(app[2] + " 삭제 실패 (앱이 아직 차에 있습니다):\n" + o.trim());
                    ui.say(app[2] + ": 설치돼 있지 않음");
                }
                ui.say("\n삭제 완료. 순정 상태로 돌아갔습니다(순정 시스템은 원래 건드리지 않습니다).");
                if (star) ui.say("☆ 버튼이 아직 계기판을 가리킵니다. ☆를 길게 눌러 나오는 순정 설정에서 원래 기능을 고르세요.");
            } else {
                ui.say("\n■ 앱 설치");
                for (int i = 0; i < APPS.length; i++) install(adb, APPS[i], apk[i], ui);
                ui.say("지금 차에 깔린 버전: " + versions(adb));
                adb.shell("am start -n com.veloster.carplayhub/.HubActivity", 30_000);
                ui.say("\n완료! 차 화면 '내 앱'에서 실행 바로가기를 켜면");
                ui.say("휴대폰 없이 HOME 가장자리 버튼으로 다시 열 수 있습니다.");
            }
            ui.say("USB 디버깅은 시동을 껐다 켜면 저절로 꺼집니다. 바로 끄려면 Factory Test에서  0A 01 → 65 00 → 0A 02");
            ui.say("(또는 '내 앱' → USB 디버깅 → OFF)");
        } finally {
            adb.close();
        }
        return null;
    }

    /** USB, adb auth, and jsn_kr only. The link is closed again when any of it fails. */
    private static Adb connect(Car car, File dir, Ui ui) throws Exception {
        Adb adb = new Adb(car.waitCar(ui));
        try {
            ui.say("\n■ 차와 adb 연결");
            ui.waiting("차와 연결 중");
            adb.connect(key(dir), 180_000, () -> {
                ui.say("→ 차 화면에 \"USB 디버깅 허용\" 창이 뜨면 [항상 허용]을 누르세요.");
                ui.waiting("차 화면의 허용을 기다리는 중 (최대 3분)");
            });
            ui.waiting(null);
            String product = prop(adb, "ro.product.name");   // exactly jsn_kr, not jsn_kr2
            if (!product.equals(PRODUCT)) throw new Stop("벨로스터 N 내비(" + PRODUCT + ")가 아닙니다: " + product + "\n이 기기에는 아무것도 하지 않았습니다.");
            serial = prop(adb, "ro.serialno");
            ui.say("차 연결됨: " + PRODUCT + " (" + prop(adb, "ro.product.device") + ") / API " + prop(adb, "ro.build.version.sdk")
                    + " / 빌드 " + prop(adb, "ro.build.version.incremental"));
            return adb;
        } catch (Exception e) {
            adb.close();
            throw e;
        }
    }

    private static void install(Adb adb, String[] app, File apk, Ui ui) throws Exception {
        // The car keeps the installed APK byte for byte, so the same bytes mean the same version: nothing to do.
        String path = adb.shell("pm path " + app[1], 30_000).trim();
        if (path.startsWith("package:") && path.endsWith(".apk")) {
            try {
                if (Arrays.equals(adb.pull(path.substring(8), 60_000), Files.readAllBytes(apk.toPath()))) {
                    ui.say(app[2] + ": 이미 같은 버전이 설치돼 있습니다 — 건너뜀");
                    return;
                }
            } catch (IOException e) { ui.say("  (설치된 파일과 비교하지 못해 그대로 설치합니다: " + e.getMessage() + ")"); }
        }
        String remote = "/data/local/tmp/" + app[0];
        ui.say(app[2] + " (" + apk.length() / 1024 + " KB)");
        ui.say("  ① 전송 (USB)");
        ui.waiting("① 전송 중 (최대 60초)");
        try { adb.push(apk, remote, 60_000); }
        catch (IOException e) { if (ui.cancelled()) throw e; throw new Stop(app[2] + " 전송 실패: " + e.getMessage() + "\n케이블·OTG 어댑터를 확인하고 Factory Test부터 다시 하세요."); }
        ui.say("  ② 설치 (내비)");
        ui.waiting("② 설치 중 (최대 2분)");
        String o = adb.shell("pm install -r " + remote, 120_000);
        ui.waiting(null);
        // Only a signature clash is fixed by uninstalling; any other failure must not remove the working app.
        if (o.contains("INCONSISTENT_CERTIFICATES") || o.contains("UPDATE_INCOMPATIBLE")) {
            ui.say(app[2] + ": 기존에 다른 서명으로 설치돼 있습니다.");
            if (ui.ask(app[2] + "이(가) 다른 서명으로 설치돼 있습니다. 지우고 다시 설치할까요? (앱 설정도 지워집니다)")) {
                String u = adb.shell("pm uninstall " + app[1], 60_000);
                if (!u.contains("Success")) throw new Stop(app[2] + " 기존 앱 삭제 실패:\n" + u.trim());
                o = adb.shell("pm install -r " + remote, 120_000);
            }
        }
        adb.shell("rm -f " + remote, 15_000);
        if (!o.contains("Success")) throw new Stop(app[2] + " 설치 실패:\n" + o.trim());
        ui.say(app[2] + " 설치 완료");
    }

    /** Reads only: fixed properties, our two packages' versions, our apps' log and their private error records. Masked, then saved
     *  under dir/diag as vn_진단기록_<stamp>.txt (the desktop installers' name). */
    private static File diag(Adb adb, File dir, Ui ui) throws Exception {
        ui.say("\n■ 진단 기록 모으기 (차에는 아무것도 바꾸지 않습니다)");
        StringBuilder b = new StringBuilder("벨로스터 N 계기판 진단 기록 — " + stamp("yyyy-MM-dd HH:mm") + " / 휴대폰 설치 앱 " + VERSION + "\n\n[내비]\n");
        for (String k : PROPS) b.append(k).append(": ").append(prop(adb, k)).append('\n');
        b.append("시각: ").append(adb.shell("date", 15_000).trim()).append("\n\n[앱]\n");
        for (String[] app : APPS) {
            String v = versionLines(adb.shell("dumpsys package " + app[1], 30_000));
            b.append(app[2]).append(" (").append(app[1]).append(")\n").append(v.isEmpty() ? "  설치돼 있지 않음\n" : v);
        }
        ui.waiting("기록 읽는 중");
        b.append("\n[기록: ").append(LOG_TAGS).append("]\n").append(tail(adb.shell("logcat -d -v time -s " + LOG_TAGS, 60_000), 3000));
        b.append(errorRecords(adb));
        ui.waiting(null);
        File out = new File(new File(dir, "diag"), "vn_진단기록_" + stamp("yyyyMMdd_HHmmss") + ".txt");
        if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs()) throw new IOException("저장 폴더를 만들지 못했습니다");
        Files.write(out.toPath(), mask(b.toString()).getBytes(StandardCharsets.UTF_8));
        ui.say("진단 기록을 만들었습니다 (" + Math.max(1, out.length() / 1024) + " KB).");
        return out;
    }

    /** Each app's own error record (com.veloster.diagnostics.ErrorLog: exception types and code places only, at most two 32 KB
     *  files in files/diagnostics). Both apps are debuggable, so run-as reads them without root; nothing is changed. */
    static String errorRecords(Adb adb) throws IOException {
        StringBuilder b = new StringBuilder("\n[앱 오류 기록]\n");
        for (String[] app : APPS)
            for (String f : new String[]{"errors.previous.log", "errors.log"}) {
                String o = adb.shell("run-as " + app[1] + " cat files/diagnostics/" + f, 15_000).replace("\r", "");
                b.append(app[2]).append(" ").append(f).append(": ");
                if (o.trim().isEmpty() || o.contains("No such file")) b.append("없음\n");
                else if (!o.startsWith("VN-ERROR")) b.append("읽지 못함 (").append(o.trim().split("\n")[0]).append(")\n");
                else b.append("\n").append(tail(o.trim(), 400));
            }
        return b.toString();
    }

    /** Both apps' versions on the car, one line. */
    static String versions(Adb adb) throws IOException {
        StringBuilder b = new StringBuilder();
        for (String[] app : APPS) {
            Matcher m = Pattern.compile("versionName=([!-~]+)").matcher(adb.shell("dumpsys package " + app[1], 30_000));
            b.append(b.length() == 0 ? "" : " · ").append(app[2]).append(' ').append(m.find() ? m.group(1) : "없음");
        }
        return b.toString();
    }

    static String versionLines(String dumpsys) {
        StringBuilder b = new StringBuilder();
        for (String l : dumpsys.split("\n"))
            if (l.matches(".*(versionCode|versionName|firstInstallTime|lastUpdateTime|codePath)=.*") && b.indexOf(l.trim()) < 0)
                b.append("  ").append(l.trim()).append('\n');
        return b.toString();
    }

    static String tail(String text, int lines) {
        String[] l = text.split("\n");
        return String.join("\n", Arrays.copyOfRange(l, Math.max(0, l.length - lines), l.length)) + "\n";
    }

    /** What could identify a person, as the desktop installers mask it: MAC addresses, Korean mobile numbers, mail addresses
     *  and the device serial. */
    static String mask(String t) {
        t = t.replaceAll("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b", "<MAC>")
                .replaceAll("\\b01[016789][-. ]?\\d{3,4}[-. ]?\\d{4}\\b", "<전화번호>")
                .replaceAll("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+", "<메일>");
        String s = serial;
        return s == null || s.trim().isEmpty() ? t : t.replace(s.trim(), "<기기번호>");
    }

    static String stamp(String f) { return new SimpleDateFormat(f, Locale.US).format(new Date()); }

    private static String prop(Adb adb, String k) throws IOException { return adb.shell("getprop " + k, 15_000).trim(); }

    static void download(String url, File dst, Ui ui) throws Exception {
        try {
            URLConnection c = new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", "vn-installer");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dst)) {
                byte[] b = new byte[16 * 1024];
                for (int n; (n = in.read(b)) > 0; ) { if (ui.cancelled()) throw new Stop("취소했습니다."); out.write(b, 0, n); }
            }
        } catch (IOException e) {
            throw new Stop("APK 다운로드 실패: " + dst.getName().replace(".part", "") + "\n인터넷 연결을 확인하세요.\n" + url + "\n" + e);
        }
        // An APK is a ZIP ('PK'); this rejects a saved 404 page.
        byte[] magic = new byte[2];
        try (InputStream in = new FileInputStream(dst)) { if (in.read(magic) != 2) magic[0] = 0; }
        if (magic[0] != 'P' || magic[1] != 'K') throw new Stop("받은 파일이 APK가 아닙니다: " + url);
        ui.say(url.substring(url.lastIndexOf('/') + 1) + ": 받음 (" + dst.length() / 1024 + " KB)");
    }

    /** The phone installer's VERSION on main (what the latest release's vn-installer-android.apk is built from), or null offline. */
    static String newerVersion() {
        try {
            URLConnection c = new URL(RAW + "main/installer/android/src/com/veloster/installer/Flow.java").openConnection();
            c.setConnectTimeout(5_000);
            c.setReadTimeout(5_000);
            c.setRequestProperty("User-Agent", "vn-installer");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { byte[] buf = new byte[8192]; for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n); }
            Matcher m = Pattern.compile("VERSION = \"([^\"]+)\"").matcher(o.toString("UTF-8"));
            return m.find() ? m.group(1) : null;
        } catch (IOException e) { return null; }   // offline: everything else still works, the APKs are bundled
    }
    /** True when the published version is newer than mine. */
    static boolean isNewer(String published, String mine) { return published != null && cmp(published, mine) > 0; }
    /** MAJOR.MINOR.PATCH compared as numbers (1.11.0 > 1.9.0); the old date names (2026-11-24) sort below every release. */
    static int cmp(String a, String b) { long x = ver(a), y = ver(b); return x < y ? -1 : x > y ? 1 : 0; }
    private static long ver(String v) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$").matcher(v.trim());
        return m.matches() ? Long.parseLong(m.group(1)) * 1_000_000L + Long.parseLong(m.group(2)) * 1_000L + Long.parseLong(m.group(3)) : -1;
    }
    /** The latest release's phone installer APK (the [업데이트] band downloads it; versionCode is checked before installing). */
    static String apkUrl() { return RELEASES + "/latest/download/" + APK_NAME; }

    /** This phone's adb key, kept so the car's "항상 허용" holds next time. */
    static KeyPair key(File dir) throws Exception {
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile())
            return new KeyPair(f.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    f.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        Files.write(priv.toPath(), k.getPrivate().getEncoded());
        Files.write(pub.toPath(), k.getPublic().getEncoded());
        return k;
    }
}
