/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/** Small reflection cache for the public MapKit API calls used on live navigation updates. */
final class ReflectMethods {
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>>
            NO_ARGUMENT_METHODS = new ConcurrentHashMap<>();

    private ReflectMethods() {}

    static Method publicMethod(Class<?> owner, String name, Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        if (parameterTypes.length != 0) {
            Method method = owner.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        }
        ConcurrentHashMap<String, Method> methods = NO_ARGUMENT_METHODS.get(owner);
        if (methods == null) {
            ConcurrentHashMap<String, Method> created = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Method> existing =
                    NO_ARGUMENT_METHODS.putIfAbsent(owner, created);
            methods = existing == null ? created : existing;
        }
        Method cached = methods.get(name);
        if (cached != null) return cached;
        Method resolved = owner.getMethod(name);
        resolved.setAccessible(true);
        Method existing = methods.putIfAbsent(name, resolved);
        return existing == null ? resolved : existing;
    }
}
