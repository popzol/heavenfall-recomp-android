package io.github.popzol.heavenfall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A text the screens show: the name of an Android string resource ({@code hf_...} in
 * {@code strings_heavenfall.xml}) and the arguments its {@code %1$s} / {@code %1$d} places take, in order.
 * The model never builds sentences; the screens look the key up in the player's language.
 */
public final class Text {
    public final String key;
    public final List<String> args;

    public Text(String key, String... args) {
        this.key = key;
        this.args = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(args)));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Text && ((Text) o).key.equals(key) && ((Text) o).args.equals(args);
    }

    @Override
    public int hashCode() {
        return key.hashCode() * 31 + args.hashCode();
    }

    @Override
    public String toString() {
        return args.isEmpty() ? key : key + args;
    }
}
