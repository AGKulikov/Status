/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut.preset;

import androidx.annotation.NonNull;

import java.math.BigDecimal;
import java.util.ArrayList;

import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.Operator;
import dezz.status.widget.scenario.Output;
import dezz.status.widget.scenario.Rule;
import dezz.status.widget.scenario.RuleSet;
import dezz.status.widget.scenario.ScenarioPresets;

/** Converts typed Sprut.hub recommendations into editable local display rules. */
public final class SprutRuleSetFactory {
    private SprutRuleSetFactory() {}

    @NonNull
    public static RuleSet fromPreset(@NonNull SprutPopupPreset preset) {
        if (preset.statusRules().isEmpty()) return forPresentation(preset.presentation());
        ArrayList<Rule> rules = new ArrayList<>();
        ArrayList<SprutPopupPreset.StatusRule> ordered = new ArrayList<>();
        for (SprutPopupPreset.StatusRule source : preset.statusRules()) {
            if (source.kind() != SprutPopupPreset.StatusRule.Kind.FALLBACK) ordered.add(source);
        }
        for (SprutPopupPreset.StatusRule source : preset.statusRules()) {
            if (source.kind() == SprutPopupPreset.StatusRule.Kind.FALLBACK) ordered.add(source);
        }
        int index = 0;
        for (SprutPopupPreset.StatusRule source : ordered) {
            Operator operator;
            String first = "";
            String second = "";
            switch (source.kind()) {
                case EXACT:
                    operator = Operator.EQUALS;
                    first = source.expectedValue().map(String::valueOf).orElse("");
                    break;
                case RANGE:
                    if (source.minimumInclusive().isPresent()
                            && source.maximumInclusive().isPresent()) {
                        operator = Operator.BETWEEN;
                        first = decimal(source.minimumInclusive().get());
                        second = decimal(source.maximumInclusive().get());
                    } else if (source.minimumInclusive().isPresent()) {
                        operator = Operator.GREATER_OR_EQUAL;
                        first = decimal(source.minimumInclusive().get());
                    } else {
                        operator = Operator.LESS_OR_EQUAL;
                        first = decimal(source.maximumInclusive().orElse(0d));
                    }
                    break;
                case FALLBACK:
                default:
                    operator = Operator.ALWAYS;
                    break;
            }
            SprutPopupPreset.StatusStyle style = source.style();
            String label = style.label().isEmpty() ? "{value}" : style.label();
            rules.add(new Rule("sprut_" + index++, Input.FIELD_VALUE, operator, first, second,
                    new Output(label, style.argbColor(), preset.iconId(), null, null, null)));
        }
        return new RuleSet("preset.sprut", rules);
    }

    @NonNull
    public static RuleSet forPresentation(SprutPopupPreset.Presentation presentation) {
        switch (presentation) {
            case COVER: return ScenarioPresets.cover();
            case BOOLEAN: return ScenarioPresets.booleanState();
            case TEMPERATURE: return ScenarioPresets.temperature();
            case AUTO:
            case RAW:
            default: return ScenarioPresets.raw();
        }
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
