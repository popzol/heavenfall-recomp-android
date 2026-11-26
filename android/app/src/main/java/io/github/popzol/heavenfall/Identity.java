package io.github.popzol.heavenfall;

/**
 * What a file the player gave is, as the native layer reads it: MH4U's base game, its update, MH XX, a container of
 * another title or not a game at all; its region; whether it is encrypted; and its revision (the game's and MH XX's
 * RomFS hash, the update's ExeFS hash: the update's exact identity, which a set records).
 */
public final class Identity {
    public enum Kind { NONE, OTHER, GAME, UPDATE, XX }

    /** The reader's own code (0: it read the file; else the error that stopped it, see {@link ApplyError}). */
    public final int rc;
    public final Kind kind;
    /** "usa", "eur", "jpn" or "unknown". */
    public final String region;
    public final boolean encrypted;
    /** 16 upper-case hex digits. */
    public final String titleId;
    /** 64 lower-case hex digits, or "" when the file is encrypted or has none. */
    public final String revision;

    Identity(int rc, Kind kind, String region, boolean encrypted, String titleId, String revision) {
        this.rc = rc;
        this.kind = kind;
        this.region = region;
        this.encrypted = encrypted;
        this.titleId = titleId;
        this.revision = revision;
    }

    /** A made-up identity (the screens' tests, the source rules' tests). */
    public static Identity of(Kind kind, String region, boolean encrypted, String revision) {
        return new Identity(0, kind, region, encrypted, "0000000000000000", revision == null ? "" : revision);
    }

    /** The native layer's answer; {@link IllegalArgumentException} when it is not one. */
    public static Identity parse(String text) {
        KeyValues kv = KeyValues.parse(text);
        Kind kind;
        switch (kv.string("kind")) {
            case "none": kind = Kind.NONE; break;
            case "other": kind = Kind.OTHER; break;
            case "game": kind = Kind.GAME; break;
            case "update": kind = Kind.UPDATE; break;
            case "xx": kind = Kind.XX; break;
            default: throw new IllegalArgumentException("unknown kind " + kv.string("kind"));
        }
        return new Identity(kv.integer("rc"), kind, kv.string("region"), kv.flag("encrypted"),
                TitleIds.normalize(kv.string("title")),
                kv.hash("revision"));
    }

    @Override
    public String toString() {
        return kind + " " + region + (encrypted ? " encrypted" : "") + (revision.isEmpty() ? "" : " " + revision.substring(0, 8));
    }
}
