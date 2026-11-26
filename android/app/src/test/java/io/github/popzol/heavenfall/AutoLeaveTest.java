package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Catches the app's own update waiting for the player to press the Home button (not clear to a player): the home
 * screen takes the player out by itself once only the install is left, never with the game open, and at no other
 * moment of a job.
 */
public class AutoLeaveTest {
    private static JobProgress at(JobProgress.Phase phase, String key) {
        return new JobProgress(phase, 900, new Text(key), 0, UpdatePlan.StepKind.APP_INSTALL, 5, 5, null, null);
    }

    @Test
    public void theHomeScreenTakesThePlayerOutForTheInstall() {
        for (String key : new String[] {"hf_state_job_leave_app_last", "hf_state_job_leave_app_then"}) {
            JobProgress p = at(JobProgress.Phase.RUNNING, key);
            assertTrue(key, AutoLeave.now(p, true, false));
            assertFalse(key + ": never with the game open", AutoLeave.now(p, true, true));
            assertFalse(key + ": only from the home screen on the screen", AutoLeave.now(p, false, false));
        }
    }

    @Test
    public void noOtherMomentTakesThePlayerOut() {
        String[] others = {"hf_state_job_confirm_install", "hf_state_job_installing_app", "hf_state_job_restarting",
                "hf_state_job_checking"};
        for (String key : others) assertFalse(key, AutoLeave.now(at(JobProgress.Phase.RUNNING, key), true, false));
        assertFalse("a job that ended", AutoLeave.now(at(JobProgress.Phase.FAILED, "hf_state_job_leave_app_last"), true, false));
        assertFalse(AutoLeave.now(null, true, false));
        assertFalse(AutoLeave.now(JobProgress.IDLE, true, false));
    }
}
