/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import java.util.Arrays;

/** Safe built-in first-match display rule sets; callers may edit/copy their JSON normally. */
public final class ScenarioPresets {
    private ScenarioPresets() {}

    public static RuleSet cover() {
        return cover(null);
    }

    public static RuleSet cover(ValueReference source) {
        return new RuleSet("preset.cover", source, Arrays.asList(
                stateProblem("unavailable", Operator.UNAVAILABLE, "—", "gate"),
                stateProblem("stale", Operator.STALE, "—", "gate"),
                rule("open", Operator.EQUALS, "open",
                        new Output("Открыто", "#FF4CAF50", "gate", null, null, null)),
                rule("opening", Operator.EQUALS, "opening",
                        new Output("Открывается", "#FFFF9800", "gate", null, null, null)),
                rule("closing", Operator.EQUALS, "closing",
                        new Output("Закрывается", "#FFF44336", "gate", null, null, null)),
                rule("closed", Operator.EQUALS, "closed",
                        new Output("Закрыто", "#FFFFFFFF", "gate", null, null, null)),
                rule("stopped", Operator.EQUALS, "stopped",
                        new Output("Остановлено", "#FFFFC107", "gate", null, null, null)),
                rule("raw", Operator.ALWAYS, "",
                        new Output("{value}", "#FFFFFFFF", "gate", null, null, null))));
    }

    public static RuleSet booleanState() {
        return booleanState(null);
    }

    public static RuleSet booleanState(ValueReference source) {
        return new RuleSet("preset.boolean", source, Arrays.asList(
                stateProblem("unavailable", Operator.UNAVAILABLE, "—", "power"),
                stateProblem("stale", Operator.STALE, "—", "power"),
                rule("true", Operator.TRUE, "",
                        new Output("Включено", "#FF4CAF50", "power", null, null, null)),
                rule("false", Operator.FALSE, "",
                        new Output("Выключено", "#FFFFFFFF", "power", null, null, null)),
                rule("raw", Operator.ALWAYS, "",
                        new Output("{value}", null, "power", null, null, null))));
    }

    public static RuleSet temperature() {
        return temperature(null);
    }

    public static RuleSet temperature(ValueReference source) {
        return new RuleSet("preset.temperature", source, Arrays.asList(
                stateProblem("unavailable", Operator.UNAVAILABLE, "—", "temperature"),
                stateProblem("stale", Operator.STALE, "—", "temperature"),
                rule("value", Operator.ALWAYS, "",
                        new Output("{value}", null, "temperature", null, null, null))));
    }

    public static RuleSet raw() {
        return raw(null);
    }

    public static RuleSet raw(ValueReference source) {
        return new RuleSet("preset.raw", source, Arrays.asList(
                stateProblem("unavailable", Operator.UNAVAILABLE, "—", null),
                stateProblem("stale", Operator.STALE, "—", null),
                rule("value", Operator.ALWAYS, "",
                        new Output("{value}", null, null, null, null, null))));
    }

    private static Rule stateProblem(String id, Operator operator, String text, String icon) {
        return new Rule(id, Input.FIELD_VALUE, operator, "", "",
                new Output(text, null, icon, null, null, false));
    }

    private static Rule rule(String id, Operator operator, String operand, Output output) {
        return new Rule(id, Input.FIELD_VALUE, operator, operand, "", output);
    }
}
