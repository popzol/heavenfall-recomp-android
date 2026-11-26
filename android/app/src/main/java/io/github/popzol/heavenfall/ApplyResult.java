package io.github.popzol.heavenfall;

/** What the native layer's apply answered: where it stopped (or that it did not), and what it made. */
public final class ApplyResult {
    public final int rc;
    public final int stage;
    public final int errno;
    /** Which source's hash differed (the set's source kind), -1 when none did. */
    public final int sourceKind;
    /** The file being made when it stopped. */
    public final String path;
    public final int files;
    public final long bytes;
    /** SHA-256 of the mod's code as written, or "". */
    public final String codeSha256;

    private ApplyResult(KeyValues kv) {
        rc = kv.integer("rc");
        stage = kv.integer("stage");
        errno = kv.integer("errno");
        sourceKind = kv.integer("source");
        path = kv.string("path");
        files = kv.integer("files");
        bytes = kv.longValue("bytes");
        codeSha256 = kv.hash("code_sha256");
    }

    /** The native layer's answer; {@link IllegalArgumentException} when it is not one. */
    public static ApplyResult parse(String text) {
        return new ApplyResult(KeyValues.parse(text));
    }

    public boolean ok() {
        return rc == 0;
    }

    /** The error of a run that did not finish; null for one that did. */
    public ApplyError error() {
        return ok() ? null : ApplyError.of(rc, stage, errno);
    }
}
