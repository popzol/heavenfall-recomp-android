package io.github.popzol.heavenfall;

/**
 * A step that stopped, with the reason and the advice the player reads and a detail for the log (a file name, an
 * HTTP status). Always resumable: see {@link UpdateFailure}.
 */
public final class UpdateException extends Exception {
    private static final long serialVersionUID = 1L;

    public final transient Text reason;
    public final transient Text advice;
    public final UpdateFailure failure;   // null for a release or plan refusal
    public final String detail;

    public UpdateException(UpdateFailure failure, String detail) {
        this(failure, detail, null);
    }

    public UpdateException(UpdateFailure failure, String detail, Throwable cause) {
        super(failure + (detail == null || detail.isEmpty() ? "" : ": " + detail), cause);
        this.failure = failure;
        this.detail = detail == null ? "" : detail;
        this.reason = failure == UpdateFailure.SERVER ? failure.reason(this.detail) : failure.reason();
        this.advice = failure.advice();
    }

    public UpdateException(ReleaseRules.Refusal refusal, String detail) {
        super(refusal + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.failure = null;
        this.detail = detail == null ? "" : detail;
        this.reason = new Text(refusal.reasonKey());
        this.advice = new Text(refusal.adviceKey());
    }

    public UpdateException(UpdatePlan.Refusal refusal) {
        super(String.valueOf(refusal));
        this.failure = null;
        this.detail = "";
        this.reason = new Text(refusal.reasonKey());
        this.advice = new Text(refusal.adviceKey());
    }

    public boolean cancelled() {
        return failure == UpdateFailure.CANCELLED;
    }
}
