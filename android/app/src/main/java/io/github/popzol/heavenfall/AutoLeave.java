package io.github.popzol.heavenfall;

/**
 * The app's own update takes the player out of the app by itself: once everything else is done and only the APK's
 * install is left, the home screen shows that the app closes to install its new version and, after the tuning's
 * {@code app_leave_show_s}, goes to the phone's home screen. Android replaces the app only once it is off the screen
 * ({@link LeaveGate}), and asking the player to press the Home button was not clear. Never with the game open
 * ({@link GameOpen}): a hunt is never left that way.
 */
public final class AutoLeave {
    private AutoLeave() {
    }

    /** The states of a job that waits for the player to leave the app before the install. */
    public static boolean waitsToLeave(JobProgress p) {
        if (p == null || p.phase != JobProgress.Phase.RUNNING || p.state == null) return false;
        return "hf_state_job_leave_app_last".equals(p.state.key) || "hf_state_job_leave_app_then".equals(p.state.key);
    }

    /** Whether the home screen, on the screen now, takes the player out for the update's install. */
    public static boolean now(JobProgress p, boolean homeOnScreen, boolean gameOpen) {
        return homeOnScreen && !gameOpen && waitsToLeave(p);
    }
}
