package io.github.popzol.heavenfall;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The native layer's answers: "key=value" lines. A line without "=" or a repeated key is not an answer of the layer. */
final class KeyValues {
    private final Map<String, String> values = new HashMap<>();

    private KeyValues() {
    }

    static KeyValues parse(String text) {
        if (text == null) throw new IllegalArgumentException("no answer from the native layer");
        KeyValues kv = new KeyValues();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0 || kv.values.put(line.substring(0, eq), line.substring(eq + 1)) != null) {
                throw new IllegalArgumentException("not an answer of the native layer: " + line);
            }
        }
        return kv;
    }

    String string(String key) {
        String v = values.get(key);
        if (v == null) throw new IllegalArgumentException("the native layer's answer has no " + key);
        return v;
    }

    int integer(String key) {
        try {
            return Integer.parseInt(string(key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the native layer's " + key + " is not a number");
        }
    }

    long longValue(String key) {
        try {
            return Long.parseLong(string(key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the native layer's " + key + " is not a number");
        }
    }

    boolean flag(String key) {
        return integer(key) != 0;
    }

    /** A SHA-256 as lower-case hex, or "" when the layer gave none. */
    String hash(String key) {
        String v = string(key);
        if (!v.isEmpty() && !Json.isSha256(v)) throw new IllegalArgumentException("the native layer's " + key + " is not a SHA-256");
        return v.toLowerCase(Locale.ROOT);
    }
}
