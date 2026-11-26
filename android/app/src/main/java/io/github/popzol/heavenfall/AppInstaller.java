package io.github.popzol.heavenfall;

import java.io.File;

/**
 * Replaces the running app with a downloaded APK (the Android side checks it is this app, newer and signed by the
 * same certificates, then installs it). The install ends the running app, so it waits until the player has left the
 * app ({@link LeaveGate}) and only then hands the APK to the system: the player is never taken out of the app by its
 * own update. It returns only when the install ended without the running process being replaced; a refused or failed
 * install is an {@link UpdateException}.
 */
public interface AppInstaller {
    /** What the install tells the job while it waits (the job says it on its bar). */
    interface Waiting {
        /** The system asks the player to confirm the install. */
        void needsConfirmation();

        /** The APK is ready and waits for the player to leave the app (told once, at the start of the wait). */
        default void waitingToLeave() {
        }

        /** The player has left the app: the APK is handed to the system now. */
        default void installing() {
        }
    }

    /** The code library's id the app inside {@code apk} makes ({@link AppLibraryId}); "" when not known. */
    default String libraryId(File apk) {
        return AppLibraryId.read(apk);
    }

    void install(File apk, Control control, Waiting waiting) throws UpdateException;
}
