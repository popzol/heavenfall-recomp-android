package io.github.popzol.heavenfall;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Asks GitHub for a repository's latest release: {@code api.github.com/repos/<repository>/releases/latest}, and when
 * that gives no usable answer (refused, rate limited, offline, not a release), the release file attached to the
 * latest release ({@code github.com/<repository>/releases/latest/download/<release file>}, the same JSON). The
 * answer goes through {@link ReleaseRules}: nothing is trusted that the rules do not accept.
 */
public final class ReleaseClient {
    /** The biggest answer read: a release's JSON is a few kilobytes. */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** What came back: a release, or why not. */
    public static final class Answer {
        public final ReleaseRules.Release release;
        /** Set when {@link #release} is null. */
        public final UpdateException failure;
        /** The release came from the release file, not the API. */
        public final boolean viaReleaseFile;

        Answer(ReleaseRules.Release release, UpdateException failure, boolean viaReleaseFile) {
            this.release = release;
            this.failure = failure;
            this.viaReleaseFile = viaReleaseFile;
        }

        public boolean ok() {
            return release != null;
        }
    }

    private final UrlMap map;

    public ReleaseClient(UrlMap map) {
        this.map = map;
    }

    public static String apiUrl(String repository) {
        return "https://" + Hosts.API + "/repos/" + repository + "/releases/latest";
    }

    public static String releaseFileUrl(String repository, String releaseFile) {
        return "https://github.com/" + repository + "/releases/latest/download/" + releaseFile;
    }

    /** The app's own latest release: it must carry the APK. */
    public Answer latestApp(Editions.App app) {
        return latest(app.repository, app.releaseFile, java.util.Collections.singletonList(app.apk));
    }

    /** An edition's latest release, with the sets and the shared file it names. */
    public Answer latestEdition(Editions.Edition edition) {
        return latest(edition.repository, edition.releaseFile, edition.wantedNames());
    }

    /** The latest release of {@code repository} ({@code owner/name}) with the {@code wanted} files it carries. */
    public Answer latest(String repository, String releaseFile, List<String> wanted) {
        try {
            String json = fetchText(apiUrl(repository), true, "application/vnd.github+json");
            ReleaseRules.Result r = ReleaseRules.parse(json, repository, wanted);
            if (r.ok()) return new Answer(r.release, null, false);
        } catch (UpdateException ignored) {
            // no usable answer from the API: the release file is asked next
        }
        try {
            String json = fetchText(releaseFileUrl(repository, releaseFile), false, "application/json");
            ReleaseRules.Result r = ReleaseRules.parse(json, repository, wanted);
            if (r.ok()) return new Answer(r.release, null, true);
            return new Answer(null, new UpdateException(r.refusal, r.detail), true);
        } catch (UpdateException e) {
            return new Answer(null, e, true);   // both failed: the last one is the answer
        }
    }

    private String fetchText(String url, boolean api, String accept) throws UpdateException {
        try (Http.Response r = Http.get(url, api, map, 0, accept)) {
            r.requireOk();
            try (InputStream in = r.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_BODY_BYTES) throw new UpdateException(ReleaseRules.Refusal.BAD_ANSWER, "too big");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UpdateException(UpdateFailure.NETWORK, e.getClass().getSimpleName(), e);
        }
    }
}
