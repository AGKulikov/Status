"""Source attribution/artwork and safe native-selector integration contracts."""
import hashlib
import json
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main"
JAVA = MAIN / "java/dezz/status/widget"


class DriveSelectorPortTests(unittest.TestCase):
    def test_original_icons_are_byte_identical(self):
        reference = json.loads((ROOT / "docs/drivemode/reference-v1.0.0.json").read_text())
        self.assertEqual(19, len(reference["icons"]))
        for icon in reference["icons"]:
            data = (MAIN / "res/drawable" / icon["name"]).read_bytes()
            self.assertEqual(icon["sha256"], hashlib.sha256(data).hexdigest(), icon["name"])
            ET.fromstring(data)

    def test_all_imported_xml_parses(self):
        paths = list((MAIN / "res").glob("*/drive_selector_*.xml"))
        self.assertGreater(len(paths), 10)
        for path in paths:
            ET.parse(path)

    def test_complete_catalog_without_second_vendor_owner(self):
        catalog = (JAVA / "drivemode/car/DriveModeCatalog.java").read_text()
        self.assertEqual(24, catalog.count("list.add(new DriveModeDescriptor("))
        self.assertNotIn("import com.ecarx", catalog)
        repository = (JAVA / "drivemode/car/DriveModeRepository.java").read_text()
        self.assertIn("CarIntegrations.get(context)", repository)
        self.assertIn("CarControlCommand.Operation.SET", repository)
        self.assertNotIn("setFunctionValue(", repository)

    def test_preview_does_not_write_or_invent_current(self):
        source = (JAVA / "drivemode/service/DriveModeOverlayService.java").read_text()
        preview = source.split("private void preview()", 1)[1].split("private void step(", 1)[0]
        self.assertNotIn("setMode", preview)
        self.assertNotIn("get(0)", preview)
        self.assertIn("overlay.show(enabled, actual", preview)
        self.assertIn("setModeConfirmed(target", source)
        self.assertLess(source.index("if (!ok)"), source.index("overlay.animateStepsTo"))

    def test_native_actions_never_use_external_broadcast(self):
        bridge = (JAVA / "media/DriveSelectorController.java").read_text()
        self.assertNotIn("sendBroadcast", bridge)
        self.assertIn("new Intent(app, DriveModeOverlayService.class)", bridge)
        manifest = ET.parse(MAIN / "AndroidManifest.xml").getroot()
        ns = "{http://schemas.android.com/apk/res/android}"
        service = next(e for e in manifest.find("application").findall("service")
                       if e.get(ns + "name") == ".drivemode.service.DriveModeOverlayService")
        self.assertEqual("false", service.get(ns + "exported"))

    def test_color_policy_and_consumers(self):
        icons = (JAVA / "drivemode/ui/DriveModeIcons.java").read_text()
        self.assertIn("if (!mode.iconIsColored)", icons)
        for file in ("VehicleControlActivity.java", "MediaButtonsSettingsActivity.java",
                     "LauncherActivity.java", "driver/DriverPanelOverlayController.java",
                     "launcher/LauncherIconResolver.java"):
            self.assertIn("DriveModeIcons", (JAVA / file).read_text(), file)


if __name__ == "__main__":
    unittest.main()
