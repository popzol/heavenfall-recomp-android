package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

public class EditionsTest {
    /** The asset the other tasks generate must read as the contract says. */
    @Test
    public void sampleFileHasTheContractsValues() {
        Editions t = Support.editions();
        assertEquals("popzol/heavenfall-recomp-android", t.app().repository);
        assertEquals("Heavenfall-android.apk", t.app().apk);
        assertEquals("Heavenfall-android-release.json", t.app().releaseFile);
        assertEquals("https://github.com/popzol/heavenfall-recomp-android/releases/latest/download/Heavenfall-android-release.json",
                t.app().releaseFileUrl());
        assertEquals(1, t.list().size());
        Editions.Edition e = t.byId("heavenfall_beta");
        assertEquals(e, t.first());
        assertEquals("Heavenfall Beta", e.name);
        assertEquals("popzol/4u-heavenfall-mod-beta", e.repository);
        assertEquals("0004000000126300", e.title);
        assertEquals("0004000E00126300", e.updateTitle);
        assertEquals("HeavenfallBeta-android-USA.hfpatch", e.setName("usa", false));
        assertEquals("HeavenfallBeta-android-USA-XX.hfpatch", e.setName("usa", true));
        assertNull(e.setName("eur", false));
        assertEquals("HeavenfallBeta-3ds-shared.hfpack", e.sharedFile);
        assertEquals("HeavenfallBeta-android-release.json", e.releaseFile);
        assertEquals(Arrays.asList("HeavenfallBeta-android-USA.hfpatch", "HeavenfallBeta-android-USA-XX.hfpatch",
                "HeavenfallBeta-3ds-shared.hfpack"), e.wantedNames());
        assertNull(t.byId("full"));
    }

    /** A table of the shipped file's shape, in a fixed text the broken variants below are cut from. */
    private static final String SAMPLE = "{\"format\": 1, \"app\": {\"repository\": \"popzol/heavenfall-recomp-android\", "
            + "\"apk\": \"Heavenfall-android.apk\", \"release_file\": \"Heavenfall-android-release.json\"}, "
            + "\"editions\": [{\"id\": \"heavenfall_beta\", \"name\": \"Heavenfall Beta\", "
            + "\"repository\": \"popzol/4u-heavenfall-mod-beta\", \"title\": \"0004000000126300\", "
            + "\"update_title\": \"0004000E00126300\", "
            + "\"sets\": {\"usa\": \"HeavenfallBeta-android-USA.hfpatch\", \"usa_xx\": \"HeavenfallBeta-android-USA-XX.hfpatch\"}, "
            + "\"shared_file\": \"HeavenfallBeta-3ds-shared.hfpack\", \"release_file\": \"HeavenfallBeta-android-release.json\"}]}";

    private static String with(String from, String to) {
        String json = SAMPLE;
        assertTrue("the sample lost " + from, json.contains(from));
        return json.replace(from, to);
    }

    private static void refused(String json) {
        try {
            Editions.parse(json);
            fail("must be refused: " + json);
        } catch (Editions.InvalidException expected) {
            // named refusal
        }
    }

    /** A table in a newer format, a broken name, a repository that is a path or a title that is not an id is refused. */
    @Test
    public void brokenTablesAreRefused() {
        assertEquals(1, Editions.parse(SAMPLE).list().size());
        refused("not json");
        refused("[]");
        refused(with("\"format\": 1", "\"format\": 2"));
        refused(with("\"format\": 1,", ""));
        refused(with("popzol/heavenfall-recomp-android", "popzol"));
        refused(with("popzol/heavenfall-recomp-android", "https://github.com/popzol/x"));
        refused(with("Heavenfall-android.apk", "../Heavenfall-android.apk"));
        refused(with("0004000000126300", "00040000001263"));
        refused(with("\"editions\": [", "\"editions\": [] , \"x\": ["));
        refused(with("\"sets\": {\"usa\": \"HeavenfallBeta-android-USA.hfpatch\", \"usa_xx\": \"HeavenfallBeta-android-USA-XX.hfpatch\"}",
                "\"sets\": {}"));
        refused(with("HeavenfallBeta-android-USA-XX.hfpatch", "HeavenfallBeta-android-USA.hfpatch"));
        refused(with("\"id\": \"heavenfall_beta\"", "\"id\": \"Beta Edition\""));
        refused(with("\"name\": \"Heavenfall Beta\"", "\"name\": 7"));
    }

    /** Two entries with one id would make "the edition" ambiguous. */
    @Test
    public void duplicateIds() {
        String json = SAMPLE;
        int start = json.indexOf("{\"id\"");
        int end = json.lastIndexOf("}]}");
        String entry = json.substring(start, end + 1);
        refused(json.substring(0, end + 1) + ", " + entry + json.substring(end + 1));
    }
}
