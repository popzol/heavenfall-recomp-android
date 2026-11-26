package io.github.popzol.heavenfall;

import java.io.File;

/**
 * Replaces the running app with a downloaded APK (the Android side checks it is this app, newer and signed by the
 * same certificates, then installs it). It returns only when the install ended without the running process being
 * replaced; a refused or failed install is an {@link UpdateException}.
 */
public interface AppInstaller {
    /** Called when the system asks the player to confirm the install (the job says so on its bar). */
    interface Waiting {
        void needsConfirmation();
    }

    void install(File apk, Control control, Waiting waiting) throws UpdateException;
}
