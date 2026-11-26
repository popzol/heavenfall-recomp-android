package io.github.popzol.heavenfall.app;

import android.app.Application;

/** The application class: it only puts the native parts in place ({@link Wiring}); every process of the app runs it. */
public final class HeavenfallApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Wiring.install(this);
    }
}
