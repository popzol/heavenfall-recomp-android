package io.github.popzol.heavenfall.app;

import com.mh4u.port.HeavenfallScreens;

import java.io.File;

import io.github.popzol.heavenfall.DumpIdentifier;
import io.github.popzol.heavenfall.ModBuilder;
import io.github.popzol.heavenfall.NativeIdentities;

/**
 * The folder scan's reading of a file: the native layer says which of the three dumps it is (the American game, its
 * update, MH XX), its region and whether it is encrypted, so the screens and the mod's maker read files the same way;
 * an update's title version comes from its header ({@link HeavenfallScreens#readDump}).
 */
final class NativeDumpIdentifier implements DumpIdentifier {
    private final ModBuilder.Backend backend;

    NativeDumpIdentifier(ModBuilder.Backend backend) {
        this.backend = backend;
    }

    @Override
    public Identity identify(File file) {
        return NativeIdentities.toScreens(ModBuilder.identify(backend, SourceFile.of(file)), () -> {
            HeavenfallScreens.DumpFacts facts = HeavenfallScreens.readDump(file);
            return facts == null ? -1 : facts.version;
        });
    }
}
