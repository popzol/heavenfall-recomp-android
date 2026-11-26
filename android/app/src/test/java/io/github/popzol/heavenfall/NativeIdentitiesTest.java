package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The native reader's answer as the screens' folder scan takes it. */
public class NativeIdentitiesTest {
    private static final String REV = "0123456789012345678901234567890123456789012345678901234567890123";

    private static DumpIdentifier.Identity of(Identity.Kind kind, String region, boolean encrypted, int version) {
        return NativeIdentities.toScreens(Identity.of(kind, region, encrypted, REV), () -> version);
    }

    @Test
    public void everyKindAndRegionKeepsItsMeaning() {
        // Catches a swapped region or kind, which would turn the European game into the wrong one in the checklist.
        assertEquals(DumpIdentifier.Identity.Kind.GAME, of(Identity.Kind.GAME, "usa", false, 0).kind);
        assertEquals(DumpIdentifier.Identity.Region.USA, of(Identity.Kind.GAME, "usa", false, 0).region);
        assertEquals(DumpIdentifier.Identity.Region.EUR, of(Identity.Kind.GAME, "eur", false, 0).region);
        assertEquals(DumpIdentifier.Identity.Region.OTHER, of(Identity.Kind.GAME, "jpn", false, 0).region);
        assertEquals(DumpIdentifier.Identity.Kind.XX, of(Identity.Kind.XX, "jpn", false, 0).kind);
        assertTrue(of(Identity.Kind.GAME, "usa", true, 0).encrypted);
        assertFalse(of(Identity.Kind.GAME, "usa", false, 0).encrypted);
    }

    @Test
    public void onlyAnUpdateAsksForItsTitleVersion() {
        // Catches the header read for every file (a game would be given a version it has not), or an update's lost.
        assertEquals(DumpScan.UPDATE_TITLE_VERSION,
                of(Identity.Kind.UPDATE, "usa", false, DumpScan.UPDATE_TITLE_VERSION).titleVersion);
        assertEquals(-1, of(Identity.Kind.GAME, "usa", false, 99).titleVersion);
    }

    @Test
    public void whatIsNotOneOfTheThreeIsNotADump() {
        // Catches another game's container, a file that could not be read, or a missing answer taken as a dump.
        assertEquals(DumpIdentifier.Identity.Kind.NOT_A_DUMP, of(Identity.Kind.OTHER, "usa", false, 0).kind);
        assertEquals(DumpIdentifier.Identity.Kind.NOT_A_DUMP, of(Identity.Kind.NONE, "unknown", false, 0).kind);
        assertEquals(DumpIdentifier.Identity.Kind.NOT_A_DUMP, NativeIdentities.toScreens(null, () -> 0).kind);
    }
}
