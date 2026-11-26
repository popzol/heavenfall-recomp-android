package io.github.popzol.heavenfall.app;

import android.content.Context;

import com.mh4u.port.HeavenfallBridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.github.popzol.heavenfall.BuildEta;
import io.github.popzol.heavenfall.BuildFailures;
import io.github.popzol.heavenfall.Control;
import io.github.popzol.heavenfall.Fraction;
import io.github.popzol.heavenfall.JobPaths;
import io.github.popzol.heavenfall.LibraryBuilder;
import io.github.popzol.heavenfall.LibraryFile;
import io.github.popzol.heavenfall.Tuning;
import io.github.popzol.heavenfall.UpdateException;
import io.github.popzol.heavenfall.UpdateFailure;

/**
 * The code library built by upstream's builder service (tools, translation, compiling, installing; paused by heat and
 * battery, resumable), run under the update job's bar: started with the game, its update and the launcher's mod alone,
 * watched until it ends, cancelled when the job is. The library is built into the app's private storage
 * ({@link Locations#libraryDir}), where the game loads it from; nothing in the game folder is built or read as code.
 */
public final class PhoneLibraryBuilder implements LibraryBuilder {
    /**
     * phone_build.py's option that takes one mod's own folder (with its exefs): the build compiles the launcher's mod
     * alone, named by its folder, whatever else is in the mods folder or the load order.
     */
    static final String MOD_FLAG = "--mod";
    private static final long START_WAIT_MS = 15_000;
    private static final long STOP_WAIT_MS = 60_000;

    private final Context context;
    private final Tuning tuning;

    public PhoneLibraryBuilder(Context context, Tuning tuning) {
        this.context = context.getApplicationContext();
        this.tuning = tuning;
    }

    static File library(Context c) {
        return new File(Locations.libraryDir(c), HeavenfallBridge.libraryFileName());
    }

    /** The id the builder writes beside the library: the library's name with ".id" in place of its extension. */
    static File idFile(Context c) {
        String name = HeavenfallBridge.libraryFileName();
        int dot = name.lastIndexOf('.');
        return new File(Locations.libraryDir(c), (dot > 0 ? name.substring(0, dot) : name) + ".id");
    }

    static String idOnDisk(Context c) {
        try {
            File f = idFile(c);
            if (!f.isFile() || f.length() > 4096) return "";
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** The library in the private storage as it is now (whether it is the one the last build left: State#matches). */
    static State stateOf(Context c) {
        File library = library(c);
        boolean present = library.isFile() && library.canRead();
        return new State(present, idOnDisk(c), present ? library : null);
    }

    @Override
    public State state() {
        return stateOf(context);
    }

    @Override
    public String wantedId() {
        return HeavenfallBridge.wantedId(context);
    }

    @Override
    public Built build(JobPaths paths, Control control, Fraction progress) throws UpdateException {
        if (HeavenfallBridge.buildBusy()) throw new UpdateException(UpdateFailure.BUILD_FAILED, "a build is running");
        HeavenfallBridge.buildAcknowledged();   // the end of an earlier build is not this one's
        File libraryDir = paths.libraryDir;
        if (!libraryDir.isDirectory() && !libraryDir.mkdirs() && !libraryDir.isDirectory()) {
            throw new UpdateException(UpdateFailure.BUILD_INSTALL, libraryDir.getPath());
        }
        BuildEta eta = BuildEta.of(tuning);
        eta.start(System.currentTimeMillis(), HeavenfallBridge.objectsKept(context));
        progress.msLeft(eta.msLeft(System.currentTimeMillis()));
        Semaphore changed = new Semaphore(0);
        Runnable wake = changed::release;
        HeavenfallBridge.listen(wake);
        try {
            HeavenfallBridge.startBuild(context, paths.gameDump, paths.updateDump, libraryDir, MOD_FLAG, paths.modDir);
            HeavenfallBridge.BuildStatus s = awaitStart(changed, control);
            while (s.active) {
                long now = System.currentTimeMillis();
                eta.sample(now, s.stage, s.done, s.total);
                progress.msLeft(eta.msLeft(now));
                progress.fraction(Math.max(0, s.percent) / 100.0);
                if (control.isCancelled()) {
                    HeavenfallBridge.cancelBuild(context);
                    awaitStop(changed);
                    throw new UpdateException(UpdateFailure.CANCELLED, "build");
                }
                changed.tryAcquire(1, TimeUnit.SECONDS);
                changed.drainPermits();
                s = HeavenfallBridge.buildStatus();
            }
            return result(s, control, progress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HeavenfallBridge.cancelBuild(context);
            throw new UpdateException(UpdateFailure.INTERRUPTED, "build", e);
        } finally {
            HeavenfallBridge.unlisten(wake);
        }
    }

    /** The service sets "running" on its own thread: wait until the build shows itself, or ends at once. */
    private HeavenfallBridge.BuildStatus awaitStart(Semaphore changed, Control control)
            throws InterruptedException, UpdateException {
        long deadline = System.currentTimeMillis() + START_WAIT_MS;
        HeavenfallBridge.BuildStatus s = HeavenfallBridge.buildStatus();
        while (!s.active && s.status.equals("idle")) {
            control.check();
            if (System.currentTimeMillis() > deadline) throw new UpdateException(UpdateFailure.BUILD_FAILED, "did not start");
            changed.tryAcquire(250, TimeUnit.MILLISECONDS);
            s = HeavenfallBridge.buildStatus();
        }
        return s;
    }

    private void awaitStop(Semaphore changed) throws InterruptedException {
        long deadline = System.currentTimeMillis() + STOP_WAIT_MS;
        while (HeavenfallBridge.buildBusy() && System.currentTimeMillis() < deadline) {
            changed.tryAcquire(500, TimeUnit.MILLISECONDS);
        }
    }

    private Built result(HeavenfallBridge.BuildStatus s, Control control, Fraction progress) throws UpdateException {
        switch (s.status) {
            case "done": {
                progress.fraction(1.0);
                String id = idOnDisk(context);
                if (id.isEmpty()) throw new UpdateException(UpdateFailure.BUILD_INSTALL, "no id beside the library");
                try {
                    // (about 120 MB hashed once, here: the record installed.json keeps, checked before every start)
                    return new Built(id, LibraryFile.of(library(context)));
                } catch (IOException e) {
                    throw new UpdateException(UpdateFailure.BUILD_INSTALL, "the library cannot be read", e);
                }
            }
            case "cancelled":
                // the player's cancel is the job's own; any other stop is the system's (a service time limit)
                throw new UpdateException(control.isCancelled() ? UpdateFailure.CANCELLED : UpdateFailure.INTERRUPTED, "build");
            default:
                throw new UpdateException(BuildFailures.forStage(s.errorStage), s.errorStage + ": " + s.errorText);
        }
    }
}
