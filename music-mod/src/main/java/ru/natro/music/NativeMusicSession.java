/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.music;

import android.content.Context;
import android.media.session.MediaSession;
import android.os.Handler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

/** Read-only adapter for the pinned 2026.10.2 Media3 runtime. No library browsing or PLAY. */
final class NativeMusicSession {
    final Object session, implementation;
    final Handler handler;
    final MediaSession.Token token;
    private final Class<?> registry;

    private NativeMusicSession(Class<?> registry, Object session, Object implementation,
                               Handler handler, MediaSession.Token token) {
        this.registry=registry; this.session=session; this.implementation=implementation;
        this.handler=handler; this.token=token;
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        Class<?> type=owner instanceof Class ? (Class<?>)owner : owner.getClass();
        Field f=type.getField(name);
        return f.get(owner instanceof Class ? null : owner);
    }

    static NativeMusicSession find(Context context) throws ReflectiveOperationException {
        Class<?> registry=Class.forName("d4j", false, context.getClassLoader());
        Object lock=field(registry,"b");
        ArrayList<?> sessions;
        synchronized(lock) { sessions=new ArrayList<>(((Map<?,?>)field(registry,"c")).values()); }
        NativeMusicSession found=null;
        for(Object session:sessions) {
            Object impl=field(session,"a");
            // Media3 registers the object before its constructor has published the implementation.
            if(impl==null) continue;
            Object owner=field(impl,"f");
            if(!(owner instanceof Context)
                    || !context.getPackageName().equals(((Context)owner).getPackageName())) continue;
            synchronized(field(impl,"a")) { if(Boolean.TRUE.equals(field(impl,"y"))) continue; }
            Object handler=field(impl,"l");
            Object legacy=field(impl,"h");
            if(legacy==null || !(handler instanceof Handler)) continue;
            Object compat=field(legacy,"l");
            if(compat==null) continue;
            Object platform=field(field(compat,"a"),"a");
            if(!(platform instanceof MediaSession)) throw new IllegalStateException("native_token_type");
            if(found!=null) return null; // Never choose arbitrarily between multiple players.
            found=new NativeMusicSession(registry,session,impl,(Handler)handler,
                    ((MediaSession)platform).getSessionToken());
        }
        return found;
    }

    boolean current() throws ReflectiveOperationException {
        synchronized(field(registry,"b")) {
            if(!((Map<?,?>)field(registry,"c")).containsValue(session)) return false;
        }
        if(field(session,"a")!=implementation) return false;
        synchronized(field(implementation,"a")) {
            return !Boolean.TRUE.equals(field(implementation,"y"));
        }
    }
}
