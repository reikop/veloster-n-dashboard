#!/usr/bin/env python3
"""Prepare an authenticated app update bundle locally. Does not publish, sign APKs or access a vehicle."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = {'com.veloster.dashboard': 'dashboard', 'com.veloster.carplayhub': 'hub'}


def metadata(badging, apk, url, certificate):
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
    if not match or match[1] not in PACKAGES or not 0 < int(match[2]) < 2**31:
        raise ValueError('Only N Dashboard / My Apps with a positive version code are supported')
    if "minSdkVersion:'17'" not in badging or "targetSdkVersion:'17'" not in badging:
        raise ValueError('Requires minSdk and targetSdk 17')
    u = urlsplit(url)
    if u.scheme != 'https' or u.netloc != 'github.com' or u.query or u.fragment or not re.fullmatch(r'/reikop/veloster-n-dashboard/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\.apk', u.path):
        raise ValueError('Requires a direct HTTPS release APK URL in this project')
    if not re.fullmatch('[0-9a-f]{64}', certificate):
        raise ValueError('Invalid certificate digest')
    with apk.open("rb") as stream:
        data = stream.read(16*1024*1024+1)
    if not 0 < len(data) <= 16*1024*1024:
        raise ValueError('APK size must be at most 16 MiB')
    values = [('package', match[1]), ('version', match[2]), ('bytes', str(len(data))),
              ('sha256', hashlib.sha256(data).hexdigest()), ('certificate', certificate),
              ('product', 'jsn_kr'), ('incremental', 'eng.vmadmin.20250902.081552'),
              ('sdk', '17'), ('url', url)]
    return ('VNUPDATE1\n' + ''.join(k+'='+v+'\n' for k,v in values)).encode('ascii')


def run(command):
    result = subprocess.run(list(map(str, command)), capture_output=True, timeout=90)
    if result.returncode:
        raise ValueError('Tool failed: '+str(command[0])+'\n'+result.stderr.decode('utf-8', 'replace')[:3000])
    return result.stdout.decode('utf-8').strip()


def prepare(args):
    apk = args.apk.resolve()
    if args.out.exists():
        raise ValueError('Output must be a new directory')
    java = Path(shutil.which(str(args.java)) or args.java).resolve()
    with tempfile.TemporaryDirectory(prefix='vn-update-') as temporary:
        tmp = Path(temporary)
        # Every tool and the emitted bundle use one bounded snapshot, even if a build replaces the input.
        original = apk
        apk = tmp/'package.apk'
        with original.open('rb') as stream:
            data = stream.read(16*1024*1024+1)
        if not 0 < len(data) <= 16*1024*1024:
            raise ValueError('APK size must be at most 16 MiB')
        apk.write_bytes(data)
        source = ROOT/'apps/hub/src/com/veloster/carplayhub/update'
        run([java.with_name('javac'), '-encoding', 'UTF-8', '-d', tmp,
             *[source/(name+'.java') for name in ('UpdateFiles','UpdateManifest','UpdateTrust')],
             ROOT/'scripts/UpdatePackageTool.java'])
        # API17 accepts SHA1 APK V1 signatures. This compatibility setting is scoped to this child JVM only.
        security = tmp/'api17.security'
        security.write_text('jdk.jar.disabledAlgorithms=MD2, MD5, RSA keySize < 1024, DSA keySize < 1024\n')
        tool = [java, '-Djava.security.properties='+str(security), '-cp', tmp, 'UpdatePackageTool']
        certificate = run([*tool, 'certificate', apk])
        badging = run([args.aapt2, 'dump', 'badging', apk])
        manifest = metadata(badging, apk, args.url, certificate)
        (tmp/'manifest.txt').write_bytes(manifest)
        run(['openssl','dgst','-sha256','-sign',args.key,'-out',tmp/'manifest.sig',tmp/'manifest.txt'])
        # Authenticates against the public key embedded in the app. A mismatched key fails before output creation.
        verified = run([*tool, 'verify', tmp/'manifest.txt', tmp/'manifest.sig', apk])
        args.out.mkdir(parents=True, exist_ok=False)
        try:
            for name in ('manifest.txt','manifest.sig','package.apk'):
                shutil.copyfile(tmp/name,args.out/name)
        except Exception:
            shutil.rmtree(args.out)
            raise
        print(verified)
        print('Bundle prepared locally: '+str(args.out))
        print('USB: copy this directory as vn-update/dashboard or vn-update/hub. Online publishing is separate.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',required=True,type=Path)
    parser.add_argument('--key',required=True,type=Path,help='External private update authorization key; never copied into output')
    parser.add_argument('--url',required=True,help='Future HTTPS release APK asset URL; it is not fetched')
    parser.add_argument('--out',required=True,type=Path)
    parser.add_argument('--java',default='java')
    parser.add_argument('--aapt2',required=True)
    args=parser.parse_args()
    try:prepare(args)
    except (OSError,ValueError,subprocess.TimeoutExpired) as error:parser.exit(1,str(error)+'\n')

if __name__=='__main__':main()
