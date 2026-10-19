"""CLI path checks use only synthetic fixtures; no native SDK/dependencies required."""
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.apply_f1_display_overlay import prepare_paths


class F1OverlayPathTests(unittest.TestCase):
    def setUp(self):
        parent = ROOT / "build/public-tool-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="f1-overlay-", dir=parent)
        self.root = Path(self.temp.name).resolve()
        self.source = self.root / "synthetic.arm"
        self.source.write_bytes(bytes(79808))
        self.output = self.root / "fresh.arm"

    def tearDown(self):
        self.assertTrue(self.root.is_relative_to((ROOT / "build/public-tool-tests").resolve()))
        self.temp.cleanup()

    def test_fresh_paths_are_validated_without_importing_native_dependencies(self):
        source, output, report = prepare_paths(self.source, self.output)
        self.assertEqual(self.source, source)
        self.assertEqual(self.output, output)
        self.assertEqual(self.root / "fresh.arm.json", report)
        self.assertFalse(output.exists())

    def test_existing_output_is_never_overwritten(self):
        self.output.write_bytes(b"preserve")
        with self.assertRaises(ValueError):
            prepare_paths(self.source, self.output)
        self.assertEqual(b"preserve", self.output.read_bytes())

    def test_existing_report_is_never_overwritten(self):
        report = self.output.with_suffix(".arm.json")
        report.write_text("preserve", encoding="utf-8")
        with self.assertRaises(ValueError):
            prepare_paths(self.source, self.output)
        self.assertEqual("preserve", report.read_text(encoding="utf-8"))

    def test_wrong_size_missing_input_and_missing_parent_are_rejected(self):
        with self.assertRaises(ValueError):
            prepare_paths(self.root / "missing.arm", self.output)
        with self.assertRaises(ValueError):
            prepare_paths(self.source, self.root / "missing/fresh.arm")
        self.source.write_bytes(b"not a receiver")
        with self.assertRaises(ValueError):
            prepare_paths(self.source, self.output)

    def test_input_cannot_also_be_output(self):
        with self.assertRaises(ValueError):
            prepare_paths(self.source, self.source)


if __name__ == "__main__":
    unittest.main()
