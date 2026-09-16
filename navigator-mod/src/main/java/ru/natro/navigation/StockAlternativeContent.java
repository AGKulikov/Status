/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** Reuses the exact baseline's alternative content renderer, not a guessed HUD label. */
final class StockAlternativeContent {
    static final class Content {
        final String text;
        final int color;
        final boolean neutral;
        final Drawable icon;
        final String distanceText;
        final int distanceColor;
        Content(String text, int color, boolean neutral, Drawable icon) {
            this(text, color, neutral, icon, "", color);
        }
        Content(String text, int color, boolean neutral, Drawable icon, String distanceText, int distanceColor) {
            this.text = text; this.color = color; this.neutral = neutral; this.icon = icon;
            this.distanceText = distanceText; this.distanceColor = distanceColor;
        }
    }

    private final Context context;
    private Object factory;

    StockAlternativeContent(Context context) { this.context = context; }

    Content read(Object route, Object fork, Object currentRoute, Object currentFork,
                 boolean night) throws Exception {
        Class<?> valueClass = Class.forName("com.yandex.mapkit.LocalizedValue");
        Class<?> weightClass = Class.forName("com.yandex.mapkit.directions.driving.Weight");
        Class<?> flagsClass = Class.forName("com.yandex.mapkit.directions.driving.Flags");
        Class<?> featuresClass = Class.forName("com.yandex.mapkit.directions.driving.NonAvoidedFeatures");
        Class<?> summaryClass = Class.forName("com.yandex.mapkit.directions.driving.Summary");
        Class<?> alternativeClass = Class.forName("com.yandex.mapkit.navigation.automotive.layer.AlternativeBalloon");
        Class<?> balloonClass = Class.forName("com.yandex.mapkit.navigation.automotive.layer.Balloon");

        // Metadata is evaluated at the verified fork on THIS route; old passed tolls or a
        // nearby route's flags must not be attached to this alternative's balloon.
        Object metadata = metadataAt(route, fork);
        Object currentMetadata = metadataAt(currentRoute, currentFork);
        Object weight = get(metadata, "getWeight");
        Object currentWeight = get(currentMetadata, "getWeight");
        Object summary = summaryClass.getConstructor(weightClass, flagsClass, featuresClass)
                .newInstance(weight, get(metadata, "getFlags"), get(metadata, "getNonAvoidedFeatures"));
        double time = value(get(weight, "getTime")) - value(get(currentWeight, "getTime"));
        double trafficTime = number(get(fork, "timeToFinish")) - number(get(currentFork, "timeToFinish"));
        double distance = number(get(fork, "distanceToFinish")) - number(get(currentFork, "distanceToFinish"));
        // Exact 30.3.0 constructor order: time, timeWithTraffic, distance. The factory localizes
        // the numeric relative time itself through I18nManager; no handcrafted unit string.
        Object relative = weightClass.getConstructor(valueClass, valueClass, valueClass).newInstance(
                localized(valueClass, time), localized(valueClass, trafficTime), localized(valueClass, distance));
        Object alternative = alternativeClass.getConstructor(summaryClass, weightClass)
                .newInstance(summary, relative);
        Object balloon = balloonClass.getMethod("fromAlternative", alternativeClass).invoke(null, alternative);
        if (factory == null) {
            Class<?> colors = Class.forName("com.yandex.mapkit.styling.automotive.balloons.BalloonColors");
            factory = Class.forName("com.yandex.mapkit.styling.automotivenavigation.balloons.AlternativeBalloonTextureFactory")
                    .getConstructor(Context.class, colors).newInstance(context, null);
        }
        View view = (View) ReflectMethods.publicMethod(factory.getClass(), "createView",
                new Class<?>[]{balloonClass, boolean.class}).invoke(factory, balloon, night);
        int textId = context.getResources().getIdentifier("text_alternativeballoon_time_diff", "id", context.getPackageName());
        int iconId = context.getResources().getIdentifier("image_alternativeballoon_icon", "id", context.getPackageName());
        View textView = view.findViewById(textId);
        View iconView = view.findViewById(iconId);
        if (!(textView instanceof TextView)) throw new IllegalStateException("Stock alternative text unavailable");
        TextView text = (TextView) textView;
        String label = text.getText().toString();
        if (label.trim().isEmpty()) throw new IllegalStateException("Empty stock alternative text");
        Drawable icon = iconView instanceof ImageView && iconView.getVisibility() == View.VISIBLE
                ? ((ImageView) iconView).getDrawable() : null;
        // The factory reuses its private View. Retain independent original artwork, never that View.
        if (icon != null) {
            Drawable.ConstantState state = icon.getConstantState();
            if (state == null) throw new IllegalStateException("Stock alternative icon is not detachable");
            Drawable original = icon;
            icon = state.newDrawable(context.getResources()).mutate();
            icon.setState(original.getState().clone());
            icon.setLevel(original.getLevel());
            icon.setAlpha(original.getAlpha());
        }
        java.lang.reflect.Field threshold = factory.getClass().getDeclaredField("NEGLECTABLE_TIME_DIFFERENCE");
        threshold.setAccessible(true);
        // The projected MapKit factory has only a time field. Navigator's main balloon also
        // displays distance. Localize the verified fork's delta, never a guessed polyline length.
        return new Content(label, text.getCurrentTextColor(),
                Math.abs(trafficTime) <= threshold.getDouble(null), icon, localizeRelativeDistance(distance),
                distance < 0d ? StockAlternativePalette.negativeColor() : StockAlternativePalette.positiveColor());
    }

    private String localizeRelativeDistance(double meters) {
        long rounded = Math.round(Math.abs(meters));
        if (rounded == 0L) return "";
        try {
            if (rounded > Integer.MAX_VALUE) throw new IllegalArgumentException("distance_overflow");
            Object manager = Class.forName("com.yandex.runtime.i18n.I18nManagerFactory")
                    .getMethod("getI18nManagerInstance").invoke(null);
            Object localized = ReflectMethods.publicMethod(manager.getClass(),
                    "localizeDistance", new Class<?>[]{int.class}).invoke(manager, (int) rounded);
            if (!(localized instanceof String)) throw new IllegalArgumentException("distance_missing");
            String unitText = (String) localized;
            if (unitText.trim().isEmpty()) throw new IllegalArgumentException("distance_empty");
            String resource = "mapkit_styling_automotive_alternative_"
                    + (meters < 0d ? "minus_sign" : "plus_sign");
            int id = context.getResources().getIdentifier(resource, "string", context.getPackageName());
            if (id == 0) throw new IllegalStateException("distance_sign_resource");
            return context.getString(id) + unitText;
        } catch (Exception failure) {
            NavigationBridgeClient.reportDiagnostic("alternative-content distance=unavailable, reason="
                    + failure.getClass().getSimpleName() + ", time_preserved=true");
            return "";
        }
    }

    private static Object metadataAt(Object route, Object fork) throws Exception {
        String id = String.valueOf(get(route, "getRouteId"));
        Object position = ReflectMethods.publicMethod(fork.getClass(), "positionOnRoute",
                new Class<?>[]{String.class}).invoke(fork, id);
        if (position == null) throw new IllegalArgumentException("Alternative fork belongs to another route");
        Class<?> positionClass = Class.forName("com.yandex.mapkit.geometry.PolylinePosition");
        return ReflectMethods.publicMethod(route.getClass(), "metadataAt",
                new Class<?>[]{positionClass}).invoke(route, position);
    }

    private static Object localized(Class<?> type, double value) throws Exception {
        return type.getConstructor(double.class, String.class).newInstance(value, "");
    }
    private static double value(Object value) throws Exception { return number(get(value, "getValue")); }
    private static double number(Object value) {
        double number = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite alternative weight");
        return number;
    }
    private static Object get(Object target, String method) throws Exception {
        return ReflectMethods.publicMethod(target.getClass(), method, new Class<?>[0]).invoke(target);
    }
}
