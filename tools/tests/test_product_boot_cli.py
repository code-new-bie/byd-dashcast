"""Boot correction CLI preflight and nonmutation, without private firmware."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class ProductBootCliTests(unittest.TestCase):
    def test_help_does_not_need_private_inputs_or_native_dependencies(self):
        result = subprocess.run([sys.executable,'-B',ROOT/'tools/build_product_boot_components.py','--help'],
            capture_output=True,text=True,timeout=20)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertIn('--scene',result.stdout)

    def test_wrong_original_toc_is_rejected_without_output_or_input_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            command = [sys.executable,'-B',ROOT/'tools/build_product_boot_components.py']
            for name in ('hmi','kzb','scene','toc','receiver'):
                path = base/name
                path.write_bytes(b'invalid-'+name.encode())
                command += ['--'+name,path]
            out = base/'output'
            result = subprocess.run([*command,'--output',out],capture_output=True,text=True,timeout=20)
            self.assertNotEqual(0,result.returncode)
            self.assertIn('Wrong original TOC',result.stderr)
            self.assertFalse(out.exists())
            for name in ('hmi','kzb','scene','toc','receiver'):
                self.assertEqual(b'invalid-'+name.encode(),(base/name).read_bytes())
