package io.github.popzol.heavenfall.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.popzol.heavenfall.UrlMap;

/**
 * Test releases, in debug builds only (this class is not in a release build; {@link Networks} finds it by name).
 * When the file {@code test_release/base_url.txt} is in the app's external files folder, GitHub's addresses are
 * fetched from that base URL instead (a computer on the same network serving two versions' files and their
 * {@code releases/latest} answers). The answers keep GitHub's own addresses and every rule stays as strict: only the
 * place the bytes come from changes.
 *
 * <pre>
 * api.github.com/&lt;rest&gt;  is fetched from  &lt;base&gt;/api/&lt;rest&gt;
 * github.com/&lt;rest&gt;      is fetched from  &lt;base&gt;/github/&lt;rest&gt;
 * </pre>
 */
public final class TestReleases implements UrlMap {
    public static final String BASE_FILE = "test_release/base_url.txt";
    static final String API_PREFIX = "https://api.github.com/";
    static final String GITHUB_PREFIX = "https://github.com/";

    private final String base;
    private final URL baseUrl;

    /** {@code baseUrl}: {@code http(s)://host[:port][/path]}; throws when it is not one. */
    public TestReleases(String baseUrl) {
        String text = baseUrl.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        try {
            this.baseUrl = new URL(text);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("not a base URL: " + baseUrl, e);
        }
        String protocol = this.baseUrl.getProtocol();
        if ((!protocol.equals("http") && !protocol.equals("https")) || this.baseUrl.getHost().isEmpty()
                || this.baseUrl.getUserInfo() != null || this.baseUrl.getQuery() != null
                || this.baseUrl.getRef() != null) {
            throw new IllegalArgumentException("not a base URL: " + baseUrl);
        }
        this.base = text;
    }

    /** The map for the base URL in the app's test file, or null when there is no such file (or it is not a URL). */
    public static UrlMap create(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return null;
        File file = new File(dir, BASE_FILE);
        if (!file.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : new TestReleases(text);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String map(String url) {
        if (url.startsWith(API_PREFIX)) return base + "/api/" + url.substring(API_PREFIX.length());
        if (url.startsWith(GITHUB_PREFIX)) return base + "/github/" + url.substring(GITHUB_PREFIX.length());
        return url;
    }

    @Override
    public boolean isTestHost(URL url) {
        return url != null && url.getProtocol().equals(baseUrl.getProtocol())
                && url.getHost().equalsIgnoreCase(baseUrl.getHost()) && port(url) == port(baseUrl)
                && url.getUserInfo() == null;
    }

    private static int port(URL url) {
        return url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
    }
}
