package io.github.popzol.heavenfall.app;

import android.content.Context;

import io.github.popzol.heavenfall.UrlMap;

/**
 * Where the app's downloads really come from. A release build always uses GitHub's addresses as they are. A debug
 * build carries the class {@code io.github.popzol.heavenfall.app.TestReleases} (in src/debug), found here by its name
 * at run time: a release build has none of its bytes.
 */
public final class Networks {
    private static final String TEST_CLASS = "io.github.popzol.heavenfall.app.TestReleases";

    private Networks() {
    }

    public static UrlMap urlMap(Context c) {
        try {
            Object map = Class.forName(TEST_CLASS).getMethod("create", Context.class).invoke(null, c);
            if (map instanceof UrlMap) return (UrlMap) map;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // no such class (a release build) or no test base: GitHub
        }
        return UrlMap.NONE;
    }
}
