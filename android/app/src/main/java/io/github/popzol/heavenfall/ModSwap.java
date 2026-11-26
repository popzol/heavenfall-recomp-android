package io.github.popzol.heavenfall;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Puts a new mod folder in place of the installed one so that a stop at any moment (the app killed, the phone off)
 * leaves the old mod whole or the new one whole, never a half of either.
 *
 * <p>Three folders in one parent (so each move is one rename): the installed mod, the staging folder the new one is
 * made in, and a folder for the old one while it is replaced. {@link #commit} does:
 * <ol>
 * <li>installed to old (when there is an installed mod);</li>
 * <li>staging to installed;</li>
 * <li>the old one deleted.</li>
 * </ol>
 * Between 1 and 2 there is no installed mod: {@link #recover}, which runs before anything else touches the folders,
 * puts the old one back. After 2 the new one is whole and only the old one is left to delete. A staging folder left
 * by a run that did not reach the swap is not touched by recovery: the home screen shows it as a job left half-done
 * ({@code UpdatePlan.jobLeft}) and the next run starts by deleting it.
 *
 * <p>The files of the staging folder are synced by the native layer as it writes them; the renames are the file
 * system's own (journalled), which is what the guarantee above relies on.
 */
public final class ModSwap {
    /** What {@link #recover} found and did. */
    public enum Recovery {
        /** Nothing was out of place. */
        NOTHING,
        /** The swap stopped between its two renames: the old mod was put back. */
        OLD_RESTORED,
        /** The swap stopped after the new mod was in place: the old one was deleted. */
        OLD_REMOVED
    }

    /** Called between the steps of {@link #commit} (a test stops the swap there, as a crash would). */
    interface Hook {
        void step(String name) throws IOException;
    }

    private final File installed, staging, old;
    private final Hook hook;

    public ModSwap(File installed, File staging, File old) {
        this(installed, staging, old, name -> { });
    }

    ModSwap(File installed, File staging, File old, Hook hook) {
        if (installed.equals(staging) || installed.equals(old) || staging.equals(old)) {
            throw new IllegalArgumentException("the mod, staging and old folders are three folders");
        }
        File parent = installed.getParentFile();
        if (parent == null || !parent.equals(staging.getParentFile()) || !parent.equals(old.getParentFile())) {
            throw new IllegalArgumentException("the three folders must be in one folder: a rename is the swap");
        }
        this.installed = installed;
        this.staging = staging;
        this.old = old;
        this.hook = hook;
    }

    public File installedDir() {
        return installed;
    }

    public File stagingDir() {
        return staging;
    }

    /** A staging folder is there (what {@code UpdatePlan.jobLeft} takes as {@code stagingFolder}). */
    public boolean stagingLeft() {
        return staging.exists();
    }

    /** Puts the folders right after a swap that was cut short; see the class comment. */
    public Recovery recover() throws IOException {
        boolean hasInstalled = installed.exists(), hasOld = old.exists();
        if (!hasInstalled && hasOld) {
            move(old, installed);
            return Recovery.OLD_RESTORED;
        }
        if (hasInstalled && hasOld) {
            deleteTree(old);
            return Recovery.OLD_REMOVED;
        }
        return Recovery.NOTHING;
    }

    /** Recovers, deletes any staging folder and makes a new empty one: where the new mod is to be made. */
    public File begin() throws IOException {
        recover();
        deleteTree(staging);
        File parent = staging.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot make " + parent);
        if (!staging.mkdir()) throw new IOException("cannot make " + staging);
        return staging;
    }

    /** Deletes the staging folder (a run that failed or was cancelled). */
    public void discardStaging() {
        deleteTree(staging);
    }

    /** Puts the staging folder in place of the installed mod. On an exception the installed mod is as it was. */
    public void commit() throws IOException {
        if (!staging.isDirectory()) throw new IOException("no staging folder to put in place");
        recover();
        hook.step("start");
        boolean hadInstalled = installed.exists();
        if (hadInstalled) move(installed, old);
        hook.step("old aside");
        try {
            move(staging, installed);
        } catch (IOException e) {
            if (hadInstalled) {
                try {
                    move(old, installed);
                } catch (IOException again) {
                    e.addSuppressed(again);     // (the next recover() puts it back)
                }
            }
            throw e;
        }
        hook.step("new in place");
        deleteTree(old);
        hook.step("done");
    }

    private static void move(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    /** A folder and everything in it (a symbolic link is removed, not followed). */
    static void deleteTree(File f) {
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) deleteTree(kid);
            }
        }
        f.delete();
    }
}
