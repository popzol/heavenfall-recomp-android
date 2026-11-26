package io.github.popzol.heavenfall;

/** The names of the texts the update job's notifications use (the strings are in both languages). */
public final class UpdateTexts {
    private UpdateTexts() {
    }

    /** The notification channel's name, and the notification's cancel button. */
    public static final String CHANNEL = "hf_note_channel";
    public static final String CANCEL = "hf_note_cancel";
    /** The running job's title (the state line is the text). */
    public static final String RUNNING_TITLE = "hf_note_running_title";
    /** The job ended: done, failed (the reason is the text), cancelled. */
    public static final String DONE_TITLE = "hf_note_done_title";
    public static final String FAILED_TITLE = "hf_note_failed_title";
    public static final String CANCELLED_TITLE = "hf_note_cancelled_title";
    /** The player has to confirm the app's install on the screen. */
    public static final String CONFIRM_TITLE = "hf_note_confirm_title";
    public static final String CONFIRM_TEXT = "hf_note_confirm_text";
    /** The app was replaced and the update waits for a tap (the service could not start by itself). */
    public static final String RESUME_TITLE = "hf_note_resume_title";
    public static final String RESUME_TEXT = "hf_note_resume_text";
}
