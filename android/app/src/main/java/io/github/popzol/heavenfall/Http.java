package io.github.popzol.heavenfall;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * One GET with the app's rules: https only (a debug build's test server aside), only the hosts of {@link Hosts},
 * redirects followed by hand (each target checked), a generic User-Agent and nothing else about the player or the
 * phone, timeouts, no cache and no compression (a range and a size stay exact).
 */
final class Http {
    static final String USER_AGENT = "Heavenfall-Android";
    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 30_000;
    static final int MAX_REDIRECTS = 5;

    private Http() {
    }

    /** An answer whose status is 2xx, 4xx or 5xx (a redirect is followed before this is returned). */
    static final class Response implements Closeable {
        final int status;
        final URL url;
        private final HttpURLConnection connection;

        Response(int status, URL url, HttpURLConnection connection) {
            this.status = status;
            this.url = url;
            this.connection = connection;
        }

        String header(String name) {
            return connection.getHeaderField(name);
        }

        long contentLength() {
            return connection.getContentLengthLong();
        }

        InputStream body() throws IOException {
            return connection.getInputStream();
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        /** The answer is an error status: {@link UpdateFailure#SERVER} with the status as the detail. */
        void requireOk() throws UpdateException {
            if (!ok()) throw new UpdateException(UpdateFailure.SERVER, String.valueOf(status));
        }

        @Override
        public void close() {
            connection.disconnect();
        }
    }

    /**
     * GET {@code githubUrl} (an address of GitHub: the map decides where it is really fetched from).
     * {@code api}: the address is on the API host, else on a release-download host. {@code rangeFrom} above zero asks
     * for the bytes from there on. A network failure is {@link UpdateFailure#NETWORK}; an address or a redirect
     * target off the lists is {@link UpdateFailure#UNTRUSTED_ADDRESS}.
     */
    static Response get(String githubUrl, boolean api, UrlMap map, long rangeFrom, String accept) throws UpdateException {
        URL first = parse(githubUrl);
        if (!Hosts.allowed(first, api, UrlMap.NONE)) throw new UpdateException(UpdateFailure.UNTRUSTED_ADDRESS, githubUrl);
        URL url = parse(map.map(githubUrl));
        if (!Hosts.allowed(url, api, map)) throw new UpdateException(UpdateFailure.UNTRUSTED_ADDRESS, String.valueOf(url));
        boolean test = map.isTestHost(url);
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setUseCaches(false);
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept-Encoding", "identity");
                if (accept != null) c.setRequestProperty("Accept", accept);
                if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
                int status = c.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = c.getHeaderField("Location");
                    c.disconnect();
                    if (location == null) throw new UpdateException(UpdateFailure.UNTRUSTED_ADDRESS, "redirect without a target");
                    if (redirects >= MAX_REDIRECTS) throw new UpdateException(UpdateFailure.UNTRUSTED_ADDRESS, "too many redirects");
                    URL next;
                    try {
                        next = new URL(url, location);
                    } catch (MalformedURLException e) {
                        throw new UpdateException(UpdateFailure.UNTRUSTED_ADDRESS, "bad redirect target", e);
                    }
                    boolean fine = test ? map.isTestHost(next) : Hosts.allowed(next, api, UrlMap.NONE);
                    if (!fine) throw new UpdateException(UpdateFailure.UNTRUSTED_ADDRESS, hostOf(next));
                    url = next;
                    continue;
                }
                return new Response(status, url, c);
            } catch (UpdateException e) {
                if (c != null) c.disconnect();
                throw e;
            } catch (IOException e) {
                if (c != null) c.disconnect();
                throw new UpdateException(UpdateFailure.NETWORK, e.getClass().getSimpleName(), e);
            }
        }
    }

    private static String hostOf(URL url) {
        return url.getProtocol() + "://" + url.getHost();
    }

    private static URL parse(String text) throws UpdateException {
        try {
            return new URL(text);
        } catch (MalformedURLException e) {
            throw new UpdateException(UpdateFailure.UNTRUSTED_ADDRESS, "bad address", e);
        }
    }
}
