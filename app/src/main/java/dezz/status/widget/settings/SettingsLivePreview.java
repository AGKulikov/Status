/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.*;

/** Observes controls without replacing any of their validation or visibility listeners. */
public final class SettingsLivePreview {
    private static final ThreadLocal<Boolean> running = new ThreadLocal<>();
    private SettingsLivePreview() {}
    public static boolean running() { return Boolean.TRUE.equals(running.get()); }
    public static void bind(View controls, Runnable updateDraft) {
        final String[] previous = {fingerprint(controls)};
        final boolean[] posted = {false};
        Runnable update = () -> {
            posted[0] = false;
            if (!controls.isAttachedToWindow()) return;
            running.set(true);
            try { updateDraft.run(); }
            catch (RuntimeException invalid) { android.util.Log.w("SettingsPreview", "Incomplete draft", invalid); }
            finally { running.remove(); }
        };
        ViewTreeObserver.OnPreDrawListener observer = () -> {
            String current = fingerprint(controls);
            if (!current.equals(previous[0])) {
                previous[0] = current;
                if (!posted[0]) { posted[0] = true; controls.post(update); }
            }
            return true;
        };
        controls.getViewTreeObserver().addOnPreDrawListener(observer);
        controls.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { previous[0] = fingerprint(controls); }
            @Override public void onViewDetachedFromWindow(View view) {
                view.removeCallbacks(update);
                if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnPreDrawListener(observer);
            }
        });
    }
    private static String fingerprint(View root) {
        StringBuilder text = new StringBuilder(); append(root, text); return text.toString();
    }
    private static void append(View view, StringBuilder text) {
        if (SettingsEditorLayout.PREVIEW_TAG.equals(view.getTag())) return;
        if (SettingsEditorLayout.CONTROL_ROW_TAG.equals(view.getTag()) && view instanceof ViewGroup) {
            for(int i=0;i<((ViewGroup)view).getChildCount();i++)
                if(((ViewGroup)view).getChildAt(i) instanceof SeekBar)append(((ViewGroup)view).getChildAt(i),text);
            return;
        }
        boolean field=view instanceof CompoundButton||view instanceof SeekBar||view instanceof Spinner
                ||view instanceof EditText||view instanceof Button;
        if(field)text.append(System.identityHashCode(view)).append(':');
        if (view instanceof CompoundButton) text.append(((CompoundButton)view).isChecked());
        else if (view instanceof SeekBar) text.append(((SeekBar)view).getProgress());
        else if (view instanceof Spinner) text.append(((Spinner)view).getSelectedItemPosition());
        else if (view instanceof EditText || view instanceof Button) text.append(((TextView)view).getText());
        if(field)text.append(';');
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) append(((ViewGroup)view).getChildAt(i),text);
    }
}
