package io.github.popzol.heavenfall;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** {@code installed.json} on disk, written whole or not at all, and the SHA-256 of a file. */
public final class InstalledStore {
    private InstalledStore() {
    }

    /** The record in {@code file}, or null when there is none or it is not one the app understands. */
    public static Installed read(File file) {
        try {
            return Installed.tryParse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The record after a mod was made: a first install ({@code previous} null) or the next one, which keeps the code
     * library's record (a library built from the same code stays good).
     */
    public static Installed after(Installed previous, String edition, String modVersion, String setName, String setSha256,
                                  boolean withXx, String codeSha256) {
        if (previous == null || !previous.edition.equals(edition)) {
            return Installed.fresh(edition, modVersion, setName, setSha256, withXx, codeSha256);
        }
        return previous.applied(modVersion, setName, setSha256, withXx, codeSha256);
    }

    /** Writes the record to a temporary file, syncs it and renames it over {@code file}. */
    public static void write(File file, Installed record) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot make " + parent);
        File tmp = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(record.toJson().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
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
