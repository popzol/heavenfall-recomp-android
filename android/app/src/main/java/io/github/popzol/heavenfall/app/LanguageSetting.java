package io.github.popzol.heavenfall.app;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

import io.github.popzol.heavenfall.Languages;

/**
 * The language of the launcher's own screens: the phone's, or the one chosen in More. It is kept in the app's private
 * preferences and applied to each screen's context; the process's default locale follows, so the game folder's pages
 * (which read it) speak the same language.
 */
final class LanguageSetting {
    private static final String PREFS = "heavenfall_screens";
    private static final String KEY = "language";

    private LanguageSetting() {
    }

    static String get(Context c) {
        try {
            return Languages.normalize(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""));
        } catch (RuntimeException e) {
            return "";
        }
    }

    static void set(Context c, String code) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, Languages.normalize(code)).apply();
        } catch (RuntimeException e) {
            // the choice is not kept: the phone's language is used
        }
    }

    /** The choice after {@code code} in {@link Languages#CODES}. */
    static String next(String code) {
        String[] codes = Languages.CODES;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(Languages.normalize(code))) return codes[(i + 1) % codes.length];
        }
        return codes[0];
    }

    /** The context a screen draws with. */
    static Context wrap(Context base) {
        String code = get(base);
        if (code.isEmpty()) {
            Locale system = Resources.getSystem().getConfiguration().getLocales().get(0);
            if (!system.equals(Locale.getDefault())) Locale.setDefault(system);
            return base;
        }
        Locale locale = new Locale(code);
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        return base.createConfigurationContext(configuration);
    }
}
