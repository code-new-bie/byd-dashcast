"""dashcast-usb --boot compiled for this machine, with its paths moved into a sandbox.

This checks what start-up does to files and processes; it says nothing about the ARM build,
the QNX loader or the instrument's start-up manager.
"""
import os
import re
import shutil
import signal
import stat
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "instrument/native"
STAMP = re.compile(r"^\d+\.\d{3} ")


@unittest.skipIf(os.name == "nt" or not shutil.which("gcc"), "Needs a Linux C compiler")
class Boot(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="dashcast-boot-")
        base = Path(cls.temp.name)
        cls.install = base / "install"
        cls.tmpfs = base / "tmpfs"
        cls.run_dir = cls.tmpfs / "dashcast"
        cls.page = base / "background-page"
        cls.program = base / "dashcast-usb"
        result = subprocess.run(["gcc", "-O1", "-Wall", "-Wextra", "-Werror", "-Wno-unused-result", "-Wno-unused-function", f'-DRUN_DIR="{cls.run_dir}"',
                                 f'-DINSTALL_DIR="{cls.install}"', f'-DRAM_ROOT="{cls.tmpfs}"', f'-DBACKGROUND_PAGE="{cls.page}"',
                                 f'-DPPS_PATH="{base}/no-such-pps"', f"-I{NATIVE / 'tests'}", str(NATIVE / "dashcast_usb.c"), "-o", str(cls.program), "-ldl"],
                                capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        for path in (self.install, self.tmpfs):
            shutil.rmtree(path, ignore_errors=True)
            path.mkdir()
        self.page.unlink(missing_ok=True)
        shutil.copyfile(self.program, self.install / "dashcast-usb")
        os.chmod(self.install / "dashcast-usb", 0o700)
        (self.install / "dashcast-receiver").write_bytes(b"receiver bytes")
        self.process = None
        self.addCleanup(self.finish)

    def finish(self):
        if self.process is not None and self.process.poll() is None:
            self.process.kill()
            self.process.wait(timeout=10)

    def lines(self):
        path = self.run_dir / "usb.log"
        return [STAMP.sub("", line) for line in path.read_text().splitlines()] if path.exists() else []

    def boot(self, **env):
        self.process = subprocess.Popen([str(self.program), "--boot"], env=dict(os.environ, **env), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        self.addCleanup(self.process.stderr.close)
        return self.process

    def wait_for_service(self):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if "WAIT_FOR_CHANNEL" in self.lines():
                return
            if self.process.poll() is not None:
                self.fail(f"Exited with {self.process.returncode}: {self.lines()}")
            time.sleep(0.01)
        self.fail("The service did not start: " + str(self.lines()))

    def mode(self, path):
        return stat.S_IMODE(path.stat().st_mode)

    def test_module_moves_to_ram_and_the_service_takes_over(self):
        self.boot()
        self.wait_for_service()
        for name in ("dashcast-usb", "dashcast-receiver"):
            self.assertEqual((self.install / name).read_bytes(), (self.run_dir / name).read_bytes(), name)
            self.assertEqual(0o700, self.mode(self.run_dir / name), name)
        self.assertEqual(os.readlink(f"/proc/{self.process.pid}/exe"), str(self.run_dir / "dashcast-usb"))
        self.assertEqual(["DASHCAST_STARTUP", "DCV1_FILES_READY", "WATCH_BEGIN", "WAIT_FOR_CHANNEL"], self.lines())
        self.assertEqual(0o600, self.mode(self.run_dir / "usb.log"))
        self.assertEqual(0o700, self.mode(self.run_dir))
        self.assertTrue((self.run_dir / "lock").is_dir())
        self.assertFalse((self.run_dir / "dashcast-receiver.tmp").exists())
        self.process.send_signal(signal.SIGTERM)
        self.assertEqual(0, self.process.wait(timeout=8))

    def test_background_page_is_made_once(self):
        self.boot()
        self.wait_for_service()
        self.assertEqual(bytes(4096), self.page.read_bytes())
        self.assertEqual(0o600, self.mode(self.page))

    def test_existing_background_page_is_not_touched(self):
        self.page.write_text("live")
        self.boot()
        self.wait_for_service()
        self.assertEqual("live", self.page.read_text())

    def test_receiver_is_optional(self):
        (self.install / "dashcast-receiver").unlink()
        self.boot()
        self.wait_for_service()
        self.assertFalse((self.run_dir / "dashcast-receiver").exists())
        self.assertNotIn("DCV1_FILES_READY", self.lines())

    @unittest.skipIf(getattr(os, "geteuid", lambda: -1)() == 0, "root can read everything")
    def test_receiver_that_cannot_be_copied_does_not_stop_usb_control(self):
        os.chmod(self.install / "dashcast-receiver", 0)
        self.boot()
        self.wait_for_service()
        self.assertIn("DCV1_COPY_FAILED", self.lines())
        self.assertFalse((self.run_dir / "dashcast-receiver").exists())
        self.assertFalse((self.run_dir / "dashcast-receiver.tmp").exists())

    def test_missing_program_stops_before_the_service(self):
        (self.install / "dashcast-usb").unlink()
        self.assertEqual(1, self.boot().wait(timeout=8))
        self.assertFalse((self.run_dir / "dashcast-usb").exists())
        self.assertNotIn("WATCH_BEGIN", self.lines())

    def test_a_linked_program_is_refused(self):
        (self.install / "dashcast-usb").unlink()
        (self.install / "dashcast-usb").symlink_to(self.program)
        self.assertEqual(1, self.boot().wait(timeout=8))

    def test_second_start_is_refused_while_the_lock_is_held(self):
        (self.run_dir / "lock").mkdir(parents=True)
        self.assertEqual(1, self.boot().wait(timeout=8))
        self.assertEqual([], self.lines())

    def test_linked_run_directory_is_refused(self):
        target = self.tmpfs / "elsewhere"
        target.mkdir()
        self.run_dir.symlink_to(target)
        self.assertEqual(1, self.boot().wait(timeout=8))
        self.assertEqual([], list(target.iterdir()))

    def test_no_ram_directory(self):
        shutil.rmtree(self.tmpfs)
        process = self.boot()
        self.assertEqual(1, process.wait(timeout=8))
        self.assertIn(b"RAM_DIR_UNAVAILABLE", process.stderr.read())

    def test_inherited_socket_setting_does_not_reach_the_service(self):
        self.boot(SOCK="/somewhere")
        self.wait_for_service()
        self.assertNotIn(b"SOCK=", Path(f"/proc/{self.process.pid}/environ").read_bytes())

    def test_a_previous_log_is_replaced(self):
        self.run_dir.mkdir()
        (self.run_dir / "usb.log").write_text("old\n")
        self.boot()
        self.wait_for_service()
        self.assertNotIn("old", self.lines())


if __name__ == "__main__":
    unittest.main()
