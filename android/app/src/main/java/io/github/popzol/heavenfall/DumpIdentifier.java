package io.github.popzol.heavenfall;

import java.io.File;

/**
 * What a file of the game folder is, as far as the three dumps go: the American MH4U, its update and MH XX. One
 * small seam: the implementation over the checks of the app's own game folder (upstream's {@code Dump}) lives in the
 * Android classes, and the native reader of the three dumps replaces it behind this interface.
 */
public interface DumpIdentifier {
    /** The answer for {@code file}; {@link Identity#NOT_A_DUMP} for anything that is none of the three. */
    Identity identify(File file);

    final class Identity {
        public enum Kind { NOT_A_DUMP, GAME, UPDATE, XX }

        /** USA is the American game; EUR the European one; OTHER any other game of the family (MH4G, MH XX's region). */
        public enum Region { USA, EUR, OTHER }

        public static final Identity NOT_A_DUMP = new Identity(Kind.NOT_A_DUMP, Region.OTHER, false, -1);

        public final Kind kind;
        public final Region region;
        /** The file is not a decrypted copy. */
        public final boolean encrypted;
        /** The update's title version from its TMD (1.3.0 is {@link DumpScan#UPDATE_TITLE_VERSION}); -1 when not known. */
        public final int titleVersion;

        public Identity(Kind kind, Region region, boolean encrypted, int titleVersion) {
            this.kind = kind;
            this.region = region;
            this.encrypted = encrypted;
            this.titleVersion = titleVersion;
        }
    }
}
