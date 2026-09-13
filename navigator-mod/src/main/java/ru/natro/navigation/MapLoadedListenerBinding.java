/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.lang.ref.WeakReference;

/** Exact listener ABI of Map/MapBinding in the reviewed Navigator 30.3.0 DEX. */
final class MapLoadedListenerBinding {
    private MapLoadedListenerBinding() {}

    /**
     * The Java parameter is WeakReference, not MapLoadedListener. Keep the listener itself
     * strongly owned by the renderer until detach. An empty reference clears the native listener.
     */
    static void set(Object map, Object listener) throws Exception {
        ReflectMethods.publicMethod(map.getClass(), "setMapLoadedListener",
                new Class<?>[]{WeakReference.class}).invoke(map, new WeakReference<>(listener));
    }
}
