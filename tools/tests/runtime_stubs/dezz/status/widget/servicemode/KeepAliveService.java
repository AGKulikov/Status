package dezz.status.widget.servicemode;
import android.content.Context;
public final class KeepAliveService { public static int stops; public static void stop(Context c) { stops++; } }
