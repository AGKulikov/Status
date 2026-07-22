package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LauncherRuleIdPolicyTest {
    @Test public void longActionNeverReusesPrimaryRule() {
        assertEquals("", LauncherRuleIdPolicy.reusableId(true,
                true, "launcher_1", false, false, ""));
    }

    @Test public void editingExistingLongRuleReusesOnlyLongTarget() {
        assertEquals("launcher_2", LauncherRuleIdPolicy.reusableId(true,
                true, "launcher_1", true, true, "launcher_2"));
    }

    @Test public void normalEditReusesPrimaryGeneratedRule() {
        assertEquals("launcher_1", LauncherRuleIdPolicy.reusableId(false,
                true, "launcher_1", true, true, "launcher_2"));
    }

    @Test public void userIntentRulesAreNeverOverwritten() {
        assertEquals("", LauncherRuleIdPolicy.reusableId(false,
                true, "intent_4", false, false, ""));
    }
}
