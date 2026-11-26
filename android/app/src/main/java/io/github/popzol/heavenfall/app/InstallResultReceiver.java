package io.github.popzol.heavenfall.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The PackageInstaller's results for the app's own update, passed to the job waiting for them. */
public final class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && SelfUpdate.ACTION.equals(intent.getAction())) SelfUpdate.deliver(intent);
    }
}
