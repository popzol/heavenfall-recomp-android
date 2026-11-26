package io.github.popzol.heavenfall;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The mod made from the player's files: the part of the job that does not need Android. The native layer ({@link
 * Backend}: libheavenfall.so, see the app package's HeavenfallNative) reads the files and writes the mod's folder; this
 * opens the files, makes the staging folder, turns every failure into an {@link ApplyError}, checks what was written
 * and only then puts the folder in place ({@link ModSwap}). A failure or a cancel leaves the installed mod as it was.
 */
public final class ModBuilder {
    private ModBuilder() {
    }

    /** The native layer's three calls; each file is an open file descriptor the layer reads with pread and never closes. */
    public interface Backend {
        String identify(int fd);

        String setInfo(int fd);

        /** -1 for a file not given. {@code sink} is told the bytes done of the total and answers true to cancel. */
        String apply(int setFd, int gameFd, int updateFd, int xxFd, int sharedFd, byte[] outDir, boolean verifySources,
                     ProgressSink sink);
    }

    /** What the native layer calls as the bytes go by (its method is looked up by this name). */
    public interface ProgressSink {
        boolean update(long done, long total);
    }

    /** What a caller wants told: bytes done of the total (the total is an estimate that never falls short of done). */
    public interface Progress {
        void update(long done, long total);
    }

    /** An open file of the player's or the release's; closing it closes the descriptor. */
    public interface Handle extends Closeable {
        int fd();
    }

    /** A file of the player's or the release's, opened when it is needed (a path, or the document picker's answer). */
    public interface Input {
        Handle open() throws IOException;
    }

    /** What the player gave and where the mod goes. {@code xx} and {@code shared} are null when there are none. */
    public static final class Request {
        public final Input set, game, update, xx, shared;
        public final ModSwap swap;
        public final boolean verifySources;

        public Request(Input set, Input game, Input update, Input xx, Input shared, ModSwap swap, boolean verifySources) {
            if (set == null || game == null || update == null || swap == null) throw new IllegalArgumentException("a request needs the set, the game, its update and the swap");
            this.set = set;
            this.game = game;
            this.update = update;
            this.xx = xx;
            this.shared = shared;
            this.swap = swap;
            this.verifySources = verifySources;
        }
    }

    /** The mod that was put in place. */
    public static final class Made {
        /** SHA-256 of the mod's executable ({@code exefs/code.bin}), as the layer hashed it. */
        public final String codeSha256;
        /** The digest of everything in the mod that becomes code ({@link ModCode}), taken before it was put in place. */
        public final String modCodeSha256;
        public final int files;
        public final long bytes;

        Made(String codeSha256, String modCodeSha256, int files, long bytes) {
            this.codeSha256 = codeSha256;
            this.modCodeSha256 = modCodeSha256;
            this.files = files;
            this.bytes = bytes;
        }
    }

    /** Making the mod stopped; the installed mod is as it was. */
    public static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        public final transient ApplyError error;

        public Failed(ApplyError error, Throwable cause) {
            super(error.logLine(), cause);
            this.error = error;
        }
    }

    /** What a file is. A file that cannot be opened is an identity with no kind (code {@link ApplyError#IO}). */
    public static Identity identify(Backend backend, Input input) {
        try (Handle h = input.open()) {
            return Identity.parse(backend.identify(h.fd()));
        } catch (IOException e) {
            return new Identity(ApplyError.IO, Identity.Kind.NONE, "unknown", false, "0000000000000000", "");
        }
    }

    /** A set's head; for a set that cannot be opened, the head of "not a set" (code {@link ApplyError#IO}). */
    public static SetInfo setInfo(Backend backend, Input input) {
        try (Handle h = input.open()) {
            return SetInfo.parse(backend.setInfo(h.fd()));
        } catch (IOException e) {
            return SetInfo.parse("rc=" + ApplyError.IO + "\nformat=0\nneeds_newer_app=0\nregion=unknown\nedition=\nversion=\n"
                    + "xx=0\nxx_title=0000000000000000\nxx_romfs_sha256=\nshared=0\nshared_size=0\nshared_sha256=\n"
                    + "update=0\nupdate_title=0000000000000000\nupdate_exefs_sha256=\ncheats=0\nfiles=0\n");
        }
    }

    /**
     * Makes the mod into the staging folder, checks it and puts it in place of the installed one. Blocking: run it off
     * the main thread. {@code progress} (may be null) is called from the calling thread; {@code cancelled} (may be null)
     * is looked at at every call of it.
     */
    public static Made apply(Backend backend, Request r, Progress progress, BooleanSupplier cancelled) throws Failed {
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        List<Handle> open = new ArrayList<>();
        boolean staged = false;
        try {
            int set = open(r.set, ApplyError.STAGE_SET, open);
            int game = open(r.game, ApplyError.STAGE_GAME, open);
            int update = open(r.update, ApplyError.STAGE_UPDATE, open);
            int xx = r.xx == null ? -1 : open(r.xx, ApplyError.STAGE_XX, open);
            int shared = r.shared == null ? -1 : open(r.shared, ApplyError.STAGE_SHARED, open);
            File staging;
            try {
                staging = r.swap.begin();
            } catch (IOException e) {
                throw new Failed(ApplyError.of(ApplyError.Failure.WRITE_FAILED), e);
            }
            staged = true;
            ApplyResult result;
            try {
                result = ApplyResult.parse(backend.apply(set, game, update, xx, shared,
                        staging.getPath().getBytes(StandardCharsets.UTF_8), r.verifySources, (done, total) -> {
                            if (progress != null) progress.update(done, total);
                            return stop.getAsBoolean();
                        }));
            } catch (RuntimeException e) {      // a native answer that is no answer, or a callback that threw
                throw new Failed(ApplyError.of(ApplyError.Failure.INTERNAL), e);
            }
            if (!result.ok()) throw new Failed(result.error(), null);
            if (stop.getAsBoolean()) throw new Failed(ApplyError.of(ApplyError.CANCEL, ApplyError.STAGE_WRITE, 0), null);
            checkStaged(staging, result);
            String modCode;
            try {
                modCode = ModCode.digest(staging);
            } catch (IOException e) {
                throw new Failed(ApplyError.of(ApplyError.Failure.READ_FAILED), e);
            }
            if (modCode == null) throw new Failed(ApplyError.of(ApplyError.Failure.OUTPUT_DIFFERS), null);
            try {
                r.swap.commit();
            } catch (IOException e) {
                throw new Failed(ApplyError.of(ApplyError.Failure.SWAP_FAILED), e);
            }
            staged = false;
            return new Made(result.codeSha256, modCode, result.files, result.bytes);
        } finally {
            if (staged) r.swap.discardStaging();
            for (Handle h : open) {
                try {
                    h.close();
                } catch (IOException ignored) {
                    // (a descriptor that does not close: nothing to do with it)
                }
            }
        }
    }

    private static int open(Input input, int stage, List<Handle> open) throws Failed {
        try {
            Handle h = input.open();
            open.add(h);
            return h.fd();
        } catch (IOException e) {
            throw new Failed(ApplyError.unreadable(stage), e);
        }
    }

    /** What the layer wrote is on the disk: the executable and the exheader, the executable as the layer hashed it. */
    private static void checkStaged(File staging, ApplyResult result) throws Failed {
        File code = new File(staging, "exefs/code.bin"), exheader = new File(staging, "exheader.bin");
        if (!code.isFile() || !exheader.isFile() || exheader.length() == 0 || result.codeSha256.isEmpty()) {
            throw new Failed(ApplyError.of(ApplyError.Failure.OUTPUT_DIFFERS), null);
        }
        try {
            if (!InstalledStore.sha256(code).equals(result.codeSha256)) {
                throw new Failed(ApplyError.of(ApplyError.Failure.OUTPUT_DIFFERS), null);
            }
        } catch (IOException e) {
            throw new Failed(ApplyError.of(ApplyError.Failure.READ_FAILED), e);
        }
    }
}
