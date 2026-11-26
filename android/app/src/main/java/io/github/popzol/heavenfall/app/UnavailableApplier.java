package io.github.popzol.heavenfall.app;

import java.io.File;

import io.github.popzol.heavenfall.Control;
import io.github.popzol.heavenfall.Fraction;
import io.github.popzol.heavenfall.JobPaths;
import io.github.popzol.heavenfall.ModApplier;
import io.github.popzol.heavenfall.UpdateException;
import io.github.popzol.heavenfall.UpdateFailure;

/** Stands in for the mod applier until the native one is plugged in ({@link UpdateService#setApplier}): it always fails, naming why. */
public final class UnavailableApplier implements ModApplier {
    @Override
    public Applied apply(JobPaths paths, File set, File shared, boolean withXx, Control control, Fraction progress)
            throws UpdateException {
        throw new UpdateException(UpdateFailure.APPLY_UNAVAILABLE, "no applier");
    }
}
