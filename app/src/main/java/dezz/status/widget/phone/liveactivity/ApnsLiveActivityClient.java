/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.liveactivity;

import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal HTTP/2 APNs provider for private push-to-start and immediate end events. */
final class ApnsLiveActivityClient {
    interface Completion {
        void onComplete(boolean success, int statusCode, @NonNull String reason);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long JWT_MAX_AGE_SECONDS = 45 * 60L;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private String cachedJwt = "";
    private String cachedIdentity = "";
    private long cachedIssuedAt;

    void start(@NonNull ApnsCredentialStore.Credentials credentials,
               @NonNull byte[] pushToStartToken,
               @NonNull LiveActivityProvisioningStore.Configuration configuration,
               int panel, @NonNull Completion completion) {
        long now = Instant.now().getEpochSecond();
        int[] controls = panel == 0
                ? configuration.climateControlIds : configuration.functionControlIds;
        try {
            JSONObject attributes = new JSONObject()
                    .put("panel", panel == 0 ? "climate" : "functions")
                    .put("controlIDs", intArray(controls))
                    .put("vehicleName", configuration.vehicleName)
                    .put("showVehicle", configuration.showVehicle);
            JSONObject state = initialState(now, controls);
            JSONObject aps = new JSONObject()
                    .put("timestamp", now)
                    .put("event", "start")
                    .put("attributes-type", "NatroLiveActivityAttributes")
                    .put("attributes", attributes)
                    .put("content-state", state)
                    // iOS 18 returns the per-activity token to the launched app immediately.
                    .put("input-push-token", 1)
                    .put("stale-date", now + 15 * 60L);
            send(credentials, pushToStartToken, new JSONObject().put("aps", aps), completion);
        } catch (Exception invalid) {
            completion.onComplete(false, 0, "payload");
        }
    }

    void end(@NonNull ApnsCredentialStore.Credentials credentials,
             @NonNull LiveActivityProvisioningStore.ActivityToken activity,
             @NonNull Completion completion) {
        long now = Instant.now().getEpochSecond();
        try {
            JSONObject state = new JSONObject()
                    .put("s", 0).put("v", new JSONArray()).put("f", new JSONArray())
                    .put("u", now);
            JSONObject aps = new JSONObject()
                    .put("timestamp", now)
                    .put("event", "end")
                    .put("content-state", state)
                    .put("dismissal-date", now);
            send(credentials, activity.token, new JSONObject().put("aps", aps), completion);
        } catch (JSONException invalid) {
            completion.onComplete(false, 0, "payload");
        }
    }

    private void send(@NonNull ApnsCredentialStore.Credentials credentials,
                      @NonNull byte[] deviceToken, @NonNull JSONObject payload,
                      @NonNull Completion completion) {
        final String authorization;
        try {
            authorization = "bearer " + jwt(credentials);
        } catch (Exception signingFailure) {
            completion.onComplete(false, 0, "signing");
            return;
        }
        String tokenHex = hex(deviceToken);
        Request request = new Request.Builder()
                .url(credentials.endpoint() + "/3/device/" + tokenHex)
                .header("authorization", authorization)
                .header("apns-push-type", "liveactivity")
                .header("apns-topic", credentials.topic)
                .header("apns-priority", "10")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        HTTP.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull java.io.IOException e) {
                completion.onComplete(false, 0, "network");
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response exact = response) {
                    String reason = exact.isSuccessful() ? "ok" : "apns";
                    if (exact.body() != null) {
                        String body = exact.body().string();
                        try { reason = new JSONObject(body).optString("reason", reason); }
                        catch (JSONException ignored) { }
                    }
                    completion.onComplete(exact.isSuccessful(), exact.code(), reason);
                } catch (Exception readFailure) {
                    completion.onComplete(false, response.code(), "response");
                }
            }
        });
    }

    @NonNull
    private synchronized String jwt(@NonNull ApnsCredentialStore.Credentials credentials)
            throws Exception {
        long now = Instant.now().getEpochSecond();
        String identity = credentials.teamId + ":" + credentials.keyId;
        if (identity.equals(cachedIdentity) && !cachedJwt.isEmpty()
                && now - cachedIssuedAt >= 0 && now - cachedIssuedAt < JWT_MAX_AGE_SECONDS) {
            return cachedJwt;
        }
        String header = urlBase64(new JSONObject()
                .put("alg", "ES256").put("kid", credentials.keyId).toString().getBytes(
                        StandardCharsets.UTF_8));
        String claims = urlBase64(new JSONObject()
                .put("iss", credentials.teamId).put("iat", now).toString().getBytes(
                        StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(credentials.privateKey());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String token = signingInput + "." + urlBase64(derSignatureToJose(signer.sign()));
        cachedIdentity = identity;
        cachedIssuedAt = now;
        cachedJwt = token;
        return token;
    }

    @NonNull
    static byte[] derSignatureToJose(@NonNull byte[] der) {
        if (der.length < 8 || der[0] != 0x30) throw new IllegalArgumentException("bad DER");
        int[] cursor = {1};
        int sequenceLength = readLength(der, cursor);
        if (sequenceLength != der.length - cursor[0] || der[cursor[0]++] != 0x02) {
            throw new IllegalArgumentException("bad DER sequence");
        }
        int rLength = readLength(der, cursor);
        if (rLength < 1 || cursor[0] + rLength >= der.length) {
            throw new IllegalArgumentException("bad DER r");
        }
        byte[] r = Arrays.copyOfRange(der, cursor[0], cursor[0] + rLength);
        cursor[0] += rLength;
        if (der[cursor[0]++] != 0x02) throw new IllegalArgumentException("bad DER s tag");
        int sLength = readLength(der, cursor);
        if (sLength < 1 || cursor[0] + sLength != der.length) {
            throw new IllegalArgumentException("bad DER s");
        }
        byte[] s = Arrays.copyOfRange(der, cursor[0], cursor[0] + sLength);
        byte[] jose = new byte[64];
        copyInteger(r, jose, 0);
        copyInteger(s, jose, 32);
        return jose;
    }

    private static int readLength(byte[] bytes, int[] cursor) {
        if (cursor[0] >= bytes.length) throw new IllegalArgumentException("bad DER length");
        int first = bytes[cursor[0]++] & 0xff;
        if (first < 0x80) return first;
        int count = first & 0x7f;
        if (count < 1 || count > 2 || cursor[0] + count > bytes.length) {
            throw new IllegalArgumentException("bad DER long length");
        }
        int result = 0;
        for (int index = 0; index < count; index++) {
            result = result << 8 | bytes[cursor[0]++] & 0xff;
        }
        return result;
    }

    private static void copyInteger(byte[] integer, byte[] output, int offset) {
        int start = 0;
        while (start < integer.length - 1 && integer[start] == 0) start++;
        int length = integer.length - start;
        if (length > 32 || (integer[start] & 0x80) != 0) {
            throw new IllegalArgumentException("DER integer out of range");
        }
        System.arraycopy(integer, start, output, offset + 32 - length, length);
    }

    @NonNull private static JSONObject initialState(long now, int[] controls)
            throws JSONException {
        JSONArray values = new JSONArray();
        JSONArray flags = new JSONArray();
        for (int ignored : controls) {
            values.put(0);
            flags.put(1); // available, not yet known; BLE state replaces it immediately.
        }
        return new JSONObject().put("s", 1).put("v", values).put("f", flags).put("u", now);
    }

    @NonNull private static JSONArray intArray(int[] values) {
        JSONArray result = new JSONArray();
        for (int value : values) result.put(value);
        return result;
    }

    @NonNull private static String urlBase64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    @NonNull private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }
}
