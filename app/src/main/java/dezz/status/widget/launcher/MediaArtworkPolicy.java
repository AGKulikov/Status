/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Pure identity and reuse policy for the disk-backed rich-media artwork cache. */
final class MediaArtworkPolicy {
    enum Action { CLEAR, REUSE, DECODE }

    private MediaArtworkPolicy() {}

    /**
     * Chooses whether an update needs expensive decode/PNG/fsync work.
     *
     * <p>A payload identity always wins when one is available. Some publishers send artwork only
     * on the first packet and keep {@code has_artwork=true} on later position packets; those may
     * reuse the file only while the privacy-preserving track identity is unchanged.</p>
     */
    static Action decide(boolean artworkExpected, boolean sourceAvailable,
                         String incomingSourceSignature,
                         String incomingTrackSignature,
                         boolean storedHasArtwork, boolean storedFileValid,
                         String storedSourceSignature,
                         String storedTrackSignature) {
        if (!artworkExpected) return Action.CLEAR;
        if (!storedHasArtwork || !storedFileValid) {
            return sourceAvailable ? Action.DECODE : Action.CLEAR;
        }
        if (sourceAvailable) {
            return !incomingSourceSignature.isEmpty()
                    && incomingSourceSignature.equals(storedSourceSignature)
                    ? Action.REUSE : Action.DECODE;
        }
        return !incomingTrackSignature.isEmpty()
                && incomingTrackSignature.equals(storedTrackSignature)
                ? Action.REUSE : Action.CLEAR;
    }

    /** Raw metadata and content URIs are never written to the identity preference. */
    static String trackSignature(String packageName, String title,
                                 String artist, String album) {
        if (packageName.isEmpty() && title.isEmpty() && artist.isEmpty() && album.isEmpty()) {
            return "";
        }
        return "track:" + sha256(framed(packageName, title, artist, album));
    }

    static String bytesSignature(byte[] bytes) {
        return bytes.length == 0 ? "" : "bytes:" + sha256(bytes);
    }

    static String uriSignature(String uri, String trackSignature) {
        if (uri.isEmpty()) return "";
        return "uri:" + sha256(framed(uri, trackSignature));
    }

    /**
     * Identifies every supplied artwork source without persisting its URI or raw bytes.
     *
     * <p>The decoder tries the URI first and the byte array as a fallback. Including both means
     * that a publisher which keeps a stable URI but refreshes its fallback image cannot leave a
     * stale cache entry behind.</p>
     */
    static String sourceSignature(String uri, byte[] bytes, String trackSignature) {
        String uriValue = uriSignature(uri, trackSignature);
        String byteValue = bytes == null ? "" : bytesSignature(bytes);
        if (uriValue.isEmpty()) return byteValue;
        if (byteValue.isEmpty()) return uriValue;
        return "source:" + sha256(framed(uriValue, byteValue));
    }

    private static byte[] framed(String... values) {
        StringBuilder value = new StringBuilder();
        for (String item : values) {
            value.append(item.length()).append(':').append(item).append(';');
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Android/Java must provide SHA-256", impossible);
        }
        char[] result = new char[digest.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < digest.length; index++) {
            int unsigned = digest[index] & 0xff;
            result[index * 2] = alphabet[unsigned >>> 4];
            result[index * 2 + 1] = alphabet[unsigned & 0x0f];
        }
        return new String(result);
    }
}
