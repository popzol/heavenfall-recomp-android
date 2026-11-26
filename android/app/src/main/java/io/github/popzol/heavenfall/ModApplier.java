package io.github.popzol.heavenfall;

import java.io.File;

/**
 * Makes the mod's folder from a downloaded set (and the release's shared file), the player's game and its update
 * (and MH XX): in {@link JobPaths#staging}, every recorded hash checked, then the folder replaces
 * {@link JobPaths#modDir}. The job writes {@code installed.json} after this returns. A set made for a newer app is
 * {@code new UpdateException(UpdatePlan.Refusal.NEEDS_NEWER_APP)}. Failing leaves the installed mod as it was.
 */
public interface ModApplier {
    /** What applying made. */
    final class Applied {
        /** SHA-256 of the mod's executable (lower-case hex). */
        public final String codeSha256;
        /** The digest of everything in the new mod that becomes code ({@link ModCode}): what a library is built from. */
        public final String modCodeSha256;

        public Applied(String codeSha256, String modCodeSha256) {
            if (!Json.isSha256(codeSha256)) throw new IllegalArgumentException("not a SHA-256: " + codeSha256);
            if (!Json.isSha256(modCodeSha256)) throw new IllegalArgumentException("not a SHA-256: " + modCodeSha256);
            this.codeSha256 = Json.lower(codeSha256);
            this.modCodeSha256 = Json.lower(modCodeSha256);
        }
    }

    /**
     * @param shared the release's shared file, or null when the set names none
     * @param withXx the set is the one with MH XX (the dump is {@link JobPaths#xxDump})
     */
    Applied apply(JobPaths paths, File set, File shared, boolean withXx, Control control, Fraction progress)
            throws UpdateException;
}
