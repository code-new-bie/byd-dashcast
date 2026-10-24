"""Portable source/path checks only; real firmware is not part of public tests."""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"tools"))
spec=importlib.util.spec_from_file_location("soft_cli",ROOT/"tools/build_soft_edge_components.py")
cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)


class SoftEdgeCliTest(unittest.TestCase):
    def test_existing_output_or_missing_parent_cannot_overwrite_originals(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);inputs=[root/n for n in ("hmi","kzb","toc","receiver")]
            for p in inputs:p.write_bytes(b"not-firmware")
            cli.checked_paths(*inputs,root/"new")
            self.assertFalse((root/"new").exists())
            for out in (root,root/"missing"/"new"):
                with self.assertRaises(ValueError):cli.checked_paths(*inputs,out)
            self.assertTrue(all(p.read_bytes()==b"not-firmware" for p in inputs))

    def test_wide_edges_compose_frozen_compact_layout_instead_of_rewriting_vehicle_data(self):
        source=(ROOT/"analysis/soft_edge_layout.py").read_text("utf-8")
        self.assertIn("FULL_INNER = (192, 144, 1536, 384)",source)
        self.assertIn("build_compact_info_kzb(original, toc)",source)
        self.assertIn("return prior_rectangles(True)",source)
        self.assertNotIn('"Node.Visible"',source)
        self.assertNotIn("write_bytes",source)

    def test_current_busy_path_services_same_owner_and_preserves_guarded_recovery(self):
        source=(ROOT/"phase1/android/app/src/main/java/com/byd/dashcast/phase1/NoAuthVideoClient.java").read_text("utf-8")
        frame=source.split("void frame(byte[] annexB, long ptsMicros)",1)[1].split("private void heartbeat",1)[0]
        self.assertIn("maintain();",frame)
        builder=(ROOT/"tools/build_soft_edge_components.py").read_text("utf-8")
        self.assertIn("build_cleanup_guard_receiver(receiver)",builder)
        self.assertIn("build_soft_edge_receiver(receiver)",builder)
