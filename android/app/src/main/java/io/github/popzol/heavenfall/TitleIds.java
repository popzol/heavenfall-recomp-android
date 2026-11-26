package io.github.popzol.heavenfall;

import java.util.Locale;

/**
 * Title ids are 16 hex digits and their case differs by source (the editions table writes {@code 0004000E00126300}, a
 * set's head read by the applier writes it in lower case): every place that compares two goes through {@link #same}.
 */
public final class TitleIds {
    private TitleIds() {
    }

    /** The id in upper case (the form the editions table keeps); null stays null. */
    public static String normalize(String id) {
        return id == null ? null : id.trim().toUpperCase(Locale.ROOT);
    }

    /** Two ids are the same title whatever their case; two missing ones are not. */
    public static boolean same(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }
}
