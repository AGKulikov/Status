/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded PNG/JPEG import. Caller runs on a worker; no persistent source URI is needed. */
public final class PhoneIconImporter {
    private PhoneIconImporter() {}
    public static byte[] read(Context context, Uri uri) throws IOException {
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Не удалось открыть изображение");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 8_000_000) throw new IOException("Изображение больше 8 МБ");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (!("image/png".equals(bounds.outMimeType) || "image/jpeg".equals(bounds.outMimeType))
                || bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > 40_000_000L)
            throw new IOException("Выберите корректный PNG или JPEG до 40 мегапикселей");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024)
            options.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) throw new IOException("Не удалось прочитать изображение");
        Bitmap scaled = decoded;
        try {
            int max = Math.max(decoded.getWidth(), decoded.getHeight());
            if (max > 512) scaled = Bitmap.createScaledBitmap(decoded,
                    Math.max(1, decoded.getWidth() * 512 / max), Math.max(1, decoded.getHeight() * 512 / max), true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Не удалось подготовить иконку");
            return output.toByteArray();
        } finally { if (scaled != decoded) scaled.recycle(); decoded.recycle(); }
    }
}
