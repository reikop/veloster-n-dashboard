#!/usr/bin/env python3
"""Authenticate a My Apps USB bundle locally; optionally preflight/install ONLY My Apps over ADB."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

import install
import menu_entry
import prepare_app_update as author

ROOT = Path(__file__).resolve().parents[1]
HUB = 'com.veloster.carplayhub'
# Exact locally signed previews handed to the owner; arbitrary same-signer APKs remain refused.
KNOWN_HUB_PREVIEWS = frozenset({
    '759526f6efcabddac528fd97d2a1ce93c6da92486401c59d0655477b0675a706',  # v11
    '968170321b73786fac975b0ec8c465ed2e2158728dd7fedd5921ca49b6d3665a',  # v12
    '23a6437fb944e2df97c52fe00c0dc8e18ee12618dc832cd4dc9b4c23449da590',  # v13
    '711c31f57dfd3c92fc7b27a8c167005214d61b10b3fbe8cba40f0c51296b2c8e',  # v14
    '293208cb0f6053c3794e89860632b37a613814eaa89548c7c37c1decd2c4c4c2',  # v15
})


def bounded(path, limit):
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if not data or len(data) > limit:
        raise ValueError('Missing or oversized bundle member: ' + path.name)
    return data


def apply(args, apk, manifest):
    """Called only after authentication. Default is offline; --serial alone is read-only."""
    if args.install and (not args.parked or not args.serial):
        raise ValueError('--install requires --serial and --parked')
    if not args.serial:
        print('Authenticated My Apps v' + manifest['version'] + '. Offline check only; no ADB connection.')
        return None
    profile = menu_entry.load_profile()
    released = json.loads((ROOT/'release.json').read_text())
    accepted = {e['sha256'] for e in released['apks'] if e['package'] == HUB}
    accepted.update(KNOWN_HUB_PREVIEWS)
    accepted.add(manifest['sha256'])
    apps = {HUB: {'path': apk, 'sha256': manifest['sha256'], 'accepted': accepted}}
    adb = menu_entry.Adb(args.serial, args.adb)
    journal = menu_entry.change(adb, profile, 'install-apps', install.new_backup(),
                                apps=apps, check=not args.install, companions=(HUB,))
    print('Private backup and receipt: ' + str(journal.path))
    if args.install:
        menu_entry.require(journal.data['status'] == 'verified', 'Installation not verified')
        journal.step(package=HUB, stage='launch_requested', component=install.HUB_COMPONENT)
        try:
            install.launch_accepted(adb.shell('am', 'start', '-n', install.HUB_COMPONENT))
        except Exception:
            journal.data['status'] = 'apps_verified_launch_unconfirmed'
            journal.save()
            raise
        journal.data.update(status='apps_verified_launch_accepted', entry_verified_on_screen=False,
                            reboot_entry_verified=False)
        journal.step(package=HUB, stage='launch_accepted')
        print('My Apps readback verified. Check its screen manually; dashboard and system apps were not selected.')
    return journal


def bootstrap(args):
    if args.install and (not args.parked or not args.serial):
        raise ValueError('--install requires --serial and --parked')
    with tempfile.TemporaryDirectory(prefix='vn-bootstrap-') as folder:
        tmp = Path(folder)
        for name, limit in [('manifest.txt',4096), ('manifest.sig',256), ('package.apk',16*1024*1024)]:
            (tmp/name).write_bytes(bounded(args.bundle/name,limit))
        java = Path(shutil.which(str(args.java)) or args.java).resolve()
        source = ROOT/'apps/hub/src/com/veloster/carplayhub/update'
        author.run([java.with_name('javac'), '-encoding','UTF-8','-d',tmp,
                    *[source/(n+'.java') for n in ('UpdateFiles','UpdateManifest','UpdateTrust')],
                    ROOT/'scripts/UpdatePackageTool.java'])
        security = tmp/'api17.security'
        security.write_text('jdk.jar.disabledAlgorithms=MD2, MD5, RSA keySize < 1024, DSA keySize < 1024\n')
        author.run([java,'-Djava.security.properties='+str(security),'-cp',tmp,'UpdatePackageTool',
                    'verify',tmp/'manifest.txt',tmp/'manifest.sig',tmp/'package.apk'])
        raw = (tmp/'manifest.txt').read_bytes()
        fields = dict(line.split('=',1) for line in raw.decode('ascii').splitlines()[1:])
        if fields['package'] != HUB or int(fields['version']) <= 10:
            raise ValueError('Bootstrap accepts only an authenticated My Apps successor after v10')
        badging = author.run([args.aapt2,'dump','badging',tmp/'package.apk'])
        if author.metadata(badging,tmp/'package.apk',fields['url'],fields['certificate']) != raw:
            raise ValueError('Signed manifest does not match APK identity')
        return apply(args,tmp/'package.apk',fields)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle',type=Path,required=True)
    p.add_argument('--java',default='java')
    p.add_argument('--aapt2',required=True)
    p.add_argument('--serial',help='Exact target; without --install this only checks and backs up My Apps')
    p.add_argument('--adb',default='adb')
    p.add_argument('--install',action='store_true')
    p.add_argument('--parked',action='store_true')
    args = p.parse_args()
    try:
        bootstrap(args)
    except (OSError, ValueError, menu_entry.Stopped, KeyError, subprocess.TimeoutExpired) as error:
        p.exit(1,'STOPPED: '+str(error)+'\n')


if __name__ == '__main__':
    main()
