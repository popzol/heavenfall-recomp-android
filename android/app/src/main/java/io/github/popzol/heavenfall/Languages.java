package io.github.popzol.heavenfall;

/** The language of the launcher's own screens: the phone's, English or Spanish. */
public final class Languages {
    /** The saved choices: "" is the phone's language. */
    public static final String[] CODES = {"", "en", "es"};

    private Languages() {
    }

    /** A saved value as one of {@link #CODES} ("" for anything else). */
    public static String normalize(String saved) {
        if (saved == null) return "";
        for (String c : CODES) {
            if (c.equals(saved)) return c;
        }
        return "";
    }

    public static Text label(String code) {
        switch (normalize(code)) {
            case "en": return new Text("hf_lang_en");
            case "es": return new Text("hf_lang_es");
            default: return new Text("hf_lang_auto");
        }
    }
}
