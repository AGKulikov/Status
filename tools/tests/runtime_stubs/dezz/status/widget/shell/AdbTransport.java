package dezz.status.widget.shell;
import android.content.Context;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
/** Deterministic daemon simulation; tests the real console policy, not an actual adbd. */
public class AdbTransport {
    public static final List<String> services = new ArrayList<>(), commands = new ArrayList<>();
    public static boolean root, refuse, dropRootReply;
    public static void reset() { root=false;refuse=false;dropRootReply=false;services.clear();commands.clear(); }
    public static boolean probe(String host,int port,Consumer<Socket> sink) { sink.accept(new Socket());return true; }
    public static AdbTransport connect(Context c,String host,int port,Consumer<Socket> sink) { sink.accept(new Socket());return new AdbTransport(); }
    public void execRaw(String wrapped,Consumer<byte[]> output) {
        commands.add(wrapped);
        String marker = wrapped.substring(wrapped.indexOf("NATRO_EXIT_"));marker=marker.substring(0,marker.indexOf(':'));
        output.accept(((root?"0":"2000")+"\n\n"+marker+":0\n").getBytes(StandardCharsets.UTF_8));
    }
    public void readService(String service,Consumer<byte[]> output) throws IOException {
        services.add(service);
        if(!refuse)root=service.equals("root:");
        if(dropRootReply)throw new IOException("daemon restarted before reply");
        output.accept((refuse?"adbd cannot run as root in production builds":"restarting adbd").getBytes(StandardCharsets.UTF_8));
    }
    public void close() {}
}
