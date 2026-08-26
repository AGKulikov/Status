#!/usr/bin/env python3
"""Recreate an APK ZIP while replacing only explicitly named entries.

Recreating the ZIP intentionally drops the obsolete APK Signing Block. zipalign and apksigner must
run afterwards. Uncompressed file bytes and entry order are preserved for every untouched entry.
"""

from __future__ import annotations

import argparse
import copy
from pathlib import Path
import shutil
import sys
import zipfile


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--replace", action="append", default=[], metavar="ENTRY=FILE",
        help="replace or append one exact APK entry",
    )
    args = parser.parse_args(argv)

    replacements = {}
    try:
        for raw in args.replace:
            name, separator, file_name = raw.partition("=")
            if not separator or not name or name in replacements:
                raise ValueError(f"invalid or duplicate --replace value {raw!r}")
            path = Path(file_name)
            if not path.is_file():
                raise ValueError(f"replacement is not a file: {path}")
            replacements[name] = path
        if args.output.resolve() == args.baseline.resolve():
            raise ValueError("output must not overwrite the baseline")
        args.output.parent.mkdir(parents=True, exist_ok=True)

        seen = set()
        replaced = set()
        with zipfile.ZipFile(args.baseline, "r") as source, zipfile.ZipFile(
            args.output, "w", allowZip64=True
        ) as target:
            for original in source.infolist():
                if original.filename in seen:
                    raise ValueError(f"duplicate baseline entry {original.filename!r}")
                seen.add(original.filename)
                info = copy.copy(original)
                # Alignment padding stored in APK ZIP extra fields is offset-specific. Reusing it
                # after rewriting the archive makes zipalign report one header mismatch per entry.
                # Drop only ZIP metadata; zipalign recreates valid padding after this step.
                info.extra = b""
                info.comment = b""
                if original.filename in replacements:
                    with replacements[original.filename].open("rb") as stream:
                        with target.open(info, "w") as output:
                            shutil.copyfileobj(stream, output, 1024 * 1024)
                    replaced.add(original.filename)
                else:
                    with source.open(original, "r") as stream:
                        with target.open(info, "w") as output:
                            shutil.copyfileobj(stream, output, 1024 * 1024)

            for name in replacements.keys() - replaced:
                info = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 1, 1, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with replacements[name].open("rb") as stream:
                    with target.open(info, "w") as output:
                        shutil.copyfileobj(stream, output, 1024 * 1024)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"Repacked {args.output} with entries: {', '.join(sorted(replacements))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
