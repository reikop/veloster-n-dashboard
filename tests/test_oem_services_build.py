"""An isolated OEM pin changes only a compile-time expectation, preserving the default source."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('oem_services_build', ROOT / 'scripts/oem_services_build.py')
inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inputs)
TEMPLATE = ROOT / 'apps/hub/src/com/veloster/carplayhub/HubApplication.java'
DEFAULT = '03c4a6112aac92b1cbb1c34f11684deca2f54849e352b1d563278a71457915c9'


class OemServicesBuild(unittest.TestCase):
    def test_default_preserves_current_hub_input(self):
        raw = TEMPLATE.read_bytes()
        generated, pin = inputs.source(raw)
        self.assertIsNone(generated)
        self.assertEqual(pin, DEFAULT)
        self.assertEqual(TEMPLATE.read_bytes(), raw)

    def test_isolated_variant_changes_only_the_literal(self):
        raw = TEMPLATE.read_bytes()
        pin = 'a7' * 32
        generated, selected = inputs.source(raw, pin)
        self.assertEqual(selected, pin)
        self.assertEqual(generated.replace(pin.encode(), DEFAULT.encode()), raw)
        self.assertEqual(TEMPLATE.read_bytes(), raw)

    def test_malformed_or_injected_value_is_refused(self):
        raw = TEMPLATE.read_bytes()
        for pin in ('0' * 64, 'A7' * 32, 'ab' * 31, 'ab' * 33, 'x' * 64,
                    '"; static { throw new Error(); } //'):
            with self.subTest(pin=pin), self.assertRaises(ValueError):
                inputs.source(raw, pin)

    def test_nonhub_override_is_refused(self):
        inputs.validate('dashboard', None)
        with self.assertRaisesRegex(ValueError, 'only available for Hub'):
            inputs.validate('dashboard', 'a7' * 32)

    def test_missing_or_ambiguous_field_is_refused(self):
        for raw in (b'// no pin field', TEMPLATE.read_bytes() * 2):
            with self.assertRaises(ValueError):
                inputs.source(raw, 'a7' * 32)


if __name__ == '__main__':
    unittest.main()
