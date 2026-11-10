"""The USB role and receiver handling of dashcast-usb, compiled for this machine.

Every system command goes to a recording fixture, and each role request is compared with the
expected command order, results and files (expected-role-traces.json). Nothing touches real processes, USB or a vehicle, and this says
nothing about the ARM build or QNX.
"""
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "instrument/native"
FIXTURES = NATIVE / "tests"
EXPECTED = FIXTURES / "expected-role-traces.json"
TOKEN = re.compile(r"^(USB|NCM|HOST|DCV1)_[A-Z0-9_]+(?: .*)?$")
STAMP = re.compile(r"^\d+\.\d{3} ")
# Files of the sandbox itself rather than results of a run.
NOT_STATE = {"trace", "dashcast-usb", "dashcast-receiver", "fake-cast", "lock", "commands", "pps"}


@unittest.skipIf(os.name == "nt" or not shutil.which("gcc"), "Needs a Linux C compiler")
class RoleBase(unittest.TestCase):
    defines = ()

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="dashcast-role-")
        base = Path(cls.temp.name)
        cls.directory = base / "run"
        cls.platform = cls.directory / "platform"
        cls.program = base / "dashcast-usb"
        cls.log = base / "service.log"
        run = str(cls.directory)
        flags = ["gcc", "-O1", "-Wall", "-Wextra", "-Werror", "-Wno-unused-result", "-Wno-unused-function", f'-DRUN_DIR="{run}"', f'-DPLATFORM="{cls.platform}"',
                 f'-DPPS_PATH="{run}/pps"', f'-DCOMMAND_WRAPPER="{run}/dashcast-usb"', f'-DSCREEN_NODE="{run}/screen-node"',
                 f'-DRENDERER_NODE="{run}/renderer-node"', "-DQUERY_ATTEMPTS=1", *cls.defines, f"-I{FIXTURES}", str(NATIVE / "dashcast_usb.c"), "-o", str(cls.program), "-ldl"]
        result = subprocess.run(flags, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        shutil.rmtree(self.directory, ignore_errors=True)
        self.directory.mkdir()
        self.steps = []
        self.process = None
        self.env = dict(os.environ, FAKE_DIR=str(self.directory))
        self.env.pop("SOCK", None)
        self.put("trace")
        shutil.copyfile(FIXTURES / "fake-helper.sh", self.directory / "dashcast-usb")
        os.chmod(self.directory / "dashcast-usb", 0o700)
        for relative in ("sbin/io-usb-dcd", "sbin/io-pkt-v6-hc", "sbin/ulink_ctrl", "sbin/ifconfig",
                         "lib/dll/devu-usbncm-omap543x-dwcotg3.so", "lib/dll/devnp-usbdnet.so",
                         "dev/io-usb/devu-omap5-xhci.so"):
            self.put("platform/" + relative)
        self.put("usb-world")
        (self.directory / "dcd").mkdir()
        (self.directory / "net/dev").mkdir(parents=True)
        self.prepare()
        self.addCleanup(self.finish)
        self.start_service()
        self.assertEqual(0, self.last_rc(), self.log.read_text())
        # Each case starts from what the script tests started from: nothing run yet.
        self.put("trace")
        self.remove("processes")

    def prepare(self):
        pass

    def finish(self):
        if self.process is not None and self.process.poll() is None:
            self.process.kill()
            self.process.wait(timeout=10)

    def put(self, relative, value=""):
        path = self.directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value.encode("ascii"))

    def has(self, relative):
        return (self.directory / relative).exists()

    def remove(self, relative):
        (self.directory / relative).unlink()

    def trace(self):
        return (self.directory / "trace").read_bytes().decode("utf-8")

    def lines(self):
        return [STAMP.sub("", line) for line in self.log.read_text(errors="replace").splitlines()] if self.log.exists() else []

    def wait_for(self, condition, what, seconds=20):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(0.005)
        self.fail("Timed out waiting for " + what + "\n" + "\n".join(self.lines()))

    def start_service(self):
        pps = self.directory / "pps"
        if pps.exists():
            pps.unlink()
        os.mkfifo(pps)
        self.pps_fd = os.open(pps, os.O_RDWR | os.O_NONBLOCK)
        self.addCleanup(os.close, self.pps_fd)
        self.log_start = len(self.lines())
        with self.log.open("ab") as output:
            self.process = subprocess.Popen([str(self.program), "--run"], env=self.env, stderr=output)
        self.wait_for(lambda: "WATCHING" in self.lines()[self.log_start:] or self.process.poll() is not None, "the service")

    def stop_service(self):
        self.process.send_signal(signal.SIGTERM)
        return self.process.wait(timeout=30)

    def restart_if_stopped(self):
        """An unconfirmed recovery stops the service on purpose and keeps its lock."""
        if self.process.poll() is not None:
            (self.directory / "lock").rmdir()
            self.start_service()

    def last_rc(self):
        requests = [line for line in self.lines() if line.startswith("ROLE_REQUEST")]
        return int(requests[-1].rsplit("rc=", 1)[1])

    def state(self):
        files = []
        for path in sorted(self.directory.rglob("*")):
            name = path.relative_to(self.directory).as_posix()
            if name not in NOT_STATE and not name.startswith(("platform/sbin", "platform/lib")) and path.name != "dev":
                files.append(name + ("/" if path.is_dir() else ""))
        return files

    def outcome(self, mode, started_log, started_trace):
        new = self.lines()[started_log:]
        rc = self.last_rc()
        tokens = [re.sub(r"pid=\d+", "pid=N", line) for line in new if TOKEN.match(line)]
        trace = [line.replace(str(self.directory), "{RUN}") for line in self.trace().splitlines()[started_trace:]]
        self.steps.append(dict(arguments=mode, rc=rc, tokens=tokens, trace=trace, state=self.state()))
        blob = "\n".join(tokens).encode()
        return types.SimpleNamespace(returncode=rc, stdout=blob, stderr=blob)

    def role(self, mode):
        started_log, started_trace = len(self.lines()), len(self.trace().splitlines())
        if mode == "query":
            self.stop_service()
            self.start_service()
            self.wait_for(lambda: any(line.startswith("ROLE_REQUEST") for line in self.lines()[started_log:]), "the query")
            return self.outcome(mode, started_log, started_trace)
        fd = os.open(self.directory / "commands", os.O_WRONLY | os.O_NONBLOCK)
        os.write(fd, {"host": b"SCMD_USBMODE1\n", "ncm": b"SCMD_USBMODE2\n"}[mode])
        os.close(fd)
        self.wait_for(lambda: "REQUEST_DONE" in self.lines()[started_log:] or self.process.poll() is not None, "the request")
        return self.outcome(mode, started_log, started_trace)

    def check_expected(self):
        known = json.loads(EXPECTED.read_text("utf-8"))
        key = type(self).__name__ + "." + self._testMethodName
        self.assertIn(key, known)
        old = [dict(arguments=s["arguments"], rc=s["rc"], tokens=sorted(s["stdout"] + s["stderr"]), trace=s["trace"], state=s["state"]) for s in known[key]]
        new = [dict(step, tokens=sorted(step["tokens"])) for step in self.steps]
        self.assertEqual(old, new)

    def ncm_state(self, up=True):
        (self.platform / "dev/io-usb/devu-omap5-xhci.so").unlink(missing_ok=True)
        for name in ("dcd/io-usb", "net/dev/socket", "interface-present"):
            self.put(name)
        if up:
            self.put("interface-up")
            self.put("link-up")

    def host_node(self):
        return self.platform / "dev/io-usb/devu-omap5-xhci.so"


class Usb(RoleBase):
    """Every role case with its marker files and expected command order."""
    defines = ("-DVIDEO_FIXTURE",)

    def prepare(self):
        shutil.copyfile(FIXTURES / "fake-cast-mode.sh", self.directory / "fake-cast")
        os.chmod(self.directory / "fake-cast", 0o700)
        shutil.copyfile(FIXTURES / "fake-receiver.sh", self.directory / "dashcast-receiver")
        os.chmod(self.directory / "dashcast-receiver", 0o700)

    def test_host_to_ncm_runs_the_stack_up_in_order(self):
        result = self.role("ncm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"USB_NCM_READY", result.stdout)
        trace = self.trace()
        order = [trace.index(word) for word in ("--usb-check no-devices", "/bin/umount", "io-usb-dcd -n", "io-pkt-v6-hc -i7",
                                                "netmask 255.255.255.252 up", "-l 1")]
        self.assertEqual(sorted(order), order)
        self.check_expected()

    def test_link_up_is_reached_with_two_process_surveys(self):
        self.assertEqual(0, self.role("ncm").returncode)
        self.assertEqual(2, self.trace().split("-l 1")[0].count("/bin/pidin ar"))
        self.check_expected()

    def test_stack_left_down_is_brought_back_up(self):
        self.ncm_state(up=False)
        result = self.role("ncm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.has("interface-up"))
        self.assertTrue(self.has("link-up"))
        self.assertIn("-l 1", self.trace())
        self.check_expected()

    def test_ncm_then_host_restores_the_original_host(self):
        self.assertEqual(0, self.role("ncm").returncode)
        self.assertTrue(self.has("link-up"))
        result = self.role("host")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"USB_HOST_READY", result.stdout)
        self.assertTrue(self.host_node().exists())
        self.assertFalse(self.has("dcd/io-usb"))
        self.assertFalse(self.has("net/dev/socket"))
        self.check_expected()

    def test_pending_disconnect_is_retried_within_bounds(self):
        self.ncm_state()
        self.put("destroy-failures", "1\n")
        result = self.role("host")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"NCM_INTERFACE_REMOVED", result.stdout)
        self.check_expected()

    def test_interface_already_removed_does_not_block_host(self):
        self.ncm_state()
        self.remove("interface-present")
        result = self.role("host")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"NCM_INTERFACE_ALREADY_REMOVED", result.stdout)
        self.check_expected()

    def test_slow_stack_exit_is_waited_for(self):
        self.ncm_state()
        self.put("stop-delay", "30\n")
        result = self.role("host")
        self.assertEqual(0, result.returncode, result.stderr)
        self.check_expected()

    def test_peer_still_attached_blocks_host(self):
        self.ncm_state()
        self.put("physical-peer")
        self.assertNotEqual(0, self.role("host").returncode)
        self.assertNotIn("/bin/mount ", self.trace())
        self.assertTrue(self.has("dcd/io-usb"))
        self.assertNotIn("kill -", self.trace())
        self.check_expected()

    def test_unconfirmed_video_stop_blocks_teardown(self):
        self.ncm_state()
        self.put("cast-stop-failure")
        self.assertNotEqual(0, self.role("host").returncode)
        self.assertNotIn("ulink_ctrl", self.trace())
        self.assertNotIn("kill -", self.trace())
        self.check_expected()

    def test_foreign_stack_or_failed_survey_is_not_empty(self):
        for marker in ("foreign-stack", "pidin-failure"):
            self.put(marker)
            self.put("trace")
            self.assertNotEqual(0, self.role("ncm").returncode)
            self.assertNotIn("/bin/umount ", self.trace())
            self.assertNotIn("kill -", self.trace())
            self.remove(marker)
            self.restart_if_stopped()
        self.check_expected()

    def test_attached_storage_blocks_ncm_before_unloading_host(self):
        self.put("attached-storage")
        self.assertNotEqual(0, self.role("ncm").returncode)
        self.assertNotIn("/bin/umount ", self.trace())
        self.check_expected()

    def test_video_failure_does_not_undo_ncm(self):
        self.put("cast-start-failure")
        result = self.role("ncm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"DCV1_UNAVAILABLE", result.stderr)
        self.assertTrue(self.has("link-up"))
        self.check_expected()

    def test_status_does_not_accept_a_down_interface(self):
        self.ncm_state(up=False)
        self.assertNotEqual(0, self.role("query").returncode)
        self.assertNotIn("ulink_ctrl", self.trace())
        self.check_expected()

    def test_failed_interface_query_is_not_absence(self):
        self.ncm_state()
        self.put("ifconfig-failure")
        self.assertNotEqual(0, self.role("host").returncode)
        self.assertNotIn("kill -", self.trace())
        self.check_expected()

    def test_failed_link_up_recovers_host_without_claiming_ncm(self):
        self.put("linkup-failure")
        result = self.role("ncm")
        self.assertEqual(1, result.returncode)
        self.assertNotIn(b"USB_NCM_READY", result.stdout)
        self.assertTrue(self.host_node().exists())
        self.check_expected()

    def test_failed_recovery_reports_its_own_exit_code(self):
        self.put("linkup-failure")
        self.put("physical-peer")
        result = self.role("ncm")
        self.assertEqual(3, result.returncode)
        self.assertIn(b"USB_HOST_RECOVERY_UNCONFIRMED", result.stderr)
        self.assertEqual(1, self.process.wait(timeout=10))
        self.check_expected()

    def test_timeout_keeps_the_guard_and_blocks_new_work(self):
        self.ncm_state()
        self.put("command-timeout")
        result = self.role("host")
        self.assertEqual(124, result.returncode)
        self.assertIn(b"USB_OPERATION_QUARANTINED_RESTART_REQUIRED", result.stderr)
        self.assertTrue((self.directory / "role-operation").is_dir())
        self.assertNotIn("ulink_ctrl", self.trace())
        self.remove("command-timeout")
        before = self.trace()
        retry = self.role("ncm")
        self.assertNotEqual(0, retry.returncode)
        self.assertIn(b"USB_OPERATION_BUSY_OR_UNCONFIRMED", retry.stderr)
        self.assertEqual(before, self.trace())
        self.check_expected()

    def test_term_during_a_command_keeps_the_guard(self):
        self.put("slow-command")
        fd = os.open(self.directory / "commands", os.O_WRONLY | os.O_NONBLOCK)
        os.write(fd, b"SCMD_USBMODE1\n")
        os.close(fd)
        self.wait_for(lambda: "/bin/pidin" in self.trace(), "the first command")
        self.process.send_signal(signal.SIGTERM)
        self.process.wait(timeout=30)
        self.assertIn("USB_OPERATION_QUARANTINED_RESTART_REQUIRED", self.lines())
        self.assertTrue((self.directory / "role-operation").is_dir())
        self.assertEqual(1, self.trace().count("\n"))

    def test_timeout_during_ncm_skips_recovery(self):
        self.put("command-timeout")
        result = self.role("ncm")
        self.assertEqual(124, result.returncode)
        self.assertEqual(1, self.trace().count("\n"))
        self.check_expected()

    def test_guard_is_released_after_failure_and_success(self):
        self.put("pidin-failure")
        self.assertNotEqual(0, self.role("host").returncode)
        self.assertFalse(self.has("role-operation"))
        self.remove("pidin-failure")
        self.assertEqual(0, self.role("ncm").returncode)
        self.assertFalse(self.has("role-operation"))
        self.assertEqual(0, self.role("host").returncode)
        self.assertFalse(self.has("role-operation"))
        self.check_expected()

    def test_unknown_guard_blocks_changes_but_not_status(self):
        (self.directory / "role-operation").mkdir()
        self.assertNotEqual(0, self.role("ncm").returncode)
        self.assertEqual("", self.trace())
        self.assertEqual(0, self.role("query").returncode)
        self.assertTrue(self.has("role-operation"))
        self.check_expected()

    def test_repeated_cycles_and_duplicate_ncm(self):
        for _ in range(3):
            self.assertEqual(0, self.role("host").returncode)
            self.assertEqual(0, self.role("ncm").returncode)
            before = self.trace()
            result = self.role("ncm")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(b"NCM_ALREADY_CONFIGURED_NO_RECONNECT", result.stdout)
            added = self.trace()[len(before):]
            self.assertNotIn("ulink_ctrl", added)
            self.assertNotIn("umount", added)
            self.assertFalse(self.has("role-operation"))
            self.assertEqual(0, self.role("host").returncode)
        self.check_expected()

    def test_failed_guard_release_turns_success_into_failure(self):
        self.put("guard-release-failure")
        result = self.role("host")
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"USB_OPERATION_GUARD_RELEASE_FAILED", result.stderr)
        self.assertTrue((self.directory / "role-operation").is_dir())
        self.check_expected()

    def test_failed_host_clears_the_link_mark(self):
        self.assertEqual(0, self.role("ncm").returncode)
        self.put("physical-peer")
        self.assertNotEqual(0, self.role("host").returncode)
        self.assertFalse(self.has("ncm-connected"))
        self.remove("physical-peer")
        before = self.trace()
        self.assertEqual(0, self.role("ncm").returncode)
        self.assertIn("-l 1", self.trace()[len(before):])
        self.check_expected()

    def test_a_network_stack_on_our_interface_is_foreign(self):
        for row in ("50 /sbin/io-pkt-v6-hc -i7 -d other", "50 /sbin/io-pkt-v6-hc -d other -i7", f"50 /sbin/io-pkt-v6-hc -d x prefix={self.directory}/net"):
            self.put("process-state", row + "\n")
            self.assertNotEqual(0, self.role("ncm").returncode, row)
            self.remove("process-state")
            self.restart_if_stopped()
        self.assertNotIn("/bin/umount ", self.trace())

    def test_the_systems_own_network_stack_is_ignored(self):
        self.put("process-state", "50 /sbin/io-pkt-v6-hc -i0 -d ti814x-j6 -ptcpip\n60 /sbin/devb-sdmmc   -d x\nnot a process line\n")
        self.assertEqual(0, self.role("ncm").returncode)

    def test_process_table_too_large_is_unknown(self):
        self.put("process-state", "".join(f"{i} /sbin/other {'x' * 200}\n" for i in range(1000, 2000)))
        self.assertNotEqual(0, self.role("ncm").returncode)
        self.assertNotIn("/bin/umount ", self.trace())

    def test_every_log_line_is_timed(self):
        self.assertEqual(0, self.role("ncm").returncode)
        raw = self.log.read_text().splitlines()
        self.assertTrue(raw)
        for line in raw:
            self.assertRegex(line, STAMP)
        self.assertTrue(any(re.search(r"STEP umount rc=0 ms=\d+$", line) for line in raw), raw)


class Receiver(RoleBase):
    """The service as the receiver's parent."""
    defines = ("-DCAST_START_POLLS=20", "-DCAST_STOP_POLLS=40", "-DMEDIA_POLLS=6")

    def prepare(self):
        shutil.copyfile(FIXTURES / "fake-receiver.sh", self.directory / "dashcast-receiver")
        os.chmod(self.directory / "dashcast-receiver", 0o700)
        self.put("screen-node")
        self.put("renderer-node")
        self.addCleanup(self.kill_receivers)

    def kill_receivers(self):
        if self.has("receiver-pids"):
            for pid in (self.directory / "receiver-pids").read_text().split():
                try:
                    os.kill(int(pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def receiver_pids(self):
        return (self.directory / "receiver-pids").read_text().split() if self.has("receiver-pids") else []

    def alive(self, pid):
        status = Path(f"/proc/{pid}/stat")
        return status.exists() and status.read_text().rsplit(")", 1)[1].split()[0] != "Z"

    def test_ncm_starts_the_receiver_and_host_stops_it(self):
        started = self.role("ncm")
        self.assertEqual(0, started.returncode, started.stderr)
        self.assertIn(b"DCV1_LISTENING_NOT_RENDERED", started.stdout)
        self.assertNotIn(b"DCV1_UNAVAILABLE", started.stderr)
        self.assertEqual("--listen 192.168.77.1 17475 --client 192.168.77.2 --ready-fd 3\n", (self.directory / "receiver-args").read_text())
        self.assertEqual(str(self.directory / "net") + "\n", (self.directory / "receiver-sock").read_text())
        self.assertEqual("FAKE_RECEIVER_OUTPUT\n", (self.directory / "cast.log").read_text())
        pid = self.receiver_pids()[0]
        self.assertTrue(self.alive(pid))
        stopped = self.role("host")
        self.assertEqual(0, stopped.returncode, stopped.stderr)
        self.assertIn(b"DCV1_CLEANUP_REPORTED", stopped.stdout)
        self.assertIn(b"USB_HOST_READY", stopped.stdout)
        self.assertFalse(self.alive(pid))
        for leftover in ("cast-guard", "cast-owner", "cast-cleanup"):
            self.assertFalse(self.has(leftover))

    def test_it_can_start_again_after_a_clean_stop(self):
        self.assertEqual(0, self.role("ncm").returncode)
        self.assertEqual(0, self.role("host").returncode)
        self.assertEqual(0, self.role("ncm").returncode)
        self.assertEqual(2, len(self.receiver_pids()))
        self.assertEqual(0, self.role("host").returncode)

    def test_host_without_a_receiver_installed(self):
        self.remove("dashcast-receiver")
        self.assertEqual(0, self.role("ncm").returncode)
        result = self.role("host")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.receiver_pids())

    def test_repeated_ncm_keeps_the_running_receiver(self):
        self.assertEqual(0, self.role("ncm").returncode)
        result = self.role("ncm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"DCV1_LISTENING_NOT_RENDERED", result.stdout)
        self.assertEqual(1, len(self.receiver_pids()))

    def test_missing_media_service_starts_nothing(self):
        self.remove("renderer-node")
        result = self.role("ncm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"DCV1_MEDIA_NOT_READY", result.stderr)
        self.assertIn(b"DCV1_UNAVAILABLE", result.stderr)
        self.assertEqual([], self.receiver_pids())
        self.assertTrue(self.has("link-up"))
        self.assertEqual(0, self.role("host").returncode)

    def test_failed_start_does_not_undo_ncm_but_blocks_host(self):
        self.put("early-exit", "7")
        result = self.role("ncm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"DCV1_UNAVAILABLE", result.stderr)
        self.assertTrue(self.has("link-up"))
        blocked = self.role("host")
        self.assertNotEqual(0, blocked.returncode)
        self.assertIn(b"DCV1_CLEANUP_UNCONFIRMED", blocked.stderr)
        self.assertIn(b"HOST_VIDEO_STOP_UNCONFIRMED", blocked.stderr)
        self.assertNotIn("ulink_ctrl", self.trace().split("cast")[-1])
        self.assertFalse(self.host_node().exists())

    def test_unclean_previous_exit_blocks_restart(self):
        self.assertEqual(0, self.role("ncm").returncode)
        pid = self.receiver_pids()[0]
        self.put("exit-code", "7")
        os.kill(int(pid), signal.SIGTERM)
        self.wait_for(lambda: self.has("receiver-exited"), "the receiver to exit")
        time.sleep(0.05)
        result = self.role("ncm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"DCV1_PRIOR_CLEANUP_UNCONFIRMED", result.stderr)
        self.assertEqual(1, len(self.receiver_pids()))

    def test_clean_exit_on_its_own_allows_restart(self):
        self.assertEqual(0, self.role("ncm").returncode)
        os.kill(int(self.receiver_pids()[0]), signal.SIGTERM)
        self.wait_for(lambda: self.has("receiver-exited"), "the receiver to exit")
        time.sleep(0.05)
        result = self.role("ncm")
        self.assertIn(b"DCV1_LISTENING_NOT_RENDERED", result.stdout)
        self.assertEqual(2, len(self.receiver_pids()))

    def test_receiver_that_never_reports_ready_is_stopped_later(self):
        self.put("no-ready")
        result = self.role("ncm")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"DCV1_START_UNCONFIRMED", result.stderr)
        stopped = self.role("host")
        self.assertEqual(0, stopped.returncode, stopped.stderr)
        self.assertFalse(self.alive(self.receiver_pids()[0]))

    def test_slow_cleanup_is_waited_for(self):
        self.assertEqual(0, self.role("ncm").returncode)
        self.put("exit-delay", "0.6")
        begin = time.monotonic()
        result = self.role("host")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreater(time.monotonic() - begin, 0.55)
        self.assertIn(b"DCV1_CLEANUP_REPORTED", result.stdout)

    def test_cleanup_that_never_finishes_is_not_forced(self):
        self.put("ignore-term")
        self.assertEqual(0, self.role("ncm").returncode)
        pid = self.receiver_pids()[0]
        result = self.role("host")
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"DCV1_STOP_UNCONFIRMED", result.stderr)
        self.assertTrue(self.alive(pid))
        self.assertFalse(self.host_node().exists())

    def test_failed_cleanup_exit_blocks_host(self):
        self.assertEqual(0, self.role("ncm").returncode)
        self.put("exit-code", "7")
        result = self.role("host")
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"DCV1_STOP_UNCONFIRMED", result.stderr)
        self.assertIn(b"HOST_VIDEO_STOP_UNCONFIRMED", result.stderr)

    def test_stopping_the_service_stops_the_receiver(self):
        self.assertEqual(0, self.role("ncm").returncode)
        pid = self.receiver_pids()[0]
        self.assertEqual(0, self.stop_service())
        self.assertFalse(self.alive(pid))
        self.assertFalse(self.has("lock"))

    def test_stuck_receiver_keeps_the_lock_when_the_service_stops(self):
        self.put("ignore-term")
        self.assertEqual(0, self.role("ncm").returncode)
        self.assertEqual(1, self.stop_service())
        self.assertTrue(self.has("lock"))


if __name__ == "__main__":
    unittest.main()
