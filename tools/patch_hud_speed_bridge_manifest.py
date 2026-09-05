#!/usr/bin/env python3
"""Add exactly one explicit, authenticated bridge service to decoded HUD Speed XML."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


EXPECTED_PACKAGE = 'package="air.StrelkaHUDFREE"'
SERVICE_NAME = "air.StrelkaSD.bridge.HudSpeedCameraBridgeService"
SERVICE_XML = f'''        <service
            android:name="{SERVICE_NAME}"
            android:directBootAware="false"
            android:enabled="true"
            android:exported="true"
            android:stopWithTask="false">
            <intent-filter>
                <action android:name="ru.natro.hudspeed.camera.BIND_V1" />
            </intent-filter>
        </service>
'''


def patch(value: str) -> str:
    if EXPECTED_PACKAGE not in value:
        raise ValueError("manifest is not the expected HUD Speed package")
    if SERVICE_NAME in value:
        raise ValueError("HUD Speed bridge service is already present")
    marker = "    </application>"
    if value.count(marker) != 1:
        raise ValueError("manifest application boundary is ambiguous")
    return value.replace(marker, SERVICE_XML + marker)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args(argv)
    try:
        original = args.manifest.read_text(encoding="utf-8")
        args.manifest.write_text(patch(original), encoding="utf-8")
    except (OSError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"Added authenticated HUD Speed bridge service: {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
