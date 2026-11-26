package io.github.popzol.heavenfall;

import org.json.JSONObject;

/**
 * The note left when the app is about to update itself ({@code resume.json}): the APK's install ends the running
 * app, so the new one reads this to go on with the update where it stopped, under the same bar.
 */
public final class Resume {
    public static final String FILE = "resume.json";
    public static final int FORMAT = 1;

    /** What a freshly started app does with a note. */
    public enum Verdict {
        /** The app is the one the note waited for (or newer): go on with the update. */
        GO,
        /** The app is still older than the note's target (the install did not happen): forget the note. */
        DROP
    }

    public final String edition;
    public final String appTarget;     // the app version the note waits for
    public final String modTarget;     // the mod version the update is going to (null when only the app changes)
    public final boolean withXx;       // the update makes the set with MH XX
    public final int basePermille;     // where the single bar stood when the app was replaced (0..999)

    public Resume(String edition, String appTarget, String modTarget, boolean withXx, int basePermille) {
        if (edition == null || edition.isEmpty()) throw new IllegalArgumentException("resume.json: no edition");
        if (!Version.isValid(appTarget)) throw new IllegalArgumentException("resume.json: the app target is not a version");
        if (modTarget != null && !Version.isValid(modTarget)) {
            throw new IllegalArgumentException("resume.json: the mod target is not a version");
        }
        if (basePermille < 0 || basePermille > 999) throw new IllegalArgumentException("resume.json: base out of range");
        this.edition = edition;
        this.appTarget = appTarget;
        this.modTarget = modTarget;
        this.withXx = withXx;
        this.basePermille = basePermille;
    }

    /** What to do now that {@code runningApp} (a version) is the app that started. */
    public Verdict verdict(String runningApp) {
        return Version.newer(appTarget, runningApp) == 0 ? Verdict.GO : Verdict.DROP;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        Json.put(o, "format", FORMAT);
        Json.put(o, "edition", edition);
        Json.put(o, "app_target", appTarget);
        if (modTarget != null) Json.put(o, "mod_target", modTarget);
        Json.put(o, "xx", withXx);
        Json.put(o, "base_permille", basePermille);
        return o.toString();
    }

    /** The note in {@code json}, or null when it is missing or not a note this app understands. */
    public static Resume tryParse(String json) {
        JSONObject o = Json.object(json);
        if (o == null) return null;
        Long format = Json.integer(o, "format");
        Long base = Json.integer(o, "base_permille");
        Boolean xx = Json.bool(o, "xx");
        if (format == null || format != FORMAT || base == null || xx == null || base < 0 || base > 999) return null;
        try {
            return new Resume(Json.string(o, "edition"), Json.string(o, "app_target"), Json.string(o, "mod_target"),
                    xx, base.intValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
