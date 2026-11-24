"""Prevent stale JNI/source mixing and accidental unlisted module inputs."""
import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('carplay_build_inputs', ROOT / 'scripts/carplay_build_inputs.py')
inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inputs)


class CarPlayBuildInputs(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.module = self.root / 'third_party/wireless-carplay'
        self.files = {'source/com/example/Receiver.java': b'fixture', 'java/COPYING': b'GPL fixture'}
        for name in ('native/tunnel/crypto_jni.c', 'native/pairing/crypto_jni.c', 'native/pairing/srp_jni.c'):
            self.files[name] = name.encode()
        for name, data in self.files.items():
            path = self.module / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.hashes = {n: hashlib.sha256(v).hexdigest() for n, v in self.files.items()}
        (self.module / 'source-manifest.json').write_text(json.dumps({'format': 1, 'oem_code_or_binary_included': False, 'files': self.hashes}))
        notice = self.root / 'third_party/network/THIRD_PARTY_NOTICES.txt'
        notice.parent.mkdir(parents=True)
        notice.write_text('OpenSSL fixture')
        self.lib = self.root / 'crypto.so'
        data = bytearray(52)
        data[:6] = b'\x7fELF\x01\x01'
        data[16:20] = b'\x03\0\x28\0'
        self.lib.write_bytes(data)
        self.report = {'api': 17, 'abi': 'armeabi-v7a', 'library_sha256': hashlib.sha256(data).hexdigest(),
                       'source_hashes': {n: h for n, h in self.hashes.items() if n.startswith('native/')}}
        self.manifest = self.root / 'native.json'
        self.save()

    def tearDown(self):
        self.tmp.cleanup()

    def save(self):
        self.manifest.write_text(json.dumps(self.report))

    def test_disabled_build_and_notices(self):
        _, assets, metadata = inputs.load(self.root)
        self.assertFalse(metadata['native_available'])
        self.assertIn('assets/licenses/wireless/java/COPYING', assets)
        self.assertNotIn('lib/armeabi-v7a/libvn-carplay-crypto.so', assets)

    def test_matched_input_packages_actual_bytes(self):
        _, assets, metadata = inputs.load(self.root, self.lib, self.manifest)
        self.assertTrue(metadata['native_available'])
        self.assertEqual(assets['lib/armeabi-v7a/libvn-carplay-crypto.so'], self.lib.read_bytes())

    def test_changed_source_refused(self):
        (self.module / 'source/com/example/Receiver.java').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'snapshot changed'):
            inputs.load(self.root)

    def test_unlisted_source_refused(self):
        (self.module / 'source/com/example/Hidden.java').write_text('unlisted')
        with self.assertRaisesRegex(ValueError, 'missing from manifest'):
            inputs.load(self.root)

    def test_mismatched_native_source_refused(self):
        self.report['source_hashes']['native/pairing/srp_jni.c'] = '0' * 64
        self.save()
        with self.assertRaisesRegex(ValueError, 'JNI source differs'):
            inputs.load(self.root, self.lib, self.manifest)

    def test_altered_library_and_partial_inputs_refused(self):
        with self.assertRaisesRegex(ValueError, 'both'):
            inputs.load(self.root, self.lib)
        self.lib.write_bytes(self.lib.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'does not match library'):
            inputs.load(self.root, self.lib, self.manifest)

    def eld(self):
        names = ('native/audio-codec/native/eld16k_system_encoder.c',
                 'native/audio-codec/native/eld16k_system_encoder.h', 'native/audio-codec/native/eld16k_jni.c')
        pins = {}
        for name in names:
            p = self.module / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(name)
            pins[name] = hashlib.sha256(p.read_bytes()).hexdigest()
        self.hashes.update(pins)
        (self.module / 'source-manifest.json').write_text(json.dumps({'format': 1, 'oem_code_or_binary_included': False, 'files': self.hashes}))
        report = dict(api=17, abi='armeabi-v7a', library_sha256=hashlib.sha256(self.lib.read_bytes()).hexdigest(),
                      source_hashes=pins, fdk_bundled=False)
        path = self.root / 'eld.json'; path.write_text(json.dumps(report))
        return path, report

    def test_optional_eld_packages_exact_backend_without_support_claim(self):
        path, _ = self.eld()
        _, assets, metadata = inputs.load(self.root, self.lib, self.manifest, self.lib, path)
        self.assertEqual(assets['lib/armeabi-v7a/libvn-carplay-eld16.so'], self.lib.read_bytes())
        self.assertTrue(metadata['eld_native_available'])
        self.assertFalse(metadata['eld_runtime_support_verified'])

    def test_eld_requires_crypto_and_complete_arguments(self):
        path, _ = self.eld()
        with self.assertRaisesRegex(ValueError, 'requires the wireless crypto'):
            inputs.load(self.root, eld_library=self.lib, eld_manifest=path)
        with self.assertRaisesRegex(ValueError, 'both --carplay-eld'):
            inputs.load(self.root, self.lib, self.manifest, self.lib)

    def test_eld_stale_header_is_refused(self):
        path, report = self.eld()
        report['source_hashes']['native/audio-codec/native/eld16k_system_encoder.h'] = '0' * 64
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, 'ELD JNI source differs'):
            inputs.load(self.root, self.lib, self.manifest, self.lib, path)

    def test_eld_unreviewed_codec_packaging_is_refused(self):
        path, report = self.eld(); report['fdk_bundled'] = True
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, 'codec packaging'):
            inputs.load(self.root, self.lib, self.manifest, self.lib, path)

    def test_eld_altered_native_library_is_refused(self):
        path, _ = self.eld()
        altered = self.root / 'altered.so'; altered.write_bytes(self.lib.read_bytes() + b'different')
        with self.assertRaisesRegex(ValueError, 'ELD build does not match library'):
            inputs.load(self.root, self.lib, self.manifest, altered, path)


if __name__ == '__main__':
    unittest.main()
