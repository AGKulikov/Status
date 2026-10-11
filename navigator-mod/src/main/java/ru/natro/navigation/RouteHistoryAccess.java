/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.Map;

/** Version-pinned, fail-closed adapter. Borrows the stock account's database, never opens/closes it. */
final class RouteHistoryAccess {
    static final String DATABASE_ID = ".ext.maps_common@ymapspointshistory1";
    static final String COLLECTION_ID = "pointshistory";
    private static final String PREFIX = "ru.yandex.yandexmaps.multiplatform.datasync.wrapper.";
    static Object call(Object target, String name) throws Exception {
        if (target == null) throw new IllegalStateException("not ready");
        return ReflectMethods.publicMethod(target.getClass(), name, new Class<?>[0]).invoke(target);
    }
    static Object stringCall(Object target, String name, String argument) throws Exception {
        return ReflectMethods.publicMethod(target.getClass(), name, new Class<?>[]{String.class})
                .invoke(target, argument);
    }
    static void listenerCall(Object target, String name, String type, Object listener) throws Exception {
        Class<?> api = Class.forName(type, false, target.getClass().getClassLoader());
        ReflectMethods.publicMethod(target.getClass(), name, new Class<?>[]{api}).invoke(target, listener);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static Object typedField(Object target, String type) throws Exception {
        Field match = null;
        for (Field field : target.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals(type)) {
                if (match != null) throw new IllegalStateException("ambiguous field");
                match = field;
            }
        }
        if (match == null) throw new NoSuchFieldException(type);
        match.setAccessible(true);
        return match.get(target);
    }
    static Object resolve(Object application) throws Exception {
        Object component = call(application, "c");
        // Stock routes component Oe() reads this exact singleton provider. Do not walk
        // arbitrary providers/getters: they can instantiate unrelated application services.
        if (!component.getClass().getName().equals("ru.yandex.yandexmaps.app.di.components.a0")) {
            throw new IllegalStateException("unknown application component");
        }
        Object service = call(field(component, "v2"), "get");
        if (service == null || !service.getClass().getName().equals("ru.yandex.yandexmaps.datasync.c")) {
            throw new IllegalStateException("unknown datasync service");
        }
        Object wrapper = call(service, "g");
        Object holder = typedField(wrapper, PREFIX + "internal.m1");
        Object bindings = field(holder, "g");
        if (!(bindings instanceof Map)) throw new IllegalStateException("unknown bindings");
        Object found = null;
        for (Object binding : ((Map<?, ?>) bindings).values()) {
            if (!binding.getClass().getName().equals(PREFIX + "internal.c1")) continue;
            if (!DATABASE_ID.equals(call(binding, "q")) || call(binding, "o") == null) continue;
            Object nativeWrapper = call(field(binding, "f"), "getValue");
            if (nativeWrapper == null) continue;
            if (!nativeWrapper.getClass().getName().equals(PREFIX + "internal.f2")) {
                throw new IllegalStateException("unknown database wrapper");
            }
            Object database = typedField(nativeWrapper, "com.yandex.datasync.Database");
            if (!DATABASE_ID.equals(call(database, "getDatabaseId"))
                    || !Boolean.TRUE.equals(call(database, "isValid"))) continue;
            if (found != null && found != database) throw new IllegalStateException("ambiguous account");
            found = database;
        }
        return found;
    }
    static Object proxy(Object owner, String type, InvocationHandler callback) throws Exception {
        ClassLoader loader = owner.getClass().getClassLoader();
        return Proxy.newProxyInstance(loader, new Class<?>[]{Class.forName(type, false, loader)},
                (self, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("hashCode")) return System.identityHashCode(self);
                        if (method.getName().equals("equals")) return self == args[0];
                        return "NatroRouteHistoryListener";
                    }
                    return callback.invoke(self, method, args);
                });
    }
    static RouteHistoryDiff.Item read(Object record) throws Exception {
        String id = (String) call(record, "recordId");
        String title = (String) stringCall(record, "fieldAsString", "title");
        double latitude = ((Number) stringCall(record, "fieldAsDouble", "latitude")).doubleValue();
        double longitude = ((Number) stringCall(record, "fieldAsDouble", "longitude")).doubleValue();
        long lastUsed = ((Number) call(stringCall(record, "fieldAsTimestamp", "last_used"), "getValue")).longValue();
        return new RouteHistoryDiff.Item(id, title, latitude, longitude, lastUsed);
    }
}
