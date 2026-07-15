package com.botmaker.studio.blocks;

import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

public class ClassBlock extends AbstractCodeBlock implements BlockWithChildren {

    private final String className;
    /** The {@code extends} superclass, or null when the class extends nothing. */
    private final String superClassName;
    /** The {@code implements} interfaces, in source order; empty when there are none. */
    private final List<String> interfaceNames = new ArrayList<>();
    private final List<CodeBlock> bodyDeclarations = new ArrayList<>();
    private final BlockDragAndDropManager dragAndDropManager;

    public ClassBlock(String id, TypeDeclaration astNode, BlockDragAndDropManager manager) {
        super(id, astNode);
        this.className = astNode.getName().getIdentifier();
        // Inheritance is part of what the class *is* — an activity stub is generated as
        // `class Mine extends Activity`, and hiding that made every stub look like a plain class.
        Type superType = astNode.getSuperclassType();
        this.superClassName = (superType == null) ? null : superType.toString();
        for (Object t : astNode.superInterfaceTypes()) {
            interfaceNames.add(t.toString());
        }
        this.dragAndDropManager = manager;
    }

    /** The name of the superclass this class extends, or {@code null} if it extends nothing. */
    public String getSuperClassName() { return superClassName; }

    /** The interfaces this class implements, in source order. Never null. */
    public List<String> getInterfaceNames() { return new ArrayList<>(interfaceNames); }

    /** The class header as it reads in source: {@code Foo extends Bar implements Baz}. */
    public String headerText() {
        StringBuilder sb = new StringBuilder(className);
        if (superClassName != null) sb.append(" extends ").append(superClassName);
        if (!interfaceNames.isEmpty()) sb.append(" implements ").append(String.join(", ", interfaceNames));
        return sb.toString();
    }

    /**
     * The class header: {@code Class: Name}, followed by {@code extends Super} / {@code implements ...} when
     * present. The inheritance clause is rendered as its own de-emphasised run so the class's own name still
     * reads first, but its superclass is no longer invisible.
     */
    private Node createHeader() {
        Label name = new Label("Class: " + className);
        name.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        HBox header = new HBox(8, name);
        header.setAlignment(Pos.BASELINE_LEFT);
        header.setPadding(new Insets(0, 0, 15, 0));

        if (superClassName != null) {
            header.getChildren().add(inheritanceLabel("extends " + superClassName));
        }
        if (!interfaceNames.isEmpty()) {
            header.getChildren().add(inheritanceLabel("implements " + String.join(", ", interfaceNames)));
        }
        return header;
    }

    private Label inheritanceLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("class-inheritance");
        l.setStyle("-fx-font-size: 15px; -fx-font-style: italic; -fx-text-fill: #5d6d7e;");
        return l;
    }

    public void addBodyDeclaration(CodeBlock block) {
        bodyDeclarations.add(block);
    }

    @Override
    public List<CodeBlock> getChildren() {
        return new ArrayList<>(bodyDeclarations);
    }

// In ClassBlock.java

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(10);
        container.setPadding(new Insets(15));

        container.setStyle(
                "-fx-background-color: linear-gradient(to bottom, #ecf0f1 0%, #bdc3c7 100%);" +
                        "-fx-border-color: #34495e;" +
                        "-fx-border-width: 3px;" +
                        "-fx-border-radius: 10px;" +
                        "-fx-background-radius: 10px;"
        );

        container.getChildren().add(createHeader());

        // A read-only class is inert: no "Add Function", no drop targets between members, no drag-to-reorder.
        // The members still render (the user should be able to read generated scaffolding) — they just can't
        // be added to, removed, or shuffled. See project/FileRole.
        boolean editable = !isReadOnly();

        // --- 1. ADD BUTTONS AT TOP (Call helper to get NEW instances) ---
        if (editable) {
            container.getChildren().add(createControlToolbar(context));
            container.getChildren().add(createClassMemberSeparator(context, 0));
        }

        for (int i = 0; i < bodyDeclarations.size(); i++) {
            CodeBlock block = bodyDeclarations.get(i);

            Node node = block.getUINode(context);

            // Make methods draggable for reordering. makeBlockMovable installs the onDragDetected
            // handler itself, so call it directly at render time (not from inside an onDragDetected).
            if (editable && block instanceof MethodDeclarationBlock) {
                context.getDragAndDropManager().makeBlockMovable(node, block);
            }

            container.getChildren().add(node);
            if (editable) {
                container.getChildren().add(createClassMemberSeparator(context, i + 1));
            }
        }

        // --- 2. ADD BUTTONS AT BOTTOM (Call helper AGAIN to get NEW instances) ---
        if (editable) {
            container.getChildren().add(createControlToolbar(context));
        }

        return container;
    }

    /**
     * Helper method to create a fresh set of buttons every time it is called.
     */
    private Node createControlToolbar(CodeEditorService context) {
        // Using HBox to put them side-by-side, or VBox if you prefer them stacked
        javafx.scene.layout.HBox toolbar = new javafx.scene.layout.HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // "Add Constructor" is intentionally hidden — the bot's generated class has no user-authored
        // constructors, and exposing one only invites broken generated code. Keep only "Add Function".
        Button addMethodBtn = new Button("+ Add Function");
        addMethodBtn.setStyle(
                "-fx-background-color: #8E44AD; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;"
        );
        addMethodBtn.setOnAction(e -> {
            context.getCodeEditor().addMethodToClass(
                    (TypeDeclaration) this.astNode,
                    "newMethod",
                    "void",
                    bodyDeclarations.size()
            );
        });

        toolbar.getChildren().add(addMethodBtn);
        return toolbar;
    }

    private Region createClassMemberSeparator(CodeEditorService context, int insertIndex) {
        Region separator = new Region();
        separator.setMinHeight(15);
        separator.setMaxHeight(15);
        // No inline background: it would override the :drag-over-* pseudo-class feedback (inline styles
        // beat author stylesheets). A Region is transparent by default.
        separator.getStyleClass().add("class-member-separator");

        context.getDragAndDropManager().addClassMemberDropHandlers(separator, this, insertIndex);

        return separator;
    }
}