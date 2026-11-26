package io.github.popzol.heavenfall;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a release is worth trusting: the rules for GitHub's {@code releases/latest} answer and for the fallback
 * release file (same shape: {@code tag_name} and {@code assets[]} with {@code name}, {@code size}, {@code digest},
 * {@code browser_download_url}). The tag is a version; each wanted asset has exactly its name, a size, a
 * {@code sha256:} digest and an https URL under the repository's own {@code releases/download/}. Other assets are
 * left alone. A broken rule is a named {@link Refusal}, never a partial release.
 */
public final class ReleaseRules {
    private ReleaseRules() {
    }

    /** Why a release was not taken. Each has a reason text and an advice text (resource names). */
    public enum Refusal {
        BAD_ANSWER("hf_refusal_bad_answer"),     // not the JSON of a release
        BAD_TAG("hf_refusal_bad_tag"),           // the tag is not a version
        NO_SIZE("hf_refusal_no_size"),           // a wanted asset without a size above zero
        NO_DIGEST("hf_refusal_no_digest"),       // a wanted asset without a sha256 digest
        BAD_URL("hf_refusal_bad_url"),           // a wanted asset's URL is not the repository's own release download
        DUPLICATE("hf_refusal_duplicate"),       // two assets with the same wanted name
        MISSING("hf_refusal_missing");           // the release does not carry a file the app needs

        private final String reasonKey;

        Refusal(String reasonKey) {
            this.reasonKey = reasonKey;
        }

        public String reasonKey() {
            return reasonKey;
        }

        public String adviceKey() {
            return "hf_advice_try_later";
        }
    }

    /** One wanted file of a release, checked. */
    public static final class Asset {
        public final String name;
        public final long size;
        public final String sha256;   // 64 lower-case hex digits
        public final String url;

        Asset(String name, long size, String sha256, String url) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.url = url;
        }
    }

    /** A release whose wanted assets passed every rule. */
    public static final class Release {
        public final String tag;
        public final Version version;
        private final Map<String, Asset> assets;

        Release(String tag, Version version, Map<String, Asset> assets) {
            this.tag = tag;
            this.version = version;
            this.assets = Collections.unmodifiableMap(assets);
        }

        /** The wanted asset with this exact name, or null when the release does not carry it. */
        public Asset asset(String name) {
            return assets.get(name);
        }

        public boolean has(String name) {
            return assets.containsKey(name);
        }

        public Collection<Asset> assets() {
            return assets.values();
        }

        /** This release when it carries every named file, else a {@link Refusal#MISSING} result naming the first absent one. */
        public Result require(String... names) {
            for (String name : names) {
                if (!assets.containsKey(name)) return Result.refused(Refusal.MISSING, name);
            }
            return Result.of(this);
        }
    }

    /** A release or the refusal; {@code detail} names the asset or the field that broke the rule. */
    public static final class Result {
        public final Release release;
        public final Refusal refusal;
        public final String detail;

        private Result(Release release, Refusal refusal, String detail) {
            this.release = release;
            this.refusal = refusal;
            this.detail = detail;
        }

        static Result of(Release release) {
            return new Result(release, null, null);
        }

        static Result refused(Refusal refusal, String detail) {
            return new Result(null, refusal, detail);
        }

        public boolean ok() {
            return release != null;
        }
    }

    /**
     * The release in {@code json} of {@code repository} ({@code owner/name}) with the assets named in
     * {@code wanted} that it carries. A wanted name the release lacks is not an error here ({@link Release#require}).
     */
    public static Result parse(String json, String repository, List<String> wanted) {
        JSONObject root = Json.object(json);
        if (root == null) return Result.refused(Refusal.BAD_ANSWER, "release");
        String tag = Json.string(root, "tag_name");
        JSONArray array = Json.array(root, "assets");
        if (tag == null || array == null) return Result.refused(Refusal.BAD_ANSWER, "tag_name");
        Version version = Version.parse(tag);
        if (version == null) return Result.refused(Refusal.BAD_TAG, tag);
        Map<String, Asset> found = new LinkedHashMap<>();
        String prefix = "https://github.com/" + repository + "/releases/download/";
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof JSONObject)) continue;
            JSONObject o = (JSONObject) item;
            String name = Json.string(o, "name");
            if (name == null || !wanted.contains(name)) continue;
            if (found.containsKey(name)) return Result.refused(Refusal.DUPLICATE, name);
            String digest = Json.string(o, "digest");
            String sha = digest == null ? null : digestHex(digest);
            if (sha == null) return Result.refused(Refusal.NO_DIGEST, name);
            Long size = Json.integer(o, "size");
            if (size == null || size <= 0) return Result.refused(Refusal.NO_SIZE, name);
            String url = Json.string(o, "browser_download_url");
            if (url == null) return Result.refused(Refusal.BAD_URL, name);
            if (!urlOk(url, prefix, name)) return Result.refused(Refusal.BAD_URL, name);
            found.put(name, new Asset(name, size, sha, url));
        }
        return Result.of(new Release(tag, version, found));
    }

    /** The 64 lower-case hex digits of {@code sha256:<64 hex>}, or null. */
    static String digestHex(String digest) {
        if (!digest.startsWith("sha256:")) return null;
        String hex = digest.substring(7);
        return Json.isSha256(hex) ? Json.lower(hex) : null;
    }

    /**
     * An https URL on the repository's own release downloads, ending in the asset's name, with no dot segments
     * (also written percent-encoded), no spaces, controls, backslashes, queries or fragments.
     */
    static boolean urlOk(String url, String prefix, String name) {
        if (!url.startsWith(prefix) || !url.endsWith("/" + name)) return false;
        if (url.contains("..")) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) return false;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c == 0x7f || c == '\\' || c == '?' || c == '#') return false;
        }
        return true;
    }
}
