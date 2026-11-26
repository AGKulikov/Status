package dezz.status.widget.shell;

import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.SocketFactory;
import com.tananaev.adblib.AdbStream;
import dezz.status.widget.qnx.QnxSsh;
import java.io.*;
import java.net.Socket;
import java.util.Arrays;

/** Raw TCP carried by authenticated adbd, with SSH providing end-to-end authentication. */
final class AdbSshProxy implements Proxy {
    interface Opener { AdbStream open(String service) throws Exception; }
    private final Opener opener;
    private AdbStream stream;
    AdbSshProxy(Opener opener) { this.opener = opener; }
    public void connect(SocketFactory unused, String host, int port, int timeout) throws Exception {
        if (!QnxSsh.HOST.equals(host) || port != QnxSsh.PORT) throw new IOException("Unexpected QNX endpoint");
        // Preserve the head unit's shell-UID route. exec: provides a raw pipe for SSH packets.
        stream = opener.open("exec:toybox nc -w 30 " + QnxSsh.HOST + " " + QnxSsh.PORT);
    }
    public Socket getSocket() { return null; }
    public InputStream getInputStream() {
        return new InputStream() {
            byte[] packet = new byte[0];
            int offset;
            public int read() throws IOException {
                byte[] single = new byte[1];
                return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
            }
            public int read(byte[] dst, int start, int length) throws IOException {
                if (length == 0) return 0;
                while (offset == packet.length) {
                    try { packet = stream.read(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                    offset = 0;
                    if (packet == null) return -1;
                }
                int count = Math.min(length, packet.length - offset);
                System.arraycopy(packet, offset, dst, start, count);
                offset += count;
                return count;
            }
        };
    }
    public OutputStream getOutputStream() {
        return new OutputStream() {
            public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
            public void write(byte[] bytes, int offset, int length) throws IOException {
                while (length > 0) {
                    int count = Math.min(length, 4096);
                    try { stream.write(Arrays.copyOfRange(bytes, offset, offset + count)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                    offset += count; length -= count;
                }
            }
        };
    }
    public void close() { if (stream != null) try { stream.close(); } catch (IOException ignored) {} }
}
