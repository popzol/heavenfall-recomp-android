package io.github.popzol.heavenfall;

import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict reads of org.json values: a field has the type asked or it is absent (no coercion of numbers to text). */
final class Json {
    private Json() {
    }

    /** The object in {@code text}, or null when it is not a JSON object. */
    static JSONObject object(String text) {
        if (text == null) return null;
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    static String string(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    static JSONObject child(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    static JSONArray array(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    /** An integer field (a JSON number without fraction that fits a long), or null. */
    static Long integer(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Long) return (Long) v;
        return null;
    }

    static Boolean bool(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    /** {@code key: value} into {@code o}; the value is a String, Long, Integer or Boolean (never a JSON null). */
    static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // only for a NaN or an infinite number, which are never put
        }
    }

    static boolean isSha256(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
