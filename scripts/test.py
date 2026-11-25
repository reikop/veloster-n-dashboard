#!/usr/bin/env python3
"""Run synthetic host tests without Android, private logs, network or ADB."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    found = shutil.which(args.java)
    if not found:
        raise SystemExit("JDK 17 java is required")
    java = Path(found).resolve()
    javac = java.with_name("javac.exe" if os.name == "nt" else "javac")
    hub = ROOT / "apps/hub/src/com/veloster/carplayhub"
    sources = [ROOT / "shared/src/com/veloster/vehicle/Telemetry.java",
               ROOT / "shared/src/com/veloster/i18n/UiText.java",
               ROOT / "apps/dashboard/src/com/veloster/dashboard/HscanFrameDecoder.java"]
    neon = ROOT / "apps/neondrive/src/com/veloster/neondrive"
    sources += [neon / (name + ".java") for name in ("DriveSignals", "DriveModel", "HscanFrames", "CarPaint", "Theme", "SolarClock", "PixelFont")]
    sources += [hub / (name + ".java") for name in ("RadioTrial", "BluetoothSession", "RebootRequest", "QuickEntryPolicy")]
    tests = sorted((ROOT / "tests").glob("*Test.java"))
    sources += [ROOT / "shared/src/com/veloster/diagnostics/ErrorLog.java", ROOT / "shared/src/com/veloster/vehicle/DashboardPage.java",
                ROOT / "shared/src/com/veloster/vehicle/TaxiMeter.java"]
    # Pure-Java parts ported from the i30 PD build (sung9155/i30-pd-dashboard, MIT) and their tests.
    sources += [ROOT / "shared/src/com/veloster/vehicle" / (name + ".java") for name in
                ("VehicleProfile", "ShiftGuide", "Theme", "Records", "SettingsBackup", "DriveStats", "GearRatios", "ShiftCoach", "RevMatch", "DayNight", "DayPhase", "Tiles", "TaxiTariff", "TaxiZones", "TaxiZoneData", "DriveModes", "SpeedChime")]
    # 설정 → 글꼴: the font reader and the system font change (FontManager runs it on the head unit).
    sources += [ROOT / "shared/src/com/veloster/vehicle" / (name + ".java") for name in ("FontFile", "SystemFonts", "BundledFonts")]
    sources += tests
    with tempfile.TemporaryDirectory(prefix="vn-public-tests-") as temporary:
        subprocess.run([str(javac), "-encoding", "UTF-8", "-d", temporary,
                        *map(str, sources)], check=True)
        for test in tests:
            cls = ("com.veloster.dashboard." if test.stem == "HscanFrameDecoderTest" else "") + test.stem
            subprocess.run([str(java), "-ea", "-Dvn.root=" + str(ROOT), "-cp", temporary, cls], check=True)
    print("PASS: " + str(len(tests)) + " synthetic host test programs. Vehicle operation is outside this scope.")


if __name__ == "__main__":
    main()
