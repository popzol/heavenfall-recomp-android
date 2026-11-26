package io.github.popzol.heavenfall.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The app was replaced (by its own update): the update goes on from the resume note, without the player doing
 * anything when Android lets the service start, else through a notification the player taps.
 */
public final class PackageReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            UpdateService.afterReplace(context);
        }
    }
}
