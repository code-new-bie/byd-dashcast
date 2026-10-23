"""No firmware inputs: bounded paths, geometry and source-level presentation rules."""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "analysis"))
spec = importlib.util.spec_from_file_location("integrated_cli", ROOT / "tools/build_integrated_components.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class IntegratedCliTest(unittest.TestCase):
    def test_paths_are_validated_without_creating_or_touching_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            paths = [root / name for name in ("hmi", "kzb", "toc", "receiver")]
            for path in paths:
                path.write_bytes(b"not-firmware")
            cli.checked_paths(*paths, root / "new")
            self.assertFalse((root / "new").exists())
            with self.assertRaises(ValueError):
                cli.checked_paths(*paths, root)
            self.assertTrue(all(path.read_bytes() == b"not-firmware" for path in paths))

    def test_source_has_no_global_warning_hide_or_new_vehicle_state(self):
        source = (ROOT / "analysis/integrated_layout.py").read_text("utf-8")
        self.assertNotIn('("LaneDeparture", "Node.Opacity"', source)
        self.assertNotIn('("CarDoorAndSafe", "Node.Opacity"', source)
        self.assertIn('0x52575634', source)
        self.assertIn('FULL_INNER = (96, 144, 1728, 384)', source)
        self.assertIn('"DCIdle", 0, []', source)

    def test_existing_output_or_missing_parent_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            paths = [root / name for name in ("hmi", "kzb", "toc", "receiver")]
            for path in paths:
                path.write_bytes(b"fixture")
            with self.assertRaises(ValueError):
                cli.checked_paths(*paths, root / "absent" / "new")
