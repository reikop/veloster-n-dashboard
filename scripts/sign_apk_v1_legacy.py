#!/usr/bin/env python3
"""V1 (JAR) sign an APK in the form Android 4.2's harmony verifier accepts.

JDK 17 jarsigner adds PKCS#7 signed attributes (signingTime,
CMSAlgorithmProtection) and the sha1WithRSAEncryption OID; the API17
PackageParser rejects that with "Incorrect signature". This writes SHA1
digests and a detached PKCS#7 without signed attributes (rsaEncryption),
matching the released N-Dashboard APKs. Local files only; never contacts a device.
"""
import argparse
import base64
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile


def b64sha1(data):
    return base64.b64encode(hashlib.sha1(data).digest())


def wrap(line):
    """JAR manifest lines are at most 72 bytes; continuations start with a space."""
    raw = line.encode('utf-8')
    if len(raw) <= 72:
        return raw + b'\r\n'
    out = raw[:72] + b'\r\n'
    raw = raw[72:]
    while raw:
        out += b' ' + raw[:71] + b'\r\n'
        raw = raw[71:]
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('out', type=Path)
    parser.add_argument('--key', type=Path, required=True, help='PKCS#8 DER private key (.pk8)')
    parser.add_argument('--cert', type=Path, required=True, help='X.509 PEM certificate')
    parser.add_argument('--openssl', default='openssl')
    args = parser.parse_args()
    if args.out.exists():
        raise SystemExit('Refusing to overwrite ' + str(args.out))

    with zipfile.ZipFile(args.apk) as source:
        entries = [i for i in source.infolist() if not i.is_dir() and not i.filename.startswith('META-INF/')]
        payload = {i.filename: source.read(i) for i in entries}

    manifest = b'Manifest-Version: 1.0\r\nCreated-By: 1.0 (Android)\r\n\r\n'
    sections = []
    for info in entries:
        section = wrap('Name: ' + info.filename) + b'SHA1-Digest: ' + b64sha1(payload[info.filename]) + b'\r\n\r\n'
        manifest += section
        sections.append((info.filename, section))
    signature_file = (b'Signature-Version: 1.0\r\nCreated-By: 1.0 (Android)\r\nSHA1-Digest-Manifest: '
                      + b64sha1(manifest) + b'\r\n\r\n')
    for name, section in sections:
        signature_file += wrap('Name: ' + name) + b'SHA1-Digest: ' + b64sha1(section) + b'\r\n\r\n'

    with tempfile.TemporaryDirectory(prefix='apk-v1-') as temp:
        temp = Path(temp)
        key_pem, sf, rsa = temp / 'key.pem', temp / 'CERT.SF', temp / 'CERT.RSA'
        sf.write_bytes(signature_file)
        subprocess.run([args.openssl, 'pkcs8', '-inform', 'DER', '-nocrypt', '-in', str(args.key), '-out', str(key_pem)],
                       check=True, capture_output=True)
        subprocess.run([args.openssl, 'smime', '-sign', '-binary', '-noattr', '-md', 'sha1', '-outform', 'DER',
                        '-in', str(sf), '-signer', str(args.cert), '-inkey', str(key_pem), '-out', str(rsa)],
                       check=True, capture_output=True)
        block = rsa.read_bytes()
        dump = subprocess.run([args.openssl, 'asn1parse', '-inform', 'DER', '-in', str(rsa)],
                              check=True, capture_output=True, text=True).stdout
        if 'signingTime' in dump or 'messageDigest' in dump or ':rsaEncryption' not in dump:
            raise SystemExit('Unexpected PKCS#7 shape; refusing to write an API17-incompatible signature')

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, 'w') as target:
        for name, data in (('META-INF/MANIFEST.MF', manifest), ('META-INF/CERT.SF', signature_file), ('META-INF/CERT.RSA', block)):
            target.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), data, compress_type=zipfile.ZIP_DEFLATED)
        for info in entries:
            clone = zipfile.ZipInfo(info.filename, info.date_time)
            clone.external_attr = info.external_attr
            target.writestr(clone, payload[info.filename], compress_type=info.compress_type)
    print(hashlib.sha256(args.out.read_bytes()).hexdigest() + '  ' + str(args.out))


if __name__ == '__main__':
    main()
