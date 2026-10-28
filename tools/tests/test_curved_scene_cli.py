import importlib.util
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class CurvedSceneCliTest(unittest.TestCase):
    def test_scene_input_is_bounded_and_output_cannot_cover_it(self):
        spec = importlib.util.spec_from_file_location("curved_cli", ROOT / "tools/build_curved_scene_components.py")
        module = importlib.util.module_from_spec(spec)
        import sys
        sys.path.insert(0, str(ROOT / "tools"))
        try:
            spec.loader.exec_module(module)
            with tempfile.TemporaryDirectory() as directory:
                base = Path(directory).resolve()
                source = base / "scene"
                source.write_bytes(b"source")
                self.assertEqual(source, module.checked_scene(source, base / "out"))
                for output in (source, base):
                    with self.assertRaises(ValueError):
                        module.checked_scene(source, output)
                self.assertEqual(b"source", source.read_bytes())
                with self.assertRaises(ValueError):
                    module.checked_scene(base / "absent", base / "out")
        finally:
            sys.path.pop(0)

    def test_resource_scope_is_plain_instances_and_empty_idle(self):
        source = (ROOT / "analysis/curved_scene_layout.py").read_text(encoding="utf-8")
        self.assertIn("PLAIN_TARGETS=('Viewport 2D/Car','Viewport 2D/Scene/RoadRoot/Plane')", source)
        self.assertIn("('DCIdle',0,[])", source)
        self.assertIn("digest(original)!=SCENE_SHA", source)
        self.assertNotIn("write_bytes", source)

    def test_current_sharp_source_covers_the_curved_aperture(self):
        source = next((ROOT / "phase1/android/app/src/main").rglob("ContentViewport.kt")).read_text(encoding="utf-8")
        spec = importlib.util.spec_from_file_location("expanded_geometry", ROOT / "analysis/expanded_rim_geometry.py")
        geometry = importlib.util.module_from_spec(spec)
        sys.path.insert(0, str(ROOT / "analysis"))
        try:
            spec.loader.exec_module(geometry)
            self.assertTrue(geometry.validate_expanded_carrier())
            x, y, width, height = geometry.FULL_INNER
            carrier = re.search(r'fun full\(\): ContentViewport = ContentViewport\(([^)]+)\)', source)
            self.assertIsNotNone(carrier)
            self.assertEqual((width, height, x, y, width, height),
                             tuple(int(value) for value in carrier[1].split(',')))
        finally:
            sys.path.pop(0)
        self.assertIn("intArrayOf(320, 176, 1280, 336)", source)
        self.assertIn("texture2D(padding", source)


if __name__ == "__main__":
    unittest.main()
