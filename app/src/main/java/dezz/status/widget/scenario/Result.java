/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

/** Immutable first-match result of evaluating a {@link RuleSet}. */
public final class Result {
    public final boolean matched;
    public final int matchedRuleIndex;
    public final Rule matchedRule;
    public final Output output;
    /** Rendered text override, or null when the matching output leaves text unchanged. */
    public final String renderedText;
    /** The own or referenced input actually used for evaluation. */
    public final Input input;

    private Result(boolean matched, int matchedRuleIndex, Rule matchedRule, Output output,
                   String renderedText, Input input) {
        this.matched = matched;
        this.matchedRuleIndex = matchedRuleIndex;
        this.matchedRule = matchedRule;
        this.output = output;
        this.renderedText = renderedText;
        this.input = input;
    }

    static Result matched(int index, Rule rule, Input input) {
        return new Result(true, index, rule, rule.output, rule.output.renderText(input), input);
    }

    static Result noMatch(Input input) {
        return new Result(false, -1, null, Output.none(), null, input);
    }

    public String matchedRuleId() {
        return matchedRule == null ? null : matchedRule.id;
    }
}
