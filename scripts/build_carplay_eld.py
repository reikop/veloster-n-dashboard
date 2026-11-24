#!/usr/bin/env python3
"""Build optional ARM/API17 AAC-ELD JNI from public source; no FDK is bundled."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NAMES = ('native/audio-codec/native/eld16k_system_encoder.c',
         'native/audio-codec/native/eld16k_system_encoder.h',
         'native/audio-codec/native/eld16k_jni.c')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    module = ROOT / 'third_party/wireless-carplay'
    exported = json.loads((module / 'source-manifest.json').read_text())
    pins = {name: sha(module / name) for name in NAMES}
    if any(exported['files'].get(name) != digest for name, digest in pins.items()):
        raise ValueError('ELD JNI sources do not match the public source manifest')
    ndk = args.ndk.resolve()
    if 'Pkg.Revision = 22.1.7171670' not in (ndk / 'source.properties').read_text():
        raise ValueError('This recipe requires the pinned NDK r22b')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}.get(platform.system())
    if host is None:
        raise ValueError('Run this recipe on macOS or Linux')
    binaries = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    compiler = binaries / 'armv7a-linux-androideabi17-clang'
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=False)
    library = out / 'libvn-carplay-eld16.so'
    command = [compiler, '-std=c11', '-Wall', '-Wextra', '-Werror', '-fPIC', '-shared',
               '-Wl,-z,relro,-z,now', '-o', library, module / NAMES[0], module / NAMES[2], '-ldl']
    with (out / 'compile.log').open('w') as log:
        subprocess.run(list(map(str, command)), stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
    elf = subprocess.check_output([str(binaries / 'llvm-readelf'), '-d', '-Ws', str(library)], text=True)
    (out / 'elf.log').write_text(elf)
    if any('NEEDED' in line and ('stagefright' in line or 'fdk' in line.lower()) for line in elf.splitlines()):
        raise ValueError('System codec must remain an optional runtime dependency')
    if pins != {name: sha(module / name) for name in NAMES}:
        raise ValueError('ELD JNI sources changed during build')
    report = {'format': 1, 'api': 17, 'abi': 'armeabi-v7a', 'source_hashes': pins,
              'library_sha256': sha(library), 'builder_sha256': sha(Path(__file__)),
              'compiler_sha256': sha(compiler), 'command': list(map(str, command)),
              'runtime_dependency': '/system/lib/libstagefright_soft_aacenc.so',
              'runtime_image_sha256_pinned': False, 'fdk_bundled': False,
              'runtime_verified_by_this_build': False, 'vehicle_accessed': False}
    (out / 'build-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'library_sha256': report['library_sha256'], 'fdk_bundled': False}))

if __name__ == '__main__':
    main()
