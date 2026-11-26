package io.github.popzol.heavenfall;

/** How far a step is, 0..1, told by the step to the job. */
public interface Fraction {
    void fraction(double done);
}
