/* SPDX-License-Identifier: GPL-3.0-or-later */
package air.StrelkaSD.bridge;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal read-only bridge injected into HUD Speed.
 *
 * <p>It exposes only a bounded live list already held by HUD Speed's radar engine. It neither
 * reads nor exports the on-disk RadarBase database, network credentials, license state or user
 * settings. Every request is authenticated by Binder UID, exact Natro package and release
 * certificate.</p>
 */
public final class HudSpeedCameraBridgeService extends Service {
    private static final String BIND_ACTION = "ru.natro.hudspeed.camera.BIND_V1";
    private static final String NATRO_PACKAGE = "ru.natro.statuswidget";
    private static final String NATRO_CERT_SHA256 =
            "6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75";
    private static final int MSG_REQUEST_CAMERAS = 1;
    private static final int MSG_CAMERA_FRAME = 2;
    private static final int PROTOCOL_VERSION = 1;
    private static final String KEY_PROTOCOL_VERSION = "protocol_version";
    private static final String KEY_CLIENT_PACKAGE = "client_package";
    private static final String KEY_CAMERAS_JSON = "cameras_json";
    private static final int MAX_CAMERAS = 64;

    private final Messenger endpoint = new Messenger(
            new Handler(Looper.getMainLooper(), this::onMessage));

    @Override public IBinder onBind(Intent intent) {
        if (intent == null || !BIND_ACTION.equals(intent.getAction())) return null;
        return endpoint.getBinder();
    }

    private boolean onMessage(Message message) {
        if (message.what != MSG_REQUEST_CAMERAS || message.replyTo == null) return true;
        Bundle request = message.getData();
        if (request.getInt(KEY_PROTOCOL_VERSION, -1) != PROTOCOL_VERSION
                || !NATRO_PACKAGE.equals(request.getString(KEY_CLIENT_PACKAGE, ""))
                || !isTrustedNatro(message.sendingUid)) return true;
        try {
            Bundle data = new Bundle();
            data.putString(KEY_CAMERAS_JSON, buildCameraFrame());
            Message response = Message.obtain(null, MSG_CAMERA_FRAME);
            response.setData(data);
            message.replyTo.send(response);
        } catch (RemoteException ignored) {
            // The client disappeared between its request and this same-main-loop response.
        }
        return true;
    }

    private String buildCameraFrame() {
        JSONArray cameras = new JSONArray();
        try {
            Class<?> engineClass = Class.forName("h.b");
            Object engine = engineClass.getField("Q").get(null);
            if (engine != null) {
                byte vehicleMode = readVehicleMode(engineClass, engine);
                for (Object camera : liveCameraSnapshot(engineClass, engine)) {
                    if (camera == null || cameras.length() >= MAX_CAMERAS) break;
                    JSONObject item = cameraJson(camera, vehicleMode);
                    if (item != null) cameras.put(item);
                }
            }
        } catch (Throwable unavailable) {
            // HUD Speed can legitimately be between database/runtime initialisation phases.
            cameras = new JSONArray();
        }
        try {
            return new JSONObject()
                    .put("schema", 1)
                    .put("sampleElapsedMs", SystemClock.elapsedRealtime())
                    .put("cameras", cameras)
                    .toString();
        } catch (Exception impossible) {
            return "{\"schema\":1,\"sampleElapsedMs\":0,\"cameras\":[]}";
        }
    }

    private static List<?> liveCameraSnapshot(Class<?> engineClass, Object engine)
            throws Exception {
        // n is HUD Speed's nearby (roughly 1.6 km) in-memory list. It is already bounded by the
        // radar engine and does not require opening or copying the app's database.
        Field nearbyField = engineClass.getField("n");
        Object value = nearbyField.get(engine);
        if (!(value instanceof List)) return new ArrayList<Object>();
        try {
            return new ArrayList<Object>((List<?>) value);
        } catch (RuntimeException changingNow) {
            // One retry on the same main looper is enough for an ArrayList being refreshed.
            return new ArrayList<Object>((List<?>) nearbyField.get(engine));
        }
    }

    private static byte readVehicleMode(Class<?> engineClass, Object engine) {
        try {
            Object settings = engineClass.getField("b").get(engine);
            Object value = settings.getClass().getMethod("U").invoke(settings);
            return value instanceof Number ? ((Number) value).byteValue() : (byte) 1;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private JSONObject cameraJson(Object camera, byte vehicleMode) {
        try {
            Class<?> type = camera.getClass();
            int typeId = number(type.getMethod("v").invoke(camera), -1);
            if (!isCameraType(typeId)) return null;
            double latitude = ((Number) type.getMethod("k").invoke(camera)).doubleValue();
            double longitude = ((Number) type.getMethod("l").invoke(camera)).doubleValue();
            if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d
                    || !Double.isFinite(longitude) || longitude < -180d
                    || longitude > 180d) return null;
            int numericId = number(type.getMethod("j").invoke(camera), 0);
            JSONObject result = new JSONObject()
                    .put("id", numericId != 0 ? Integer.toString(numericId)
                            : "point:" + Math.round(latitude * 100_000d)
                            + ':' + Math.round(longitude * 100_000d))
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("typeId", typeId);
            String typeLabel = typeLabel(typeId);
            if (!typeLabel.isEmpty()) result.put("typeLabel", typeLabel);

            int speed = number(type.getMethod("s", byte.class)
                    .invoke(camera, vehicleMode), -1);
            if (speed > 0 && speed <= 400) {
                result.put("speedLimit", speed);
                int unit = number(type.getMethod("u").invoke(camera), 1);
                result.put("speedUnit", unit == 1 ? "KPH" : "MPH");
            }
            result.put("controlTags", controlTags(typeId, speed > 0));
            result.put("directions", directions(type, camera));
            return result;
        } catch (Throwable malformed) {
            return null;
        }
    }

    private String typeLabel(int typeId) {
        String shortName = "cam_type_" + typeId + "_short";
        int identifier = getResources().getIdentifier(shortName, "string", getPackageName());
        if (identifier == 0) {
            identifier = getResources().getIdentifier(
                    "cam_type_" + typeId, "string", getPackageName());
        }
        if (identifier == 0) return "";
        try { return getString(identifier); }
        catch (RuntimeException unavailable) { return ""; }
    }

    private static JSONArray directions(Class<?> type, Object camera) throws Exception {
        JSONArray result = new JSONArray();
        int directionMode = number(type.getMethod("h").invoke(camera), 0);
        int bearing = number(type.getMethod("g").invoke(camera), -1);
        // 0 means HUD Speed did not publish a direction mode. North (bearing 0) remains valid
        // when the mode itself is present. Its own RadarView draws the opposite sector only for 2.
        if (directionMode >= 1 && directionMode <= 4 && bearing >= 0 && bearing < 360) {
            result.put(bearing);
            if (directionMode == 2) result.put((bearing + 180) % 360);
        }
        return result;
    }

    private static JSONArray controlTags(int typeId, boolean hasSpeed) {
        JSONArray result = new JSONArray();
        if (hasSpeed) result.put("SPEED_CONTROL");
        switch (typeId) {
            case 3:
                result.put("TRAFFIC_CONTROL");
                break;
            case 4:
            case 41:
            case 42:
            case 43:
                result.put("AVERAGE_SPEED_CONTROL");
                break;
            case 5:
            case 104:
            case 105:
                result.put("MOBILE_CONTROL");
                break;
            case 10:
                result.put("ALL_RULES_CONTROL");
                break;
            case 11:
                result.put("LANE_CONTROL");
                break;
            case 12:
                result.put("SHOULDER_CONTROL");
                break;
            case 13:
                result.put("ROAD_MARKING_CONTROL");
                break;
            case 17:
            case 171:
            case 172:
                result.put("TRUCK_CONTROL");
                break;
            case 18:
                result.put("NO_STOPPING_CONTROL");
                break;
            case 103:
                result.put("STOP_SIGN_CONTROL");
                break;
            case 107:
                result.put("PEDESTRIAN_CONTROL");
                break;
            default:
                break;
        }
        return result;
    }

    private static boolean isCameraType(int typeId) {
        switch (typeId) {
            case 1:
            case 3:
            case 4:
            case 5:
            case 10:
            case 11:
            case 12:
            case 13:
            case 14:
            case 15:
            case 16:
            case 17:
            case 18:
            case 41:
            case 42:
            case 43:
            case 103:
            case 104:
            case 105:
            case 106:
            case 107:
            case 108:
            case 171:
            case 172:
                return true;
            default:
                return false;
        }
    }

    private boolean isTrustedNatro(int uid) {
        if (uid <= 0) return false;
        PackageManager packages = getPackageManager();
        String[] names;
        try { names = packages.getPackagesForUid(uid); }
        catch (RuntimeException failure) { return false; }
        boolean exactPackage = false;
        if (names != null) {
            for (String name : names) {
                if (NATRO_PACKAGE.equals(name)) {
                    exactPackage = true;
                    break;
                }
            }
        }
        if (!exactPackage) return false;
        try {
            PackageInfo info = packages.getPackageInfo(
                    NATRO_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] signatures = info.signingInfo == null ? null
                    : info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
            if (signatures == null) return false;
            for (Signature signature : signatures) {
                if (NATRO_CERT_SHA256.equals(sha256(signature.toByteArray()))) return true;
            }
        } catch (Throwable unavailable) {
            return false;
        }
        return false;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            int unsigned = item & 0xff;
            if (unsigned < 16) result.append('0');
            result.append(Integer.toHexString(unsigned));
        }
        return result.toString();
    }
}
