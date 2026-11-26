package io.github.popzol.heavenfall.app;

import android.content.Context;

import java.io.File;

import com.mh4u.port.HeavenfallBridge;

import io.github.popzol.heavenfall.JobPaths;
import io.github.popzol.heavenfall.LauncherMod;

/**
 * Where the launcher's files are. The app's own folder ({@code HeavenfallBridge.home}) holds {@code heavenfall/} (the
 * downloads, {@code installed.json}, the job's record and its resume note) and the user folder the game runs from:
 * the mod itself is {@code <home>/user/load/mods/Heavenfall/}, made in its staging sibling and swapped in there
 * ({@link LauncherMod}), which is where the game and the code library's build read it. The code library goes in the
 * game folder the player chose: it is where the entry flow of the game looks for it.
 */
public final class Locations {
    private Locations() {
    }

    /** The app's own folder, as the game uses it (it can move to the private one: ask again, never keep it). */
    public static File home(Context c) {
        return HeavenfallBridge.home(c);
    }

    public static File base(Context c) {
        return new File(home(c), "heavenfall");
    }

    /** The saved update request: where the app finds the job again after it replaced itself. */
    public static File jobFile(Context c) {
        return new File(base(c), "job.json");
    }

    /**
     * The usual paths of a job: downloads, {@code installed.json}, {@code resume.json} and the job's record in the
     * launcher's folder, the mod's folder and its staging sibling in the game's mods folder; the dumps and the
     * library's folder (the game folder) are where the player put them.
     */
    public static JobPaths paths(Context c, File gameDump, File updateDump, File xxDump, File libraryDir) {
        File base = base(c);
        File home = home(c);
        return new JobPaths(new File(base, "downloads"), LauncherMod.stagingDir(home), LauncherMod.modDir(home),
                new File(base, "installed.json"), new File(base, "resume.json"), jobFile(c), gameDump, updateDump,
                xxDump, libraryDir);
    }

    /** Puts the mod right after a swap that was cut short, once per process (see {@code ModRecovery}); off the main thread. */
    public static void recoverMod(Context c) {
        try {
            io.github.popzol.heavenfall.ModRecovery.once(home(c));
        } catch (java.io.IOException e) {
            android.util.Log.w(UpdateService.TAG, "the mod's folders could not be put right: " + e);
        }
    }
}
