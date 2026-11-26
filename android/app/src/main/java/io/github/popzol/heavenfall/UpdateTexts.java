package io.github.popzol.heavenfall;

/** The names of the texts the update job's notifications use (the strings are in both languages). */
public final class UpdateTexts {
    private UpdateTexts() {
    }

    /** The notification channel's name, and the notification's cancel button. */
    public static final String CHANNEL = "hf_note_channel";
    public static final String CANCEL = "hf_note_cancel";
    /** The channel of how a job ended (seen in the status bar: the app may be closed when it ends). */
    public static final String END_CHANNEL = "hf_note_end_channel";
    /** The running job's title (the state line is the text). */
    public static final String RUNNING_TITLE = "hf_note_running_title";
    /** The job ended: done (a tap opens Home to play), failed (the reason and the advice are the text), cancelled. */
    public static final String DONE_TITLE = "hf_note_done_title";
    public static final String DONE_TEXT = "hf_note_done_text";
    public static final String FAILED_TITLE = "hf_note_failed_title";
    public static final String CANCELLED_TITLE = "hf_note_cancelled_title";
    public static final String CANCELLED_TEXT = "hf_note_cancelled_text";
    /** The player has to confirm the app's install on the screen. */
    public static final String CONFIRM_TITLE = "hf_note_confirm_title";
    public static final String CONFIRM_TEXT = "hf_note_confirm_text";
    /** The app was replaced and the update waits for a tap (the service could not start by itself). */
    public static final String RESUME_TITLE = "hf_note_resume_title";
    public static final String RESUME_TEXT = "hf_note_resume_text";

    /**
     * How a job that ended is told in a notification: its title and text (string names, the failure's own texts
     * resolved by the caller), or null for an end that has none (the app is replacing itself: the new app goes on).
     */
    public static String[] ended(JobProgress p) {
        switch (p.phase) {
            case DONE:
                return new String[] {DONE_TITLE, DONE_TEXT};
            case FAILED:
                return new String[] {FAILED_TITLE, null};
            case CANCELLED:
                return new String[] {CANCELLED_TITLE, CANCELLED_TEXT};
            default:
                return null;
        }
    }
}
