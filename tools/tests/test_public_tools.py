"""Isolated synthetic fixtures only; no vehicle, firmware input or remote operations."""
import copy
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "analysis"))
from tools.check_public_tree import check, inspect_files, proposed_files
from sv_container import normalized, pack_sv, unpack_sv, verify_entries


class PublicCheckTests(unittest.TestCase):
    def setUp(self):
        fixture_parent = ROOT / "build/public-tool-tests"
        fixture_parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="dashcast-public-check-", dir=fixture_parent)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(fixture_parent.resolve()))

    def tearDown(self):
        self.assertTrue(self.root.is_relative_to((ROOT / "build/public-tool-tests").resolve()))
        self.temporary.cleanup()

    def put(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def test_clean_text_passes(self):
        self.put("README.md", b"# Synthetic test\n")
        self.assertEqual([], inspect_files(self.root, proposed_files(self.root)))

    def test_only_exact_reviewed_owned_background_pngs_are_allowed(self):
        from tools.check_public_tree import OWNED_BACKGROUNDS
        for name in OWNED_BACKGROUNDS:
            data = (ROOT / name).read_bytes()
            self.assertTrue(data.startswith(b'\x89PNG\r\n\x1a\n'))
            self.put(name, data)
        self.assertEqual([], inspect_files(self.root, proposed_files(self.root)))
        name = next(iter(OWNED_BACKGROUNDS))
        self.put(name, b'changed artwork')
        self.assertIn((name, 'CHANGED_OWNED_BACKGROUND'), inspect_files(self.root, proposed_files(self.root)))

    def test_unreviewed_png_remains_rejected(self):
        self.put('unreviewed.png', b'\x89PNG\r\n\x1a\n' + bytes(20))
        self.assertIn(('unreviewed.png', 'UNREVIEWED_BINARY'), inspect_files(self.root, proposed_files(self.root)))

    def test_kotlin_session_cache_excluded_but_explicit_tracking_refused(self):
        name = "phase1/android/.kotlin/sessions/synthetic-session"
        self.put(name, b"synthetic generated cache")
        self.assertNotIn(name, proposed_files(self.root))
        self.assertEqual([(name, "PRIVATE_OR_GENERATED_FILE_TRACKED")], inspect_files(self.root, [name]))

    def test_firmware_extension_rejected(self):
        self.put("unreviewed.sv", b"synthetic, not a firmware")
        self.assertIn(("unreviewed.sv", "BINARY_FIRMWARE_LOG_OR_KEY"), inspect_files(self.root, proposed_files(self.root)))

    def test_token_pattern_reports_only_path_and_category(self):
        fake = "ghp_" + "X" * 40
        self.put("config.txt", fake.encode())
        result = inspect_files(self.root, proposed_files(self.root))
        self.assertEqual([("config.txt", "PRIVATE_KEY_OR_ACCESS_TOKEN")], result)
        self.assertNotIn(fake, repr(result))

    def test_personal_path_rejected(self):
        self.put("README.md", ("C:" + "/" + "Users/Example/private").encode())
        self.assertIn(("README.md", "PERSONAL_WORKSPACE_OR_ID"), inspect_files(self.root, proposed_files(self.root)))

    def test_missing_local_link_rejected(self):
        self.put("README.md", b"[missing](docs/not-present.md)\n")
        self.assertIn(("README.md", "BROKEN_OR_EXTERNAL_LOCAL_DOCUMENT_LINK"), inspect_files(self.root, proposed_files(self.root)))

    def test_wrong_wrapper_rejected(self):
        self.put("phase1/android/gradle/wrapper/gradle-wrapper.jar", b"synthetic wrong wrapper")
        result = inspect_files(self.root, proposed_files(self.root))
        self.assertIn(("phase1/android/gradle/wrapper/gradle-wrapper.jar", "UNVERIFIED_GRADLE_WRAPPER"), result)

    def test_unexpected_large_file_rejected(self):
        self.put("large.txt", b"0" * (4 * 1024 * 1024 + 1))
        self.assertIn(("large.txt", "UNEXPECTED_LARGE_FILE"), inspect_files(self.root, proposed_files(self.root)))

    def test_parent_traversal_rejected(self):
        self.assertEqual([("../outside.txt", "UNSAFE_PATH_OR_LINK")], inspect_files(self.root, ["../outside.txt"]))

    def test_snapshot_changed_file_rejected(self):
        data = b"# Synthetic\n"
        self.put("README.md", data)
        manifest = dict(files=[dict(path="README.md", bytes=len(data), sha256=hashlib.sha256(data).hexdigest())])
        self.put("SOURCE_MANIFEST.json", json.dumps(manifest).encode())
        self.assertEqual("PASS_PUBLIC_FILE_CHECK", check(self.root, verify_snapshot=True)["status"])
        self.put("README.md", b"# Synthetic changed\n")
        with self.assertRaisesRegex(ValueError, "Snapshot hash differs"):
            check(self.root, verify_snapshot=True)

    @unittest.skipUnless(shutil.which("git"), "Git not installed")
    def test_force_added_ignored_firmware_cannot_bypass_check(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True, capture_output=True)
        self.put(".gitignore", b"*.sv\n")
        self.put("fake.sv", b"synthetic only")
        subprocess.run(["git", "add", "-f", "fake.sv"], cwd=self.root, check=True, capture_output=True)
        self.assertIn("fake.sv", proposed_files(self.root))
        self.assertIn(("fake.sv", "BINARY_FIRMWARE_LOG_OR_KEY"), inspect_files(self.root, proposed_files(self.root)))


class SvCodecTests(unittest.TestCase):
    def fixture(self):
        directory = tarfile.TarInfo("base")
        directory.type = tarfile.DIRTYPE
        directory.mode, directory.uid, directory.gid, directory.mtime = 0o700, 0, 0, 1
        member = tarfile.TarInfo("base/synthetic.txt")
        member.mode, member.uid, member.gid, member.mtime = 0o640, 1, 2, 3
        link = tarfile.TarInfo("base/synthetic-link")
        link.type, link.linkname = tarfile.SYMTYPE, "synthetic.txt"
        entries = {"base": (directory, None), "base/synthetic.txt": (member, b"synthetic data"),
                   "base/synthetic-link": (link, None)}
        tail = bytearray(512)
        tail[:19] = b"Desay SV Automotive"
        return entries, bytes(tail)

    def test_roundtrip_preserves_all_member_metadata(self):
        entries, tail = self.fixture()
        data = pack_sv(entries, tail)
        result, footer = unpack_sv(data)
        verify_entries(entries, result)
        self.assertEqual(512, len(footer))

    def test_pack_is_deterministic(self):
        entries, tail = self.fixture()
        self.assertEqual(pack_sv(entries, tail), pack_sv(entries, tail))

    def test_checksum_corruption_rejected(self):
        entries, tail = self.fixture()
        data = bytearray(pack_sv(entries, tail))
        data[-512 + 0x120] = ord("x")
        with self.assertRaisesRegex(ValueError, "checksum"):
            unpack_sv(bytes(data))

    def test_unknown_footer_rejected(self):
        entries, tail = self.fixture()
        data = bytearray(pack_sv(entries, tail))
        data[-512] = 0
        with self.assertRaisesRegex(ValueError, "unrecognized"):
            unpack_sv(bytes(data))

    def test_traversal_and_absolute_member_rejected(self):
        for name in ("/absolute", "../parent", "base/../parent"):
            with self.assertRaisesRegex(ValueError, "unsafe"):
                normalized(name)

    def test_metadata_change_rejected(self):
        entries, _ = self.fixture()
        changed = copy.deepcopy(entries)
        changed["base"][0].mode = 0o777
        with self.assertRaisesRegex(ValueError, "round-trip"):
            verify_entries(entries, changed)


if __name__ == "__main__":
    unittest.main()
