package io.github.popzol.heavenfall.app;

import io.github.popzol.heavenfall.ModBuilder;

/**
 * libheavenfall.so (heavenfall/jni): the patch set's applier. Every file is an open file descriptor the library reads
 * with pread and never closes; the mod is written under a folder path. The answers are the "key=value" text that
 * {@code Identity}, {@code SetInfo} and {@code ApplyResult} parse.
 */
public final class HeavenfallNative implements ModBuilder.Backend {
    static {
        System.loadLibrary("heavenfall");
    }

    private static native String nativeIdentify(int fd);

    private static native String nativeSetInfo(int fd);

    private static native String nativeApply(int setFd, int gameFd, int updateFd, int xxFd, int sharedFd, byte[] outDir,
                                             boolean verifySources, ModBuilder.ProgressSink sink);

    @Override
    public String identify(int fd) {
        return nativeIdentify(fd);
    }

    @Override
    public String setInfo(int fd) {
        return nativeSetInfo(fd);
    }

    @Override
    public String apply(int setFd, int gameFd, int updateFd, int xxFd, int sharedFd, byte[] outDir, boolean verifySources,
                        ModBuilder.ProgressSink sink) {
        return nativeApply(setFd, gameFd, updateFd, xxFd, sharedFd, outDir, verifySources, sink);
    }
}
