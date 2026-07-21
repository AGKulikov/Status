/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Crash-safe, device-protected cache of the last Sprut.hub catalog and current-value snapshot. */
public final class SprutHubCatalogStore {
    private static final String TAG = "SprutCatalogStore";
    /** Serializes AtomicFile transactions across controller and settings-screen instances. */
    private static final Object FILE_LOCK = new Object();
    private final AtomicFile file;

    public SprutHubCatalogStore(@NonNull Context context) {
        Context device = context.getApplicationContext().createDeviceProtectedStorageContext();
        file = new AtomicFile(new File(device.getFilesDir(), "spruthub_catalog_v1.json"));
    }

    public void save(@NonNull JSONObject snapshot) throws IOException {
        synchronized (FILE_LOCK) {
            byte[] bytes = snapshot.toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(bytes);
                output.flush();
                file.finishWrite(output);
            } catch (IOException e) {
                if (output != null) file.failWrite(output);
                throw e;
            }
        }
    }

    @Nullable
    public JSONObject load() {
        synchronized (FILE_LOCK) {
            if (!file.getBaseFile().exists()) return null;
            try (FileInputStream input = file.openRead()) {
                byte[] bytes = new byte[(int) Math.min(
                        file.getBaseFile().length(), 20_000_000L)];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                if (offset == 0) return null;
                return new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "Ignored invalid Sprut.hub catalog cache", e);
                return null;
            }
        }
    }
}
