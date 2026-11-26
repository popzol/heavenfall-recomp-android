package io.github.popzol.heavenfall;

import java.net.URL;

/**
 * Where an address of GitHub is really fetched from. In a release build every address stays as it is; a debug build
 * may map GitHub's addresses to a test server on the local network (the release rules and the checks of what is
 * downloaded do not change: only the place the bytes come from).
 */
public interface UrlMap {
    /** The address to fetch for the GitHub address {@code url}. */
    String map(String url);

    /** Whether {@code url} is the test server (it may be http, and a redirect may stay on it). */
    boolean isTestHost(URL url);

    /** Release builds: no mapping and no test server. */
    UrlMap NONE = new UrlMap() {
        @Override
        public String map(String url) {
            return url;
        }

        @Override
        public boolean isTestHost(URL url) {
            return false;
        }
    };
}
