"""No firmware: fail-closed paths and effective geometry invariants."""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "analysis"))
spec = importlib.util.spec_from_file_location("viewport_cli", ROOT / "tools/build_viewport_components.py")
cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)
from content_viewport import FULL_INNER, CENTER_INNER, FULL_SOURCE, CENTER_SOURCE, encoded_inner


class ViewportCliTest(unittest.TestCase):
    def test_content_aspect_not_outer_aspect(self):
        for source, inner in ((FULL_SOURCE, FULL_INNER), (CENTER_SOURCE, CENTER_INNER)):
            self.assertEqual(source[0] / source[1], inner[2] / inner[3])
        self.assertEqual((60, 40, 1800, 640), encoded_inner(True))
        self.assertEqual((96, 128, 1728, 432), encoded_inner(False))

    def test_existing_output_is_refused_without_touching_originals(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            paths = [root / n for n in ("hmi", "kzb", "toc", "receiver")]
            for path in paths: path.write_bytes(b"fixture-not-firmware")
            with self.assertRaises(ValueError): cli.checked_paths(*paths, root)
            self.assertTrue(all(p.read_bytes() == b"fixture-not-firmware" for p in paths))

    def test_missing_receiver_or_missing_output_parent_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            paths = [root / n for n in ("hmi", "kzb", "toc", "receiver")]
            for path in paths: path.write_bytes(b"fixture-not-firmware")
            with self.assertRaises(ValueError): cli.checked_paths(*paths[:3], root / "missing", root / "new")
            with self.assertRaises(ValueError): cli.checked_paths(*paths, root / "missing" / "new")

    def test_validation_does_not_create_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            paths = [root / n for n in ("hmi", "kzb", "toc", "receiver")]
            for path in paths: path.write_bytes(b"fixture-not-firmware")
            cli.checked_paths(*paths, root / "new")
            self.assertFalse((root / "new").exists())
