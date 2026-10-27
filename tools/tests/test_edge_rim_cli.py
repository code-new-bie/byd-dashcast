"""Exercise preflight without private firmware or Pillow in the source-only CI."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class EdgeRimCliTest(unittest.TestCase):
    def test_help_needs_no_native_runtime_or_firmware(self):
        result = subprocess.run([sys.executable, '-B', ROOT/'tools/build_edge_rim_components.py', '--help'],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--scene', result.stdout)

    def test_wrong_toc_is_rejected_before_output_or_native_dependency_import(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            command = [sys.executable, '-B', ROOT/'tools/build_edge_rim_components.py']
            for name in ('hmi', 'kzb', 'scene', 'toc', 'receiver'):
                path = base/name
                path.write_bytes(b'not-a-firmware-'+name.encode())
                command += ['--'+name, path]
            out = base/'output'
            result = subprocess.run([*command, '--output', out], capture_output=True, text=True, timeout=20)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Wrong original TOC', result.stderr)
            self.assertFalse(out.exists())
            self.assertEqual(b'not-a-firmware-toc', (base/'toc').read_bytes())

if __name__ == '__main__':
    unittest.main()
