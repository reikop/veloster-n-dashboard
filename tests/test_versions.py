import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / "scripts" / "check_versions.py"


class VersionTest(unittest.TestCase):
    def test_one_version_everywhere(self):
        r = subprocess.run([sys.executable, str(CHECK)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertRegex(r.stdout.strip(), r"^\d+\.\d+\.\d+$")

    def test_tag_must_match(self):
        v = subprocess.run([sys.executable, str(CHECK)], capture_output=True, text=True).stdout.strip()
        self.assertEqual(subprocess.run([sys.executable, str(CHECK), "--tag", "v" + v], capture_output=True).returncode, 0)
        self.assertNotEqual(subprocess.run([sys.executable, str(CHECK), "--tag", "v0.0.0"], capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
