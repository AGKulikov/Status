#!/usr/bin/env python3
"""Project REL-004: release patch component is exactly one digit (0..9)."""
import re
import sys


def validate_release_version(version):
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.[0-9]", version):
        raise ValueError("REL-004: use X.Y.0..9; after 3.0.9 use 3.1.0, never 3.0.10")
    return tuple(int(part) for part in version.split("."))


if __name__ == "__main__":
    validate_release_version(sys.argv[1])
