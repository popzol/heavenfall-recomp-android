package io.github.popzol.heavenfall;

import static io.github.popzol.heavenfall.DumpIdentifier.Identity;
import static io.github.popzol.heavenfall.HomeModel.Source;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DumpScanTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, Identity> known = new HashMap<>();
    private final DumpIdentifier identifier = f -> known.getOrDefault(f.getName(), Identity.NOT_A_DUMP);

    private static Identity game(Identity.Region region, boolean encrypted) {
        return new Identity(Identity.Kind.GAME, region, encrypted, -1);
    }

    private static Identity update(Identity.Region region, boolean encrypted, int version) {
        return new Identity(Identity.Kind.UPDATE, region, encrypted, version);
    }

    private File folderWith(String... names) throws IOException {
        File dir = tmp.newFolder();
        for (String n : names) new File(dir, n).createNewFile();
        return dir;
    }

    private DumpScan.Found scan(String... names) throws IOException {
        return DumpScan.scan(folderWith(names), identifier);
    }

    /** The title version of 1.3.0 is the 3DS's packing of 1.3.0, the number the real update's TMD carries. */
    @Test
    public void updateVersionIsTheRealOne() {
        assertEquals(1072, DumpScan.UPDATE_TITLE_VERSION);
    }

    /** The game, its update and MH XX are found by what they are, whatever their names. */
    @Test
    public void findsTheThreeByWhatTheyAre() throws IOException {
        known.put("a.3ds", game(Identity.Region.USA, false));
        known.put("b.cia", update(Identity.Region.USA, false, DumpScan.UPDATE_TITLE_VERSION));
        known.put("c.cci", new Identity(Identity.Kind.XX, Identity.Region.OTHER, false, -1));
        DumpScan.Found f = scan("a.3ds", "b.cia", "c.cci", "notes.txt");
        assertEquals(Source.GOOD, f.game);
        assertEquals("a.3ds", f.gameFile.getName());
        assertEquals(Source.GOOD, f.update);
        assertEquals("b.cia", f.updateFile.getName());
        assertEquals(Source.GOOD, f.xx);
        assertEquals("c.cci", f.xxFile.getName());
    }

    /** An empty folder, a missing one and no folder at all hold nothing. */
    @Test
    public void nothingFound() throws IOException {
        assertEquals(Source.MISSING, scan().game);
        assertEquals(Source.MISSING, DumpScan.scan(new File(tmp.getRoot(), "gone"), identifier).update);
        assertEquals(Source.MISSING, DumpScan.scan(null, identifier).xx);
        assertNull(scan().gameFile);
    }

    /** A file whose extension is not a dump's is never read, whatever the identifier would say. */
    @Test
    public void onlyDumpExtensionsAreRead() throws IOException {
        known.put("game.txt", game(Identity.Region.USA, false));
        assertEquals(Source.MISSING, scan("game.txt").game);
        known.put("GAME.3DS", game(Identity.Region.USA, false));
        assertEquals("extensions are not case sensitive", Source.GOOD, scan("GAME.3DS").game);
    }

    /** The encrypted copy, the European game and Monster Hunter 4G each read as their own problem. */
    @Test
    public void gameProblems() throws IOException {
        known.put("a.3ds", game(Identity.Region.USA, true));
        assertEquals(Source.ENCRYPTED, scan("a.3ds").game);
        known.put("a.3ds", game(Identity.Region.EUR, false));
        assertEquals(Source.WRONG_REGION, scan("a.3ds").game);
        known.put("a.3ds", game(Identity.Region.OTHER, false));
        assertEquals(Source.WRONG_VERSION, scan("a.3ds").game);
    }

    /** The update: encrypted, another region, another version; an unknown version (a bare NCCH) is not refused. */
    @Test
    public void updateProblems() throws IOException {
        known.put("u.cia", update(Identity.Region.USA, true, DumpScan.UPDATE_TITLE_VERSION));
        assertEquals(Source.ENCRYPTED, scan("u.cia").update);
        known.put("u.cia", update(Identity.Region.EUR, false, DumpScan.UPDATE_TITLE_VERSION));
        assertEquals(Source.WRONG_REGION, scan("u.cia").update);
        known.put("u.cia", update(Identity.Region.USA, false, (1 << 10) | (1 << 4)));
        assertEquals(Source.WRONG_VERSION, scan("u.cia").update);
        known.put("u.cia", update(Identity.Region.USA, false, -1));
        assertEquals(Source.GOOD, scan("u.cia").update);
    }

    /** A usable copy beats an encrypted one beside it, and the encrypted one beats a wrong region, whatever the names. */
    @Test
    public void theBestCandidateDecides() throws IOException {
        known.put("a.3ds", game(Identity.Region.USA, true));
        known.put("b.3ds", game(Identity.Region.USA, false));
        known.put("c.3ds", game(Identity.Region.EUR, false));
        DumpScan.Found f = scan("a.3ds", "b.3ds", "c.3ds");
        assertEquals(Source.GOOD, f.game);
        assertEquals("b.3ds", f.gameFile.getName());

        DumpScan.Found g = scan("a.3ds", "c.3ds");
        assertEquals(Source.ENCRYPTED, g.game);
        assertEquals("a.3ds", g.gameFile.getName());
    }

    /** An update is not taken for a game, nor a game for an update, even in the other's place. */
    @Test
    public void kindsStayApart() throws IOException {
        known.put("x.cia", update(Identity.Region.USA, false, DumpScan.UPDATE_TITLE_VERSION));
        DumpScan.Found f = scan("x.cia");
        assertEquals(Source.MISSING, f.game);
        assertEquals(Source.GOOD, f.update);
    }
}
