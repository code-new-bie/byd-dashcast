"""Input checks shared by the instrument component tools. No firmware is read here."""
from pathlib import Path

# bic_byd_stha.kzb.txt shipped next to the original KZB in the factory app.sv.
TOC_SHA = "402c609076bc539bd552652f00fd63440f59d28e8cc908ca494f249060ad106e"


def prepare_paths(hmi, kzb, toc, output):
    for path in (hmi, kzb, toc, output):
        if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
            raise ValueError("Symlink input/output refused")
    hmi, kzb, toc, output = (path.resolve() for path in (hmi, kzb, toc, output))
    for path, limit in ((hmi, 16 * 1024 * 1024), (kzb, 256 * 1024 * 1024), (toc, 4 * 1024 * 1024)):
        if not path.is_file() or not 1 <= path.stat().st_size <= limit:
            raise ValueError("Missing or unbounded original component")
    if output.exists() or not output.parent.is_dir():
        raise ValueError("Existing parent and NEW output directory required")
    return hmi, kzb, toc, output


def checked_paths(hmi, kzb, toc, receiver, output):
    hmi, kzb, toc, output = prepare_paths(hmi, kzb, toc, output)
    if receiver.is_symlink() or any(p.is_symlink() for p in receiver.parents):
        raise ValueError("Symlink receiver refused")
    receiver = receiver.resolve()
    if not receiver.is_file() or not 1 <= receiver.stat().st_size <= 16 * 1024 * 1024:
        raise ValueError("Missing or unbounded receiver")
    return hmi, kzb, toc, receiver, output


def checked_file(path, output, limit=16 * 1024 * 1024):
    if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
        raise ValueError("Symlink input refused")
    path = Path(path).resolve()
    if not path.is_file() or not 1 <= path.stat().st_size <= limit:
        raise ValueError("Missing or unbounded input")
    if output == path or output in path.parents:
        raise ValueError("Output may not replace or contain an input")
    return path
