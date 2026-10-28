"""RWVF entry preflight must not need firmware or a native SDK."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class ProductRuntimeCliTest(unittest.TestCase):
    def test_help_needs_no_native_dependency_or_private_input(self):
        result = subprocess.run([sys.executable, '-B', ROOT/'tools/build_product_runtime_components.py', '--help'],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--scene', result.stdout)

    def test_wrong_toc_cannot_create_an_output_or_modify_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            command = [sys.executable, '-B', ROOT/'tools/build_product_runtime_components.py']
            for name in ('hmi', 'kzb', 'scene', 'toc', 'receiver'):
                path = base/name
                path.write_bytes(b'invalid-'+name.encode())
                command += ['--'+name, path]
            out = base/'output'
            result = subprocess.run([*command, '--output', out], capture_output=True, text=True, timeout=20)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Wrong original TOC', result.stderr)
            self.assertFalse(out.exists())
            self.assertEqual(b'invalid-toc', (base/'toc').read_bytes())

if __name__ == '__main__':
    unittest.main()
