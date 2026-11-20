/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Bitmap;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** The original turn texture is a candidate until it also matches this window's visible map. */
public final class StockTurnTextures {
    private static final String FACTORY = "com.yandex.navikit.ui.balloons.BalloonFactory";
    private static final String VIEWS = "ru.yandex.yandexnavi.ui.guidance.context.";
    private static final int MAX_TEXTURES = 8;
    private static final int MAX_PIXELS = 512 * 1024;
    private static final ArrayList<Capture> captures = new ArrayList<>();
    private static long sequence;

    static final class Texture {
        final Bitmap bitmap;
        final Object provider;
        final float anchorX, anchorY;
        final boolean combined;
        final long revision;
        // Identity of the native view, independent of its changing distance/texture.
        // A token does not keep the view, its Context or its window alive.
        final Object source;
        final CombinedLaneArtwork artwork;

        Texture(Bitmap bitmap, Object provider, float anchorX, float anchorY,
                boolean combined, long revision, CombinedLaneArtwork artwork, Object source) {
            this.source = source;
            this.artwork = artwork;
            this.bitmap = bitmap;
            this.provider = provider;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.combined = combined;
            this.revision = revision;
        }
    }

    private static final class Capture {
        final WeakReference<Object> window;
        final WeakReference<Object> view;
        final Texture texture;

        Capture(WeakReference<Object> window, Object owner, Texture texture) {
            this.window = window;
            this.view = new WeakReference<>(owner);
            this.texture = texture;
        }
    }

    /** Called at the reviewed NaviGuidanceLayerFactory callsite, with its real window and factory. */
    public static Object wrapFactory(Object mapWindow, Object delegate) {
        if (mapWindow == null || delegate == null) return delegate;
        try {
            ClassLoader loader = delegate.getClass().getClassLoader();
            Class<?> type = Class.forName(FACTORY, false, loader);
            if (!type.isInstance(delegate)) return delegate;
            WeakReference<Object> sourceWindow = new WeakReference<>(mapWindow);
            return forward(type, delegate, (method, result, proxy) -> {
                boolean combined = method.getName().equals("createLaneAndManeuverBalloonView");
                if (!combined && !method.getName().equals("createManeuverBalloonView")) return result;
                String owner = VIEWS + (combined ? "LaneAndManeuverBalloonViewImpl" : "ManeuverBalloonViewImpl");
                if (result == null || !result.getClass().getName().equals(owner)) return result;
                return observeView(method.getReturnType(), result, sourceWindow, combined);
            });
        } catch (Throwable unavailable) {
            return delegate;
        }
    }

    private static Object observeView(Class<?> api, Object view, WeakReference<Object> window,
                                      boolean combined) throws ReflectiveOperationException {
        Method anchorMethod = api.getMethod("getAnchor");
        Class<?> point = anchorMethod.getReturnType();
        Method x = point.getMethod("getX"), y = point.getMethod("getY");
        Method image = api.getMethod("createTexture").getReturnType().getMethod("getImage");
        Object source = new Object();
        return forward(api, view, (method, provider, owner) -> {
            if (method.getName().equals("createTexture") && window.get() != null) {
                // The pinned createTexture() allocates a new ARGB bitmap, draws it once and returns
                // ImageProvider.fromBitmap. Read the matching normalized anchor after that layout.
                // A failed observation of a replacement must relinquish the previous texture.
                boolean recorded = false;
                try {
                    if (provider == null) return null;
                    Object anchor = anchorMethod.invoke(view);
                    float ax = ((Number) x.invoke(anchor)).floatValue();
                    float ay = ((Number) y.invoke(anchor)).floatValue();
                    Bitmap bitmap = (Bitmap) image.invoke(provider);
                    if (ax >= 0 && ax <= 1 && ay >= 0 && ay <= 1 && bitmap != null
                            && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0
                            && (long) bitmap.getWidth() * bitmap.getHeight() <= MAX_PIXELS) {
                        record(window, owner, bitmap, provider, ax, ay, combined, view, source);
                        recorded = true;
                    }
                } finally {
                    if (!recorded) remove(owner);
                }
            }
            return provider;
        });
    }

    private interface Observation {
        Object after(Method method, Object original, Object proxy) throws ReflectiveOperationException;
    }

    private static Object forward(Class<?> api, Object original, Observation observation) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return original.toString();
            }
            Object result;
            try {
                result = method.invoke(original, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            try {
                return observation.after(method, result, proxy);
            } catch (Throwable unavailable) {
                // A failed observation has no effect on Navigator's original render.
                return result;
            }
        };
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, handler);
    }

    private static synchronized void record(WeakReference<Object> window, Object owner, Bitmap bitmap,
                                            Object provider, float ax, float ay, boolean combined, Object view, Object source) {
        for (Capture capture : captures) {
            Texture old = capture.texture;
            if (capture.view.get() == owner && capture.window.get() == window.get()
                    && old.anchorX == ax && old.anchorY == ay && old.combined == combined
                    && !old.bitmap.isRecycled() && old.bitmap.sameAs(bitmap)) return;
        }
        CombinedLaneArtwork artwork = CombinedLaneArtwork.capture(view, bitmap);
        remove(owner);
        if (captures.size() == MAX_TEXTURES) captures.remove(0);
        captures.add(new Capture(window, owner, new Texture(bitmap, provider, ax, ay, combined, ++sequence, artwork, source)));
    }

    private static synchronized void remove(Object owner) {
        Iterator<Capture> it = captures.iterator();
        while (it.hasNext()) {
            Capture c = it.next();
            Object v = c.view.get();
            if (v == null || v == owner || c.window.get() == null) it.remove();
        }
    }

    static synchronized List<Texture> forWindow(Object primaryMapWindow) {
        remove(null);
        if (primaryMapWindow == null) return Collections.emptyList();
        ArrayList<Texture> result = new ArrayList<>();
        for (Capture c : captures) {
            if (c.window.get() == primaryMapWindow && !c.texture.bitmap.isRecycled()) result.add(c.texture);
        }
        return Collections.unmodifiableList(result);
    }

    static synchronized void clearWindow(Object primaryMapWindow) {
        for (Iterator<Capture> it = captures.iterator(); it.hasNext();) {
            Capture c = it.next();
            if (c.window.get() == null || c.window.get() == primaryMapWindow || c.view.get() == null) it.remove();
        }
    }

    private StockTurnTextures() {}
}
