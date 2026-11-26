package io.github.popzol.heavenfall;

/** Builds the game's code library on the phone from the installed mod (the app's own builder service). */
public interface LibraryBuilder {
    /** The library on disk: whether it is there and the id of the tools that built it ("" when none or not known). */
    final class State {
        public final boolean present;
        public final String id;

        public State(boolean present, String id) {
            this.present = present;
            this.id = id == null ? "" : id;
        }
    }

    State state();

    /** The id of the tools this app builds with ("" when the app has none). */
    String wantedId();

    /**
     * Builds the library into {@link JobPaths#libraryDir} from the mod in {@link JobPaths#modDir}, the game and its
     * update. Returns the id of the tools that built it. What was compiled is kept when it stops, so the next build
     * goes on from there.
     */
    String build(JobPaths paths, Control control, Fraction progress) throws UpdateException;
}
