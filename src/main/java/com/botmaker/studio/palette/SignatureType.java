package com.botmaker.studio.palette;

import java.util.Optional;

/**
 * A type as it appears in a function's signature: one of the curated {@link BotType}s the editor can offer, or
 * one it can only <em>carry</em> — the source text, kept verbatim.
 *
 * <p>The second case is why this exists. {@code MethodSignatures.draftOf} used to return empty for any type
 * outside the catalogue, so an activity's {@code Outcome run(…)} — a signature the SDK writes and the user
 * never chose — could not have its name or its inputs edited either. The dialog explained, correctly and
 * uselessly, that it could not describe {@code Outcome}. But it never needed to describe it: it needed to
 * leave it alone. {@link Kept} says exactly that, and every write path treats it as "don't touch this node",
 * so the type reaches the file as the same characters it left it.
 *
 * <p>Two positions, not a whitelist of extra types: the moment the editor is asked to <em>offer</em> a type it
 * has to know its default value, its editors and its imports, which is what {@link BotType} is. Carrying one
 * through needs none of that.
 */
public sealed interface SignatureType {

    /** How the type is written in source — {@code Point}, {@code List<Point>}, {@code Outcome}. */
    String sourceName();

    /** A type the editor knows: it can be picked, defaulted, imported and swapped for another. */
    record Described(BotType.Choice choice) implements SignatureType {
        public Described {
            if (choice == null) throw new IllegalArgumentException("a described type needs a choice");
        }

        @Override
        public String sourceName() {
            return choice.sourceName();
        }
    }

    /** A type the editor only carries: shown, never offered, and written back unchanged. */
    record Kept(String sourceName) implements SignatureType {
        public Kept {
            sourceName = sourceName == null ? "" : sourceName.trim();
            if (sourceName.isEmpty()) throw new IllegalArgumentException("a kept type needs its source text");
        }
    }

    static SignatureType of(BotType.Choice choice) {
        return new Described(choice);
    }

    static SignatureType of(BotType type) {
        return new Described(BotType.Choice.of(type));
    }

    static SignatureType kept(String sourceName) {
        return new Kept(sourceName);
    }

    /** The curated choice, when there is one — empty for a type that is only carried. */
    default Optional<BotType.Choice> described() {
        return this instanceof Described described ? Optional.of(described.choice()) : Optional.empty();
    }

    /** Whether this type is written back verbatim rather than rewritten from a choice. */
    default boolean isKept() {
        return this instanceof Kept;
    }

    /** Whether the function gives nothing back. A carried type always gives something — it has a name. */
    default boolean isVoid() {
        return this instanceof Described described && described.choice().isVoid();
    }

    /**
     * The value a body is seeded with when it has to produce one of these — {@code ""}, {@code false},
     * {@code List.of()}, and {@code null} for a type the editor only carries, which is the one honest answer
     * for a type it knows nothing about.
     */
    default String defaultText() {
        return described().map(BotType.Choice::defaultText).orElse("null");
    }

    /** What the user is shown: the choice's own label, or the source text for one that is only carried. */
    default String label() {
        return this instanceof Described described ? described.choice().label() : sourceName();
    }
}
