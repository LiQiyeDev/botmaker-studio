package com.botmaker.studio.core.component;

import javafx.scene.Node;

import java.util.function.Supplier;

/**
 * One declared field of a block — a keyword, an expression slot, a picker, a body — together with the two
 * things that decide whether it is drawn: who it is for ({@link Visibility}) and what should happen to it when
 * the code it edits is locked ({@link WhenLocked}).
 *
 * <p><b>Why declared rather than built.</b> Blocks assemble their fields imperatively inside
 * {@code createUINode}, so every rule about showing or hiding one has to be re-implemented per block, and the
 * copies drift — the same failure the flat template folder and the duplicated id→name switches produced
 * elsewhere in this codebase. Declaring the fields makes the rule a single filter
 * ({@link ComponentResolver}) over a single list, and makes it testable without a JavaFX toolkit.
 *
 * <p>{@code node} is a {@link Supplier}, not a {@link Node}, on purpose: a hidden component's widget is never
 * constructed. That matters for the expensive ones — an image-template picker builds thumbnails — and it means
 * a component hidden from the user cannot leak a live control into the scene graph by accident.
 *
 * <p>A supplier may return {@code null}, which means "this affordance does not exist" and is simply skipped —
 * the same null-is-absence convention the layout builders already use for a read-only block's add/delete/change
 * buttons (see {@code SentenceLayoutBuilder.addNode}).
 */
public record BlockComponent(String id, Kind kind, Visibility visibility, WhenLocked whenLocked,
                             Supplier<Node> node) {

    /** What the component is, for styling and for callers that want to find one by shape rather than by id. */
    public enum Kind {
        /** Static text: a keyword or a connecting word. */
        LABEL,
        /** A slot holding a value-producing expression. */
        EXPRESSION_SLOT,
        /** A typed chooser: an image template, a rect, an enum constant, a capture target. */
        PICKER,
        /** A nested container of statements. */
        BODY,
        /** Anything a block builds itself and the schema does not model. */
        CUSTOM
    }

    /** Which audience a component is drawn for. */
    public enum Visibility {
        /** Shown to everyone — the default, and what every un-migrated block effectively is today. */
        EVERYONE,
        /**
         * Shown only to {@link Audience#EDITOR}: generated members, scaffold wiring, and fields that exist to
         * give the editor a drop target rather than to be read.
         */
        EDITOR_ONLY
    }

    /**
     * What to do with a component whose code is locked. Locked is <em>not</em> a synonym for hidden: an
     * activity's pinned {@code return} is locked in place yet must stay on screen, because choosing which
     * outcome it reports is the entire point of it.
     */
    public enum WhenLocked {
        /** Draw it, marked read-only. The default: the user can see the value, just not change it. */
        SHOW,
        /** Drop it entirely. For components that are meaningless without the ability to edit them. */
        HIDE
    }

    public BlockComponent {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("component id is required");
        if (kind == null || visibility == null || whenLocked == null || node == null) {
            throw new IllegalArgumentException("component " + id + " is missing a required field");
        }
    }

    /** A component everyone sees, shown read-only when locked — the shape most fields have. */
    public static BlockComponent of(String id, Kind kind, Supplier<Node> node) {
        return new BlockComponent(id, kind, Visibility.EVERYONE, WhenLocked.SHOW, node);
    }

    /** A component only the bot's author sees, whatever the lock says. */
    public static BlockComponent editorOnly(String id, Kind kind, Supplier<Node> node) {
        return new BlockComponent(id, kind, Visibility.EDITOR_ONLY, WhenLocked.SHOW, node);
    }

    /** This component, but dropped rather than dimmed when its code is locked. */
    public BlockComponent hiddenWhenLocked() {
        return new BlockComponent(id, kind, visibility, WhenLocked.HIDE, node);
    }
}
