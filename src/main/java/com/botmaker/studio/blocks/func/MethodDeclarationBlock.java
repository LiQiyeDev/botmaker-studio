package com.botmaker.studio.blocks.func;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.ui.app.AddFunctionDialog;
import com.botmaker.studio.ui.app.SignatureEdits;
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
import java.util.function.UnaryOperator;

public class MethodDeclarationBlock extends AbstractStatementBlock implements BlockWithChildren {

    /** Drives the collapsed-header corner radius via blocks.css (`.block-header:collapsed`). */
    protected static final PseudoClass COLLAPSED = PseudoClass.getPseudoClass("collapsed");

    private final String methodName;
    private final String returnType;
    private BodyBlock body;

    protected boolean isDeletable = true; // False for Main method
    private boolean isCollapsed = false;
    // There used to be a lock badge here — a short note in the header saying what the user could do with this
    // method ("Generated - Read Only", "Name and parameters required by BotMaker") and, on the one method they
    // were meant to fill in, "Your code goes here" plus a block-level accent that made it findable among a
    // dozen identical-looking siblings. Every one of those sentences was about a method BotMaker wrote, and it
    // writes none: in a file that is entirely the user's, a badge on one method would be answering a question
    // nobody is asking.

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

    /**
     * The "BotMaker changed this and could not finish" badge, or null for a function carrying no mark.
     *
     * <p>Read from this block's own {@code @NeedsReview} annotation rather than pushed in by the converter:
     * the annotation is on the very {@link MethodDeclaration} this block was built from, so the count cannot
     * drift from the source, and a mark stripped in the Review tab disappears from the header on the next
     * render with nothing to keep in step.
     *
     * <p>The block editor renders no annotations at all — this is the one it makes visible, because a mark
     * nobody sees while looking straight at the function is a mark that does not do its job. The entries
     * themselves are the tooltip: the header has room for a count, not for three sentences.
     */
    private Node reviewBadge() {
        List<String> entries = ReviewMarks.entriesOf((MethodDeclaration) this.astNode);
        if (entries.isEmpty()) return null;

        Label badge = new Label(entries.size() == 1 ? "⚑ 1 to review" : "⚑ " + entries.size() + " to review");
        badge.getStyleClass().add("method-review-badge");
        badge.setTooltip(new Tooltip(String.join("\n\n", entries)));
        return badge;
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

        Node reviewBadge = reviewBadge();
        if (reviewBadge != null) topRowBuilder.addNode(reviewBadge);

        topRowBuilder.addNode(BlockUIComponents.createSpacer())
                .addNode(returnsLabel).addNode(returnTypeLabel);

        if (canEditSignature()) {
            Button deleteBtn = new Button("×");
            deleteBtn.getStyleClass().add("header-delete-button");
            deleteBtn.setOnAction(e -> SignatureEdits.delete(
                    context, windowOf(deleteBtn), (MethodDeclaration) this.astNode));
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
            // Retyping one input is a signature change like any other, so it goes through the same scan and
            // preview the ✎ dialog does. Rebuilding the draft and replacing one row's type — rather than
            // rewriting the declaration in place — is also what keeps every other parameter's origin intact,
            // so the migration reads this as "input 2 is now a Point" and not as five parameters replaced.
            ExpressionMenu.installTypeSelector(typeLabel, "Click to change type",
                    () -> ProjectAnalyzer.resolveType(param.getType()), context, null,
                    newType -> SignatureEdits.edit(context, windowOf(typeLabel),
                            (MethodDeclaration) this.astNode,
                            draft -> withParameter(draft, index, p -> new FunctionDraft.Parameter(p.name(),
                                    MethodSignatures.signatureTypeOf(newType.simpleName()), p.origin()))));
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

            // Same route as the retype above, for the same reason: dropping an input changes what every call
            // has to pass, and the body may still be reading the name. Both are the migration's business.
            deleteBtn.setOnAction(e -> SignatureEdits.edit(context, windowOf(deleteBtn),
                    (MethodDeclaration) this.astNode, draft -> withoutParameter(draft, index)));
            box.getChildren().add(deleteBtn);
        }

        return box;
    }

    /** The window a control is in, for a dialog to be modal over — null before the block is in a scene. */
    static Window windowOf(Node node) {
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    /** {@code draft} with the parameter at {@code index} run through {@code change}. */
    static FunctionDraft withParameter(FunctionDraft draft, int index,
                                       UnaryOperator<FunctionDraft.Parameter> change) {
        List<FunctionDraft.Parameter> parameters = new ArrayList<>(draft.parameters());
        if (index < 0 || index >= parameters.size()) return draft;
        parameters.set(index, change.apply(parameters.get(index)));
        return new FunctionDraft(draft.name(), draft.returnType(), parameters);
    }

    /** {@code draft} with one more parameter on the end, marked as new so every call gets a default there. */
    static FunctionDraft withAddedParameter(FunctionDraft draft, String name, SignatureType type) {
        List<FunctionDraft.Parameter> parameters = new ArrayList<>(draft.parameters());
        parameters.add(new FunctionDraft.Parameter(name, type));
        return new FunctionDraft(draft.name(), draft.returnType(), parameters);
    }

    /** {@code draft} without the parameter at {@code index}. */
    static FunctionDraft withoutParameter(FunctionDraft draft, int index) {
        List<FunctionDraft.Parameter> parameters = new ArrayList<>(draft.parameters());
        if (index < 0 || index >= parameters.size()) return draft;
        parameters.remove(index);
        return new FunctionDraft(draft.name(), draft.returnType(), parameters);
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
                SignatureEdits.explainUneditable(owner, method);
                return;
            }
            new AddFunctionDialog(owner, otherSignatures(method), current.get()).showAndWait()
                    .ifPresent(draft -> SignatureEdits.apply(context, owner, method, current.get(), draft));
        });
        return edit;
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
