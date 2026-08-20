/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.launcher.LauncherSettingsMigrationRegistry;
import dezz.status.widget.phone.PhoneNotificationDeferralPolicy;

public class Preferences {
    private static final String TAG = "Preferences";
    private static final String PHONE_BLE_V2_SWITCH_SNAPSHOT_KEY =
            "phoneBleV2SwitchSnapshot";
    private static final String PHONE_BLE_V2_HELPER_INSTALLATION_ID_KEY =
            "phoneBleV2HelperInstallationId";
    private static final String PHONE_BLE_V2_ANDROID_INSTALLATION_ID_KEY =
            "phoneBleV2AndroidInstallationId";
    private static final String PHONE_BLE_V2_ENROLLMENT_RECORD_KEY =
            "phoneBleV2EnrollmentRecord";
    private static final String PHONE_BLE_V2_ENROLLMENT_PENDING_RECORD_KEY =
            "phoneBleV2EnrollmentPendingRecord";

    private static final String SYSTEM_UI_PENDING_ACTIVE = "systemUiPendingActive";
    private static final String SYSTEM_UI_PENDING_ENABLED = "systemUiPendingEnabled";
    private static final String SYSTEM_UI_PENDING_ROLLBACK_NULL =
            "systemUiPendingRollbackNull";
    private static final String SYSTEM_UI_PENDING_ROLLBACK_RAW =
            "systemUiPendingRollbackRaw";
    private static final String SYSTEM_UI_PENDING_DESIRED_NULL =
            "systemUiPendingDesiredNull";
    private static final String SYSTEM_UI_PENDING_DESIRED_RAW =
            "systemUiPendingDesiredRaw";
    private static final String SYSTEM_UI_PENDING_OWNED_SLOTS =
            "systemUiPendingOwnedSlots";
    /** New large HOME surfaces must remain opt-in when an existing layout is upgraded. */
    static final boolean DEFAULT_LAUNCHER_VEHICLE_INFO_VISIBLE = false;
    /** Secrets and installation identities never leave the device through settings exports or
     * presets. Keep future connector credentials/identities here as well so adding a transport
     * cannot accidentally make them exportable or clone one client identity to another device. */
    private static final Set<String> SECRET_PREFERENCE_KEYS = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    "mqttPassword", "sprutPassword", "sprutClientId", "haAccessToken",
                    // The paired phone is installation-specific and must not be copied into a
                    // settings backup restored on another head unit.
                    "phoneDeviceAddress",
                    // Legacy reverse-route cache may remain in an upgraded SharedPreferences
                    // file. It is never read by v2 and remains non-exportable until Android
                    // eventually compacts the preference file.
                    "phoneAncsDeviceAddress",
                    // ANCS v2 role ownership and installation proofs are local protocol state.
                    // Copying any of them to another head unit could replay a stale drain or
                    // impersonate the installation identity used by the paired Helper.
                    PHONE_BLE_V2_SWITCH_SNAPSHOT_KEY,
                    PHONE_BLE_V2_HELPER_INSTALLATION_ID_KEY,
                    PHONE_BLE_V2_ANDROID_INSTALLATION_ID_KEY,
                    PHONE_BLE_V2_ENROLLMENT_RECORD_KEY,
                    PHONE_BLE_V2_ENROLLMENT_PENDING_RECORD_KEY,
                    "phoneBleExperimentalRouteBEnabled",
                    // Contains both the full bearer action and fixed-endpoint token. Layout
                    // presets are routinely shared, so rules must remain device-local too.
                    "intentActionRulesJson")));

    /** State tied to this head unit and its current SystemUI must never enter backups/presets. */
    private static final Set<String> DEVICE_LOCAL_PREFERENCE_KEYS =
            Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
                    "systemUiHideStockContentGlobally",
                    "systemUiOwnedHiddenSlots",
                    SYSTEM_UI_PENDING_ACTIVE,
                    SYSTEM_UI_PENDING_ENABLED,
                    SYSTEM_UI_PENDING_ROLLBACK_NULL,
                    SYSTEM_UI_PENDING_ROLLBACK_RAW,
                    SYSTEM_UI_PENDING_DESIRED_NULL,
                    SYSTEM_UI_PENDING_DESIRED_RAW,
                    SYSTEM_UI_PENDING_OWNED_SLOTS)));
    public static abstract class Preference {
        final Preferences preferences;
        final String key;

        public Preference(Preferences preferences, String key) {
            this.preferences = preferences;
            this.key = key;
        }

        public void reset() {
            preferences.prefs.edit().remove(key).apply();
        }
    }

    public static final class Bool extends Preference {
        private final boolean defaultValue;

        public Bool(Preferences preferences, String key, boolean defaultValue) {
            super(preferences, key);
            this.defaultValue = defaultValue;
        }

        public boolean get() {
            return preferences.prefs.getBoolean(key, defaultValue);
        }

        public void set(boolean value) {
            preferences.prefs.edit().putBoolean(key, value).apply();
        }
    }

    public static final class StringSet extends Preference {
        public StringSet(Preferences preferences, String key) {
            super(preferences, key);
        }

        public Set<String> get() {
            Set<String> stored = preferences.prefs.getStringSet(key, Collections.emptySet());
            // SharedPreferences may return a backing collection — copy to avoid surprises on edit().
            return new HashSet<>(stored);
        }

        public void set(Set<String> value) {
            preferences.prefs.edit().putStringSet(key, new HashSet<>(value)).apply();
        }
    }

    /** Durable write-ahead record for an ownership-safe SystemUI mutation. */
    public static final class PendingSystemStatusBarContentState {
        public final boolean enabled;
        @Nullable public final String rollbackRaw;
        @Nullable public final String desiredRaw;
        @NonNull public final Set<String> ownedSlots;

        private PendingSystemStatusBarContentState(boolean enabled,
                                                   @Nullable String rollbackRaw,
                                                   @Nullable String desiredRaw,
                                                   @NonNull Set<String> ownedSlots) {
            this.enabled = enabled;
            this.rollbackRaw = rollbackRaw;
            this.desiredRaw = desiredRaw;
            this.ownedSlots = Collections.unmodifiableSet(new HashSet<>(ownedSlots));
        }
    }

    public static final class Int extends Preference {
        private final int defaultValue;

        public Int(Preferences preferences, String key, int defaultValue) {
            super(preferences, key);
            this.defaultValue = defaultValue;
        }

        public int get() {
            return preferences.prefs.getInt(key, defaultValue);
        }

        public void set(int value) {
            preferences.prefs.edit().putInt(key, value).apply();
        }
    }

    public static final class Str extends Preference {
        private final String defaultValue;

        public Str(Preferences preferences, String key, String defaultValue) {
            super(preferences, key);
            this.defaultValue = defaultValue;
        }

        public String get() {
            return preferences.prefs.getString(key, defaultValue);
        }

        public void set(String value) {
            preferences.prefs.edit().putString(key, value).apply();
        }
    }

    /** A string encrypted with an app-private Android Keystore key. */
    public static final class Secret extends Preference {
        public Secret(Preferences preferences, String key) { super(preferences, key); }

        public String get() {
            String stored = preferences.prefs.getString(key, "");
            try {
                String plain = SecretStore.decrypt(preferences.appContext, stored);
                // Migrate an old plaintext value immediately after a successful read.
                if (!stored.isEmpty() && !stored.startsWith("v1:")) set(plain);
                return plain;
            } catch (Exception e) {
                Log.w(TAG, "Secret is unavailable until Android Keystore unlocks", e);
                return "";
            }
        }

        public void set(String value) {
            try {
                preferences.prefs.edit().putString(key,
                        SecretStore.encrypt(preferences.appContext, value == null ? "" : value))
                        .apply();
            } catch (Exception e) {
                Log.e(TAG, "Could not encrypt secret", e);
                throw new IllegalStateException("Android Keystore is unavailable", e);
            }
        }
    }

    /** Common settings for any text-based brick. */
    public static class TextBrickPrefs {
        public final String prefix;
        public final Int fontSize;
        public final Int outlineAlpha;
        public final Int outlineWidth;
        public final Int marginStart;
        public final Int marginEnd;
        public final Int adjustY;
        /** {@link Fonts.Family#key} of the chosen font family. */
        public final Str fontFamily;
        public final Bool fontBold;
        public final Bool fontItalic;
        /** Apps where the brick should be hidden when its own list is in effect. */
        public final StringSet hideInPackages;
        /**
         * If non-empty, the {@link BrickType#name()} of another brick whose list to inherit.
         * Empty string means use {@link #hideInPackages}.
         */
        public final Str hideSource;
        /** Position group inside status-bar mode: 0 = start, 1 = center, 2 = end. */
        public final Int statusAlignment;
        /**
         * When the brick is hidden by a foreground-app match, reserve its space instead of
         * collapsing siblings. Maps to {@code View.INVISIBLE} vs {@code View.GONE}.
         */
        public final Bool hideKeepsSpace;
        /** Whole-element opacity 0..255 — applied to the View via {@code setAlpha(value/255f)}. */
        public final Int contentAlpha;

        public TextBrickPrefs(Preferences p, String prefix, int defaultFontSize) {
            this.prefix = prefix;
            fontSize = new Int(p, prefix + "FontSize", defaultFontSize);
            outlineAlpha = new Int(p, prefix + "OutlineAlpha", 0xAA);
            outlineWidth = new Int(p, prefix + "OutlineWidth", 2);
            marginStart = new Int(p, prefix + "MarginStart", 0);
            marginEnd = new Int(p, prefix + "MarginEnd", 0);
            adjustY = new Int(p, prefix + "AdjustY", 0);
            fontFamily = new Str(p, prefix + "FontFamily", Fonts.DEFAULT_KEY);
            fontBold = new Bool(p, prefix + "FontBold", false);
            fontItalic = new Bool(p, prefix + "FontItalic", false);
            hideInPackages = new StringSet(p, prefix + "HideInPackages");
            hideSource = new Str(p, prefix + "HideSource", "");
            statusAlignment = new Int(p, prefix + "StatusAlignment", 0);
            hideKeepsSpace = new Bool(p, prefix + "HideKeepsSpace", false);
            contentAlpha = new Int(p, prefix + "ContentAlpha", 255);
        }

        public String hideInPackagesKey() {
            return prefix + "HideInPackages";
        }
    }

    /** Date brick — date number, day of week, formatting and ordering options. */
    public static final class DateBrickPrefs extends TextBrickPrefs {
        public final Bool showDate;
        public final Bool showDayOfWeek;
        public final Bool showFullName;
        public final Bool dateBeforeDayOfWeek;
        public final Bool oneLineLayout;
        public final Int alignment;

        public DateBrickPrefs(Preferences p) {
            super(p, "date", 20);
            showDate = new Bool(p, "dateShowDate", true);
            showDayOfWeek = new Bool(p, "dateShowDayOfWeek", true);
            showFullName = new Bool(p, "dateShowFullName", false);
            dateBeforeDayOfWeek = new Bool(p, "dateBeforeDayOfWeek", false);
            oneLineLayout = new Bool(p, "dateOneLineLayout", false);
            alignment = new Int(p, "dateAlignment", 0);
        }
    }

    /** Media brick — has its own max-width to bound marquee scrolling. */
    public static final class MediaBrickPrefs extends TextBrickPrefs {
        public final Int maxWidth;
        /** Horizontal alignment of the two text lines inside the media container: 0/1/2 = start/center/end. */
        public final Int alignment;
        /** Whether to show the app-name line above the track title. */
        public final Bool showSource;
        /** Whether to draw the play/pause state glyph independently of both text rows. */
        public final Bool showPlaybackStateIcon;
        /** Hide the complete status-row media brick unless MediaSession reports PLAYING. */
        public final Bool onlyWhilePlaying;
        /** {@code true} → render as "title — artist"; {@code false} (default) → "artist — title". */
        public final Bool titleFirst;
        /** Vertical gap (px) between the app-name line and the track-title line. */
        public final Int lineGap;
        /**
         * {@code true} (default) → on overflow scroll the text continuously past the max-width;
         * {@code false} → render statically up to max-width and cut off with an ellipsis.
         */
        public final Bool marqueeEnabled;
        /**
         * Show a thin progress bar under the title line that fills as the current track plays.
         * Default on. Off-state hides the bar entirely without affecting the rest of the brick.
         */
        public final Bool progressBarEnabled;
        /**
         * Show the track's total duration to the right of the title (e.g. "4:56"). Default on.
         * Hidden automatically when the player doesn't report a usable duration.
         */
        public final Bool showDuration;

        /**
         * Duration text settings — independent from the title's font so the user can tune it to a
         * smaller / less prominent style without affecting the track subtitle. Font family / bold
         * / italic are inherited from the title (these covers the common case; can be split out
         * later if anyone asks).
         */
        public final Int durationFontSize;
        public final Int durationContentAlpha;
        public final Int durationOutlineAlpha;
        public final Int durationOutlineWidth;

        /**
         * Source-line ("now playing in &lt;app&gt;") text settings. Title keeps using the
         * inherited {@code TextBrickPrefs} fields (fontSize, fontFamily, fontBold/Italic,
         * outlineAlpha/Width, contentAlpha) so existing presets keep working.
         */
        public final Int sourceFontSize;
        public final Str sourceFontFamily;
        public final Bool sourceFontBold;
        public final Bool sourceFontItalic;
        public final Int sourceContentAlpha;
        public final Int sourceOutlineAlpha;
        public final Int sourceOutlineWidth;
        /**
         * Horizontal alignment of the source line within the media container: 0/1/2 =
         * start/center/end. Title uses the existing {@link #alignment} pref so old presets
         * keep working unchanged.
         */
        public final Int sourceAlignment;

        public MediaBrickPrefs(Preferences p) {
            super(p, "media", 20);
            maxWidth = new Int(p, "mediaMaxWidth", 500);
            alignment = new Int(p, "mediaAlignment", 0);
            showSource = new Bool(p, "mediaShowSource", true);
            showPlaybackStateIcon = new Bool(p, "mediaShowPlaybackStateIcon", true);
            onlyWhilePlaying = new Bool(p, "mediaOnlyWhilePlaying", false);
            titleFirst = new Bool(p, "mediaTitleFirst", false);
            lineGap = new Int(p, "mediaLineGap", 0);
            marqueeEnabled = new Bool(p, "mediaMarqueeEnabled", true);
            progressBarEnabled = new Bool(p, "mediaProgressBarEnabled", true);
            showDuration = new Bool(p, "mediaShowDuration", true);
            durationFontSize = new Int(p, "mediaDurationFontSize", 16);
            durationContentAlpha = new Int(p, "mediaDurationContentAlpha", 255);
            durationOutlineAlpha = new Int(p, "mediaDurationOutlineAlpha", 0xAA);
            durationOutlineWidth = new Int(p, "mediaDurationOutlineWidth", 2);
            sourceFontSize = new Int(p, "mediaSourceFontSize", 20);
            sourceFontFamily = new Str(p, "mediaSourceFontFamily", Fonts.DEFAULT_KEY);
            sourceFontBold = new Bool(p, "mediaSourceFontBold", false);
            sourceFontItalic = new Bool(p, "mediaSourceFontItalic", false);
            sourceContentAlpha = new Int(p, "mediaSourceContentAlpha", 255);
            sourceOutlineAlpha = new Int(p, "mediaSourceOutlineAlpha", 0xAA);
            sourceOutlineWidth = new Int(p, "mediaSourceOutlineWidth", 2);
            sourceAlignment = new Int(p, "mediaSourceAlignment", 0);
        }
    }

    /** Common settings for an icon brick. */
    public static class IconBrickPrefs {
        public final String prefix;
        public final Int size;
        public final Int outlineAlpha;
        public final Int outlineWidth;
        public final Int marginStart;
        public final Int marginEnd;
        public final Int adjustY;
        public final StringSet hideInPackages;
        public final Str hideSource;
        /** Position group inside status-bar mode: 0 = start, 1 = center, 2 = end. */
        public final Int statusAlignment;
        /** Reserve space instead of collapsing when hidden by an app match. */
        public final Bool hideKeepsSpace;
        /** Icon opacity 0..255 — applied to the ImageView via {@code setAlpha(value/255f)}. */
        public final Int contentAlpha;

        public IconBrickPrefs(Preferences p, String prefix) {
            this.prefix = prefix;
            size = new Int(p, prefix + "Size", 70);
            outlineAlpha = new Int(p, prefix + "OutlineAlpha", 0xAA);
            outlineWidth = new Int(p, prefix + "OutlineWidth", 2);
            marginStart = new Int(p, prefix + "MarginStart", 0);
            marginEnd = new Int(p, prefix + "MarginEnd", 0);
            adjustY = new Int(p, prefix + "AdjustY", 0);
            hideInPackages = new StringSet(p, prefix + "HideInPackages");
            hideSource = new Str(p, prefix + "HideSource", "");
            statusAlignment = new Int(p, prefix + "StatusAlignment", 0);
            hideKeepsSpace = new Bool(p, prefix + "HideKeepsSpace", false);
            contentAlpha = new Int(p, prefix + "ContentAlpha", 255);
        }

        public String hideInPackagesKey() {
            return prefix + "HideInPackages";
        }
    }

    /** GPS brick adds the satellite-count badge toggle. */
    public static final class GpsBrickPrefs extends IconBrickPrefs {
        public final Bool showSatelliteBadge;

        public GpsBrickPrefs(Preferences p) {
            super(p, "gps");
            showSatelliteBadge = new Bool(p, "gpsShowSatelliteBadge", true);
        }
    }

    /** Bluetooth brick adds the connected-device-count badge toggle. */
    public static final class BluetoothBrickPrefs extends IconBrickPrefs {
        public final Bool showDeviceCountBadge;

        public BluetoothBrickPrefs(Preferences p) {
            super(p, "bluetooth");
            showDeviceCountBadge = new Bool(p, "bluetoothShowDeviceCountBadge", true);
        }
    }

    /** iPhone battery brick adds the optional percentage rendered inside the solid body. */
    public static final class PhoneBatteryBrickPrefs extends IconBrickPrefs {
        public final Bool showPercentage;

        public PhoneBatteryBrickPrefs(Preferences p) {
            super(p, "phoneBattery");
            showPercentage = new Bool(p, "phoneBatteryShowPercentage", true);
        }
    }

    /** Combined cellular brick can independently include or hide its radio-generation label. */
    public static final class PhoneCellularBrickPrefs extends IconBrickPrefs {
        public final Bool showNetworkType;

        public PhoneCellularBrickPrefs(Preferences p) {
            super(p, "phoneCellular");
            showNetworkType = new Bool(p, "phoneCellularShowNetworkType", true);
        }
    }

    /** Persisted generation marker retained only for one-time HA1084 migration. */
    public enum DriverPanelStyle {
        OLD("old"),
        NEW("new");

        @NonNull public final String key;

        DriverPanelStyle(@NonNull String key) {
            this.key = key;
        }

        @NonNull
        public static DriverPanelStyle fromKey(@Nullable String key) {
            return NEW.key.equalsIgnoreCase(key == null ? "" : key) ? NEW : OLD;
        }
    }

    /**
     * One complete, independently persisted driver-panel profile.
     *
     * <p>The old profile deliberately keeps every HA1082 storage key unchanged for migration.
     * HA1085 exposes and runs only the current profile in the additive
     * {@code driverPanelNew*} namespace.</p>
     */
    public static final class DriverPanelProfile {
        @NonNull public final DriverPanelStyle style;
        public final Int side;
        public final Int widthPx;
        public final Int topPaddingPx;
        public final Int bottomPaddingPx;
        public final Int itemGapPx;
        public final Int cornerRadiusPx;
        public final Str backgroundColor;
        public final Str borderColor;
        public final Int borderWidthPx;
        public final Str shortcutsJson;

        private DriverPanelProfile(@NonNull Preferences preferences,
                                   @NonNull DriverPanelStyle style,
                                   @NonNull String prefix,
                                   int defaultWidthPx) {
            this.style = style;
            side = new Int(preferences, prefix + "Side", 0);
            widthPx = new Int(preferences, prefix + "WidthPx", defaultWidthPx);
            topPaddingPx = new Int(preferences, prefix + "TopPaddingPx", 8);
            bottomPaddingPx = new Int(preferences, prefix + "BottomPaddingPx", 8);
            itemGapPx = new Int(preferences, prefix + "ItemGapPx", 10);
            // Driver mode in the reference applies a minimum 20 px radius over the 16 px resource.
            cornerRadiusPx = new Int(preferences, prefix + "CornerRadiusPx", 20);
            backgroundColor = new Str(preferences, prefix + "BackgroundColor", "#FF13171C");
            borderColor = new Str(preferences, prefix + "BorderColor", "#00FFFFFF");
            borderWidthPx = new Int(preferences, prefix + "BorderWidthPx", 0);
            shortcutsJson = new Str(preferences, prefix + "ShortcutsJson", "");
        }
    }

    private final SharedPreferences prefs;
    private volatile boolean startupMigrationsComplete;
    private final Context appContext;

    // Global widget settings.
    public final Bool widgetEnabled = new Bool(this, "enabled", false);
    public final Bool widgetAlignRight = new Bool(this, "widgetAlignRight", false);
    /** 0 = floating overlay (current behaviour), 1 = full-width status bar at the top. */
    public final Int widgetMode = new Int(this, "widgetMode", 0);
    public final Int iconDesign = new Int(this, "iconDesign", 0);
    public final Int iconStyle = new Int(this, "iconStyle", 0);
    /** 0 = follow system, 1 = always light, 2 = always dark, 3 = inverse of system. */
    public final Int widgetTheme = new Int(this, "widgetTheme", 0);
    public final Int backgroundAlpha = new Int(this, "backgroundAlpha", 0xAA);
    public final Int backgroundCornerRadius = new Int(this, "backgroundCornerRadius", 100);
    public final Int overlayX = new Int(this, "overlayX", 200);
    public final Int overlayY = new Int(this, "overlayY", 300);
    /** Padding inside the widget container on each side, in px. */
    public final Int paddingLeft = new Int(this, "paddingLeft", 40);
    public final Int paddingTop = new Int(this, "paddingTop", 0);
    public final Int paddingRight = new Int(this, "paddingRight", 40);
    public final Int paddingBottom = new Int(this, "paddingBottom", 0);
    public final StringSet hideInPackages = new StringSet(this, "hideInPackages");

    /** Comma-separated list of brick types in display order. Missing types are hidden. */
    public final Str brickOrder = new Str(this, "brickOrder", "TIME,DATE,WIFI,GPS");

    /**
     * Whether the user has been shown the notification access prompt at least once. Used to keep
     * the media brick "active" only when the user has explicitly granted access.
     */
    public final Bool mediaEnabled = new Bool(this, "mediaEnabled", false);

    // Per-brick settings.
    public final TextBrickPrefs time = new TextBrickPrefs(this, "time", 60);
    public final DateBrickPrefs date = new DateBrickPrefs(this);
    public final MediaBrickPrefs media = new MediaBrickPrefs(this);
    public final IconBrickPrefs wifi = new IconBrickPrefs(this, "wifi");
    public final GpsBrickPrefs gps = new GpsBrickPrefs(this);
    public final BluetoothBrickPrefs bluetooth = new BluetoothBrickPrefs(this);
    public final PhoneCellularBrickPrefs phoneCellular = new PhoneCellularBrickPrefs(this);
    public final PhoneBatteryBrickPrefs phoneBattery = new PhoneBatteryBrickPrefs(this);
    public final TextBrickPrefs phoneNetworkType =
            new TextBrickPrefs(this, "phoneNetworkType", 36);
    // Car-specific temperature bricks (fed by the flavor's CarIntegration).
    public final TextBrickPrefs indoorTemp = new TextBrickPrefs(this, "indoorTemp", 40);
    public final TextBrickPrefs outdoorTemp = new TextBrickPrefs(this, "outdoorTemp", 40);
    /** Layout-level settings for the dynamic HA row. Child text styles live in haMainBricksJson. */
    public final TextBrickPrefs homeAssistant = new TextBrickPrefs(this, "homeAssistant", 40);
    /** Shared text appearance for the selected iPhone scalar blocks in the status row. */
    public final TextBrickPrefs phoneStatus = new TextBrickPrefs(this, "phoneStatus", 20);

    // Home Assistant / automation configuration. JSON arrays are versioned by their model
    // classes and therefore automatically participate in the existing settings export/import.
    public final Str haMainBricksJson = new Str(this, "haMainBricksJson", "[]");
    public final Str popupItemsJson = new Str(this, "popupItemsJson", "[]");
    /** Independent floating overlay windows. Empty means the legacy popup settings still need
     * to be projected into the default `popup` overlay by PopupOverlayConfigStore. */
    public final Str popupOverlaysJson = new Str(this, "popupOverlaysJson", "");
    /** Dedicated CarPlay-style notification-card composition for the two phone overlays. */
    public final Str phoneNotificationLayoutsJson = new Str(
            this, "phoneNotificationLayoutsJson", "[]");
    /** Ordered connector-neutral local scenarios. Conditions and UI targets are independent. */
    public final Str localScenariosJson = new Str(this, "localScenariosJson", "[]");
    /** One-shot, exact Android Intent actions mapped to stored connector commands. */
    public final Str intentActionRulesJson = new Str(this, "intentActionRulesJson", "[]");
    /** Per-metric mappings from the vehicle SDK to writable Sprut.hub characteristics. */
    public final Str carSprutBindingsJson = new Str(this, "carSprutBindingsJson", "[]");
    public final Bool popupEnabled = new Bool(this, "popupEnabled", true);
    public final Int popupWidth = new Int(this, "popupWidth", 500);
    public final Int popupHeight = new Int(this, "popupHeight", 500);
    public final Int popupRows = new Int(this, "popupRows", 2);
    public final Int popupColumns = new Int(this, "popupColumns", 2);
    public final Int popupX = new Int(this, "popupX", 200);
    public final Int popupY = new Int(this, "popupY", 300);
    public final Int popupPaddingLeft = new Int(this, "popupPaddingLeft", 12);
    public final Int popupPaddingTop = new Int(this, "popupPaddingTop", 12);
    public final Int popupPaddingRight = new Int(this, "popupPaddingRight", 12);
    public final Int popupPaddingBottom = new Int(this, "popupPaddingBottom", 12);
    public final Int popupCellGap = new Int(this, "popupCellGap", 8);
    public final Str popupBackgroundColor = new Str(this, "popupBackgroundColor", "#FF000000");
    public final Int popupBackgroundAlpha = new Int(this, "popupBackgroundAlpha", 0xCC);
    public final Int popupCornerRadius = new Int(this, "popupCornerRadius", 28);

    // Optional HOME/launcher surface. These settings deliberately live in the same exported
    // preference file as the widget and connector configuration, so installing a newer APK keeps
    // one coherent setup and Import/Export can move the whole dashboard to another head unit.
    // Geometry is stored as versioned JSON because launcher elements are independent rectangles
    // (x/y/width/height) and the set will grow as new panels are added.
    public final Str launcherLayoutJson = new Str(this, "launcherLayoutJson", "");
    /** Screen-wide geometry of individual HOME elements after migration from panel-local grids. */
    public final Str launcherGlobalElementsJson = new Str(this,
            "launcherGlobalElementsJson", "");
    /** Independent decorative HOME layers. They are always rendered below live widgets. */
    public final Str launcherBackdropsJson = new Str(this, "launcherBackdropsJson", "");
    /** Horizontal free-frame groups. Members keep their own style/action and text size. */
    public final Str launcherHorizontalGroupsJson = new Str(
            this, "launcherHorizontalGroupsJson", "");
    /** Recoverable, immutable snapshot taken before the flat Launcher settings migration. */
    public final Str launcherUnifiedLegacyBackupJson = new Str(
            this, "launcherUnifiedLegacyBackupJson", "");
    /** Idempotent schema marker for the audited launcher-settings registry. */
    public final Int launcherUnifiedSettingsMigrationVersion = new Int(
            this, "launcherUnifiedSettingsMigrationVersion", 0);
    public final Str launcherFavoritePackages = new Str(this, "launcherFavoritePackages", "");
    /** Per-application HOME icon/label sizes; selection and order remain in the legacy list. */
    public final Str launcherFavoriteAppsAppearanceJson = new Str(this,
            "launcherFavoriteAppsAppearanceJson", "");
    /** Shared appearance/filter for every runtime "Все приложения" surface. */
    public final Int launcherAllAppsColumns = new Int(this,
            "launcherAllAppsColumns", 5);
    public final Int launcherAllAppsIconScalePercent = new Int(this,
            "launcherAllAppsIconScalePercent", 100);
    public final Int launcherAllAppsGapPx = new Int(this,
            "launcherAllAppsGapPx", 8);
    /** Flattened launcher components hidden from both HOME and driver-panel catalogs. */
    public final StringSet launcherAllAppsHiddenComponents = new StringSet(
            this, "launcherAllAppsHiddenComponents");
    /** One-time HA1132 default: system apps are hidden except the user-facing Phone app. */
    public final Bool launcherSystemAppsDefaultApplied = new Bool(
            this, "launcherSystemAppsDefaultApplied", false);
    public final Str launcherBackgroundColor = new Str(this, "launcherBackgroundColor", "#101827");
    public final Bool launcherShowGrid = new Bool(this, "launcherShowGrid", true);
    public final Int launcherSnapPx = new Int(this, "launcherSnapPx", 20);
    public final Bool launcherImmersive = new Bool(this, "launcherImmersive", true);
    /** Legacy key retained: now hides only TIME and BLUETOOTH views while our HOME is resumed. */
    public final Bool launcherHideSystemStatusBar = new Bool(this,
            "launcherHideSystemStatusBar", false);
    public final Str launcherSystemStatusBarOriginalPolicy = new Str(this,
            "launcherSystemStatusBarOriginalPolicy", "__unset__");
    /** Globally hides firmware-declared stock SystemUI slots while preserving the bar itself. */
    public final Bool systemUiHideStockContentGlobally = new Bool(this,
            "systemUiHideStockContentGlobally", false);
    /** Only slots added by Natro; disable removes this set and preserves every other owner. */
    public final StringSet systemUiOwnedHiddenSlots = new StringSet(this,
            "systemUiOwnedHiddenSlots");
    /** Explicit HOME chain requested for ECARX: HOME -> our launcher -> windowed Navigator. */
    public final Bool launcherHomeOpensWindowedNavigator = new Bool(this,
            "launcherHomeOpensWindowedNavigator", false);
    public final Bool launcherAppsVisible = new Bool(this, "launcherAppsVisible", true);
    public final Bool launcherMediaVisible = new Bool(this, "launcherMediaVisible", true);
    /** Resume the exact player remembered before shutdown; remains opt-in on every upgrade. */
    public final Bool launcherMediaAutoResumeEnabled = new Bool(this,
            "launcherMediaAutoResumeEnabled", false);
    /** Delay lets ECARX finish restoring its audio/player services; mSaver's proven default is 5s. */
    public final Int launcherMediaAutoResumeDelaySeconds = new Int(this,
            "launcherMediaAutoResumeDelaySeconds", 5);
    /**
     * Routes every HOME media action to one explicitly selected Android package. When disabled,
     * the last real player recorded by MediaSession/mHUD is used instead.
     */
    public final Bool launcherMediaFixedPlayerEnabled = new Bool(this,
            "launcherMediaFixedPlayerEnabled", false);
    public final Str launcherMediaFixedPlayerPackage = new Str(this,
            "launcherMediaFixedPlayerPackage", "");
    public final Bool launcherClockVisible = new Bool(this, "launcherClockVisible", true);
    public final Bool launcherNavigationVisible = new Bool(this, "launcherNavigationVisible", true);
    public final Bool launcherActionsVisible = new Bool(this, "launcherActionsVisible", true);
    public final Str launcherMediaConfigJson = new Str(this, "launcherMediaConfigJson", "");
    // User-defined one-tap destinations (Home, Work, etc.). They share one adaptive HOME panel
    // with current-route information: favorites are the idle state, navigation is the live state.
    public final Str launcherFavoriteRoutesJson = new Str(this,
            "launcherFavoriteRoutesJson", "");
    public final Bool launcherFavoriteRoutesVisible = new Bool(this,
            "launcherFavoriteRoutesVisible", false);
    public final Int launcherFavoriteRoutesColumns = new Int(this,
            "launcherFavoriteRoutesColumns", 2);
    /** One-shot migration prevents a later partial import/toggle from re-anchoring the panel. */
    public final Bool launcherCombinedNavigationMigrated = new Bool(this,
            "launcherCombinedNavigationMigrated", false);
    // Opt-in on upgrades so a new large panel never covers an existing hand-tuned HOME layout.
    public final Bool launcherClimateVisible = new Bool(this, "launcherClimateVisible", false);
    // Independent HOME surface for live eCarX/HUD telemetry.  Content and appearance live in a
    // versioned JSON document so new metrics can be added without invalidating existing layouts.
    public final Bool launcherVehicleInfoVisible = new Bool(this,
            "launcherVehicleInfoVisible", DEFAULT_LAUNCHER_VEHICLE_INFO_VISIBLE);
    public final Str launcherVehicleInfoConfigJson = new Str(this,
            "launcherVehicleInfoConfigJson", "");
    /** Independent read-only HOME panel combining car/system and smart-home statuses. */
    public final Bool launcherInformationVisible = new Bool(this,
            "launcherInformationVisible", false);
    public final Str launcherInformationConfigJson = new Str(this,
            "launcherInformationConfigJson", "");
    // Per-panel inner element visibility/order/scale. Kept separate from outer pixel geometry so
    // older HOME layouts migrate without moving any panel on upgrade.
    public final Str launcherPanelElementsJson = new Str(this, "launcherPanelElementsJson", "");
    /** Cell geometry for the WYSIWYG navigation editor; migrated from launcherPanelElementsJson. */
    public final Str launcherNavigationConfigJson = new Str(this,
            "launcherNavigationConfigJson", "");
    /** HOME climate widgets only; the floating surface was split out by HA1132. */
    public final Str launcherClimateConfigJson = new Str(this, "launcherClimateConfigJson", "");
    /** Independent appearance/content for the floating climate surface. */
    public final Str floatingClimateConfigJson = new Str(this,
            "floatingClimateConfigJson", "");
    // Optional always-on climate surface. It is deliberately independent from both the HOME
    // climate panel above and the status widget service: a user may want climate controls while
    // another application occupies the main display. Defaults are opt-in and preserve every
    // existing installation's layout on update.
    public final Bool climatePanelEnabled = new Bool(this, "climatePanelEnabled", false);
    /** 0 = compact overlay button, 1 = persistent panel with a reserved screen edge. */
    public final Int climatePanelMode = new Int(this, "climatePanelMode", 0);
    /** 0 = bottom, 1 = top, 2 = left, 3 = right. */
    public final Int climatePanelEdge = new Int(this, "climatePanelEdge", 0);
    public final Int climatePanelExtent = new Int(this, "climatePanelExtent", 180);
    public final Int climatePanelDisplayId = new Int(this, "climatePanelDisplayId", 0);
    public final Int climateOverlayWidth = new Int(this, "climateOverlayWidth", 1200);
    public final Int climateOverlayHeight = new Int(this, "climateOverlayHeight", 360);
    public final Int climateButtonSize = new Int(this, "climateButtonSize", 84);
    public final Int climateButtonX = new Int(this, "climateButtonX", 40);
    public final Int climateButtonY = new Int(this, "climateButtonY", 300);
    public final Bool climateButtonLocked = new Bool(this, "climateButtonLocked", false);
    public final Str launcherShortcutsJson = new Str(this, "launcherShortcutsJson", "");
    // Current Monjaro driver rail plus the read-only legacy profile used by one-time migration.
    public final Bool driverPanelEnabled = new Bool(this, "driverPanelEnabled", false);
    public final Str driverPanelStyle = new Str(this, "driverPanelStyle",
            DriverPanelStyle.NEW.key);
    public final DriverPanelProfile driverPanelOld = new DriverPanelProfile(
            this, DriverPanelStyle.OLD, "driverPanel", 120);
    public final DriverPanelProfile driverPanelNew = new DriverPanelProfile(
            this, DriverPanelStyle.NEW, "driverPanelNew", 150);
    // Source-compatible aliases now point at the only user-visible current profile.
    /** 0 = left/start edge, 1 = right/end edge. */
    public final Int driverPanelSide = driverPanelNew.side;
    public final Int driverPanelWidthPx = driverPanelNew.widthPx;
    public final Int driverPanelTopPaddingPx = driverPanelNew.topPaddingPx;
    public final Int driverPanelBottomPaddingPx = driverPanelNew.bottomPaddingPx;
    public final Int driverPanelItemGapPx = driverPanelNew.itemGapPx;
    public final Int driverPanelCornerRadiusPx = driverPanelNew.cornerRadiusPx;
    public final Str driverPanelBackgroundColor = driverPanelNew.backgroundColor;
    public final Str driverPanelBorderColor = driverPanelNew.borderColor;
    public final Int driverPanelBorderWidthPx = driverPanelNew.borderWidthPx;
    /** Ordered collection for the unified current driver panel. */
    public final Str driverPanelShortcutsJson = driverPanelNew.shortcutsJson;
    /** Independent fully customizable drawer opened from a driver-panel Favorites shortcut. */
    public final Str driverFavoritesShortcutsJson = new Str(this,
            "driverFavoritesShortcutsJson", "");
    /** Unlimited independently addressable compact Favorites panels. */
    public final Str driverFavoritesPanelsJson = new Str(this,
            "driverFavoritesPanelsJson", "");
    /** Last panel edited in settings; does not control runtime visibility. */
    public final Str driverFavoritesSelectedPanelId = new Str(this,
            "driverFavoritesSelectedPanelId", "favorites_default");
    /** Independent multi-display HUD surface. The JSON document contains the selected display,
     * grid, global presentation options and an unlimited ordered element collection. */
    public final Bool hudPanelEnabled = new Bool(this, "hudPanelEnabled", false);
    /** Restore the HUD presentation after boot/package replacement while the master switch is on. */
    public final Bool hudPanelAutostart = new Bool(this, "hudPanelAutostart", true);
    public final Str hudPanelConfigJson = new Str(this, "hudPanelConfigJson", "");
    /** Last explicitly selected OEM ProfileTransfer mode; -1 means do not change it. */
    public final Int hudStockProfileMode = new Int(this, "hudStockProfileMode", -1);
    /**
     * Opt-in conservative fallback. It repeats only the selected mode 0..3 through CB33278 and
     * never sends a visual mask or the generic profile-save signal.
     */
    public final Bool hudStockProfileModeAutoRepeat =
            new Bool(this, "hudStockProfileModeAutoRepeat", false);
    /** Desired values for the five independent ECARX Settings HUD content categories. */
    public final Bool hudStockDriveEnvironment =
            new Bool(this, "hudStockDriveEnvironment", true);
    public final Bool hudStockSafety = new Bool(this, "hudStockSafety", true);
    public final Bool hudStockMedia = new Bool(this, "hudStockMedia", true);
    public final Bool hudStockNavigation = new Bool(this, "hudStockNavigation", true);
    public final Bool hudStockPhone = new Bool(this, "hudStockPhone", true);
    /** Additive cell positions for action/smart-home icons; shortcut actions stay untouched. */
    public final Str launcherActionsGridJson = new Str(this, "launcherActionsGridJson", "");
    public final Int launcherAppsColumns = new Int(this, "launcherAppsColumns", 3);
    public final Int launcherActionsColumns = new Int(this, "launcherActionsColumns", 3);

    /** Local diagnostics are opt-in because the detailed journal can grow to its cyclic limit. */
    public final Bool debugModeEnabled = new Bool(this, "debugModeEnabled", false);
    /** Persistent control frame used to start/stop action capture above every application. */
    public final Bool actionRecorderOverlayVisible = new Bool(this,
            "actionRecorderOverlayVisible", false);
    public final Int actionRecorderOverlayX = new Int(this, "actionRecorderOverlayX", 40);
    public final Int actionRecorderOverlayY = new Int(this, "actionRecorderOverlayY", 160);
    public final Int actionRecorderOverlayWidth = new Int(this,
            "actionRecorderOverlayWidth", 420);
    /** Whole-frame alpha, 80..255. */
    public final Int actionRecorderOverlayAlpha = new Int(this,
            "actionRecorderOverlayAlpha", 235);
    /** Passive raw EV_KEY capture. Requires an already-authorised su and is never enabled itself. */
    public final Bool actionRecorderRootInputEnabled = new Bool(this,
            "actionRecorderRootInputEnabled", false);

    public final Bool mqttEnabled = new Bool(this, "mqttEnabled", false);
    public final Str mqttHost = new Str(this, "mqttHost", "");
    public final Int mqttPort = new Int(this, "mqttPort", 1883);
    public final Bool mqttTls = new Bool(this, "mqttTls", false);
    public final Str mqttUsername = new Str(this, "mqttUsername", "");
    public final Secret mqttPassword = new Secret(this, "mqttPassword");
    public final Str mqttClientId = new Str(this, "mqttClientId", "");
    public final Str mqttDeviceId = new Str(this, "mqttDeviceId", "geely");
    public final Str mqttBaseTopic = new Str(this, "mqttBaseTopic", "statuswidget/v1");
    public final Int mqttQos = new Int(this, "mqttQos", 1);
    public final Int mqttKeepAliveSeconds = new Int(this, "mqttKeepAliveSeconds", 30);
    public final Bool mqttKeepAwake = new Bool(this, "mqttKeepAwake", true);

    // Paired iPhone connector. The Bluetooth address is deliberately stored as a normal local
    // preference (the controller needs it before Android Keystore unlock) but is excluded from
    // settings export above because it identifies one physical phone/head-unit pairing.
    public final Bool phoneConnectorEnabled = new Bool(this,
            "phoneConnectorEnabled", false);
    public final Str phoneDeviceAddress = new Str(this, "phoneDeviceAddress", "");
    /**
     * BLE role of the iPhone. Zero preserves the HA1161 route (iPhone peripheral/KX11 central);
     * one enables the opt-in reverse route (iPhone central/KX11 peripheral). Classic profiles
     * never read this preference.
     */
    public final Int phoneBleRole = new Int(this, "phoneBleRole", 0);
    /** Local diagnostics latch; normal settings entry clears it and production stays Route A. */
    public final Bool phoneBleExperimentalRouteBEnabled = new Bool(this,
            "phoneBleExperimentalRouteBEnabled", false);

    /**
     * Returns the write-ahead snapshot owned by the ANCS v2 role-switch coordinator.
     *
     * <p>This value is deliberately not exposed through a regular {@link Str}: transition code
     * must never replace synchronous durability with {@link SharedPreferences.Editor#apply()}.
     * A process can be killed immediately after any BLE callback during an APK replacement.</p>
     */
    @NonNull
    public String phoneBleV2SwitchSnapshot() {
        return prefs.getString(PHONE_BLE_V2_SWITCH_SNAPSHOT_KEY, "");
    }

    /** Distinguishes a first v2 migration from a present-but-empty/torn fail-closed snapshot. */
    public boolean hasPhoneBleV2SwitchSnapshot() {
        return prefs.contains(PHONE_BLE_V2_SWITCH_SNAPSHOT_KEY);
    }

    /** Persists coordinator state before the corresponding BLE effect is allowed to run. */
    public boolean commitPhoneBleV2SwitchSnapshot(@NonNull String encodedSnapshot) {
        return prefs.edit().putString(PHONE_BLE_V2_SWITCH_SNAPSHOT_KEY,
                encodedSnapshot).commit();
    }

    /** Stable Helper installation identity learned only after the encrypted exact-owner H proof. */
    @NonNull
    public String phoneBleV2HelperInstallationId() {
        dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2 pending =
                dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2.parse(
                        phoneBleV2PendingEnrollmentRecord());
        if (pending != null) return pending.helperInstallationId.toString();
        dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2 active =
                dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2.parse(
                        phoneBleV2EnrollmentRecord());
        if (active != null) return active.helperInstallationId.toString();
        return prefs.getString(PHONE_BLE_V2_HELPER_INSTALLATION_ID_KEY, "");
    }

    public boolean commitPhoneBleV2HelperInstallationId(@NonNull String installationId) {
        return prefs.edit().putString(PHONE_BLE_V2_HELPER_INSTALLATION_ID_KEY,
                installationId).commit();
    }

    /** Stable random Android endpoint identity sent in H; generated once per installation. */
    @NonNull
    public String phoneBleV2AndroidInstallationId() {
        return prefs.getString(PHONE_BLE_V2_ANDROID_INSTALLATION_ID_KEY, "");
    }

    public boolean commitPhoneBleV2AndroidInstallationId(@NonNull String installationId) {
        return prefs.edit().putString(PHONE_BLE_V2_ANDROID_INSTALLATION_ID_KEY,
                installationId).commit();
    }

    /** Returns only authenticated ciphertext; legacy plaintext is intentionally rejected. */
    @NonNull
    public String phoneBleV2EnrollmentRecord() {
        return readEncryptedEnrollmentRecord(PHONE_BLE_V2_ENROLLMENT_RECORD_KEY,
                "BLE enrollment record");
    }

    public boolean commitPhoneBleV2EnrollmentRecord(@NonNull String encoded) {
        try {
            String encrypted = SecretStore.encrypt(appContext, encoded);
            return prefs.edit().putString(PHONE_BLE_V2_ENROLLMENT_RECORD_KEY, encrypted)
                    .commit() && encoded.equals(phoneBleV2EnrollmentRecord());
        } catch (Exception error) {
            Log.e(TAG, "Could not persist BLE enrollment record", error);
            return false;
        }
    }

    @NonNull
    public String phoneBleV2PendingEnrollmentRecord() {
        return readEncryptedEnrollmentRecord(PHONE_BLE_V2_ENROLLMENT_PENDING_RECORD_KEY,
                "Pending BLE enrollment");
    }

    public boolean beginPhoneBleV2EnrollmentCommit(@NonNull String encoded) {
        try {
            String encrypted = SecretStore.encrypt(appContext, encoded);
            return prefs.edit().putString(PHONE_BLE_V2_ENROLLMENT_PENDING_RECORD_KEY,
                    encrypted).commit() && encoded.equals(phoneBleV2PendingEnrollmentRecord());
        } catch (Exception error) {
            Log.e(TAG, "Could not stage BLE enrollment record", error);
            return false;
        }
    }

    public boolean completePhoneBleV2EnrollmentCommit(@NonNull String encoded) {
        String encrypted = prefs.getString(PHONE_BLE_V2_ENROLLMENT_PENDING_RECORD_KEY, "");
        return encrypted != null && encrypted.startsWith("v1:")
                && encoded.equals(phoneBleV2PendingEnrollmentRecord())
                && prefs.edit().putString(PHONE_BLE_V2_ENROLLMENT_RECORD_KEY, encrypted)
                .remove(PHONE_BLE_V2_ENROLLMENT_PENDING_RECORD_KEY).commit()
                && encoded.equals(phoneBleV2EnrollmentRecord())
                && phoneBleV2PendingEnrollmentRecord().isEmpty();
    }

    public boolean clearPhoneBleV2PendingEnrollmentRecord() {
        return prefs.edit().remove(PHONE_BLE_V2_ENROLLMENT_PENDING_RECORD_KEY).commit();
    }

    public boolean clearPhoneBleV2EnrollmentRecord() {
        return prefs.edit().remove(PHONE_BLE_V2_ENROLLMENT_RECORD_KEY)
                .remove(PHONE_BLE_V2_ENROLLMENT_PENDING_RECORD_KEY)
                .remove(PHONE_BLE_V2_HELPER_INSTALLATION_ID_KEY).commit();
    }

    @NonNull
    private String readEncryptedEnrollmentRecord(@NonNull String key,
                                                 @NonNull String label) {
        String encrypted = prefs.getString(key, "");
        if (encrypted != null && encrypted.startsWith("v1:")) {
            try {
                return SecretStore.decrypt(appContext, encrypted);
            } catch (Exception error) {
                Log.w(TAG, label + " unavailable until Keystore unlock", error);
            }
        }
        return "";
    }
    public final Bool phoneNotificationsEnabled = new Bool(this,
            "phoneNotificationsEnabled", true);
    public final Bool phoneMessagesEnabled = new Bool(this,
            "phoneMessagesEnabled", false);
    public final Bool phoneIncludeNotificationText = new Bool(this,
            "phoneIncludeNotificationText", false);
    /** Ordered comma-separated ids rendered inside the PHONE_STATUS status-row brick. */
    public final Str phoneStatusBarItems = new Str(this, "phoneStatusItems",
            "connected,battery.level,network.type,network.signal");
    /** Whether a newly received real-time ANCS event temporarily replaces Now Playing. */
    public final Bool phoneStatusBarNotificationsEnabled = new Bool(this,
            "phoneNotificationTickerEnabled", false);
    /** Whether the newest ANCS event is also rendered by its dedicated automation overlay. */
    public final Bool phonePopupNotificationsEnabled = new Bool(this,
            "phoneNotificationPopupEnabled", false);
    /** Suppresses both phone notification destinations unless helper telemetry says locked. */
    public final Bool phoneNotificationsOnlyWhenLocked = new Bool(this,
            "phoneNotificationsOnlyWhenLocked", false);
    /** Holds new phone notifications while one of the selected head-unit apps is foreground. */
    public final Bool phoneNotificationDelayInAppsEnabled = new Bool(this,
            "phoneNotificationDelayInAppsEnabled", false);
    /** Uses Android 9 window add/remove events to hold behind non-foreground overlays (e.g. AVM). */
    public final Bool phoneNotificationDelayForExternalOverlays = new Bool(this,
            "phoneNotificationDelayForExternalOverlays", true);
    /** Exact Android package names; stored as a StringSet for lossless settings migration. */
    public final StringSet phoneNotificationDelayInPackages = new StringSet(this,
            "phoneNotificationDelayInPackages");
    /** Maximum hold time for each notification before it must enter the normal presentation. */
    public final Int phoneNotificationDelayMaxWaitSeconds = new Int(this,
            "phoneNotificationDelayMaxWaitSeconds", 30);
    /** How long the temporary notification presentation remains visible. */
    public final Int phoneStatusBarNotificationSeconds = new Int(this,
            "phoneNotificationTickerSeconds", 10);
    /** Ordered notification fields used by the temporary status-row presentation. */
    public final Str phoneStatusBarNotificationFields = new Str(this,
            "phoneNotificationTickerFields", "application,topic,text");
    /** ANCS categories allowed to enter the live notification cache and presentation surfaces. */
    public final Str phoneNotificationCategoryIds = new Str(this,
            "phoneNotificationCategoryIds", "0,1,2,3,4,5,6,7,8,9,10,11");
    /** 0 = all apps, 1 = only selected apps, 2 = every app except selected apps. */
    public final Int phoneNotificationAppFilterMode = new Int(this,
            "phoneNotificationAppFilterMode", 0);
    /** Canonical iOS application keys used by the selected app-filter mode. */
    public final Str phoneNotificationAppFilterKeys = new Str(this,
            "phoneNotificationAppFilterKeys", "");
    /** Independent text color for live ANCS notifications temporarily replacing Now Playing. */
    public final Str phoneStatusBarNotificationColor = new Str(this,
            "phoneNotificationTickerColor", "#FFFFFFFF");
    /** Two exact-percentage warnings, routed through the ordinary phone-notification surfaces. */
    public final Bool phoneLowBatteryAlertEnabled = new Bool(this,
            "phoneLowBatteryAlertEnabled", false);
    public final Int phoneLowBatteryAlertThreshold = new Int(this,
            "phoneLowBatteryAlertThreshold", 20);
    public final Str phoneLowBatteryAlertColor = new Str(this,
            "phoneLowBatteryAlertColor", "#FFFF453A");
    public final Int phoneLowBatteryAlertThreshold2 = new Int(this,
            "phoneLowBatteryAlertThreshold2", 10);
    public final Str phoneLowBatteryAlertColor2 = new Str(this,
            "phoneLowBatteryAlertColor2", "#FFFF2D55");
    /** Internal latches survive Bluetooth/service restarts and reset only after recovery. */
    public final Bool phoneLowBatteryAlertLatched = new Bool(this,
            "phoneLowBatteryAlertLatched", false);
    public final Bool phoneLowBatteryAlertLatched2 = new Bool(this,
            "phoneLowBatteryAlertLatched2", false);
    /** Optional writable Sprut.hub boolean characteristic reflecting phone presence. */
    public final Bool phoneSprutPresenceEnabled = new Bool(this,
            "phoneSprutPresenceEnabled", false);
    public final Str phoneSprutPresencePath = new Str(this,
            "phoneSprutPresencePath", "");
    /** Optional independent Sprut.hub switch reflecting confirmed ANCS subscriptions. */
    public final Bool phoneSprutAncsPresenceEnabled = new Bool(this,
            "phoneSprutAncsPresenceEnabled", false);
    public final Str phoneSprutAncsPresencePath = new Str(this,
            "phoneSprutAncsPresencePath", "");

    // Direct Sprut.hub connector. The token is intentionally kept only in the live connector;
    // reconnect performs a fresh challenge using the Keystore-protected password.
    public final Bool sprutEnabled = new Bool(this, "sprutEnabled", false);
    public final Str sprutWebSocketUrl = new Str(this, "sprutWebSocketUrl",
            "ws://192.168.1.2/spruthub");
    public final Str sprutEmail = new Str(this, "sprutEmail", "");
    public final Secret sprutPassword = new Secret(this, "sprutPassword");
    /** Stable cloud client identity, equivalent to the official web app's persisted cid. */
    public final Str sprutClientId = new Str(this, "sprutClientId", "");
    /** Optional hub serial. Empty means select the only/first hub returned by hub.list. */
    public final Str sprutHubSerial = new Str(this, "sprutHubSerial", "");
    public final Bool sprutKeepAwake = new Bool(this, "sprutKeepAwake", true);

    // Direct Home Assistant API/WebSocket connector. Broadcast updates remain supported as a
    // compatibility ingress, but this connector can obtain an authoritative startup snapshot.
    public final Bool haApiEnabled = new Bool(this, "haApiEnabled", false);
    public final Str haBaseUrl = new Str(this, "haBaseUrl", "http://homeassistant.local:8123");
    public final Secret haAccessToken = new Secret(this, "haAccessToken");
    public final Bool haKeepAwake = new Bool(this, "haKeepAwake", true);

    @Nullable
    public TextBrickPrefs textBrickPrefs(BrickType type) {
        switch (type) {
            case TIME:
                return time;
            case DATE:
                return date;
            case MEDIA:
                return media;
            case INDOOR_TEMP:
                return indoorTemp;
            case OUTDOOR_TEMP:
                return outdoorTemp;
            case HOME_ASSISTANT:
                return homeAssistant;
            case PHONE_STATUS:
                return phoneStatus;
            case PHONE_NETWORK_TYPE:
                return phoneNetworkType;
            default:
                return null;
        }
    }

    @Nullable
    public IconBrickPrefs iconBrickPrefs(BrickType type) {
        switch (type) {
            case WIFI:
                return wifi;
            case GPS:
                return gps;
            case BLUETOOTH:
                return bluetooth;
            case PHONE_CELLULAR:
                return phoneCellular;
            case PHONE_BATTERY:
                return phoneBattery;
            default:
                return null;
        }
    }

    public StringSet hideListFor(BrickType type) {
        TextBrickPrefs t = textBrickPrefs(type);
        if (t != null) return t.hideInPackages;
        IconBrickPrefs i = iconBrickPrefs(type);
        if (i != null) return i.hideInPackages;
        throw new IllegalArgumentException("Unknown brick type: " + type);
    }

    public Int statusAlignmentFor(BrickType type) {
        TextBrickPrefs t = textBrickPrefs(type);
        if (t != null) return t.statusAlignment;
        IconBrickPrefs i = iconBrickPrefs(type);
        if (i != null) return i.statusAlignment;
        throw new IllegalArgumentException("Unknown brick type: " + type);
    }

    public Str hideSourceFor(BrickType type) {
        TextBrickPrefs t = textBrickPrefs(type);
        if (t != null) return t.hideSource;
        IconBrickPrefs i = iconBrickPrefs(type);
        if (i != null) return i.hideSource;
        throw new IllegalArgumentException("Unknown brick type: " + type);
    }

    /** Per-brick INVISIBLE-vs-GONE toggle for foreground-app hiding. */
    public Bool hideKeepsSpaceFor(BrickType type) {
        TextBrickPrefs t = textBrickPrefs(type);
        if (t != null) return t.hideKeepsSpace;
        IconBrickPrefs i = iconBrickPrefs(type);
        if (i != null) return i.hideKeepsSpace;
        throw new IllegalArgumentException("Unknown brick type: " + type);
    }

    public String hideListKeyFor(BrickType type) {
        TextBrickPrefs t = textBrickPrefs(type);
        if (t != null) return t.hideInPackagesKey();
        IconBrickPrefs i = iconBrickPrefs(type);
        if (i != null) return i.hideInPackagesKey();
        throw new IllegalArgumentException("Unknown brick type: " + type);
    }

    /** Allowlist for {@link AppSelectionActivity}'s internal preference-key deep link. */
    public boolean isHideListKey(@Nullable String key) {
        if (key == null || key.isEmpty()) return false;
        if (hideInPackages.key.equals(key)) return true;
        for (BrickType type : BrickType.values()) {
            TextBrickPrefs text = textBrickPrefs(type);
            if (text != null && text.hideInPackages.key.equals(key)) return true;
            IconBrickPrefs icon = iconBrickPrefs(type);
            if (icon != null && icon.hideInPackages.key.equals(key)) return true;
        }
        return false;
    }

    /**
     * Returns the brick whose hide-in-apps list this brick uses. {@code type} itself if the
     * brick has its own list; another type if it inherits.
     */
    public BrickType effectiveHideSourceFor(BrickType type) {
        String src = hideSourceFor(type).get();
        BrickType resolved = BrickType.fromName(src);
        if (resolved != null && resolved != type) {
            return resolved;
        }
        return type;
    }

    public Preferences(Context context) {
        this(context, true);
    }

    /** Visual-only service bootstrap may defer migrations to its background runtime barrier. */
    Preferences(Context context, boolean runStartupMigrations) {
        appContext = context.getApplicationContext();
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_preferences",
                AppProcessPolicy.preferenceMode());
        if (runStartupMigrations) completeDeferredStartupMigrations();
    }

    synchronized void completeDeferredStartupMigrations() {
        if (startupMigrationsComplete) return;
        migrateLegacyPrefsIfNeeded();
        migrateUnifiedDriverPanelIfNeeded();
        migrateUnifiedLauncherSettingsIfNeeded();
        migratePhoneNotificationDeferralIfNeeded();
        migrateNavigatorWindowSurfaceIfNeeded();
        startupMigrationsComplete = true;
    }

    /**
     * Before the floating surface had its own target, a selected Yandex package hid both its
     * full-screen Activity and the ECARX window. Add the new id once to those existing status-row
     * lists so an upgrade keeps the user's pixels unchanged; afterwards both checkboxes can be
     * edited independently.
     */
    private void migrateNavigatorWindowSurfaceIfNeeded() {
        final String marker = "navigatorWindowSurfaceHa1219";
        if (prefs.getBoolean(marker, false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!isHideListKey(entry.getKey()) || !(entry.getValue() instanceof Set<?>)) continue;
            Set<String> targets = new HashSet<>();
            for (Object raw : (Set<?>) entry.getValue()) {
                if (raw instanceof String) targets.add((String) raw);
            }
            if (containsYandexPackage(targets)
                    && targets.add(StatusBarSurfaceContext.NAVIGATOR_WINDOW)) {
                editor.putStringSet(entry.getKey(), targets);
            }
        }

        try {
            JSONArray configs = new JSONArray(haMainBricksJson.get());
            boolean changed = false;
            for (int index = 0; index < configs.length(); index++) {
                JSONObject config = configs.optJSONObject(index);
                if (config == null) continue;
                JSONArray hidden = config.optJSONArray("hideInPackages");
                if (hidden == null) continue;
                boolean yandex = false;
                boolean hasWindow = false;
                for (int item = 0; item < hidden.length(); item++) {
                    String target = hidden.optString(item, "").trim();
                    yandex |= StatusBarSurfaceContext.isYandexPackage(target);
                    hasWindow |= StatusBarSurfaceContext.NAVIGATOR_WINDOW.equals(target);
                }
                if (yandex && !hasWindow) {
                    hidden.put(StatusBarSurfaceContext.NAVIGATOR_WINDOW);
                    changed = true;
                }
            }
            if (changed) editor.putString(haMainBricksJson.key, configs.toString());
        } catch (JSONException invalidImportedJson) {
            Log.w(TAG, "Could not migrate Navigator-window hide targets", invalidImportedJson);
        }
        editor.putBoolean(marker, true).apply();
    }

    private static boolean containsYandexPackage(@NonNull Set<String> targets) {
        for (String target : targets) {
            if (StatusBarSurfaceContext.isYandexPackage(target)) return true;
        }
        return false;
    }

    /** Normalizes experimental/imported HA1217 values without changing an existing selection. */
    private void migratePhoneNotificationDeferralIfNeeded() {
        if (prefs.getBoolean("phoneNotificationDeferralHa1217", false)) return;
        Set<String> migrated = new HashSet<>();
        Object rawPackages = prefs.getAll().get("phoneNotificationDelayInPackages");
        if (rawPackages instanceof Set<?>) {
            for (Object raw : (Set<?>) rawPackages) {
                if (!(raw instanceof String)) continue;
                String packageName = ((String) raw).trim();
                if (packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) {
                    migrated.add(packageName);
                }
            }
        }
        Object rawWait = prefs.getAll().get("phoneNotificationDelayMaxWaitSeconds");
        int requestedWait = PhoneNotificationDeferralPolicy.DEFAULT_MAX_WAIT_SECONDS;
        if (rawWait instanceof Number) {
            requestedWait = ((Number) rawWait).intValue();
        } else if (rawWait instanceof String) {
            try { requestedWait = Integer.parseInt(((String) rawWait).trim()); }
            catch (NumberFormatException ignored) { }
        }
        int seconds = PhoneNotificationDeferralPolicy.boundedMaxWaitSeconds(
                requestedWait);
        boolean enabled = false;
        Object rawEnabled = prefs.getAll().get("phoneNotificationDelayInAppsEnabled");
        if (rawEnabled instanceof Boolean) enabled = (Boolean) rawEnabled;
        prefs.edit()
                .putStringSet("phoneNotificationDelayInPackages", migrated)
                .putInt("phoneNotificationDelayMaxWaitSeconds", seconds)
                .putBoolean("phoneNotificationDelayInAppsEnabled",
                        enabled && !migrated.isEmpty())
                .putBoolean("phoneNotificationDeferralHa1217", true)
                .apply();
    }

    /**
     * Tiny device-protected admission probe for the boot receiver/first HOME frame.
     *
     * <p>Constructing {@link Preferences} also runs idempotent migrations. The visual-only start
     * path needs just this one bit and lets {@link WidgetService} own those migrations after it has
     * already satisfied Android's foreground-service deadline.</p>
     */
    static boolean isStatusWidgetEnabledForVisualBootstrap(@NonNull Context context) {
        Context app = context.getApplicationContext();
        Context device = app.createDeviceProtectedStorageContext();
        return device.getSharedPreferences(context.getPackageName() + "_preferences",
                AppProcessPolicy.preferenceMode()).getBoolean("enabled", false);
    }

    @NonNull
    public DriverPanelStyle activeDriverPanelStyle() {
        return DriverPanelStyle.NEW;
    }

    @NonNull
    public DriverPanelProfile activeDriverPanelProfile() {
        return driverPanelNew;
    }

    /** HA1085 removes the obsolete old/new selector while preserving the profile in active use. */
    private void migrateUnifiedDriverPanelIfNeeded() {
        if (prefs.getBoolean("driverPanelUnifiedHa1085", false)) return;
        DriverPanelStyle selected = DriverPanelStyle.fromKey(
                prefs.getString(driverPanelStyle.key, DriverPanelStyle.OLD.key));
        SharedPreferences.Editor editor = prefs.edit();
        if (selected == DriverPanelStyle.OLD) {
            editor.putInt(driverPanelNew.side.key, driverPanelOld.side.get());
            editor.putInt(driverPanelNew.widthPx.key,
                    Math.max(DriverPanelLayoutPolicyCompat.NEW_MIN_WIDTH,
                            driverPanelOld.widthPx.get()));
            editor.putInt(driverPanelNew.topPaddingPx.key,
                    driverPanelOld.topPaddingPx.get());
            editor.putInt(driverPanelNew.bottomPaddingPx.key,
                    driverPanelOld.bottomPaddingPx.get());
            editor.putInt(driverPanelNew.itemGapPx.key, driverPanelOld.itemGapPx.get());
            editor.putInt(driverPanelNew.cornerRadiusPx.key,
                    driverPanelOld.cornerRadiusPx.get());
            editor.putString(driverPanelNew.backgroundColor.key,
                    driverPanelOld.backgroundColor.get());
            editor.putString(driverPanelNew.borderColor.key,
                    driverPanelOld.borderColor.get());
            editor.putInt(driverPanelNew.borderWidthPx.key,
                    driverPanelOld.borderWidthPx.get());
            editor.putString(driverPanelNew.shortcutsJson.key,
                    driverPanelOld.shortcutsJson.get());
        }
        editor.putString(driverPanelStyle.key, DriverPanelStyle.NEW.key)
                .putBoolean("driverPanelUnifiedHa1085", true)
                .commit();
    }

    /**
     * HA1132 flattens only the settings/navigation model. Existing storage keys intentionally stay
     * unchanged so geometry, style, actions, fonts, colours and media behavior survive exactly.
     * Before marking the migration complete, store a recoverable snapshot of every affected key.
     */
    private void migrateUnifiedLauncherSettingsIfNeeded() {
        int current = prefs.getInt(launcherUnifiedSettingsMigrationVersion.key, 0);
        if (current >= LauncherSettingsMigrationRegistry.SCHEMA_VERSION) return;
        try {
            JSONObject root = new JSONObject();
            root.put("version", LauncherSettingsMigrationRegistry.SCHEMA_VERSION);
            root.put("capturedAtMillis", System.currentTimeMillis());
            JSONObject values = new JSONObject();
            Map<String, ?> stored = prefs.getAll();
            for (String key : LauncherSettingsMigrationRegistry.storageKeys()) {
                if (!stored.containsKey(key)) continue;
                Object value = stored.get(key);
                if (value instanceof Set) {
                    JSONArray array = new JSONArray();
                    for (Object item : (Set<?>) value) array.put(String.valueOf(item));
                    values.put(key, array);
                } else if (value != null) {
                    values.put(key, value);
                }
            }
            root.put("values", values);

            SharedPreferences.Editor editor = prefs.edit();
            if (launcherUnifiedLegacyBackupJson.get().trim().isEmpty()) {
                editor.putString(launcherUnifiedLegacyBackupJson.key, root.toString());
            }
            // The old build shared one climate document between HOME and the floating panel.
            // Copy it once, then both surfaces evolve independently.
            if (!prefs.contains(floatingClimateConfigJson.key)) {
                editor.putString(floatingClimateConfigJson.key,
                        launcherClimateConfigJson.get());
            }
            editor.putInt(launcherUnifiedSettingsMigrationVersion.key,
                    LauncherSettingsMigrationRegistry.SCHEMA_VERSION);
            editor.commit();
        } catch (JSONException error) {
            Log.w(TAG, "Cannot snapshot legacy launcher settings", error);
        }
    }

    /** Avoids a common-to-driver package dependency for the fixed new-panel minimum. */
    private static final class DriverPanelLayoutPolicyCompat {
        static final int NEW_MIN_WIDTH = 150;
    }

    /** HA1084's single document remains the default panel; later panels get isolated documents. */
    @NonNull
    public Str driverFavoritesShortcuts(@Nullable String panelId) {
        String id = panelId == null ? "" : panelId.trim();
        if (id.isEmpty() || "favorites_default".equals(id)) {
            return driverFavoritesShortcutsJson;
        }
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid Favorites panel id");
        }
        return new Str(this, "driverFavoritesShortcutsJson." + id, "");
    }

    /**
     * Wipes all stored preferences. Defaults take over on next read.
     * Uses {@link android.content.SharedPreferences.Editor#commit()} (synchronous) instead of
     * {@code apply()} because the caller typically kills the process immediately afterwards and
     * an async write may not reach disk in time.
     */
    public void resetAll() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (!DEVICE_LOCAL_PREFERENCE_KEYS.contains(key)) editor.remove(key);
        }
        editor.commit();
    }

    public boolean saveVerifiedSystemStatusBarContentState(
            boolean enabled, @NonNull Set<String> ownedSlots) {
        return prefs.edit()
                .putBoolean(systemUiHideStockContentGlobally.key, enabled)
                .putStringSet(systemUiOwnedHiddenSlots.key, new HashSet<>(ownedSlots))
                .commit();
    }

    /** Commits the complete rollback journal before any shared SystemUI setting is written. */
    public boolean beginPendingSystemStatusBarContentState(
            boolean enabled, @Nullable String rollbackRaw, @Nullable String desiredRaw,
            @NonNull Set<String> ownedSlots) {
        return putPendingFields(prefs.edit(), enabled, rollbackRaw, desiredRaw, ownedSlots)
                .putBoolean(SYSTEM_UI_PENDING_ACTIVE, true).commit();
    }

    public boolean restorePendingSystemStatusBarContentState(
            boolean enabled, @NonNull Set<String> ownedSlots,
            @NonNull PendingSystemStatusBarContentState pending) {
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(systemUiHideStockContentGlobally.key, enabled)
                .putStringSet(systemUiOwnedHiddenSlots.key, new HashSet<>(ownedSlots));
        return putPendingFields(editor, pending.enabled, pending.rollbackRaw,
                pending.desiredRaw, pending.ownedSlots)
                .putBoolean(SYSTEM_UI_PENDING_ACTIVE, true).commit();
    }

    public boolean completePendingSystemStatusBarContentState(
            boolean enabled, @NonNull Set<String> ownedSlots) {
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(systemUiHideStockContentGlobally.key, enabled)
                .putStringSet(systemUiOwnedHiddenSlots.key, new HashSet<>(ownedSlots));
        return clearPendingFields(editor).commit();
    }

    public boolean clearPendingSystemStatusBarContentState() {
        return clearPendingFields(prefs.edit()).commit();
    }

    @Nullable
    public PendingSystemStatusBarContentState pendingSystemStatusBarContentState() {
        if (!prefs.getBoolean(SYSTEM_UI_PENDING_ACTIVE, false)) return null;
        boolean rollbackNull = prefs.getBoolean(SYSTEM_UI_PENDING_ROLLBACK_NULL, false);
        boolean desiredNull = prefs.getBoolean(SYSTEM_UI_PENDING_DESIRED_NULL, false);
        return new PendingSystemStatusBarContentState(
                prefs.getBoolean(SYSTEM_UI_PENDING_ENABLED, false),
                rollbackNull ? null : prefs.getString(SYSTEM_UI_PENDING_ROLLBACK_RAW,
                        " invalid-pending-state"),
                desiredNull ? null : prefs.getString(SYSTEM_UI_PENDING_DESIRED_RAW,
                        " invalid-pending-state"),
                prefs.getStringSet(SYSTEM_UI_PENDING_OWNED_SLOTS, Collections.emptySet()));
    }

    @NonNull
    private static SharedPreferences.Editor putPendingFields(
            @NonNull SharedPreferences.Editor editor, boolean enabled,
            @Nullable String rollbackRaw, @Nullable String desiredRaw,
            @NonNull Set<String> ownedSlots) {
        editor.putBoolean(SYSTEM_UI_PENDING_ENABLED, enabled)
                .putBoolean(SYSTEM_UI_PENDING_ROLLBACK_NULL, rollbackRaw == null)
                .putBoolean(SYSTEM_UI_PENDING_DESIRED_NULL, desiredRaw == null)
                .putStringSet(SYSTEM_UI_PENDING_OWNED_SLOTS, new HashSet<>(ownedSlots));
        if (rollbackRaw == null) editor.remove(SYSTEM_UI_PENDING_ROLLBACK_RAW);
        else editor.putString(SYSTEM_UI_PENDING_ROLLBACK_RAW, rollbackRaw);
        if (desiredRaw == null) editor.remove(SYSTEM_UI_PENDING_DESIRED_RAW);
        else editor.putString(SYSTEM_UI_PENDING_DESIRED_RAW, desiredRaw);
        return editor;
    }

    @NonNull
    private static SharedPreferences.Editor clearPendingFields(
            @NonNull SharedPreferences.Editor editor) {
        return editor.remove(SYSTEM_UI_PENDING_ACTIVE)
                .remove(SYSTEM_UI_PENDING_ENABLED)
                .remove(SYSTEM_UI_PENDING_ROLLBACK_NULL)
                .remove(SYSTEM_UI_PENDING_ROLLBACK_RAW)
                .remove(SYSTEM_UI_PENDING_DESIRED_NULL)
                .remove(SYSTEM_UI_PENDING_DESIRED_RAW)
                .remove(SYSTEM_UI_PENDING_OWNED_SLOTS);
    }

    /** Popup drag can be followed immediately by ignition power-off; persist both coordinates
     * atomically and synchronously so a half-updated or lost position cannot occur. */
    public void savePopupPosition(int x, int y) {
        prefs.edit().putInt(popupX.key, x).putInt(popupY.key, y).commit();
    }

    /** Multi-overlay geometry may be followed immediately by ignition power-off. */
    public void savePopupOverlaysJson(@NonNull String json) {
        prefs.edit().putString(popupOverlaysJson.key, json).commit();
    }

    /**
     * Removes every stored pref whose storage key starts with the given brick's prefix. After
     * removal subsequent reads return the brick's defaults. The brick stays in {@link #brickOrder}
     * — only its own settings (font, outline, margins, hide list, alignment, type-specific flags)
     * are reset.
     */
    public void resetBrick(BrickType type) {
        String prefix = brickPrefix(type);
        if (prefix == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    @Nullable
    private static String brickPrefix(BrickType type) {
        switch (type) {
            case TIME: return "time";
            case DATE: return "date";
            case MEDIA: return "media";
            case WIFI: return "wifi";
            case GPS: return "gps";
            case BLUETOOTH: return "bluetooth";
            case INDOOR_TEMP: return "indoorTemp";
            case OUTDOOR_TEMP: return "outdoorTemp";
            case HOME_ASSISTANT: return "homeAssistant";
            case PHONE_STATUS: return "phoneStatus";
            case PHONE_CELLULAR: return "phoneCellular";
            case PHONE_BATTERY: return "phoneBattery";
            case PHONE_NETWORK_TYPE: return "phoneNetworkType";
            default: return null;
        }
    }

    /**
     * One-shot migration from the pre-brick layout. Idempotent: re-running after the migration is
     * a no-op (detected by the presence of the {@code brickOrder} key). Also re-run after every
     * settings import in case the imported file used the legacy schema.
     */
    private void migrateLegacyPrefsIfNeeded() {
        if (prefs.contains("brickOrder")) return;
        if (!prefs.contains("showWifiIcon") && !prefs.contains("showGnssIcon")
                && !prefs.contains("showTime") && !prefs.contains("showDate")
                && !prefs.contains("showMedia")) {
            // Fresh install — keep the default brickOrder; nothing to migrate.
            return;
        }

        SharedPreferences.Editor e = prefs.edit();

        StringBuilder order = new StringBuilder();
        if (prefs.getBoolean("showTime", false)) appendOrder(order, BrickType.TIME);
        if (prefs.getBoolean("showDate", false) || prefs.getBoolean("showDayOfTheWeek", false)) {
            appendOrder(order, BrickType.DATE);
        }
        if (prefs.getBoolean("showMedia", false)) appendOrder(order, BrickType.MEDIA);
        if (prefs.getBoolean("showWifiIcon", true)) appendOrder(order, BrickType.WIFI);
        if (prefs.getBoolean("showGnssIcon", true)) appendOrder(order, BrickType.GPS);
        e.putString("brickOrder", order.toString());

        e.putBoolean("mediaEnabled", prefs.getBoolean("showMedia", false));

        // Carry over the date sub-toggles into the new namespace.
        e.putBoolean("dateShowDate", prefs.getBoolean("showDate", true));
        e.putBoolean("dateShowDayOfWeek", prefs.getBoolean("showDayOfTheWeek", true));
        e.putBoolean("dateShowFullName", prefs.getBoolean("showFullDayAndMonth", false));
        e.putBoolean("dateOneLineLayout", prefs.getBoolean("oneLineLayout", false));
        // dateBeforeDayOfWeek and dateAlignment kept their original keys.

        // Text outline → per-text-brick.
        int textAlpha = prefs.getInt("textOutlineAlpha", 0xAA);
        int textWidth = prefs.getInt("textOutlineWidth", 2);
        e.putInt("timeOutlineAlpha", textAlpha);
        e.putInt("timeOutlineWidth", textWidth);
        e.putInt("dateOutlineAlpha", textAlpha);
        e.putInt("dateOutlineWidth", textWidth);
        e.putInt("mediaOutlineAlpha", textAlpha);
        e.putInt("mediaOutlineWidth", textWidth);

        // Icon outline + size → per-icon-brick.
        int iconAlpha = prefs.getInt("iconOutlineAlpha", 0xAA);
        int iconWidth = prefs.getInt("iconOutlineWidth", 2);
        int iconSize = prefs.getInt("iconSize", 70);
        e.putInt("wifiOutlineAlpha", iconAlpha);
        e.putInt("wifiOutlineWidth", iconWidth);
        e.putInt("wifiSize", iconSize);
        e.putInt("gpsOutlineAlpha", iconAlpha);
        e.putInt("gpsOutlineWidth", iconWidth);
        e.putInt("gpsSize", iconSize);

        // Per-text adjust Y kept original keys: timeAdjustY, dateAdjustY → migrate from legacy.
        e.putInt("timeAdjustY", prefs.getInt("adjustTimeY", 0));
        e.putInt("dateAdjustY", prefs.getInt("adjustDateY", 0));

        // Legacy spacings: spacingLeftOfMedia → media.marginStart; spacingLeftOfIcons → wifi.marginStart.
        e.putInt("mediaMarginStart", prefs.getInt("spacingBetweenTextsAndIcons", 0));
        e.putInt("wifiMarginStart", prefs.getInt("spacingBetweenMediaAndIcons", 0));

        // Carry the satellite badge toggle.
        e.putBoolean("gpsShowSatelliteBadge", prefs.getBoolean("showGnssSatelliteBadge", true));

        e.apply();
    }

    private static void appendOrder(StringBuilder sb, BrickType type) {
        if (sb.length() > 0) sb.append(',');
        sb.append(type.name());
    }

    private static final String EXPORT_FILE_TYPE = "dezz.status.widget.settings";
    private static final int EXPORT_FILE_VERSION = 1;
    private static final String KEY_FILE_TYPE = "fileType";
    private static final String KEY_FILE_VERSION = "fileVersion";
    private static final String KEY_PRESET_NAME = "presetName";
    private static final String KEY_PREFERENCES = "preferences";

    /**
     * Extracts the optional {@code presetName} field from a preset/settings JSON. Returns
     * {@code null} if the field is absent or the JSON is malformed.
     */
    @Nullable
    public static String readPresetName(@NonNull String json) {
        try {
            JSONObject root = new JSONObject(json);
            String name = root.optString(KEY_PRESET_NAME, "").trim();
            return name.isEmpty() ? null : name;
        } catch (JSONException e) {
            return null;
        }
    }

    public static class InvalidSettingsFileException extends Exception {
        public InvalidSettingsFileException(String message) {
            super(message);
        }
    }

    public String exportToJson() throws JSONException {
        return exportToJson(null);
    }

    /**
     * Same as {@link #exportToJson()} but writes the optional {@code presetName} field. Used when
     * saving the current state as a named user preset.
     */
    public String exportToJson(@Nullable String presetName) throws JSONException {
        JSONObject preferencesNode = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            // Credentials are device-local and never leave the app in an export or preset.
            if (SECRET_PREFERENCE_KEYS.contains(entry.getKey())
                    || DEVICE_LOCAL_PREFERENCE_KEYS.contains(entry.getKey())) continue;
            Object value = entry.getValue();
            if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (Object item : (Set<?>) value) {
                    array.put(String.valueOf(item));
                }
                preferencesNode.put(entry.getKey(), array);
            } else {
                preferencesNode.put(entry.getKey(), value);
            }
        }
        JSONObject root = new JSONObject();
        root.put(KEY_FILE_TYPE, EXPORT_FILE_TYPE);
        root.put(KEY_FILE_VERSION, EXPORT_FILE_VERSION);
        if (presetName != null && !presetName.trim().isEmpty()) {
            root.put(KEY_PRESET_NAME, presetName.trim());
        }
        root.put(KEY_PREFERENCES, preferencesNode);
        return root.toString(2);
    }

    public void importFromJson(String json) throws JSONException, InvalidSettingsFileException {
        applyJson(json, true);
    }

    /**
     * Applies only keys explicitly present in a bundled appearance preset.
     *
     * <p>Bundled presets intentionally describe a small status-row theme rather than a complete
     * backup.  Treating one as a normal import used to clear HOME panels, connector configuration,
     * climate reservation and automation rules.  Full user presets and backups still use
     * {@link #importFromJson(String)} and retain replace-all semantics.</p>
     */
    public void applyPatchFromJson(String json)
            throws JSONException, InvalidSettingsFileException {
        applyJson(json, false);
    }

    private void applyJson(String json, boolean clearExisting)
            throws JSONException, InvalidSettingsFileException {
        JSONObject root = new JSONObject(json);
        if (!EXPORT_FILE_TYPE.equals(root.optString(KEY_FILE_TYPE, null))) {
            throw new InvalidSettingsFileException("Not a Natro settings file");
        }
        int version = root.optInt(KEY_FILE_VERSION, -1);
        if (version <= 0 || version > EXPORT_FILE_VERSION) {
            throw new InvalidSettingsFileException("Unsupported settings file version: " + version);
        }
        JSONObject preferencesNode = root.optJSONObject(KEY_PREFERENCES);
        if (preferencesNode == null) {
            throw new InvalidSettingsFileException("Missing preferences section");
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (clearExisting) {
            for (String existing : prefs.getAll().keySet()) {
                if (!SECRET_PREFERENCE_KEYS.contains(existing)
                        && !DEVICE_LOCAL_PREFERENCE_KEYS.contains(existing)) {
                    editor.remove(existing);
                }
            }
        }
        Iterator<String> keys = preferencesNode.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (SECRET_PREFERENCE_KEYS.contains(key)
                    || DEVICE_LOCAL_PREFERENCE_KEYS.contains(key)) continue;
            Object value = preferencesNode.get(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                long longValue = (Long) value;
                if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                    editor.putInt(key, (int) longValue);
                } else {
                    editor.putLong(key, longValue);
                }
            } else if (value instanceof Double || value instanceof Float) {
                editor.putFloat(key, ((Number) value).floatValue());
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                Set<String> set = new HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    set.add(array.getString(i));
                }
                editor.putStringSet(key, set);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            }
        }
        editor.commit();
        // The file may be from the legacy (pre-brick) schema — re-run migration so it adapts.
        migrateLegacyPrefsIfNeeded();
        // A full backup made by HA1084 may still select the legacy driver profile and does not
        // contain HA1085's migration marker. Preserve that active profile after import too.
        migrateUnifiedDriverPanelIfNeeded();
        migrateUnifiedLauncherSettingsIfNeeded();
        migrateNavigatorWindowSurfaceIfNeeded();
    }
}
