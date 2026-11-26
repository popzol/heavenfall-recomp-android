package io.github.popzol.heavenfall;

/** How far a step is, 0..1, told by the step to the job. */
public interface Fraction {
    void fraction(double done);

    /**
     * The step's own measure of the time it has left, in milliseconds (a build counts its compiled objects:
     * {@link BuildEta}); negative when it has none, and the plan's estimate stands. Steps that cannot tell never call it.
     */
    default void msLeft(long ms) {
    }
}
