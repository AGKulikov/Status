/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

/**
 * Versioned IPC vocabulary shared by Natro and the small Navigator patch.
 *
 * <p>Navigator initiates an explicit bind to the Natro-owned endpoint. Natro validates the
 * Binder/Messenger sending UID, exact package and signing certificate on every inbound message.
 * Navigator therefore needs no exported component and no manifest edit. The contract transports
 * navigation state and a producer-owned render surface; it never transports screenshots or
 * per-frame bitmaps.</p>
 */
public final class NavigationBridgeContract {
    public static final int PROTOCOL_VERSION = 2;

    public static final String NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi";
    public static final String NATRO_PACKAGE = "ru.natro.statuswidget";
    public static final String NATRO_ENDPOINT_SERVICE_CLASS =
            "dezz.status.widget.navigation.NavigationHudEndpointService";
    public static final String NATRO_BIND_ACTION = "ru.natro.navigation.bridge.BIND_V2";
    public static final String CONFIGURATION_PROVIDER_AUTHORITY =
            "ru.natro.statuswidget.navigation.configuration";
    public static final String CONFIGURATION_PROVIDER_METHOD =
            "get_navigation_configuration_v2";

    /** Existing Natro shortcuts already send this vendor-compatible window contract. */
    public static final String LEGACY_FLOATING_ACTION =
            "navi_win/" + NAVIGATOR_PACKAGE;
    public static final String EXTRA_WINDOWED = "ddnavwin";
    public static final String EXTRA_FORCE_FULLSCREEN = "ddnavforcewinfull";

    public static final int MSG_HELLO = 1;
    public static final int MSG_CAPABILITIES = 2;
    public static final int MSG_APPLY_CONFIGURATION = 3;
    public static final int MSG_ATTACH_HUD_SURFACE = 4;
    public static final int MSG_DETACH_HUD_SURFACE = 5;
    public static final int MSG_REQUEST_SNAPSHOT = 6;
    public static final int MSG_NAVIGATION_SNAPSHOT = 7;
    public static final int MSG_REQUEST_ROUTE_GEOMETRY = 8;
    public static final int MSG_ROUTE_GEOMETRY = 9;
    public static final int MSG_ERROR = 10;
    public static final int MSG_SET_MAIN_WINDOW_MODE = 11;
    public static final int MSG_HUD_SURFACE_LOST = 12;
    public static final int MSG_HEARTBEAT = 13;
    /** Human-readable runtime milestones forwarded into Natro's exportable journal. */
    public static final int MSG_DIAGNOSTIC = 14;
    public static final int MSG_ATTACH_CLUSTER_SURFACE = 15;
    public static final int MSG_DETACH_CLUSTER_SURFACE = 16;
    public static final int MSG_CLUSTER_SURFACE_LOST = 17;

    public static final long CAP_NAVIGATION_SNAPSHOT = 1L;
    public static final long CAP_ROUTE_GEOMETRY = 1L << 1;
    public static final long CAP_LANES = 1L << 2;
    public static final long CAP_TRAFFIC = 1L << 3;
    public static final long CAP_MAIN_MAP_CONFIGURATION = 1L << 4;
    public static final long CAP_MAIN_FLOATING_WINDOW = 1L << 5;
    /** Navigator owns a second MapWindow backed by the leased HUD Surface. */
    public static final long CAP_HUD_INDEPENDENT_MAP_WINDOW = 1L << 6;
    public static final long CAP_HUD_DIRECT_SURFACE = 1L << 7;
    public static final long CAP_NAVIGATOR_WINDOW_BUTTON = 1L << 8;
    public static final long CAP_LEGACY_WINDOW_INTENTS = 1L << 9;
    /** Navigator owns a third, native-size MapWindow for Natro's instrument panel. */
    public static final long CAP_CLUSTER_INDEPENDENT_MAP_WINDOW = 1L << 10;
    public static final long CAP_CLUSTER_DIRECT_SURFACE = 1L << 11;
    public static final long CAP_NATRO_CONFIGURATION_HOST = 1L << 32;
    public static final long CAP_NATRO_NAVIGATION_STATE_SINK = 1L << 33;
    /** Advertise only after Natro can lease a real Surface rather than a bitmap bridge. */
    public static final long CAP_NATRO_HUD_SURFACE_PROVIDER = 1L << 34;
    public static final long CAP_NATRO_WINDOW_COMMAND_SOURCE = 1L << 35;
    public static final long CAP_NATRO_CLUSTER_SURFACE_PROVIDER = 1L << 36;

    public static final int WINDOW_MODE_FULLSCREEN = 0;
    public static final int WINDOW_MODE_FLOATING = 1;
    public static final int WINDOW_MODE_TOGGLE = 2;

    public static final String KEY_PROTOCOL_VERSION = "protocol_version";
    public static final String KEY_SESSION_ID = "session_id";
    public static final String KEY_NONCE = "nonce";
    public static final String KEY_CLIENT_PACKAGE = "client_package";
    public static final String KEY_CLIENT_CERT_SHA256 = "client_cert_sha256";
    public static final String KEY_CAPABILITIES = "capabilities";
    public static final String KEY_CONFIGURATION_JSON = "configuration_json";
    public static final String KEY_SNAPSHOT_JSON = "snapshot_json";
    public static final String KEY_ROUTE_GEOMETRY_JSON = "route_geometry_json";
    public static final String KEY_SURFACE = "surface";
    public static final String KEY_SURFACE_WIDTH = "surface_width";
    public static final String KEY_SURFACE_HEIGHT = "surface_height";
    public static final String KEY_SURFACE_DPI = "surface_dpi";
    public static final String KEY_SURFACE_GENERATION = "surface_generation";
    public static final String KEY_WINDOW_MODE = "window_mode";
    public static final String KEY_WINDOW_COMMAND_SOURCE = "window_command_source";
    public static final String KEY_ERROR_CODE = "error_code";
    public static final String KEY_ERROR_DETAIL = "error_detail";

    private NavigationBridgeContract() {}
}
