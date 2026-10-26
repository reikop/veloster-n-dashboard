"""Project-authored menu patch and legacy APK verification helpers.

No vendor implementation or keys are included. Input APK/ODEX hashes are gated
by menu-entry/profile.json before these narrowly scoped hooks are used.
"""
import base64
import hashlib
import logging
import struct
import zipfile
import zlib
from pathlib import Path
logging.disable(logging.CRITICAL)
from androguard.core.bytecodes.apk import APK
from androguard.core.bytecodes.dvm import DalvikVMFormat, DalvikOdexVMFormat
from asn1crypto import cms, core
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, utils
HASHES = {"sha1": hashes.SHA1, "sha256": hashes.SHA256, "sha512": hashes.SHA512}
if not __debug__:
    raise RuntimeError("Do not run menu tools with Python -O: verification assertions are required")

def verify_cms(der, content=None, content_digest=None):
    sd = cms.ContentInfo.load(der)["content"]
    verified = []
    for signer in sd["signer_infos"]:
        sid = signer["sid"].chosen
        certs = [c.chosen for c in sd["certificates"] if c.name == "certificate"]
        certs = [c for c in certs if c.serial_number == sid["serial_number"].native
                 and c.issuer.dump() == sid["issuer"].dump()]
        assert len(certs) == 1, "Signer certificate not uniquely identified"
        cert = x509.load_der_x509_certificate(certs[0].dump())
        algorithm = signer["digest_algorithm"]["algorithm"].native
        assert signer["signature_algorithm"]["algorithm"].native in ("rsassa_pkcs1v15", "sha1_rsa", "sha256_rsa")
        digest = content_digest if content_digest is not None else hashlib.new(algorithm, content).digest()
        attrs = signer["signed_attrs"]
        if attrs.native:
            message_digests = [a["values"][0].native for a in attrs if a["type"].native == "message_digest"]
            assert message_digests == [digest], "CMS content digest mismatch"
            encoded = attrs.dump()
            cert.public_key().verify(signer["signature"].native, b"\x31" + encoded[1:], padding.PKCS1v15(), HASHES[algorithm]())
        else:
            cert.public_key().verify(signer["signature"].native, digest, padding.PKCS1v15(), utils.Prehashed(HASHES[algorithm]()))
        verified.append(cert)
    assert verified
    return verified

def manifest_sections(data):
    # JAR attributes can be continued on the following line with one space.
    result = []
    for section in data.replace(b"\r\n", b"\n").split(b"\n\n"):
        if not section.strip():
            continue
        unfolded = []
        for line in section.split(b"\n"):
            if line.startswith(b" "):
                unfolded[-1] += line[1:]
            elif line:
                unfolded.append(line)
        result.append(dict(line.decode("utf-8").split(": ", 1) for line in unfolded))
    return result

def digest_matches(attrs, suffix, data):
    found = False
    for name, value in attrs.items():
        if name.endswith(suffix):
            alg = name[:-len(suffix)].lower().replace("-", "")
            assert base64.b64decode(value) == hashlib.new(alg, data).digest(), name
            found = True
    assert found, "No supported digest attribute"

def verify_jar(path):
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        names = z.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP entries"
        signatures = [n for n in names if n.upper().startswith("META-INF/") and n.upper().endswith(".RSA")]
        assert len(signatures) == 1
        signature = signatures[0]
        sf = z.read(signature[:-4] + ".SF")
        certs = verify_cms(z.read(signature), content=sf)
        mf = z.read("META-INF/MANIFEST.MF")
        digest_matches(manifest_sections(sf)[0], "-Digest-Manifest", mf)
        covered = set()
        absent = []
        for attrs in manifest_sections(mf)[1:]:
            name = attrs["Name"]
            assert name not in covered
            if name not in names:
                # A preoptimized ROM may strip classes.dex after signing;
                # verify every present entry and report absent signed entries.
                absent.append(name)
                continue
            digest_matches(attrs, "-Digest", z.read(name))
            covered.add(name)
        payload = {n for n in names if not n.endswith("/") and not n.upper().startswith("META-INF/")}
        assert payload <= covered, "Unsigned payload entries"
    return {"jar_signature_valid": True, "covered_payload_entries": len(payload),
            "signed_entries_absent_from_archive": absent,
            "signer_sha256": certs[0].fingerprint(hashes.SHA256()).hex(),
            "signer_subject": certs[0].subject.rfc4514_string()}

def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(4 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def shape(dex):
    return {
        "classes": {c.get_name() for c in dex.get_classes()},
        "methods": {(c.get_name(), m.get_name(), m.get_descriptor(), m.get_access_flags())
                    for c in dex.get_classes() for m in c.get_methods()},
        "fields": {(c.get_name(), f.get_name(), f.get_descriptor(), f.get_access_flags())
                   for c in dex.get_classes() for f in c.get_fields()},
    }

def align_zip(source, target):
    # JAR v1 signatures cover uncompressed data, not ZIP padding. Align Stored
    # entries after jarsigner, which may relocate local file headers.
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, "w") as dst:
        for old in src.infolist():
            info = zipfile.ZipInfo(old.filename, old.date_time)
            info.compress_type = old.compress_type
            info.external_attr, info.create_system = old.external_attr, old.create_system
            info.extra = old.extra
            if info.compress_type == zipfile.ZIP_STORED:
                position = dst.fp.tell() + 30 + len(info.filename.encode("utf-8")) + len(info.extra)
                if position % 4:
                    payload = (-position - 4) % 4
                    info.extra += struct.pack("<HH", 0xA11E, payload) + b"\0" * payload
            dst.writestr(info, src.read(old))

def check_alignment(path):
    with path.open("rb") as f, zipfile.ZipFile(path) as z:
        for entry in z.infolist():
            if entry.compress_type != zipfile.ZIP_STORED:
                continue
            f.seek(entry.header_offset + 26)
            name_length, extra_length = struct.unpack("<HH", f.read(4))
            assert (entry.header_offset + 30 + name_length + extra_length) % 4 == 0, entry.filename

def replace_version(manifest, version):
    """Patch the typed integer attribute only; preserve the original AXML bytes."""
    data = bytearray(manifest)
    pos = 8
    strings = None
    changes = []
    while pos < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, pos)
        assert size >= header and size > 0
        if kind == 1:
            count, _, flags, string_start, _ = struct.unpack_from('<5I', data, pos + 8)
            strings = []
            def length(at, utf8):
                value = data[at] if utf8 else struct.unpack_from('<H', data, at)[0]
                if utf8:
                    return (((value & 127) << 8) | data[at+1], at+2) if value & 128 else (value, at+1)
                return (((value & 32767) << 16) | struct.unpack_from('<H', data, at+2)[0], at+4) if value & 32768 else (value, at+2)
            for index in range(count):
                at = pos + string_start + struct.unpack_from('<I', data, pos+header+4*index)[0]
                if flags & 256:
                    _, at = length(at, True)
                    size_s, at = length(at, True)
                    value = data[at:at+size_s].decode('utf-8')
                else:
                    size_s, at = length(at, False)
                    value = data[at:at+size_s*2].decode('utf-16le')
                strings.append(value)
        elif kind == 0x102:
            ext = pos + header
            _, name, attr_start, attr_size, count = struct.unpack_from('<IIHHH', data, ext)
            if strings[name] == 'manifest':
                for index in range(count):
                    at = ext + attr_start + index*attr_size
                    _, key, _, _, _, dtype, value = struct.unpack_from('<IIIHBBI', data, at)
                    if strings[key] == 'versionCode':
                        assert dtype == 0x10 and value == 1
                        struct.pack_into('<I', data, at+16, version)
                        changes.append(at+16)
        pos += size
    assert len(changes) == 1
    return bytes(data), changes[0]

def dex_methods(dex):
    return {(c.get_name(), m.get_name(), m.get_descriptor()):
            [(i.get_name(), i.get_output()) for i in m.get_instructions()]
            for c in dex.get_classes() for m in c.get_methods()}

def check_dex(data):
    assert data[:8] == b'dex\n035\0'
    assert hashlib.sha1(data[32:]).digest() == data[12:32]
    assert zlib.adler32(data[12:]) == struct.unpack_from('<I', data, 8)[0]
    dex = DalvikVMFormat(data)
    for instructions in dex_methods(dex).values():
        for op, _ in instructions:
            assert 'quick' not in op and not op.startswith(('execute-inline', 'throw-verification-error'))
    return dex
