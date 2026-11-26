package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LeaveGateTest {
    /** Catches an install while the player is in the app: it must never be told "left" with the app on the screen. */
    @Test
    public void neverLeftWhileTheAppIsOnTheScreen() {
        LeaveGate gate = new LeaveGate(3000);
        for (long t = 0; t < 60_000; t += 500) assertFalse("at " + t, gate.left(true, false, t));
    }

    /** Catches an install the moment the player steps out: the app must stay off the screen for the quiet time. */
    @Test
    public void leftOnlyAfterTheQuietTime() {
        LeaveGate gate = new LeaveGate(3000);
        assertFalse(gate.left(true, false, 0));
        assertFalse(gate.left(false, false, 1000));
        assertFalse(gate.left(false, false, 3999));
        assertTrue(gate.left(false, false, 4000));
        assertTrue("and it stays so", gate.left(false, false, 9000));
    }

    /** Catches a quick look at another app counting as leaving: coming back starts the quiet time again. */
    @Test
    public void comingBackStartsTheQuietTimeAgain() {
        LeaveGate gate = new LeaveGate(3000);
        assertFalse(gate.left(false, false, 0));
        assertFalse(gate.left(false, false, 2500));
        assertFalse("back in the app", gate.left(true, false, 2900));
        assertFalse(gate.left(false, false, 3000));
        assertFalse(gate.left(false, false, 5999));
        assertTrue(gate.left(false, false, 6000));
    }

    /** No quiet time: left as soon as the app is off the screen. */
    @Test
    public void noQuietTime() {
        LeaveGate gate = new LeaveGate(0);
        assertFalse(gate.left(true, false, 0));
        assertTrue(gate.left(false, false, 1));
    }

    /**
     * Catches an install with a game open: the player starts the game and goes to the phone's home screen for a
     * moment, the app is off the screen for as long as they like, and the update must still not be committed (the
     * hunt in progress would be lost when Android stops the app).
     */
    @Test
    public void neverLeftWhileAGameIsOpen() {
        LeaveGate gate = new LeaveGate(3000);
        assertFalse(gate.left(true, false, 0));
        for (long t = 500; t < 120_000; t += 500) assertFalse("game open, app away, at " + t, gate.left(false, true, t));
    }

    /** The quiet time starts when the game has ended with the app off the screen, not when the player left the app. */
    @Test
    public void quietTimeStartsWhenTheGameEnds() {
        LeaveGate gate = new LeaveGate(3000);
        assertFalse(gate.left(false, true, 0));
        assertFalse(gate.left(false, true, 50_000));
        assertFalse("the game ends now", gate.left(false, false, 60_000));
        assertFalse(gate.left(false, false, 62_999));
        assertTrue(gate.left(false, false, 63_000));
    }

    /** A game opened during the quiet time starts it again. */
    @Test
    public void aGameOpenedAgainStartsTheQuietTimeAgain() {
        LeaveGate gate = new LeaveGate(3000);
        assertFalse(gate.left(false, false, 0));
        assertFalse(gate.left(false, false, 2500));
        assertFalse("the player opens the game", gate.left(false, true, 2900));
        assertFalse(gate.left(false, false, 3000));
        assertFalse(gate.left(false, false, 5999));
        assertTrue(gate.left(false, false, 6000));
    }
}
