#!/usr/bin/env python3
"""Build optional OEM menu updates locally from exact, privately captured originals.

Never connects to ADB. No OEM APK, decompiled implementation or key is distributed.
The independent My Apps quick-entry feature does not require this optional route.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

from menu_lib import (APK, DalvikOdexVMFormat, align_zip, check_alignment,
                      check_dex, cms, core, dex_methods, hashes, padding,
                      replace_version, serialization, sha, shape, verify_jar, x509)
import menu_patch_downloadmenu
import menu_patch_allmenu

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "menu-entry/profile.json"


def payload_sha256(path):
    with zipfile.ZipFile(path) as archive:
        rows = [[name, hashlib.sha256(archive.read(name)).hexdigest()]
                for name in sorted(archive.namelist())
                if not name.upper().startswith("META-INF/") and not name.endswith("/")]
    return hashlib.sha256(json.dumps(rows, separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()


def run(out, name, command):
    result = subprocess.run(list(map(str, command)), capture_output=True)
    log = out / (name + ".log")
    log.write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(name + " failed; see " + str(log))


def sign(unsigned, final, out, java, key, cert):
    from cryptography.hazmat.primitives.serialization import pkcs12
    store = out / "temporary-public-test-key.p12"
    password = b"public-aosp-test-key"
    store.write_bytes(pkcs12.serialize_key_and_certificates(
        b"platform", key, cert, None, serialization.BestAvailableEncryption(password)))
    store.chmod(0o600)
    signed, legacy = out / "signed.apk", out / "legacy.apk"
    try:
        run(out, "sign", [java.with_name("jarsigner" + java.suffix), "-keystore", store,
                         "-storetype", "PKCS12", "-storepass", password.decode(),
                         "-sigalg", "SHA1withRSA", "-digestalg", "SHA1", "-sigfile", "CERT",
                         "-signedjar", signed, unsigned, "platform"])
        # Android 4.2 JarVerifier requires the previously tested detached,
        # attribute-free PKCS#7 form; modern jarsigner output alone is insufficient.
        with zipfile.ZipFile(signed) as source:
            info = cms.ContentInfo.load(source.read("META-INF/CERT.RSA"))
            signer = info["content"]["signer_infos"][0]
            del signer["signed_attrs"]
            del signer["unsigned_attrs"]
            signer["signature_algorithm"] = {"algorithm": "rsassa_pkcs1v15", "parameters": core.Null()}
            signer["signature"] = key.sign(source.read("META-INF/CERT.SF"), padding.PKCS1v15(), hashes.SHA1())
            with zipfile.ZipFile(legacy, "w") as dest:
                for item in source.infolist():
                    dest.writestr(item, info.dump() if item.filename == "META-INF/CERT.RSA" else source.read(item))
        align_zip(legacy, final)
        check_alignment(final)
    finally:
        store.unlink(missing_ok=True)


def compile_helper(args, work):
    classes, dex, smali = work / "helper-classes", work / "helper-dex", work / "helper-smali"
    classes.mkdir(); dex.mkdir()
    inputs = sorted((ROOT / "menu-entry/helper").rglob("*.java"))
    inputs += sorted((ROOT / "menu-entry/stubs").rglob("*.java"))
    run(work, "helper-compile", [args.java.with_name("javac" + args.java.suffix), "-encoding", "UTF-8",
                               "-source", "7", "-target", "7", "-classpath", args.tools / "android-api16.jar",
                               "-d", classes, *inputs])
    run(work, "helper-dex", [args.java, "-cp", args.tools / "r8.jar", "com.android.tools.r8.D8",
                           "--min-api", "17", "--lib", args.tools / "android-api16.jar",
                           "--classpath", classes, "--output", dex,
                           classes / "com/veloster/menu/HubEntryHelper.class"])
    run(work, "helper-disassemble", [args.java, "-jar", args.tools / "baksmali.jar", "disassemble",
                                   "-a", "17", "-o", smali, dex / "classes.dex"])
    files = list(smali.rglob("*.smali"))
    assert len(files) == 1 and files[0].relative_to(smali).as_posix() == "com/veloster/menu/HubEntryHelper.smali"
    return files[0]


def build(args):
    profile = json.loads(PROFILE.read_text())
    for filename, expected in profile["tool_sha256"].items():
        if sha(args.tools / filename) != expected:
            raise ValueError("Tool differs from tested version: " + filename)
    for filename, expected in profile["framework_sha256"].items():
        if sha(args.capture / "framework" / filename) != expected:
            raise ValueError("Framework differs from tested build: " + filename)
    for name, menu in profile["menus"].items():
        for extension in ("apk", "odex"):
            if sha(args.capture / "menus" / name / ("system." + extension)) != menu[extension + "_sha256"]:
                raise ValueError("Original mismatch: " + name + " " + extension)
    key = serialization.load_der_private_key(args.key.read_bytes(), None)
    cert = x509.load_pem_x509_certificate(args.cert.read_bytes())
    if cert.fingerprint(hashes.SHA256()).hex() != profile["signer_sha256"]:
        raise ValueError("Certificate is not the tested public AOSP platform certificate")
    if key.public_key().public_numbers() != cert.public_key().public_numbers():
        raise ValueError("Signing key and certificate differ")
    args.out.mkdir(parents=True, exist_ok=False)
    receipt = {"schema": 1, "profile_sha256": sha(PROFILE), "status": "building",
               "new_vehicle_test_performed": False, "menus": {}}
    for name, menu in profile["menus"].items():
        work = args.out / name
        work.mkdir()
        original = args.capture / "menus" / name / "system.apk"
        odex = original.with_suffix(".odex")
        original_signature = verify_jar(original)
        assert original_signature["signer_sha256"] == profile["signer_sha256"]
        before = APK(str(original))
        assert before.get_package() == menu["package"]
        assert int(before.get_androidversion_code()) == 1
        base, candidate = work / "baseline-smali", work / "candidate-smali"
        run(work, "deodex", [args.java, "-jar", args.tools / "baksmali.jar", "deodex", "-a", "17",
                            "-d", args.capture / "framework", "-o", base, odex])
        shutil.copytree(base, candidate)
        patch = menu_patch_downloadmenu if name == "downloadmenu" else menu_patch_allmenu
        source_text = (base / patch.ACTIVITY).read_text()
        patched = patch.patch_activity(source_text)
        assert patch.unpatch_activity(patched) == source_text
        (candidate / patch.ACTIVITY).write_text(patched)
        if name == "downloadmenu":
            helper = compile_helper(args, work)
            target = candidate / "com/veloster/menu/HubEntryHelper.smali"
            target.parent.mkdir(parents=True)
            shutil.copyfile(helper, target)
        models = {}
        for flavor, source in (("fallback", base), ("candidate", candidate)):
            dex = work / (flavor + ".dex")
            run(work, flavor + "-assemble", [args.java, "-jar", args.tools / "smali.jar", "assemble",
                                            "-j", "1", "-a", "17", "-o", dex, source])
            models[flavor] = check_dex(dex.read_bytes())
            # A second tool roundtrip checks complete instruction preservation.
            tree, roundtrip = work / (flavor + "-roundtrip"), work / (flavor + "-roundtrip.dex")
            run(work, flavor + "-roundtrip-disassemble", [args.java, "-jar", args.tools / "baksmali.jar",
                                                        "disassemble", "-a", "17", "-o", tree, dex])
            run(work, flavor + "-roundtrip-assemble", [args.java, "-jar", args.tools / "smali.jar", "assemble",
                                                     "-j", "1", "-a", "17", "-o", roundtrip, tree])
            assert dex_methods(models[flavor]) == dex_methods(check_dex(roundtrip.read_bytes()))
        original_shape = shape(DalvikOdexVMFormat(odex.read_bytes()))
        assert original_shape == shape(models["fallback"])
        old_methods, new_methods = dex_methods(models["fallback"]), dex_methods(models["candidate"])
        changed = {k for k in old_methods if old_methods[k] != new_methods[k]}
        cls = "L" + patch.ACTIVITY.removesuffix(".smali") + ";"
        expected = {(cls, "init", "()V")}
        if name == "downloadmenu":
            expected.add((cls, "addItem", "()V"))
            assert shape(models["candidate"])["classes"] - original_shape["classes"] == {"Lcom/veloster/menu/HubEntryHelper;"}
        else:
            assert original_shape == shape(models["candidate"])
        assert changed == expected
        for kind in original_shape:
            assert original_shape[kind] <= shape(models["candidate"])[kind]
        with zipfile.ZipFile(original) as archive:
            assert "classes.dex" not in archive.namelist()
            manifest, _ = replace_version(archive.read("AndroidManifest.xml"), 2)
        result = {"base_apk_sha256": menu["apk_sha256"], "base_odex_sha256": menu["odex_sha256"],
                  "changed_methods": sorted([list(row) for row in changed]),
                  "original_resources_preserved": True, "roundtrip_passed": True}
        for flavor in ("candidate", "fallback"):
            output = args.out / (name + "-" + flavor + ".apk")
            unsigned = work / (flavor + "-unsigned.apk")
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(unsigned, "w") as dest:
                for item in source.infolist():
                    if item.filename.upper().startswith("META-INF/"):
                        continue
                    dest.writestr(item, manifest if item.filename == "AndroidManifest.xml" else source.read(item))
                dest.write(work / (flavor + ".dex"), "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
            signing = work / (flavor + "-signing"); signing.mkdir()
            sign(unsigned, output, signing, args.java, key, cert)
            signed = verify_jar(output)
            assert signed["signer_sha256"] == profile["signer_sha256"] and not signed["signed_entries_absent_from_archive"]
            after = APK(str(output))
            assert after.get_package() == before.get_package() and int(after.get_androidversion_code()) == 2
            assert after.get_permissions() == before.get_permissions()
            assert after.get_android_manifest_xml().get("{http://schemas.android.com/apk/res/android}sharedUserId") is None
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(output) as dest:
                assert dest.read("AndroidManifest.xml") == manifest
                for filename in source.namelist():
                    if filename.upper().startswith("META-INF/") or filename == "AndroidManifest.xml":
                        continue
                    assert source.read(filename) == dest.read(filename)
            payload = payload_sha256(output)
            pinned = menu.get("expected_payload_sha256", {}).get(flavor)
            if pinned is None or payload != pinned:
                raise ValueError("Unapproved payload for " + name + "/" + flavor + ": " + payload)
            result[flavor] = {"file": output.name, "sha256": sha(output), "payload_sha256": payload}
        receipt["menus"][name] = result
    receipt["status"] = "offline_checks_passed"
    (args.out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("Built checked menu updates and same-version original-code fallbacks: " + str(args.out))
    print("These files contain your OEM code. Keep the entire output private. No vehicle was modified.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--tools", type=Path, required=True, help="Pinned android-api16.jar, r8.jar, smali.jar, baksmali.jar")
    parser.add_argument("--java", type=Path, required=True, help="JDK17 java executable")
    parser.add_argument("--key", type=Path, required=True, help="External public AOSP platform.pk8")
    parser.add_argument("--cert", type=Path, required=True, help="External public AOSP platform.x509.pem")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    for name in vars(args):
        setattr(args, name, getattr(args, name).resolve())
    build(args)


if __name__ == "__main__":
    main()
