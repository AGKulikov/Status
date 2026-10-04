/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import dezz.status.widget.R;
import java.util.*;

/** Six real pages of controls, with the original field instances and their validation intact. */
public final class SettingsEditorLayout extends LinearLayout {
    public static final String CONTROL_ROW_TAG = "natro.settings.numeric.row";
    public static final String PREVIEW_TAG = "natro.settings.preview";
    private static final String FORM_TAG = "natro.settings.form";
    private final View scroll;
    private final LinearLayout form;
    private final String owner;
    private final LinearLayout navigation;
    private final Map<SettingsSection, Button> buttons = new EnumMap<>(SettingsSection.class);
    private final List<FieldGroup> groups = new ArrayList<>();
    private final Map<SettingsSection, Integer> positions = new EnumMap<>(SettingsSection.class);
    private SettingsSection selected = SettingsSection.MAIN;
    private boolean organizing;

    private static final class FieldGroup extends LinearLayout {
        final SettingsSection section;
        FieldGroup(Context context, SettingsSection section) {
            super(context); this.section = section; setOrientation(VERTICAL);
        }
    }

    private SettingsEditorLayout(Context context, View scroll, LinearLayout form, String owner) {
        super(context); this.scroll = scroll; this.form = form; this.owner = owner;
        setOrientation(VERTICAL); setTag(FORM_TAG);
        setBackgroundColor(color(R.color.settings_background));
        LinearLayout split = new LinearLayout(context); split.setOrientation(HORIZONTAL);
        View preview = null;
        for (int i = form.getChildCount() - 1; i >= 0; i--) {
            View child = form.getChildAt(i);
            if (PREVIEW_TAG.equals(child.getTag())) { form.removeViewAt(i); preview = child; }
        }
        if (form.getChildCount() > 0 && hasBackControl(form.getChildAt(0))) {
            View toolbar = form.getChildAt(0); form.removeViewAt(0); addView(toolbar, new LayoutParams(-1,-2));
        }
        try { selected = SettingsSection.valueOf(SettingsAppearance.preferences(context)
                .getString("editor." + owner + ".section", "MAIN")); }
        catch (IllegalArgumentException ignored) { selected = SettingsSection.MAIN; }
        ScrollView rail = new ScrollView(context); rail.setFillViewport(false);
        navigation = new LinearLayout(context); navigation.setOrientation(VERTICAL);
        navigation.setPadding(dp(8), dp(8), dp(8), dp(8)); rail.addView(navigation);
        split.addView(rail, new LayoutParams(dp(194), ViewGroup.LayoutParams.MATCH_PARENT));
        split.addView(scroll, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        if (preview != null) split.addView(preview, new LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT));
        addView(split, new LayoutParams(-1, 0, 1));
        for (SettingsSection section : SettingsSection.values()) {
            Button button = new Button(context); button.setAllCaps(false); button.setText(section.title);
            button.setTextSize(20); button.setMinHeight(dp(54)); button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            button.setPadding(dp(12), dp(8), dp(8), dp(8));
            LayoutParams params = new LayoutParams(-1, -2); params.bottomMargin = dp(6);
            navigation.addView(button, params); buttons.put(section, button);
            button.setOnClickListener(v -> select(section, true));
        }
        organize();
        form.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (organizing) return;
            for (int i = 0; i < form.getChildCount(); i++) {
                if (!(form.getChildAt(i) instanceof FieldGroup)) { organize(); break; }
            }
        });
        post(() -> scroll.scrollTo(0, SettingsAppearance.preferences(context)
                .getInt("editor." + owner + "." + selected.name(), 0)));
    }

    /** Only explicit vertical settings forms are transformed. Canvas/preview siblings stay put. */
    public static View wrap(Context context, View content, String owner) {
        if (!(content instanceof ScrollView) && !(content instanceof NestedScrollView)) return content;
        ViewGroup scroll = (ViewGroup) content;
        if (scroll.getChildCount() != 1 || !(scroll.getChildAt(0) instanceof LinearLayout)) return content;
        LinearLayout form = (LinearLayout) scroll.getChildAt(0);
        if (form.getOrientation() != VERTICAL || form.getChildCount() < 4) return content;
        return new SettingsEditorLayout(context, content, form, owner);
    }

    public static void install(View root, String owner) {
        if (!(root instanceof ViewGroup) || root instanceof SettingsEditorLayout
                || root.getClass().getName().startsWith("dezz.")
                && !root.getClass().getName().contains("Settings")) return;
        ViewGroup parent = (ViewGroup) root;
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child instanceof ScrollView || child instanceof NestedScrollView) {
                ViewGroup.LayoutParams params = child.getLayoutParams();
                parent.removeViewAt(index);
                View replacement = wrap(root.getContext(), child, owner + "." + index);
                parent.addView(replacement, index, params);
                if (replacement != child && parent instanceof LinearLayout
                        && ((LinearLayout) parent).getOrientation() == HORIZONTAL
                        && parent.getChildCount() == 2) {
                    for (int sibling = 0; sibling < 2; sibling++) {
                        View pane = parent.getChildAt(sibling);
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pane.getLayoutParams();
                        lp.width = 0; lp.weight = pane == replacement ? .62f : .38f; pane.setLayoutParams(lp);
                    }
                }
                if (replacement == child) install(child, owner + "." + index);
            } else install(child, owner + "." + index);
        }
    }

    private void organize() {
        organizing = true;
        List<View> controls = new ArrayList<>();
        for (int i = 0; i < form.getChildCount(); i++) {
            View child = form.getChildAt(i);
            if (child instanceof FieldGroup) {
                FieldGroup group = (FieldGroup) child;
                while (group.getChildCount() > 0) {
                    View field = group.getChildAt(0); group.removeViewAt(0); controls.add(field);
                }
            } else controls.add(child);
        }
        form.removeAllViews(); groups.clear();
        SettingsSection section = SettingsSection.MAIN;
        FieldGroup last = null;
        for (View control : controls) {
            String label = label(control);
            boolean unlabelledInput = label.isEmpty() && (control instanceof EditText
                    || control instanceof Spinner || control instanceof SeekBar
                    || CONTROL_ROW_TAG.equals(control.getTag()));
            if (!unlabelledInput || last == null) {
                section = SettingsEditorCatalog.section(getContext(), label, section);
                last = new FieldGroup(getContext(), section);
                last.setPadding(dp(14), dp(8), dp(14), dp(8));
                GradientDrawable background = new GradientDrawable();
                background.setColor(color(R.color.settings_group_background));
                background.setCornerRadius(dp(12));
                last.setBackground(background);
                LayoutParams params = new LayoutParams(-1, -2); params.bottomMargin = dp(8);
                form.addView(last, params); groups.add(last);
            }
            ViewGroup.LayoutParams params = control.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
                if (lp.height == 0 && lp.weight > 0) { lp.height = -2; lp.weight = 0; }
            }
            last.addView(control, params == null ? new LayoutParams(-1, -2) : params);
        }
        if (!hasSection(selected)) {
            for (SettingsSection possible : SettingsSection.values()) if (hasSection(possible)) { selected = possible; break; }
        }
        select(selected, false); organizing = false;
    }

    private boolean hasSection(SettingsSection section) {
        for (FieldGroup group : groups) if (group.section == section) return true;
        return false;
    }
    private void select(SettingsSection section, boolean rememberPosition) {
        if (rememberPosition) positions.put(selected, scroll.getScrollY());
        selected = section;
        for (FieldGroup group : groups) group.setVisibility(group.section == section ? VISIBLE : GONE);
        for (Map.Entry<SettingsSection, Button> entry : buttons.entrySet()) {
            Button button = entry.getValue(); boolean active = entry.getKey() == section;
            button.setVisibility(hasSection(entry.getKey()) ? VISIBLE : GONE);
            button.setSelected(active);
            button.setTextColor(color(active ? R.color.settings_accent : R.color.text_primary));
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(
                    active ? R.color.settings_group_background : R.color.settings_background)));
        }
        if (rememberPosition) scroll.post(() -> scroll.scrollTo(0, positions.containsKey(section)
                ? positions.get(section) : SettingsAppearance.preferences(getContext())
                .getInt("editor." + owner + "." + section.name(), 0)));
    }
    public void reveal(View field) {
        View ancestor = field;
        while (ancestor.getParent() instanceof View && !(ancestor instanceof FieldGroup)) ancestor = (View) ancestor.getParent();
        if (ancestor instanceof FieldGroup) select(((FieldGroup) ancestor).section, true);
    }
    @Override protected void onDetachedFromWindow() {
        positions.put(selected, scroll.getScrollY());
        android.content.SharedPreferences.Editor editor = SettingsAppearance.preferences(getContext()).edit();
        editor.putString("editor." + owner + ".section", selected.name());
        for (Map.Entry<SettingsSection, Integer> position : positions.entrySet())
            editor.putInt("editor." + owner + "." + position.getKey().name(), position.getValue());
        editor.apply(); super.onDetachedFromWindow();
    }
    private static String label(View view) {
        if (view instanceof EditText || view instanceof Spinner || view instanceof SeekBar) return "";
        if (view instanceof TextView) return ((TextView) view).getText().toString();
        if (CONTROL_ROW_TAG.equals(view.getTag())) return "";
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String text = label(group.getChildAt(i)); if (!text.isEmpty()) return text;
            }
        }
        return "";
    }
    private static boolean hasBackControl(View view) {
        if (view instanceof Button) {
            String text = ((Button)view).getText().toString();
            return text.equals("‹") || text.startsWith("←") || text.equals("Назад");
        }
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++)
            if (hasBackControl(((ViewGroup)view).getChildAt(i))) return true;
        return false;
    }
    private int color(int id) { return ContextCompat.getColor(getContext(), id); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
