package io.github.popzol.heavenfall;

import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The only hosts the app talks to: GitHub's API, its release pages, and the storage the release pages redirect to. */
public final class Hosts {
    private Hosts() {
    }

    public static final String API = "api.github.com";

    /** GitHub's release pages and the storage hosts their downloads are redirected to. */
    public static final List<String> RELEASE_DOWNLOADS = Collections.unmodifiableList(Arrays.asList(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"));

    /** An address the app may fetch: https on the API host (for {@code api}) or on a release-download host. */
    static boolean allowed(URL url, boolean api, UrlMap map) {
        if (url == null) return false;
        if (map.isTestHost(url)) return true;
        if (!"https".equals(url.getProtocol())) return false;
        if (url.getUserInfo() != null) return false;
        int port = url.getPort();
        if (port != -1 && port != 443) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return api ? host.equals(API) : RELEASE_DOWNLOADS.contains(host);
    }
}
