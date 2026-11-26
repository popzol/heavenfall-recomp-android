package io.github.popzol.heavenfall;

/**
 * What the screens and the notification show of an update job: one snapshot, never changed afterwards. The bar is
 * {@link #permille} over the whole plan (the APK, the mod and the library under one bar); {@link #state} says what
 * is happening now; {@link #msLeft} is the plan's estimate; a failed or cancelled job says why and what to do.
 */
public final class JobProgress {
    public enum Phase {
        /** No job (nothing was started, or the last one was shown and forgotten). */
        IDLE,
        RUNNING,
        /** The new app was installed and this process is about to be replaced: the update goes on in the new app. */
        RESTARTING,
        DONE,
        FAILED,
        CANCELLED
    }

    public final Phase phase;
    /** 0..1000 over the whole plan. */
    public final int permille;
    public final Text state;
    /** Milliseconds left by the plan's estimate; 0 when unknown or finished. */
    public final long msLeft;
    /** The step running (or the one that stopped); null while checking or when none. */
    public final UpdatePlan.StepKind step;
    /** The number of the running step (1 based) and how many steps the plan has; 0 while there is no plan. */
    public final int stepNumber;
    public final int stepCount;
    /** FAILED (and CANCELLED): why, and what to do about it; otherwise null. */
    public final Text failureReason;
    public final Text failureAdvice;

    JobProgress(Phase phase, int permille, Text state, long msLeft, UpdatePlan.StepKind step, int stepNumber,
                int stepCount, Text failureReason, Text failureAdvice) {
        this.phase = phase;
        this.permille = Math.max(0, Math.min(1000, permille));
        this.state = state;
        this.msLeft = Math.max(0, msLeft);
        this.step = step;
        this.stepNumber = stepNumber;
        this.stepCount = stepCount;
        this.failureReason = failureReason;
        this.failureAdvice = failureAdvice;
    }

    public static final JobProgress IDLE = new JobProgress(Phase.IDLE, 0, new Text("hf_state_job_idle"), 0, null, 0, 0,
            null, null);

    /** The first snapshot of a job that was just asked for, before its plan is known. */
    public static JobProgress starting(int permille) {
        return new JobProgress(Phase.RUNNING, permille, new Text("hf_state_job_checking"), 0, null, 0, 0, null, null);
    }

    /** A job that could not even start: the bar at {@code permille} and the reason. */
    public static JobProgress failed(UpdateException e, int permille) {
        return new JobProgress(e.cancelled() ? Phase.CANCELLED : Phase.FAILED, permille,
                new Text(e.cancelled() ? "hf_state_job_cancelled" : "hf_state_job_failed"), 0, null, 0, 0, e.reason,
                e.advice);
    }

    /** The snapshot of a job the system stopped (a time limit, the process ending): resumable like any failure. */
    public JobProgress interrupted() {
        UpdateException e = new UpdateException(UpdateFailure.INTERRUPTED, "");
        return new JobProgress(Phase.FAILED, permille, new Text("hf_state_job_failed"), 0, step, stepNumber, stepCount,
                e.reason, e.advice);
    }

    /** Whether the job is still going (the home screen shows no button, the notification stays). */
    public boolean active() {
        return phase == Phase.RUNNING || phase == Phase.RESTARTING;
    }

    @Override
    public String toString() {
        return phase + " " + permille + " " + state + (failureReason == null ? "" : " / " + failureReason);
    }
}
