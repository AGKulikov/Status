package dezz.status.hudlab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.CRC32;

/* loaded from: classes4.dex */
final class HudQnxTimeGapPatch {
    static final int BLOCK_LENGTH = 5540;
    static final String BLOCK_MD5 = "a4cf6f44d5a0b24e0833e73e13734729";
    static final long BLOCK_OFFSET = 19975872;
    static final String BLOCK_SHA256 = "db6e17d019d375e2bb0e5752b8f2d9363e41c9f3b3864a5bfb6beab8ff80dca3";
    private static final int[] RESOURCE_LENGTHS = {920, 996, 1083, 786, 840, 915};
    private static final byte[] PNG_SIGNATURE = {-119, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] TRANSPARENT_IDAT = {120, -38, -19, -63, 1, 1, 0, 0, 0, -126, 32, -1, -81, 110, 72, 64, 1, 0, 0, 0, 0, 0, 124, 26, 25, 40, 0, 1};

    private HudQnxTimeGapPatch() {
    }

    static byte[] build() {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(BLOCK_LENGTH);
        for (int i : RESOURCE_LENGTHS) {
            byte[] bArrTransparentResource = transparentResource(i);
            byteArrayOutputStream.write(bArrTransparentResource, 0, bArrTransparentResource.length);
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        if (byteArray.length != BLOCK_LENGTH) {
            throw new IllegalStateException("TimeGap block length " + byteArray.length);
        }
        String strDigest = digest("SHA-256", byteArray);
        if (BLOCK_SHA256.equals(strDigest)) {
            return byteArray;
        }
        throw new IllegalStateException("TimeGap block checksum " + strDigest);
    }

    private static String digest(String str, byte[] bArr) {
        try {
            byte[] bArrDigest = MessageDigest.getInstance(str).digest(bArr);
            StringBuilder sb = new StringBuilder(bArrDigest.length * 2);
            for (byte b : bArrDigest) {
                sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & (-1))));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(str, e);
        }
    }

    static String md5(byte[] bArr) {
        return digest("MD5", bArr);
    }

    private static byte[] transparentResource(int i) {
        int i2 = i - 20;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(i2);
        write(byteArrayOutputStream, PNG_SIGNATURE);
        ByteArrayOutputStream byteArrayOutputStream2 = new ByteArrayOutputStream(13);
        writeBigEndian(byteArrayOutputStream2, 40);
        writeBigEndian(byteArrayOutputStream2, 40);
        byteArrayOutputStream2.write(8);
        byteArrayOutputStream2.write(6);
        byteArrayOutputStream2.write(0);
        byteArrayOutputStream2.write(0);
        byteArrayOutputStream2.write(0);
        writeChunk(byteArrayOutputStream, "IHDR", byteArrayOutputStream2.toByteArray());
        ByteArrayOutputStream byteArrayOutputStream3 = new ByteArrayOutputStream(9);
        writeBigEndian(byteArrayOutputStream3, 2835);
        writeBigEndian(byteArrayOutputStream3, 2835);
        byteArrayOutputStream3.write(1);
        writeChunk(byteArrayOutputStream, "pHYs", byteArrayOutputStream3.toByteArray());
        writeChunk(byteArrayOutputStream, "IDAT", TRANSPARENT_IDAT);
        int i3 = i - 138;
        if (i3 < 0) {
            throw new IllegalArgumentException("resource is too small: " + i);
        }
        writeChunk(byteArrayOutputStream, "npAd", new byte[i3]);
        writeChunk(byteArrayOutputStream, "IEND", new byte[0]);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        if (byteArray.length != i2) {
            throw new IllegalStateException("PNG length " + byteArray.length + " != " + i2);
        }
        ByteArrayOutputStream byteArrayOutputStream4 = new ByteArrayOutputStream(i);
        write(byteArrayOutputStream4, new byte[16]);
        writeLittleEndian(byteArrayOutputStream4, i2);
        write(byteArrayOutputStream4, byteArray);
        return byteArrayOutputStream4.toByteArray();
    }

    private static void write(ByteArrayOutputStream byteArrayOutputStream, byte[] bArr) {
        try {
            byteArrayOutputStream.write(bArr);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeBigEndian(ByteArrayOutputStream byteArrayOutputStream, int i) {
        byteArrayOutputStream.write((i >>> 24) & 255);
        byteArrayOutputStream.write((i >>> 16) & 255);
        byteArrayOutputStream.write((i >>> 8) & 255);
        byteArrayOutputStream.write(i & 255);
    }

    private static void writeChunk(ByteArrayOutputStream byteArrayOutputStream, String str, byte[] bArr) {
        byte[] bytes = str.getBytes(StandardCharsets.US_ASCII);
        writeBigEndian(byteArrayOutputStream, bArr.length);
        write(byteArrayOutputStream, bytes);
        write(byteArrayOutputStream, bArr);
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        crc32.update(bArr);
        writeBigEndian(byteArrayOutputStream, (int) crc32.getValue());
    }

    private static void writeLittleEndian(ByteArrayOutputStream byteArrayOutputStream, int i) {
        byteArrayOutputStream.write(i & 255);
        byteArrayOutputStream.write((i >>> 8) & 255);
        byteArrayOutputStream.write((i >>> 16) & 255);
        byteArrayOutputStream.write((i >>> 24) & 255);
    }
}
