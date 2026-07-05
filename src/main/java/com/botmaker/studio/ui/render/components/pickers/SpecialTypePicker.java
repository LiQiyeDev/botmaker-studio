package com.botmaker.studio.ui.render.components.pickers;

import javafx.scene.Node;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A bot-first editor for a "special" argument type: it decides whether it applies to a given
 * {@link PickerContext} and, if so, builds the JavaFX editor node that replaces the generic
 * expression pill. Registered in and dispatched by {@link PickerRegistry}.
 *
 * <p>Most pickers are simple {@code predicate + factory} pairs — use {@link #of}. Pickers that need to
 * carry state between {@code matches} and {@code create} (e.g. a resolved enum type) implement the
 * interface directly.
 */
public interface SpecialTypePicker {

    /** Whether this picker applies to {@code ctx}. */
    boolean matches(PickerContext ctx);

    /** Builds the editor node for {@code ctx}. Only called when {@link #matches} returned true. */
    Node create(PickerContext ctx);

    /** A picker from a match predicate and a node factory. */
    static SpecialTypePicker of(Predicate<PickerContext> matches, Function<PickerContext, Node> create) {
        return new SpecialTypePicker() {
            @Override public boolean matches(PickerContext ctx) { return matches.test(ctx); }
            @Override public Node create(PickerContext ctx) { return create.apply(ctx); }
        };
    }
}
