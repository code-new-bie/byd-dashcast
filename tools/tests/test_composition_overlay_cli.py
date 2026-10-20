"""Synthetic file-boundary tests; no firmware/SDK or vehicle needed."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("composition_cli", ROOT / "tools/build_composition_components.py")
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


class CompositionCliTests(unittest.TestCase):
    def setUp(self):
        self.parent = ROOT / "build"; self.parent.mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix="owned-composition-path-", dir=self.parent)
        self.path = Path(self.tmp.name).resolve()
        self.receiver = self.path / "receiver-input"
        self.config = self.path / "application.cfg"
        with self.receiver.open("xb") as stream: stream.truncate(79808)
        self.config.write_bytes(b"synthetic")
        self.output = self.path / "new-output"

    def tearDown(self):
        self.assertTrue(self.path.is_relative_to(self.parent.resolve()))
        self.tmp.cleanup()

    def test_new_paths_accepted_without_output_write(self):
        self.assertEqual((self.receiver, self.config, self.output),
                         CLI.prepare_paths(self.receiver, self.config, self.output))
        self.assertFalse(self.output.exists())

    def test_existing_output_refused(self):
        self.output.mkdir()
        with self.assertRaises(ValueError): CLI.prepare_paths(self.receiver, self.config, self.output)

    def test_missing_or_wrong_size_receiver_refused(self):
        with self.assertRaises(ValueError): CLI.prepare_paths(self.path / "missing", self.config, self.output)
        with self.receiver.open("r+b") as stream: stream.truncate(1)
        with self.assertRaises(ValueError): CLI.prepare_paths(self.receiver, self.config, self.output)

    def test_empty_or_oversized_config_refused(self):
        for size in (0, 4097):
            with self.config.open("r+b") as stream: stream.truncate(size)
            with self.assertRaises(ValueError): CLI.prepare_paths(self.receiver, self.config, self.output)

    def test_missing_parent_refused(self):
        with self.assertRaises(ValueError):
            CLI.prepare_paths(self.receiver, self.config, self.path / "missing" / "output")
