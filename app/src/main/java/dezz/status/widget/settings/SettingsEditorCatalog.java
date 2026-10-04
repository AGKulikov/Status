/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reviewed field destinations, built from the actual source forms, not a runtime word guess. */
public final class SettingsEditorCatalog {
    private static final Map<String, SettingsSection> fields = new LinkedHashMap<>();
    private static boolean loaded;
    private SettingsEditorCatalog() {}
    public static synchronized SettingsSection section(Context context, String text,
                                                       SettingsSection fallback) {
        if (!loaded) {
            try (InputStream input = context.getAssets().open("settings-editor-fields.json");
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) out.write(bytes, 0, count);
                JSONObject object = new JSONObject(out.toString("UTF-8"));
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    String key = keys.next(); fields.put(key, SettingsSection.valueOf(object.getString(key)));
                }
                loaded = true;
            } catch (Exception failure) {
                throw new IllegalStateException("Missing editor field catalog", failure);
            }
        }
        String label = text == null ? "" : text.trim();
        SettingsSection exact = fields.get(label);
        if (exact != null) return exact;
        // A live value is appended after the stable label (e.g. "Ширина: 300 px").
        int longest = 0; SettingsSection result = fallback;
        for (Map.Entry<String, SettingsSection> field : fields.entrySet()) {
            String prefix = field.getKey();
            if (prefix.length() > longest && label.startsWith(prefix)
                    && (prefix.endsWith(" ") || prefix.endsWith(":") || label.length() == prefix.length()
                    || ": ·\n(—–= ".indexOf(label.charAt(prefix.length())) >= 0)) {
                result = field.getValue(); longest = prefix.length();
            }
        }
        return result;
    }
}
