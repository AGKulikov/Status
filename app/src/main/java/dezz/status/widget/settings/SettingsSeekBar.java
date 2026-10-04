/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import androidx.appcompat.app.AlertDialog;

/** A slider with accessible single-step controls and validated exact-value entry. */
public final class SettingsSeekBar extends SeekBar {
    private OnSeekBarChangeListener listener;
    private boolean userStep, decorated;
    private double minimum, step = 1;
    private String unit = "";
    private String firstLabel;
    private Button valueButton, minus, plus;

    public SettingsSeekBar(Context context) {
        super(context);
        super.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                updateValue();
                if (listener != null) listener.onProgressChanged(bar, progress, user || userStep);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                if (listener != null) listener.onStartTrackingTouch(bar);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (listener != null) listener.onStopTrackingTouch(bar);
            }
        });
    }
    public SettingsSeekBar(Context context, double minimum, double step, String unit) {
        this(context); domain(minimum, step, unit);
    }
    public void domain(double minimum, double step, String unit) {
        new SettingsNumericRange(minimum, step, getMax());
        this.minimum = minimum; this.step = step; this.unit = unit == null ? "" : unit;
        updateValue();
    }
    public SettingsSeekBar firstValue(String label) { firstLabel = label; updateValue(); return this; }
    @Override public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {
        this.listener = listener;
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled); updateValue();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!decorated) post(this::decorate);
    }
    private SettingsNumericRange range() { return new SettingsNumericRange(minimum, step, getMax()); }
    public void setProgressFromUser(int progress) {
        if (!isEnabled()) return;
        if (listener != null) listener.onStartTrackingTouch(this);
        userStep = true;
        try { setProgress(Math.max(getMin(), Math.min(getMax(), progress))); }
        finally { userStep = false; }
        if (listener != null) listener.onStopTrackingTouch(this);
    }
    private void decorate() {
        if (decorated || !(getParent() instanceof LinearLayout)) return;
        decorated = true;
        LinearLayout parent = (LinearLayout) getParent();
        ViewGroup.LayoutParams parentParams = parent.getLayoutParams();
        if (parentParams != null && parentParams.height > 0 && parentParams.height < dp(54)) {
            parentParams.height = dp(54); parent.setLayoutParams(parentParams);
        }
        int index = parent.indexOfChild(this);
        ViewGroup.LayoutParams old = getLayoutParams();
        parent.removeView(this);
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(SettingsEditorLayout.CONTROL_ROW_TAG);
        minus = button("−", "Уменьшить на один шаг");
        plus = button("+", "Увеличить на один шаг");
        valueButton = button("", "Точное значение");
        row.addView(minus, new LinearLayout.LayoutParams(dp(54), dp(54)));
        row.addView(this, new LinearLayout.LayoutParams(0, dp(54), 1));
        row.addView(valueButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(54)));
        row.addView(plus, new LinearLayout.LayoutParams(dp(54), dp(54)));
        if (old instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) old;
            if (params.height > 0 && params.height < dp(54)) params.height = dp(54);
        }
        parent.addView(row, index, old);
        minus.setOnClickListener(v -> setProgressFromUser(getProgress() - 1));
        plus.setOnClickListener(v -> setProgressFromUser(getProgress() + 1));
        valueButton.setOnClickListener(v -> editValue());
        updateValue();
    }
    private Button button(String text, String description) {
        Button button = new Button(getContext()); button.setAllCaps(false);
        button.setText(text); button.setTextSize(20); button.setContentDescription(description);
        button.setMinWidth(dp(54)); button.setMinimumWidth(dp(54));
        return button;
    }
    private void updateValue() {
        if (valueButton == null) return;
        valueButton.setText(firstLabel != null && getProgress() == getMin()
                ? firstLabel : range().format(getProgress()) + unit);
        valueButton.setEnabled(isEnabled());
        minus.setEnabled(isEnabled() && getProgress() > getMin());
        plus.setEnabled(isEnabled() && getProgress() < getMax());
    }
    private void editValue() {
        EditText field = new EditText(getContext()); field.setTextSize(22); field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field.setText(range().format(getProgress())); field.selectAll();
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext()).setTitle("Точное значение" + unit)
                .setMessage(range().format(getMin()) + " … " + range().format(getMax()))
                .setView(field).setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", null);
        if (firstLabel != null) builder.setNeutralButton(firstLabel, (d,w)->setProgressFromUser(getMin()));
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try { setProgressFromUser(range().progress(field.getText().toString())); dialog.dismiss(); }
                    catch (IllegalArgumentException error) { field.setError("Введите число в указанном диапазоне"); }
                }));
        dialog.show();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
