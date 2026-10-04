/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;

/** Bounded strict syntax check before Android's permissive org.json decoder is allowed to run. */
public final class BackupJson {
    private final String text; private int index, values;
    private BackupJson(String text) { this.text = text; }
    public static void validate(String text) throws IOException {
        if (text == null || text.length() > 16 * 1024 * 1024) throw new IOException("JSON size limit");
        BackupJson parser = new BackupJson(text); parser.value(0); parser.space();
        if (parser.index != text.length()) throw new IOException("Trailing JSON data");
    }
    private void space() { while (index < text.length() && " \r\n\t".indexOf(text.charAt(index)) >= 0) index++; }
    private char peek() throws IOException { space(); if (index >= text.length()) throw new IOException("Truncated JSON"); return text.charAt(index); }
    private void take(char expected) throws IOException { if (peek() != expected) throw new IOException("Invalid JSON token"); index++; }
    private void value(int depth) throws IOException {
        if (depth > 64 || ++values > 500000) throw new IOException("JSON complexity limit");
        char next = peek();
        if (next == '{') {
            index++; Set<String> keys = new HashSet<>();
            if (peek() == '}') { index++; return; }
            for (;;) {
                String key = string(); if (!keys.add(key)) throw new IOException("Duplicate JSON key");
                take(':'); value(depth + 1); next = peek(); index++;
                if (next == '}') return;
                if (next != ',') throw new IOException("Invalid JSON object");
            }
        } else if (next == '[') {
            index++; if (peek() == ']') { index++; return; }
            for (;;) { value(depth + 1); next = peek(); index++; if (next == ']') return;
                if (next != ',') throw new IOException("Invalid JSON array"); }
        } else if (next == '"') string();
        else if (text.startsWith("true", index)) index += 4;
        else if (text.startsWith("false", index)) index += 5;
        else if (text.startsWith("null", index)) index += 4;
        else {
            int start = index;
            while (index < text.length() && "-+0123456789.eE".indexOf(text.charAt(index)) >= 0) index++;
            String number = text.substring(start, index);
            if (number.length() > 128 || !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                throw new IOException("Invalid JSON number");
        }
    }
    private String string() throws IOException {
        take('"'); int start = index - 1; boolean escaped = false;
        while (index < text.length()) {
            char value = text.charAt(index++);
            if (value < 32) throw new IOException("Control character in JSON");
            if (escaped) {
                if (value == 'u') { for (int digit = 0; digit < 4; digit++) {
                    if (index >= text.length() || Character.digit(text.charAt(index++), 16) < 0)
                        throw new IOException("Invalid JSON escape");
                } } else if ("\"\\/bfnrt".indexOf(value) < 0) throw new IOException("Invalid JSON escape");
                escaped = false;
            } else if (value == '\\') escaped = true;
            else if (value == '"') {
                try { return new JSONArray("[" + text.substring(start, index) + "]").getString(0); }
                catch (org.json.JSONException invalid) { throw new IOException(invalid); }
            }
        }
        throw new IOException("Unterminated JSON string");
    }
}
