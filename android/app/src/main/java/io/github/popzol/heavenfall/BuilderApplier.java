package io.github.popzol.heavenfall;

import java.io.File;
import java.util.function.Function;

/**
 * The update job's {@link ModApplier} over the mod's maker ({@link ModBuilder}): the set, the player's game and update
 * (and MH XX) and the release's shared file are opened as the layer needs them, the mod is made in the job's staging
 * folder and put in place of the installed one, and a failure becomes the job's {@link UpdateException} with the
 * words of {@link ApplyError}. Without Android: the layer and the way a file is opened come in from outside.
 */
public final class BuilderApplier implements ModApplier {
    private final ModBuilder.Backend backend;
    private final Function<File, ModBuilder.Input> inputs;

    /** @param inputs how a file is opened for the layer (a plain path on the phone) */
    public BuilderApplier(ModBuilder.Backend backend, Function<File, ModBuilder.Input> inputs) {
        this.backend = backend;
        this.inputs = inputs;
    }

    @Override
    public Applied apply(JobPaths paths, File set, File shared, boolean withXx, Control control, Fraction progress)
            throws UpdateException {
        ModSwap swap = new ModSwap(paths.modDir, paths.staging, new File(paths.modDir.getParentFile(), LauncherMod.OLD));
        ModBuilder.Request request = new ModBuilder.Request(inputs.apply(set), inputs.apply(paths.gameDump),
                inputs.apply(paths.updateDump), withXx && paths.xxDump != null ? inputs.apply(paths.xxDump) : null,
                shared == null ? null : inputs.apply(shared), swap, true);
        try {
            ModBuilder.Made made = ModBuilder.apply(backend, request,
                    (done, total) -> progress.fraction(total <= 0 ? 0 : Math.min(1.0, done / (double) total)),
                    control::isCancelled);
            return new Applied(made.codeSha256);
        } catch (ModBuilder.Failed failed) {
            throw exception(failed.error, failed);
        }
    }

    /** What the job says of a mod that could not be made: a cancel and a set of a newer app have their own endings. */
    static UpdateException exception(ApplyError error, Throwable cause) {
        switch (error.failure) {
            case CANCELLED:
                return new UpdateException(UpdateFailure.CANCELLED, "apply");
            case NEEDS_NEWER_APP:
                return new UpdateException(UpdatePlan.Refusal.NEEDS_NEWER_APP);
            default:
                return new UpdateException(UpdateFailure.APPLY_FAILED, error.reason(), error.advice(), error.logLine(),
                        cause);
        }
    }
}
