package io.github.popzol.heavenfall;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** {@code installed.json} on disk, written whole or not at all ({@link Records}), and the SHA-256 of a file. */
public final class InstalledStore {
    private InstalledStore() {
    }

    /** The record in {@code file}, or null when there is none or it is not one the app understands. */
    public static Installed read(File file) {
        return Installed.tryParse(Records.read(file));
    }

    /**
     * The record after a mod was made: a first install ({@code previous} null) or the next one, which keeps the code
     * library's record (a library built from the same code stays good).
     */
    public static Installed after(Installed previous, String edition, String modVersion, String setName, String setSha256,
                                  boolean withXx, String codeSha256, String modCodeSha256) {
        if (previous == null || !previous.edition.equals(edition)) {
            return Installed.fresh(edition, modVersion, setName, setSha256, withXx, codeSha256, modCodeSha256);
        }
        return previous.applied(modVersion, setName, setSha256, withXx, codeSha256, modCodeSha256);
    }

    /** Writes the record whole or not at all. */
    public static void write(File file, Installed record) throws IOException {
        Records.write(file, record.toJson());
    }

    /** The SHA-256 of a file as lower-case hex. */
    public static String sha256(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) digest.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 0xFF));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
