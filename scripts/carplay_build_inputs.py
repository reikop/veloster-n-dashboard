"""Verified source snapshot and optional locally built ARM/API17 JNI module."""
import hashlib
import json
from pathlib import Path


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load(root, library=None, manifest=None, eld_library=None, eld_manifest=None):
    module = root / 'third_party/wireless-carplay'
    exported = json.loads((module / 'source-manifest.json').read_text())
    if exported.get('format') != 1 or exported.get('oem_code_or_binary_included') is not False:
        raise ValueError('Unsupported wireless source manifest')
    for name, digest in exported['files'].items():
        path = module / name
        if path.is_symlink() or not path.resolve().is_relative_to(module.resolve()):
            raise ValueError('Wireless source path escapes module')
        if sha(path.read_bytes()) != digest:
            raise ValueError('Wireless source snapshot changed: ' + name)
    sources = sorted((module / 'source').rglob('*.java'))
    if not sources or any(p.relative_to(module).as_posix() not in exported['files'] for p in sources):
        raise ValueError('Wireless sources missing from manifest')
    assets = {}
    for name in exported['files']:
        if Path(name).name in ('COPYING', 'LICENSE', 'NOTICE.md', 'NOTICE.txt', 'TRANSPORT-NOTICE.md'):
            assets['assets/licenses/wireless/' + name] = (module / name).read_bytes()
    metadata = {'format': 1, 'min_api': 17, 'abi': 'armeabi-v7a', 'native_available': False,
                'source_manifest_sha256': sha((module / 'source-manifest.json').read_bytes()),
                'phone_or_vehicle_verified': False, 'eld_native_available': False}
    if bool(library) != bool(manifest):
        raise ValueError('Provide both --carplay-crypto and --carplay-crypto-manifest')
    if library:
        data = Path(library).read_bytes()
        report = json.loads(Path(manifest).read_text())
        if not 52 <= len(data) <= 16*1024*1024 or data[:6] != b'\x7fELF\x01\x01' or data[16:20] != b'\x03\x00\x28\x00':
            raise ValueError('Wireless crypto must be little-endian ARM32 shared ELF')
        if report.get('api') != 17 or report.get('abi') != 'armeabi-v7a' or report.get('library_sha256') != sha(data):
            raise ValueError('Wireless crypto build does not match library')
        expected = {'native/tunnel/crypto_jni.c', 'native/pairing/crypto_jni.c', 'native/pairing/srp_jni.c'}
        if set(report.get('source_hashes', {})) != expected:
            raise ValueError('Wireless JNI source set does not match')
        for name in expected:
            if report['source_hashes'][name] != exported['files'][name]:
                raise ValueError('Wireless JNI source differs from bundled source: ' + name)
        notices = root / 'third_party/network/THIRD_PARTY_NOTICES.txt'
        assets['assets/licenses/wireless/OPENSSL_AND_TOOLCHAIN_NOTICES.txt'] = notices.read_bytes()
        assets['lib/armeabi-v7a/libvn-carplay-crypto.so'] = data
        metadata.update(native_available=True, native_sha256=sha(data),
                        native_build_manifest_sha256=sha(Path(manifest).read_bytes()))
    if bool(eld_library) != bool(eld_manifest):
        raise ValueError('Provide both --carplay-eld and --carplay-eld-manifest')
    if eld_library:
        if not library:
            raise ValueError('Optional ELD backend requires the wireless crypto module')
        data = Path(eld_library).read_bytes()
        report = json.loads(Path(eld_manifest).read_text())
        if not 52 <= len(data) <= 1024*1024 or data[:6] != b'\x7fELF\x01\x01' or data[16:20] != b'\x03\x00\x28\x00':
            raise ValueError('ELD backend must be little-endian ARM32 shared ELF')
        if report.get('api') != 17 or report.get('abi') != 'armeabi-v7a' or report.get('library_sha256') != sha(data):
            raise ValueError('ELD build does not match library')
        expected = {'native/audio-codec/native/eld16k_system_encoder.c',
                    'native/audio-codec/native/eld16k_system_encoder.h', 'native/audio-codec/native/eld16k_jni.c'}
        if set(report.get('source_hashes', {})) != expected or report.get('fdk_bundled') is not False:
            raise ValueError('Unsupported ELD JNI source set or codec packaging')
        for name in expected:
            if report['source_hashes'][name] != exported['files'].get(name):
                raise ValueError('ELD JNI source differs from bundled source: ' + name)
        assets['lib/armeabi-v7a/libvn-carplay-eld16.so'] = data
        metadata.update(eld_native_available=True, eld_native_sha256=sha(data),
                        eld_build_manifest_sha256=sha(Path(eld_manifest).read_bytes()),
                        eld_runtime_support_verified=False)
    assets['assets/wireless/build.json'] = (json.dumps(metadata, indent=2) + '\n').encode()
    assets['assets/wireless/build.properties'] = ('format=1\nnative_available=' +
        ('1' if metadata['native_available'] else '0') + '\nnative_sha256=' +
        metadata.get('native_sha256', '') + '\n').encode('ascii')
    return sources, assets, metadata
