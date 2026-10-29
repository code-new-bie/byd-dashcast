"""Native clear CLI rejects wrong input before any mutation or private imports."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]


class NativeClearCliTests(unittest.TestCase):
    def test_help_without_native_dependencies_or_private_firmware(self):
        result=subprocess.run([sys.executable,'-B',ROOT/'tools/build_native_clear_components.py','--help'],
            capture_output=True,text=True,timeout=20)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertIn('--scene',result.stdout)

    def test_invalid_toc_preserves_every_input_and_creates_no_output(self):
        with tempfile.TemporaryDirectory() as folder:
            base=Path(folder);command=[sys.executable,'-B',ROOT/'tools/build_native_clear_components.py']
            for name in ('hmi','kzb','scene','toc','receiver'):
                path=base/name;path.write_bytes(b'invalid-'+name.encode());command+=['--'+name,path]
            output=base/'output'
            result=subprocess.run([*command,'--output',output],capture_output=True,text=True,timeout=20)
            self.assertNotEqual(0,result.returncode)
            self.assertIn('Wrong original TOC',result.stderr)
            self.assertFalse(output.exists())
            for name in ('hmi','kzb','scene','toc','receiver'):
                self.assertEqual(b'invalid-'+name.encode(),(base/name).read_bytes())
