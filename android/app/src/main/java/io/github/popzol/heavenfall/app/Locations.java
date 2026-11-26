package io.github.popzol.heavenfall.app;

import android.content.Context;

import java.io.File;

import com.mh4u.port.HeavenfallBridge;

import io.github.popzol.heavenfall.JobPaths;

/** The launcher's files, all under one folder of the app's own folder. */
public final class Locations {
    private Locations() {
    }

    public static File base(Context c) {
        return new File(HeavenfallBridge.home(c), "heavenfall");
    }

    /** The saved update request: where the app finds the job again after it replaced itself. */
    public static File jobFile(Context c) {
        return new File(base(c), "job.json");
    }

    /**
     * The usual paths of a job: downloads, staging, the mod's folder, {@code installed.json}, {@code resume.json}
     * and the job's record in the launcher's folder; the dumps and the library's folder are where the player and the
     * app's settings put them.
     */
    public static JobPaths paths(Context c, File gameDump, File updateDump, File xxDump, File libraryDir) {
        File base = base(c);
        return new JobPaths(new File(base, "downloads"), new File(base, "staging"), new File(base, "mod"),
                new File(base, "installed.json"), new File(base, "resume.json"), jobFile(c), gameDump, updateDump,
                xxDump, libraryDir);
    }
}
