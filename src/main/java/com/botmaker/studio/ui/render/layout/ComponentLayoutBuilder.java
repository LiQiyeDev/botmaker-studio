package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.core.component.Audience;
import com.botmaker.studio.core.component.BlockComponent;
import com.botmaker.studio.core.component.ComponentResolver;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.core.render.ReadOnlyDecorator;
import javafx.geometry.Pos;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link ComponentSpec} — the schema-driven counterpart to {@link SentenceLayoutBuilder}, which
 * every un-migrated block still uses.
 *
 * <p>It adds no styling, no spacing rule and no container of its own: the result is the same
 * {@link WrappingSentencePane} a sentence layout produces, so CSS, {@code styleContainer} and every caller that
 * reaches into {@code getChildren()} are unaffected. The only thing this builder does that the sentence
 * builder does not is <em>ask</em> — {@link ComponentResolver} decides, per component, between drawing it,
 * drawing it read-only, and not building it at all.
 *
 * <p>A hidden component's {@code node} supplier is never invoked, so its widget never enters the scene graph.
 */
public final class ComponentLayoutBuilder {

    private final ComponentSpec spec;
    private final Audience audience;
    private final boolean locked;
    private double spacing = 5.0;
    private Pos alignment = Pos.CENTER_LEFT;

    ComponentLayoutBuilder(ComponentSpec spec, Audience audience, boolean locked) {
        this.spec = spec == null ? ComponentSpec.empty() : spec;
        this.audience = audience == null ? Audience.EDITOR : audience;
        this.locked = locked;
    }

    public ComponentLayoutBuilder spacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    public ComponentLayoutBuilder alignment(Pos alignment) {
        this.alignment = alignment;
        return this;
    }

    public WrappingSentencePane build() {
        List<Node> nodes = new ArrayList<>();
        for (BlockComponent component : spec.components()) {
            ComponentResolver.Verdict verdict = ComponentResolver.resolve(component, audience, locked);
            if (!verdict.isVisible()) continue;

            Node node = component.node().get();
            // Null is "this affordance does not exist", the convention SentenceLayoutBuilder.addNode
            // documents — a read-only block's buttons return null rather than a disabled control.
            if (node == null) continue;

            if (verdict.isReadOnly()) {
                node.pseudoClassStateChanged(ReadOnlyDecorator.READ_ONLY, true);
            }
            nodes.add(node);
        }

        WrappingSentencePane container = new WrappingSentencePane(spacing);
        container.setAlignment(alignment);
        container.getChildren().addAll(nodes);
        return container;
    }
}
