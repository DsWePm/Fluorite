import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("path_probe", Path(__file__).with_name("path-probe.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class PathProbeTest(unittest.TestCase):
    original = b"fov:0.0\r\noverrideWidth:1600\r\noverrideHeight:900\r\n"

    def test_normal_exit_restores_original_bytes_despite_game_window_updates(self):
        active = probe.window_options(self.original, 640, 360)
        current = active.replace(b"overrideWidth:640", b"overrideWidth:641")
        self.assertEqual(self.original, probe.restore_window(self.original, active, current))

    def test_keeps_unrelated_user_edits_while_restoring_dimensions(self):
        active = probe.window_options(self.original, 640, 360)
        current = active.replace(b"fov:0.0", b"fov:0.5")
        self.assertEqual(self.original.replace(b"fov:0.0", b"fov:0.5"),
                         probe.restore_window(self.original, active, current))

    def test_refuses_missing_and_duplicate_window_fields(self):
        with self.assertRaises(ValueError):
            probe.window_options(b"overrideWidth:640\n", 640, 360)
        with self.assertRaises(ValueError):
            probe.window_options(self.original + b"overrideWidth:1\n", 640, 360)

    def test_never_restores_options_under_a_live_client(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(probe, "clients", return_value=["123"]):
            with self.assertRaises(RuntimeError):
                probe.restore(Path(folder))
            self.assertEqual([], list(Path(folder).iterdir()))

    def test_refuses_configurations_in_which_the_readback_cap_cannot_advance(self):
        good = dict(composite={"path-reservoir": True, "debug-view": 0},
                    diagnostics={"path-reuse-identity-check": True})
        probe.validate_config(good)
        for changed in [dict(composite={"path-reservoir": False}, diagnostics=good["diagnostics"]),
                        dict(composite=good["composite"], diagnostics={}),
                        dict(composite={"path-reservoir": True, "debug-view": 2}, diagnostics=good["diagnostics"])]:
            with self.assertRaises(ValueError):
                probe.validate_config(changed)


if __name__ == "__main__":
    unittest.main()
