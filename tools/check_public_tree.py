"""Check files proposed for public Git; errors contain paths/categories, not values.

This is an allow-scope and common-pattern check, not a complete secrets scanner.
With Git, even ignored files forced into the index are checked. No network use.
"""
import argparse
import fnmatch
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WRAPPER = "phase1/android/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_HASH = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
FORBIDDEN_EXTENSIONS = {".sv", ".zip", ".apk", ".aab", ".img", ".bin", ".elf", ".so", ".dll",
                        ".h264", ".dcv1", ".ts", ".mp4", ".log", ".jks", ".keystore", ".pem",
                        ".p12", ".pfx", ".key", ".class", ".jar"}
GENERATED_PARTS = {".git", "build", "__pycache__", ".android-sdk", ".downloads", ".native-deps",
                   "_tmp", ".venv", ".pytest_cache", ".ruff_cache", "private-inputs"}
PATTERNS = {
    "PRIVATE_KEY_OR_ACCESS_TOKEN": re.compile(
        r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}"),
    "PERSONAL_WORKSPACE_OR_ID": re.compile(r"[A-Za-z]:[/\\]Users[/\\]|/[U]sers/|wxid_[A-Za-z0-9_]+"),
    "FIXED_SECRET_LITERAL": re.compile(
        r"\b(?:KEY|SECRET|TOKEN|PASSWORD)\s*=\s*[\"'][A-Za-z0-9+/=_-]{16,}[\"']"),
}


def generated(relative):
    return any(part in GENERATED_PARTS or part.startswith(".gradle") for part in relative.parts)


def proposed_files(root):
    root = Path(root).resolve()
    if (root / ".git").exists():
        top = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=root, check=True,
                             capture_output=True, timeout=10).stdout.decode().strip()
        if Path(top).resolve() != root:
            raise ValueError("Check the intended repository root, not a parent checkout")
        output = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                                cwd=root, check=True, capture_output=True, timeout=10).stdout
        return sorted({value.decode("utf-8") for value in output.split(b"\0") if value})
    names = []
    for directory, subdirs, files in os.walk(root, followlinks=False):
        current = Path(directory)
        kept = []
        for name in subdirs:
            path = current / name
            relative = path.relative_to(root)
            if generated(relative):
                continue
            if path.is_symlink():
                names.append(relative.as_posix())
            else:
                kept.append(name)
        subdirs[:] = kept
        for name in files:
            relative = (current / name).relative_to(root)
            if not generated(relative):
                names.append(relative.as_posix())
    return sorted(names)


def inspect_files(root, names):
    root = Path(root).resolve()
    issues = []
    for name in names:
        relative = Path(name)
        path = root / relative
        if relative.is_absolute() or ".." in relative.parts or path.is_symlink():
            issues.append((name, "UNSAFE_PATH_OR_LINK"))
            continue
        if not path.is_file() or not path.resolve().is_relative_to(root):
            issues.append((name, "MISSING_OR_ESCAPED_FILE"))
            continue
        if generated(relative):
            issues.append((name, "PRIVATE_OR_GENERATED_FILE_TRACKED"))
            continue
        if path.stat().st_size > 4 * 1024 * 1024:
            issues.append((name, "UNEXPECTED_LARGE_FILE"))
            continue
        data = path.read_bytes()
        if name == WRAPPER:
            if hashlib.sha256(data).hexdigest() != WRAPPER_HASH:
                issues.append((name, "UNVERIFIED_GRADLE_WRAPPER"))
            continue
        if path.suffix.lower() in FORBIDDEN_EXTENSIONS or fnmatch.fnmatch(path.name, "adbkey*"):
            issues.append((name, "BINARY_FIRMWARE_LOG_OR_KEY"))
            continue
        if path.name in {"ReferenceVideoProfile.java", "local.properties"} or path.name.startswith(".env"):
            issues.append((name, "PRIVATE_CONFIGURATION_OR_OLD_CREDENTIAL_FIXTURE"))
            continue
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            issues.append((name, "UNREVIEWED_BINARY"))
            continue
        for category, pattern in PATTERNS.items():
            if pattern.search(text):
                issues.append((name, category))
        if path.suffix == ".md":
            for match in re.finditer(r"\[[^\]]*\]\((<[^>]+>|[^)]+)\)", text):
                target = match[1].strip("<>").split("#", 1)[0]
                if not target or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target):
                    continue
                link = path.parent / target
                if not link.resolve().is_relative_to(root) or not link.exists():
                    issues.append((name, "BROKEN_OR_EXTERNAL_LOCAL_DOCUMENT_LINK"))
    return issues


def check(root, verify_snapshot=False, refresh_snapshot=False):
    root = Path(root).resolve()
    names = proposed_files(root)
    issues = inspect_files(root, names)
    if issues:
        return dict(status="FAIL_PUBLIC_FILE_CHECK", issues=issues)
    manifest_path = root / "SOURCE_MANIFEST.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else None
    if verify_snapshot:
        if manifest is None:
            raise ValueError("No source snapshot manifest")
        expected_names = {row["path"] for row in manifest["files"]} | {"SOURCE_MANIFEST.json"}
        if set(names) != expected_names:
            raise ValueError("Snapshot file set differs; review additions/removals")
        for row in manifest["files"]:
            data = (root / row["path"]).read_bytes().replace(b"\r\n", b"\n") \
                if row["path"].endswith(".bat") else (root / row["path"]).read_bytes()
            if len(data) != row["bytes"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
                raise ValueError("Snapshot hash differs: " + row["path"])
    if refresh_snapshot:
        if manifest is None:
            raise ValueError("Cannot refresh an absent snapshot manifest")
        old = {row["path"]: row for row in manifest["files"]}
        rows = []
        for name in names:
            if name == "SOURCE_MANIFEST.json":
                continue
            data = (root / name).read_bytes()
            if name.endswith(".bat"):
                data = data.replace(b"\r\n", b"\n")
            row = dict(old.get(name, dict(path=name, origin="REVIEWED_AFTER_INITIAL_EXPORT", transformations=[])))
            row.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
            rows.append(row)
        manifest["files"] = rows
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return dict(status="PASS_PUBLIC_FILE_CHECK", files_checked=len(names), snapshot_verified=verify_snapshot,
                snapshot_refreshed=refresh_snapshot, project_license_selected=(root / "LICENSE").exists())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--verify-snapshot", action="store_true")
    mode.add_argument("--refresh-snapshot", action="store_true",
                      help="Only after manual review of the new public file set")
    args = parser.parse_args()
    result = check(args.root, args.verify_snapshot, args.refresh_snapshot)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["status"] != "PASS_PUBLIC_FILE_CHECK":
        raise SystemExit(1)
