package io.github.popzol.heavenfall;

/** The cancel button of a running job: every long step looks at it between its small pieces of work. */
public final class Control {
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Stops the step with {@link UpdateFailure#CANCELLED} when the player cancelled. */
    public void check() throws UpdateException {
        if (cancelled) throw new UpdateException(UpdateFailure.CANCELLED, "");
    }
}
