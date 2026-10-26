"""Failure-path tests for menu deployment. Synthetic APKs and fake ADB only."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "menu_entry.py"
spec = importlib.util.spec_from_file_location("menu_entry", SCRIPT)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def apk(label):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("AndroidManifest.xml", "manifest:" + label)
        archive.writestr("classes.dex", b"dex\n035\0" + label.encode())
        archive.writestr("resources.arsc", label.encode())
        archive.writestr("META-INF/CERT.RSA", b"test-only")
        archive.writestr("META-INF/CERT.SF", b"test-only")
    return output.getvalue()


class FakeAdb:
    serial = "TEST-SERIAL"

    def __init__(self, profile, files, packages, artifacts):
        self.props = {"ro.product.name": profile["product"], "ro.build.version.incremental": profile["incremental"],
                      "ro.build.version.sdk": profile["sdk"], "ro.kernel.qemu": ""}
        self.files = dict(files)
        self.packages = dict(packages)
        self.artifacts = artifacts
        self.calls = []
        self.pm_result = "Success\n"
        self.fail_install_number = 0
        self.install_count = 0
        self.readback_bad = False
        self.upload_bad = False
        self.push_error = False
        self.df = "Filesystem Size Used Free Blksize\n/data 1G 200M 800M 4096\n"

    def run(self, *parts, **kwargs):
        self.calls.append(tuple(parts))
        if parts == ("get-state",):
            return "device\n"
        raise AssertionError(parts)

    def shell(self, *parts, **kwargs):
        self.calls.append(tuple(parts))
        if parts[0] == "getprop":
            return self.props.get(parts[1], "") + "\n"
        if parts[:2] == ("pm", "path"):
            return "package:" + self.packages[parts[2]] + "\n" if parts[2] in self.packages else ""
        if parts[:3] == ("pm", "install", "-r"):
            self.install_count += 1
            if self.fail_install_number == self.install_count:
                raise m.Stopped("ADB disconnected during install")
            if self.pm_result != "Success\n":
                return self.pm_result
            data = self.files[parts[3]]
            package = self.artifacts[digest(data)]
            remote = "/data/app/" + package + "-1.apk"
            self.packages[package] = remote
            self.files[remote] = b"corrupt readback" if self.readback_bad else data
            return self.pm_result
        if parts[:2] == ("df", "/data"):
            return self.df
        if parts[:2] == ("rm", "-f"):
            self.files.pop(parts[2], None)
            return ""
        raise AssertionError(parts)

    def pull(self, remote, local):
        self.calls.append(("pull", remote, str(local)))
        require_absent = Path(local)
        if require_absent.exists():
            raise AssertionError("backup overwritten")
        require_absent.parent.mkdir(parents=True, exist_ok=True)
        require_absent.write_bytes(self.files[remote])

    def push(self, local, remote):
        self.calls.append(("push", str(local), remote))
        if self.push_error:
            raise m.Stopped("push failed")
        data = Path(local).read_bytes()
        self.files[remote] = b"incomplete upload" if self.upload_bad else data


class DeployTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.profile = {"product": "jsn_kr", "incremental": "supported-build", "sdk": "17", "menus": {},
                        "framework_sha256": {"core.jar": digest(b"core")}}
        self.bundle = {}
        files = {"/system/framework/core.jar": b"core"}
        packages, artifacts = {}, {}
        self.bundle_dir = self.root / "bundle"
        self.bundle_dir.mkdir()
        bundle_receipt = {"status": "offline_checks_passed", "menus": {}}
        for name, factory in (("downloadmenu", "HKMC_DownloadMenu"), ("allmenu", "HKMC_AllMenuKR")):
            package = "com.hkmc.system.app." + name
            stock = apk(name + "-stock")
            odex = (name + "-odex").encode()
            self.profile["menus"][name] = {"package": package, "file": factory, "apk_sha256": digest(stock),
                                            "odex_sha256": digest(odex), "expected_payload_sha256": {}}
            files["/system/app/" + factory + ".apk"] = stock
            files["/system/app/" + factory + ".odex"] = odex
            packages[package] = "/system/app/" + factory + ".apk"
            self.bundle[name] = {}
            bundle_receipt["menus"][name] = {"base_apk_sha256": digest(stock), "base_odex_sha256": digest(odex)}
            for kind in ("candidate", "fallback"):
                path = self.bundle_dir / (name + "-" + kind + ".apk")
                path.write_bytes(apk(name + "-" + kind))
                self.bundle[name][kind] = {"path": path, "sha256": m.sha(path)}
                self.profile["menus"][name]["expected_payload_sha256"][kind] = m.payload_sha256(path)
                bundle_receipt["menus"][name][kind] = {"file": path.name, "sha256": m.sha(path)}
                artifacts[m.sha(path)] = package
        self.apps = {}
        for package in m.COMPANIONS:
            path = self.root / (package + ".apk")
            path.write_bytes(apk(package))
            self.apps[package] = {"path": path, "sha256": m.sha(path), "accepted": {m.sha(path)}}
            remote = "/data/app/" + package + "-1.apk"
            packages[package] = remote
            files[remote] = path.read_bytes()
            artifacts[m.sha(path)] = package
        self.profile_path = self.root / "profile.json"
        self.profile_path.write_text(json.dumps(self.profile))
        bundle_receipt["profile_sha256"] = m.sha(self.profile_path)
        (self.bundle_dir / "receipt.json").write_text(json.dumps(bundle_receipt))
        self.adb = FakeAdb(self.profile, files, packages, artifacts)
        self.backup = self.root / "backup"

    def tearDown(self):
        self.temp.cleanup()

    def deploy(self, action="install", check=False):
        return m.change(self.adb, self.profile, action, self.backup, self.bundle, self.apps, check)

    def no_mutations(self):
        self.assertFalse(any(c[0] in ("push", "rm") or c[:2] == ("pm", "install") for c in self.adb.calls))

    def test_wrong_build_stops_before_backup_or_mutation(self):
        self.adb.props["ro.build.version.incremental"] = "other-build"
        with self.assertRaisesRegex(m.Stopped, "Unsupported target"):
            self.deploy()
        self.no_mutations()
        self.assertFalse(any(c[0] == "pull" for c in self.adb.calls))

    def test_factory_mismatch_stops_before_mutation(self):
        self.adb.files["/system/app/HKMC_AllMenuKR.odex"] = b"different"
        with self.assertRaisesRegex(m.Stopped, "Factory odex mismatch"):
            self.deploy()
        self.no_mutations()

    def test_unknown_active_menu_is_backed_up_and_not_replaced(self):
        package = self.profile["menus"]["allmenu"]["package"]
        self.adb.packages[package] = "/data/app/unknown.apk"
        self.adb.files["/data/app/unknown.apk"] = b"unknown update"
        with self.assertRaisesRegex(m.Stopped, "Unknown active menu"):
            self.deploy()
        self.assertEqual((self.backup / "menus/allmenu/active.apk").read_bytes(), b"unknown update")
        self.no_mutations()

    def test_absent_companion_stops_menu_update(self):
        del self.adb.packages[m.COMPANIONS[1]]
        with self.assertRaises(m.Stopped):
            self.deploy()
        self.no_mutations()

    def test_readonly_check_backs_up_without_install(self):
        journal = self.deploy(check=True)
        self.assertEqual(journal.data["status"], "checked_without_changes")
        self.assertTrue((self.backup / "menus/allmenu/system.odex").is_file())
        self.no_mutations()

    def test_install_order_and_readback(self):
        journal = self.deploy()
        self.assertEqual(journal.data["status"], "verified")
        verified = [s["package"] for s in journal.data["steps"] if s["stage"] == "verified"]
        self.assertEqual(verified, [self.profile["menus"][n]["package"] for n in m.MENU_ORDER])
        self.assertFalse(any(k.startswith("/data/local/tmp/") for k in self.adb.files))

    def test_pm_failure_with_zero_adb_exit_does_not_claim_success(self):
        self.adb.pm_result = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n"
        with self.assertRaisesRegex(m.Stopped, "unambiguous Success"):
            self.deploy()
        journal = m.read_json(self.backup / "receipt.json")
        self.assertEqual(journal["status"], "stopped")
        self.assertFalse(any(s["stage"] == "verified" for s in journal["steps"]))
        self.assertEqual(self.adb.install_count, 1)

    def test_interrupted_second_install_retains_partial_journal(self):
        self.adb.fail_install_number = 2
        with self.assertRaisesRegex(m.Stopped, "disconnected"):
            self.deploy()
        journal = m.read_json(self.backup / "receipt.json")
        self.assertEqual(journal["status"], "stopped")
        self.assertEqual(len([s for s in journal["steps"] if s["stage"] == "verified"]), 1)
        self.assertEqual(journal["steps"][-1]["failed_stage"], "install_started")
        self.assertEqual(self.adb.install_count, 2)  # No automatic rollback or retry.

    def test_restore_needs_no_companions_and_uses_reverse_order(self):
        for package in m.COMPANIONS:
            del self.adb.packages[package]
        journal = m.change(self.adb, self.profile, "restore", self.backup, self.bundle, apps=None)
        self.assertEqual(journal.data["status"], "verified")
        verified = [s["package"] for s in journal.data["steps"] if s["stage"] == "verified"]
        self.assertEqual(verified, [self.profile["menus"][n]["package"] for n in reversed(m.MENU_ORDER)])
        self.assertFalse(any(c[:2] == ("pm", "path") and c[2] in m.COMPANIONS for c in self.adb.calls))

    def test_uploaded_bytes_checked_before_pm_install(self):
        self.adb.upload_bad = True
        with self.assertRaisesRegex(m.Stopped, "Uploaded APK hash mismatch"):
            self.deploy()
        self.assertEqual(self.adb.install_count, 0)

    def test_failed_push_cannot_install_stale_temp_file(self):
        self.adb.push_error = True
        with self.assertRaisesRegex(m.Stopped, "push failed"):
            self.deploy()
        self.assertEqual(self.adb.install_count, 0)
        remote = next(c[2] for c in self.adb.calls if c[0] == "push")
        self.assertRegex(remote, r"^/data/local/tmp/vn-menu-[0-9a-f]{32}\.apk$")

    def test_readback_mismatch_stops_remaining_install(self):
        self.adb.readback_bad = True
        with self.assertRaisesRegex(m.Stopped, "readback mismatch"):
            self.deploy()
        self.assertEqual(self.adb.install_count, 1)
        self.assertEqual(m.read_json(self.backup / "receipt.json")["status"], "stopped")

    def test_low_storage_refuses_before_push(self):
        self.adb.df = "Filesystem Size Used Free Blksize\n/data 1G 999M 1M 4096\n"
        with self.assertRaisesRegex(m.Stopped, "100 MiB"):
            self.deploy()
        self.no_mutations()

    def test_existing_backup_is_never_overwritten(self):
        self.backup.mkdir()
        (self.backup / "important").write_text("keep")
        with self.assertRaises(FileExistsError):
            self.deploy()
        self.assertEqual((self.backup / "important").read_text(), "keep")
        self.no_mutations()

    def test_first_time_companion_install(self):
        for package in m.COMPANIONS:
            del self.adb.packages[package]
        journal = self.deploy("install-apps")
        self.assertEqual(journal.data["status"], "verified")
        self.assertEqual(self.adb.install_count, 2)
        self.assertFalse(any("/system/app/" in str(c) for c in self.adb.calls))

    def test_unknown_companion_preserved(self):
        self.adb.files[self.adb.packages[m.COMPANIONS[0]]] = b"unknown companion"
        with self.assertRaisesRegex(m.Stopped, "Unknown installed companion"):
            self.deploy("install-apps")
        self.no_mutations()

    def test_capture_is_readonly_and_checks_framework(self):
        journal = m.capture(self.adb, self.profile, self.backup)
        self.assertEqual(journal.data["status"], "captured")
        self.assertEqual((self.backup / "framework/core.jar").read_bytes(), b"core")
        self.no_mutations()

    def test_historical_menu_hash_accepted_without_new_bundle(self):
        package = self.profile["menus"]["allmenu"]["package"]
        historical = apk("reviewed historical candidate")
        self.profile["menus"]["allmenu"]["known_active_sha256"] = [digest(historical)]
        self.adb.packages[package] = "/data/app/historical.apk"
        self.adb.files["/data/app/historical.apk"] = historical
        journal = m.capture(self.adb, self.profile, self.backup)
        self.assertEqual(journal.data["status"], "captured")
        self.assertEqual((self.backup / "menus/allmenu/active.apk").read_bytes(), historical)
        self.no_mutations()

    def test_malformed_historical_hash_profile_rejected(self):
        self.profile["menus"]["allmenu"]["known_active_sha256"] = ["anything"]
        self.profile_path.write_text(json.dumps(self.profile))
        with self.assertRaisesRegex(m.Stopped, "historical active APK hash"):
            m.load_profile(self.profile_path)

    def test_capture_unknown_framework_fails(self):
        self.adb.files["/system/framework/core.jar"] = b"different core"
        with self.assertRaisesRegex(m.Stopped, "Framework hash mismatch"):
            m.capture(self.adb, self.profile, self.backup)
        self.no_mutations()

    def test_bundle_payload_pin_is_independent_of_receipt(self):
        self.assertEqual(set(m.load_bundle(self.bundle_dir, self.profile, self.profile_path)), set(m.MENU_ORDER))
        receipt = m.read_json(self.bundle_dir / "receipt.json")
        path = self.bundle_dir / receipt["menus"]["allmenu"]["candidate"]["file"]
        path.write_bytes(apk("tampered"))
        receipt["menus"]["allmenu"]["candidate"]["sha256"] = m.sha(path)
        (self.bundle_dir / "receipt.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(m.Stopped, "Unrecognized APK payload"):
            m.load_bundle(self.bundle_dir, self.profile, self.profile_path)

    def test_incomplete_or_wrong_base_bundle_rejected(self):
        receipt_path = self.bundle_dir / "receipt.json"
        receipt = m.read_json(receipt_path)
        receipt["status"] = "building"
        receipt_path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(m.Stopped, "did not finish"):
            m.load_bundle(self.bundle_dir, self.profile, self.profile_path)
        receipt["status"] = "offline_checks_passed"
        receipt["menus"]["allmenu"]["base_odex_sha256"] = "0" * 64
        receipt_path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(m.Stopped, "factory base mismatch"):
            m.load_bundle(self.bundle_dir, self.profile, self.profile_path)

    def test_bundle_path_escape_rejected(self):
        receipt = m.read_json(self.bundle_dir / "receipt.json")
        receipt["menus"]["allmenu"]["candidate"]["file"] = "../outside.apk"
        (self.bundle_dir / "receipt.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(m.Stopped, "escapes"):
            m.load_bundle(self.bundle_dir, self.profile, self.profile_path)

    def test_remote_package_injection_rejected(self):
        for value in ("package:/data/app/x.apk;id", "package:/data/app/../x.apk", "package:/data/app/$(id).apk",
                      "package:/data/app/a.apk\npackage:/data/app/b.apk", "Failure: not installed"):
            with self.subTest(value=value), self.assertRaises(m.Stopped):
                m.installed_path(value, "pkg", optional=True)

    def test_serial_injection_and_shell_quoting(self):
        for serial in ("-s", "abc;id", "abc\nxyz", "$(id)"):
            with self.subTest(serial=serial), self.assertRaises(m.Stopped):
                m.Adb(serial)
        with patch.object(m.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")) as run:
            m.Adb("test").shell("echo", "x;$(id) ' y")
        command = run.call_args.args[0]
        self.assertEqual(command[:4], ["adb", "-s", "test", "shell"])
        self.assertEqual(shlex.split(command[4]), ["echo", "x;$(id) ' y"])
        self.assertNotIn("shell", run.call_args.kwargs)

    def test_ambiguous_success_is_failure(self):
        for output in ("", "not Success", "Success\nFailure [bad]", "error\nSuccess"):
            with self.subTest(output=output), self.assertRaises(m.Stopped):
                m.success(output)
        m.success("\r\nSuccess\r\n")

    def test_real_api17_pm_prefix_is_accepted_only_for_uploaded_path(self):
        remote = "/data/local/tmp/vn-menu-123.apk"
        m.success("\tpkg: " + remote + "\nSuccess\n", remote)
        with self.assertRaises(m.Stopped):
            m.success("pkg: /data/local/tmp/different.apk\nSuccess\n", remote)

    def test_df_variants_and_unknown_units(self):
        self.assertEqual(m.free_bytes("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/x 500000 100000 400000 20% /data\n"), 400000 * 1024)
        with self.assertRaises(m.Stopped):
            m.free_bytes("Filesystem Size Used Free Blksize\n/data 1000 500 500 4096\n")


if __name__ == "__main__":
    unittest.main()
