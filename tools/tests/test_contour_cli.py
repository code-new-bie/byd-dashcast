"""Public source/path checks; no private firmware or generated native bitmaps shipped."""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
spec=importlib.util.spec_from_file_location('contour_cli',ROOT/'tools/build_contour_components.py')
cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)


class ContourCliTest(unittest.TestCase):
    def test_unknown_outputs_cannot_overwrite_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);inputs=[root/n for n in ('hmi','kzb','toc','receiver')]
            for p in inputs:p.write_bytes(b'not-firmware')
            cli.checked_paths(*inputs,root/'new')
            for target in (root,root/'missing'/'new'):
                with self.assertRaises(ValueError):cli.checked_paths(*inputs,target)
            self.assertTrue(all(p.read_bytes()==b'not-firmware' for p in inputs))

    def test_contour_uses_original_mask_and_palette_not_vehicle_data_or_new_shader(self):
        source=(ROOT/'analysis/contour_layout.py').read_text('utf-8')
        for token in ('mask_%02d.png', 'Color Brush_BgBottom_', "'Kanzi.Image2D'", 'build_soft_edge_kzb(original, toc)'):
            self.assertIn(token,source)
        self.assertNotIn('write_bytes',source)
        self.assertNotIn('TextBlockConcept.Text\'',source)
        self.assertIn('media_guard_query_role_byte_exact',source)

    def test_pts_guard_and_primary_source_profile_are_preserved(self):
        base=ROOT/'phase1/android/app/src/main/java/com/byd/dashcast/phase1'
        self.assertIn('DCV1_NON_MONOTONIC_OR_GAPPED_PTS',(base/'NoAuthVideoClient.java').read_text('utf-8'))
        self.assertIn('getRealMetrics(metrics)',(base/'BusinessDisplayProfile.kt').read_text('utf-8'))
        self.assertIn('minimumSourceTimestampNanos',(base/'CenterFrameRenderer.kt').read_text('utf-8'))
        self.assertIn('SafeSettingsNavigator.open',(base/'ProductActivity.kt').read_text('utf-8'))
