"""One-command installer orchestration tests; no ADB or vehicle access."""
from contextlib import redirect_stdout, redirect_stderr
import importlib.util
import io
from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("public_install", ROOT / "scripts/install.py")
i = importlib.util.module_from_spec(spec)
spec.loader.exec_module(i)


class Journal:
    def __init__(self, path, checked=False):
        self.path = path / "receipt.json"
        self.data = {"status": "checked_without_changes" if checked else "verified", "steps": []}
        self.saved = 0

    def step(self, **entry):
        self.data["steps"].append(entry)
        self.saved += 1


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.args = SimpleNamespace(serial="TEST-SERIAL", adb="adb", parked=True, check=False)
        self.journal = Journal(self.root / "private-backups/attempt")
        self.output = "Starting: Intent { cmp=com.veloster.carplayhub/.HubActivity }\n"
        self.adb = SimpleNamespace(shell=lambda *args: self.output)
        self.patches = [patch.object(i.menu_entry, "load_profile", return_value={"tested": True}),
                        patch.object(i.menu_entry, "load_apps", return_value={"verified": True}),
                        patch.object(i.menu_entry, "Adb", return_value=self.adb),
                        patch.object(i.menu_entry, "change", return_value=self.journal)]
        self.profile, self.apps, self.adb_ctor, self.change = [p.start() for p in self.patches]
        self.stdout, self.stderr = io.StringIO(), io.StringIO()

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.temp.cleanup()

    def run_install(self):
        with redirect_stdout(self.stdout), redirect_stderr(self.stderr):
            return i.install(self.args, root=self.root)

    def test_verified_install_launches_hub_and_prints_acceptance_steps(self):
        with patch.object(self.adb, "shell", return_value=self.output) as shell:
            result = self.run_install()
        shell.assert_called_once_with("am", "start", "-n", i.HUB_COMPONENT)
        self.assertEqual(result.data["status"], "apps_verified_launch_accepted")
        self.assertFalse(result.data["entry_verified_on_screen"])
        self.assertFalse(result.data["reboot_entry_verified"])
        self.assertIn("Go HOME", self.stdout.getvalue())
        self.assertIn("normal full reboot", self.stdout.getvalue())
        self.assertIn("No shortcut preference was enabled automatically", self.stdout.getvalue())
        args, kwargs = self.change.call_args
        self.assertEqual(args[2], "install-apps")
        self.assertEqual(args[3].parent, self.root / "private-backups")
        self.assertRegex(args[3].name, r"^install-\d{8}T\d{6}Z-[a-f0-9]{12}$")
        self.assertFalse(kwargs["check"])

    def test_check_never_launches_or_mutates(self):
        self.args.check, self.args.parked = True, False
        self.journal.data["status"] = "checked_without_changes"
        with patch.object(self.adb, "shell") as shell:
            result = self.run_install()
        shell.assert_not_called()
        self.assertTrue(self.change.call_args.kwargs["check"])
        self.assertEqual(result.data["status"], "checked_without_changes")
        self.assertFalse(result.data["steps"])

    def test_failed_install_does_not_launch(self):
        self.change.side_effect = i.menu_entry.Stopped("installed hash mismatch")
        with patch.object(self.adb, "shell") as shell, self.assertRaisesRegex(i.menu_entry.Stopped, "hash mismatch"):
            self.run_install()
        shell.assert_not_called()
        self.assertFalse(self.journal.data["steps"])

    def test_unverified_receipt_does_not_launch(self):
        self.journal.data["status"] = "stopped"
        with patch.object(self.adb, "shell") as shell, self.assertRaisesRegex(i.menu_entry.Stopped, "not verified"):
            self.run_install()
        shell.assert_not_called()

    def test_missing_parked_refuses_before_target_access(self):
        self.args.parked = False
        with self.assertRaisesRegex(i.menu_entry.Stopped, "requires --parked"):
            self.run_install()
        self.profile.assert_not_called()
        self.adb_ctor.assert_not_called()
        self.change.assert_not_called()

    def test_am_error_with_zero_adb_exit_leaves_verified_apps(self):
        self.output += "Error type 3\nError: Activity class does not exist.\n"
        with self.assertRaisesRegex(i.menu_entry.Stopped, "rejected"):
            self.run_install()
        self.assertEqual(self.journal.data["status"], "apps_verified_launch_unconfirmed")
        self.assertEqual(self.journal.data["steps"][-1]["stage"], "launch_unconfirmed")
        self.assertIn("no uninstall or rollback", self.stderr.getvalue())
        self.assertEqual(self.change.call_count, 1)

    def test_ambiguous_launch_output_stays_unconfirmed(self):
        self.output = ""
        with self.assertRaisesRegex(i.menu_entry.Stopped, "Could not confirm"):
            self.run_install()
        self.assertEqual(self.journal.data["status"], "apps_verified_launch_unconfirmed")

    def test_existing_top_task_acknowledgement_is_accepted(self):
        i.launch_accepted(self.output + "Warning: Activity not started, its current task has been brought to the front\n")
        i.launch_accepted("Starting: Intent { cmp=com.veloster.carplayhub/com.veloster.carplayhub.HubActivity }\n")

    def test_wrong_component_and_failure_variants_rejected(self):
        for output in ("Starting: Intent { cmp=wrong/.Activity }", self.output + "java.lang.SecurityException: denied",
                       self.output + "Failure [denied]", self.output + "Exception occurred", self.output + "SecurityException: denied"):
            with self.subTest(output=output), self.assertRaises(i.menu_entry.Stopped):
                i.launch_accepted(output)

    def test_backups_unique_without_user_naming(self):
        paths = {i.new_backup(self.root) for _ in range(8)}
        self.assertEqual(len(paths), 8)
        self.assertTrue(all(p.parent == self.root / "private-backups" for p in paths))


if __name__ == "__main__":
    unittest.main()
