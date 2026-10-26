"""Catalog coverage and generated-source checks; no Android or vehicle required."""
import importlib.util
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class TranslationTests(unittest.TestCase):
    def test_generated_catalog_matches_sources(self):
        spec = importlib.util.spec_from_file_location("translations", ROOT / "scripts/generate_translations.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(module.generated(), module.TARGET.read_text(encoding="utf-8"))

    def test_every_literal_translation_has_english(self):
        catalog = json.loads((ROOT / "translations/en.json").read_text(encoding="utf-8"))
        for name in ("apps", "shared/src"):
            for file in (ROOT / name).rglob("*.java"):
                for match in re.finditer(r'UiText\.t\(("(?:\\.|[^"\\])*")\)', file.read_text(encoding="utf-8")):
                    key = json.loads(match[1])
                    self.assertIn(key, catalog, str(file) + ": " + key)
        for key, value in catalog.items():
            self.assertTrue(value.strip(), key)
            self.assertIsNone(re.search("[가-힣]", value), key)
        korean = json.loads((ROOT / "translations/ko.json").read_text(encoding="utf-8"))
        self.assertTrue(set(korean) <= set(catalog))

    def test_launcher_labels_have_system_locale_resources(self):
        for app, english, korean in (("hub", "My Apps", "내 앱"), ("dashboard", "N Dashboard", "N 계기판")):
            base = ROOT / "apps" / app
            manifest = ET.parse(base / "AndroidManifest.xml").getroot()
            self.assertEqual(manifest.find("application").get("{http://schemas.android.com/apk/res/android}label"), "@string/app_name")
            for folder, expected in (("values", english), ("values-ko", korean)):
                strings = ET.parse(base / "res" / folder / "strings.xml").getroot()
                self.assertEqual(strings.find("string[@name='app_name']").text, expected)


if __name__ == "__main__":
    unittest.main()
