/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

/**
 * Versioned IPC vocabulary shared by Natro and the small Navigator patch.
 *
 * <p>The contract deliberately transports navigation state and a producer-owned render surface;
 * it never transports screenshots or per-frame bitmaps. The Navigator side must verify the caller
 * package and signing-certificate digest before accepting a surface or configuration.</p>
 */
public final class NavigationBridgeContract {
    public static final int PROTOCOL_VERSION = 2;

    public static final String NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi";
    public static final String SERVICE_CLASS =
            "ru.monjaro.natro.navigation.NavigationBridgeService";
    public static final String BIND_ACTION = "ru.natro.navigation.bridge.BIND_V2";

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

    public static final long CAP_NAVIGATION_SNAPSHOT = 1L;
    public static final long CAP_ROUTE_GEOMETRY = 1L << 1;
    public static final long CAP_LANES = 1L << 2;
    public static final long CAP_TRAFFIC = 1L << 3;
    public static final long CAP_MAIN_MAP_CONFIGURATION = 1L << 4;
    public static final long CAP_MAIN_FLOATING_WINDOW = 1L << 5;
    public static final long CAP_HUD_OFFSCREEN_MAP = 1L << 6;
    public static final long CAP_HUD_DIRECT_SURFACE = 1L << 7;

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
    public static final String KEY_ERROR_CODE = "error_code";
    public static final String KEY_ERROR_DETAIL = "error_detail";

    private NavigationBridgeContract() {}
}
