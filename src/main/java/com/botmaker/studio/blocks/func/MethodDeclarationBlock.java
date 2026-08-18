package com.botmaker.studio.blocks.func;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.ui.app.AddFunctionDialog;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

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
    /**
     * Whether the header may offer signature edits. Protected because {@link ConstructorBlock} builds its own
     * header and must answer this the same way — it did not ask at all, so a generated file's private
     * constructor (every {@code FlowDriver} and {@code ActivityRegistry} has one) kept a live delete and
     * add-parameter button.
     */
    protected boolean canEditSignature() {
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

    /** The declared method's name — mirrors {@code MethodInvocationBlock.getMethodName()} on the call side. */
    public String getMethodName() {
        return methodName;
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

        // The name is shown, never typed into: the whole signature is edited in one place. See editSignature.
        Label nameLabel = new Label(methodName);
        nameLabel.getStyleClass().add("header-name-label");
        Node nameNode = nameLabel;

        Label returnsLabel = new Label("returns");
        returnsLabel.getStyleClass().add("method-returns-label");

        Label returnTypeLabel = new Label(returnType);
        returnTypeLabel.getStyleClass().add("return-type-label");

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
            topRowBuilder.addNode(editSignatureButton(context));

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
                paramRowBuilder.addNode(createParamNode(param, i, context, false));
            }
        }

        HBox paramRow = paramRowBuilder.build();

        headerBox.getChildren().addAll(topRow, paramRow);
        container.getChildren().add(headerBox);

        if (!isCollapsed) {
            container.getChildren().add(bodyWrapper);
        }

        return container;
    }

    /**
     * One parameter, as a pill. {@code editable} is false for a method — its parameters are changed through
     * the header's Edit button, in one edit with the rest of the signature — and {@link ConstructorBlock}'s
     * own answer for a constructor, which has no such dialog to send them to.
     */
    Node createParamNode(SingleVariableDeclaration param, int index, CodeEditorService context,
                         boolean editable) {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("param-pill");

        Label typeLabel = new Label(param.getType().toString());
        typeLabel.getStyleClass().add("param-type-label");

        if (editable) {
            ExpressionMenu.installTypeSelector(typeLabel, "Click to change type",
                    () -> ProjectAnalyzer.resolveType(param.getType()), context, null,
                    newType -> context.getCodeEditor().changeMethodParameterType((MethodDeclaration) this.astNode, index, newType));
        }

        String currentName = param.getName().getIdentifier();
        Node nameNode;
        if (editable) {
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

        if (editable) {
            Button deleteBtn = new Button("×");
            deleteBtn.getStyleClass().add("param-delete-button");

            deleteBtn.setOnAction(e -> {
                context.getCodeEditor().deleteParameterFromMethod((MethodDeclaration) this.astNode, index);
            });
            box.getChildren().add(deleteBtn);
        }

        return box;
    }

    /**
     * The one control that changes this method's signature: it opens {@link AddFunctionDialog} on what is
     * written, and applies whatever comes back as a single edit.
     *
     * <p>It replaces a live name field, a return-type chip and a chip pair per parameter. Each of those
     * rewrote the file the moment it was touched, so getting from {@code click(int)} to
     * {@code click(Point, int)} meant passing through signatures nobody asked for — every one of them a
     * moment where the file did not compile and the call sites were broken. Deciding the whole signature
     * first and writing it once is what makes the change compilation-safe.
     *
     * <p>The button is <em>never</em> disabled. It used to grey itself out whenever the signature named a type
     * the dialog cannot offer ({@code String[] args}), which put the explanation in the one place a user
     * cannot get at: a disabled control has no click, and the tooltip on it reads as "this button is broken"
     * rather than "this function is unusual". It now always opens — on the dialog when the signature can be
     * described, and on a sentence naming the exact part that cannot when it can't.
     */
    private Button editSignatureButton(CodeEditorService context) {
        MethodDeclaration method = (MethodDeclaration) this.astNode;
        Button edit = new Button("✎");
        edit.getStyleClass().add("header-edit-button");
        edit.setTooltip(new Tooltip("Edit this function's name, inputs and result"));

        edit.setOnAction(e -> {
            Window owner = edit.getScene() == null ? null : edit.getScene().getWindow();
            Optional<FunctionDraft> current = MethodSignatures.draftOf(method);
            if (current.isEmpty()) {
                explainUneditableSignature(owner, method);
                return;
            }
            new AddFunctionDialog(owner, otherSignatures(method), current.get()).showAndWait()
                    .ifPresent(draft -> context.getCodeEditor().applyFunctionSignature(method, draft));
        });
        return edit;
    }

    /** Says which part of the signature the dialog cannot describe, and where to change it instead. */
    private static void explainUneditableSignature(Window owner, MethodDeclaration method) {
        String because = MethodSignatures.unrepresentable(method)
                .orElse("it uses something the editor cannot describe");
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("This function is edited in the Java file");
        alert.setHeaderText(method.getName().getIdentifier() + " can't be edited here");
        alert.setContentText("The editor can't rewrite this signature because " + because
                + ".\n\nOpen the Java file to change it. Its body is still yours to edit here.");
        alert.showAndWait();
    }

    /** Every signature the enclosing class declares except this method's own — which cannot clash with itself. */
    private static Set<String> otherSignatures(MethodDeclaration method) {
        if (!(method.getParent() instanceof TypeDeclaration typeDecl)) return Set.of();
        Set<String> taken = new LinkedHashSet<>(MethodSignatures.declaredIn(typeDecl));
        taken.remove(MethodSignatures.keyOf(method));
        return taken;
    }
}
