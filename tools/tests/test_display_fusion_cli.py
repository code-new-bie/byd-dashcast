"""No firmware fixtures; validate only the new CLI's safe input/output boundaries."""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("fusion_cli", TOOLS / "build_display_fusion_components.py")
cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)


class FusionCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inputs = [self.root / name for name in ("hmi", "kzb", "toc", "receiver")]
        for path in self.inputs: path.write_bytes(b"synthetic-not-firmware")

    def test_new_output_is_not_created_by_validation(self):
        result = cli.checked_paths(*self.inputs, self.root / "new")
        self.assertEqual(self.root / "new", result[-1]); self.assertFalse(result[-1].exists())

    def test_missing_and_empty_receiver_are_refused(self):
        for receiver in (self.root / "missing", self.inputs[-1]):
            if receiver.exists(): receiver.write_bytes(b"")
            with self.assertRaises(ValueError): cli.checked_paths(*self.inputs[:3], receiver, self.root / "new")

    def test_existing_output_or_missing_parent_preserves_inputs(self):
        for out in (self.root, self.root / "missing" / "new"):
            with self.assertRaises(ValueError): cli.checked_paths(*self.inputs, out)
        self.assertTrue(all(path.read_bytes() == b"synthetic-not-firmware" for path in self.inputs))
