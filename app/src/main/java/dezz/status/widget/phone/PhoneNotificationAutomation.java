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
 * Reserved, user-customizable popup surface used by the latest-phone-notification automation.
 *
 * <p>Only routing invariants are repaired on every enable. Geometry and appearance are left
 * untouched so the normal popup and visual editors remain the single source of truth.</p>
 */
public final class PhoneNotificationAutomation {
    public static final String OVERLAY_ID = "phone_notifications";
    /** HA1129 development builds used one combined tile. It is migrated, never rendered twice. */
    private static final String LEGACY_ITEM_ID = "phone_notification_latest";
    private static final String LEGACY_AUTOMATION_ID = "phone_notification_latest";
    public static final String APPLICATION_AUTOMATION_ID =
            "phone_notification_application";
    public static final String TOPIC_AUTOMATION_ID = "phone_notification_topic";
    public static final String TEXT_AUTOMATION_ID = "phone_notification_text";
    private static final List<String> FIELD_AUTOMATION_IDS = Collections.unmodifiableList(
            Arrays.asList(APPLICATION_AUTOMATION_ID, TOPIC_AUTOMATION_ID,
                    TEXT_AUTOMATION_ID));

    private PhoneNotificationAutomation() {
    }

    /** Stable popup/state id for one canonical notification field. */
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
                throw new IllegalArgumentException("Unknown phone notification field: "
                        + fieldId);
        }
    }

    /** Canonical order used by state updates, expiry and the visual editor. */
    @NonNull
    public static List<String> fieldAutomationIds() {
        return FIELD_AUTOMATION_IDS;
    }

    /** Lets the scenario editor permit child visibility only for these reserved live fields. */
    public static boolean isFieldAutomationId(@Nullable String automationId) {
        return automationId != null && FIELD_AUTOMATION_IDS.contains(automationId.trim());
    }

    /** Creates the reserved overlay/fields and repairs routing after a partial settings edit. */
    public static void ensureConfigured(@NonNull Preferences prefs) throws JSONException {
        PopupOverlayConfigStore overlayStore = new PopupOverlayConfigStore(prefs);
        List<PopupOverlayConfig> overlays = new ArrayList<>(overlayStore.load());
        PopupOverlayConfig overlay = null;
        for (PopupOverlayConfig candidate : overlays) {
            if (OVERLAY_ID.equals(candidate.id)) {
                overlay = candidate;
                break;
            }
        }
        if (overlay == null) {
            overlay = PopupOverlayConfig.create(OVERLAY_ID,
                    "Уведомления телефона", overlays.size());
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
        // Runtime visibility is event/condition driven. A retained default would resurrect the
        // last notification after process restart.
        overlay.enabled = true;
        overlay.defaultVisible = false;
        overlayStore.save(overlays);

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
        if (hadLegacyCombinedTile && overlay.rows == 1) {
            // The old single-cell geometry cannot display three independently conditioned
            // fields. Preserve position, width and every visual setting while expanding only
            // the minimum grid geometry required by the migration.
            overlay.rows = 3;
            if (overlay.height == 190) overlay.height = 300;
            overlayStore.save(overlays);
        }

        ensureField(items, PhoneStatusBarPolicy.FIELD_APPLICATION,
                APPLICATION_AUTOMATION_ID, "Уведомление · Приложение",
                "Приложение", 0, true);
        ensureField(items, PhoneStatusBarPolicy.FIELD_TOPIC,
                TOPIC_AUTOMATION_ID, "Уведомление · Тема",
                "Тема", 1, false);
        ensureField(items, PhoneStatusBarPolicy.FIELD_TEXT,
                TEXT_AUTOMATION_ID, "Уведомление · Текст",
                "Текст", 2, false);
        itemStore.save(items);
    }

    private static void ensureField(@NonNull List<PopupItemConfig> items,
                                    @NonNull String fieldId,
                                    @NonNull String automationId,
                                    @NonNull String name,
                                    @NonNull String title,
                                    int row,
                                    boolean showPhoneIcon) {
        PopupItemConfig item = null;
        for (PopupItemConfig candidate : items) {
            if (automationId.equals(candidate.id)
                    || automationId.equals(candidate.automationId)) {
                item = candidate;
                break;
            }
        }
        if (item == null) {
            item = PopupItemConfig.create(automationId, items.size());
            item.name = name;
            item.title = title;
            item.row = row;
            item.column = 0;
            item.icon = "phone";
            item.iconSize = showPhoneIcon ? 42 : 0;
            item.orientation = 1;
            item.showTitle = true;
            item.showStatus = true;
            item.titleSize = 18;
            item.textSize = 22;
            // Automatic backplates are deliberately absent. The user can add a background,
            // border and rounding in the same visual editor when desired.
            item.backgroundColor = "#00000000";
            item.backgroundAlpha = 0;
            item.borderAlpha = 0;
            item.borderWidth = 0;
            item.cornerRadius = 0;
            item.padding = 0;
            items.add(item);
        }
        // Repair identity/source invariants only. Geometry, fonts and appearance remain exactly
        // as the user configured them after the first creation.
        item.overlayId = OVERLAY_ID;
        item.automationId = automationId;
        item.type = PopupItemConfig.TYPE_HA_TEXT;
        item.enabled = true;
        item.sourceBinding = new SourceBinding(ConnectorType.PHONE,
                SourceBinding.DEFAULT_CONNECTOR_ID, "notifications.latest",
                "@value." + fieldId, SourceBinding.PRESENTATION_RAW, "");
        item.actionBinding = ActionBinding.unbound();
        if (item.displayRules == null) item.displayRules = ScenarioPresets.raw();
    }
}
