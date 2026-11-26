package io.github.popzol.heavenfall;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The notification question of Android 13 and later. */
public class NotificationAskTest {
    @Test
    public void askedOnceOnAndroid13AndLaterWhenNotGivenYet() {
        // Catches a question put on a phone that has no such permission (Android 12 and before), one put again after
        // an answer, or one put to a player who allowed it already.
        assertTrue(NotificationAsk.shouldAsk(33, false, false));
        assertTrue(NotificationAsk.shouldAsk(35, false, false));
        assertFalse(NotificationAsk.shouldAsk(32, false, false));
        assertFalse(NotificationAsk.shouldAsk(28, false, false));
        assertFalse(NotificationAsk.shouldAsk(34, true, false));
        assertFalse(NotificationAsk.shouldAsk(34, false, true));
    }

    @Test
    public void theReasonIsThere() {
        // Catches the question without its sentence (the strings test also needs both languages).
        assertTrue(ScreenTexts.NOTIFY_TEXT.key.startsWith("hf_notify"));
        assertTrue(ScreenTexts.NOTIFY_TITLE.key.startsWith("hf_notify"));
    }
}
