"""Public source-only safety contracts, no firmware/log inputs."""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT / "tools"))
spec=importlib.util.spec_from_file_location("recovery_cli",ROOT / "tools/build_cleanup_recovery_components.py")
cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)


class RecoveryCliTest(unittest.TestCase):
    def test_no_output_or_input_mutation_during_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);inputs=[root/n for n in ("hmi","kzb","toc","receiver")]
            for p in inputs:p.write_bytes(b"fixture")
            cli.checked_paths(*inputs,root / "new")
            self.assertFalse((root / "new").exists())
            with self.assertRaises(ValueError):cli.checked_paths(*inputs,root)
            self.assertTrue(all(p.read_bytes()==b"fixture" for p in inputs))

    def test_guard_is_pinned_and_query_never_clears_native_error(self):
        src=(ROOT / "analysis/cleanup_guard_native.py").read_text("utf-8")
        self.assertIn('sticky_flag_never_written=True',src)
        self.assertIn('guarded_query=dict(operation=0, nonce=0, hold_marker=1)',src)
        self.assertNotIn('rm -',src)
        self.assertIn('raw(HELPER, 192) != NOP*48',src)

    def test_incident_is_diagnostic_not_authorization_and_hard_local_guard_remains(self):
        base=ROOT / "phase1/android/app/src/main/java/com/byd/dashcast/phase1"
        src=(base / "RecoveryIncidentStore.kt").read_text("utf-8")
        self.assertIn('HISTORICAL_DIAGNOSTIC_NOT_CURRENT_STATE',src)
        policy=(base / "CleanupRecoveryPolicy.kt").read_text("utf-8")
        self.assertIn('!captureClosed || !usbClosed || partialOpenUnknown',policy)
        recovery=(base / "DetachedPeerRecovery.kt").read_text("utf-8")
        self.assertIn('putInt(36, 1)',recovery)
        self.assertIn('PEER_RECOVERY_NATIVE_CLEANUP_FAULT',recovery)
