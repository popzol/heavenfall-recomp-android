package io.github.popzol.heavenfall.app;

import com.mh4u.port.HeavenfallScreens;

import java.io.File;

import io.github.popzol.heavenfall.DumpIdentifier;

/**
 * The dump reading over the game folder's own checks (upstream's {@code Dump}): the game and its update are told
 * apart by their title ids, the region by the id's last half, the encryption by the NCCH flags (a CIA whose game is
 * encrypted by its TMD). MH XX is not identified here: it reads as "not a dump" until the native reader of the three
 * dumps is put in its place ({@link Wiring}), because only that one checks the revision the XX sets are made for.
 */
final class UpstreamDumpIdentifier implements DumpIdentifier {
    private static final long GAME_HIGH = 0x00040000L;
    private static final long UPDATE_HIGH = 0x0004000EL;
    private static final long USA = 0x00126300L;
    private static final long EUR = 0x00126100L;
    /** Monster Hunter 4G (Japan): recognized so that the screen can say it is not the game Heavenfall needs. */
    private static final long MH4G = 0x0011D700L;

    @Override
    public Identity identify(File file) {
        HeavenfallScreens.DumpFacts facts = HeavenfallScreens.readDump(file);
        if (facts == null) return Identity.NOT_A_DUMP;
        long high = facts.titleId >>> 32;
        long low = facts.titleId & 0xFFFFFFFFL;
        Identity.Kind kind = high == GAME_HIGH ? Identity.Kind.GAME : high == UPDATE_HIGH ? Identity.Kind.UPDATE : null;
        if (kind == null) return Identity.NOT_A_DUMP;
        Identity.Region region;
        if (low == USA) region = Identity.Region.USA;
        else if (low == EUR) region = Identity.Region.EUR;
        else if (low == MH4G) region = Identity.Region.OTHER;
        else return Identity.NOT_A_DUMP;
        return new Identity(kind, region, facts.encrypted, kind == Identity.Kind.UPDATE ? facts.version : -1);
    }
}
