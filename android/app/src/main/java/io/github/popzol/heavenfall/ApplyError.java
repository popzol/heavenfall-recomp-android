package io.github.popzol.heavenfall;

/**
 * Why making the mod stopped, in the player's words: the native layer's code and the stage it came from (which file to
 * blame) become a {@link Failure} with a reason and an advice (string resources, both languages), and the numbers stay
 * for the log. Every code the layer can return is mapped; an unknown one is {@link Failure#INTERNAL}.
 */
public final class ApplyError {
    /** The codes of heavenfall/c/title_io.h and heavenfall/jni/hf_core.h. */
    public static final int OK = 0, ARG = -1, MEM = -2, IO = -3, PATH = -4, SIZE = -5, FORMAT = -6, CANCEL = -7,
            ENCRYPTED = -8, GAME = -9, NOPATCH = -10, HASH = -11, DAMAGED = -12, NO_UPDATE = -13, NOT_UPDATE = -14,
            OTHER_UPDATE = -15, NEWER = -100, NOT_ANDROID = -101, INCOMPLETE = -102, NO_XX = -103, NO_SHARED = -104,
            READ = -105, WRITE = -106, SPACE = -107;

    /** Where in the run a code came from (hf_core.h's HF_STAGE_*). */
    public static final int STAGE_SET = 0, STAGE_GAME = 1, STAGE_UPDATE = 2, STAGE_XX = 3, STAGE_SHARED = 4, STAGE_WRITE = 5;

    public enum Failure {
        CANCELLED("hf_apply_cancelled", "hf_advice_apply_cancelled"),
        NEEDS_NEWER_APP("hf_refusal_needs_newer_app", "hf_advice_update_app"),
        SET_DAMAGED("hf_apply_set_damaged", "hf_advice_apply_download_again"),
        SET_NOT_FOR_APP("hf_apply_set_not_for_app", "hf_advice_apply_download_again"),
        GAME_ENCRYPTED("hf_problem_game_encrypted", "hf_advice_game_encrypted"),
        GAME_REGION("hf_problem_game_region", "hf_advice_game_region"),
        GAME_NOT_MH4U("hf_problem_game_version", "hf_advice_game_version"),
        GAME_DAMAGED("hf_apply_game_damaged", "hf_advice_apply_dump_again"),
        GAME_DIFFERENT("hf_apply_game_different", "hf_advice_apply_clean_copies"),
        UPDATE_MISSING("hf_problem_update_missing", "hf_advice_update_missing"),
        UPDATE_ENCRYPTED("hf_problem_update_encrypted", "hf_advice_update_encrypted"),
        UPDATE_NOT_UPDATE("hf_problem_update_version", "hf_advice_update_version"),
        UPDATE_OTHER("hf_apply_update_other", "hf_advice_apply_update_other"),
        XX_NEEDED("hf_apply_xx_needed", "hf_advice_apply_xx_needed"),
        XX_ENCRYPTED("hf_problem_xx_encrypted", "hf_advice_xx_encrypted"),
        XX_NOT_XX("hf_problem_xx_version", "hf_advice_xx_version"),
        XX_DAMAGED("hf_apply_xx_damaged", "hf_advice_apply_dump_again"),
        XX_DIFFERENT("hf_problem_xx_version", "hf_advice_xx_version"),
        SHARED_BAD("hf_apply_shared_bad", "hf_advice_apply_download_again"),
        OUTPUT_DIFFERS("hf_apply_output_differs", "hf_advice_apply_clean_copies"),
        READ_FAILED("hf_apply_read_failed", "hf_advice_apply_read_failed"),
        WRITE_FAILED("hf_apply_write_failed", "hf_advice_apply_write_failed"),
        NO_SPACE("hf_apply_no_space", "hf_advice_space"),
        NO_MEMORY("hf_apply_no_memory", "hf_advice_memory"),
        SWAP_FAILED("hf_apply_swap_failed", "hf_advice_apply_swap_failed"),
        INTERNAL("hf_apply_internal", "hf_advice_apply_internal");

        public final String reasonKey;
        public final String adviceKey;

        Failure(String reasonKey, String adviceKey) {
            this.reasonKey = reasonKey;
            this.adviceKey = adviceKey;
        }
    }

    public final Failure failure;
    public final int code;
    public final int stage;
    /** The errno of a read or write that failed, 0 otherwise. */
    public final int errno;

    private ApplyError(Failure failure, int code, int stage, int errno) {
        this.failure = failure;
        this.code = code;
        this.stage = stage;
        this.errno = errno;
    }

    /** An error of this app's own (opening a file, putting the folder in place), with no native code. */
    public static ApplyError of(Failure failure) {
        return new ApplyError(failure, OK, STAGE_WRITE, 0);
    }

    /** A file the player gave that could not be opened: the read failed at {@code stage}. */
    public static ApplyError unreadable(int stage) {
        return new ApplyError(Failure.READ_FAILED, READ, stage, 0);
    }

    /** The failure a native code at a stage is (see {@link Failure}); {@code OK} is not one. */
    public static ApplyError of(int code, int stage, int errno) {
        return new ApplyError(failureOf(code, stage), code, stage, errno);
    }

    private static Failure failureOf(int code, int stage) {
        switch (code) {
            case CANCEL: return Failure.CANCELLED;
            case NEWER: return Failure.NEEDS_NEWER_APP;
            case NOT_ANDROID: return Failure.SET_NOT_FOR_APP;
            case INCOMPLETE: case PATH: case SIZE: return Failure.SET_DAMAGED;
            case NO_XX: return Failure.XX_NEEDED;
            case NO_SHARED: return Failure.SHARED_BAD;
            case READ: case IO: return Failure.READ_FAILED;
            case WRITE: return Failure.WRITE_FAILED;
            case SPACE: return Failure.NO_SPACE;
            case MEM: return Failure.NO_MEMORY;
            case NO_UPDATE: return Failure.UPDATE_MISSING;
            case NOT_UPDATE: return Failure.UPDATE_NOT_UPDATE;
            case OTHER_UPDATE: return Failure.UPDATE_OTHER;
            case ENCRYPTED:
                return stage == STAGE_GAME ? Failure.GAME_ENCRYPTED : stage == STAGE_UPDATE ? Failure.UPDATE_ENCRYPTED
                        : stage == STAGE_XX ? Failure.XX_ENCRYPTED : Failure.INTERNAL;
            case GAME:      // a game that is not the set's: another region, or another title
                return stage == STAGE_GAME ? Failure.GAME_REGION : stage == STAGE_XX ? Failure.XX_NOT_XX : Failure.INTERNAL;
            case FORMAT:
                switch (stage) {
                    case STAGE_SET: return Failure.SET_DAMAGED;
                    case STAGE_GAME: return Failure.GAME_NOT_MH4U;
                    case STAGE_UPDATE: return Failure.UPDATE_NOT_UPDATE;
                    case STAGE_XX: return Failure.XX_NOT_XX;
                    case STAGE_SHARED: return Failure.SHARED_BAD;
                    default: return Failure.INTERNAL;
                }
            case HASH:
                switch (stage) {
                    case STAGE_GAME: return Failure.GAME_DIFFERENT;
                    case STAGE_UPDATE: return Failure.UPDATE_OTHER;
                    case STAGE_XX: return Failure.XX_DIFFERENT;
                    case STAGE_SHARED: return Failure.SHARED_BAD;
                    case STAGE_WRITE: return Failure.OUTPUT_DIFFERS;
                    default: return Failure.INTERNAL;
                }
            case DAMAGED:
                return stage == STAGE_GAME ? Failure.GAME_DAMAGED : stage == STAGE_XX ? Failure.XX_DAMAGED : Failure.INTERNAL;
            default:        // ARG, NOPATCH, a code this app does not know
                return Failure.INTERNAL;
        }
    }

    public Text reason() {
        return failure == Failure.INTERNAL ? new Text(failure.reasonKey, Integer.toString(code)) : new Text(failure.reasonKey);
    }

    public Text advice() {
        return new Text(failure.adviceKey);
    }

    public boolean cancelled() {
        return failure == Failure.CANCELLED;
    }

    /** The line for the log: the failure, the code and the stage, the errno. */
    public String logLine() {
        return failure + " (code " + code + ", stage " + stage + (errno != 0 ? ", errno " + errno : "") + ")";
    }

    @Override
    public String toString() {
        return logLine();
    }
}
