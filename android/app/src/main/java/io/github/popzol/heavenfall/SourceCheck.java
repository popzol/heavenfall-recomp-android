package io.github.popzol.heavenfall;

import io.github.popzol.heavenfall.HomeModel.Source;

/**
 * What the home screen's checklist says about a file the player gave, from what the native layer made of it: the base
 * game, MH4U's update and MH XX each map onto {@link Source}. A null identity is no file at all (MISSING); a file that
 * is something else is WRONG_VERSION ("this is not ..."); a decrypted-only dump that is not decrypted is ENCRYPTED.
 */
public final class SourceCheck {
    private SourceCheck() {
    }

    /**
     * The base game: MH4U's of {@code wantedRegion} ("usa"), decrypted. The European game is WRONG_REGION; MH XX, an
     * update or any other file is WRONG_VERSION.
     */
    public static Source game(Identity id, String wantedRegion) {
        if (id == null) return Source.MISSING;
        if (id.kind != Identity.Kind.GAME) return Source.WRONG_VERSION;
        if (id.encrypted) return Source.ENCRYPTED;
        return id.region.equals(wantedRegion) ? Source.GOOD : Source.WRONG_REGION;
    }

    /**
     * MH4U's update: of {@code wantedRegion}, decrypted, and (when {@code wantedExefsSha256} is not empty: the one the
     * set was made for) the same revision. The other region's update is WRONG_REGION; another revision, the game or
     * any other file is WRONG_VERSION.
     */
    public static Source update(Identity id, String wantedRegion, String wantedExefsSha256) {
        if (id == null) return Source.MISSING;
        if (id.kind != Identity.Kind.UPDATE) return Source.WRONG_VERSION;
        if (id.encrypted) return Source.ENCRYPTED;
        if (!id.region.equals(wantedRegion)) return Source.WRONG_REGION;
        if (!wantedExefsSha256.isEmpty() && !wantedExefsSha256.equalsIgnoreCase(id.revision)) return Source.WRONG_VERSION;
        return Source.GOOD;
    }

    /**
     * MH XX, decrypted, and (when {@code wantedRomfsSha256} is not empty: the one the set was made with) the same
     * revision. Any other file is WRONG_VERSION ("this copy cannot be used").
     */
    public static Source xx(Identity id, String wantedRomfsSha256) {
        if (id == null) return Source.MISSING;
        if (id.kind != Identity.Kind.XX) return Source.WRONG_VERSION;
        if (id.encrypted) return Source.ENCRYPTED;
        if (!wantedRomfsSha256.isEmpty() && !wantedRomfsSha256.equalsIgnoreCase(id.revision)) return Source.WRONG_VERSION;
        return Source.GOOD;
    }
}
