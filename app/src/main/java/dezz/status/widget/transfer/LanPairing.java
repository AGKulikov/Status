/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded ephemeral sessions plus explicitly remembered, hashed 256-bit credentials. */
public final class LanPairing {
    public interface TrustStore {
        Set<String> hashes();
        boolean save(Set<String> hashes);
    }
    private final TrustStore trust;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new LinkedHashMap<>();
    private String pin;
    private long pinExpires, windowStart;
    private int attempts;
    public LanPairing(long now) { this(now,null); }
    public LanPairing(long now,TrustStore trust) { this.trust=trust;newPin(now); }
    public synchronized String pin() { return pin; }
    public synchronized long expires() { return pinExpires; }
    private void newPin(long now) {
        pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        pinExpires = now + 5 * 60_000L; windowStart = now; attempts = 0; sessions.clear();
    }
    public synchronized boolean rotate(long now) {
        sessions.clear();
        if(trust!=null&&!trust.save(Collections.emptySet()))return false;
        newPin(now);return true;
    }
    public synchronized String pair(String supplied, long now) {
        return pair(supplied,now,false);
    }
    public synchronized String pair(String supplied,long now,boolean remember) {
        if (now - windowStart >= 60_000L) { attempts = 0; windowStart = now; }
        if (++attempts > 5 || now >= pinExpires || !equal(pin, supplied)) return null;
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if(remember) {
            if(trust==null)return null;
            Set<String> hashes=new HashSet<>(trust.hashes());
            if(hashes.size()>=4)return null;
            hashes.add(hash(token));
            return trust.save(hashes)?token:null;
        }
        sessions.entrySet().removeIf(entry -> now >= entry.getValue());
        while (sessions.size() >= 4) sessions.remove(sessions.keySet().iterator().next());
        sessions.put(token, now + 30 * 60_000L); return token;
    }
    public synchronized boolean authorized(String token, long now) {
        if (token == null || token.length() != 43) return false;
        if(trust!=null)for(String saved:trust.hashes())if(equal(saved,hash(token)))return true;
        for (Map.Entry<String, Long> entry : sessions.entrySet()) {
            if (equal(entry.getKey(), token)) {
                if (now >= entry.getValue()) return false;
                entry.setValue(now + 30 * 60_000L); return true;
            }
        }
        return false;
    }
    public synchronized boolean forget(String token) {
        if(token==null||token.length()!=43)return false;
        if(trust!=null) {
            Set<String> hashes=new HashSet<>(trust.hashes());hashes.remove(hash(token));
            if(!trust.save(hashes))return false;
        }
        sessions.remove(token);return true;
    }
    private static String hash(String token) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static boolean equal(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
