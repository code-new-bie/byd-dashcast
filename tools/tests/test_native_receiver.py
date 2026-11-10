"""dashcast-receiver compiled for this machine and driven over real TCP connections.

Screen, mm-renderer and the QNX message channel are fixtures. This checks the program's protocol and
state handling; it says nothing about the ARM build, the QNX loader or a real decoder.
"""
import os
import re
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "instrument/native"

START, FRAME, STOP, CLOSE, HELLO, HEARTBEAT, BACKGROUND = 1, 2, 3, 4, 7, 8, 10
FLAG_CONTEXT, FLAG_FRAME, FLAG_CHILD, FLAG_CLEANUP, FLAG_VISIBLE = 1, 2, 4, 8, 16
PAGE_MAGIC, RENEW_CAPABILITY = 0x31474244, 0x52575642
BG_OK, BG_BAD_REQUEST, BG_NO_FILE, BG_NOT_READY, BG_BUSY, BG_WRONG_OWNER, BG_API_ERROR = range(7)


class Bits:
    def __init__(self):
        self.text = ""

    def u(self, value, size):
        self.text += format(value, f"0{size}b")
        return self

    def ue(self, value):
        coded = value + 1
        self.text += "0" * (coded.bit_length() - 1) + format(coded, "b")
        return self

    def done(self):
        text = self.text + "1"
        text += "0" * (-len(text) % 8)
        return int(text, 2).to_bytes(len(text) // 8, "big")


NAL = b"\0\0\0\1"


def sps(width_mbs=120, height_mbs=45, profile=0x42):
    return NAL + b"\x67" + (Bits().u(profile, 8).u(0, 8).u(31, 8).ue(0).ue(0).ue(2).ue(1).u(0, 1).ue(width_mbs - 1)
                            .ue(height_mbs - 1).u(1, 1).u(1, 1).u(0, 1).done())


def pps():
    return NAL + b"\x68" + Bits().ue(0).ue(0).done()


def slice_unit(key):
    return NAL + (b"\x65" if key else b"\x41") + Bits().ue(0).ue(7 if key else 5).ue(0).u(0x2a, 8).done()


KEY_FRAME = sps() + pps() + slice_unit(True)
NEXT_FRAME = slice_unit(False)


def rect(profile=1):
    box = (640, 240, 640, 240) if profile == 0 else (0, 0, 1920, 720)
    return struct.pack(">6I", *box, 30, profile)


class Client:
    def __init__(self, port, source=None):
        self.sock = socket.socket()
        self.sock.settimeout(5)
        if source:
            self.sock.bind((source, 0))
        self.sock.connect(("127.0.0.1", port))

    def close(self):
        self.sock.close()

    def receive(self, size):
        data = b""
        while len(data) < size:
            part = self.sock.recv(size - len(data))
            if not part:
                raise EOFError(f"closed after {len(data)} of {size} bytes")
            data += part
        return data

    def offer(self):
        return self.receive(104)

    def send(self, kind, payload=b"", sequence=0, pts=0, flags=0, length=None, magic=b"DCV1"):
        declared = len(payload) if length is None else length
        self.sock.sendall(magic + struct.pack(">5I", kind, declared, sequence, pts, flags) + payload)

    def ack(self):
        data = self.receive(40)
        self.assert_magic(data)
        kind, length, sequence, pts, zero, result, flags, submissions, last = struct.unpack(">9I", data[4:40])
        return {"type": kind, "length": length, "sequence": sequence, "pts": pts, "zero": zero, "result": result, "flags": flags,
                "submissions": submissions, "last": last}

    @staticmethod
    def assert_magic(data):
        if data[:4] != b"DCV1":
            raise AssertionError(data[:8])

    def hello(self, version=3):
        self.offer()
        self.send(HELLO, struct.pack(">II", 0, 0) + b"DCV1" + struct.pack(">I", version))
        return self.ack()

    def closed(self):
        try:
            return self.sock.recv(1) == b""
        except ConnectionResetError:
            return True


@unittest.skipIf(os.name == "nt" or not shutil.which("gcc"), "Needs a Linux C compiler")
class NativeReceiver(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="dashcast-receiver-")
        base = Path(cls.temp.name)
        cls.program = base / "dashcast-receiver"
        cls.control = base / "window-control"
        cls.page = base / "background-page"
        common = ["gcc", "-O1", "-Wall", "-Wextra", "-Werror", "-Wno-unused-result"]
        library = base / "libscreen.so.1"
        cls.run_dir = base / "run"
        for arguments in ([f'-DRUN_DIR="{cls.run_dir}"', f'-DPPS_WINDOW_CONTROL="{cls.control}"', f'-DBACKGROUND_PAGE="{cls.page}"', str(NATIVE / "dashcast_receiver.c"),
                           str(NATIVE / "tests/fake_push.c"), "-o", str(cls.program), "-ldl"],
                          ["-shared", "-fPIC", str(NATIVE / "tests/fake_display.c"), "-o", str(library)]):
            result = subprocess.run(common + arguments, capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError(result.stderr)
        for name in ("libmmrndclient.so.1", "libstrm.so.1"):
            (base / name).symlink_to(library.name)
        cls.environment = dict(os.environ, LD_LIBRARY_PATH=str(base))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="case-", dir=self.temp.name))
        self.addCleanup(shutil.rmtree, self.work, ignore_errors=True)
        self.log = self.work / "calls"
        self.errors = self.work / "stderr"
        for path in (self.control, self.page):
            if path.exists():
                path.unlink()
        self.control.write_bytes(b"")

    @staticmethod
    def free_port():
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            return probe.getsockname()[1]

    def start(self, peer="127.0.0.1", **env):
        self.port = self.free_port()
        output = self.errors.open("wb")
        self.addCleanup(output.close)
        self.process = subprocess.Popen([str(self.program), "--listen", "127.0.0.1", str(self.port), "--client", peer], stderr=output,
                                        env=dict(self.environment, FAKE_LOG=str(self.log), **env))
        self.addCleanup(self.stop_process)
        self.wait_for(lambda: b"DCV1_PORT_OPEN" in self.errors.read_bytes(), "the listener")

    def stop_process(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait()

    def wait_for(self, condition, what, seconds=5):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition():
                return
            time.sleep(0.01)
        self.fail("Timed out waiting for " + what)

    def client(self, source=None):
        peer = Client(self.port, source)
        self.addCleanup(peer.close)
        return peer

    def calls(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def session(self, version=3, profile=1):
        peer = self.client()
        self.assertEqual(peer.hello(version)["result"], 0)
        peer.send(START, rect(profile))
        return peer, peer.ack()

    def send_key(self, peer, sequence=1, pts=100):
        peer.send(FRAME, KEY_FRAME, sequence=sequence, pts=pts, flags=1)
        return peer.ack()

    def assert_display_closed(self):
        self.wait_for(lambda: "screen_destroy_context" in self.calls(), "the display to close")

    def assert_still_listening(self):
        self.assertIsNone(self.process.poll())
        peer = self.client()
        self.assertEqual(peer.offer()[:8], b"DCV1\0\0\0\x85")
        peer.close()

    # ---- Command line ----

    def run_once(self, *arguments):
        return subprocess.run([str(self.program), *arguments], capture_output=True, timeout=10, env=self.environment)

    def test_wrong_arguments_print_usage_and_exit_2(self):
        result = self.run_once("--listen", "127.0.0.1")
        self.assertEqual(result.returncode, 2)
        self.assertIn(b"usage:", result.stderr)

    def test_port_outside_range_exits_2(self):
        self.assertEqual(self.run_once("--listen", "127.0.0.1", "65536", "--client", "127.0.0.1").returncode, 2)

    def test_unusable_addresses_exit_1(self):
        self.assertEqual(self.run_once("--listen", "not-an-address", "9", "--client", "127.0.0.1").returncode, 1)
        self.assertEqual(self.run_once("--listen", "0.0.0.0", "9", "--client", "127.0.0.1").returncode, 1)

    def test_port_already_taken_exits_1(self):
        with socket.socket() as holder:
            holder.bind(("127.0.0.1", 0))
            holder.listen(1)
            result = self.run_once("--listen", "127.0.0.1", str(holder.getsockname()[1]), "--client", "127.0.0.1")
        self.assertEqual(result.returncode, 1)
        self.assertIn(b"DCV1_PORT_FAILED", result.stderr)

    # ---- Connection and handshake ----

    def test_ready_descriptor_is_written_once_listening(self):
        read_end, write_end = os.pipe()
        self.addCleanup(os.close, read_end)
        port = self.free_port()
        process = subprocess.Popen([str(self.program), "--listen", "127.0.0.1", str(port), "--client", "127.0.0.1", "--ready-fd", str(write_end)],
                                   stderr=subprocess.DEVNULL, pass_fds=[write_end], env=dict(self.environment, FAKE_LOG=str(self.log)))
        os.close(write_end)
        self.addCleanup(process.wait)
        self.addCleanup(process.kill)
        self.assertEqual(b"R", os.read(read_end, 1))
        self.assertEqual(b"", os.read(read_end, 1))
        with socket.create_connection(("127.0.0.1", port), timeout=5):
            pass

    def test_bad_ready_descriptor_exits_2(self):
        self.assertEqual(2, self.run_once("--listen", "127.0.0.1", "9", "--client", "127.0.0.1", "--ready-fd", "x").returncode)
        self.assertEqual(2, self.run_once("--listen", "127.0.0.1", "9", "--client", "127.0.0.1", "--other", "3").returncode)

    def fetch_log(self, which=0, version=1):
        peer = self.client()
        peer.offer()
        peer.send(11, struct.pack(">II", version, which))
        header = peer.receive(24)
        self.assertEqual(b"DCV1", header[:4])
        kind, length, sequence, pts, flags = struct.unpack(">5I", header[4:24])
        self.assertEqual((0x8b, 0, 0, 0), (kind, sequence, pts, flags))
        text = peer.receive(length)
        peer.close()
        return text

    def write_run_log(self, name, data):
        self.run_dir.mkdir(exist_ok=True)
        (self.run_dir / name).write_bytes(data)

    def test_version_is_reported(self):
        self.start()
        text = (NATIVE / "version.h").read_text()
        self.assertEqual(re.search(r'"([^"]+)"', text.split("#define DASHCAST_VERSION", 1)[1]).group(1).encode(), self.fetch_log(2))

    def test_version_matches_the_app(self):
        native = re.search(r'#define DASHCAST_VERSION "([^"]+)"', (NATIVE / "version.h").read_text()).group(1)
        app = re.search(r'versionName "([^"]+)"', (ROOT / "android/app/build.gradle").read_text()).group(1)
        self.assertEqual(app, native)

    def test_each_connection_counts_its_own_frames(self):
        self.start()
        first, _ = self.session()
        self.assertEqual(1, self.send_key(first)["submissions"])
        first.send(STOP)
        first.ack()
        first.close()
        second = self.client()
        self.assertEqual(0, second.hello()["submissions"])
        second.send(START, rect())
        self.assertEqual((0, 0), (lambda a: (a["result"], a["submissions"]))(second.ack()))
        self.assertEqual(1, self.send_key(second)["submissions"])

    def test_logs_can_be_collected(self):
        self.start()
        self.write_run_log("usb.log", b"usb lines\n")
        self.write_run_log("cast.log", b"cast lines\n")
        self.assertEqual(b"usb lines\n", self.fetch_log(0))
        self.assertEqual(b"cast lines\n", self.fetch_log(1))

    def test_only_the_end_of_a_long_log_is_sent(self):
        self.start()
        self.write_run_log("usb.log", b"a" * 20000 + b"b" * 16384)
        self.assertEqual(b"b" * 16384, self.fetch_log(0))

    def test_missing_log_or_bad_request_is_empty(self):
        self.start()
        shutil.rmtree(self.run_dir, ignore_errors=True)
        self.assertEqual(b"", self.fetch_log(0))
        self.write_run_log("usb.log", b"x")
        self.assertEqual(b"", self.fetch_log(3))
        self.assertEqual(b"", self.fetch_log(0, version=2))

    def test_log_request_keeps_the_session(self):
        self.start()
        self.write_run_log("usb.log", b"x")
        peer, ack = self.session()
        self.assertEqual(0, ack["result"])
        peer.send(11, struct.pack(">II", 1, 0))
        self.assertEqual(25, len(peer.receive(25)))
        peer.send(STOP)
        self.assertEqual(0, peer.ack()["result"])

    def test_only_the_peer_address_is_served(self):
        self.start(peer="127.0.0.2")
        stranger = self.client("127.0.0.1")
        self.assertTrue(stranger.closed())
        self.assertEqual(self.client("127.0.0.2").offer()[:8], b"DCV1\0\0\0\x85")

    def test_sessions_are_served_one_after_another(self):
        self.start()
        first = self.client()
        first.hello()
        waiting = self.client()
        with self.assertRaises(TimeoutError):
            waiting.sock.settimeout(0.3)
            waiting.receive(1)
        waiting.sock.settimeout(5)
        first.close()
        self.assertEqual(waiting.offer()[:4], b"DCV1")

    def test_offer_is_header_and_80_zero_bytes(self):
        self.start()
        offer = self.client().offer()
        self.assertEqual(offer[:4], b"DCV1")
        self.assertEqual(struct.unpack(">5I", offer[4:24]), (0x85, 80, 0, 0, 0))
        self.assertEqual(offer[24:], bytes(80))

    def test_each_protocol_version_is_accepted(self):
        self.start()
        for version in (1, 2, 3):
            with self.subTest(version=version):
                peer = self.client()
                ack = peer.hello(version)
                self.assertEqual((ack["type"], ack["length"], ack["result"]), (HELLO | 0x80, 16, 0))
                peer.close()

    def test_bad_hello_is_answered_with_2_and_closed(self):
        self.start()
        cases = {"version 4": b"\0" * 8 + b"DCV1" + struct.pack(">I", 4), "version 0": b"\0" * 8 + b"DCV1" + struct.pack(">I", 0),
                 "wrong tag": b"\0" * 8 + b"XXXX" + struct.pack(">I", 3), "stray bytes": b"\1" + b"\0" * 7 + b"DCV1" + struct.pack(">I", 3)}
        for name, payload in cases.items():
            with self.subTest(name):
                peer = self.client()
                peer.offer()
                peer.send(HELLO, payload)
                self.assertEqual(peer.ack()["result"], 2)
                self.assertTrue(peer.closed())
                peer.close()

    def test_first_message_must_be_a_hello(self):
        self.start()
        peer = self.client()
        peer.offer()
        peer.send(START, rect())
        self.assertEqual(peer.ack()["result"], 2)
        self.assertTrue(peer.closed())
        self.assertNotIn("screen_create_context", self.calls())

    def test_malformed_headers_close_without_a_reply(self):
        self.start()
        cases = {"bad magic": dict(kind=HELLO, payload=bytes(16), magic=b"XCV1"), "unknown type": dict(kind=9, payload=bytes(16)),
                 "wrong size": dict(kind=HELLO, payload=bytes(12)), "fields set on control": dict(kind=HELLO, payload=bytes(16), pts=1)}
        for name, fields in cases.items():
            with self.subTest(name):
                peer = self.client()
                peer.offer()
                peer.send(**fields)
                self.assertTrue(peer.closed())

    def test_heartbeat_counter_must_advance_by_one(self):
        self.start()
        peer = self.client()
        peer.hello(3)
        peer.send(HEARTBEAT, bytes(16) + struct.pack(">I", 1))
        self.assertEqual(peer.ack()["result"], 0)
        peer.send(HEARTBEAT, bytes(16) + struct.pack(">I", 3))
        self.assertEqual(peer.ack()["result"], 2)
        self.assertTrue(peer.closed())

    # ---- A full session ----

    def test_start_opens_display_and_decoder_in_order(self):
        self.start()
        _, ack = self.session()
        self.assertEqual((ack["result"], ack["flags"]), (0, FLAG_CONTEXT))
        calls = self.calls()
        order = [next(i for i, line in enumerate(calls) if line.startswith(name))
                 for name in ("screen_create_context", "screen_create_window", "screen_create_window_buffers", "screen_post_window",
                              "screen_create_event", "mmr_connect", "mmr_context_create", "mmr_output_attach", "mmr_input_attach", "mmr_play",
                              "name_open")]
        self.assertEqual(order, sorted(order))
        post = next(line for line in calls if line.startswith("screen_post_window"))
        self.assertIn("uncleared=0 ", post)
        name = f"dashcast_{self.process.pid}"
        self.assertIn(f"mmr_output_attach screen:/any?wingrp={name}&winembed=1&winid={name}_picture&nodstviewport=1&initflags=invisible video", calls)
        self.assertIn(f"mmr_input_attach mdevpush:/dev/name/local/{name}?fourcc=H264&video.frame_rate=30&video.depth=24&video.width=1920"
                      "&video.height=720&queue_capacity=2 track", calls)
        self.assertIn("set_display 2 (property 0xb)", calls)
        self.assertIn("push kind=2 declared=32 total=56 pts=0 bytes=00000000", calls)

    def test_decoder_gets_low_latency_settings(self):
        self.start()
        self.session()
        calls = self.calls()
        for pair in ("prefetch_mode=-1", "mmf.SCREEN_WRITER_DISABLE_BACKGROUND=1", "VideoDecoderLowLatencyMode=1", "ColorSpaceMode=0",
                     "mmf.SCREEN_WRITER_DISABLE_MEDIACLOCK=1", "mmf.SCREEN_WRITER_POST_BUFFER_NOSYNC=1"):
            self.assertIn("dict " + pair, calls)

    def test_frames_reach_the_decoder_and_are_acknowledged(self):
        self.start()
        peer, _ = self.session()
        ack = self.send_key(peer, sequence=1, pts=100)
        self.assertEqual(ack["type"], FRAME | 0x80)
        self.assertEqual((ack["sequence"], ack["pts"], ack["result"], ack["submissions"], ack["last"]), (1, 100, 0, 1, 100))
        self.assertEqual(ack["flags"], FLAG_CONTEXT | FLAG_FRAME | FLAG_CHILD | FLAG_VISIBLE)
        peer.send(FRAME, NEXT_FRAME, sequence=2, pts=133)
        ack = peer.ack()
        self.assertEqual((ack["result"], ack["submissions"], ack["last"]), (0, 2, 133))
        pushes = [line for line in self.calls() if line.startswith("push kind=3")]
        self.assertEqual(pushes, [f"push kind=3 declared={len(KEY_FRAME) + 8} total={len(KEY_FRAME) + 32} pts=0 bytes=00000001",
                                  f"push kind=3 declared={len(NEXT_FRAME) + 8} total={len(NEXT_FRAME) + 32} pts=33000 bytes=00000001"])

    def test_stop_closes_everything_and_the_session_can_restart(self):
        self.start()
        peer, _ = self.session()
        self.send_key(peer)
        peer.send(STOP)
        ack = peer.ack()
        self.assertEqual((ack["result"], ack["flags"]), (0, 0))
        calls = self.calls()
        order = [calls.index(name) for name in ("mmr_context_destroy", "mmr_disconnect", "screen_destroy_event", "screen_destroy_window",
                                                "screen_destroy_context")]
        self.assertEqual(order, sorted(order))
        self.assertIn("name_close 33", calls)
        self.assertIn("set child 0x33 0", calls)
        peer.send(START, rect())
        self.assertEqual(peer.ack()["result"], 0)
        self.assertEqual(self.calls().count("screen_create_context"), 2)

    def test_close_message_ends_the_connection_and_the_display(self):
        self.start()
        peer, _ = self.session()
        peer.send(CLOSE)
        self.assertTrue(peer.closed())
        self.assert_display_closed()
        self.assert_still_listening()

    def test_client_disconnect_closes_the_display(self):
        self.start()
        peer, _ = self.session()
        peer.close()
        self.assert_display_closed()
        self.assert_still_listening()

    def test_terminate_closes_the_display_and_exits_cleanly(self):
        self.start()
        self.session()
        self.process.send_signal(signal.SIGTERM)
        self.assertEqual(self.process.wait(timeout=5), 0)
        self.assertIn("screen_destroy_context", self.calls())

    def test_second_start_is_refused(self):
        self.start()
        peer, _ = self.session()
        peer.send(START, rect())
        self.assertEqual(peer.ack()["result"], 1)
        self.assert_display_closed()

    def test_full_frame_window_goes_under_the_hmi(self):
        self.start()
        peer, _ = self.session(profile=1)
        self.send_key(peer)
        self.assertEqual(self.control.read_text(), f"dashcast_{self.process.pid}::-3\n")
        self.assertIn("set parent 0x36 -3", self.calls())
        self.assertIn("set child 0x36 1", self.calls())

    def test_small_window_goes_above_the_hmi(self):
        self.start()
        peer, ack = self.session(profile=0)
        self.assertEqual(ack["result"], 0)
        self.send_key(peer)
        self.assertEqual(self.control.read_text(), f"dashcast_{self.process.pid}::12\n")
        calls = self.calls()
        self.assertIn("set parent 0x23 640", calls)
        self.assertIn("set parent 0x36 1", calls)

    # ---- Rejected input ----

    def test_invalid_start_rectangle_is_refused(self):
        self.start()
        for name, payload in {"profile 3": struct.pack(">6I", 0, 0, 1920, 720, 30, 3),
                              "wrong rectangle": struct.pack(">6I", 0, 0, 1280, 720, 30, 1),
                              "wrong rate": struct.pack(">6I", 0, 0, 1920, 720, 25, 1)}.items():
            with self.subTest(name):
                peer = self.client()
                peer.hello()
                peer.send(START, payload)
                self.assertEqual(peer.ack()["result"], 1)
                self.assertTrue(peer.closed())
        self.assertNotIn("screen_create_context", self.calls())

    def test_frame_before_start_is_a_parser_error(self):
        self.start()
        peer = self.client()
        peer.hello()
        peer.send(FRAME, KEY_FRAME, sequence=1, pts=1, flags=1)
        self.assertEqual(peer.ack()["result"], 2)
        self.assertTrue(peer.closed())

    def test_frames_that_would_break_the_decoder_are_rejected(self):
        self.start()
        cases = {"wrong size": sps(width_mbs=80) + pps() + slice_unit(True), "not baseline": sps(profile=0x64) + pps() + slice_unit(True),
                 "first frame without parameters": slice_unit(True), "no start code": b"\x65\x88\x84",
                 "parameters after the picture": slice_unit(True) + sps() + pps()}
        for name, payload in cases.items():
            with self.subTest(name):
                peer, _ = self.session()
                peer.send(FRAME, payload, sequence=1, pts=1, flags=1)
                self.assertEqual(peer.ack()["result"], 2)
                self.assertTrue(peer.closed())
                peer.close()
                self.assert_still_listening()

    def test_key_flag_must_match_the_picture(self):
        self.start()
        peer, _ = self.session()
        peer.send(FRAME, KEY_FRAME, sequence=1, pts=1, flags=0)
        self.assertEqual(peer.ack()["result"], 2)

    def test_sequence_gap_is_rejected(self):
        self.start()
        peer, _ = self.session()
        self.send_key(peer, sequence=1)
        peer.send(FRAME, NEXT_FRAME, sequence=3, pts=133)
        self.assertEqual(peer.ack()["result"], 2)
        self.assertTrue(peer.closed())

    def test_timestamp_jump_is_rejected(self):
        self.start()
        peer, _ = self.session()
        self.send_key(peer, sequence=1, pts=100)
        peer.send(FRAME, NEXT_FRAME, sequence=2, pts=2101)
        self.assertEqual(peer.ack()["result"], 2)

    def test_sequence_zero_only_passes_the_header_on_version_3(self):
        self.start()
        peer = self.client()
        peer.hello(2)
        peer.send(START, rect())
        peer.ack()
        peer.send(FRAME, KEY_FRAME, sequence=0, pts=1, flags=1)
        self.assertTrue(peer.closed())
        peer = self.client()
        peer.hello(3)
        peer.send(START, rect())
        peer.ack()
        peer.send(FRAME, KEY_FRAME, sequence=0, pts=1, flags=1)
        self.assertEqual(peer.ack()["result"], 2)

    def test_oversized_frame_header_closes_without_reading_it(self):
        self.start()
        peer, _ = self.session()
        peer.send(FRAME, b"", sequence=1, pts=1, length=0x200001)
        self.assertTrue(peer.closed())
        self.assert_display_closed()

    # ---- Timeouts ----

    def test_version_1_ends_a_session_after_idling_1500_ms(self):
        self.start()
        peer, _ = self.session(version=1)
        began = time.monotonic()
        peer.sock.settimeout(4)
        self.assertTrue(peer.closed())
        self.assertLess(abs(time.monotonic() - began - 1.5), 0.5)
        self.assert_display_closed()

    def test_missing_window_gives_up_2_seconds_after_the_first_frame(self):
        self.start(FAKE_NO_WINDOW="1")
        peer, _ = self.session()
        ack = self.send_key(peer, sequence=1, pts=0)
        self.assertEqual((ack["result"], ack["flags"]), (0, FLAG_CONTEXT | FLAG_FRAME))
        began = time.monotonic()
        sequence, result = 1, 0
        while not result:
            self.assertLess(time.monotonic() - began, 4)
            time.sleep(0.2)
            sequence += 1
            peer.send(FRAME, NEXT_FRAME, sequence=sequence, pts=sequence * 33)
            result = peer.ack()["result"]
        self.assertEqual(result, 4)
        self.assertGreater(time.monotonic() - began, 1.8)
        self.assertTrue(peer.closed())

    # ---- Decoder window ----

    def test_another_programs_window_is_left_alone(self):
        self.start(FAKE_WINDOW_ID="somebody_else")
        peer, _ = self.session()
        ack = self.send_key(peer)
        self.assertEqual(ack["flags"], FLAG_CONTEXT | FLAG_FRAME)
        self.assertFalse([line for line in self.calls() if line.startswith("set child")])

    def test_events_are_limited_per_frame_so_the_window_can_come_on_the_next_one(self):
        self.start(FAKE_OTHER_EVENTS="20")
        peer, _ = self.session()
        self.assertEqual(self.send_key(peer)["flags"] & FLAG_CHILD, 0)
        peer.send(FRAME, NEXT_FRAME, sequence=2, pts=133)
        self.assertEqual(peer.ack()["flags"] & FLAG_CHILD, FLAG_CHILD)

    def test_window_not_where_it_was_put_is_an_error(self):
        self.start(FAKE_MOVED="1")
        peer, _ = self.session()
        ack = self.send_key(peer)
        self.assertEqual((ack["result"], ack["flags"]), (4, 0))
        self.assertTrue(peer.closed())

    # ---- Failures in the display path ----

    def test_a_failed_open_releases_what_it_created(self):
        self.start(FAKE_FAIL="mmr_play")
        peer, ack = self.session()
        self.assertEqual((ack["result"], ack["flags"]), (1, 0))
        self.assertTrue(peer.closed())
        calls = self.calls()
        for name in ("mmr_context_destroy", "mmr_disconnect", "screen_destroy_event", "screen_destroy_window", "screen_destroy_context"):
            self.assertIn(name, calls)
        self.assertIn(b"VIDEO_WINDOW_OPEN_FAILED", self.errors.read_bytes())
        self.assert_still_listening()

    def test_only_one_matching_display_is_acceptable(self):
        for widths in ("1280,1024", "1920,1920"):
            with self.subTest(widths):
                self.start(FAKE_DISPLAYS=widths)
                _, ack = self.session()
                self.assertEqual(ack["result"], 1)
                self.stop_process()

    def test_decoder_input_may_take_a_moment_to_appear(self):
        self.start(FAKE_NAME_OPEN_FAILS="5")
        self.assertEqual(self.session()[1]["result"], 0)

    def test_decoder_input_that_never_appears_fails_the_start(self):
        self.start(FAKE_NAME_OPEN_FAILS="1000")
        peer = self.client()
        peer.hello()
        peer.send(START, rect())
        peer.sock.settimeout(5)
        self.assertEqual(peer.ack()["result"], 1)

    def test_full_decoder_queue_is_retried(self):
        self.start(FAKE_PUSH_BUSY="4")
        peer, ack = self.session()
        self.assertEqual(ack["result"], 0)
        self.assertEqual(self.send_key(peer)["result"], 0)

    def test_decoder_queue_stuck_full_is_a_submit_error(self):
        self.start(FAKE_PUSH_BUSY="1000000")
        peer = self.client()
        peer.hello()
        peer.send(START, rect())
        began = time.monotonic()
        self.assertEqual(peer.ack()["result"], 1)
        self.assertLess(abs(time.monotonic() - began - 0.25), 0.2)

    def test_submit_failure_closes_the_session_and_service_continues(self):
        self.start(FAKE_PUSH_ERROR_AT="2")
        peer, _ = self.session()
        ack = self.send_key(peer)
        self.assertEqual((ack["result"], ack["submissions"], ack["flags"]), (3, 0, 0))
        self.assertTrue(peer.closed())
        peer, ack = self.session()
        self.assertEqual(ack["result"], 0)
        self.assertEqual(self.send_key(peer)["result"], 0)

    def test_a_failed_cleanup_is_reported_and_stops_the_service(self):
        self.start(FAKE_FAIL="screen_destroy_window")
        peer, _ = self.session()
        peer.send(STOP)
        ack = peer.ack()
        self.assertEqual(ack["result"], 1)
        self.assertEqual(ack["flags"] & FLAG_CLEANUP, FLAG_CLEANUP)
        self.assertEqual(self.process.wait(timeout=5), 1)
        self.assertIn(b"VIDEO_WINDOW_CLEANUP_FAILED", self.errors.read_bytes())

    # ---- Background control ----

    def write_page(self, **fields):
        words = [0] * 1024
        now = time.clock_gettime_ns(time.CLOCK_MONOTONIC)
        words[0:3] = [PAGE_MAGIC, 1, 128]
        words[10], words[12], words[13], words[18] = 77, now // 1_000_000_000, now % 1_000_000_000, 9
        for name, value in fields.items():
            words[int(name[1:])] = value
        self.page.write_bytes(struct.pack("<1024I", *words))

    def page_word(self, index):
        return struct.unpack_from("<I", self.page.read_bytes(), index * 4)[0]

    def background(self, op=0, nonce=0, hold=0, version=1):
        peer = self.client()
        peer.offer()
        peer.send(BACKGROUND, struct.pack(">4I", version, op, nonce, hold))
        data = peer.receive(168)
        peer.close()
        self.assertEqual(data[:4], b"DCV1")
        self.assertEqual(struct.unpack(">5I", data[4:24]), (0x8a, 0x90, 0, 0, 0))
        self.assertEqual(data[24:32], struct.pack(">I", 1) + b"BGP1")
        reported, code = struct.unpack(">2I", data[32:40])
        return reported, code, data[40:]

    def test_background_status_reports_the_page(self):
        self.start()
        self.write_page(w3=4, w8=4, w26=6)
        reported, code, snapshot = self.background()
        self.assertEqual((reported, code), (0, BG_OK))
        self.assertEqual(snapshot[:4], struct.pack(">I", PAGE_MAGIC))
        self.assertEqual(snapshot[12:16], struct.pack(">I", 4))
        self.assertEqual(snapshot[104:108], struct.pack(">I", 6))

    def test_background_reports_a_missing_or_wrong_page(self):
        self.start()
        self.assertEqual(self.background()[1], BG_NO_FILE)
        self.page.write_bytes(b"short")
        self.assertEqual(self.background()[1], BG_API_ERROR)

    def test_background_not_ready_when_the_page_is_stale_or_unset(self):
        self.start()
        for fields in ({"w0": 1}, {"w1": 2}, {"w2": 64}, {"w10": 0}, {"w18": 0}, {"w12": 0}):
            with self.subTest(fields):
                self.write_page(**fields)
                self.assertEqual(self.background()[1], BG_NOT_READY)

    def test_background_acquire_publishes_through_the_seqlock(self):
        self.start()
        self.write_page(w3=4, w8=4)
        self.assertEqual(self.background(1, 5, 5000)[1], BG_OK)
        self.assertEqual([self.page_word(i) for i in (3, 4, 5, 19, 20)], [6, 1, 77, 9, 5])

    def test_background_acquire_refused_when_held_or_mid_update(self):
        self.start()
        for fields in ({"w9": 1}, {"w3": 5, "w8": 5}, {"w3": 4, "w8": 2}):
            with self.subTest(fields):
                self.write_page(**fields)
                self.assertEqual(self.background(1, 5, 5000)[1], BG_BUSY)

    def test_background_release_needs_the_owners_nonce(self):
        self.start()
        self.write_page(w9=1, w20=5)
        self.assertEqual(self.background(2, 6, 0)[1], BG_WRONG_OWNER)
        self.assertEqual(self.page_word(3), 0)
        self.assertEqual(self.background(2, 5, 0)[1], BG_OK)
        self.assertEqual([self.page_word(i) for i in (3, 4)], [2, 0])

    def test_background_renew_needs_owner_and_capability(self):
        self.start()
        self.write_page(w20=5, w31=RENEW_CAPABILITY)
        self.assertEqual(self.background(3, 5, 5000)[1], BG_WRONG_OWNER)
        self.write_page(w9=1, w20=5)
        self.assertEqual(self.background(3, 5, 5000)[1], BG_NOT_READY)
        self.write_page(w9=1, w20=5, w31=RENEW_CAPABILITY)
        self.assertEqual(self.background(3, 5, 5000)[1], BG_OK)
        self.assertEqual(self.page_word(4), 2)

    def test_background_rejects_bad_requests(self):
        self.start()
        self.write_page()
        self.assertEqual(self.background(version=2)[:2], (0xffffffff, BG_BAD_REQUEST))
        self.assertEqual(self.background(op=4, nonce=1)[:2], (4, BG_BAD_REQUEST))
        self.assertEqual(self.background(op=1, nonce=0, hold=5000)[:2], (1, BG_BAD_REQUEST))
        self.assertEqual(self.background(op=1, nonce=5, hold=1000)[:2], (1, BG_BAD_REQUEST))
        self.assertEqual(self.background(op=0, nonce=1)[:2], (0, BG_BAD_REQUEST))


if __name__ == "__main__":
    unittest.main()
