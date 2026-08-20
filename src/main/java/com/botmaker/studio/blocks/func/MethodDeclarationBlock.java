package com.botmaker.studio.blocks.func;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.parser.refactor.MethodReferences;
import com.botmaker.studio.parser.refactor.SignatureMigration;
import com.botmaker.studio.ui.app.AddFunctionDialog;
import com.botmaker.studio.ui.app.SignatureMigrationDialog;
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

    /**
     * The header alone, whenever this method's lock is its <em>signature's</em> — an activity's
     * {@code Outcome run()} is the case that matters, where the signature is fixed and the body is the whole
     * reason the stub exists.
     *
     * <p>{@code setReadOnly} on this block has only ever meant "the signature is kept"
     * ({@code BlockConverter}: {@code setReadOnly(!signatureEditable(...))}, with the body's own verdict passed
     * down separately). The wash did not know that, so a run method the user was invited to fill in was drawn
     * as dim as the scaffolding around it, and the natural reading of a dim body is that it is locked.
     *
     * <p>The body block still dims itself when it is genuinely read-only, so a locked file looks exactly as it
     * did — see {@code .block-body:read-only} in blocks.css.
     */
    @Override
    public Node lockedSurface(Node root) {
        if (body == null || body.isReadOnly()) return root;
        Node header = root.lookup(".block-header");
        return header != null ? header : root;
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

        // The pencil goes *beside the name it edits*, not at the far end of the header past a spacer. What it
        // opens is the signature dialog — name, inputs, result — so out on the right, after the return type and
        // next to the × that deletes the whole function, it read as a second delete-ish control belonging to
        // nothing in particular. Its size is blocks.css's business; it used to be whatever a 14px glyph with no
        // padding happened to be, which is a hit target of about ten pixels.
        if (canEditSignature()) topRowBuilder.addNode(editSignatureButton(context));

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
                    .ifPresent(draft -> migrateAndApply(context, owner, method, current.get(), draft));
        });
        return edit;
    }

    /**
     * Carries the edited signature to every call in the project, asking first.
     *
     * <p>Three outcomes, and the order is the whole design. A project with a file that doesn't parse, or a call
     * this editor can't be certain about, is <b>refused</b> — named, explained, and nothing written, because a
     * rename that reaches three of four call sites is worse than one that reaches none. A signature nothing
     * calls yet <b>just saves</b>, as it did before this existed. Anything else is <b>previewed</b>: the user
     * sees what will happen to which files and gets a Cancel that leaves even the declaration untouched.
     */
    private static void migrateAndApply(CodeEditorService context, Window owner, MethodDeclaration method,
                                        FunctionDraft before, FunctionDraft after) {
        MethodReferences.Result references = MethodReferences.find(context.getState(), method);
        if (references.isRefusal()) {
            explainRefusedMigration(owner, method, references.refusal());
            return;
        }
        SignatureMigration.Plan plan =
                SignatureMigration.of(before, after, method, references.calls());
        if (plan.isEmpty()) {
            context.getCodeEditor().applyFunctionSignature(method, after);
            return;
        }
        if (!SignatureMigrationDialog.confirm(owner, method.getName().getIdentifier(), plan)) return;
        context.getCodeEditor().applyFunctionSignature(method, after, plan);
    }

    /** Why the change could not be made, naming the file that has to be fixed first. */
    private static void explainRefusedMigration(Window owner, MethodDeclaration method, String because) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("This change can't be made yet");
        alert.setHeaderText(method.getName().getIdentifier() + " wasn't changed");
        alert.setContentText(because);
        alert.showAndWait();
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

    // No "Variables in this activity…" entry here any more: it opened a list of every local the activity
    // declares, and that list is gone. A variable is edited from the block that declares it — the gesture that
    // says which one. The method header could only ever offer all of them and let the user find theirs.
}
