"""Reject wrong or destructive input paths before loading native dependencies."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "tools/patch_window_alpha_usage.py"


class UsageAlphaCliTests(unittest.TestCase):
    def test_help_without_firmware_or_native_dependencies(self):
        run = subprocess.run([sys.executable, "-B", CLI, "--help"],
                             capture_output=True, text=True, timeout=15)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertIn("NOT a flash ZIP", run.stdout)

    def test_wrong_input_leaves_both_input_and_output_untouched(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            source, output = base / "hmi", base / "result"
            source.write_bytes(b"not-the-exact-firmware")
            run = subprocess.run([sys.executable, "-B", CLI, "--hmi", source, "--output", output],
                                 capture_output=True, text=True, timeout=15)
            self.assertNotEqual(0, run.returncode)
            self.assertIn("Exact inspected", run.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(b"not-the-exact-firmware", source.read_bytes())

    def test_existing_output_and_input_ancestor_are_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            source, output = base / "hmi", base / "result"
            source.write_bytes(b"unchanged")
            output.mkdir()
            sentinel = output / "keep"
            sentinel.write_bytes(b"keep")
            for target in (output, base):
                run = subprocess.run([sys.executable, "-B", CLI, "--hmi", source, "--output", target],
                                     capture_output=True, text=True, timeout=15)
                self.assertNotEqual(0, run.returncode)
                self.assertIn("new output directory", run.stderr)
            self.assertEqual(b"keep", sentinel.read_bytes())
            self.assertEqual(b"unchanged", source.read_bytes())
