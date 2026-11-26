package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VersionTest {
    /** A tag without the "v" the releases sometimes lack must still be a version (the 3DS launcher accepts both). */
    @Test
    public void tagWithAndWithoutV() {
        assertEquals(Version.parse("v1.2.3"), Version.parse("1.2.3"));
        assertEquals("1.2.3", Version.parse("v1.2.3").toString());
    }

    /** Comparing as text would call 1.9 newer than 1.10. */
    @Test
    public void numericFieldsNotText() {
        assertEquals(1, Version.newer("1.10", "1.9"));
        assertEquals(1, Version.newer("v0.2.10", "0.2.9"));
        assertEquals(0, Version.newer("0.2.9", "0.2.10"));
    }

    /** "1.2" and "v1.2.0" are the same release: no update loop between them. */
    @Test
    public void trailingZerosDoNotCount() {
        assertEquals(0, Version.newer("1.2.0", "1.2"));
        assertEquals(0, Version.newer("1.2", "v1.2.0.0"));
        assertEquals(1, Version.newer("1.2.1", "1.2"));
        assertEquals(0, Version.newer("1.2", "1.2.1"));
        assertEquals("1.2", Version.parse("v1.2.0").toString());
    }

    /** Anything that is not numbers and dots must not parse: a hostile or odd tag cannot reach a comparison. */
    @Test
    public void notVersions() {
        for (String bad : new String[] {null, "", "v", "latest", "1..2", "1.2.", ".1", "1.2-beta", "V1.2", "vv1", "1.a",
                "1234567890", "1.2.3.4.5.6.7.8.9", "1 2", "-1", "+1", "1.2\u0000"}) {
            assertNull("must not parse: " + bad, Version.parse(bad));
        }
        assertFalse(Version.isValid("nope"));
    }

    /** Surrounding blanks (a file's last newline) are not part of the version. */
    @Test
    public void blanksAround() {
        assertNotNull(Version.parse(" v1.0\n"));
        assertEquals(Version.parse("1"), Version.parse(" v1.0\r\n"));
    }

    /** A version that is not understood is "-1", never "not newer": the caller must not take it for up to date. */
    @Test
    public void unknownIsMinusOne() {
        assertEquals(-1, Version.newer("junk", "1.0"));
        assertEquals(-1, Version.newer("1.0", "junk"));
        assertEquals(-1, Version.newer(null, "1.0"));
    }

    /** An installed version nobody understands is updated over; a latest nobody understands never is. */
    @Test
    public void updateToWithUnknownCurrent() {
        assertTrue(Version.updateTo("1.0", "junk"));
        assertTrue(Version.updateTo("1.0", ""));
        assertFalse(Version.updateTo("junk", "1.0"));
        assertFalse(Version.updateTo("1.0", "1.0"));
        assertFalse(Version.updateTo(null, "1.0"));
    }
}
