package io.github.popzol.heavenfall;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the build's named stop means for the player: {@code phone_build.py}'s {@code @@error STAGE MESSAGE} (and the
 * builder service's own, for a process Android closed), as a failure with its text and advice in both languages.
 * <ul>
 *   <li>{@code space N MB more free space needed (...)}: the build's own count of the room it lacks;</li>
 *   <li>{@code memory the translation ... needs about N MB ... this device has M MB in all (with its swap)}: a phone
 *   that can never build; any other {@code memory} stop is one the phone ran out of memory in;</li>
 *   <li>{@code compile the phone ran out of memory compiling part of the game's code (...)}: the end of the build's
 *   retries (a part stopped by Android with nothing else compiling); any other {@code compile} stop is the tool's.</li>
 * </ul>
 */
public final class BuildFailures {
    private static final Pattern SPACE = Pattern.compile("^(\\d+) MB more free space needed");
    private static final Pattern NEVER = Pattern.compile("needs about (\\d+) MB of memory and this device has (\\d+) MB in all");
    private static final String COMPILE_MEMORY = "the phone ran out of memory compiling";

    private BuildFailures() {
    }

    public static UpdateFailure forStage(String stage) {
        return forError(stage, "");
    }

    /** The failure of a stop in {@code stage} with the build's words {@code text}. */
    public static UpdateFailure forError(String stage, String text) {
        if (stage == null) return UpdateFailure.BUILD_FAILED;
        String words = text == null ? "" : text.trim();
        switch (stage) {
            case "space":
                return UpdateFailure.BUILD_SPACE;
            case "memory":
                return NEVER.matcher(words).find() ? UpdateFailure.BUILD_MEMORY_NEVER : UpdateFailure.BUILD_MEMORY;
            case "download":
            case "tools":
                return UpdateFailure.BUILD_TOOLS;
            case "extract":
            case "version":
            case "mods":
                return UpdateFailure.BUILD_GAME_FILES;
            case "compile":
                return words.startsWith(COMPILE_MEMORY) ? UpdateFailure.BUILD_COMPILE_MEMORY : UpdateFailure.BUILD_COMPILE;
            case "recompile":
            case "shaders":
            case "link":
                return UpdateFailure.BUILD_COMPILE;
            case "install":
            case "dest":
                return UpdateFailure.BUILD_INSTALL;
            default:
                return UpdateFailure.BUILD_FAILED;
        }
    }

    /** The stop as the job's failure: the build's numbers in its text where it gives them. */
    public static UpdateException exception(String stage, String text) {
        UpdateFailure f = forError(stage, text);
        String words = text == null ? "" : text.trim();
        String detail = stage + ": " + words;
        if (f == UpdateFailure.BUILD_SPACE) {
            Matcher m = SPACE.matcher(words);
            if (m.find()) return new UpdateException(f, new Text("hf_fail_build_space_mb", m.group(1)), f.advice(), detail, null);
        } else if (f == UpdateFailure.BUILD_MEMORY_NEVER) {
            Matcher m = NEVER.matcher(words);
            if (m.find()) {
                return new UpdateException(f, new Text("hf_fail_build_memory_never_mb", m.group(2), m.group(1)), f.advice(),
                        detail, null);
            }
        }
        return new UpdateException(f, detail);
    }
}
