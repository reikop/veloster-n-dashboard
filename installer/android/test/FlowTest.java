// Adapted from sung9155/i30-pd-dashboard (i30pd_port/installer/android/test, MIT) for the Veloster N public repo.
package com.veloster.installer;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Runs Flow on the desktop against fake_adbd.py over TCP. args: PORT BUNDLE_DIR KEY_DIR install|uninstall|diag, or "unit".
 *  Exit 3 = Flow.Stop. */
public class FlowTest {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("unit")) { unit(); return; }
        int port = Integer.parseInt(a[0]);
        Flow.Ui ui = new Flow.Ui() {
            public void say(String m) { System.out.println(m); }
            public boolean ask(String q) { System.out.println("ASK " + q); return true; }
        };
        try {
            File diag = Flow.run(new File(a[1]), Flow.Mode.valueOf(a[3].toUpperCase()), new File(a[2]), u -> tcp(port), ui);
            if (diag != null) System.out.println("DIAG " + diag);
        } catch (Flow.Stop e) {
            System.out.println("STOP " + e.getMessage());
            System.exit(3);
        }
    }

    /** A new connection each time Flow looks for the car. */
    static Adb.Transport tcp(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        return new Adb.Transport() {
            public void write(byte[] b, int off, int len) throws IOException { s.getOutputStream().write(b, off, len); }
            public int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
                s.setSoTimeout(timeoutMs);
                try { int n = s.getInputStream().read(b, off, len); if (n < 0) throw new IOException("closed"); return n; }
                catch (SocketTimeoutException e) { return 0; }
            }
            public void close() throws IOException { s.close(); }
        };
    }

    /** The self-update decision, the release URL and the masking, without a car. */
    static void unit() {
        check(Flow.isNewer("1.11.1", "1.11.0") && Flow.isNewer("1.12.0", "1.11.9") && Flow.isNewer("1.11.0", "1.9.0"), "newer");
        check(!Flow.isNewer("1.11.0", "1.11.0") && !Flow.isNewer("1.9.0", "1.11.0"), "not newer");
        check(Flow.isNewer("1.11.0", "2026-11-24") && !Flow.isNewer("2026-11-25", "1.11.0"), "old date names are older");
        check(!Flow.isNewer(null, "1.11.0"), "offline");
        check(Flow.apkUrl().equals("https://github.com/reikop/veloster-n-dashboard/releases/latest/download/vn-installer-android.apk"), "apkUrl");
        check(Flow.PRODUCT.equals("jsn_kr") && Flow.VERIFIED_BUILD.equals("eng.vmadmin.20250902.081552"), "target");
        Flow.serial = "SER123";
        check(Flow.mask("a 00:1A:7D:DA:71:13 b 010-1234-5678 c kim@example.com d SER123").equals("a <MAC> b <전화번호> c <메일> d <기기번호>"), "mask");
        Flow.serial = "";
        System.out.println("PASS unit");
    }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
