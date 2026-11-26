package io.github.popzol.heavenfall;

/**
 * Why an update step stopped, each with a reason text and an advice text (string resource names, in both languages).
 * Every failure leaves the update resumable: a download keeps its ".part" file, an install or a build can be started
 * again, and nothing already installed is touched until the mod is applied.
 */
public enum UpdateFailure {
    // the network and the downloads
    NETWORK("hf_fail_network", "hf_advice_resume"),
    SERVER("hf_fail_server", "hf_advice_try_later"),
    UNTRUSTED_ADDRESS("hf_fail_untrusted_address", "hf_advice_try_later"),
    BAD_SIZE("hf_fail_bad_size", "hf_advice_download_again"),
    BAD_DIGEST("hf_fail_bad_digest", "hf_advice_download_again"),
    NO_SPACE("hf_fail_no_space", "hf_advice_space"),
    CANNOT_WRITE("hf_fail_cannot_write", "hf_advice_space"),
    CANCELLED("hf_fail_cancelled", "hf_advice_resume"),
    INTERRUPTED("hf_fail_interrupted", "hf_advice_resume"),
    UNEXPECTED("hf_fail_unexpected", "hf_advice_resume"),
    /** The game is open in its own task: no job starts until it is closed ({@link GameOpen}). */
    GAME_OPEN("hf_fail_game_open", "hf_advice_close_game"),
    // the app's own update
    SELF_UNREADABLE("hf_fail_self_unreadable", "hf_advice_download_again"),
    SELF_OTHER_APP("hf_fail_self_other_app", "hf_advice_download_again"),
    SELF_NOT_NEWER("hf_fail_self_not_newer", "hf_advice_try_later"),
    SELF_OTHER_SIGNER("hf_fail_self_other_signer", "hf_advice_other_signer"),
    SELF_ABORTED("hf_fail_self_aborted", "hf_advice_resume"),
    SELF_BLOCKED("hf_fail_self_blocked", "hf_advice_allow_installs"),
    SELF_CONFLICT("hf_fail_self_conflict", "hf_advice_other_signer"),
    SELF_INCOMPATIBLE("hf_fail_self_incompatible", "hf_advice_try_later"),
    SELF_INVALID("hf_fail_self_invalid", "hf_advice_download_again"),
    SELF_STORAGE("hf_fail_self_storage", "hf_advice_space"),
    SELF_NO_ANSWER("hf_fail_self_no_answer", "hf_advice_resume"),
    SELF_NOT_LEFT("hf_fail_self_not_left", "hf_advice_leave_the_app"),
    SELF_FAILED("hf_fail_self_failed", "hf_advice_resume"),
    // applying the mod and building the library
    APPLY_FAILED("hf_fail_apply_failed", "hf_advice_resume"),
    BUILD_SPACE("hf_fail_build_space", "hf_advice_space"),
    BUILD_MEMORY("hf_fail_build_memory", "hf_advice_memory"),
    /** The phone has less memory in all (with its swap) than the translation's peak: it can never build. */
    BUILD_MEMORY_NEVER("hf_fail_build_memory_never", "hf_advice_memory_never"),
    /** A part of the code Android stopped compiling with nothing else running, at the end of the build's retries. */
    BUILD_COMPILE_MEMORY("hf_fail_build_compile_memory", "hf_advice_compile_memory"),
    BUILD_BATTERY("hf_fail_build_battery", "hf_advice_battery"),
    /** The installed mod's code is not the one installed.json recorded: the mod is made again, never built as it is. */
    MOD_CHANGED("hf_fail_mod_changed", "hf_advice_reinstall"),
    BUILD_TOOLS("hf_fail_build_tools", "hf_advice_resume"),
    BUILD_GAME_FILES("hf_fail_build_game_files", "hf_advice_game_files"),
    BUILD_COMPILE("hf_fail_build_compile", "hf_advice_share_log"),
    BUILD_INSTALL("hf_fail_build_install", "hf_advice_space"),
    BUILD_FAILED("hf_fail_build_failed", "hf_advice_resume");

    private final String reasonKey;
    private final String adviceKey;

    UpdateFailure(String reasonKey, String adviceKey) {
        this.reasonKey = reasonKey;
        this.adviceKey = adviceKey;
    }

    public String reasonKey() {
        return reasonKey;
    }

    public String adviceKey() {
        return adviceKey;
    }

    /** The reason as a text; {@link #SERVER} takes the HTTP status as its one argument. */
    public Text reason(String... args) {
        return new Text(reasonKey, args);
    }

    public Text advice() {
        return new Text(adviceKey);
    }
}
