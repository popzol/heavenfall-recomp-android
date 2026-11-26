package io.github.popzol.heavenfall.app;

import android.content.Context;

import java.io.File;

import com.mh4u.port.HeavenfallBridge;
import com.mh4u.port.HeavenfallScreens;

import io.github.popzol.heavenfall.JobPaths;
import io.github.popzol.heavenfall.LauncherMod;

/**
 * Where the launcher's files are, every one of them fixed here (no path is read back from a file).
 *
 * <ul>
 *   <li>The app's private storage ({@code getFilesDir()}), on every Android version: {@code heavenfall/} holds the
 *       downloads, {@code installed.json}, the job's record ({@code job.json}) and its resume note; the code library
 *       ({@code libmh4u_code_native.so} and its {@code .id}) is built there, where the game loads it from.</li>
 *   <li>The app's home ({@code HeavenfallBridge.home}: the private storage on Android 9 and 10, the app's folder in
 *       Android/data from Android 11): the user folder the game runs from, with the mod itself in
 *       {@code user/load/mods/Heavenfall/}, made in its staging sibling and swapped in ({@link LauncherMod}).</li>
 *   <li>The game folder the player chose: only the dumps are read there.</li>
 * </ul>
 */
public final class Locations {
    private Locations() {
    }

    /** The app's own folder, as the game uses it (ask again, never keep it). */
    public static File home(Context c) {
        return HeavenfallBridge.home(c);
    }

    /** The launcher's records and downloads, in the app's private storage. */
    public static File base(Context c) {
        return new File(c.getFilesDir(), "heavenfall");
    }

    /** Where the code library is built and loaded from: the app's private storage. */
    public static File libraryDir(Context c) {
        return c.getFilesDir();
    }

    public static File installedFile(Context c) {
        return new File(base(c), "installed.json");
    }

    /** The game folder the player chose, or null (none, or not there). */
    public static File gameFolder(Context c) {
        File saved = HeavenfallScreens.savedFolder(c);
        return saved != null && saved.isDirectory() ? saved : null;
    }

    /** The launcher's own places of a job. */
    public static JobPaths.Places places(Context c) {
        File base = base(c);
        File home = home(c);
        return new JobPaths.Places(new File(base, "downloads"), LauncherMod.stagingDir(home), LauncherMod.modDir(home),
                installedFile(c), new File(base, "resume.json"), new File(base, "job.json"), libraryDir(c));
    }

    /** A job's paths: the launcher's places and the dumps found in the game folder. */
    public static JobPaths paths(Context c, File gameDump, File updateDump, File xxDump) {
        return new JobPaths(places(c), gameDump, updateDump, xxDump);
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
