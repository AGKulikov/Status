/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

/** Release barriers for the shared HA1203 visual vector-icon catalog. */
public final class Ha1203VectorIconPickerContractTest {
    private static final String[] SELECTABLE_AUTOMOTIVE_KEYS = {
            "front_car", "car_side", "car_rear", "car_lock", "car_unlock", "car_doors",
            "headlights", "high_beam", "fog_lights", "hazard", "horn",
            "air_recirculation", "steering", "charging", "ev_battery", "fuel",
            "tire_pressure", "parking", "car_camera", "car_key", "wiper_wash"
    };

    /** Existing saved values still resolve, but misleading drawings are not offered again. */
    private static final String[] PERSISTED_FALLBACK_KEYS = {
            "hood_open", "car_window", "sunroof", "mirror_fold", "parking_sensor", "child_lock"
    };

    private static final String[] AUTOMOTIVE_VECTORS = {
            "ic_car_front.xml", "ic_car_side.xml", "ic_car_rear.xml", "ic_car_lock.xml",
            "ic_car_unlock.xml", "ic_car_hood_open.xml", "ic_car_doors.xml",
            "ic_car_window.xml", "ic_car_sunroof.xml", "ic_car_mirror_fold.xml",
            "ic_car_headlight.xml", "ic_car_high_beam.xml", "ic_car_fog_light.xml",
            "ic_car_hazard.xml", "ic_car_horn.xml", "ic_car_air_recirculation.xml",
            "ic_car_steering.xml", "ic_car_charging.xml", "ic_car_ev_battery.xml",
            "ic_car_fuel.xml", "ic_car_tire_pressure.xml", "ic_car_parking.xml",
            "ic_car_parking_sensor.xml", "ic_car_camera.xml", "ic_car_key.xml",
            "ic_car_child_lock.xml", "ic_car_wiper_wash.xml", "ic_car_ac.xml",
            "ic_car_climate_auto.xml", "ic_car_fan.xml"
    };

    private static final String[] MODERN_PACK_VECTORS = {
            "ic_car_front.xml", "ic_car_side.xml", "ic_car_rear.xml", "ic_car_lock.xml",
            "ic_car_unlock.xml", "ic_car_doors.xml", "ic_car_headlight.xml",
            "ic_car_high_beam.xml", "ic_car_fog_light.xml", "ic_car_hazard.xml",
            "ic_car_horn.xml", "ic_car_air_recirculation.xml", "ic_car_steering.xml",
            "ic_car_charging.xml", "ic_car_ev_battery.xml", "ic_car_fuel.xml",
            "ic_car_tire_pressure.xml", "ic_car_parking.xml", "ic_car_camera.xml",
            "ic_car_key.xml", "ic_car_wiper_wash.xml", "ic_car_ac.xml",
            "ic_car_climate_auto.xml", "ic_car_fan.xml", "ic_car_climate.xml",
            "ic_car_seat_heat.xml", "ic_car_seat_heat_passenger.xml",
            "ic_car_seat_vent.xml", "ic_car_seat_vent_passenger.xml",
            "ic_car_wheel_heat.xml", "ic_car_defrost_front.xml",
            "ic_car_defrost_rear.xml", "ic_car_wiper.xml", "ic_car_drive_mode.xml",
            "ic_car_fuel_save.xml", "ic_car_trunk_closed.xml", "ic_car_trunk_open.xml"
    };

    @Test public void expandedCatalogKeepsOldKeysAndHidesMisleadingNewChoices()
            throws Exception {
        String resolver = source("launcher/LauncherIconResolver.java");
        String[] historical = {
                "app", "apps", "navigation", "home", "media", "garage", "gate", "door",
                "lock", "light", "power", "temperature", "climate", "seat_heat",
                "seat_vent", "wheel_heat", "defrost_front", "defrost_rear", "wiper",
                "drive_mode", "fuel_save", "trunk_closed", "trunk_open", "car"
        };
        for (String key : historical) {
            assertTrue("Missing historical icon id: " + key,
                    resolver.contains("new Preset(\"" + key + "\""));
        }
        for (String key : SELECTABLE_AUTOMOTIVE_KEYS) {
            assertTrue("Missing automotive icon id: " + key,
                    resolver.contains("new Preset(\"" + key + "\""));
        }
        for (String key : PERSISTED_FALLBACK_KEYS) {
            assertFalse("Misleading automotive icon must stay out of new choices: " + key,
                    resolver.contains("new Preset(\"" + key + "\""));
            assertTrue("Persisted automotive id must keep resolving: " + key,
                    resolver.contains("case \"" + key + "\": return R.drawable."));
        }
        assertEquals("The safe catalog remains 24 choices larger than HA1202",
                100, occurrences(resolver, "new Preset(\""));
        assertTrue(resolver.contains("case \"front_car\": return R.drawable.ic_car_front"));
        assertTrue("front_car must remain distinct from the historical car id",
                resolver.contains("case \"car\": return R.drawable.ic_smart_car"));
    }

    @Test public void everyNewAutomotiveAssetIsAStandaloneVectorDrawable() throws Exception {
        for (String name : AUTOMOTIVE_VECTORS) {
            String xml = resource("drawable/" + name);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            assertEquals(name, "vector", document.getDocumentElement().getTagName());
            assertFalse(name + " must not embed raster content", xml.contains("<bitmap"));
        }
    }

    @Test public void modernAutomotiveAssetsUseOnlyPinnedLicensedSources() throws Exception {
        int google = 0;
        int mdi = 0;
        for (String name : MODERN_PACK_VECTORS) {
            String xml = resource("drawable/" + name);
            if (xml.contains("Google Material Symbols Rounded @ 50f0603")) google++;
            else if (xml.contains("Pictogrammers MDI 7.4.47 @ 9e0420a")) mdi++;
            else assertTrue(name + " must identify a pinned licensed icon source", false);
        }
        assertEquals(22, google);
        assertEquals(15, mdi);

        String trunkOpen = resource("drawable/ic_car_trunk_open.xml");
        assertTrue(trunkOpen.contains("(car-back + arrow-up-bold; adapted composition)"));
        assertEquals("Open trunk must compose two intact upstream glyphs in groups",
                2, occurrences(trunkOpen, "<group"));

        String mdiNotice = project(
                "third_party/pictogrammers-material-design-icons/NOTICE.md");
        String mdiLicense = project(
                "third_party/pictogrammers-material-design-icons/LICENSE-APACHE-2.0.txt");
        String googleNotice = project(
                "third_party/google-material-symbols/NOTICE.md");
        String googleLicense = project(
                "third_party/google-material-symbols/LICENSE-APACHE-2.0.txt");
        assertTrue(mdiNotice.contains("9e04201d4557e729822fb57f62a316c3dea1d4a8"));
        assertTrue(googleNotice.contains("50f0603134ce7b70b2d71b686cc13e8b57ccb74c"));
        assertTrue(mdiNotice.contains("stable icon resource names and persisted catalog keys"));
        assertTrue(googleNotice.contains("stable icon resource names"));
        assertTrue(googleNotice.contains("persisted catalog keys were retained"));
        assertTrue(mdiLicense.contains("Version 2.0, January 2004"));
        assertTrue(googleLicense.contains("Version 2.0, January 2004"));
    }

    @Test public void pickerIsAnIconPreviewGridRatherThanANameList() throws Exception {
        String picker = source("settings/VectorIconPickerDialog.java");
        assertTrue(picker.contains("new GridLayoutManager(context, columns)"));
        assertTrue(picker.contains("holder.icon.setImageDrawable(drawable)"));
        assertTrue(picker.contains("ImageView.ScaleType.CENTER_INSIDE"));
        assertTrue(picker.contains("setContentDescription"));
        assertFalse("The shared icon picker must never regress to a text-only list",
                picker.contains(".setItems("));
    }

    @Test public void everyCustomIconSurfaceUsesTheSharedPreviewGrid() throws Exception {
        String[] surfaces = {
                "VisualBrickEditorActivity.java", "ScenarioSettingsActivity.java",
                "DriverFavoritesSettingsActivity.java", "FavoriteRoutesSettingsActivity.java",
                "InformationPanelSettingsActivity.java", "DriverPanelSettingsActivity.java",
                "LauncherShortcutSettingsActivity.java"
        };
        for (String surface : surfaces) {
            assertTrue(surface + " must open the shared icon preview grid",
                    source(surface).contains("VectorIconPickerDialog.show"));
        }
        assertTrue(source("InformationPanelSettingsActivity.java")
                .contains("VectorIconPickerDialog.option(\"auto\""));
        assertTrue(source("DriverPanelSettingsActivity.java")
                .contains("VectorIconPickerDialog.option(\"live_climate\""));
        assertFalse(source("LauncherShortcutSettingsActivity.java")
                .contains("VectorIconPickerDialog.option(\"auto\""));
        assertFalse(source("DriverFavoritesSettingsActivity.java")
                .contains("VectorIconPickerDialog.option(\"auto\""));
        assertTrue(source("launcher/LauncherIconResolver.java")
                .contains("new Preset(\"app\""));
        assertTrue(source("launcher/LauncherIconResolver.java")
                .contains("if (\"none\".equalsIgnoreCase(shortcut.icon)) return null"));
    }

    @Test public void popupAllowListRemainsOfflineAndUsesTheSameStableCatalog() throws Exception {
        String popup = source("popup/PopupIconCatalog.java");
        assertTrue(popup.contains("isAllowedId(id)"));
        assertTrue(popup.contains("LauncherIconResolver.isKnownKey(id)"));
        assertFalse(popup.contains("LauncherIconResolver.presets()"));
        assertFalse(popup.contains("LABELS"));
        assertTrue(popup.contains("LauncherIconResolver.resource(id)"));
        assertFalse(popup.contains("java.net"));
        assertFalse(popup.contains("android.net.Uri"));
        assertFalse(popup.contains("java.io.File"));
        assertFalse(popup.contains("http://"));
        assertFalse(popup.contains("https://"));
    }

    @Test public void liveClimatePresentationRemainsIndependentFromButtonAction()
            throws Exception {
        String settings = source("DriverPanelSettingsActivity.java");
        String runtime = source("driver/DriverPanelOverlayController.java");
        String actionPicker = source("launcher/ShortcutActionPicker.java");
        assertTrue(settings.contains(
                "boolean interactive = LauncherShortcutStore.isInteractive(shortcut)"));
        assertTrue(settings.contains("VectorIconPickerDialog.withFirst(live"));
        assertTrue(settings.contains(
                "interactive && shortcut.liveClimateIcon ? live.key : shortcut.icon"));
        assertFalse(settings.contains("isStockClimate(shortcut)"));
        assertFalse(settings.contains("shortcut.liveClimateIcon =\n"
                + "                            action == "
                + "LauncherShortcutStore.Builtin.STOCK_CLIMATE"));
        assertFalse(settings.contains("shortcut.iconCustomized = false;\n"
                + "                    shortcut.liveClimateIcon = false;\n"
                + "                    shortcut.showTitle = false;"));
        assertTrue(settings.contains("return shortcut.liveClimateIcon\n"
                + "                && LauncherShortcutStore.isInteractive(shortcut);"));
        assertTrue(runtime.contains("return shortcut.liveClimateIcon\n"
                + "                && LauncherShortcutStore.isInteractive(shortcut);"));
        assertTrue(runtime.contains("|| shortcut.kind == LauncherShortcutStore.Kind.CAR)\n"
                + "                && !liveClimate\n"
                + "                && shortcut.showState"));
        assertFalse(actionPicker.contains(
                "if (value.kind != LauncherShortcutStore.Kind.BUILTIN\n"
                        + "                || !LauncherShortcutStore.Builtin.STOCK_CLIMATE.key"
                        + ".equals(value.target))"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String resource(String relative) throws Exception {
        return project("app/src/main/res/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(direct) ? direct : parent;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        for (int at = 0; (at = source.indexOf(token, at)) >= 0; at += token.length()) count++;
        return count;
    }
}
