package io.github.popzol.heavenfall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A title id read from the applier (lower case) against the editions table's (upper case). */
public class TitleIdsTest {
    @Test
    public void theSameIdInOtherCaseIsTheSameTitle() {
        // Catches an update refused as "another update" because 0004000e00126300 differs from 0004000E00126300.
        assertTrue(TitleIds.same("0004000e00126300", "0004000E00126300"));
        assertFalse(TitleIds.same("0004000e00126300", "0004000000126300"));
        assertFalse(TitleIds.same(null, null));
        assertFalse(TitleIds.same("0004000E00126300", null));
    }

    @Test
    public void whatTheNativeLayerAnswersIsKeptInTheTablesCase() {
        // Catches a title that stays in lower case in the parsed answers and is later compared with the table by equals.
        Identity id = Identity.parse("rc=0\nkind=update\nregion=usa\nencrypted=0\ntitle=0004000e00126300\nrevision=\n");
        assertEquals("0004000E00126300", id.titleId);
        assertTrue(TitleIds.same(id.titleId, "0004000e00126300"));
    }
}
