#!/usr/bin/env python3
"""Merge Status components into the decoded Navigator manifest.

The Navigator resource table remains the root 0x7f package. Status is built separately with
package id 0x80, so resource references on imported components are rewritten to their numeric
0x80 ids before apktool compiles the merged manifest.
"""

from __future__ import annotations

import argparse
import copy
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
A = f"{{{ANDROID}}}"
HOST_PACKAGE = "ru.natro.statuswidget"
STATUS_PREFIX = "dezz.status.widget."
RESOURCE_REFERENCE = re.compile(r"^@([a-zA-Z0-9_]+)/(.+)$")

ET.register_namespace("android", ANDROID)


def resource_ids(public_xml: Path) -> dict[tuple[str, str], str]:
    root = ET.parse(public_xml).getroot()
    result: dict[tuple[str, str], str] = {}
    for item in root.findall("public"):
        resource_type = item.get("type")
        name = item.get("name")
        value = item.get("id")
        if not resource_type or not name or not value:
            continue
        if not value.lower().startswith("0x80"):
            raise SystemExit(
                f"Status resource {resource_type}/{name} has {value}; "
                "build it with -PembeddedNavigatorResources"
            )
        result[(resource_type, name)] = value
    return result


def rewrite_resource_references(element: ET.Element,
                                ids: dict[tuple[str, str], str]) -> None:
    root_resources = {
        ("style", "Theme.StatusWidget.Launcher"): "@style/AppTheme",
        ("xml", "widget_accessibility_service"):
            "@xml/status_widget_accessibility_service",
        ("xml", "file_paths"): "@xml/status_widget_file_paths",
    }
    for node in element.iter():
        for key, value in list(node.attrib.items()):
            match = RESOURCE_REFERENCE.match(value)
            if not match or value.startswith("@android:"):
                continue
            identity = (match.group(1), match.group(2))
            if identity == ("string", "app_name"):
                # A literal is sufficient for service labels and avoids an external resource
                # reference in the root binary manifest.
                node.set(key, "Status Widget")
                continue
            root_reference = root_resources.get(identity)
            if root_reference is not None:
                node.set(key, root_reference)
                continue
            resource_id = ids.get(identity)
            if resource_id is None:
                raise SystemExit(
                    f"Missing 0x80 id for imported manifest reference {value}"
                )
            raise SystemExit(
                f"Imported manifest reference {value} ({resource_id}) has no root-resource "
                "mapping. Android's root manifest cannot be linked against a nested 0x80 APK."
            )


def signature(element: ET.Element) -> tuple[str, str, str]:
    return (
        element.tag,
        element.get(A + "name", ""),
        element.get(A + "authorities", ""),
    )


def merge_queries(navigator: ET.Element, status: ET.Element) -> None:
    source = status.find("queries")
    if source is None:
        return
    target = navigator.find("queries")
    if target is None:
        target = ET.Element("queries")
        application_index = list(navigator).index(navigator.find("application"))
        navigator.insert(application_index, target)
    existing = {ET.tostring(child, encoding="unicode") for child in target}
    for child in source:
        serialized = ET.tostring(child, encoding="unicode")
        if serialized not in existing:
            target.append(copy.deepcopy(child))
            existing.add(serialized)


def should_import_component(element: ET.Element) -> bool:
    name = element.get(A + "name", "")
    authorities = element.get(A + "authorities", "")
    return name.startswith(STATUS_PREFIX) or (
        name == "androidx.core.content.FileProvider"
        and authorities.startswith(HOST_PACKAGE)
    )


def rewrite_navigator_identity_scoped_values(root: ET.Element) -> None:
    """Avoid provider/permission ownership conflicts with a separately installed source mod."""
    scoped_attributes = {
        A + "authorities",
        A + "permission",
        A + "readPermission",
        A + "writePermission",
    }
    for element in root.iter():
        for key, value in list(element.attrib.items()):
            package_scoped_name = (
                key == A + "name"
                and element.tag in {
                    "permission",
                    "uses-permission",
                    "uses-permission-sdk-23",
                }
            )
            if (key in scoped_attributes or package_scoped_name) and (
                "ru.yandex.yandexnavi" in value
            ):
                element.set(
                    key, value.replace("ru.yandex.yandexnavi", HOST_PACKAGE)
                )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--navigator", type=Path, required=True)
    parser.add_argument("--status", type=Path, required=True)
    parser.add_argument("--status-public", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    navigator_tree = ET.parse(args.navigator)
    status_tree = ET.parse(args.status)
    navigator = navigator_tree.getroot()
    status = status_tree.getroot()
    ids = resource_ids(args.status_public)

    navigator.set("package", HOST_PACKAGE)
    rewrite_navigator_identity_scoped_values(navigator)
    for attribute in (A + "versionCode", A + "versionName"):
        value = status.get(attribute)
        if value:
            navigator.set(attribute, value)

    status_sdk = status.find("uses-sdk")
    navigator_sdk = navigator.find("uses-sdk")
    if status_sdk is not None:
        replacement = copy.deepcopy(status_sdk)
        if navigator_sdk is None:
            navigator.insert(0, replacement)
        else:
            navigator.insert(list(navigator).index(navigator_sdk), replacement)
            navigator.remove(navigator_sdk)

    root_tags = {
        "uses-permission",
        "uses-permission-sdk-23",
        "uses-feature",
        "permission",
    }
    existing_root = {signature(child) for child in navigator if child.tag in root_tags}
    application = navigator.find("application")
    if application is None:
        raise SystemExit("Navigator manifest has no application")
    application_index = list(navigator).index(application)
    for child in status:
        if child.tag not in root_tags:
            continue
        child_signature = signature(child)
        if child_signature in existing_root:
            continue
        navigator.insert(application_index, copy.deepcopy(child))
        application_index += 1
        existing_root.add(child_signature)

    merge_queries(navigator, status)

    status_application = status.find("application")
    if status_application is None:
        raise SystemExit("Status manifest has no application")
    application.set(A + "label", "Status Widget + Навигатор")
    application.set(A + "usesCleartextTraffic", "true")

    existing_components = {signature(child) for child in application}
    for child in status_application:
        if not should_import_component(child):
            continue
        imported = copy.deepcopy(child)
        rewrite_resource_references(imported, ids)
        component_signature = signature(imported)
        if component_signature in existing_components:
            continue
        application.append(imported)
        existing_components.add(component_signature)

    ET.indent(navigator_tree, space="    ")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    navigator_tree.write(args.output, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
