package dezz.status.widget.diagnostics;
import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class HudFailurePrivacyTest {
    @Test public void structuredStageSurvivesWithoutExposingCredentials() {
        String stage = "root_identity_before_request_invalid_uid".replace('_', ' ');
        String safe = DiagnosticJournal.redact("operation_stage=" + stage + ", token=private password=private");
        assertTrue(safe.contains("operation_stage=" + stage));
        assertFalse(safe.contains("private"));
        assertTrue(DiagnosticJournal.redact("Abcd1234".repeat(9)).contains("<hidden>"));
    }
}
