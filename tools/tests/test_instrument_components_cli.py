"""One-run instrument CLI validation; no private firmware or native dependencies needed."""
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "tools/build_instrument_components.py"


class InstrumentComponentsCliTests(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, "-B", CLI, *map(str, args)], capture_output=True, text=True, timeout=30)

    def inputs(self, folder):
        base = folder / "base.zip"
        with zipfile.ZipFile(base, "w") as archive:
            for name in ("app.sv", "os.sv", "mcu.sv", "RcAppVersion", "kzbVersion", "version_svp"):
                archive.writestr("release/" + name, b"not-" + name.encode())
        paths = {"base": base}
        for name in ("native-usb", "native-receiver"):
            paths[name] = folder / name
            paths[name].write_bytes(b"not-" + name.encode())
        return paths

    def test_help_names_all_inputs_and_component_only_scope(self):
        result = self.run_cli("--help")
        self.assertEqual(0, result.returncode, result.stderr)
        for name in ("--base", "--native-usb", "--native-receiver", "--output"):
            self.assertIn(name, result.stdout)
        self.assertIn("NOT a flash ZIP", result.stdout)

    def test_wrong_base_preserves_inputs_and_creates_no_output(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            paths = self.inputs(folder)
            before = {name: path.read_bytes() for name, path in paths.items()}
            output = folder / "output"
            args = [item for name, path in paths.items() for item in ("--" + name, path)]
            result = self.run_cli(*args, "--output", output)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Not the pinned base firmware", result.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(before, {name: path.read_bytes() for name, path in paths.items()})

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            paths = self.inputs(folder)
            output = folder / "output"
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
