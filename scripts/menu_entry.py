#!/usr/bin/env python3
"""Capture, install or restore the matched OEM menu entry. Requires an explicit ADB target.

No system partition writes, uninstall, data clearing, reboot, or force-stop. Capture
and --check are read-only on the vehicle. Generated OEM APKs and backups stay local.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "menu-entry" / "profile.json"
MENU_ORDER = ("downloadmenu", "allmenu")
COMPANIONS = ("com.veloster.dashboard", "com.veloster.carplayhub")
REMOTE_PATH = re.compile(r"/(?:system|data)/(?:[A-Za-z0-9_+.\-]+/)*[A-Za-z0-9_+.\-]+\.apk")


class Stopped(RuntimeError):
    """Expected preflight or device failure; never implies a successful install."""


def require(condition, message):
    if not condition:
        raise Stopped(message)


def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def contained(root, name):
    require(isinstance(name, str) and name and not Path(name).is_absolute(), "Invalid relative artifact path")
    path = (root / name).resolve()
    require(root.resolve() in path.parents, "Artifact path escapes its directory")
    require(path.is_file(), "Missing artifact: " + name)
    return path


def payload_sha256(path):
    """Stable identity for all non-signature APK bytes, independent of ZIP layout."""
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP members: " + str(path))
        require(sum(i.file_size for i in archive.infolist()) <= 256 * 1024 * 1024, "Unexpected APK size")
        require(archive.testzip() is None, "APK CRC failure")
        require(archive.read("classes.dex")[:8] == b"dex\n035\0", "APK is not DEX035")
        require("AndroidManifest.xml" in names, "APK manifest missing")
        require("META-INF/CERT.RSA" in names and "META-INF/CERT.SF" in names, "APK signature missing")
        rows = [[name, hashlib.sha256(archive.read(name)).hexdigest()] for name in sorted(names)
                if not name.endswith("/") and not name.upper().startswith("META-INF/")]
    return hashlib.sha256(json.dumps(rows, separators=(",", ":"), ensure_ascii=True).encode("ascii")).hexdigest()


def load_profile(path=PROFILE):
    profile = read_json(path)
    require(set(profile["menus"]) == set(MENU_ORDER), "Profile menu list mismatch")
    for name in MENU_ORDER:
        menu = profile["menus"][name]
        require(re.fullmatch(r"com\.hkmc\.system\.app\.[a-z]+", menu["package"]), "Invalid menu package")
        require(re.fullmatch(r"HKMC_[A-Za-z0-9]+", menu["file"]), "Invalid factory filename")
        for kind in ("apk_sha256", "odex_sha256"):
            require(re.fullmatch(r"[0-9a-f]{64}", menu[kind]), "Invalid factory hash")
        known = menu.get("known_active_sha256", [])
        require(isinstance(known, list) and all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)
                                               for value in known), "Invalid historical active APK hash")
    for name, digest in profile["framework_sha256"].items():
        require(re.fullmatch(r"[A-Za-z0-9_.+\-]+\.(?:jar|odex)", name), "Invalid framework filename")
        require(re.fullmatch(r"[0-9a-f]{64}", digest), "Invalid framework hash")
    return profile


def load_bundle(directory, profile, profile_path=PROFILE):
    directory = Path(directory).resolve()
    receipt = read_json(directory / "receipt.json")
    require(receipt.get("status") == "offline_checks_passed", "Bundle build did not finish its offline checks")
    require(receipt["profile_sha256"] == sha(profile_path), "Bundle was built against a different profile")
    require(set(receipt["menus"]) == set(MENU_ORDER), "Bundle menu list mismatch")
    result = {}
    for name in MENU_ORDER:
        result[name] = {}
        for extension in ("apk", "odex"):
            require(receipt["menus"][name].get("base_" + extension + "_sha256") ==
                    profile["menus"][name][extension + "_sha256"], "Bundle factory base mismatch: " + name)
        for kind in ("candidate", "fallback"):
            info = receipt["menus"][name][kind]
            path = contained(directory, info["file"])
            require(sha(path) == info["sha256"], "Bundle file hash mismatch: " + path.name)
            expected = profile["menus"][name].get("expected_payload_sha256", {}).get(kind)
            require(expected is not None, "Profile has no reviewed APK payload hash: " + name + "/" + kind)
            require(payload_sha256(path) == expected, "Unrecognized APK payload: " + path.name)
            result[name][kind] = {"path": path, "sha256": info["sha256"]}
    return result


def load_apps(root=ROOT):
    release = read_json(root / "release.json")
    apps = {}
    for package in COMPANIONS:
        entries = [e for e in release["apks"] if e["package"] == package]
        require(bool(entries), "Release is missing companion: " + package)
        current = max(entries, key=lambda item: int(item["version_code"]))
        path = contained(root, current["file"])
        require(path.stat().st_size == current["bytes"] and sha(path) == current["sha256"],
                "Companion release integrity failure: " + path.name)
        payload_sha256(path)
        apps[package] = {"path": path, "sha256": current["sha256"],
                         "accepted": {entry["sha256"] for entry in entries}}
    return apps


def installed_path(output, package, optional=False):
    lines = [line.strip() for line in output.replace("\r", "").splitlines() if line.strip()]
    paths = [line[len("package:"):] for line in lines if line.startswith("package:")]
    if not paths and optional and not lines:
        return None
    require(len(paths) == 1 and len(lines) == 1, "Cannot establish installed package path: " + package)
    path = paths[0]
    require(REMOTE_PATH.fullmatch(path) and ".." not in Path(path).parts,
            "Unexpected installed APK path: " + package)
    return path


def free_bytes(output):
    lines = output.replace("\r", "").splitlines()
    header = next((line.split() for line in lines if "Filesystem" in line), [])
    require("Free" in header or "Available" in header or "Avail" in header, "Unknown df output; inspect /data storage manually")
    for line in lines:
        fields = line.split()
        if len(fields) < 4 or not fields[0].startswith("/"):
            continue
        match = re.fullmatch(r"(\d+(?:\.\d+)?)([KMGT])?", fields[3], re.I)
        if not match:
            continue
        suffix = (match[2] or "").upper()
        # Android toolbox prints suffixed sizes; POSIX 1K-blocks are KiB.
        factor = {"K": 1024, "M": 1024**2, "G": 1024**3, "T": 1024**4}.get(suffix)
        if factor is None:
            require("1K-blocks" in header or "1024-blocks" in header, "Unsuffixed df units are unknown")
            factor = 1024
        return int(float(match[1]) * factor)
    raise Stopped("Cannot determine /data free space")


class Adb:
    def __init__(self, serial, executable="adb"):
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:\-]*", serial), "Invalid ADB serial")
        self.serial = serial
        self.executable = executable

    def run(self, *parts, timeout=90):
        result = subprocess.run([self.executable, "-s", self.serial, *map(str, parts)],
                                capture_output=True, text=True, timeout=timeout)
        output = (result.stdout + result.stderr).replace("\r", "")
        require(result.returncode == 0, "ADB command failed: " + " ".join(map(str, parts)) + "\n" + output.strip())
        return output

    def shell(self, *parts, timeout=90):
        # Old adbd joins shell arguments; quote for the DEVICE shell as well.
        return self.run("shell", shlex.join([str(p) for p in parts]), timeout=timeout)

    def pull(self, remote, local):
        local = Path(local)
        require(not local.exists(), "Refusing to overwrite a backup: " + str(local))
        local.parent.mkdir(parents=True, exist_ok=True)
        self.run("pull", remote, local)
        require(local.is_file() and local.stat().st_size > 0, "ADB pull did not create a usable file")

    def push(self, local, remote):
        self.run("push", local, remote)


def check_identity(adb, profile):
    require(adb.run("get-state").strip() == "device", "ADB target is not in device state")
    props = {"ro.product.name": profile["product"], "ro.build.version.incremental": profile["incremental"],
             "ro.build.version.sdk": str(profile["sdk"])}
    observed = {}
    for prop, expected in props.items():
        observed[prop] = adb.shell("getprop", prop).strip()
        require(observed[prop] == expected, "Unsupported target: " + prop + " = " + observed[prop])
    require(adb.shell("getprop", "ro.kernel.qemu").strip() != "1", "Emulator target is not supported")
    return observed


class Journal:
    def __init__(self, directory, action, adb):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=False)
        self.path = self.directory / "receipt.json"
        self.data = {"action": action, "serial": adb.serial, "status": "preflight", "steps": []}
        self.save()

    def save(self):
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.path)

    def step(self, **entry):
        self.data["steps"].append(entry)
        self.save()


def backup_package(adb, package, destination, optional=False):
    remote = installed_path(adb.shell("pm", "path", package), package, optional)
    if remote is None:
        return None
    adb.pull(remote, destination)
    return {"remote": remote, "sha256": sha(destination)}


def backup_menus(adb, profile, directory, bundle=None):
    result = {}
    for name in MENU_ORDER:
        menu = profile["menus"][name]
        target = directory / "menus" / name
        target.mkdir(parents=True)
        for extension, expected in (("apk", menu["apk_sha256"]), ("odex", menu["odex_sha256"])):
            destination = target / ("system." + extension)
            adb.pull("/system/app/" + menu["file"] + "." + extension, destination)
            require(sha(destination) == expected, "Factory " + extension + " mismatch: " + name)
        active = backup_package(adb, menu["package"], target / "active.apk")
        accepted = {menu["apk_sha256"], *menu.get("known_active_sha256", [])}
        if bundle:
            accepted.update(bundle[name][kind]["sha256"] for kind in ("candidate", "fallback"))
        require(active["sha256"] in accepted, "Unknown active menu update; backed up but not replaced: " + name)
        result[name] = active
    return result


def capture(adb, profile, out, bundle=None):
    journal = Journal(out, "capture", adb)
    try:
        journal.data["identity"] = check_identity(adb, profile)
        journal.data["menus"] = backup_menus(adb, profile, journal.directory, bundle)
        for name, expected in profile["framework_sha256"].items():
            destination = journal.directory / "framework" / name
            adb.pull("/system/framework/" + name, destination)
            require(sha(destination) == expected, "Framework hash mismatch: " + name)
        journal.data["status"] = "captured"
        journal.save()
        return journal
    except (Exception, KeyboardInterrupt) as error:
        journal.data.update(status="stopped", error=str(error))
        journal.save()
        raise


def success(output, remote=None):
    lines = [line.strip() for line in output.replace("\r", "").splitlines() if line.strip()]
    # Android 4.2 pm prints "pkg: /data/local/tmp/...apk" before Success.
    # Match that one known informational line, rather than accepting arbitrary
    # failure text merely because it also contains the word Success.
    if remote is not None and lines and lines[0] == "pkg: " + remote:
        lines = lines[1:]
    require(lines == ["Success"], "pm install did not report an unambiguous Success: " + output.strip())


def install_one(adb, package, artifact, journal, label):
    remote = "/data/local/tmp/vn-menu-" + uuid.uuid4().hex + ".apk"
    folder = journal.directory / "verification" / label
    folder.mkdir(parents=True)
    stage = "upload"
    try:
        journal.step(package=package, stage=stage, remote=remote)
        adb.push(artifact["path"], remote)
        adb.pull(remote, folder / "uploaded.apk")
        require(sha(folder / "uploaded.apk") == artifact["sha256"], "Uploaded APK hash mismatch: " + package)
        stage = "install_started"
        journal.step(package=package, stage=stage)
        success(adb.shell("pm", "install", "-r", remote, timeout=120), remote)
        stage = "readback"
        journal.step(package=package, stage=stage)
        installed = backup_package(adb, package, folder / "installed.apk")
        require(installed["sha256"] == artifact["sha256"], "Installed APK readback mismatch: " + package)
        journal.step(package=package, stage="verified", sha256=installed["sha256"])
    except (Exception, KeyboardInterrupt) as error:
        journal.step(package=package, stage="failed", failed_stage=stage, error=str(error))
        raise
    finally:
        try:
            output = adb.shell("rm", "-f", remote)
            if output.strip():
                journal.step(package=package, stage="cleanup_warning", output=output.strip())
        except (Exception, KeyboardInterrupt) as error:
            journal.step(package=package, stage="cleanup_warning", error=str(error))


def change(adb, profile, action, out, bundle=None, apps=None, check=False):
    require(action in ("install", "restore", "install-apps"), "Unknown mutation action")
    journal = Journal(out, action, adb)
    try:
        journal.data["identity"] = check_identity(adb, profile)
        active = {}
        if action != "restore":
            require(apps is not None, "Companion release validation is required")
            for package in COMPANIONS:
                record = backup_package(adb, package, journal.directory / "companions" / (package + ".apk"),
                                        optional=action == "install-apps")
                if action == "install-apps":
                    require(record is None or record["sha256"] in apps[package]["accepted"],
                            "Unknown installed companion; backed up but not replaced: " + package)
                else:
                    require(record is not None and record["sha256"] == apps[package]["sha256"],
                            "Install the current public companion first: " + package)
                active[package] = record
            journal.data["companions"] = active
        if action != "install-apps":
            require(bundle is not None, "A validated menu bundle is required")
            active = backup_menus(adb, profile, journal.directory, bundle)
            journal.data["menus"] = active
        free = free_bytes(adb.shell("df", "/data"))
        require(free >= 100 * 1024**2, "Less than 100 MiB free in /data; inspect storage before installing")
        journal.data["data_free_bytes"] = free
        journal.data["status"] = "preflight_passed"
        journal.save()
        if check:
            journal.data["status"] = "checked_without_changes"
            journal.save()
            return journal
        journal.data["status"] = "applying"
        journal.save()
        if action == "install-apps":
            changes = [(package, apps[package], active[package], package) for package in COMPANIONS]
        else:
            order = MENU_ORDER if action == "install" else tuple(reversed(MENU_ORDER))
            kind = "candidate" if action == "install" else "fallback"
            changes = [(profile["menus"][name]["package"], bundle[name][kind], active[name], name) for name in order]
        for package, artifact, previous, label in changes:
            if previous and previous["sha256"] == artifact["sha256"]:
                journal.step(package=package, stage="already_matches", sha256=artifact["sha256"])
                continue
            # Recheck target and active APK immediately before EACH package mutation.
            check_identity(adb, profile)
            fresh = backup_package(adb, package, journal.directory / "preinstall" / (label + ".apk"),
                                   optional=previous is None)
            require(fresh == previous, "Installed package changed after preflight; stopped: " + package)
            require(free_bytes(adb.shell("df", "/data")) >= 100 * 1024**2, "Storage changed after preflight")
            install_one(adb, package, artifact, journal, label)
        journal.data["status"] = "verified"
        journal.save()
        return journal
    except (Exception, KeyboardInterrupt) as error:
        journal.data.update(status="stopped", error=str(error))
        journal.save()
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("capture", "install-apps", "install", "restore"):
        command = sub.add_parser(name)
        command.add_argument("--serial", required=True, help="Exact serial from adb devices -l")
        command.add_argument("--adb", default="adb", help="ADB executable path")
        if name == "capture":
            command.add_argument("--out", required=True, type=Path, help="New private capture directory")
            command.add_argument("--bundle", type=Path, help="Own validated bundle, to recognize already modified menu APKs")
        else:
            command.add_argument("--backup", required=True, type=Path, help="NEW private backup directory for every attempt")
            command.add_argument("--parked", action="store_true", help="Confirm vehicle is parked and screen can be checked")
            command.add_argument("--check", action="store_true", help="Read-only preflight and backups; do not install")
            if name in ("install", "restore"):
                command.add_argument("--bundle", required=True, type=Path, help="Own locally generated, reviewed menu bundle")
    args = parser.parse_args(argv)
    try:
        if args.action != "capture":
            require(args.parked or args.check, "Installation requires --parked; use --check for read-only preflight")
        profile = load_profile()
        bundle = load_bundle(args.bundle, profile) if getattr(args, "bundle", None) else None
        apps = load_apps() if args.action in ("install", "install-apps") else None
        adb = Adb(args.serial, args.adb)
        if args.action == "capture":
            journal = capture(adb, profile, args.out, bundle)
        else:
            journal = change(adb, profile, args.action, args.backup, bundle, apps, args.check)
        print(journal.data["status"] + ": " + str(journal.path))
        if args.action == "install" and not args.check:
            print("Now leave the menus, return HOME, then All menus > page 2 > Information > My Apps.")
            print("Check that both My Apps and N Dashboard open; also verify reopening after a normal reboot while parked.")
        if args.action == "install-apps" and not args.check:
            print("Apps installed. Open My Apps now while the PC is connected:")
            print(shlex.join([args.adb, "-s", args.serial, "shell", "am", "start", "-n",
                              "com.veloster.carplayhub/.HubActivity"]))
            print("In My Apps, enable Quick entry (실행 바로가기) and check that its shortcut opens the app outside this screen.")
            print("Also verify reopening after a normal reboot while parked. Installation alone adds no factory home icon.")
        return 0
    except (Stopped, OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired, KeyboardInterrupt) as error:
        print("STOPPED: " + str(error), file=sys.stderr)
        if args.action in ("install", "restore"):
            print("Keep the bundle and backup receipt. No blind rollback or reboot was attempted.", file=sys.stderr)
            print("For an interrupted menu install, use restore with the SAME bundle and a NEW --backup directory; see docs/MENU_ENTRY.md.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
