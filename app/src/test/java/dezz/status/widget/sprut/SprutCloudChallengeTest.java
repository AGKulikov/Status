/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

public final class SprutCloudChallengeTest {
    /**
     * Vector generated independently with the same primitives used by Sprut's current web client:
     * hash-wasm Argon2id, WebCrypto-compatible HKDF-SHA256 and noble Ed25519.
     */
    @Test public void matchesCurrentSprutWebClientProof() throws Exception {
        JSONObject data = new JSONObject()
                .put("rootSalt", "AAECAwQFBgcICQoLDA0ODw==")
                .put("challenge", "//79/Pv6+fj39vX08/Lx8O/u7ezr6uno5+bl5OPi4eA=")
                .put("kdfParams", "m=32,t=2,p=1")
                .put("info", "Sprut.hub challenge test");

        assertEquals(
                "4b3jD8tSAaWH/xuatU8emTtxid+czxyBgzIXEXAtO7gW22apw96PZ9zYGZEhWcQS"
                        + "gwSoiLkUzndw24T35EUQDg==",
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

    @Test public void acceptsCurrentBetaChallengeWithoutOptionalHkdfInfo() throws Exception {
        JSONObject withoutInfo = new JSONObject()
                .put("rootSalt", "AAECAwQFBgcICQoLDA0ODw==")
                .put("challenge", "//79/Pv6+fj39vX08/Lx8O/u7ezr6uno5+bl5OPi4eA=")
                .put("kdfParams", "m=32,t=2,p=1");
        JSONObject withEmptyInfo = new JSONObject(withoutInfo.toString()).put("info", "");

        String missingAnswer = SprutCloudChallenge.answer("secret", withoutInfo.toString());
        String emptyAnswer = SprutCloudChallenge.answer("secret", withEmptyInfo.toString());

        assertEquals(emptyAnswer, missingAnswer);
        assertEquals(64, java.util.Base64.getDecoder().decode(missingAnswer).length);
    }

    @Test public void rejectsNonStringHkdfInfoWithoutLeakingPassword() throws Exception {
        JSONObject data = new JSONObject()
                .put("rootSalt", "AAECAwQFBgcICQoLDA0ODw==")
                .put("challenge", "AQIDBAUGBwg=")
                .put("kdfParams", "m=32,t=2,p=1")
                .put("info", new JSONObject());
        try {
            SprutCloudChallenge.answer("must-not-appear", data.toString());
            fail("Expected non-string info to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("info is not a string"));
            assertTrue(!expected.getMessage().contains("must-not-appear"));
        }
    }
}
