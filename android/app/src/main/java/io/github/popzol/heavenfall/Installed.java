package io.github.popzol.heavenfall;

import org.json.JSONObject;

/**
 * What is installed, as {@code installed.json} (in the app's private storage) records it, written by the app only
 * after the mod's folder replaced the old one: the edition, the mod's version, the set it was made from (its name and
 * SHA-256, and whether it is the one with MH XX), the SHA-256 of the mod's executable, the digest of the mod's code
 * ({@link ModCode}) when it was applied, and what the code library was built for: the id of the tools that built it,
 * the mod code digest it was built from and the library file it left ({@link LibraryFile}). The library's three are
 * all empty while there is none. Immutable.
 */
public final class Installed {
    public static final String FILE = "installed.json";
    /** 2: the mod's code digest and the library file are recorded (1 had neither: read as nothing installed). */
    public static final int FORMAT = 2;

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
    /** The mod's code digest when it was applied ({@link ModCode}). */
    public final String modCodeSha256;
    public final String libraryId;           // "" when no library was built
    public final String libraryCodeSha256;   // the mod code digest the library was built from; "" when none
    public final LibraryFile libraryFile;    // null when no library was built

    public Installed(String edition, String modVersion, String setName, String setSha256, boolean withXx,
                     String codeSha256, String modCodeSha256, String libraryId, String libraryCodeSha256,
                     LibraryFile libraryFile) {
        this.edition = require(edition, "edition");
        this.modVersion = require(modVersion, "mod_version");
        this.setName = require(setName, "set_name");
        this.setSha256 = sha(setSha256, "set_sha256", false);
        this.withXx = withXx;
        this.codeSha256 = sha(codeSha256, "code_sha256", false);
        this.modCodeSha256 = sha(modCodeSha256, "mod_code_sha256", false);
        this.libraryId = libraryId == null ? "" : libraryId;
        this.libraryCodeSha256 = sha(libraryCodeSha256, "library_code_sha256", true);
        this.libraryFile = libraryFile;
        boolean none = this.libraryId.isEmpty();
        if (none != this.libraryCodeSha256.isEmpty() || none != (libraryFile == null)) {
            throw new InvalidException("installed.json: the library's id, its code's digest and its file go together");
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new InvalidException("installed.json: no " + field);
        return value;
    }

    private static String sha(String value, String field, boolean mayBeEmpty) {
        if (value == null || (value.isEmpty() && mayBeEmpty)) {
            if (mayBeEmpty) return "";
            throw new InvalidException("installed.json: no " + field);
        }
        if (!Json.isSha256(value)) throw new InvalidException("installed.json: " + field + " is not a SHA-256");
        return Json.lower(value);
    }

    /** Whether a library was built and recorded. */
    public boolean hasLibrary() {
        return libraryFile != null;
    }

    /** The same record after the library was built by {@code libraryId} from the mod code {@code builtFrom}. */
    public Installed withLibrary(String libraryId, String builtFrom, LibraryFile file) {
        if (libraryId == null || libraryId.trim().isEmpty() || file == null) {
            throw new InvalidException("installed.json: a library needs its tools' id and its file");
        }
        return new Installed(edition, modVersion, setName, setSha256, withXx, codeSha256, modCodeSha256, libraryId,
                builtFrom, file);
    }

    /**
     * The record after a new mod was applied. The library's record stays: it stays good when the mod's code did not
     * change, and {@link #libraryState} finds it stale when it did.
     */
    public Installed applied(String modVersion, String setName, String setSha256, boolean withXx, String codeSha256,
                             String modCodeSha256) {
        return new Installed(edition, modVersion, setName, setSha256, withXx, codeSha256, modCodeSha256, libraryId,
                libraryCodeSha256, libraryFile);
    }

    /** The record for an edition's first install. */
    public static Installed fresh(String edition, String modVersion, String setName, String setSha256, boolean withXx,
                                  String codeSha256, String modCodeSha256) {
        return new Installed(edition, modVersion, setName, setSha256, withXx, codeSha256, modCodeSha256, "", "", null);
    }

    /**
     * The state of the code library for the home screen: {@code libraryPresent} (the file is in the app's private
     * storage), the id found beside it ({@code onDiskId}, null when none), the id the app's tools make
     * ({@code wantedId}) and whether the file is the one this record names ({@code fileMatches}, {@link LibraryFile}).
     * The library is good when it is that file, built by these tools from the code of the installed mod.
     */
    public HomeModel.Library libraryState(boolean libraryPresent, String onDiskId, String wantedId, boolean fileMatches) {
        if (!libraryPresent) return HomeModel.Library.NONE;
        if (!hasLibrary() || !fileMatches) return HomeModel.Library.UNKNOWN;
        if (onDiskId == null || !onDiskId.equals(wantedId) || !libraryId.equals(wantedId)) return HomeModel.Library.OTHER_TOOLS;
        if (!libraryCodeSha256.equals(modCodeSha256)) return HomeModel.Library.OTHER_MOD;
        return HomeModel.Library.GOOD;
    }

    /** The state with no record at all: a library on the phone that no record names is not one this app built. */
    public static HomeModel.Library libraryStateWithoutRecord(boolean libraryPresent) {
        return libraryPresent ? HomeModel.Library.UNKNOWN : HomeModel.Library.NONE;
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
        Json.put(o, "mod_code_sha256", modCodeSha256);
        Json.put(o, "library_id", libraryId);
        Json.put(o, "library_code_sha256", libraryCodeSha256);
        if (libraryFile != null) {
            Json.put(o, "library_sha256", libraryFile.sha256);
            Json.put(o, "library_size", libraryFile.size);
            Json.put(o, "library_modified_ms", libraryFile.modifiedMs);
        }
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
        String libSha = Json.string(o, "library_sha256");
        Long libSize = Json.integer(o, "library_size");
        Long libTime = Json.integer(o, "library_modified_ms");
        LibraryFile file = null;
        if (libSha != null || libSize != null || libTime != null) {
            if (libSha == null || libSize == null || libTime == null) {
                throw new InvalidException("installed.json: the library file's hash, size and time go together");
            }
            try {
                file = new LibraryFile(libSha, libSize, libTime);
            } catch (IllegalArgumentException e) {
                throw new InvalidException("installed.json: " + e.getMessage());
            }
        }
        return new Installed(Json.string(o, "edition"), Json.string(o, "mod_version"), Json.string(o, "set_name"),
                Json.string(o, "set_sha256"), xx, Json.string(o, "code_sha256"), Json.string(o, "mod_code_sha256"),
                Json.string(o, "library_id"), Json.string(o, "library_code_sha256"), file);
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
