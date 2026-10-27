import importlib.util
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]

class ProductSceneCliTest(unittest.TestCase):
    def test_public_module_has_no_private_archive_access(self):
        source=(ROOT/'analysis/product_scene_layout.py').read_text('utf-8')
        tree=__import__('ast').parse(source)
        forbidden={'open','read_bytes','write_bytes','exec','eval'}
        for node in __import__('ast').walk(tree):
            if isinstance(node,__import__('ast').Call):
                name=getattr(node.func,'id',getattr(node.func,'attr',''))
                self.assertNotIn(name,forbidden)
    def test_cli_validates_all_inputs_before_output_creation(self):
        source=(ROOT/'tools/build_product_scene_components.py').read_text('utf-8')
        self.assertLess(source.index('checked_paths(a.hmi'),source.index('out.mkdir()'))
        self.assertLess(source.index('checked_scene(a.scene'),source.index('out.mkdir()'))
        self.assertIn("open('xb')",source)
        self.assertIn("vehicle_tested=False",source)

if __name__=='__main__':unittest.main()
