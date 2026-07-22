package com.botmaker.studio.blocks.func;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import com.botmaker.studio.util.DefaultNames;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

import java.util.*;

public class MethodDeclarationBlock extends AbstractStatementBlock implements BlockWithChildren {

    /** Drives the collapsed-header corner radius via blocks.css (`.block-header:collapsed`). */
    protected static final PseudoClass COLLAPSED = PseudoClass.getPseudoClass("collapsed");

    /** Distinguishes a "you can't touch this" badge from a "this one's yours" badge (`.method-lock-badge:locked`). */
    protected static final PseudoClass LOCKED = PseudoClass.getPseudoClass("locked");

    private final String methodName;
    private final String returnType;
    private BodyBlock body;

    protected boolean isDeletable = true; // False for Main method
    private boolean isCollapsed = false;
    private String lockBadge;

    /** The badge text that marks the one method the user is meant to fill in. */
    private static final String YOURS_BADGE = "Your code goes here";

    /**
     * A short note rendered in the header saying what the user may do with this method — the {@code MethodLock}
     * badge ("Generated - Read Only", "Name and parameters required by BotMaker"), or the nudge toward the
     * method they <em>should</em> edit ("Your code goes here"). Null renders nothing.
     */
    public void setLockBadge(String lockBadge) {
        this.lockBadge = lockBadge;
    }

    /**
     * True when this is the method the user is meant to write. Drives the block-level accent
     * ({@code .method-block--yours}) that makes it findable at a glance: in a scaffolded file the badge alone
     * was 10px of low-contrast text among a dozen identical-looking methods, so "which one do I edit?" was a
     * question the screen didn't answer.
     */
    private boolean isUsersEntryPoint() {
        return YOURS_BADGE.equals(lockBadge);
    }

    /**
     * Whether the signature (name, return type, parameters, existence) may be changed. Read-only methods keep a
     * fully rendered header — the user should still <em>see</em> the signature — but every control that would
     * mutate it is replaced by a plain label. Without this the header's own TextFields and menus stayed live on
     * read-only blocks, since {@code ReadOnlyDecorator} only styles the node and {@code InteractionDecorator}
     * only suppresses the right-click menu.
     */
    private boolean canEditSignature() {
        return isDeletable && !isReadOnly();
    }

    public MethodDeclarationBlock(String id, MethodDeclaration astNode, BlockDragAndDropManager manager) {
        super(id, astNode);
        this.methodName = astNode.getName().getIdentifier();
        if (astNode.getReturnType2() != null) {
            this.returnType = astNode.getReturnType2().toString();
        } else {
            this.returnType = "void";
        }
    }

    public void setBody(BodyBlock body) {
        this.body = body;
    }

    @Override
    public List<CodeBlock> getChildren() {
        return body != null ? Collections.singletonList(body) : Collections.emptyList();
    }

    // Hook for subclasses (MainBlock) to hide specific parameters like 'args'
    protected boolean shouldDisplayParameter(SingleVariableDeclaration param) {
        return true;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FUNCTIONS;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(0);
        container.getStyleClass().add("method-block");
        // In a file full of scaffolding, this is the one method that should look touchable.
        if (isUsersEntryPoint()) container.getStyleClass().add("method-block--yours");

        // --- STATE SYNC ---
        String parentName = "";
        if (this.astNode.getParent() instanceof AbstractTypeDeclaration) {
            parentName = ((AbstractTypeDeclaration) this.astNode.getParent()).getName().getIdentifier();
        }
        String methodKey = parentName + "." + methodName;

        // Restore state from ApplicationState
        this.isCollapsed = context.getState().isMethodCollapsed(methodKey);

        // --- HEADER SECTION ---
        VBox headerBox = new VBox(5);
        headerBox.getStyleClass().add("block-header");
        headerBox.pseudoClassStateChanged(COLLAPSED, isCollapsed);

        // 1. Create the Body Wrapper
        VBox bodyWrapper = new VBox();
        bodyWrapper.getStyleClass().add("block-body-wrapper");

        if (body != null) {
            Node bodyNode = body.getUINode(context);
            VBox.setVgrow(bodyNode, javafx.scene.layout.Priority.ALWAYS);
            bodyWrapper.getChildren().add(bodyNode);
        }

        // 2. Collapse Toggle Button
        Button collapseBtn = new Button(isCollapsed ? "▶" : "▼");
        collapseBtn.getStyleClass().add("collapse-button");
        collapseBtn.setMinWidth(25);

        collapseBtn.setOnAction(e -> {
            this.isCollapsed = !this.isCollapsed;
            collapseBtn.setText(isCollapsed ? "▶" : "▼");
            context.getState().setMethodCollapsed(methodKey, this.isCollapsed);
            headerBox.pseudoClassStateChanged(COLLAPSED, isCollapsed);

            if (isCollapsed) {
                container.getChildren().remove(bodyWrapper);
            } else {
                container.getChildren().add(bodyWrapper);
            }
        });

        // 3. Top Row (Name & Return Type)
        Label funcLabel = new Label("Function");
        funcLabel.getStyleClass().add("header-keyword-label");

        Node nameNode;
        if (canEditSignature()) {
            TextField nameField = new TextField(methodName);
            nameField.getStyleClass().add("method-name-field");
            nameField.setPrefWidth(Math.max(80, methodName.length() * 8 + 20));

            nameField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) {
                    String newName = nameField.getText().trim();
                    if (!newName.isEmpty() && !newName.equals(methodName) && !"main".equals(newName)) {
                        context.getCodeEditor().renameMethod((MethodDeclaration) this.astNode, newName);
                    } else {
                        nameField.setText(methodName);
                    }
                }
            });
            nameNode = nameField;
        } else {
            Label nameLabel = new Label(methodName);
            nameLabel.getStyleClass().add("header-name-label");
            nameNode = nameLabel;
        }

        Label returnsLabel = new Label("returns");
        returnsLabel.getStyleClass().add("method-returns-label");

        MethodDeclaration mdRet = (MethodDeclaration) this.astNode;
        Label returnTypeLabel = new Label(returnType);
        returnTypeLabel.getStyleClass().add("return-type-label");
        if (canEditSignature()) {
            ExpressionMenu.installTypeSelector(returnTypeLabel, "Click to change return type",
                    () -> mdRet.getReturnType2() != null
                            ? ProjectAnalyzer.resolveType(mdRet.getReturnType2()) : ResolvedType.primitive("void"),
                    context, null, true,
                    newType -> context.getCodeEditor().setMethodReturnType(mdRet, newType));
        }

        var topRowBuilder = BlockLayout.sentence()
                .addNode(collapseBtn)
                .addNode(funcLabel)
                .addNode(nameNode);

        // The badge goes *beside the method name*, not after a spacer out in the middle of the header where it
        // floated next to the louder return-type chip. "Your code goes here" is the answer to the first
        // question a scaffolded file raises, so it belongs where the eye already is.
        if (lockBadge != null) {
            Label badge = new Label(lockBadge);
            badge.getStyleClass().add("method-lock-badge");
            badge.pseudoClassStateChanged(LOCKED, isReadOnly());
            topRowBuilder.addNode(badge);
        }

        topRowBuilder.addNode(BlockUIComponents.createSpacer())
                .addNode(returnsLabel).addNode(returnTypeLabel);

        if (canEditSignature()) {
            Button deleteBtn = new Button("×");
            deleteBtn.getStyleClass().add("header-delete-button");
            deleteBtn.setOnAction(e -> context.getCodeEditor().deleteMethod((MethodDeclaration) this.astNode));
            topRowBuilder.addNode(deleteBtn);
        }

        HBox topRow = topRowBuilder.build();

        // 4. Parameters Row
        Label paramsLabel = new Label("Inputs:");
        paramsLabel.getStyleClass().add("header-params-label");

        var paramRowBuilder = BlockLayout.sentence()
                .addNode(paramsLabel);

        MethodDeclaration md = (MethodDeclaration) this.astNode;
        List<?> params = md.parameters();

        for (int i = 0; i < params.size(); i++) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) params.get(i);
            if (shouldDisplayParameter(param)) {
                paramRowBuilder.addNode(createParamNode(param, i, context));
            }
        }

        if (canEditSignature()) {
            Button addParamBtn = new Button("+");
            addParamBtn.getStyleClass().add("add-param-button");
            addParamBtn.setOnAction(e -> ExpressionMenu.showTypeMenu(addParamBtn, null, context, null,
                    false, false,
                    type -> context.getCodeEditor().addParameterToMethod((MethodDeclaration) this.astNode,
                            type, DefaultNames.forType(type.simpleName()))));

            paramRowBuilder.addNode(addParamBtn);
        }

        HBox paramRow = paramRowBuilder.build();

        headerBox.getChildren().addAll(topRow, paramRow);
        container.getChildren().add(headerBox);

        if (!isCollapsed) {
            container.getChildren().add(bodyWrapper);
        }

        return container;
    }

    Node createParamNode(SingleVariableDeclaration param, int index, CodeEditorService context) {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("param-pill");

        Label typeLabel = new Label(param.getType().toString());
        typeLabel.getStyleClass().add("param-type-label");

        if (canEditSignature()) {
            ExpressionMenu.installTypeSelector(typeLabel, "Click to change type",
                    () -> ProjectAnalyzer.resolveType(param.getType()), context, null,
                    newType -> context.getCodeEditor().changeMethodParameterType((MethodDeclaration) this.astNode, index, newType));
        }

        String currentName = param.getName().getIdentifier();
        Node nameNode;
        if (canEditSignature()) {
            TextField nameField = new TextField(currentName);
            nameField.getStyleClass().add("param-name-field");
            nameField.setPrefWidth(Math.max(30, currentName.length() * 7));

            nameField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) {
                    String val = nameField.getText().trim();
                    if (!val.isEmpty() && !val.equals(currentName)) {
                        context.getCodeEditor().renameMethodParameter((MethodDeclaration) this.astNode, index, val);
                    } else {
                        nameField.setText(currentName);
                    }
                }
            });
            nameNode = nameField;
        } else {
            Label nameLabel = new Label(currentName);
            nameLabel.getStyleClass().add("param-name-label");
            nameNode = nameLabel;
        }

        box.getChildren().addAll(typeLabel, nameNode);

        if (canEditSignature()) {
            Button deleteBtn = new Button("×");
            deleteBtn.getStyleClass().add("param-delete-button");

            deleteBtn.setOnAction(e -> {
                context.getCodeEditor().deleteParameterFromMethod((MethodDeclaration) this.astNode, index);
            });
            box.getChildren().add(deleteBtn);
        }

        return box;
    }
}