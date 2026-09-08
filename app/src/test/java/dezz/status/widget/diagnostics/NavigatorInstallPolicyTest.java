/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;
import static dezz.status.widget.diagnostics.NavigatorInstallPolicy.*;

public final class NavigatorInstallPolicyTest {
    @Test public void onlyExactReleasedFileMayBeInstalled() {
        assertTrue(exactArtifact(APK_BYTES, APK_SHA256));
        assertFalse(exactArtifact(APK_BYTES - 1, APK_SHA256));
        assertFalse(exactArtifact(APK_BYTES + 1, APK_SHA256));
        assertFalse(exactArtifact(APK_BYTES, "7f6b95c73104f8d4c0c2a0f519f3eb84f71dc7441a82e7349aaff4612e21ed16"));
        assertFalse(exactArtifact(APK_BYTES, null));
    }
    @Test public void pendingSessionExcludesAnotherSelection() {
        for (String phase : new String[]{READING, WRITING, COMMITTED, USER_ACTION, WAITING}) {
            assertFalse(phase, canSelect(phase));
        }
        assertTrue(canSelect(DONE));
        assertTrue(canSelect(BLOCKED));
    }
    @Test public void confirmationIsAnIntermediateSystemResult() {
        assertTrue(acceptsResult(COMMITTED, 17, "current", 17, "current", -1));
        assertFalse(statusText(-1).contains("Навигатор установлен"));
    }
    @Test public void finalAnswerSurvivesActivityRecreationDuringConfirmation() {
        assertTrue(acceptsResult(USER_ACTION, 17, "current", 17, "current", 4));
        assertTrue(acceptsResult(WAITING, 17, "current", 17, "current", 0));
    }
    @Test public void delayedFinalAnswerCanResolveMissingCallbackState() {
        assertTrue(acceptsResult(UNKNOWN, 17, "current", 17, "current", 0));
        assertTrue(acceptsResult(UNKNOWN, 17, "current", 17, "current", 3));
        assertFalse(acceptsResult(UNKNOWN, 17, "current", 17, "current", -1));
    }
    @Test public void oldSessionCannotOverwriteNewAttempt() {
        assertFalse(acceptsResult(COMMITTED, 18, "new", 17, "old", 0));
        assertFalse(acceptsResult(COMMITTED, 18, "new", 17, "new", 0));
        assertFalse(acceptsResult(COMMITTED, 18, "new", 18, "old", 0));
    }
    @Test public void malformedResultsCannotCreateSuccess() {
        assertFalse(acceptsResult(COMMITTED, 18, "new", -1, "new", 0));
        assertFalse(acceptsResult(COMMITTED, 18, "new", 18, null, 0));
        assertFalse(acceptsResult(COMMITTED, 18, "", 18, "", 0));
        assertFalse(acceptsResult(COMMITTED, 18, "new", 18, "new", Integer.MIN_VALUE));
    }
    @Test public void earlyAndDuplicateFinalResultsAreIgnored() {
        for (String phase : new String[]{IDLE, READING, READY, WRITING, BLOCKED, DONE}) {
            assertFalse(phase, acceptsResult(phase, 18, "new", 18, "new", 0));
        }
    }
    @Test public void cancellationFailureAndUnknownCodeAreNeverLabeledSuccess() {
        for (int status : new int[]{1, 2, 3, 4, 5, 6, 7, 99}) {
            assertTrue(acceptsResult(COMMITTED, 18, "new", 18, "new", status));
            assertFalse(statusText(status).contains("Навигатор установлен"));
        }
        assertTrue(statusText(0).contains("Android сообщил: Навигатор установлен"));
    }
}
