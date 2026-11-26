package io.github.popzol.heavenfall.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.popzol.heavenfall.Editions;
import io.github.popzol.heavenfall.Tuning;

/** The app's own data files (the editions table and the tuning numbers), read once from its assets. */
public final class Config {
    private static volatile Editions editions;
    private static volatile Tuning tuning;

    private Config() {
    }

    public static Editions editions(Context c) {
        Editions e = editions;
        if (e == null) editions = e = Editions.parse(asset(c, Editions.ASSET_PATH));
        return e;
    }

    public static Tuning tuning(Context c) {
        Tuning t = tuning;
        if (t == null) tuning = t = Tuning.parse(asset(c, Tuning.ASSET_PATH));
        return t;
    }

    private static String asset(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
