// Adapted from sung9155/i30-pd-dashboard (i30pd_port/installer/android, MIT) for the Veloster N public repo.
package com.veloster.installer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** One screen, dark like the dashboard: header (icon, version), a band when this app has a newer version ([업데이트]),
 *  the versions this installer carries (assets/bundle), 설치 / 삭제 / 취소 / 진단 기록 / 공유, a wait line with seconds,
 *  and the running log. The steps live in Flow. */
public class MainActivity extends Activity implements Flow.Ui, Flow.Car {
    // the dashboard's dark palette (installer spec 2026-11-20)
    static final int BG = 0xff101315, CARD = 0xff1c303b, CARD_OFF = 0xff161b1e, ACCENT = 0xffa4d9ef, LINE = 0xff233b46,
            GO = 0xff3ddc84, GO_TEXT = 0xff06140c, BAND = 0xff3a3226, AMBER = 0xffffb84d, LOG_BG = 0xff040506, DIM = 0xff8fa0a8,
            TEXT = 0xffe8eef0;
    private TextView log;
    private ScrollView scroll;
    private TextView installBtn, removeBtn, diagBtn, cancelBtn, updateBtn;
    private TextView waitLine;
    private String waitLabel;   // UI thread only
    private long waitStart;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (waitLabel == null) { waitLine.setVisibility(View.GONE); return; }
            waitLine.setText("⏳ " + waitLabel + "  " + (android.os.SystemClock.elapsedRealtime() - waitStart) / 1000 + "초");
            waitLine.postDelayed(this, 1000);
        }
    };
    private LinearLayout band;
    private TextView bandText;
    private volatile boolean busy;
    private float dp;

    private int dp(float v) { return Math.round(v * dp); }
    /** A rounded box; stroke 0 for none. Pressed: a little lighter. */
    private android.graphics.drawable.Drawable box(int fill, int stroke, float radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(blend(fill, 0xffffffff, 0.12f), stroke, radius));
        s.addState(new int[0], shape(fill, stroke, radius));
        return s;
    }
    private GradientDrawable shape(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }
    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
                bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
    private TextView text(String t, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }
    private static LinearLayout.LayoutParams lp(int w, int h, float weight, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.topMargin = top;
        return p;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        head.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        names.addView(text("벨로스터 N 계기판 설치", 19, TEXT, true));
        names.addView(text("휴대폰 · " + Flow.VERSION, 12, DIM, false));
        head.addView(names);
        root.addView(head);

        // a newer phone installer: one band with [업데이트] (download, check, hand to the system installer)
        band = new LinearLayout(this);
        band.setGravity(Gravity.CENTER_VERTICAL);
        band.setBackground(shape(BAND, 0, 12));
        band.setPadding(dp(12), dp(8), dp(8), dp(8));
        bandText = text("", 13, AMBER, true);
        band.addView(bandText, new LinearLayout.LayoutParams(0, -2, 1));
        updateBtn = button(band, "업데이트", AMBER, BG, 0, v -> update());
        updateBtn.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(36)));
        updateBtn.setPadding(dp(14), 0, dp(14), 0);
        updateBtn.setTypeface(Typeface.DEFAULT_BOLD);
        band.setVisibility(View.GONE);
        root.addView(band, lp(-1, -2, 0, dp(12)));

        TextView warn = text("현대자동차 공식 앱이 아닙니다. 보증/AS 불이익이 있을 수 있고 책임은 본인에게 있습니다. 정차 · 시동 켠 상태에서 하세요.\n"
                + "준비물: OTG 어댑터(USB-C → USB-A 암) + A-A 케이블. 폰 설정에 OTG 켜기가 있으면 켜 두세요.", 12, DIM, false);
        root.addView(warn, lp(-1, -2, 0, dp(12)));

        // what this installer carries: the two app APKs are bundled (assets/bundle), nothing is downloaded for them
        String carried;
        try { extractBundle(); carried = Flow.bundled(bundleDir()); } catch (IOException e) { carried = null; }
        TextView version = text("이 설치기에 든 버전: " + (carried == null ? "앱 파일을 읽지 못했습니다. 이 앱을 다시 받아 설치하세요." : carried), 15, ACCENT, true);
        version.setBackground(shape(CARD, 0, 14));
        version.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(version, lp(-1, -2, 0, dp(12)));

        LinearLayout go = new LinearLayout(this);
        installBtn = button(go, "설치", GO, GO_TEXT, 0, v -> start(Flow.Mode.INSTALL));
        installBtn.setTextSize(17);
        installBtn.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(go, lp(-1, dp(52), 0, dp(4)));
        LinearLayout buttons = new LinearLayout(this);
        // one tap next to [취소] used to wipe both apps: asked first
        removeBtn = button(buttons, "삭제", BG, TEXT, LINE, v -> new android.app.AlertDialog.Builder(this)
                .setMessage("차에서 N 계기판과 '내 앱', 그 설정을 지웁니다.\n\n'내 앱'에서 ☆ 버튼을 계기판으로 써 왔다면 지우기 전에 '내 앱'에서 ☆를 끄세요. "
                        + "이미 지웠다면 ☆를 길게 눌러 나오는 순정 설정에서 원래 기능을 고르면 됩니다.")
                .setPositiveButton("삭제", (d, w) -> start(Flow.Mode.UNINSTALL)).setNegativeButton("취소", null).show());
        cancelBtn = button(buttons, "취소", BG, TEXT, LINE, v -> { cancel.set(true); on(cancelBtn, false); say("\n취소하는 중..."); });
        root.addView(buttons, lp(-1, dp(42), 0, dp(8)));
        LinearLayout report = new LinearLayout(this);
        diagBtn = button(report, "진단 기록", BG, TEXT, LINE, v -> start(Flow.Mode.DIAG));
        button(report, "공유", BG, TEXT, LINE, v -> new Thread(() -> {
            try { share(save(new File(dirDiag(), LOG_PREFIX + Flow.stamp("yyyyMMdd_HHmmss") + ".txt"), "")); } catch (IOException e) { say("기록 저장 실패: " + e.getMessage()); }
        }).start());
        root.addView(report, lp(-1, dp(42), 0, dp(6)));
        waitLine = text("", 13, AMBER, true);
        waitLine.setVisibility(View.GONE);
        root.addView(waitLine, lp(-1, -2, 0, dp(8)));

        log = text("", 12, DIM, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(dp(12), dp(10), dp(12), dp(10));
        scroll = new ScrollView(this);
        scroll.setBackground(shape(LOG_BG, 0, 12));
        scroll.addView(log);
        root.addView(scroll, lp(-1, 0, 1, dp(10)));
        setContentView(root);
        enable(true);
        refresh();
        say("[진단 기록]은 차를 연결해 상태와 앱 기록만 읽어 공유할 파일을 만듭니다. 차에는 아무것도 바꾸지 않습니다.");
    }

    private long fetchedAt;
    private String updVer;   // UI thread only: the newer phone installer to update to, or null
    /** Whether this app has a newer version (the version on main = what the latest release's vn-installer-android.apk is built
     *  from). One small request; offline nothing shows and everything else works. */
    private void refresh() {
        new Thread(() -> {
            String v = Flow.newerVersion();
            runOnUiThread(() -> {
                fetchedAt = android.os.SystemClock.elapsedRealtime();
                if (Flow.isNewer(v, Flow.VERSION)) {
                    updVer = v;
                    bandText.setText("새 설치 앱 " + v);
                    band.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }
    /** Back in the app after a while (the phone was put away between car visits): look again without being asked. */
    @Override protected void onResume() {
        super.onResume();
        if (fetchedAt > 0 && android.os.SystemClock.elapsedRealtime() - fetchedAt > 5 * 60_000) refresh();
    }

    /** [업데이트]: downloads the newer phone installer, checks it really is newer and ours, and opens the system installer on it.
     *  The phone asks once to allow installs from this app; the last 설치 tap is always the user's. */
    private void update() {
        if (busy || updVer == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            say("\n먼저 이 앱에서 설치를 허용해 주세요. 열리는 설정 화면에서 [이 출처 허용]을 켜고 돌아와 [업데이트]를 다시 누르세요.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        String ver = updVer;
        enable(false);
        cancel.set(false);
        log.setText("");
        new Thread(() -> {
            try {
                say("■ 설치 앱 업데이트: " + ver);
                File dir = new File(getFilesDir(), "update");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("저장 폴더를 만들지 못했습니다");
                File apk = new File(dir, Flow.APK_NAME), part = new File(dir, apk.getName() + ".part");
                Flow.download(Flow.apkUrl(), part, this);
                Files.move(part.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                PackageManager pm = getPackageManager();
                PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), 0);
                long mine = pm.getPackageInfo(getPackageName(), 0).getLongVersionCode();
                if (got == null || !getPackageName().equals(got.packageName)) throw new Flow.Stop("받은 파일이 이 설치 앱이 아닙니다.");
                // the source says newer, but the release may still carry the old APK for a few minutes
                if (got.getLongVersionCode() <= mine) throw new Flow.Stop("아직 새 APK가 올라오지 않았습니다 (받은 것 " + got.versionName + "). 잠시 뒤 다시 눌러 보세요.");
                say(got.versionName + " 확인. 뜨는 설치 화면에서 [업데이트](또는 [설치])를 누르세요. 끝나면 앱을 다시 여세요.");
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(DiagProvider.uri(apk), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(i));
            } catch (Flow.Stop e) {
                say("\n" + e.getMessage());
            } catch (Exception e) {
                say("\n업데이트 오류: " + e + "\n" + Flow.RELEASES + " 에서 " + Flow.APK_NAME + "를 직접 받을 수도 있습니다.");
            }
            runOnUiThread(() -> enable(true));
        }).start();
    }

    /** A flat rounded button (stroke 0: filled), sharing the row equally. */
    private TextView button(LinearLayout row, String label, int fill, int color, int stroke, View.OnClickListener l) {
        TextView b = text(label, 14, color, false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(fill, stroke, 12));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) p.leftMargin = dp(6);
        row.addView(b, p);
        return b;
    }
    private static void on(View v, boolean e) { v.setEnabled(e); v.setAlpha(e ? 1f : 0.4f); }

    private void start(Flow.Mode mode) {
        enable(false);
        cancel.set(false);
        log.setText("");
        new Thread(() -> {
            try {
                if (mode == Flow.Mode.INSTALL) extractBundle();
                File diag = Flow.run(bundleDir(), mode, getFilesDir(), this, this);
                if (diag != null) share(diag);
            } catch (Exception e) {
                if (cancel.get()) say("\n취소했습니다. 설치 중이었다면 차에서는 그 단계가 끝날 수 있습니다. 다시 실행하면 이어서 맞춥니다.");
                else if (e instanceof Flow.Stop) say("\n중단: " + e.getMessage() + "\n[공유]로 이 화면의 기록을 보낼 수 있습니다.");
                else say("\n오류: " + e + "\n[공유]로 이 화면의 기록을 보내 알려 주세요.");
            }
            waiting(null);
            runOnUiThread(() -> enable(true));
        }).start();
    }

    /** While a run is under way the back key only puts the app behind: finishing the screen left the run going with
     *  nothing to show it (an empty screen on return, and its next question had no window to open in). */
    @Override public void onBackPressed() { if (busy) moveTaskToBack(true); else super.onBackPressed(); }

    /** on: idle (everything but 취소); off: a run is under way (only 취소). */
    private void enable(boolean on) {
        busy = !on;
        for (View v : new View[]{installBtn, removeBtn, diagBtn, updateBtn}) on(v, on);
        on(cancelBtn, !on);
    }

    private File bundleDir() { return new File(getFilesDir(), "bundle"); }
    /** build.sh puts the two app APKs and bundle.txt into the APK at assets/bundle/; Flow checks them against bundle.txt
     *  (package, SHA-256) before pushing. Copied fresh each time, so an updated installer never installs stale files. */
    private void extractBundle() throws IOException {
        File dir = bundleDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("저장 폴더를 만들지 못했습니다");
        for (String name : new String[]{"bundle.txt", Flow.APPS[0][0], Flow.APPS[1][0]}) {
            try (java.io.InputStream in = getAssets().open("bundle/" + name)) {
                Files.copy(in, new File(dir, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Override public boolean cancelled() { return cancel.get(); }

    @Override public void waiting(String label) {
        runOnUiThread(() -> {
            waitLine.removeCallbacks(tick);
            waitLabel = label;
            waitStart = android.os.SystemClock.elapsedRealtime();
            if (label != null) waitLine.setVisibility(android.view.View.VISIBLE);
            tick.run();
        });
    }

    static final String LOG_PREFIX = "vn_설치기록_", DIAG_PREFIX = "vn_진단기록_";
    private File dirDiag() throws IOException {
        File dir = new File(getFilesDir(), "diag");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("저장 폴더를 만들지 못했습니다");
        return dir;
    }

    /** Writes the given head (a diagnostics file's text, or nothing) and the on-screen log, masked, to f. */
    private File save(File f, String head) throws IOException {
        CountDownLatch read = new CountDownLatch(1);
        String[] screen = new String[1];
        runOnUiThread(() -> { screen[0] = log.getText().toString(); read.countDown(); });
        try { read.await(); } catch (InterruptedException e) { throw new IOException(e); }
        Files.write(f.toPath(), (head + "\n[이 폰 화면 기록]\n" + Flow.mask(screen[0])).getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** The system share sheet with the file attached; the user picks the app and the person. */
    private void share(File f) throws IOException {
        // a diagnostics file (vn_진단기록_<stamp>.txt, already masked): add this run's screen to it under the same name
        if (f.getName().startsWith(DIAG_PREFIX)) save(f, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        android.net.Uri uri = DiagProvider.uri(f);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "벨로스터 N 계기판 기록")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("", uri));   // the chooser passes the read grant on only through ClipData
        say("\n공유 화면에서 보낼 앱을 고르세요. 증상(언제, 어떤 화면에서, 무엇이)도 같이 알려 주세요.");
        runOnUiThread(() -> startActivity(Intent.createChooser(send, "공유").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
    }

    @Override public void say(String m) {
        runOnUiThread(() -> {
            log.append(m + "\n");
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override public boolean ask(String q) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean yes = new AtomicBoolean();
        runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(q).setCancelable(false)
                .setPositiveButton("예", (d, w) -> { yes.set(true); done.countDown(); })
                .setNegativeButton("아니오", (d, w) -> done.countDown()).show());
        try { done.await(); } catch (InterruptedException e) { return false; }
        say(q + " → " + (yes.get() ? "예" : "아니오"));
        return yes.get();
    }

    /** Waits up to 3 minutes for an adb USB interface (class ff/42/01), asks for USB permission, and opens it. */
    @Override public Adb.Transport waitCar(Flow.Ui ui) throws Exception {
        ui.say("\n■ 차 연결");
        ui.waiting("차 연결을 기다리는 중 (최대 3분)");
        try { return findCar(ui); } finally { ui.waiting(null); }
    }

    private Adb.Transport findCar(Flow.Ui ui) throws Exception {
        UsbManager usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        boolean asked = false;
        for (int s = 0; s < 180; s++) {
            if (cancel.get()) throw new Flow.Stop("취소했습니다.");
            for (UsbDevice d : usb.getDeviceList().values()) {
                UsbInterface intf = adbInterface(d);
                if (intf == null) continue;
                if (!usb.hasPermission(d)) {
                    if (!asked) {
                        ui.say("→ 폰 화면에 USB 접근 허용 창이 뜨면 [확인]을 누르세요.");
                        Intent i = new Intent("com.veloster.installer.USB").setPackage(getPackageName());
                        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0));   // FLAG_MUTABLE: the system adds the result
                        asked = true;
                    }
                    continue;
                }
                UsbDeviceConnection c = usb.openDevice(d);
                if (c == null || !c.claimInterface(intf, true)) throw new Flow.Stop("USB 장치를 열지 못했습니다. 케이블을 다시 꽂고 다시 실행하세요.");
                ui.say(String.format("USB 장치 찾음: %04x:%04x", d.getVendorId(), d.getProductId()));
                return new UsbLink(c, intf, cancel);
            }
            if (s == 20) ui.say("→ 아직 안 보입니다. Factory Test(0A 01/65 01/0A 02), OTG 어댑터 방향, 케이블을 확인하세요.");
            Thread.sleep(1000);
        }
        throw new Flow.Stop("3분 안에 차를 찾지 못했습니다.\n"
                + "· Factory Test 코드를 띄어쓰기까지 정확히 다시 보내고 케이블을 다시 꽂으세요.\n"
                + "· 폰 쪽은 OTG 어댑터(USB-C 수 → USB-A 암)여야 합니다. 일반 충전 케이블로는 폰이 호스트가 되지 않습니다.\n"
                + "· 폰 설정에 OTG 켜기가 있으면 켜세요.");
    }

    private static UsbInterface adbInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 0xff && f.getInterfaceSubclass() == 0x42 && f.getInterfaceProtocol() == 1) return f;
        }
        return null;
    }

    /** Bulk endpoints of the adb interface. bulkTransfer with an offset is API 18, so this copies through a buffer. */
    private static final class UsbLink implements Adb.Transport {
        private final UsbDeviceConnection c;
        private final UsbInterface intf;
        private UsbEndpoint in, out;
        private final byte[] buf = new byte[16 * 1024];
        private final AtomicBoolean cancel;   // [취소]: the next read or write fails, which ends any adb wait

        UsbLink(UsbDeviceConnection c, UsbInterface intf, AtomicBoolean cancel) {
            this.c = c;
            this.intf = intf;
            this.cancel = cancel;
            for (int i = 0; i < intf.getEndpointCount(); i++) {
                UsbEndpoint e = intf.getEndpoint(i);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e; else out = e;
            }
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (cancel.get()) throw new IOException("취소했습니다");
                int n = Math.min(len, buf.length);
                System.arraycopy(b, off, buf, 0, n);
                int w = c.bulkTransfer(out, buf, n, 5000);
                if (w <= 0) throw new IOException("USB 쓰기 실패 (연결이 끊겼을 수 있습니다)");
                off += w;
                len -= w;
            }
        }

        @Override public int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
            if (cancel.get()) throw new IOException("취소했습니다");
            int n = c.bulkTransfer(in, buf, Math.min(len, buf.length), Math.min(timeoutMs, 1000));
            if (n <= 0) return 0;
            System.arraycopy(buf, 0, b, off, n);
            return n;
        }

        @Override public void close() { c.releaseInterface(intf); c.close(); }
    }
}
