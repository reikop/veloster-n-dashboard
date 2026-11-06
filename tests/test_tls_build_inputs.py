"""Reject incomplete/native-key build inputs before any compiler or output directory."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
class TlsBuildInputs(unittest.TestCase):
    def test_bad_tls_inputs_stop_before_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);helper=root/'helper';ca=root/'ca.pem';notices=root/'NOTICE';out=root/'out'
            helper.write_bytes(b'not-elf');ca.write_bytes(b'-----BEGIN CERTIFICATE-----\nfixture');notices.write_text('License fixture')
            command=[sys.executable,str(ROOT/'scripts/build.py'),'hub','--android-jar','missing','--r8-jar','missing','--aapt2','missing','--out',str(out)]
            flags=['--tls-helper',str(helper),'--tls-ca',str(ca),'--tls-notices',str(notices)]
            r=subprocess.run(command+flags,capture_output=True,text=True);self.assertNotEqual(r.returncode,0);self.assertIn('ARM32 PIE',r.stderr);self.assertFalse(out.exists())
            elf=bytearray(52);elf[:6]=b'\x7fELF\x01\x01';elf[16:20]=b'\x03\0\x28\0';helper.write_bytes(elf)
            ca.write_bytes(b'-----BEGIN CERTIFICATE-----\nfixture\n-----BEGIN PRIVATE KEY-----\nsecret')
            r=subprocess.run(command+flags,capture_output=True,text=True);self.assertNotEqual(r.returncode,0);self.assertIn('Private keys',r.stderr);self.assertFalse(out.exists())
            r=subprocess.run(command+flags[:2],capture_output=True,text=True);self.assertNotEqual(r.returncode,0);self.assertIn('together',r.stderr);self.assertFalse(out.exists())
