"""Synthetic path tests, not target firmware execution."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bg_cli", ROOT / "tools/build_background_poc_components.py")
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


class BackgroundCliTests(unittest.TestCase):
    def setUp(self):
        self.parent = ROOT / "build"; self.parent.mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix="owned-bg-path-", dir=self.parent)
        self.path = Path(self.tmp.name).resolve()
        self.assertTrue(self.path.is_relative_to(self.parent.resolve()))
        self.hmi, self.receiver = self.path / "hmi-input", self.path / "receiver-input"
        for path, size in ((self.hmi, 11289671), (self.receiver, 79808)):
            with path.open("xb") as stream: stream.truncate(size)
        self.output = self.path / "new-output"

    def tearDown(self):
        self.assertTrue(self.path.is_relative_to(self.parent.resolve()))
        self.tmp.cleanup()

    def test_fresh_paths_accepted_without_writing(self):
        self.assertEqual((self.hmi, self.receiver, self.output), CLI.prepare_paths(self.hmi, self.receiver, self.output))
        self.assertFalse(self.output.exists())

    def test_existing_output_refused(self):
        self.output.mkdir()
        with self.assertRaises(ValueError): CLI.prepare_paths(self.hmi, self.receiver, self.output)

    def test_missing_or_truncated_input_refused(self):
        with self.hmi.open("r+b") as stream: stream.truncate(16)
        with self.assertRaises(ValueError): CLI.prepare_paths(self.hmi, self.receiver, self.output)
        with self.assertRaises(ValueError): CLI.prepare_paths(self.path / "missing", self.receiver, self.output)

    def test_missing_output_parent_refused(self):
        with self.assertRaises(ValueError):
            CLI.prepare_paths(self.hmi, self.receiver, self.path / "missing" / "output")
