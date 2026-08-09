/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.launcher.LauncherIconResolver;

/**
 * One visual, offline-only icon chooser shared by every configurable panel.
 *
 * <p>Unlike a text {@code setItems()} dialog, every cell renders the actual VectorDrawable. The
 * small caption is retained for accessibility and to distinguish semantically similar car
 * controls. Persistent values remain stable catalog keys; no path, URI or downloaded image can
 * enter a configuration through this dialog.</p>
 */
public final class VectorIconPickerDialog {
    public interface Listener {
        void onSelected(@NonNull Option option);
    }

    public static final class Option {
        @NonNull public final String key;
        @NonNull public final String label;
        @DrawableRes public final int drawableRes;

        public Option(@NonNull String key, @NonNull String label,
                      @DrawableRes int drawableRes) {
            this.key = key;
            this.label = label;
            this.drawableRes = drawableRes;
        }
    }

    private static final List<Option> CATALOG = buildCatalog();

    private VectorIconPickerDialog() {}

    /** Full immutable vector catalog, in the same stable order as LauncherIconResolver. */
    @NonNull public static List<Option> catalog() { return CATALOG; }

    /** Adds a semantic option such as auto/live while keeping all real icon previews visible. */
    @NonNull public static List<Option> withFirst(@NonNull Option... leading) {
        List<Option> values = new ArrayList<>(CATALOG.size() + leading.length);
        Collections.addAll(values, leading);
        values.addAll(CATALOG);
        return Collections.unmodifiableList(values);
    }

    @NonNull public static Option option(@NonNull String key, @NonNull String label,
                                         @DrawableRes int drawableRes) {
        return new Option(key, label, drawableRes);
    }

    @Nullable public static Option find(@NonNull List<Option> options, @Nullable String key) {
        if (key == null) return null;
        for (Option option : options) if (option.key.equals(key)) return option;
        return null;
    }

    public static void show(@NonNull Context context, @NonNull String title,
                            @Nullable String selectedKey, @NonNull Listener listener) {
        show(context, title, CATALOG, selectedKey, listener);
    }

    public static void show(@NonNull Context context, @NonNull String title,
                            @NonNull List<Option> options, @Nullable String selectedKey,
                            @NonNull Listener listener) {
        RecyclerView grid = new RecyclerView(context);
        int horizontal = dp(context, 10);
        grid.setPadding(horizontal, dp(context, 8), horizontal, dp(context, 8));
        grid.setClipToPadding(false);
        grid.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        int availableWidth = context.getResources().getDisplayMetrics().widthPixels;
        int columns = Math.max(4, Math.min(7, availableWidth / dp(context, 112)));
        grid.setLayoutManager(new GridLayoutManager(context, columns));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        grid.setAdapter(new IconAdapter(options, selectedKey, option -> {
            listener.onSelected(option);
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        }));
        int selectedPosition = indexOf(options, selectedKey);
        if (selectedPosition >= 0) grid.post(() -> grid.scrollToPosition(selectedPosition));

        int height = Math.min(dp(context, 620),
                Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.68f));
        grid.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(context, 320), height)));
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    /** Gives form buttons the same real icon preview as the grid instead of a name-only value. */
    public static void decorate(@NonNull TextView view, @NonNull Option option) {
        Drawable icon = ContextCompat.getDrawable(view.getContext(), option.drawableRes);
        if (icon != null) {
            icon = DrawableCompat.wrap(icon).mutate();
            DrawableCompat.setTint(icon, view.getCurrentTextColor());
            int size = dp(view.getContext(), 30);
            icon.setBounds(0, 0, size, size);
        }
        view.setCompoundDrawablesRelative(icon, null, null, null);
        view.setCompoundDrawablePadding(dp(view.getContext(), 12));
        view.setText(option.label);
        view.setContentDescription("Иконка: " + option.label);
    }

    @NonNull private static List<Option> buildCatalog() {
        List<Option> values = new ArrayList<>();
        for (LauncherIconResolver.Preset preset : LauncherIconResolver.presets()) {
            values.add(new Option(preset.key, preset.label,
                    LauncherIconResolver.resource(preset.key)));
        }
        return Collections.unmodifiableList(values);
    }

    private static int indexOf(@NonNull List<Option> options, @Nullable String key) {
        if (key == null) return -1;
        for (int index = 0; index < options.size(); index++) {
            if (key.equals(options.get(index).key)) return index;
        }
        return -1;
    }

    private static final class IconAdapter extends RecyclerView.Adapter<IconHolder> {
        @NonNull private final List<Option> options;
        @Nullable private final String selectedKey;
        @NonNull private final Listener listener;

        private IconAdapter(@NonNull List<Option> options, @Nullable String selectedKey,
                            @NonNull Listener listener) {
            this.options = options;
            this.selectedKey = selectedKey;
            this.listener = listener;
            setHasStableIds(true);
        }

        @Override public long getItemId(int position) {
            return options.get(position).key.hashCode();
        }

        @NonNull @Override public IconHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                                                 int viewType) {
            Context context = parent.getContext();
            MaterialCardView card = new MaterialCardView(context);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 112));
            int margin = dp(context, 5);
            lp.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(lp);
            card.setRadius(dp(context, 18));
            card.setClickable(true);
            card.setFocusable(true);
            card.setUseCompatPadding(false);

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(context, 6), dp(context, 9), dp(context, 6),
                    dp(context, 7));
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            content.addView(image, new LinearLayout.LayoutParams(dp(context, 48),
                    dp(context, 48)));
            TextView label = new TextView(context);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(10.5f);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            labelLp.topMargin = dp(context, 5);
            content.addView(label, labelLp);
            card.addView(content, new MaterialCardView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new IconHolder(card, image, label);
        }

        @Override public void onBindViewHolder(@NonNull IconHolder holder, int position) {
            Context context = holder.itemView.getContext();
            Option option = options.get(position);
            boolean selected = option.key.equals(selectedKey);
            int onSurface = MaterialColors.getColor(holder.card,
                    com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
            int accent = MaterialColors.getColor(holder.card,
                    com.google.android.material.R.attr.colorPrimary, 0xFF0A84FF);
            int surface = MaterialColors.getColor(holder.card,
                    com.google.android.material.R.attr.colorSurface, 0xFF20242B);
            holder.card.setCardBackgroundColor(ColorStateList.valueOf(surface));
            holder.card.setStrokeWidth(dp(context, selected ? 2 : 1));
            holder.card.setStrokeColor(selected ? accent
                    : Color.argb(70, Color.red(onSurface), Color.green(onSurface),
                    Color.blue(onSurface)));
            holder.label.setTextColor(selected ? accent : onSurface);
            holder.label.setText(option.label);
            Drawable drawable = ContextCompat.getDrawable(context, option.drawableRes);
            if (drawable != null) {
                drawable = DrawableCompat.wrap(drawable).mutate();
                DrawableCompat.setTint(drawable, selected ? accent : onSurface);
            }
            holder.icon.setImageDrawable(drawable);
            holder.card.setContentDescription("Иконка: " + option.label
                    + (selected ? ", выбрана" : ""));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                holder.card.setAccessibilityPaneTitle(option.label);
            }
            holder.card.setOnClickListener(view -> listener.onSelected(option));
        }

        @Override public int getItemCount() { return options.size(); }
    }

    private static final class IconHolder extends RecyclerView.ViewHolder {
        @NonNull final MaterialCardView card;
        @NonNull final ImageView icon;
        @NonNull final TextView label;

        private IconHolder(@NonNull MaterialCardView card, @NonNull ImageView icon,
                           @NonNull TextView label) {
            super(card);
            this.card = card;
            this.icon = icon;
            this.label = label;
        }
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
