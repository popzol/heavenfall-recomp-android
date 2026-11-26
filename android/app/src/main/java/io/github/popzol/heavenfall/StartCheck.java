package io.github.popzol.heavenfall;

/**
 * The check before every start of the game (the entry flow, after Play): the mod is installed, its code is the one
 * {@code installed.json} recorded when the mod was made, and the library in the private storage is the file the last
 * build left (its record), made by this app's tools from that code. Its cost: the mod's code is hashed (about 25 MB,
 * a fraction of a second: {@link ModCode}); the library is one {@code stat} when its size and date are the recorded
 * ones, else hashed whole (about 120 MB, about a second: {@link LibraryFile}).
 */
public final class StartCheck {
    public enum Problem {
        NOT_INSTALLED("hf_state_not_installed"),
        MOD_CHANGED("hf_state_mod_changed"),
        MOD_MISSING("hf_state_mod_missing"),
        NO_LIBRARY("hf_state_build_needed"),
        LIBRARY_UNKNOWN("hf_state_rebuild_unknown"),
        OTHER_TOOLS("hf_state_rebuild_other_tools_noid"),
        OTHER_MOD("hf_state_rebuild_other_mod");

        /** What the player is told (the home screen then offers what fixes it). */
        public final Text text;

        Problem(String key) {
            this.text = new Text(key);
        }
    }

    private StartCheck() {
    }

    /** Null when the game may start. */
    public static Problem check(Installed installed, String modCodeOnDisk, LibraryBuilder.State library, String wantedId) {
        if (installed == null) return Problem.NOT_INSTALLED;
        switch (PhoneFacts.modCode(installed, modCodeOnDisk)) {
            case MISSING:
                return Problem.MOD_MISSING;
            case CHANGED:
                return Problem.MOD_CHANGED;
            default:
                break;
        }
        switch (installed.libraryState(library.present, library.id, wantedId, library.matches(installed))) {
            case NONE:
                return Problem.NO_LIBRARY;
            case UNKNOWN:
                return Problem.LIBRARY_UNKNOWN;
            case OTHER_TOOLS:
                return Problem.OTHER_TOOLS;
            case OTHER_MOD:
                return Problem.OTHER_MOD;
            default:
                return null;
        }
    }
}
