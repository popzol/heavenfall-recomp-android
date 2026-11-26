package io.github.popzol.heavenfall;

/**
 * A downloaded set's head, read without applying it: its format, edition, version and region, whether it needs MH XX,
 * the shared file it names (hash and size) and the update it was made for (title and ExeFS hash). A format newer than
 * the applier reads is {@link #needsNewerApp}: only that and the format are known then.
 */
public final class SetInfo {
    /** 0 when the head was read; else the error that stopped it (see {@link ApplyError}): not a set, cut or damaged. */
    public final int rc;
    public final int format;
    public final boolean needsNewerApp;
    /** "usa" or "eur". */
    public final String region;
    public final String edition;
    public final String version;
    public final boolean needsXx;
    public final String xxTitleId;
    public final String xxRomfsSha256;
    public final boolean needsShared;
    public final long sharedSize;
    public final String sharedSha256;
    /** The set's code comes from MH4U's update (format 4: the sets of this app). */
    public final boolean needsUpdate;
    public final String updateTitleId;
    public final String updateExefsSha256;
    public final int files;

    private SetInfo(KeyValues kv) {
        rc = kv.integer("rc");
        format = kv.integer("format");
        needsNewerApp = kv.flag("needs_newer_app");
        region = kv.string("region");
        edition = kv.string("edition");
        version = kv.string("version");
        needsXx = kv.flag("xx");
        xxTitleId = TitleIds.normalize(kv.string("xx_title"));
        xxRomfsSha256 = kv.hash("xx_romfs_sha256");
        needsShared = kv.flag("shared");
        sharedSize = kv.longValue("shared_size");
        sharedSha256 = kv.hash("shared_sha256");
        needsUpdate = kv.flag("update");
        updateTitleId = TitleIds.normalize(kv.string("update_title"));
        updateExefsSha256 = kv.hash("update_exefs_sha256");
        files = kv.integer("files");
    }

    /** The native layer's answer; {@link IllegalArgumentException} when it is not one. */
    public static SetInfo parse(String text) {
        return new SetInfo(KeyValues.parse(text));
    }

    /** The head was read and the set is one this app can apply (a format it knows, made for the recompiled game). */
    public boolean usable() {
        return rc == 0 && !needsNewerApp && needsUpdate;
    }

    /** The part {@code UpdatePlan.Input.setNeedsNewerApp} takes. */
    public boolean setNeedsNewerApp() {
        return needsNewerApp;
    }
}
