#!/usr/bin/env python3
"""Offline rebuild of the API17 native HTTPS transport. No ADB or downloads.

Supply the three verified source files in --sources and the existing NDK r22b.
All generated files stay in a new --out directory. No global installation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile

PINNED = {
    'openssl-3.5.9.tar.gz': '603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a',
    'curl-8.23.0.tar.gz': 'e3de77cfa6367083c776a26647fc9f633b538c06c3baa73d931a252ac8f72b49',
    'cacert-2026-10-20.pem': '796f1389bfea13c0ed78f663b765baaff54a508e5483786bd6ece73c5e3e3cbe',
}

CARES = {'c-ares-1.34.9.tar.gz': '9364b70a12eb59a06830fb488d9357f90100dff0d98b23ea3593a183d43e6f2f'}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--sources', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=6)
    parser.add_argument('--dns', choices=('system', 'cares'), default='system')
    args = parser.parse_args()
    if not 1 <= args.jobs <= 16:
        parser.error('--jobs must be 1..16')
    ndk, sources, out = args.ndk.resolve(), args.sources.resolve(), args.out.resolve()
    if '22.1.7171670' not in (ndk / 'source.properties').read_text():
        parser.error('This recipe is validated only with Android NDK r22b (22.1.7171670)')
    candidates = [ndk / 'toolchains/llvm/prebuilt' / p / 'bin' for p in ('darwin-x86_64', 'linux-x86_64')]
    tool = next((p for p in candidates if (p / 'armv7a-linux-androideabi17-clang').exists()), None)
    if tool is None:
        parser.error('ARMv7 API17 compiler not found')
    pinned = dict(PINNED)
    if args.dns == 'cares':
        pinned.update(CARES)
    for name, expected in pinned.items():
        if digest(sources / name) != expected:
            parser.error('Source SHA-256 mismatch: ' + name)
    out.mkdir(parents=True, exist_ok=False)
    work, openssl, prefix = out / 'build', out / 'build/openssl-3.5.9', out / 'openssl-install'
    work.mkdir()
    for name in [name for name in pinned if name.endswith('.tar.gz')]:
        with tarfile.open(sources / name) as archive:
            for member in archive.getmembers():
                path = PurePosixPath(member.name)
                if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
                    raise ValueError('Unsupported archive member: ' + member.name)
            archive.extractall(work)
    env = os.environ.copy()
    for key in ('CC', 'CXX', 'AR', 'AS', 'RANLIB', 'CFLAGS', 'CXXFLAGS', 'CPPFLAGS', 'LDFLAGS', 'LIBS', 'CROSS_COMPILE', 'CROSS_SYSROOT'):
        env.pop(key, None)
    env['ANDROID_NDK_ROOT'] = str(ndk)
    env['PATH'] = str(tool) + os.pathsep + env['PATH']
    commands = []

    def run(label, command, cwd):
        commands.append({'label': label, 'argv': command, 'cwd': str(cwd)})
        (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        with (out / (label + '.log')).open('w') as log:
            subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)

    run('openssl-configure', ['perl', 'Configure', 'android-arm', '-D__ANDROID_API__=17',
        '--prefix=' + str(prefix), 'no-shared', 'no-tests', 'no-apps', 'no-module', 'no-dso',
        'no-engine', 'no-legacy', 'no-asm', 'no-ssl3', 'no-tls1', 'no-tls1_1', 'no-dtls',
        'no-comp', 'no-zlib', '-Os', '-fPIC'], openssl)
    run('openssl-build', ['make', '-j' + str(args.jobs), 'build_libs'], openssl)
    (prefix / 'lib').mkdir(parents=True)
    shutil.copytree(openssl / 'include/openssl', prefix / 'include/openssl')
    for name in ('libssl.a', 'libcrypto.a'):
        shutil.copy2(openssl / name, prefix / 'lib' / name)
    env.update(CC=str(tool / 'armv7a-linux-androideabi17-clang'), AR=str(tool / 'llvm-ar'),
               RANLIB=str(tool / 'llvm-ranlib'), CFLAGS='-Os -fPIC', LDFLAGS='-Wl,--gc-sections',
               PKG_CONFIG_LIBDIR=str(prefix / 'lib/pkgconfig'))
    resolver_options = []
    if args.dns == 'cares':
        cares = work / 'c-ares-1.34.9'
        cares_prefix = out / 'cares-install'
        run('cares-configure', ['./configure', '--host=arm-linux-androideabi',
            '--prefix=' + str(cares_prefix), '--disable-shared', '--enable-static',
            '--disable-tests', '--disable-tools'], cares)
        run('cares-build', ['make', '-j' + str(args.jobs)], cares)
        run('cares-install', ['make', 'install'], cares)
        env['PKG_CONFIG_LIBDIR'] = str(cares_prefix / 'lib/pkgconfig') + os.pathsep + env['PKG_CONFIG_LIBDIR']
        resolver_options = ['--enable-ares=' + str(cares_prefix), '--disable-threaded-resolver']
    curl = work / 'curl-8.23.0'
    run('curl-configure', ['./configure', '--host=arm-linux-androideabi',
        '--prefix=' + str(out / 'curl-install'), '--disable-shared', '--enable-static',
        '--with-openssl=' + str(prefix), '--without-ca-bundle', '--without-ca-path',
        '--without-ca-fallback', '--without-libpsl', '--without-libidn2', '--without-nghttp2',
        '--without-nghttp3', '--without-brotli', '--without-zstd', '--without-zlib',
        '--disable-manual', '--disable-docs', '--disable-ldap', '--disable-ldaps',
        '--disable-ftp', '--disable-file', '--disable-rtsp', '--disable-dict', '--disable-telnet',
        '--disable-tftp', '--disable-pop3', '--disable-imap', '--disable-smtp', '--disable-gopher',
        '--disable-mqtt', '--disable-unix-sockets', '--disable-cookies', '--disable-netrc',
        '--disable-http-auth', '--disable-ipfs', '--disable-websockets', '--disable-hsts',
        '--disable-alt-svc'] + resolver_options, curl)
    run('curl-build', ['make', '-j' + str(args.jobs)], curl)
    (out / 'bin').mkdir()
    binary = out / 'bin/vn-curl-api17-armv7'
    shutil.copy2(curl / 'src/curl', binary)
    run('strip', [str(tool / 'llvm-strip'), '--strip-unneeded', str(binary)], out)
    run('elf', [str(tool / 'llvm-readelf'), '-h', '-d', str(binary)], out)
    shutil.copy2(sources / 'cacert-2026-10-20.pem', out / 'cacert.pem')
    shutil.copy2(Path(__file__).resolve().parents[1] / 'third_party/network/THIRD_PARTY_NOTICES.txt', out / 'THIRD_PARTY_NOTICES.txt')
    manifest = {'api': 17, 'abi': 'armeabi-v7a', 'ndk': '22.1.7171670',
                'source_sha256': pinned, 'dns': args.dns, 'binary_sha256': digest(binary),
                'binary_bytes': binary.stat().st_size,
                'note': 'Cross-build only; this script never executes Android code or connects to a device. Different absolute build paths can change binary bytes.'}
    (out / 'build-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
