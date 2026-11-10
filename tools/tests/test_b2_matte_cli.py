"""B2 matched-component CLI validation before loading native dependencies."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "tools/build_b2_matte_components.py"


class B2MatteCliTests(unittest.TestCase):
    def test_help_explains_component_pair_and_no_flash_zip(self):
        result = subprocess.run([sys.executable, "-B", CLI, "--help"], capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--masks", result.stdout)
        self.assertIn("NOT a flash ZIP", result.stdout)

    def test_wrong_pair_preserves_inputs_and_does_not_create_output(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            hmi, kzb, output = base / "hmi", base / "kzb", base / "output"
            hmi.write_bytes(b"not-the-hmi")
            kzb.write_bytes(b"not-the-kzb")
            result = subprocess.run([sys.executable, "-B", CLI, "--hmi", hmi, "--kzb", kzb, "--output", output],
                                    capture_output=True, text=True, timeout=15)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Exact matching", result.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(b"not-the-hmi", hmi.read_bytes())
            self.assertEqual(b"not-the-kzb", kzb.read_bytes())

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            hmi, kzb, output = base / "hmi", base / "kzb", base / "output"
            hmi.write_bytes(b"hmi")
            kzb.write_bytes(b"kzb")
            output.mkdir()
            marker = output / "keep"
            marker.write_bytes(b"keep")
            result = subprocess.run([sys.executable, "-B", CLI, "--hmi", hmi, "--kzb", kzb, "--output", output],
                                    capture_output=True, text=True, timeout=15)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("new output directory", result.stderr)
            self.assertEqual(b"keep", marker.read_bytes())
