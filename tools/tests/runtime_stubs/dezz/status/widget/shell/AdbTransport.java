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
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("NATRO_EXIT_[0-9a-f]{32}").matcher(wrapped);
        if (!match.find()) throw new IllegalArgumentException("Missing shell framing nonce");
        String marker = match.group();
        output.accept(("device:/ $ " + wrapped.replace("\n", "\\n") + "\r\n\r\n"
                + marker + "_BEGIN\r\n" + (root?"0":"2000") + "\r\n" + marker+":0\r\n").getBytes(StandardCharsets.UTF_8));
    }
    public void readService(String service,Consumer<byte[]> output) throws IOException {
        services.add(service);
        if(!refuse)root=service.equals("root:");
        if(dropRootReply)throw new IOException("daemon restarted before reply");
        output.accept((refuse?"adbd cannot run as root in production builds":"restarting adbd").getBytes(StandardCharsets.UTF_8));
    }
    public void close() {}
}
