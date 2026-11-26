package io.github.popzol.heavenfall;

import org.json.JSONObject;

/**
 * What is installed, as {@code installed.json} records it (written by the app only after the mod's folder replaced
 * the old one): the edition, the mod's version, the set it was made from (its name and SHA-256, and whether it is the
 * one with MH XX), the SHA-256 of the mod's code, and what the code library was built for (the id of the tools that
 * built it and the SHA-256 of the code it was built from; both empty while there is none). Immutable.
 */
public final class Installed {
    public static final String FILE = "installed.json";
    public static final int FORMAT = 1;

    /** The text is not an installed.json this app understands. */
    public static final class InvalidException extends RuntimeException {
        InvalidException(String message) {
            super(message);
        }
    }

    public final String edition;
    public final String modVersion;
    public final String setName;
    public final String setSha256;
    public final boolean withXx;
    public final String codeSha256;
    public final String libraryId;           // "" when no library was built
    public final String libraryCodeSha256;   // "" when no library was built

    public Installed(String edition, String modVersion, String setName, String setSha256, boolean withXx,
                     String codeSha256, String libraryId, String libraryCodeSha256) {
        this.edition = require(edition, "edition");
        this.modVersion = require(modVersion, "mod_version");
        this.setName = require(setName, "set_name");
        this.setSha256 = sha(setSha256, "set_sha256", false);
        this.withXx = withXx;
        this.codeSha256 = sha(codeSha256, "code_sha256", false);
        this.libraryId = libraryId == null ? "" : libraryId;
        this.libraryCodeSha256 = sha(libraryCodeSha256, "library_code_sha256", true);
        if (this.libraryId.isEmpty() != this.libraryCodeSha256.isEmpty()) {
            throw new InvalidException("installed.json: the library's id and its code's hash go together");
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new InvalidException("installed.json: no " + field);
        return value;
    }

    private static String sha(String value, String field, boolean mayBeEmpty) {
        if (value == null || (value.isEmpty() && mayBeEmpty)) return "";
        if (!Json.isSha256(value)) throw new InvalidException("installed.json: " + field + " is not a SHA-256");
        return Json.lower(value);
    }

    /** The same record after the library was built for {@code libraryId} from the mod's current code. */
    public Installed withLibrary(String libraryId) {
        return new Installed(edition, modVersion, setName, setSha256, withXx, codeSha256, libraryId, codeSha256);
    }

    /**
     * The record after a new mod was applied. The library's record stays: it stays good when the mod's code did not
     * change, and {@link #libraryState} finds it stale when it did.
     */
    public Installed applied(String modVersion, String setName, String setSha256, boolean withXx, String codeSha256) {
        return new Installed(edition, modVersion, setName, setSha256, withXx, codeSha256, libraryId, libraryCodeSha256);
    }

    /** The record for an edition's first install. */
    public static Installed fresh(String edition, String modVersion, String setName, String setSha256, boolean withXx,
                                  String codeSha256) {
        return new Installed(edition, modVersion, setName, setSha256, withXx, codeSha256, "", "");
    }

    /**
     * The state of the code library for the home screen: {@code libraryPresent} (the file is on disk), the id found
     * beside it ({@code onDiskId}, null when none) and the id the app's tools make ({@code wantedId}). The library
     * is good when it was built by these tools from the code of the installed mod.
     */
    public HomeModel.Library libraryState(boolean libraryPresent, String onDiskId, String wantedId) {
        if (!libraryPresent) return HomeModel.Library.NONE;
        if (onDiskId == null || !onDiskId.equals(wantedId) || !libraryId.equals(wantedId)) return HomeModel.Library.OTHER_TOOLS;
        if (!libraryCodeSha256.equals(codeSha256)) return HomeModel.Library.OTHER_MOD;
        return HomeModel.Library.GOOD;
    }

    /** The state with no record at all: an installed mod that left no record, or the library without one. */
    public static HomeModel.Library libraryStateWithoutRecord(boolean libraryPresent) {
        return libraryPresent ? HomeModel.Library.OTHER_TOOLS : HomeModel.Library.NONE;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        Json.put(o, "format", FORMAT);
        Json.put(o, "edition", edition);
        Json.put(o, "mod_version", modVersion);
        Json.put(o, "set_name", setName);
        Json.put(o, "set_sha256", setSha256);
        Json.put(o, "xx", withXx);
        Json.put(o, "code_sha256", codeSha256);
        Json.put(o, "library_id", libraryId);
        Json.put(o, "library_code_sha256", libraryCodeSha256);
        return o.toString();
    }

    /** The record in {@code json}; {@link InvalidException} when it is not one. */
    public static Installed parse(String json) {
        JSONObject o = Json.object(json);
        if (o == null) throw new InvalidException("installed.json is not a JSON object");
        Long format = Json.integer(o, "format");
        if (format == null || format != FORMAT) throw new InvalidException("installed.json: format " + format);
        Boolean xx = Json.bool(o, "xx");
        if (xx == null) throw new InvalidException("installed.json: no xx");
        return new Installed(Json.string(o, "edition"), Json.string(o, "mod_version"), Json.string(o, "set_name"),
                Json.string(o, "set_sha256"), xx, Json.string(o, "code_sha256"), Json.string(o, "library_id"),
                Json.string(o, "library_code_sha256"));
    }

    /** The record in {@code json}, or null when there is none or it cannot be read (the mod counts as not installed). */
    public static Installed tryParse(String json) {
        if (json == null) return null;
        try {
            return parse(json);
        } catch (InvalidException e) {
            return null;
        }
    }
}
