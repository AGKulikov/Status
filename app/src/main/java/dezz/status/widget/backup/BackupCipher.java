/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Ordered, bounded AEAD frames; a large image archive never requires one huge GCM buffer. */
public final class BackupCipher {
    private static final byte[] MAGIC = "NATROBK1".getBytes(StandardCharsets.US_ASCII);
    private static final int BLOCK = 1024 * 1024, ITERATIONS = 210000, HEADER = 36;
    private static final long MAX = BackupFiles.MAX_TOTAL_BYTES + 32L * 1024 * 1024;
    private BackupCipher() {}

    public static OutputStream encrypt(OutputStream output, char[] password) throws Exception {
        byte[] salt = new byte[16], nonce = new byte[8]; SecureRandom random = new SecureRandom();
        random.nextBytes(salt); random.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(HEADER).put(MAGIC).putInt(ITERATIONS).put(salt).put(nonce).array();
        byte[] key = key(password, salt, ITERATIONS);
        try { output.write(header); return new Frames(output, header, key, nonce); }
        catch (Exception failure) { Arrays.fill(key, (byte) 0); throw failure; }
    }

    public static void decrypt(InputStream input, OutputStream output, char[] password) throws Exception {
        DataInputStream data = new DataInputStream(input); byte[] header = new byte[HEADER]; data.readFully(header);
        ByteBuffer fields = ByteBuffer.wrap(header); byte[] magic = new byte[8]; fields.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("Неизвестный формат копии");
        int iterations = fields.getInt();
        if (iterations < 100000 || iterations > 1000000) throw new IOException("Invalid archive KDF");
        byte[] salt = new byte[16], nonce = new byte[8]; fields.get(salt); fields.get(nonce);
        byte[] key = key(password, salt, iterations);
        try {
            long total = 0; int sequence = 0;
            for (;;) {
                int size = data.readInt();
                if (size < 0 || size > BLOCK || (total += size) > MAX) throw new IOException("Invalid archive frame size");
                byte[] encrypted = new byte[size + 16]; data.readFully(encrypted);
                byte[] plain = crypt(Cipher.DECRYPT_MODE, key, nonce, header, sequence++, size, encrypted, encrypted.length);
                if (plain.length != size) throw new IOException("Invalid archive frame");
                if (size == 0) {
                    if (data.read() != -1) throw new IOException("Trailing encrypted data");
                    break;
                }
                output.write(plain); Arrays.fill(plain, (byte) 0);
            }
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] header,
                                int sequence, int size, byte[] data, int length) throws Exception {
        byte[] iv = ByteBuffer.allocate(12).put(nonce).putInt(sequence).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(header);
        cipher.updateAAD(ByteBuffer.allocate(8).putInt(sequence).putInt(size).array());
        return cipher.doFinal(data, 0, length);
    }

    private static byte[] key(char[] password, byte[] salt, int iterations) throws Exception {
        if (password == null) throw new IOException("Password required");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private static final class Frames extends OutputStream {
        final DataOutputStream output; final byte[] header, key, nonce, buffer = new byte[BLOCK];
        int count, sequence; long total; boolean closed;
        Frames(OutputStream output, byte[] header, byte[] key, byte[] nonce) {
            this.output = new DataOutputStream(output); this.header = header; this.key = key; this.nonce = nonce;
        }
        @Override public void write(int value) throws IOException { byte[] one = {(byte) value}; write(one, 0, 1); }
        @Override public void write(byte[] bytes, int offset, int size) throws IOException {
            if (closed) throw new IOException("Archive is closed");
            if (size < 0 || offset < 0 || offset > bytes.length - size) throw new IndexOutOfBoundsException();
            if ((total += size) > MAX) throw new IOException("Archive size limit exceeded");
            while (size > 0) {
                int copied = Math.min(size, buffer.length - count);
                System.arraycopy(bytes, offset, buffer, count, copied);
                count += copied; offset += copied; size -= copied;
                if (count == buffer.length) frame();
            }
        }
        private void frame() throws IOException {
            try {
                byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, nonce, header, sequence++, count, buffer, count);
                output.writeInt(count); output.write(encrypted); Arrays.fill(buffer, 0, count, (byte) 0); count = 0;
            } catch (Exception error) { throw new IOException("Archive encryption failed", error); }
        }
        @Override public void flush() throws IOException { output.flush(); }
        @Override public void close() throws IOException {
            if (closed) return; closed = true;
            try { if (count > 0) frame(); frame(); output.flush(); }
            finally { Arrays.fill(key, (byte) 0); Arrays.fill(buffer, (byte) 0); output.close(); }
        }
    }
}
