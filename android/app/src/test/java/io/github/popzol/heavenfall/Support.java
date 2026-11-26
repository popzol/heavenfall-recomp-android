package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Shared helpers of the JVM tests: the app's own asset files and release JSON written by hand. */
final class Support {
    private Support() {
    }

    /** A file of the app module ({@code src/main/...}), found from the module's folder or from the project's. */
    static String read(String relative) {
        File f = new File(relative);
        if (!f.exists()) f = new File("app", relative);
        if (!f.exists()) f = new File("android/app", relative);
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + relative + " from " + new File(".").getAbsolutePath(), e);
        }
    }

    static String editionsJson() {
        return read("src/main/assets/heavenfall/editions.json");
    }

    static String tuningJson() {
        return read("src/main/assets/heavenfall/tuning.json");
    }

    static Editions editions() {
        return Editions.parse(editionsJson());
    }

    static Tuning tuning() {
        return Tuning.parse(tuningJson());
    }

    /** A file of the repository (mh4n's, the app's), found from the module's folder or from the project's. */
    static String readRepo(String relative) {
        for (String up : new String[] {"../../", "../", ""}) {
            File f = new File(up + relative);
            if (f.isFile()) {
                try {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("cannot find " + relative + " from " + new File(".").getAbsolutePath());
    }

    /** The build's own numbers, as the APK's tools carry them (mh4n/config/build_tuning.json). */
    static BuildNeeds buildNeeds() {
        try {
            return BuildNeeds.parse(readRepo("mh4n/" + BuildNeeds.FILE));
        } catch (BuildNeeds.InvalidException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The home screen's model with the app's tuning and the build's numbers. */
    static HomeModel model() {
        return new HomeModel(tuning(), buildNeeds());
    }

    /** 64 hex digits made of one digit. */
    /** The library file the records of {@link #installed} name. */
    static final LibraryFile LIBRARY_FILE = new LibraryFile(sha('f'), 1, 1);

    /**
     * A record with the mod's code digest equal to its executable's ({@code codeSha256}) and, when it has a library,
     * the file {@link #LIBRARY_FILE}: the record of the tests that do not look at those.
     */
    static Installed installed(String edition, String modVersion, String setName, String setSha256, boolean withXx,
                               String codeSha256, String libraryId, String libraryCodeSha256) {
        boolean library = libraryId != null && !libraryId.isEmpty();
        return new Installed(edition, modVersion, setName, setSha256, withXx, codeSha256, codeSha256,
                library ? libraryId : "", library ? libraryCodeSha256 : "", library ? LIBRARY_FILE : null);
    }

    static String sha(char c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append(c);
        return sb.toString();
    }

    static final String REPO = "popzol/4u-heavenfall-mod-beta";

    static String url(String repo, String tag, String name) {
        return "https://github.com/" + repo + "/releases/download/" + tag + "/" + name;
    }

    /** One asset of a release answer; any field passed as null is left out. */
    static String asset(String name, String size, String digest, String url) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\": \"").append(name).append("\"");
        if (size != null) sb.append(", \"size\": ").append(size);
        if (digest != null) sb.append(", \"digest\": ").append(digest);
        if (url != null) sb.append(", \"browser_download_url\": \"").append(url).append("\"");
        return sb.append("}").toString();
    }

    /** A good asset of {@code repo}'s release {@code tag}. */
    static String goodAsset(String repo, String tag, String name, long size, char shaChar) {
        return asset(name, String.valueOf(size), "\"sha256:" + sha(shaChar) + "\"", url(repo, tag, name));
    }

    static String release(String tag, String... assets) {
        return "{\"tag_name\": \"" + tag + "\", \"body\": \"notes\", \"assets\": [" + String.join(", ", assets) + "]}";
    }
}
