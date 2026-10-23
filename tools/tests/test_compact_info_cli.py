"""Public source-only compact-info contracts; no firmware or private font input."""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
spec = importlib.util.spec_from_file_location("compact_cli", ROOT / "tools/build_compact_info_components.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class CompactInfoCliTest(unittest.TestCase):
    def test_existing_output_and_missing_parent_are_refused_without_touching_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            inputs = [root / n for n in ("hmi", "kzb", "toc", "receiver")]
            for p in inputs:
                p.write_bytes(b"not-firmware")
            cli.checked_paths(*inputs, root / "new")
            self.assertFalse((root / "new").exists())
            for out in (root, root / "absent" / "new"):
                with self.assertRaises(ValueError):
                    cli.checked_paths(*inputs, out)
            self.assertTrue(all(p.read_bytes() == b"not-firmware" for p in inputs))

    def test_only_full_presentation_is_overridden_not_original_values_or_visibility(self):
        source = (ROOT / "analysis/compact_info_layout.py").read_text("utf-8")
        self.assertIn('MILEAGE_BOX = (1410, 628, 415 * .70, 60 * .70)', source)
        self.assertIn('CENTER_RULES', source)
        self.assertNotIn('"Node.Visible"', source)
        self.assertNotIn('"TextBlockConcept.Text"', source)
        self.assertNotIn('write_bytes', source)

    def test_previous_integrated_builder_retains_unchanged_default_call(self):
        source = (ROOT / "analysis/integrated_layout.py").read_text("utf-8")
        self.assertIn('build_viewport_kzb(original, toc, shape_factory=rectangles)', source)
        self.assertNotIn('from compact_info_layout', source)
