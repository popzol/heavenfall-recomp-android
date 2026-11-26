package io.github.popzol.heavenfall.app;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;

import io.github.popzol.heavenfall.BuilderApplier;
import io.github.popzol.heavenfall.ModBuilder;
import io.github.popzol.heavenfall.Records;

/**
 * Where the parts that need the native library are put in place, once per process (the application class calls it, so
 * a job started by the update receiver or the service has them as well as the screens): the native reader of the
 * three dumps for the screens' folder scan, and the native applier for the update job. The library
 * ({@code libheavenfall.so}) is loaded when one of them first reads a file, not at the start of every process (the
 * game's own process runs this class too).
 */
final class Wiring {
    private static boolean done;

    private Wiring() {
    }

    /** The native layer, loaded the first time it is asked a question. */
    private static final class Lazy implements ModBuilder.Backend {
        private static final class Holder {
            static final HeavenfallNative NATIVE = new HeavenfallNative();
        }

        @Override
        public String identify(int fd) {
            return Holder.NATIVE.identify(fd);
        }

        @Override
        public String setInfo(int fd) {
            return Holder.NATIVE.setInfo(fd);
        }

        @Override
        public String apply(int setFd, int gameFd, int updateFd, int xxFd, int sharedFd, byte[] outDir,
                            boolean verifySources, ModBuilder.ProgressSink sink) {
            return Holder.NATIVE.apply(setFd, gameFd, updateFd, xxFd, sharedFd, outDir, verifySources, sink);
        }
    }

    static synchronized void install(Context context) {
        if (done) return;
        done = true;
        // a record's folder synced after its rename (temp, fsync, rename): Android's own calls, a folder opened read-only
        Records.setFolderSync(dir -> {
            try {
                FileDescriptor fd = Os.open(dir.getPath(), OsConstants.O_RDONLY, 0);
                try {
                    Os.fsync(fd);
                } finally {
                    Os.close(fd);
                }
            } catch (ErrnoException e) {
                throw new IOException(e.getMessage(), e);
            }
        });
        ModBuilder.Backend backend = new Lazy();
        PhoneReader.setIdentifier(new NativeDumpIdentifier(backend));
        UpdateService.setApplier(new BuilderApplier(backend, SourceFile::of));
    }
}
