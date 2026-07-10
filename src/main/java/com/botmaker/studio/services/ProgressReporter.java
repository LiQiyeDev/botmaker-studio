package com.botmaker.studio.services;

/**
 * Progress sink for long-running open/resolve work: a {@code fraction} in {@code [0,1]} (or a negative value
 * for "indeterminate", i.e. no meaningful percentage yet) plus a short human-readable {@code message}.
 *
 * <p>May be invoked from Aether/background worker threads — callers that touch the UI must marshal onto the
 * FX thread.
 */
@FunctionalInterface
public interface ProgressReporter {

    ProgressReporter NONE = (fraction, message) -> {};

    void report(double fraction, String message);

    /** Reports an indeterminate step (no percentage) with just a status message. */
    default void message(String message) {
        report(-1, message);
    }
}
