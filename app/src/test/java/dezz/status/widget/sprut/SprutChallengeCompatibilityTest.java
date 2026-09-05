package dezz.status.widget.sprut;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public final class SprutChallengeCompatibilityTest {
    @Test public void standardBase64RelayOperatorsRequestAFreshChallenge() {
        assertTrue(SprutHubController.hasParserUnsafeProofCharacters("/abc"));
        assertTrue(SprutHubController.hasParserUnsafeProofCharacters("+abc"));
        assertTrue(SprutHubController.hasParserUnsafeProofCharacters(""));
        assertTrue(SprutHubController.hasParserUnsafeProofCharacters("A/+/="));
        assertTrue(SprutHubController.hasParserUnsafeProofCharacters("7/+/="));
        assertFalse(SprutHubController.hasParserUnsafeProofCharacters("A+bc=="));
    }

    @Test public void retriesOnlyTheCloudResultParserRegression() {
        assertTrue(SprutHubController.isChallengeResultParserFailure(
                new SprutHubRpcClient.RpcException(
                        -32602,
                        "Failed to parse: Unknown character format in result: /",
                        null)));
        assertTrue(SprutHubController.isChallengeResultParserFailure(
                new SprutHubRpcClient.RpcException(
                        -32602,
                        "FAILED TO PARSE: unknown CHARACTER FORMAT in RESULT",
                        null)));
    }

    @Test public void wrongPasswordAndUnrelatedProtocolErrorsAreNeverRetried() {
        assertFalse(SprutHubController.isChallengeResultParserFailure(
                new SprutHubRpcClient.RpcException(
                        -32602, "Access denied: wrong answer", null)));
        assertFalse(SprutHubController.isChallengeResultParserFailure(
                new SprutHubRpcClient.RpcException(
                        -32000,
                        "Failed to parse: Unknown character format in result: /",
                        null)));
        assertFalse(SprutHubController.isChallengeResultParserFailure(
                new IOException("connection reset")));
    }
}
