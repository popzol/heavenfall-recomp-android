package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Puts the launcher's mod right after a swap that was cut short (the phone died, the app was killed between the two
 * renames), once per process and before anything reads the mod: the screens' reading of the phone, the update job and
 * Play all call {@link #once}. A staging folder that a job left is not touched: it is a half-done job
 * ({@link UpdatePlan#jobLeft}), and the next job makes it again.
 */
public final class ModRecovery {
    private static final Set<String> DONE = new HashSet<>();

    private ModRecovery() {
    }

    /**
     * Recovers {@code home}'s mod the first time it is called for that folder in this process (what it found and did),
     * and does nothing after ({@link ModSwap.Recovery#NOTHING}). A folder that cannot be put right is tried again at
     * the next call, and the failure is thrown.
     */
    public static synchronized ModSwap.Recovery once(File home) throws IOException {
        String key = home.getAbsolutePath();
        if (DONE.contains(key)) return ModSwap.Recovery.NOTHING;
        ModSwap.Recovery found = LauncherMod.swap(home).recover();
        DONE.add(key);
        return found;
    }

    /** For the tests: the process starts again. */
    static synchronized void forget() {
        DONE.clear();
    }
}
