package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The table of editions the app knows (the asset {@code heavenfall/editions.json}, made with the mod's release
 * data): where the app's own releases are, and for each edition its repository, its titles and the exact names of the
 * release files it wants. Anything the file does not state in the expected shape is refused when it is read.
 */
public final class Editions {
    public static final String ASSET_PATH = "heavenfall/editions.json";
    public static final int FORMAT = 1;

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,96}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,24}");
    private static final Pattern TITLE = Pattern.compile("[0-9A-Fa-f]{16}");

    /** The file does not hold a table this app understands. */
    public static final class InvalidException extends RuntimeException {
        InvalidException(String message) {
            super(message);
        }
    }

    /** The app's own release: its repository, its APK's name and the fallback release file's name. */
    public static final class App {
        public final String repository;
        public final String apk;
        public final String releaseFile;

        App(String repository, String apk, String releaseFile) {
            this.repository = repository;
            this.apk = apk;
            this.releaseFile = releaseFile;
        }

        /** The release file's address, for when the API gives no answer. */
        public String releaseFileUrl() {
            return "https://github.com/" + repository + "/releases/latest/download/" + releaseFile;
        }
    }

    public static final class Edition {
        public final String id;
        public final String name;
        public final String repository;
        public final String title;
        public final String updateTitle;
        public final String sharedFile;     // null: none
        public final String releaseFile;
        private final Map<String, String> sets;

        Edition(String id, String name, String repository, String title, String updateTitle, Map<String, String> sets,
                String sharedFile, String releaseFile) {
            this.id = id;
            this.name = name;
            this.repository = repository;
            this.title = title;
            this.updateTitle = updateTitle;
            this.sets = Collections.unmodifiableMap(new LinkedHashMap<>(sets));
            this.sharedFile = sharedFile;
            this.releaseFile = releaseFile;
        }

        /** The sets' keys and file names, in the file's order ({@code usa}, {@code usa_xx}). */
        public Map<String, String> sets() {
            return sets;
        }

        /** The set's file name for a region ({@code usa}), with MH XX or not; null when the edition has none. */
        public String setName(String region, boolean withXx) {
            return sets.get(withXx ? region + "_xx" : region);
        }

        /** Every file name the edition's release can carry: the sets, then the shared file. */
        public List<String> wantedNames() {
            List<String> names = new ArrayList<>(sets.values());
            if (sharedFile != null) names.add(sharedFile);
            return names;
        }

        /** The release file's address, for when the API gives no answer. */
        public String releaseFileUrl() {
            return "https://github.com/" + repository + "/releases/latest/download/" + releaseFile;
        }
    }

    private final App app;
    private final List<Edition> editions;

    private Editions(App app, List<Edition> editions) {
        this.app = app;
        this.editions = Collections.unmodifiableList(editions);
    }

    public App app() {
        return app;
    }

    public List<Edition> list() {
        return editions;
    }

    /** The edition with this id, or null. */
    public Edition byId(String id) {
        for (Edition e : editions) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    /** The edition the app starts with (the first one of the table). */
    public Edition first() {
        return editions.get(0);
    }

    /** The table in {@code json}; {@link InvalidException} when it is not a table of this format. */
    public static Editions parse(String json) {
        JSONObject root = Json.object(json);
        if (root == null) throw new InvalidException("editions.json is not a JSON object");
        Long format = Json.integer(root, "format");
        if (format == null || format != FORMAT) {
            throw new InvalidException("editions.json: format " + format + " is not " + FORMAT);
        }
        JSONObject appJson = Json.child(root, "app");
        if (appJson == null) throw new InvalidException("editions.json: no app");
        App app = new App(repository(appJson, "app"), fileName(appJson, "apk", "app"),
                fileName(appJson, "release_file", "app"));
        JSONArray array = Json.array(root, "editions");
        if (array == null || array.length() == 0) throw new InvalidException("editions.json: no editions");
        List<Edition> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof JSONObject)) {
                throw new InvalidException("editions.json: edition " + i + " is not an object");
            }
            Edition e = edition((JSONObject) item);
            for (Edition seen : list) {
                if (seen.id.equals(e.id)) throw new InvalidException("editions.json: edition " + e.id + " twice");
            }
            list.add(e);
        }
        return new Editions(app, list);
    }

    private static Edition edition(JSONObject o) {
        String id = Json.string(o, "id");
        if (id == null || !ID.matcher(id).matches()) {
            throw new InvalidException("editions.json: an edition without a good id");
        }
        String name = Json.string(o, "name");
        if (name == null || name.trim().isEmpty()) throw new InvalidException("editions.json: " + id + " has no name");
        String title = Json.string(o, "title");
        String updateTitle = Json.string(o, "update_title");
        if (title == null || !TITLE.matcher(title).matches() || updateTitle == null
                || !TITLE.matcher(updateTitle).matches()) {
            throw new InvalidException("editions.json: " + id + " has a title that is not 16 hex digits");
        }
        JSONObject setsJson = Json.child(o, "sets");
        if (setsJson == null || setsJson.length() == 0) {
            throw new InvalidException("editions.json: " + id + " has no sets");
        }
        Map<String, String> sets = new LinkedHashMap<>();
        Iterator<String> keys = setsJson.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            sets.put(key, fileName(setsJson, key, id));
        }
        String shared = o.has("shared_file") ? fileName(o, "shared_file", id) : null;
        Edition e = new Edition(id, name, repository(o, id), title.toUpperCase(Locale.ROOT),
                updateTitle.toUpperCase(Locale.ROOT), sets, shared, fileName(o, "release_file", id));
        List<String> names = e.wantedNames();
        for (int i = 0; i < names.size(); i++) {
            if (names.indexOf(names.get(i)) != i) {
                throw new InvalidException("editions.json: " + id + " names " + names.get(i) + " twice");
            }
        }
        return e;
    }

    private static String repository(JSONObject o, String where) {
        String value = Json.string(o, "repository");
        if (value == null || !REPOSITORY.matcher(value).matches()) {
            throw new InvalidException("editions.json: " + where + " has no good repository");
        }
        return value;
    }

    private static String fileName(JSONObject o, String key, String where) {
        String value = Json.string(o, key);
        if (value == null || !FILE_NAME.matcher(value).matches()) {
            throw new InvalidException("editions.json: " + where + " has no good file name for " + key);
        }
        return value;
    }
}
