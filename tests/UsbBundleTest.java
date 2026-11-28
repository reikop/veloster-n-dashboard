import com.veloster.carplayhub.usb.UsbBundle;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;

/** USB 업데이트: only a manifest signed with the update key, in the exact format, for this head unit, is accepted. */
public class UsbBundleTest {
    static void check(boolean v, String message) { if (!v) throw new AssertionError(message); }
    static final String SIGNER = "ab".repeat(32), D = "cd".repeat(32), H = "ef".repeat(32);

    static byte[] sign(PrivateKey k, byte[] data) throws Exception { Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(k); s.update(data); return s.sign(); }
    static byte[] text(String... lines) { return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.US_ASCII); }
    static byte[] good(String... apps) {
        String[] head = {"VNUSB1", "release=1.15.0", "product=jsn_kr", "sdk=17", "signer=" + SIGNER};
        String[] all = new String[head.length + apps.length]; System.arraycopy(head, 0, all, 0, head.length); System.arraycopy(apps, 0, all, head.length, apps.length);
        return text(all);
    }
    static String rejected(KeyPair kp, byte[] data) {
        try { UsbBundle.verify(data, sign(kp.getPrivate(), data), kp.getPublic()); return null; } catch (Exception e) { return e.getMessage(); }
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        KeyPair kp = g.generateKeyPair(), other = g.generateKeyPair();
        String dash = "app=com.veloster.dashboard,35,20000000," + D + ",dashboard.apk", hub = "app=com.veloster.carplayhub,21,90000," + H + ",hub.apk";
        byte[] m = good(hub, dash);
        UsbBundle b = UsbBundle.verify(m, sign(kp.getPrivate(), m), kp.getPublic());
        check(b.release.equals("1.15.0") && b.signer.equals(SIGNER) && b.apps.size() == 2, "a good manifest reads");
        check(b.apps.get(0).packageName.equals(UsbBundle.DASHBOARD) && b.apps.get(1).packageName.equals(UsbBundle.HUB), "the dashboard installs first, My Apps (the installer itself) last");
        check(b.apps.get(0).version == 35 && b.apps.get(0).bytes == 20000000 && b.apps.get(0).file.equals("dashboard.apk"), "app fields");
        b.checkDevice("jsn_kr", 17);
        try { b.checkDevice("jsn_us", 17); check(false, "another head unit refused"); } catch (java.io.IOException expected) { }
        try { b.checkDevice("jsn_kr", 19); check(false, "another API refused"); } catch (java.io.IOException expected) { }
        check(UsbBundle.newer(b.apps.get(0), 34) && !UsbBundle.newer(b.apps.get(0), 35) && !UsbBundle.newer(b.apps.get(0), 36) && !UsbBundle.newer(b.apps.get(0), 0), "only a newer version of an installed app");
        // the signature
        try { UsbBundle.verify(m, sign(other.getPrivate(), m), kp.getPublic()); check(false, "another key refused"); } catch (java.io.IOException e) { check(e.getMessage().contains("signature"), e.getMessage()); }
        byte[] changed = m.clone(); changed[20] ^= 1;
        try { UsbBundle.verify(changed, sign(kp.getPrivate(), m), kp.getPublic()); check(false, "a changed manifest refused"); } catch (java.io.IOException expected) { }
        try { UsbBundle.verify(m, new byte[255], kp.getPublic()); check(false, "short signature refused"); } catch (java.io.IOException expected) { }
        // the format (signed, but wrong)
        check(rejected(kp, good(dash)) == null, "one app alone is fine");
        check(rejected(kp, good()) != null, "no app refused");
        check(rejected(kp, good(dash, dash)) != null, "the same app twice refused");
        check(rejected(kp, good("app=com.evil.app,1,10," + D + ",x.apk")) != null, "another package refused");
        check(rejected(kp, good("app=com.veloster.dashboard,35,10," + D + ",../x.apk")) != null, "a path in the file name refused");
        check(rejected(kp, good("app=com.veloster.dashboard,35,99999999," + D + ",d.apk")) != null, "an oversized APK refused");
        check(rejected(kp, good("app=com.veloster.dashboard,035,10," + D + ",d.apk")) != null, "a padded number refused");
        check(rejected(kp, text("VNUSB2", "release=1.15.0", "product=jsn_kr", "sdk=17", "signer=" + SIGNER, dash)) != null, "another format version refused");
        check(rejected(kp, text("VNUSB1", "product=jsn_kr", "release=1.15.0", "sdk=17", "signer=" + SIGNER, dash)) != null, "fields out of order refused");
        byte[] crlf = new String(good(dash), StandardCharsets.US_ASCII).replace("\n", "\r\n").getBytes(StandardCharsets.US_ASCII);
        check(rejected(kp, crlf) != null, "CR LF refused");
        System.out.println("PASS: USB update manifest - signature, format, order, device and version checks");
    }
}
