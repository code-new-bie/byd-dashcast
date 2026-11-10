"""Input and output checks shared by the instrument component tools. No firmware is read here."""
from pathlib import Path

from base_firmware import INPUTS

# bic_byd_stha.kzb.txt shipped next to the original KZB in the base app.sv.
TOC_SHA = INPUTS["toc"][2]


def new_output(output):
    """A NEW output directory whose parent exists; refuses symlinks."""
    output = Path(output)
    if output.is_symlink() or any(parent.is_symlink() for parent in output.parents):
        raise ValueError("Symlink output refused")
    output = output.resolve()
    if output.exists() or not output.parent.is_dir():
        raise ValueError("Existing parent and NEW output directory required")
    return output


def checked_file(path, output, limit=16 * 1024 * 1024):
    if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
        raise ValueError("Symlink input refused")
    path = Path(path).resolve()
    if not path.is_file() or not 1 <= path.stat().st_size <= limit:
        raise ValueError("Missing or unbounded input")
    if output == path or output in path.parents:
        raise ValueError("Output may not replace or contain an input")
    return path
