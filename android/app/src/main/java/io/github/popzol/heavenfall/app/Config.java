package io.github.popzol.heavenfall.app;

import android.content.Context;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.github.popzol.heavenfall.BuildNeeds;
import io.github.popzol.heavenfall.Editions;
import io.github.popzol.heavenfall.Tuning;

/**
 * The app's own data files (the editions table and the tuning numbers), read once from its assets, and the build's
 * own numbers ({@link BuildNeeds}) from the build tools the APK carries.
 */
public final class Config {
    private static final String TAG = "Heavenfall";
    /** The build tools of the APK (mh4n's tools/ and config/), as the builder unpacks them. */
    private static final String TOOLS_ASSET = "builder/tools.zip";
    private static volatile Editions editions;
    private static volatile Tuning tuning;
    private static volatile BuildNeeds buildNeeds;

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

    /** The build's numbers from its own file in the APK's tools; {@link BuildNeeds#NONE} when it cannot be read. */
    public static BuildNeeds buildNeeds(Context c) {
        BuildNeeds n = buildNeeds;
        if (n != null) return n;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(c.getAssets().open(TOOLS_ASSET)))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.getName().equals(BuildNeeds.FILE)) continue;
                n = BuildNeeds.parse(new String(readAll(zip), StandardCharsets.UTF_8));
                break;
            }
            if (n == null) Log.w(TAG, "the build tools have no " + BuildNeeds.FILE + ": the build checks its own needs");
        } catch (IOException | BuildNeeds.InvalidException e) {
            Log.w(TAG, "the build's numbers cannot be read: the build checks its own needs", e);
        }
        buildNeeds = n = n == null ? BuildNeeds.NONE : n;
        return n;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
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
