package io.github.popzol.heavenfall;

/**
 * Whether the game's activity may start the game without the entry flow (its checks, the launcher's {@link StartCheck})
 * having run in this process. Only adb's {@code am start} of a test run may, and only in a debug build: the referrer an
 * intent names can be set by any caller (Intent.EXTRA_REFERRER), so in a release build it opens nothing.
 */
public final class EntryRule {
    public static final String SHELL = "com.android.shell";

    private EntryRule() {
    }

    /**
     * @param entered      the entry flow started the game in this process
     * @param referrerHost the start's referrer as Android reports it (null when none)
     * @param debuggable   the app is a debug build (ApplicationInfo.FLAG_DEBUGGABLE)
     */
    public static boolean mayStart(boolean entered, String referrerHost, boolean debuggable) {
        return entered || (debuggable && SHELL.equals(referrerHost));
    }
}
