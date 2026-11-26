package io.github.popzol.heavenfall.app;

import android.content.Context;

import com.mh4u.port.HeavenfallBridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.github.popzol.heavenfall.BuildFailures;
import io.github.popzol.heavenfall.Control;
import io.github.popzol.heavenfall.Fraction;
import io.github.popzol.heavenfall.JobPaths;
import io.github.popzol.heavenfall.LibraryBuilder;
import io.github.popzol.heavenfall.UpdateException;
import io.github.popzol.heavenfall.UpdateFailure;

/**
 * The code library built by upstream's builder service (tools, translation, compiling, installing; paused by heat and
 * battery, resumable), run under the update job's bar: started with the game, its update and the mod's folder, watched
 * until it ends, cancelled when the job is.
 */
public final class PhoneLibraryBuilder implements LibraryBuilder {
    /** phone_build.py's option that takes the mod's folder. */
    static final String MOD_FLAG = "--mod";
    private static final long START_WAIT_MS = 15_000;
    private static final long STOP_WAIT_MS = 60_000;

    private final Context context;
    private final File libraryDir;

    public PhoneLibraryBuilder(Context context, File libraryDir) {
        this.context = context.getApplicationContext();
        this.libraryDir = libraryDir;
    }

    private File library() {
        return new File(libraryDir, HeavenfallBridge.libraryFileName());
    }

    /** The id the builder writes beside the library: the library's name with ".id" in place of its extension. */
    private File idFile() {
        String name = HeavenfallBridge.libraryFileName();
        int dot = name.lastIndexOf('.');
        return new File(libraryDir, (dot > 0 ? name.substring(0, dot) : name) + ".id");
    }

    private String idOnDisk() {
        try {
            File f = idFile();
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public State state() {
        return new State(library().canRead(), idOnDisk());
    }

    @Override
    public String wantedId() {
        return HeavenfallBridge.wantedId(context);
    }

    @Override
    public String build(JobPaths paths, Control control, Fraction progress) throws UpdateException {
        if (HeavenfallBridge.buildBusy()) throw new UpdateException(UpdateFailure.BUILD_FAILED, "a build is running");
        HeavenfallBridge.buildAcknowledged();   // the end of an earlier build is not this one's
        if (!libraryDir.isDirectory() && !libraryDir.mkdirs() && !libraryDir.isDirectory()) {
            throw new UpdateException(UpdateFailure.BUILD_INSTALL, libraryDir.getPath());
        }
        Semaphore changed = new Semaphore(0);
        Runnable wake = changed::release;
        HeavenfallBridge.listen(wake);
        try {
            HeavenfallBridge.startBuild(context, paths.gameDump, paths.updateDump, libraryDir, MOD_FLAG, paths.modDir);
            HeavenfallBridge.BuildStatus s = awaitStart(changed, control);
            while (s.active) {
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

    private String result(HeavenfallBridge.BuildStatus s, Control control, Fraction progress) throws UpdateException {
        switch (s.status) {
            case "done": {
                progress.fraction(1.0);
                String id = idOnDisk();
                return id.isEmpty() ? wantedId() : id;
            }
            case "cancelled":
                // the player's cancel is the job's own; any other stop is the system's (a service time limit)
                throw new UpdateException(control.isCancelled() ? UpdateFailure.CANCELLED : UpdateFailure.INTERRUPTED, "build");
            default:
                throw new UpdateException(BuildFailures.forStage(s.errorStage), s.errorStage + ": " + s.errorText);
        }
    }
}
