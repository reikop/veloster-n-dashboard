"""USB 업데이트 end to end on the host: scripts/usb_update.py makes a bundle from two jarsigner-signed stand-in APKs and
a throwaway update key, and My Apps' own checks (UsbBundle) accept it and refuse a tampered copy. No Android."""
import importlib.util
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from test_quick_entry_runtime import java_tools

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''import com.veloster.carplayhub.usb.UsbBundle;
import java.io.File;
public class UsbHarness {
 public static void main(String[] a) throws Exception {
  File dir = new File(a[0]);
  UsbBundle b = UsbBundle.verify(UsbBundle.read(new File(dir, "manifest.txt"), 2048), UsbBundle.read(new File(dir, "manifest.sig"), 256), UsbBundle.key(a[1]));
  b.checkDevice("jsn_kr", 17);
  for (UsbBundle.App app : b.apps) b.checkApk(new File(dir, app.file), app);
  System.out.println("OK " + b.release + " " + b.apps.get(0).packageName + " " + b.apps.get(1).packageName);
 }
}'''


def run(*cmd, **kw):
    return subprocess.run([str(c) for c in cmd], capture_output=True, text=True, **kw)


class UsbUpdateTests(unittest.TestCase):
    def test_bundle_round_trip(self):
        java, javac = java_tools()
        bin_dir = Path(javac).parent
        if not shutil.which("openssl"):
            self.skipTest("openssl unavailable")
        spec = importlib.util.spec_from_file_location("usb_update", ROOT / "scripts/usb_update.py")
        usb = importlib.util.module_from_spec(spec); spec.loader.exec_module(usb)
        with tempfile.TemporaryDirectory(prefix="vn-usb-test-") as tmp:
            t = Path(tmp)
            # a throwaway update key and APK signer
            self.assertEqual(run("openssl", "genrsa", "-out", t / "update.pem", "2048").returncode, 0)
            der = run("openssl", "rsa", "-in", t / "update.pem", "-pubout", "-outform", "DER", "-out", t / "pub.der")
            self.assertEqual(der.returncode, 0, der.stderr)
            pub_hex = (t / "pub.der").read_bytes().hex()
            ks = t / "signer.p12"
            r = run(bin_dir / "keytool", "-genkeypair", "-keystore", ks, "-storetype", "PKCS12", "-storepass", "pass123", "-keypass", "pass123",
                    "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=test", "-validity", "2")
            self.assertEqual(r.returncode, 0, r.stderr)
            r = run(bin_dir / "keytool", "-exportcert", "-rfc", "-keystore", ks, "-storepass", "pass123", "-alias", "k", "-file", t / "cert.pem")
            self.assertEqual(r.returncode, 0, r.stderr)
            apks = []
            for name in ("dash", "hub"):
                apk = t / (name + ".apk")
                with zipfile.ZipFile(apk, "w") as z:
                    z.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00" + name.encode())
                    z.writestr("classes.dex", b"dex\n035\x00" + bytes(64))
                    z.writestr("res/raw/x.bin", bytes(1000))
                r = run(bin_dir / "jarsigner", "-keystore", ks, "-storepass", "pass123", "-digestalg", "SHA-256", "-sigalg", "SHA256withRSA", apk, "k")
                self.assertEqual(r.returncode, 0, r.stderr)
                apks.append(apk)
            out = t / "vn-usb-update.zip"
            release, text = usb.build(apks[0], apks[1], t / "update.pem", t / "cert.pem", out)
            with zipfile.ZipFile(out) as z:
                self.assertEqual(sorted(z.namelist()), ["vn-update/dashboard.apk", "vn-update/hub.apk", "vn-update/manifest.sig", "vn-update/manifest.txt"])
                z.extractall(t / "stick")
            self.assertIn(b"product=jsn_kr\nsdk=17\n", text)
            # My Apps' checks, compiled from the app source
            classes = t / "classes"
            (t / "UsbHarness.java").write_text(HARNESS)
            r = run(javac, "-encoding", "UTF-8", "-d", classes, ROOT / "apps/hub/src/com/veloster/carplayhub/usb/UsbBundle.java", t / "UsbHarness.java")
            self.assertEqual(r.returncode, 0, r.stderr)
            stick = t / "stick/vn-update"
            ok = run(java, "-cp", classes, "UsbHarness", stick, pub_hex)
            self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)
            self.assertIn("OK " + release + " com.veloster.dashboard com.veloster.carplayhub", ok.stdout)
            # a different APK under the same name is refused (size / SHA-256)
            data = bytearray((stick / "hub.apk").read_bytes()); data[-30] ^= 1; (stick / "hub.apk").write_bytes(bytes(data))
            bad = run(java, "-cp", classes, "UsbHarness", stick, pub_hex)
            self.assertNotEqual(bad.returncode, 0)
            self.assertIn("APK changed", bad.stdout + bad.stderr)
            # a manifest edited after signing is refused
            (stick / "hub.apk").write_bytes(bytes(data[:-30]) + bytes([data[-30] ^ 1]) + bytes(data[-29:]))
            (stick / "manifest.txt").write_bytes((stick / "manifest.txt").read_bytes().replace(b"sdk=17", b"sdk=18"))
            bad = run(java, "-cp", classes, "UsbHarness", stick, pub_hex)
            self.assertIn("signature", bad.stdout + bad.stderr)

    def test_key_in_the_app_is_the_update_key(self):
        source = (ROOT / "apps/hub/src/com/veloster/carplayhub/usb/UsbUpdateKey.java").read_text()
        import hashlib, re
        der = bytes.fromhex(re.search(r'DER = "([0-9a-f]+)"', source).group(1))
        self.assertEqual(hashlib.sha256(der).hexdigest(), "c84deb8eac276793293c5d897beb6b333ade6c8f7a7c6a0a269270466da95b39")


if __name__ == "__main__":
    unittest.main()
