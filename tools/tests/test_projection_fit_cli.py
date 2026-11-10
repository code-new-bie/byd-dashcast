"""Step-1 CLI input boundaries, no private firmware or vehicle needed."""
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class ProjectionFitCliTests(unittest.TestCase):
    def test_help_names_the_inputs_and_component_only_scope(self):
        result = subprocess.run([sys.executable, str(ROOT / 'tools/build_projection_fit_components.py'), '--help'],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        for name in ('--base', '--backgrounds', '--output'):
            self.assertIn(name, result.stdout)
        self.assertIn('NOT a flash ZIP', result.stdout)

    def test_empty_invocation_does_not_write_or_build(self):
        result = subprocess.run([sys.executable, str(ROOT / 'tools/build_projection_fit_components.py')],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(2, result.returncode)
        self.assertIn('required', result.stderr)


if __name__ == '__main__':
    unittest.main()
