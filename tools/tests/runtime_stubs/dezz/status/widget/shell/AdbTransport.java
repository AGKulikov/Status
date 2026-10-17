package dezz.status.widget.shell;
import android.content.Context;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
/** Deterministic daemon simulation; tests the real console policy, not an actual adbd. */
public class AdbTransport {
    public void collectQnx(dezz.status.widget.diagnostics.QnxFileReader.Receiver receiver) throws IOException {
        throw new IOException("QNX unavailable in daemon policy fixture");
    }
    public static final List<String> services = new ArrayList<>(), commands = new ArrayList<>();
    public static boolean root, refuse, dropRootReply;
    public static String partialOutput;
    public static Runnable afterOutput;
    public static boolean failAfterOutput;
    public static void reset() { root=false;refuse=false;dropRootReply=false;services.clear();commands.clear(); partialOutput=null;afterOutput=null;failAfterOutput=false; }
    public static boolean probe(String host,int port,Consumer<Socket> sink) { sink.accept(new Socket());return true; }
    public static AdbTransport connect(Context c,String host,int port,Consumer<Socket> sink) { sink.accept(new Socket());return new AdbTransport(); }
    public void execRaw(String wrapped,Consumer<byte[]> output) throws IOException {
        commands.add(wrapped);
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("NATRO_EXIT_[0-9a-f]{32}").matcher(wrapped);
        if (!match.find()) throw new IllegalArgumentException("Missing shell framing nonce");
        String marker = match.group();
        if (partialOutput != null) {
            for (byte b : ("\n" + marker + "_BEGIN\n" + partialOutput
                    + (failAfterOutput ? "" : "\n" + marker + ":0\n")).getBytes(StandardCharsets.UTF_8))
                output.accept(new byte[]{b});
            if (afterOutput != null) afterOutput.run();
            if (failAfterOutput) throw new IOException("synthetic transport failure");
            return;
        }
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
