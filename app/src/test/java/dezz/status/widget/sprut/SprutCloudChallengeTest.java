/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

public final class SprutCloudChallengeTest {
    /**
     * Vector generated independently with hash-wasm Argon2id and Node's Ed25519 implementation,
     * matching the direct Argon2id-to-Ed25519 flow in Sprut's current web client.
     */
    @Test public void matchesCurrentSprutWebClientProof() throws Exception {
        JSONObject data = new JSONObject()
                .put("rootSalt", "AAECAwQFBgcICQoLDA0ODw==")
                .put("challenge", "//79/Pv6+fj39vX08/Lx8O/u7ezr6uno5+bl5OPi4eA=")
                .put("kdfParams", "m=32,t=2,p=1");

        assertEquals(
                "ldfelFZ82knxzp4BQR8CuDVx0ggCAcIUvJm/AQvoyA7l/DcsbkZ2n31RlakKq2gw"
                        + "mAumvr3hxLfLlm7zMrkOAA==",
                SprutCloudChallenge.answer("пароль-StatusWidget-2026", data.toString()));
    }

    @Test public void rejectsServerParametersThatCouldExhaustTheHeadUnit() throws Exception {
        JSONObject data = new JSONObject()
                .put("rootSalt", "AAECAwQFBgcICQoLDA0ODw==")
                .put("challenge", "AQIDBAUGBwg=")
                .put("kdfParams", "m=2147483647,t=2,p=1")
                .put("info", "test");
        try {
            SprutCloudChallenge.answer("secret", data.toString());
            fail("Expected an excessive memory cost to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("memory cost"));
            assertTrue(!expected.getMessage().contains("secret"));
        }
    }

    @Test public void legacyInfoFieldDoesNotChangeOfficialProof() throws Exception {
        JSONObject withoutInfo = new JSONObject()
                .put("rootSalt", "AAECAwQFBgcICQoLDA0ODw==")
                .put("challenge", "//79/Pv6+fj39vX08/Lx8O/u7ezr6uno5+bl5OPi4eA=")
                .put("kdfParams", "m=32,t=2,p=1");
        JSONObject withLegacyInfo = new JSONObject(withoutInfo.toString())
                .put("info", "legacy client context");

        String missingAnswer = SprutCloudChallenge.answer("secret", withoutInfo.toString());
        String legacyAnswer = SprutCloudChallenge.answer("secret", withLegacyInfo.toString());

        assertEquals(legacyAnswer, missingAnswer);
        assertEquals(64, java.util.Base64.getDecoder().decode(missingAnswer).length);
    }

    @Test public void ignoresUnknownChallengeFields() throws Exception {
        JSONObject base = new JSONObject()
                .put("rootSalt", "AAECAwQFBgcICQoLDA0ODw==")
                .put("challenge", "AQIDBAUGBwg=")
                .put("kdfParams", "m=32,t=2,p=1");
        JSONObject withUnknownFields = new JSONObject(base.toString())
                .put("info", new JSONObject());

        assertEquals(
                SprutCloudChallenge.answer("secret", base.toString()),
                SprutCloudChallenge.answer("secret", withUnknownFields.toString()));
    }

    @Test public void retriesReuseOnlyTheKeyForThisSaltAndCostAndWipeItOnClose() throws Exception {
        JSONObject data = new JSONObject()
                .put("rootSalt", "AAECAwQFBgcICQoLDA0ODw==")
                .put("challenge", "AQIDBAUGBwg=").put("kdfParams", "m=32,t=2,p=1");
        SprutCloudChallenge dialogue = new SprutCloudChallenge("test-password");
        java.lang.reflect.Field key = SprutCloudChallenge.class.getDeclaredField("cachedKey");
        key.setAccessible(true);
        byte[] lastKey;
        try (dialogue) {
            String first = dialogue.sign(data.toString());
            byte[] initialKey = (byte[]) key.get(dialogue);
            data.put("challenge", "AgIDBAUGBwg=");
            String next = dialogue.sign(data.toString());
            assertSame(initialKey, key.get(dialogue));
            assertNotEquals(first, next);
            assertEquals(SprutCloudChallenge.answer("test-password", data.toString()), next);

            data.put("kdfParams", "m=64,t=1,p=1");
            assertEquals(SprutCloudChallenge.answer("test-password", data.toString()),
                    dialogue.sign(data.toString()));
            assertArrayEquals(new byte[32], initialKey);
            data.put("rootSalt", "AQECAwQFBgcICQoLDA0ODw==");
            assertEquals(SprutCloudChallenge.answer("test-password", data.toString()),
                    dialogue.sign(data.toString()));
            lastKey = (byte[]) key.get(dialogue);
        }
        assertArrayEquals(new byte[32], lastKey);
        assertThrows(IOException.class, () -> dialogue.sign(data.toString()));
    }
}
