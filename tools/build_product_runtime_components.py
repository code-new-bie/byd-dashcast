"""WITHDRAWN 1.14.0: refuse generation after confirmed QNX startup incompatibility."""
import argparse
from pathlib import Path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('hmi', 'kzb', 'scene', 'toc', 'receiver', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.parse_args()
    raise SystemExit('WITHDRAWN 1.14.0: actual QNX interpreter cannot load the relocated PHDR. '
        'Do not generate or flash RWVF 1.14.0; restore the previously working instrument firmware.')

if __name__ == '__main__':
    main()
