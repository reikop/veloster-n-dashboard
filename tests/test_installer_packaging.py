"""Owner installer packaging: every release ZIP carries both app APKs with bundle.txt; no ADB or vehicle access."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import apk_info  # noqa: E402
import package_installer  # noqa: E402

RELEASE = json.loads((ROOT / "release.json").read_text(encoding="utf-8"))
DASH = ROOT / "apks" / "N-Dashboard-v6-API17.apk"
HUB = ROOT / "apks" / "My-Apps-v10-API17.apk"
ON_MAC = sys.platform == "darwin" and shutil.which("osacompile") and shutil.which("codesign")


def kit(directory, dash=DASH, hub=HUB):
    shutil.copyfile(dash, directory / "N-Dashboard-API17.apk")
    shutil.copyfile(hub, directory / "My-Apps-API17.apk")
    return directory


class ApkInfoTest(unittest.TestCase):
    def test_manifest_matches_release_json(self):
        for entry in RELEASE["apks"]:
            info = apk_info.manifest(ROOT / entry["file"])
            self.assertEqual(info["package"], entry["package"])
            self.assertEqual(info["versionCode"], str(entry["version_code"]))
            self.assertEqual(info["versionName"], entry["version_name"])

    def test_bundle_lines(self):
        with tempfile.TemporaryDirectory() as t:
            text = apk_info.bundle(kit(Path(t)))
        rows = [l.split("\t") for l in text.splitlines() if not l.startswith("#")]
        self.assertEqual([r[0] for r in rows], ["N-Dashboard-API17.apk", "My-Apps-API17.apk"])
        self.assertEqual(rows[0][1:4], ["com.veloster.dashboard", "6", "1.5-i18n"])
        self.assertEqual(rows[1][4], hashlib.sha256(HUB.read_bytes()).hexdigest())


class PackagingRefusesTest(unittest.TestCase):
    def test_refuses_without_apks(self):
        with tempfile.TemporaryDirectory() as t:
            empty, out = Path(t) / "apks", Path(t) / "out"
            empty.mkdir()
            with self.assertRaisesRegex(ValueError, "missing bundled APK"):
                package_installer.package(empty, out)
            self.assertFalse(out.exists() and any(out.iterdir()), "nothing may be written")

    def test_refuses_without_apks_argument(self):
        r = subprocess.run([sys.executable, str(ROOT / "scripts/package_installer.py"), "--out", "x"], capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("--apks", r.stderr)

    def test_refuses_wrong_package(self):
        with tempfile.TemporaryDirectory() as t:
            apks = kit(Path(t), dash=HUB)   # '내 앱' under the dashboard's name
            with self.assertRaisesRegex(ValueError, "expected com.veloster.dashboard"):
                package_installer.package(apks, Path(t) / "out")

    def test_refuses_non_apk(self):
        with tempfile.TemporaryDirectory() as t:
            apks = kit(Path(t))
            (apks / "My-Apps-API17.apk").write_bytes(b"<html>404</html>")
            with self.assertRaisesRegex(ValueError, "not an APK"):
                package_installer.package(apks, Path(t) / "out")


@unittest.skipUnless(ON_MAC, "the mac .app needs osacompile and codesign")
class PackagingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        t = Path(cls.tmp.name)
        (t / "apks").mkdir()
        cls.apks = kit(t / "apks")
        cls.out = t / "out"
        cls.assets = package_installer.package(cls.apks, cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_assets_and_sums(self):
        names = [a.name for a in self.assets]
        self.assertEqual(names, ["vn-installer-windows.zip", "vn-installer-mac.zip", "vn-installer-linux.zip", "SHA256SUMS.txt"])
        sums = (self.out / "SHA256SUMS.txt").read_text().splitlines()
        for line, asset in zip(sums, self.assets):
            self.assertEqual(line, hashlib.sha256(asset.read_bytes()).hexdigest() + "  " + asset.name)
        self.assertFalse(list(self.out.glob("*-API17.apk")), "raw APKs are not release assets")

    def test_every_zip_bundles_the_apks(self):
        bundle = apk_info.bundle(self.apks)
        for os_name in ("windows", "mac", "linux"):
            with zipfile.ZipFile(self.out / ("vn-installer-" + os_name + ".zip")) as z:
                self.assertEqual(z.read("bundle.txt").decode(), bundle, os_name)
                self.assertEqual(z.read("N-Dashboard-API17.apk"), DASH.read_bytes(), os_name)
                self.assertEqual(z.read("My-Apps-API17.apk"), HUB.read_bytes(), os_name)
        with zipfile.ZipFile(self.out / "vn-installer-windows.zip") as z:
            names = set(z.namelist())
            self.assertTrue({"N계기판설치.cmd", "gui.ps1", "install.ps1", "설치.cmd", "installer.ico"} <= names)
            self.assertEqual(z.read("installer.ico"), (ROOT / "installer/icon/installer.ico").read_bytes(), "the installers' one icon")
            self.assertTrue(z.read("gui.ps1").startswith(b"\xef\xbb\xbf") and b"\r\n" in z.read("gui.ps1"))
        with zipfile.ZipFile(self.out / "vn-installer-linux.zip") as z:
            self.assertEqual(z.getinfo("install.sh").external_attr >> 16 & 0o777, 0o755)

    def test_mac_zip_app(self):
        app = "N 계기판 설치.app/Contents/"
        with zipfile.ZipFile(self.out / "vn-installer-mac.zip") as z:
            names = set(z.namelist())
            for n in ("Info.plist", "MacOS/applet", "Resources/Scripts/main.scpt", "_CodeSignature/CodeResources",
                      "Resources/kit/설치.command", "Resources/kit/bundle.txt", "Resources/kit/N-Dashboard-API17.apk"):
                self.assertIn(app + n, names)
            self.assertEqual(z.getinfo(app + "MacOS/applet").external_attr >> 16 & 0o111, 0o111)
            self.assertIn("설치.command", names)
            self.assertEqual(z.read(app + "Resources/applet.icns"), (ROOT / "installer/icon/installer.icns").read_bytes(), "the installers' one icon")
            with tempfile.TemporaryDirectory() as t:
                z.extractall(t)
                for info in z.infolist():   # zipfile drops modes; put them back as unzip/Finder would
                    os.chmod(Path(t) / info.filename, info.external_attr >> 16 & 0o777 or 0o644)
                a = Path(t) / "N 계기판 설치.app"
                subprocess.run(["codesign", "--verify", "--deep", "--strict", str(a)], check=True)
                plist = subprocess.run(["plutil", "-extract", "LSMinimumSystemVersion", "raw", str(a / "Contents/Info.plist")],
                                       capture_output=True, text=True, check=True).stdout.strip()
                self.assertEqual(plist, "11.0")
                if os.environ.get("VN_SKIP_GUI_SMOKE") != "1":
                    self.smoke(a, Path(t) / "smoke.txt")

    def smoke(self, app, marker):
        """Opens the window and closes it again (VN_GUI_SMOKE): the window exists and shows the bundled versions."""
        p = subprocess.Popen([str(app / "Contents/MacOS/applet")], env=dict(os.environ, VN_GUI_SMOKE=str(marker)),
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            p.wait(timeout=40)
        except subprocess.TimeoutExpired:
            p.kill()
            self.fail("the window installer did not close in smoke mode")
        text = marker.read_text(encoding="utf-8")
        self.assertIn("벨로스터 N 계기판 설치", text)
        self.assertIn("이 설치기에 든 버전: N 계기판 1.5-i18n · '내 앱' 1.9-i18n", text)


if __name__ == "__main__":
    unittest.main()
