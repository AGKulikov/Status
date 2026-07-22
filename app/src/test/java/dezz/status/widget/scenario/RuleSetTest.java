/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuleSetTest {
    @Test public void evaluatesOrderedFirstMatchAndRendersLiteralValue() {
        Rule hot = new Rule("hot", Input.FIELD_VALUE, Operator.GREATER_OR_EQUAL, "25", "",
                new Output("Hot: {value}", "#ff0000", null, null, null, null));
        Rule fallback = new Rule("fallback", Input.FIELD_VALUE, Operator.ALWAYS, "", "",
                new Output("{value}", null, null, null, null, null));

        Result result = new RuleSet("temperature", Arrays.asList(hot, fallback))
                .evaluate(Input.value("25.0", true, true));

        assertTrue(result.matched);
        assertEquals(0, result.matchedRuleIndex);
        assertEquals("hot", result.matchedRuleId());
        assertEquals("Hot: 25.0", result.renderedText);
    }

    @Test public void normalOperatorsRefuseStaleOrUnavailableInput() {
        Rule equals = new Rule("equals", Input.FIELD_VALUE, Operator.EQUALS, "on", "",
                Output.none());
        Rule stale = new Rule("stale", Input.FIELD_VALUE, Operator.STALE, "", "",
                Output.none());
        Rule unavailable = new Rule("unavailable", Input.FIELD_VALUE, Operator.UNAVAILABLE,
                "", "", Output.none());

        assertFalse(equals.matches(Input.value("on", false, true)));
        assertFalse(equals.matches(Input.value("on", true, false)));
        assertTrue(stale.matches(Input.value("on", false, true)));
        assertTrue(unavailable.matches(Input.unavailable()));
    }

    @Test public void coercionIsNumericBooleanAndLiteralOnly() {
        assertTrue(new Rule("numeric", Input.FIELD_VALUE, Operator.EQUALS, "1.00", "",
                Output.none()).matches(Input.value(1, true, true)));
        assertTrue(new Rule("decimal_comma", Input.FIELD_VALUE, Operator.GREATER, "40", "",
                Output.none()).matches(Input.value("40,5", true, true)));
        assertTrue(new Rule("comma_operand", Input.FIELD_VALUE, Operator.EQUALS, "40,5", "",
                Output.none()).matches(Input.value("40.5", true, true)));
        assertFalse(new Rule("ambiguous_number", Input.FIELD_VALUE, Operator.EQUALS,
                "1,234.5", "", Output.none()).matches(Input.value(1234.5, true, true)));
        assertTrue(new Rule("boolean", Input.FIELD_VALUE, Operator.TRUE, "", "",
                Output.none()).matches(Input.value("on", true, true)));
        assertFalse(new Rule("literal", Input.FIELD_VALUE, Operator.CONTAINS, ".*", "",
                Output.none()).matches(Input.value("axb", true, true)));
    }

    @Test public void supportsEveryBoundedComparisonAndLiteralAttributeLookup() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("label", "front door");
        Input input = new Input(10, true, true, true, true, "number", "°C", attributes);

        assertTrue(rule("eq", Operator.EQUALS, "10", "").matches(input));
        assertTrue(rule("neq", Operator.NOT_EQUALS, "11", "").matches(input));
        assertTrue(rule("gt", Operator.GREATER, "9", "").matches(input));
        assertTrue(rule("gte", Operator.GREATER_OR_EQUAL, "10", "").matches(input));
        assertTrue(rule("lt", Operator.LESS, "11", "").matches(input));
        assertTrue(rule("lte", Operator.LESS_OR_EQUAL, "10", "").matches(input));
        assertTrue(rule("between", Operator.BETWEEN, "10", "20").matches(input));
        assertTrue(rule("inclusive_single_value", Operator.BETWEEN, "10", "10")
                .matches(input));
        assertTrue(rule("exclusive", Operator.BETWEEN_EXCLUSIVE, "9", "11").matches(input));
        assertFalse(rule("exclusive_edge", Operator.BETWEEN_EXCLUSIVE, "10", "11")
                .matches(input));
        assertFalse(rule("exclusive_empty_range", Operator.BETWEEN_EXCLUSIVE, "10", "10")
                .matches(input));
        assertTrue(new Rule("contains", "attributes.label", Operator.CONTAINS, "door", "",
                Output.none()).matches(input));
        assertTrue(new Rule("unit", Input.FIELD_UNIT, Operator.NOT_EMPTY, "", "",
                Output.none()).matches(input));
        assertFalse(new Rule("empty", Input.FIELD_UNIT, Operator.EMPTY, "", "",
                Output.none()).matches(input));
        assertTrue(rule("always", Operator.ALWAYS, "", "").matches(input));
        assertTrue(rule("fresh", Operator.FRESH, "", "").matches(input));
        assertTrue(rule("available", Operator.AVAILABLE, "", "").matches(input));
    }

    @Test public void supportsHumanFriendlyStringComparisons() {
        Input input = Input.value("Opening Gate", true, true);

        assertTrue(rule("eq_i", Operator.EQUALS_IGNORE_CASE, "opening gate", "")
                .matches(input));
        assertTrue(rule("neq_i", Operator.NOT_EQUALS_IGNORE_CASE, "closed", "")
                .matches(input));
        assertTrue(rule("contains_i", Operator.CONTAINS_IGNORE_CASE, "GATE", "")
                .matches(input));
        assertTrue(rule("starts", Operator.STARTS_WITH, "Opening", "").matches(input));
        assertTrue(rule("ends", Operator.ENDS_WITH, "Gate", "").matches(input));
    }

    @Test public void missingTextOverrideKeepsOriginalAndTransparentColorIsFirstMatch() {
        Rule hiddenClosed = new Rule("closed", Input.FIELD_VALUE, Operator.EQUALS,
                "closed", "", new Output(null, "transparent", null, null, null, null));
        Rule fallback = new Rule("fallback", Input.FIELD_VALUE, Operator.ALWAYS, "", "",
                new Output("{value}", "#FFFFFFFF", null, null, null, null));

        Result result = new RuleSet("cover", Arrays.asList(hiddenClosed, fallback))
                .evaluate(Input.value("closed", true, true));

        assertNull(result.renderedText);
        assertEquals("transparent", result.output.textColor);
        assertEquals(0, result.matchedRuleIndex);
    }

    @Test public void explicitSourceIsResolvedInsteadOfOwnInput() {
        ValueReference sprut = new ValueReference("SPRUTHUB", "car", "accessory.temp", "value");
        ValueResolverRegistry registry = new ValueResolverRegistry()
                .register("spruthub", "car", reference -> Input.value(21.5, true, true));
        RuleSet rules = new RuleSet("sprut_temp", sprut, Collections.singletonList(
                new Rule("value", Input.FIELD_VALUE, Operator.ALWAYS, "", "",
                        new Output("{value}", null, null, null, null, null))));

        assertEquals("21.5", rules.evaluate(Input.value(999, true, true), registry).renderedText);
    }

    @Test public void presetsHandleUnavailableBeforeFallback() {
        Result result = ScenarioPresets.raw().evaluate(Input.unavailable());
        assertEquals("unavailable", result.matchedRuleId());
        assertEquals("—", result.renderedText);
        assertEquals(Boolean.FALSE, result.output.actionEnabled);
    }

    @Test public void acceptsMoreThanLegacy128Rules() {
        ArrayList<Rule> rules = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            rules.add(new Rule("r" + index, Input.FIELD_VALUE, Operator.ALWAYS, "", "",
                    Output.none()));
        }
        assertEquals(256, new RuleSet("many", rules).rules.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void retainsHighImportSafetyCeiling() {
        ArrayList<Rule> rules = new ArrayList<>();
        for (int index = 0; index <= RuleSet.MAX_RULES; index++) {
            rules.add(new Rule("r" + index, Input.FIELD_VALUE, Operator.ALWAYS, "", "",
                    Output.none()));
        }
        new RuleSet("too_many", rules);
    }

    private static Rule rule(String id, Operator operator, String operand, String secondOperand) {
        return new Rule(id, Input.FIELD_VALUE, operator, operand, secondOperand, Output.none());
    }
}
