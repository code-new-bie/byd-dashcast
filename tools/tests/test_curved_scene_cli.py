import importlib.util
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

    def test_carrier_and_safe_bounds_are_distinct(self):
        source = next((ROOT / "phase1/android/app/src/main").rglob("ContentViewport.kt")).read_text(encoding="utf-8")
        self.assertIn("1792", source)
        self.assertIn("448", source)
        self.assertIn("intArrayOf(256, 152, 1408, 352)", source)


if __name__ == "__main__":
    unittest.main()
