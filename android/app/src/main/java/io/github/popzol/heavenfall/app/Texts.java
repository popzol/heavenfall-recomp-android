package io.github.popzol.heavenfall.app;

import android.content.Context;

import io.github.popzol.heavenfall.Text;

/** Turns a {@link Text} (a string resource's name and its arguments) into the sentence in the player's language. */
public final class Texts {
    private Texts() {
    }

    public static String resolve(Context c, Text text) {
        if (text == null) return "";
        return resolve(c, text.key, text.args.toArray(new Object[0]));
    }

    public static String resolve(Context c, String key, Object... args) {
        int id = c.getResources().getIdentifier(key, "string", c.getPackageName());
        if (id == 0) return key;
        return args.length == 0 ? c.getString(id) : c.getString(id, args);
    }
}
