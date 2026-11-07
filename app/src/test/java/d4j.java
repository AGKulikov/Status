/* SPDX-License-Identifier: GPL-3.0-or-later */
import android.content.Context;
import android.media.session.MediaSession;
import android.os.Handler;
import java.util.HashMap;

/** Synthetic registry shape for the pinned native adapter; contains no vendor implementation. */
public final class d4j {
    public static final Object b=new Object();
    public static final HashMap<String,d4j> c=new HashMap<>();
    public Impl a;
    public static final class Impl {
        public final Object a=new Object();
        public Context f; public Handler l; public Legacy h; public boolean y;
    }
    public static final class Legacy { public Compat l; }
    public static final class Compat { public Platform a; }
    public static final class Platform { public MediaSession a; }
    public static Object install(Context context,Handler handler) {
        d4j session=new d4j(); session.a=new Impl(); session.a.f=context; session.a.l=handler;
        session.a.h=new Legacy(); session.a.h.l=new Compat(); session.a.h.l.a=new Platform();
        session.a.h.l.a.a=new MediaSession(context,"fixture");
        c.put("fixture",session); return session;
    }
    public static void clear() {
        for(d4j session:c.values()) if(session.a!=null) session.a.h.l.a.a.release();
        c.clear();
    }
    public static void release() { c.get("fixture").a.y=true; }
}
