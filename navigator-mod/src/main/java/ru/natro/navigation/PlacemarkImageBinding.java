/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.lang.reflect.Proxy;

/** MapKit image upload completion, not tile readiness or a substitute for display verification. */
final class PlacemarkImageBinding {
    private PlacemarkImageBinding() {}

    /** Caller retains the returned callback for at least the life of this image request. */
    static Object load(Object placemark, Object provider, Object style, Runnable loaded) throws Exception {
        Class<?> callbackClass = Class.forName("com.yandex.mapkit.map.Callback");
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return "NatroPlacemarkImageLoaded";
                    }
                    if ("onTaskFinished".equals(method.getName())) loaded.run();
                    return null;
                });
        ReflectMethods.publicMethod(placemark.getClass(), "setIcon", new Class<?>[]{
                Class.forName("com.yandex.runtime.image.ImageProvider"),
                Class.forName("com.yandex.mapkit.map.IconStyle"), callbackClass})
                .invoke(placemark, provider, style, callback);
        return callback;
    }
}
