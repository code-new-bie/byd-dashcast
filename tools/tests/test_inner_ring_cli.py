import ast
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class InnerRingCliTest(unittest.TestCase):
    def test_no_private_photo_archive_or_calibration_runtime_access(self):
        source=(ROOT/'analysis/inner_ring_refinement.py').read_text('utf-8')
        for node in ast.walk(ast.parse(source)):
            if isinstance(node,ast.Call):
                self.assertNotIn(getattr(node.func,'id',getattr(node.func,'attr','')),{'open','read_bytes','write_bytes','exec','eval'})
        for private in ('cv2','scipy','C:/Users','PHOTO_CALIBRATION.json'):
            self.assertNotIn(private,source)
    def test_validate_before_output_and_keep_exclusive_creation(self):
        source=(ROOT/'tools/build_inner_ring_components.py').read_text('utf-8')
        self.assertLess(source.index('checked_paths(a.hmi'),source.index('out.mkdir()'))
        self.assertLess(source.index('checked_scene(a.scene'),source.index('out.mkdir()'))
        self.assertIn("open('xb')",source);self.assertIn('vehicle_tested=False',source)
    def test_safe_guide_does_not_clip_video_and_curve_is_inside_sharp_source(self):
        import sys
        sys.path.insert(0,str(ROOT/'analysis'))
        from sharp_inner_geometry import FULL_INNER,FULL_SAFE,CURVES,validate_sharp_carrier
        self.assertNotEqual(FULL_INNER,FULL_SAFE)
        self.assertTrue(validate_sharp_carrier())
        self.assertEqual(553,CURVES[-1][-1][1])
if __name__=='__main__':unittest.main()
