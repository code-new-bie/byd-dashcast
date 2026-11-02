"""One-run instrument CLI validation; no private firmware or native dependencies needed."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "tools/build_instrument_components.py"


class InstrumentComponentsCliTests(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, "-B", CLI, *map(str, args)], capture_output=True, text=True, timeout=30)

    def inputs(self, base):
        paths = {}
        for name in ("hmi", "kzb", "scene", "toc", "receiver", "config"):
            paths[name] = base / name
            paths[name].write_bytes(b"not-" + name.encode())
        return paths

    def test_help_names_all_inputs_and_component_only_scope(self):
        result = self.run_cli("--help")
        self.assertEqual(0, result.returncode, result.stderr)
        for name in ("--hmi", "--kzb", "--scene", "--toc", "--receiver", "--config", "--output"):
            self.assertIn(name, result.stdout)
        self.assertIn("NOT a flash ZIP", result.stdout)

    def test_wrong_originals_preserve_inputs_and_create_no_output(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            paths = self.inputs(base)
            output = base / "output"
            args = [item for name, path in paths.items() for item in ("--" + name, path)]
            result = self.run_cli(*args, "--output", output)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Wrong original TOC", result.stderr)
            self.assertFalse(output.exists())
            for name, path in paths.items():
                self.assertEqual(b"not-" + name.encode(), path.read_bytes())

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            paths = self.inputs(base)
            output = base / "output"
            output.mkdir()
            marker = output / "keep"
            marker.write_bytes(b"keep")
            args = [item for name, path in paths.items() for item in ("--" + name, path)]
            result = self.run_cli(*args, "--output", output)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("NEW output directory", result.stderr)
            self.assertEqual(b"keep", marker.read_bytes())


if __name__ == "__main__":
    unittest.main()
