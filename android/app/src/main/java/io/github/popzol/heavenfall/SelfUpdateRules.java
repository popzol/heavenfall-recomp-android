package io.github.popzol.heavenfall;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * What a downloaded APK must be before it replaces the running app, and what each install result means. The phone
 * reads the facts (package name, version code, the SHA-256 of each signing certificate); the decision is here.
 */
public final class SelfUpdateRules {
    private SelfUpdateRules() {
    }

    /** {@code android.content.pm.PackageInstaller}'s status numbers (the constants' values, fixed by the platform). */
    public static final int STATUS_PENDING_USER_ACTION = -1;
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_FAILURE = 1;
    public static final int STATUS_FAILURE_BLOCKED = 2;
    public static final int STATUS_FAILURE_ABORTED = 3;
    public static final int STATUS_FAILURE_INVALID = 4;
    public static final int STATUS_FAILURE_CONFLICT = 5;
    public static final int STATUS_FAILURE_STORAGE = 6;
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;
    public static final int STATUS_FAILURE_TIMEOUT = 8;

    /**
     * Null when the APK may replace the app: the same package name, a higher version code, and signed by exactly the
     * same certificates as the installed app (a null or empty set of signers never matches).
     */
    public static UpdateFailure check(String installedPackage, long installedVersionCode, Set<String> installedSigners,
                                      String apkPackage, long apkVersionCode, Set<String> apkSigners) {
        if (apkPackage == null) return UpdateFailure.SELF_UNREADABLE;
        if (!apkPackage.equals(installedPackage)) return UpdateFailure.SELF_OTHER_APP;
        if (apkVersionCode <= installedVersionCode) return UpdateFailure.SELF_NOT_NEWER;
        if (apkSigners == null || apkSigners.isEmpty() || installedSigners == null || !apkSigners.equals(installedSigners)) {
            return UpdateFailure.SELF_OTHER_SIGNER;
        }
        return null;
    }

    /** The SHA-256 of a DER certificate, 64 lower-case hex digits. */
    public static String certificateSha256(byte[] certificate) {
        try {
            return Downloader.hex(MessageDigest.getInstance("SHA-256").digest(certificate));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The failure for an install result that is not a success and not a request for the player's confirmation. */
    public static UpdateFailure forStatus(int status) {
        switch (status) {
            case STATUS_FAILURE_BLOCKED:
                return UpdateFailure.SELF_BLOCKED;
            case STATUS_FAILURE_ABORTED:
                return UpdateFailure.SELF_ABORTED;
            case STATUS_FAILURE_INVALID:
                return UpdateFailure.SELF_INVALID;
            case STATUS_FAILURE_CONFLICT:
                return UpdateFailure.SELF_CONFLICT;
            case STATUS_FAILURE_STORAGE:
                return UpdateFailure.SELF_STORAGE;
            case STATUS_FAILURE_INCOMPATIBLE:
                return UpdateFailure.SELF_INCOMPATIBLE;
            case STATUS_FAILURE_TIMEOUT:
                return UpdateFailure.SELF_NO_ANSWER;
            default:
                return UpdateFailure.SELF_FAILED;
        }
    }
}
