/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;

public final class PhoneInformationSourcePolicyTest {
    @Test public void objectResourcesUseReadableScalarFields() {
        assertEquals("@value.app_name",
                PhoneInformationSourcePolicy.valuePath("notifications.latest"));
        assertEquals("@value.display",
                PhoneInformationSourcePolicy.valuePath("messages.latest"));
        assertEquals("@value.name",
                PhoneInformationSourcePolicy.valuePath("diagnostics.last_app"));
        assertEquals("", PhoneInformationSourcePolicy.valuePath("battery.level"));
    }

    @Test public void transportObjectsAndCollectionsAreNotOneLineTiles() {
        assertFalse(PhoneInformationSourcePolicy.selectable("diagnostics.device"));
        assertFalse(PhoneInformationSourcePolicy.selectable("notifications.items"));
        assertTrue(PhoneInformationSourcePolicy.selectable("diagnostics.last_app"));
    }

    @Test public void oldEmptyPhoneBindingMigratesWithoutTouchingExplicitPaths() {
        SourceBinding old = phone("notifications.latest", "");
        SourceBinding migrated = PhoneInformationSourcePolicy.migrate(old);
        assertEquals("@value.app_name", migrated.valuePath);

        SourceBinding explicit = phone("notifications.latest", "attributes.custom");
        assertSame(explicit, PhoneInformationSourcePolicy.migrate(explicit));
        SourceBinding scalar = phone("battery.level", "");
        assertSame(scalar, PhoneInformationSourcePolicy.migrate(scalar));
    }

    @Test public void pickerPreviewResolvesTheSameScalarAsSavedBinding() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("app_name", "Telegram");
        raw.put("icon", "chat");
        ConnectorValue value = ConnectorValue.current(ConnectorType.PHONE, "default",
                "notifications.latest", raw, true, true, false,
                "object", "", Collections.emptyMap());

        assertEquals("Telegram", PhoneInformationSourcePolicy.displayValue(value));
        assertEquals("Telegram",
                value.resolveValue(PhoneInformationSourcePolicy.valuePath(value.resourceId)));
    }

    @Test public void privacySafeMessageDisplayDoesNotRequireSenderOrBody() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("display", "Сообщения");
        raw.put("icon", "messages");
        ConnectorValue value = ConnectorValue.current(ConnectorType.PHONE, "default",
                "messages.latest", raw, true, true, false,
                "object", "", Collections.emptyMap());

        assertEquals("Сообщения", PhoneInformationSourcePolicy.displayValue(value));
    }

    private static SourceBinding phone(String resourceId, String valuePath) {
        return new SourceBinding(ConnectorType.PHONE, "default", resourceId, valuePath,
                SourceBinding.PRESENTATION_AUTO, "");
    }
}
