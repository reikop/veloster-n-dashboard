"""Exercise selection generations, bond guards and real concurrent save withdrawal."""
from pathlib import Path
import importlib.util
import os
import subprocess
import tempfile
import unittest
from test_quick_entry_runtime import java_tools
ROOT = Path(__file__).resolve().parents[1]

class WirelessCarPlaySettingsTests(unittest.TestCase):
    def test_selection_and_revocation(self):
        java, javac = java_tools()
        candidates = [Path(os.environ.get('ANDROID_JAR', '/nonexistent')),
                      ROOT.parent / 'veloster-n-lab/tools/android/android-api16.jar']
        android = next((p for p in candidates if p.is_file()), None)
        if android is None:
            self.skipTest('Set ANDROID_JAR to an Android API17-compatible SDK jar')
        with tempfile.TemporaryDirectory() as folder:
            # Compile the actual Hub/core boundary. Handwritten application/UI
            # stubs had drifted behind enrollment, volume and mode integration.
            # Only Settings is executed below, with explicit storage/radio fakes;
            # this compile does not claim Android UI or session runtime coverage.
            sources = sorted((ROOT/'apps/hub/src').rglob('*.java'))
            shared = ROOT/'shared/src/com/veloster'
            sources.append(shared/'vehicle/UsbDebugControl.java')
            for name in ('i18n', 'diagnostics', 'network'):
                sources += sorted((shared/name).rglob('*.java'))
            spec = importlib.util.spec_from_file_location(
                'settings_carplay_inputs', ROOT/'scripts/carplay_build_inputs.py')
            inputs = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(inputs)
            wireless, _, _ = inputs.load(ROOT)
            sources += wireless
            sources.append(ROOT/'tests/WirelessCarPlaySettingsTest.java')
            build = subprocess.run([javac, '-encoding', 'UTF-8', '-source', '7', '-target', '7', '-Xlint:-options',
                                    '-cp', str(android), '-d', folder, *map(str, sources)], capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stdout+build.stderr)
            run = subprocess.run([java, '-cp', folder, 'com.veloster.carplayhub.WirelessCarPlaySettingsTest'],
                                 capture_output=True, text=True, timeout=20)
            self.assertEqual(run.returncode, 0, run.stdout+run.stderr)
            print(run.stdout.strip())
