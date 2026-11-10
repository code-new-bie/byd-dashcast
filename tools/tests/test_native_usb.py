"""dashcast-usb compiled for this machine and driven through real processes, pipes and signals.

Only the QNX-specific paths are swapped at compile time; libusbdi is a fixture. This checks the
program's logic; it says nothing about the ARM build, the QNX loader or real USB drivers.
"""
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "instrument/native"


def frame(name="SCMD_USBMODE2", header="@3507", mark=0xa1, encode=None, declared=None, name_bytes=None, spaces=""):
    text = [b for ch in name for b in (ord(ch), 0)] if encode is None else encode
    values = [0] * 32 + text
    values[0x1c], values[1] = mark, len(text) if name_bytes is None else name_bytes
    size = len(values) if declared is None else declared
    body = json.dumps({"len": size, "data": values}, separators=(",", ":"))
    return f"{header}\n{spaces}data:json:{body}\n".encode()


@unittest.skipIf(os.name == "nt" or not shutil.which("gcc"), "Needs a Linux C compiler")
class NativeUsb(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="dashcast-native-")
        base = Path(cls.temp.name)
        cls.run_dir = base / "run"
        cls.pps = base / "pps"
        cls.program = base / "dashcast-usb"
        cls.script = base / "role-fixture"
        # Writes to the log and the stop pipe are best effort on purpose.
        common = ["gcc", "-O1", "-Wall", "-Wextra", "-Werror", "-Wno-unused-result", "-Wno-unused-function"]
        cls.window_control = base / "window-control"
        cls.screen_dir = base / "with-screen"
        cls.screen_dir.mkdir()
        for arguments in (["-DTEST_CLI", "-DQUERY_ATTEMPTS=3", f'-DROLE_FIXTURE="{cls.script}"', f'-DRUN_DIR="{cls.run_dir}"', f'-DPPS_PATH="{cls.pps}"', f'-DWINDOW_CONTROL="{cls.window_control}"',
                           f"-I{NATIVE / 'tests'}", str(NATIVE / "dashcast_usb.c"), "-o", str(cls.program), "-ldl"],
                          ["-shared", "-fPIC", str(NATIVE / "tests/fake_usbdi.c"), "-o", str(base / "libusbdi.so.2")],
                          ["-shared", "-fPIC", str(NATIVE / "tests/fake_libscreen.c"), "-o", str(cls.screen_dir / "libscreen.so.1")]):
            result = subprocess.run(common + arguments, capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError(result.stderr)
        cls.environment = dict(os.environ, LD_LIBRARY_PATH=str(base))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        shutil.rmtree(self.run_dir, ignore_errors=True)
        self.work = Path(tempfile.mkdtemp(prefix="case-", dir=self.temp.name))
        self.addCleanup(shutil.rmtree, self.work, ignore_errors=True)
        self.calls = self.work / "calls"
        self.rc_file = self.work / "rc"
        self.script.write_text(f'#!/bin/sh\necho "$1 $2" >> {self.calls}\n'
                               f'[ ! -f {self.work}/slow ] || sleep 1\n'
                               f'[ "$1" != ncm ] || [ ! -f {self.rc_file} ] || exit $(cat {self.rc_file})\n'
                               f'[ "$1" != query ] || [ ! -f {self.work}/status-fails ] || exit 1\n'
                               f'[ ! -f {self.work}/status-once ] || {{ rm {self.work}/status-once; exit 1; }}\nexit 0\n')
        self.script.chmod(0o700)

    def run_program(self, *arguments, timeout=15, **env):
        return subprocess.run([str(self.program), *map(str, arguments)], env=dict(self.environment, **env), capture_output=True,
                              timeout=timeout)

    def test_notice_lettering_needs_the_exact_firmware_font(self):
        wrong = self.work / "font.ttf"
        wrong.write_bytes(b"not the font")
        output = self.work / "glyphs.h"
        result = subprocess.run([sys.executable, str(NATIVE / "notice_glyphs.py"), str(wrong), str(output)], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Exact firmware font", result.stderr)
        self.assertFalse(output.exists())

    def lines(self):
        return self.calls.read_text().split("\n")[:-1] if self.calls.exists() else []

    def wait_for(self, condition, what, seconds=8):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(0.01)
        self.fail("Timed out waiting for " + what)

    def start_service(self):
        if self.pps.exists():
            self.pps.unlink()
        os.mkfifo(self.pps)
        self.pps_fd = os.open(self.pps, os.O_RDWR | os.O_NONBLOCK)
        self.addCleanup(os.close, self.pps_fd)
        self.process = subprocess.Popen([str(self.program), "--run"], env=self.environment,
                                        stderr=subprocess.PIPE)
        self.addCleanup(self.stop_service)

    def stop_service(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=10)
        self.process.stderr.close()

    def ready_service(self):
        self.start_service()
        self.wait_for(lambda: (self.run_dir / "commands").exists() and self.lines()[:1], "the service")
        time.sleep(0.05)

    def send(self, data):
        os.write(self.pps_fd, data)

    def expect_calls(self, count):
        self.wait_for(lambda: len(self.lines()) >= count, f"{count} script calls")
        time.sleep(0.15)
        self.assertEqual(count, len(self.lines()), self.lines())

    def test_exec_reports_the_child(self):
        self.assertEqual(7, self.run_program("--bounded", 4, "/bin/sh", "-c", "exit 7").returncode)
        self.assertEqual(0, self.run_program("--bounded", 45, "/bin/true").returncode)
        self.assertEqual(127, self.run_program("--bounded", 4, "/no/such/program").returncode)
        self.assertEqual(1, self.run_program("--bounded", 4, "/bin/sh", "-c", "kill -KILL $$").returncode)

    def test_exec_keeps_child_output(self):
        result = self.run_program("--bounded", 4, "/bin/echo", "row")
        self.assertEqual(b"row\n", result.stdout)

    def test_exec_rejects_bad_arguments(self):
        for arguments in (("--bounded", 0, "/bin/true"), ("--bounded", 46, "/bin/true"), ("--bounded", "4x", "/bin/true"),
                          ("--bounded", 4, "true"), ("--bounded",), ("--nothing", "a"), ()):
            self.assertEqual(2, self.run_program(*arguments).returncode, arguments)

    def test_exec_times_out_with_term(self):
        begin = time.monotonic()
        result = self.run_program("--bounded", 1, "/bin/sleep", 30)
        self.assertEqual(124, result.returncode)
        self.assertLess(time.monotonic() - begin, 3)
        self.assertIn(b"ROLE_STEP_TIMED_OUT", result.stderr)

    def test_exec_escalates_when_term_is_ignored(self):
        marker = self.work / "alive"
        script = f"trap '' TERM; while :; do : > {marker}; sleep 0.05; done"
        self.assertEqual(124, self.run_program("--bounded", 1, "/bin/sh", "-c", script).returncode)
        time.sleep(0.3)
        marker.unlink()
        time.sleep(0.3)
        self.assertFalse(marker.exists(), "Child survived KILL")

    def test_probe_truth_table(self):
        two = "XHCI:0,XHCI:0"
        cases = [("no-controller", dict(FAKE_BUSES=""), 0), ("no-controller", dict(FAKE_BUSES="XHCI:0"), 1),
                 ("controllers-up", dict(FAKE_BUSES=two), 0), ("controllers-up", dict(FAKE_BUSES="XHCI:1,XHCI:0"), 0),
                 ("controllers-up", dict(FAKE_BUSES="XHCI:0"), 1), ("controllers-up", dict(FAKE_BUSES=""), 1),
                 ("no-devices", dict(FAKE_BUSES=two), 0), ("no-devices", dict(FAKE_BUSES="XHCI:0,XHCI:1"), 1),
                 ("no-devices", dict(FAKE_BUSES="XHCI:0"), 1), ("controllers-up", dict(FAKE_BUSES="OHCI:0,XHCI:0"), 1),
                 ("controllers-up", dict(FAKE_BUSES=two, FAKE_CONNECT="5"), 1), ("no-controller", dict(FAKE_BUSES="", FAKE_CONNECT="5"), 1),
                 ("controllers-up", dict(FAKE_BUSES=two, FAKE_INFO="5"), 1), ("controllers-up", dict(FAKE_BUSES=two, FAKE_INFO="2"), 1),
                 ("no-controller", dict(FAKE_BUSES="", FAKE_DISCONNECT="1"), 1),
                 ("no-devices", dict(FAKE_BUSES=two, FAKE_TOPOLOGY="5"), 1), ("host-other", dict(FAKE_BUSES=two), 1)]
        for question, env, expected in cases:
            self.assertEqual(expected, self.run_program("--usb-check", question, **env).returncode, (question, env))

    def notice_environment(self, **extra):
        self.screen_log = self.work / "screen.log"
        self.window_control.write_text("")
        return dict(LD_LIBRARY_PATH=f"{self.screen_dir}:{self.environment['LD_LIBRARY_PATH']}", FAKE_SCREEN_LOG=str(self.screen_log), **extra)

    def screen_calls(self):
        return self.screen_log.read_text().split("\n")[:-1] if self.screen_log.exists() else []

    def test_notice_draws_raises_and_cleans_up(self):
        begin = time.monotonic()
        self.assertEqual(0, self.run_program("--show-result", 3, **self.notice_environment()).returncode)
        self.assertTrue(1.1 < time.monotonic() - begin < 3)
        calls = self.screen_calls()
        self.assertEqual(["create_context 1", "create_window 0", f"post_text_pixels {2 * 32 * 40}", "post_background 752228",
                          "destroy_window 0", "destroy_context 0"], calls)
        # A plain file here: each raise rewrites the same line. The ARM comparison counts the raises.
        line = self.window_control.read_text()
        self.assertTrue(line.startswith("dashcast_") and line.endswith("::12\n"), line)

    def test_notice_failures_clean_up_and_report(self):
        for which, calls in (("window", ["create_context 1", "destroy_context 0"]),
                             ("moved", ["create_context 1", "create_window 0", f"post_text_pixels {32 * 40}", "post_background 2056104",
                                        "destroy_window 0", "destroy_context 0"])):
            env = self.notice_environment(FAKE_SCREEN_FAIL=which)
            if self.screen_log.exists():
                self.screen_log.unlink()
            self.assertEqual(1, self.run_program("--show-result", 1, **env).returncode, which)
            self.assertEqual(calls, self.screen_calls(), which)

    def test_projection_port_matches_the_app(self):
        native = re.search(r'#define CAST_PORT "(\d+)"', (NATIVE / "dashcast_usb.c").read_text()).group(1)
        app = re.search(r"PROJECTION = (\d+)", (ROOT / "android/app/src/main/java/com/byd/dashcast/phase1/DashcastPorts.kt").read_text()).group(1)
        self.assertEqual(app, native)

    def test_each_result_has_its_own_colour(self):
        for value, colour in ((1, 2056104), (3, 752228), (4, 11740718)):
            env = self.notice_environment()
            if self.screen_log.exists():
                self.screen_log.unlink()
            self.assertEqual(0, self.run_program("--show-result", value, **env).returncode, value)
            self.assertIn(f"post_background {colour}", self.screen_calls(), value)

    def test_notice_without_the_screen_library(self):
        self.assertEqual(1, self.run_program("--show-result", 1).returncode)

    def test_only_result_notices_exist(self):
        for value, expected in ((0, 2), (2, 2), (5, 2), (9, 2), (12, 2), ("x", 2)):
            self.assertEqual(expected, self.run_program("--show-result", value, **self.notice_environment()).returncode, value)

    def test_switch_result_chooses_the_notice(self):
        env = self.notice_environment()
        self.environment = dict(self.environment, **env)
        self.addCleanup(lambda: setattr(self, "environment", type(self).environment))
        self.ready_service()
        self.send(frame("SCMD_USBMODE1"))
        self.wait_for(lambda: f"post_text_pixels {32 * 40}" in self.screen_calls(), "the Host notice")
        self.wait_for(lambda: self.screen_calls()[-1:] == ["destroy_context 0"], "the first close")
        self.rc_file.write_text("1")
        self.send(frame("SCMD_USBMODE2"))
        self.wait_for(lambda: f"post_text_pixels {3 * 32 * 40}" in self.screen_calls(), "the failure notice")

    def test_status_runs_first_and_frames_switch(self):
        self.ready_service()
        self.assertEqual([f"query {self.program}"], self.lines())
        self.send(frame("SCMD_USBMODE2"))
        self.expect_calls(2)
        self.send(frame("SCMD_USBMODE1"))
        self.expect_calls(3)
        self.assertEqual([f"ncm {self.program}", f"host {self.program}"], self.lines()[1:])

    def test_accepted_frame_forms(self):
        self.ready_service()
        for count, data in enumerate((frame(header="[n]@3507"), frame(header="+@3507"), frame(header="@3507\r"),
                                      frame(spaces=" \t")), start=2):
            self.send(data)
            self.expect_calls(count)

    def test_malformed_frames_do_nothing(self):
        self.ready_service()
        name = [b for ch in "SCMD_USBMODE2" for b in (ord(ch), 0)]
        bad = [frame(header="@3506"), frame(mark=0xa0), frame(declared=10), frame("SCMD_REBOOTSYS"), frame("SCMD_SCREENOFF"),
               frame(name_bytes=25), frame(encode=[65, 1] * 13), frame(encode=name[:-1]), b"@3507\n", b"\n", b"@3507",
               b'@3507\ndata:json:{"len":1}\n', b'@3507\ndata:json:{"len":34,"len":34,"data":[]}\n',
               frame().replace(b'"len"', b'"lem"'), frame().replace(b",0,", b",00,", 1), frame().replace(b"]", b",256]", 1),
               frame().replace(b"}", b"} x", 1), frame()[:-8]]
        for data in bad:
            self.send(data)
            time.sleep(0.02)
        time.sleep(0.15)
        self.assertEqual(1, len(self.lines()), self.lines())
        self.assertIsNone(self.process.poll())

    def test_command_channel(self):
        self.ready_service()
        self.assertEqual(0, self.run_program("--request", "SCMD_USBMODE2").returncode)
        self.expect_calls(2)
        self.assertEqual(2, self.run_program("--request", "SCMD_REBOOTSYS").returncode)
        self.assertEqual(f"ncm {self.program}", self.lines()[-1])

    def test_command_needs_the_service(self):
        self.assertEqual(1, self.run_program("--request", "SCMD_USBMODE1").returncode)

    def test_requests_during_a_switch_are_dropped(self):
        self.ready_service()
        (self.work / "slow").write_text("")
        self.send(frame("SCMD_USBMODE2"))
        self.wait_for(lambda: len(self.lines()) == 2, "the switch")
        self.send(frame("SCMD_USBMODE1"))
        time.sleep(1.4)
        self.assertEqual(2, len(self.lines()), self.lines())

    def test_failed_status_stops_and_keeps_the_lock(self):
        (self.work / "status-fails").write_text("")
        self.start_service()
        self.assertEqual(1, self.process.wait(timeout=8))
        self.assertTrue((self.run_dir / "lock").is_dir())

    def test_a_failed_first_query_is_repeated(self):
        (self.work / "status-once").write_text("")
        self.ready_service()
        self.assertEqual(2, len(self.lines()), self.lines())
        self.assertIsNone(self.process.poll())

    def test_a_request_during_the_notice_is_served(self):
        env = self.notice_environment()
        self.environment = dict(self.environment, **env)
        self.addCleanup(lambda: setattr(self, "environment", type(self).environment))
        self.ready_service()
        self.send(frame("SCMD_USBMODE2"))
        self.expect_calls(2)
        self.wait_for(lambda: any(call.startswith("post_text_pixels") for call in self.screen_calls()), "the notice")
        self.assertNotIn("destroy_context 0", self.screen_calls())
        self.send(frame("SCMD_USBMODE1"))
        self.expect_calls(3)
        self.assertEqual(f"host {self.program}", self.lines()[-1])

    def children(self):
        found = []
        for stat in Path("/proc").glob("[0-9]*/stat"):
            try:
                fields = stat.read_text().rsplit(")", 1)[1].split()
            except OSError:
                continue
            if int(fields[1]) == self.process.pid and fields[0] != "Z":
                found.append(int(stat.parent.name))
        return found

    def test_a_stuck_notice_is_removed_after_its_limit(self):
        env = self.notice_environment()
        self.environment = dict(self.environment, **env)
        self.addCleanup(lambda: setattr(self, "environment", type(self).environment))
        self.ready_service()
        self.send(frame("SCMD_USBMODE2"))
        self.wait_for(lambda: "create_context 1" in self.screen_calls() and self.children(), "the notice process")
        stuck = self.children()[0]
        os.kill(stuck, signal.SIGSTOP)
        self.wait_for(lambda: not self.children(), "the stuck notice to be removed", seconds=6)
        self.assertIsNone(self.process.poll())

    def test_term_exits_cleanly_and_removes_the_lock(self):
        self.ready_service()
        self.process.send_signal(signal.SIGTERM)
        self.assertEqual(0, self.process.wait(timeout=8))
        self.assertFalse((self.run_dir / "lock").exists())
        self.assertFalse((self.run_dir / "commands").exists())

    def test_unconfirmed_recovery_stops_the_service(self):
        self.ready_service()
        self.rc_file.write_text("3")
        self.send(frame("SCMD_USBMODE2"))
        self.assertEqual(1, self.process.wait(timeout=8))
        self.assertTrue((self.run_dir / "lock").is_dir())

    def test_other_script_failures_keep_serving(self):
        self.ready_service()
        self.rc_file.write_text("1")
        self.send(frame("SCMD_USBMODE2"))
        self.expect_calls(2)
        self.assertIsNone(self.process.poll())

    def test_second_copy_is_refused(self):
        self.ready_service()
        self.assertEqual(1, self.run_program("--run").returncode)

    def test_waits_for_the_pps_object(self):
        if self.pps.exists():
            self.pps.unlink()
        process = subprocess.Popen([str(self.program), "--run"], env=self.environment, stderr=subprocess.DEVNULL)
        self.addCleanup(process.wait)
        self.addCleanup(process.kill)
        time.sleep(0.2)
        self.assertEqual([], self.lines())
        os.mkfifo(self.pps)
        fd = os.open(self.pps, os.O_RDWR | os.O_NONBLOCK)
        self.addCleanup(os.close, fd)
        self.wait_for(lambda: self.lines()[:1], "the first status call", 4)


if __name__ == "__main__":
    unittest.main()
