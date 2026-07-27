package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class HudProfileSnapshotPatcherTest {

    @Test
    public void patchChangesOnlyHudArAndPreservesEveryOtherField() throws Exception {
        JSONObject original = completeSnapshot();
        original.put(HudProfileSnapshotPatcher.HUD_AR_PROFILE_KEY, "0");
        Map<String, String> before = values(original);

        JSONObject patched = new JSONObject(HudProfileSnapshotPatcher.patchHudAr(
                original.toString(), true, original.length()));

        assertEquals("1", patched.getString(HudProfileSnapshotPatcher.HUD_AR_PROFILE_KEY));
        assertEquals(before.size(), patched.length());
        for (Map.Entry<String, String> entry : before.entrySet()) {
            if (HudProfileSnapshotPatcher.HUD_AR_PROFILE_KEY.equals(entry.getKey())) continue;
            assertEquals(entry.getValue(), patched.getString(entry.getKey()));
        }
    }

    @Test
    public void rejectsSnapshotWithoutKnownHudArKey() throws Exception {
        JSONObject original = completeSnapshot();
        assertFalse(original.has(HudProfileSnapshotPatcher.HUD_AR_PROFILE_KEY));

        assertThrows(IllegalArgumentException.class, () ->
                HudProfileSnapshotPatcher.patchHudAr(
                        original.toString(), false, original.length()));
    }

    @Test
    public void rejectsPartialSnapshotBeforeVendorApplyCanZeroOtherSettings() throws Exception {
        JSONObject partial = new JSONObject()
                .put("100", "1")
                .put(HudProfileSnapshotPatcher.HUD_AR_PROFILE_KEY, "0");

        assertThrows(IllegalArgumentException.class, () ->
                HudProfileSnapshotPatcher.patchHudAr(partial.toString(), true, 60));
    }

    @Test
    public void rejectsAllZeroSdkPlaceholder() throws Exception {
        JSONObject placeholder = new JSONObject();
        for (int index = 0; index < 40; index++) {
            placeholder.put(String.valueOf(100_000 + index), "0");
        }

        assertThrows(IllegalArgumentException.class, () ->
                HudProfileSnapshotPatcher.patchHudAr(
                        placeholder.toString(), true, placeholder.length()));
    }

    @Test
    public void patchCanRestoreVisibleValue() throws Exception {
        JSONObject original = completeSnapshot();
        original.put(HudProfileSnapshotPatcher.HUD_AR_PROFILE_KEY, "1");

        JSONObject patched = new JSONObject(HudProfileSnapshotPatcher.patchHudAr(
                original.toString(), false, original.length()));

        assertEquals("0", patched.getString(HudProfileSnapshotPatcher.HUD_AR_PROFILE_KEY));
    }

    private static JSONObject completeSnapshot() throws Exception {
        JSONObject object = new JSONObject();
        for (int index = 0; index < 40; index++) {
            object.put(String.valueOf(100_000 + index), String.valueOf(index == 7 ? 3 : 0));
        }
        return object;
    }

    private static Map<String, String> values(JSONObject object) throws Exception {
        Map<String, String> values = new HashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            values.put(key, object.getString(key));
        }
        return values;
    }
}
