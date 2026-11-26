package com.mh4u.port;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/**
 * What the app says before the game starts. It says nothing of its own now: 4u-native's check of its own releases and
 * its warning about the APK's signing certificate are not part of this app (it is a modified version with its own
 * releases and its own key). The credits are not here: they are drawn by the native side when the app opens and
 * under Credits & Licenses (app_native/settings_panel.cpp), and the licences are in LicensesActivity.
 */
final class Notices {
    private Notices() {}

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Settings -> Check for updates: [Interface] Updates in mh4u-app.ini (off: off; anything else on). */
    static boolean updatesOn(Context c) {
        File ini = new File(AppHome.home(c), "mh4u-app.ini");
        String section = "";
        try (BufferedReader r = new BufferedReader(new FileReader(ini))) {
            for (String line; (line = r.readLine()) != null; ) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) { section = t.substring(1, t.length() - 1).trim(); continue; }
                int eq = t.indexOf('=');
                if (eq <= 0 || !section.equalsIgnoreCase("Interface")) continue;
                if (!t.substring(0, eq).trim().equalsIgnoreCase("Updates")) continue;
                String v = t.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                return !(v.equals("off") || v.equals("never") || v.equals("0") || v.equals("false") || v.equals("no"));
            }
        } catch (IOException ignored) {
        }
        return true;
    }

    /** Before the game starts: nothing is due, so `then` runs at once. */
    static void beforeGame(Activity a, Runnable then) {
        then.run();
    }
}
