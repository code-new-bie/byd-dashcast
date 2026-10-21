"""Synthetic 1.0.1 CLI boundaries, without firmware, keys or vehicle."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("unlimited_cli", ROOT / "tools/build_unlimited_components.py")
CLI = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(CLI)


class UnlimitedCliTests(unittest.TestCase):
    def setUp(self):
        parent = ROOT / "build"; parent.mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix="owned-unlimited-input-", dir=parent)
        self.path = Path(self.tmp.name).resolve()
        self.receiver, self.hmi, self.output = self.path / "receiver", self.path / "hmi", self.path / "output"
        with self.receiver.open("xb") as stream: stream.truncate(79808)
        self.hmi.write_bytes(b"synthetic")
    def tearDown(self): self.tmp.cleanup()
    def test_new_paths_checked_without_creating_output(self):
        self.assertEqual((self.receiver, self.hmi, self.output), CLI.prepare_paths(self.receiver, self.hmi, self.output))
        self.assertFalse(self.output.exists())
    def test_existing_output_refused(self):
        self.output.mkdir()
        with self.assertRaises(ValueError): CLI.prepare_paths(self.receiver, self.hmi, self.output)
    def test_size_and_missing_inputs_refused(self):
        for size in (0, 79807, 79809):
            with self.receiver.open("r+b") as stream: stream.truncate(size)
            with self.assertRaises(ValueError): CLI.prepare_paths(self.receiver, self.hmi, self.output)
    def test_empty_or_excessive_hmi_refused(self):
        for size in (0, 16 * 1024 * 1024 + 1):
            with self.hmi.open("r+b") as stream: stream.truncate(size)
            with self.assertRaises(ValueError): CLI.prepare_paths(self.receiver, self.hmi, self.output)
    def test_missing_parent_refused(self):
        with self.assertRaises(ValueError): CLI.prepare_paths(self.receiver, self.hmi, self.path / "absent/output")


if __name__ == "__main__": unittest.main()
