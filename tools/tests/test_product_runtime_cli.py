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

    def test_withdrawn_command_cannot_create_an_output_or_modify_any_input(self):
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
            self.assertIn('WITHDRAWN 1.14.0', result.stderr)
            self.assertFalse(out.exists())
            for name in ('hmi', 'kzb', 'scene', 'toc', 'receiver'):
                self.assertEqual(b'invalid-'+name.encode(), (base/name).read_bytes())

if __name__ == '__main__':
    unittest.main()
