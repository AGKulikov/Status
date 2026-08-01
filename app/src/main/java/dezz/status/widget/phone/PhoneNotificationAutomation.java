/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.Preferences;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.popup.PopupOverlayConfig;
import dezz.status.widget.popup.PopupOverlayConfigStore;
import dezz.status.widget.scenario.ScenarioPresets;

/**
 * Two independently editable phone-notification surfaces: first delivery without a cached app
 * icon and subsequent deliveries with it.
 */
public final class PhoneNotificationAutomation {
    public static final String OVERLAY_ID = "phone_notifications";
    public static final String OVERLAY_WITH_ICON_ID = "phone_notifications_icon";

    private static final String LEGACY_ITEM_ID = "phone_notification_latest";
    private static final String LEGACY_AUTOMATION_ID = "phone_notification_latest";

    public static final String APPLICATION_AUTOMATION_ID =
            "phone_notification_application";
    public static final String TOPIC_AUTOMATION_ID = "phone_notification_topic";
    public static final String TEXT_AUTOMATION_ID = "phone_notification_text";
    private static final String APPLICATION_ICON_ITEM_ID =
            "phone_notification_application_icon";
    private static final String TOPIC_ICON_ITEM_ID =
            "phone_notification_topic_icon";
    private static final String TEXT_ICON_ITEM_ID =
            "phone_notification_text_icon";

    /**
     * Both layouts intentionally share the same three live field states. Item IDs remain unique;
     * PopupItemConfigStore permits the same automation ID in different overlays.
     */
    private static final List<String> FIELD_AUTOMATION_IDS =
            Collections.unmodifiableList(Arrays.asList(
                    APPLICATION_AUTOMATION_ID, TOPIC_AUTOMATION_ID, TEXT_AUTOMATION_ID));

    private PhoneNotificationAutomation() {
    }

    @NonNull
    public static String automationIdForField(@NonNull String fieldId) {
        switch (fieldId) {
            case PhoneStatusBarPolicy.FIELD_APPLICATION:
                return APPLICATION_AUTOMATION_ID;
            case PhoneStatusBarPolicy.FIELD_TOPIC:
                return TOPIC_AUTOMATION_ID;
            case PhoneStatusBarPolicy.FIELD_TEXT:
                return TEXT_AUTOMATION_ID;
            default:
                throw new IllegalArgumentException(
                        "Unknown phone notification field: " + fieldId);
        }
    }

    @NonNull
    public static List<String> fieldAutomationIds() {
        return FIELD_AUTOMATION_IDS;
    }

    public static boolean isFieldAutomationId(@Nullable String automationId) {
        return automationId != null && FIELD_AUTOMATION_IDS.contains(automationId.trim());
    }

    public static void ensureConfigured(@NonNull Preferences prefs) throws JSONException {
        PopupOverlayConfigStore overlayStore = new PopupOverlayConfigStore(prefs);
        List<PopupOverlayConfig> overlays = new ArrayList<>(overlayStore.load());
        PopupOverlayConfig plain = ensureOverlay(
                overlays, OVERLAY_ID, "Уведомления телефона · без иконки");
        ensureOverlay(overlays, OVERLAY_WITH_ICON_ID,
                "Уведомления телефона · с иконкой");

        PopupItemConfigStore itemStore = new PopupItemConfigStore(prefs);
        List<PopupItemConfig> items = new ArrayList<>(itemStore.load());
        boolean hadLegacyCombinedTile = false;
        for (int index = items.size() - 1; index >= 0; index--) {
            PopupItemConfig candidate = items.get(index);
            if (LEGACY_ITEM_ID.equals(candidate.id)
                    || LEGACY_AUTOMATION_ID.equals(candidate.automationId)) {
                items.remove(index);
                hadLegacyCombinedTile = true;
            }
        }
        if (hadLegacyCombinedTile && plain.rows == 1) {
            plain.rows = 3;
            if (plain.height == 190) plain.height = 300;
        }

        ensureField(items, APPLICATION_AUTOMATION_ID, APPLICATION_AUTOMATION_ID,
                OVERLAY_ID,
                PhoneStatusBarPolicy.FIELD_APPLICATION,
                "Уведомление · Приложение", "Приложение", 0,
                false, false);
        ensureField(items, TOPIC_AUTOMATION_ID, TOPIC_AUTOMATION_ID,
                OVERLAY_ID,
                PhoneStatusBarPolicy.FIELD_TOPIC,
                "Уведомление · Тема", "Тема", 1,
                false, false);
        ensureField(items, TEXT_AUTOMATION_ID, TEXT_AUTOMATION_ID,
                OVERLAY_ID,
                PhoneStatusBarPolicy.FIELD_TEXT,
                "Уведомление · Текст", "Текст", 2,
                false, false);

        ensureField(items, APPLICATION_ICON_ITEM_ID, APPLICATION_AUTOMATION_ID,
                OVERLAY_WITH_ICON_ID,
                PhoneStatusBarPolicy.FIELD_APPLICATION,
                "Уведомление · Иконка приложения", "Приложение", 0,
                true, true);
        ensureField(items, TOPIC_ICON_ITEM_ID, TOPIC_AUTOMATION_ID,
                OVERLAY_WITH_ICON_ID,
                PhoneStatusBarPolicy.FIELD_TOPIC,
                "Уведомление · Тема (с иконкой)", "Тема", 1,
                false, false);
        ensureField(items, TEXT_ICON_ITEM_ID, TEXT_AUTOMATION_ID,
                OVERLAY_WITH_ICON_ID,
                PhoneStatusBarPolicy.FIELD_TEXT,
                "Уведомление · Текст (с иконкой)", "Текст", 2,
                false, false);

        // Save items first. Item IDs are unique; live field IDs are shared across the two windows.
        itemStore.save(items);
        overlayStore.save(overlays);
    }

    @NonNull
    private static PopupOverlayConfig ensureOverlay(
            @NonNull List<PopupOverlayConfig> overlays,
            @NonNull String id,
            @NonNull String name) {
        PopupOverlayConfig overlay = null;
        for (PopupOverlayConfig candidate : overlays) {
            if (id.equals(candidate.id)) {
                overlay = candidate;
                break;
            }
        }
        if (overlay == null) {
            overlay = PopupOverlayConfig.create(id, name, overlays.size());
            overlay.width = 900;
            overlay.height = 300;
            overlay.rows = 3;
            overlay.columns = 1;
            overlay.x = 420;
            overlay.y = 180;
            overlay.paddingLeft = 0;
            overlay.paddingTop = 0;
            overlay.paddingRight = 0;
            overlay.paddingBottom = 0;
            overlay.cellGap = 0;
            overlay.backgroundColor = "#00000000";
            overlay.backgroundAlpha = 0;
            overlay.cornerRadius = 0;
            overlays.add(overlay);
        }
        overlay.enabled = true;
        overlay.defaultVisible = false;
        return overlay;
    }

    private static void ensureField(
            @NonNull List<PopupItemConfig> items,
            @NonNull String itemId,
            @NonNull String automationId,
            @NonNull String overlayId,
            @NonNull String fieldId,
            @NonNull String name,
            @NonNull String title,
            int row,
            boolean dynamicAppIcon,
            boolean iconOnly) {
        PopupItemConfig item = null;
        for (PopupItemConfig candidate : items) {
            if (itemId.equals(candidate.id)) {
                item = candidate;
                break;
            }
        }
        if (item == null) {
            item = PopupItemConfig.create(itemId, items.size());
            item.name = name;
            item.title = title;
            item.row = row;
            item.column = 0;
            item.icon = dynamicAppIcon ? "phone" : "notification";
            item.iconSize = dynamicAppIcon ? 72 : 0;
            item.orientation = 1;
            item.showTitle = !iconOnly;
            item.showStatus = !iconOnly;
            item.titleSize = 18;
            item.textSize = 22;
            item.backgroundColor = "#00000000";
            item.backgroundAlpha = 0;
            item.borderAlpha = 0;
            item.borderWidth = 0;
            item.cornerRadius = 0;
            item.padding = 0;
            items.add(item);
        } else if (!dynamicAppIcon
                && APPLICATION_AUTOMATION_ID.equals(automationId)
                && OVERLAY_ID.equals(overlayId)
                && "phone".equals(item.icon)
                && item.iconSize == 42) {
            // Migrate the untouched HA1134 phone placeholder out of the text-only layout.
            item.iconSize = 0;
        }

        item.overlayId = overlayId;
        item.automationId = automationId;
        item.type = PopupItemConfig.TYPE_HA_TEXT;
        item.enabled = true;
        item.sourceBinding = new SourceBinding(
                ConnectorType.PHONE,
                SourceBinding.DEFAULT_CONNECTOR_ID,
                "notifications.latest",
                "@value." + fieldId,
                SourceBinding.PRESENTATION_RAW,
                "");
        item.actionBinding = ActionBinding.unbound();
        if (item.displayRules == null) item.displayRules = ScenarioPresets.raw();
    }
}
