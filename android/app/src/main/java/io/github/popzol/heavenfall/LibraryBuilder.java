package io.github.popzol.heavenfall;

import java.io.File;

/** Builds the game's code library on the phone from the installed mod (the app's own builder service). */
public interface LibraryBuilder {
    /** The library on disk: whether it is there, the id of the tools that built it ("" when none or not known), the file. */
    final class State {
        public final boolean present;
        public final String id;
        /** The library's file, in the app's private storage (null when there is none). */
        public final File file;

        public State(boolean present, String id, File file) {
            this.present = present;
            this.id = id == null ? "" : id;
            this.file = file;
        }

        /** The file is the one {@code record} says the last build left ({@link LibraryFile}). */
        public boolean matches(Installed record) {
            return present && file != null && record != null && record.libraryFile != null
                    && record.libraryFile.matches(file);
        }
    }

    /** What a build made: the id of the tools that built it and the library file it left. */
    final class Built {
        public final String toolsId;
        public final LibraryFile file;

        public Built(String toolsId, LibraryFile file) {
            if (toolsId == null || toolsId.trim().isEmpty() || file == null) {
                throw new IllegalArgumentException("a build gives its tools' id and its file");
            }
            this.toolsId = toolsId.trim();
            this.file = file;
        }
    }

    State state();

    /** The id of the tools this app builds with ("" when the app has none). */
    String wantedId();

    /**
     * Builds the library into {@link JobPaths#libraryDir} from the mod in {@link JobPaths#modDir} alone, the game and
     * its update. What was compiled is kept when it stops, so the next build goes on from there. {@code progress} is
     * told how far it is and, once the build can tell, the time it has left ({@link Fraction#msLeft}).
     */
    Built build(JobPaths paths, Control control, Fraction progress) throws UpdateException;
}
