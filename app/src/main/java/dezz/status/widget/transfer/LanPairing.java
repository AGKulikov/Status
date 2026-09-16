/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** In-memory, bounded pairing. Restart/rotation revokes every token; no passwords in logs. */
public final class LanPairing {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new LinkedHashMap<>();
    private String pin;
    private long pinExpires, windowStart;
    private int attempts;
    public LanPairing(long now) { rotate(now); }
    public synchronized String pin() { return pin; }
    public synchronized long expires() { return pinExpires; }
    public synchronized void rotate(long now) {
        pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        pinExpires = now + 5 * 60_000L; windowStart = now; attempts = 0; sessions.clear();
    }
    public synchronized String pair(String supplied, long now) {
        if (now - windowStart >= 60_000L) { attempts = 0; windowStart = now; }
        if (++attempts > 5 || now >= pinExpires || !equal(pin, supplied)) return null;
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.entrySet().removeIf(entry -> now >= entry.getValue());
        while (sessions.size() >= 4) sessions.remove(sessions.keySet().iterator().next());
        sessions.put(token, now + 30 * 60_000L); return token;
    }
    public synchronized boolean authorized(String token, long now) {
        if (token == null || token.length() != 43) return false;
        for (Map.Entry<String, Long> entry : sessions.entrySet()) {
            if (equal(entry.getKey(), token)) {
                if (now >= entry.getValue()) return false;
                entry.setValue(now + 30 * 60_000L); return true;
            }
        }
        return false;
    }
    private static boolean equal(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
