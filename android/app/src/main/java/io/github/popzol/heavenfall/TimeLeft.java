package io.github.popzol.heavenfall;

/** The bar's time left as a text: seconds are never shown, a minute is the smallest step. */
public final class TimeLeft {
    private TimeLeft() {
    }

    /** The text for {@code ms} milliseconds left; null when it is not known (zero or less). */
    public static Text of(long ms) {
        if (ms <= 0) return null;
        long minutes = (ms + 59_999) / 60_000;
        if (minutes <= 1) return new Text("hf_time_left_under_minute");
        if (minutes < 60) return new Text("hf_time_left_minutes", String.valueOf(minutes));
        return new Text("hf_time_left_hours", String.valueOf(minutes / 60), String.valueOf(minutes % 60));
    }
}
