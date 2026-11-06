#!/usr/bin/env python3
"""Build the wireless JNI library from public C sources and the pinned API17 OpenSSL build.

Offline build only. First run build_tls_helper.py to reproduce --openssl-build.
No OEM library, private key, device connection or signing operation is used.
"""
import argparse
import hashlib
import json
import platform
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('ndk', 'openssl-build', 'out'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    module = ROOT / 'third_party/wireless-carplay'
    source_names = ('native/tunnel/crypto_jni.c', 'native/pairing/crypto_jni.c', 'native/pairing/srp_jni.c')
    sources = [module / name for name in source_names]
    source_hashes = {name: sha(module / name) for name in source_names}
    exported = json.loads((module / 'source-manifest.json').read_text())
    if any(exported['files'].get(name) != digest for name, digest in source_hashes.items()):
        raise ValueError('JNI sources do not match the public source manifest')
    build = args.openssl_build.resolve()
    dependency = json.loads((build / 'build-manifest.json').read_text())
    if dependency.get('api') != 17 or dependency.get('abi') != 'armeabi-v7a':
        raise ValueError('Use the API17 ARMv7 TLS build output')
    ndk = args.ndk.resolve()
    if 'Pkg.Revision = 22.1.7171670' not in (ndk / 'source.properties').read_text():
        raise ValueError('This recipe requires the pinned NDK r22b')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}.get(platform.system())
    if host is None:
        raise ValueError('Run this native recipe on macOS or Linux')
    binaries = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    compiler = binaries / 'armv7a-linux-androideabi17-clang'
    prefix = build / 'openssl-install'
    archive = prefix / 'lib/libcrypto.a'
    inputs = {str(p.relative_to(prefix)): sha(p) for p in sorted((prefix / 'include').rglob('*.h'))}
    inputs['lib/libcrypto.a'] = sha(archive)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    library = out / 'libvn-carplay-crypto.so'
    command = [compiler, '-std=c99', '-Wall', '-Wextra', '-Werror', '-fvisibility=hidden',
               '-fPIC', '-Os', '-shared', '-Wl,-z,relro,-z,now', '-Wl,--exclude-libs,ALL',
               '-Wl,-soname,libvn-carplay-crypto.so', '-I' + str(prefix / 'include'),
               *sources, archive, '-ldl', '-o', library]
    with (out / 'compile.log').open('w') as log:
        subprocess.run(list(map(str, command)), stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
    with (out / 'elf.log').open('w') as log:
        subprocess.run([str(binaries / 'llvm-readelf'), '-h', '-d', '-Ws', str(library)],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=30)
    if any(sha(prefix / name) != digest for name, digest in inputs.items()):
        raise ValueError('OpenSSL inputs changed during build')
    if any(sha(module / name) != digest for name, digest in source_hashes.items()):
        raise ValueError('JNI sources changed during build')
    report = {'format': 1, 'api': 17, 'abi': 'armeabi-v7a', 'library_sha256': sha(library),
              'source_hashes': source_hashes, 'openssl_inputs': inputs,
              'dependency_build_manifest_sha256': sha(build / 'build-manifest.json'),
              'builder_sha256': sha(Path(__file__)), 'command': list(map(str, command)),
              'runtime_verified_by_this_build': False, 'vehicle_accessed': False}
    (out / 'build-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('openssl_inputs', 'command')}, indent=2))


if __name__ == '__main__':
    main()
