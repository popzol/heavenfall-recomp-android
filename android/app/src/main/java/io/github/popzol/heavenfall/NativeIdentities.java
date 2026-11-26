package io.github.popzol.heavenfall;

import java.util.function.IntSupplier;

/**
 * The native reader's answer ({@link Identity}) as the screens' folder scan takes it ({@link DumpIdentifier.Identity}):
 * the kind, the region and the encryption come from the native layer, and an update's title version (which the scan
 * compares with 1.3.0's) from the file's own header, asked only for an update.
 */
public final class NativeIdentities {
    private NativeIdentities() {
    }

    /**
     * @param updateTitleVersion the update's title version from its header, -1 when the file has none (a bare NCCH:
     *                           the scan then takes it as the right one)
     */
    public static DumpIdentifier.Identity toScreens(Identity id, IntSupplier updateTitleVersion) {
        if (id == null || id.rc != 0) return DumpIdentifier.Identity.NOT_A_DUMP;
        DumpIdentifier.Identity.Kind kind;
        switch (id.kind) {
            case GAME: kind = DumpIdentifier.Identity.Kind.GAME; break;
            case UPDATE: kind = DumpIdentifier.Identity.Kind.UPDATE; break;
            case XX: kind = DumpIdentifier.Identity.Kind.XX; break;
            default: return DumpIdentifier.Identity.NOT_A_DUMP;
        }
        DumpIdentifier.Identity.Region region = "usa".equals(id.region) ? DumpIdentifier.Identity.Region.USA
                : "eur".equals(id.region) ? DumpIdentifier.Identity.Region.EUR : DumpIdentifier.Identity.Region.OTHER;
        int version = kind == DumpIdentifier.Identity.Kind.UPDATE ? updateTitleVersion.getAsInt() : -1;
        return new DumpIdentifier.Identity(kind, region, id.encrypted, version);
    }
}
