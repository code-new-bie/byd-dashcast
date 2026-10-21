"""Public CLI boundary checks use synthetic empty fixtures, never original firmware."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "build_navigation_components.py"
spec = importlib.util.spec_from_file_location("navigation_cli", SOURCE)
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class NavigationCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.files = [self.root / name for name in ("hmi", "kzb", "toc")]
        for path in self.files: path.write_bytes(b"synthetic-not-firmware")

    def test_new_bounded_paths_are_returned_without_touching_inputs(self):
        result = cli.prepare_paths(*self.files, self.root / "new")
        self.assertEqual(tuple(self.files + [self.root / "new"]), result)
        self.assertFalse(result[-1].exists())
        self.assertTrue(all(path.read_bytes() == b"synthetic-not-firmware" for path in self.files))

    def test_existing_output_and_missing_input_or_parent_are_refused(self):
        for output in (self.root, self.root / "missing" / "new"):
            with self.assertRaises(ValueError): cli.prepare_paths(*self.files, output)
        with self.assertRaises(ValueError): cli.prepare_paths(self.root / "absent", *self.files[1:], self.root / "new")

    def test_empty_component_is_refused(self):
        self.files[1].write_bytes(b"")
        with self.assertRaises(ValueError): cli.prepare_paths(*self.files, self.root / "new")
