"""Bootstrap mode/identity orchestration checks; no device commands."""
from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
spec=importlib.util.spec_from_file_location('bootstrap',ROOT/'scripts/bootstrap_updater.py');b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
class BootstrapTests(unittest.TestCase):
    def test_offline_default_never_constructs_adb(self):
        args=SimpleNamespace(install=False,parked=False,serial=None)
        with patch.object(b.menu_entry,'Adb') as adb,redirect_stdout(io.StringIO()):
            self.assertIsNone(b.apply(args,Path('unused'),{'version':'11'}))
        adb.assert_not_called()
    def test_install_requires_explicit_target_and_parked(self):
        for serial,parked in [(None,True),('test',False)]:
            args=SimpleNamespace(install=True,parked=parked,serial=serial)
            with patch.object(b.menu_entry,'Adb') as adb:
                with self.assertRaises(ValueError):b.apply(args,Path('unused'),{})
            adb.assert_not_called()
    def test_serial_defaults_to_hub_only_readonly(self):
        args=SimpleNamespace(install=False,parked=False,serial='TEST',adb='fake')
        journal=SimpleNamespace(path=Path('receipt'),data={'status':'checked_without_changes'})
        with patch.object(b.menu_entry,'Adb') as adb,patch.object(b.menu_entry,'change',return_value=journal) as change,redirect_stdout(io.StringIO()):
            b.apply(args,Path('verified.apk'),{'version':'11','sha256':'0'*64})
        self.assertTrue(change.call_args.kwargs['check'])
        self.assertEqual(change.call_args.kwargs['companions'],(b.HUB,))
        self.assertEqual(list(change.call_args.kwargs['apps']),[b.HUB])
        accepted=change.call_args.kwargs['apps'][b.HUB]['accepted']
        self.assertTrue(b.KNOWN_HUB_PREVIEWS <= accepted)
        self.assertIn('23a6437fb944e2df97c52fe00c0dc8e18ee12618dc832cd4dc9b4c23449da590', accepted)
        self.assertIn('711c31f57dfd3c92fc7b27a8c167005214d61b10b3fbe8cba40f0c51296b2c8e', accepted)
        self.assertIn('293208cb0f6053c3794e89860632b37a613814eaa89548c7c37c1decd2c4c4c2', accepted)
        self.assertNotIn('f'*64, accepted)
        adb.return_value.shell.assert_not_called()
    def test_bundle_read_is_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'manifest';p.write_bytes(b'x'*4097)
            with self.assertRaises(ValueError):b.bounded(p,4096)
