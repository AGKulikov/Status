#!/usr/bin/env python3
"""Observe the reviewed 30.3.0 ManeuverView setters after their original bodies return."""
import argparse
import hashlib
from pathlib import Path
import re

EXPECTED_SMALI_SHA256 = "ca9a044457b58f9890bf795642c44862d0c551ed9fc5524c0ab5e57ce992289c"
OWNER = "Lru/yandex/yandexnavi/ui/guidance/maneuver/ContextManeuverView;"
HOOK = "Lru/natro/navigation/StockManeuverCommands;"
SPEC = {
    "setManeuver": (4, "onManeuver", "Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;"),
    "setLaneItems": (1, "onLanes", "Ljava/lang/Object;"),
    "setDirectionSignItems": (1, "onSigns", "Ljava/lang/Object;"),
    "setNextUpcomingDirectionSignItems": (1, "onFollowingSigns", "Ljava/lang/Object;"),
    **{name: (0, "onChanged", "") for name in (
        "setContentVisible", "setMode", "setScale", "setScreenSaverModeEnabled",
        "setCanBeVisible", "setStyle", "setMaxLines", "setNextStreetCanBeLarge",
        "setDirectionSignRedisigned", "setPresenter")},
}


def patch(source):
    if hashlib.sha256(source.encode()).hexdigest() != EXPECTED_SMALI_SHA256:
        raise ValueError("ContextManeuverView is not the exact reviewed baseline")
    result = source
    wrappers = []
    for name, (hook_count, hook_name, hook_args) in SPEC.items():
        matches = list(re.finditer(r"^\.method (public(?: final)?) " + name
                                  + r"(\([^\n]*\)V)\n", source, re.M))
        if len(matches) != 1:
            raise ValueError("Expected exactly one setter: " + name)
        match = matches[0]
        signature = match.group(2)
        params = re.findall(r"L[^;]+;|[ZIF]", signature[1:signature.index(")")])
        if (name == "setManeuver" and len(params) != 4) or not params:
            raise ValueError("Unexpected parameter layout: " + name)
        # Keep the entire original body, registers, annotations and exception semantics.
        # The wrapper's parameters are untouched even when the original reuses p registers.
        result = result.replace(match.group(0), ".method private natro$original$" + name
                                + signature + "\n", 1)
        wrappers.append(
            match.group(0) + "    .locals 1\n\n"
            + f"    invoke-direct/range {{p0 .. p{len(params)}}}, {OWNER}->natro$original${name}{signature}\n\n"
            + "    :natro_observe_start\n"
            + f"    invoke-static/range {{p0 .. p{hook_count}}}, {HOOK}->{hook_name}(Ljava/lang/Object;{hook_args})V\n"
            + "    :natro_observe_end\n"
            + "    .catch Ljava/lang/Throwable; {:natro_observe_start .. :natro_observe_end} :natro_observe_failed\n"
            + "    return-void\n\n    :natro_observe_failed\n    move-exception v0\n    return-void\n.end method\n")
    return result + "\n# Natro observation wrappers; original presenter remains the sole owner.\n" + "\n".join(wrappers)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("smali", type=Path)
    args = parser.parse_args()
    args.smali.write_text(patch(args.smali.read_text(encoding="utf-8")), encoding="utf-8")
