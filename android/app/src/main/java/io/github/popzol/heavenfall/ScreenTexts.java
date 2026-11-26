package io.github.popzol.heavenfall;

/** The fixed texts the screens use outside the presenters' lists (titles, dialogs, the folder chooser). */
public final class ScreenTexts {
    private ScreenTexts() {
    }

    public static final Text MORE_TITLE = new Text("hf_more_title");
    public static final Text BACK = new Text("hf_btn_back");
    public static final Text CONTINUE = new Text("hf_btn_continue");
    public static final Text CANCEL = new Text("hf_btn_cancel");
    /** Shown under the bar while a job runs. */
    public static final Text JOB_HINT = new Text("hf_job_hint");
    /** The question before the app asks Android to allow installs. */
    public static final Text ALLOW_TITLE = new Text("hf_allow_installs_title");
    public static final Text ALLOW_TEXT = new Text("hf_allow_installs_text");
    public static final Text ALLOW = new Text("hf_btn_allow");
    public static final Text NOT_NOW = new Text("hf_btn_not_now");
    /** The question before the app asks Android to show notifications (Android 13 and later); its button asks. */
    public static final Text NOTIFY_TITLE = new Text("hf_notify_title");
    public static final Text NOTIFY_TEXT = new Text("hf_notify_text");
    public static final Text ALLOW_NOTIFICATIONS = new Text("hf_btn_allow_notifications");
    /** Before a job that may replace the app: the new version is installed only after the player leaves the app. */
    public static final Text REPLACE_TITLE = new Text("hf_replace_title");
    public static final Text REPLACE_TEXT = new Text("hf_replace_text");
    public static final Text REPLACE_GO = new Text("hf_btn_update");
    /** Resume's Discard: its question, its button and what a Discard that could not delete everything says. */
    public static final Text DISCARD_TITLE = new Text("hf_discard_title");
    public static final Text DISCARD_TEXT = new Text("hf_discard_text");
    public static final Text DISCARD = new Text("hf_btn_discard");
    public static final Text DISCARD_FAILED = new Text("hf_toast_discard_failed");
    /** The folder chooser: its title, the line for a folder that holds a game file, the line for the others. */
    public static final Text FOLDER_TITLE = new Text("hf_folder_title");
    public static final Text FOLDER_HERE = new Text("hf_folder_here");
    public static final Text FOLDER_LOOK = new Text("hf_folder_look");
    public static final Text FOLDER_FAILED = new Text("hf_toast_folder_failed");
    public static final Text LANGUAGE_TITLE = new Text("hf_lang_title");
    /** What the assistant says when every required step is right. */
    public static final Text ASSISTANT_DONE_TEXT = new Text("hf_assistant_done_text");

    /** The line with the app's version and the installed mod's ("-" when none). */
    public static Text versions(String appVersion, Installed installed) {
        return new Text("hf_home_versions", appVersion.isEmpty() ? "-" : appVersion,
                installed == null ? "-" : installed.modVersion);
    }
}
