package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LeaveGateTest {
    /** Catches an install while the player is in the app: it must never be told "left" with the app on the screen. */
    @Test
    public void neverLeftWhileTheAppIsOnTheScreen() {
        LeaveGate gate = new LeaveGate(3000);
        for (long t = 0; t < 60_000; t += 500) assertFalse("at " + t, gate.left(true, t));
    }

    /** Catches an install the moment the player steps out: the app must stay off the screen for the quiet time. */
    @Test
    public void leftOnlyAfterTheQuietTime() {
        LeaveGate gate = new LeaveGate(3000);
        assertFalse(gate.left(true, 0));
        assertFalse(gate.left(false, 1000));
        assertFalse(gate.left(false, 3999));
        assertTrue(gate.left(false, 4000));
        assertTrue("and it stays so", gate.left(false, 9000));
    }

    /** Catches a quick look at another app counting as leaving: coming back starts the quiet time again. */
    @Test
    public void comingBackStartsTheQuietTimeAgain() {
        LeaveGate gate = new LeaveGate(3000);
        assertFalse(gate.left(false, 0));
        assertFalse(gate.left(false, 2500));
        assertFalse("back in the app", gate.left(true, 2900));
        assertFalse(gate.left(false, 3000));
        assertFalse(gate.left(false, 5999));
        assertTrue(gate.left(false, 6000));
    }

    /** No quiet time: left as soon as the app is off the screen. */
    @Test
    public void noQuietTime() {
        LeaveGate gate = new LeaveGate(0);
        assertFalse(gate.left(true, 0));
        assertTrue(gate.left(false, 1));
    }
}
