package io.github.popzol.heavenfall;

/**
 * A release version as the launcher compares it: an optional lower-case {@code v}, then numbers separated by dots
 * (at most 8 numbers of at most 9 digits each). Trailing zero numbers do not count ({@code 1.2} equals
 * {@code v1.2.0}). Anything else is not a version.
 */
public final class Version implements Comparable<Version> {
    public static final int MAX_PARTS = 8;
    private static final int MAX_DIGITS = 9;

    private final int[] parts;   // without trailing zeros (at least one number)

    private Version(int[] parts) {
        this.parts = parts;
    }

    /** The version in {@code text}, or null when it is not one (null, empty, letters, a missing number...). */
    public static Version parse(String text) {
        if (text == null) return null;
        int end = text.length();
        int at = 0;
        while (at < end && isSpace(text.charAt(at))) at++;
        while (end > at && isSpace(text.charAt(end - 1))) end--;
        if (at < end && text.charAt(at) == 'v') at++;
        int[] found = new int[MAX_PARTS];
        int count = 0;
        while (true) {
            long value = 0;
            int digits = 0;
            while (at < end && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                value = value * 10 + (text.charAt(at++) - '0');
                if (++digits > MAX_DIGITS) return null;
            }
            if (digits == 0 || count >= MAX_PARTS) return null;
            found[count++] = (int) value;
            if (at < end && text.charAt(at) == '.') {
                at++;
                continue;
            }
            break;
        }
        if (at != end) return null;
        while (count > 1 && found[count - 1] == 0) count--;
        int[] parts = new int[count];
        System.arraycopy(found, 0, parts, 0, count);
        return new Version(parts);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    public static boolean isValid(String text) {
        return parse(text) != null;
    }

    /**
     * Whether {@code latest} is newer than {@code current}: 1 yes, 0 no (equal or older), -1 when either is not a
     * version.
     */
    public static int newer(String latest, String current) {
        Version a = parse(latest);
        Version b = parse(current);
        if (a == null || b == null) return -1;
        return a.compareTo(b) > 0 ? 1 : 0;
    }

    /**
     * Whether an update to {@code latest} is wanted: it is a version and it is newer than {@code current}, or
     * {@code current} is not a version (nothing known about what is installed: install the latest).
     */
    public static boolean updateTo(String latest, String current) {
        Version a = parse(latest);
        if (a == null) return false;
        Version b = parse(current);
        return b == null || a.compareTo(b) > 0;
    }

    @Override
    public int compareTo(Version other) {
        int common = Math.min(parts.length, other.parts.length);
        for (int i = 0; i < common; i++) {
            if (parts[i] != other.parts[i]) return parts[i] > other.parts[i] ? 1 : -1;
        }
        return Integer.compare(parts.length, other.parts.length);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version && compareTo((Version) o) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(parts);
    }

    /** The numbers joined by dots, without a {@code v} and without trailing zero numbers ({@code v1.2.0} gives 1.2). */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
