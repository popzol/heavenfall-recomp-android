package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;

/**
 * The code library file a build left, as {@code installed.json} keeps it: its SHA-256, its size and its time. The
 * library lives in the app's private storage, which no other app can write; it is known again by its size and time
 * (one stat, nothing read) and, when either differs, by its whole SHA-256 (the library is about 120 MB: a second or
 * so on a modest phone). Immutable.
 */
public final class LibraryFile {
    public final String sha256;
    public final long size;
    public final long modifiedMs;

    public LibraryFile(String sha256, long size, long modifiedMs) {
        if (!Json.isSha256(sha256)) throw new IllegalArgumentException("the library's hash is not a SHA-256");
        if (size <= 0) throw new IllegalArgumentException("the library's size is not a size");
        this.sha256 = Json.lower(sha256);
        this.size = size;
        this.modifiedMs = modifiedMs;
    }

    /** The file as it is now (it is read whole). */
    public static LibraryFile of(File library) throws IOException {
        if (!library.isFile()) throw new IOException("no library at " + library);
        long size = library.length(), time = library.lastModified();
        String sha = InstalledStore.sha256(library);
        if (library.length() != size || library.lastModified() != time) throw new IOException("the library changed while it was read");
        return new LibraryFile(sha, size, time);
    }

    /** What a check of a file against this record cost: nothing read, or the whole file hashed. */
    public enum Check { SAME_STAT, SAME_HASH, DIFFERENT, MISSING }

    /** Whether {@code library} is the file this record names; see the class comment for what it reads. */
    public Check check(File library) {
        if (library == null || !library.isFile()) return Check.MISSING;
        if (library.length() != size) return Check.DIFFERENT;
        if (library.lastModified() == modifiedMs) return Check.SAME_STAT;
        try {
            return InstalledStore.sha256(library).equals(sha256) ? Check.SAME_HASH : Check.DIFFERENT;
        } catch (IOException e) {
            return Check.DIFFERENT;
        }
    }

    /** The file is this record's. */
    public boolean matches(File library) {
        Check c = check(library);
        return c == Check.SAME_STAT || c == Check.SAME_HASH;
    }
}
