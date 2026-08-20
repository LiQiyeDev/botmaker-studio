package com.botmaker.studio.ui.app.vars;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.activity.VariableWire;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.app.params.ValueEditors;
import com.botmaker.studio.ui.render.components.BotTypePicker;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One variable — its name, its type and its starting value — on a screen shaped like
 * {@link com.botmaker.studio.ui.app.AddFunctionDialog}: a modal, one labelled row per question, a line of the
 * source it will write, and controls wide enough to read.
 *
 * <p>It replaces a list of <em>every</em> variable the activity declares, which was the wrong screen for the
 * one gesture that opened it: the way in is a button on a declare block, so the variable you came to change is
 * known before the window exists. Listing forty rows to answer a question about one of them is what made the
 * types elide to {@code …}, and it is what made "Add a variable" have to guess which method to add to — a
 * guess the statement menu never has to make, since it is inserted where you clicked.
 *
 * <h2>Every edit re-resolves its node</h2>
 *
 * <p>Nothing here holds an {@link org.eclipse.jdt.core.dom.ASTNode} across an edit. The list screen kept the
 * {@code VariableDeclarationStatement} captured in each row's lambda, so the second retype in a row handed
 * {@code ASTRewrite} a node from the compilation unit that had already been replaced —
 * {@code IllegalArgumentException: Node is not inside the AST}. Here the address of the variable is
 * <em>(method name, variable name)</em>, a pair that survives a re-parse, and {@link #find()} resolves it
 * against the live unit at the moment of every write. The starting value is the same idea one layer down: it
 * is handed to the picker as a {@link ValueSlot#at ValueSlot.at(…)}, which re-asks rather than remembers.
 *
 * <h2>No Save button, on purpose</h2>
 *
 * <p>The value editors are the block canvas's own pickers, and a picker commits when you pick — that is what
 * makes it the same control here as on the block. A Save that wrote the name and the type while the value had
 * already written itself would be a button that means two different things at once. So each control applies as
 * it is used, the preview line shows what the file now says, and the button closes.
 */
public final class EditVariableDialog {

    private final CodeEditorService context;

    /** The enclosing method's name, or null for "wherever this variable is". Half of the variable's address. */
    private final String methodName;

    /** The other half. Updated as soon as a rename is asked for, so the next write resolves the new name. */
    private String variableName;

    /** The name to fall back to when a rename turns out to have been refused. */
    private String renamedFrom;

    private final TextField nameField = new TextField();
    private final Label problem = new Label();
    private final Label preview = new Label();
    private final HBox typeRow = new HBox(8);
    private final HBox valueRow = new HBox(8);

    /** True while the type picker is being filled from the file, so its listener doesn't rewrite the file. */
    private boolean prefilling;

    private EditVariableDialog(CodeEditorService context, String methodName, String variableName) {
        this.context = context;
        this.methodName = methodName;
        this.variableName = variableName;
    }

    /** Opens on {@code variableName}, wherever in the open file it is declared. */
    public static void show(CodeEditorService context, Window owner, String variableName) {
        show(context, owner, null, variableName);
    }

    /** Opens on the {@code variableName} declared in {@code methodName} — the address a rename preserves. */
    public static void show(CodeEditorService context, Window owner, String methodName, String variableName) {
        if (context == null || variableName == null || variableName.isBlank()) return;
        new EditVariableDialog(context, methodName, variableName).open(owner);
    }

    /**
     * Opens on the variable a just-inserted declare block created — the missing half of "add a variable from
     * the statement menu", which until now dropped a statement named {@code number2} and left the user to find
     * the ✎ button on it.
     *
     * <p>The new variable is identified by <em>difference</em>: the caller passes the names declared before the
     * insert, and whatever is declared afterwards and wasn't is the one. That avoids threading a return value
     * out of {@code StatementFactory.uniqueName}, several layers down a write path whose result is a string of
     * source rather than a node.
     *
     * @param before every variable name the file declared before the insert — {@link #declaredNames}
     */
    public static void openOnCreated(CodeEditorService context, Window owner, BlockType type, Set<String> before) {
        if (context == null || !(type instanceof BlockType.VarDecl)) return;
        EventBus.Subscription[] once = new EventBus.Subscription[1];
        once[0] = context.getEventBus().subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> {
            if (once[0] != null) once[0].close();
            declaredNames(context).stream()
                    .filter(name -> !before.contains(name))
                    .findFirst()
                    .ifPresent(name -> show(context, owner, name));
        }, true);
    }

    /** Every variable name the open file declares, at any depth — the "before" of {@link #openOnCreated}. */
    public static Set<String> declaredNames(CodeEditorService context) {
        Set<String> names = new java.util.LinkedHashSet<>();
        context.getState().getCompilationUnit().ifPresent(cu -> cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                names.add(fragment.getName().getIdentifier());
                return true;
            }
        }));
        return names;
    }

    // --- The window --------------------------------------------------------------------------------------

    private void open(Window owner) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Edit Variable");

        Button done = new Button("Done");
        done.getStyleClass().add("primary-button");
        done.setDefaultButton(true);
        done.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spacer, done);
        bar.setAlignment(Pos.CENTER_RIGHT);

        problem.getStyleClass().add("variables-rename-error");
        problem.setWrapText(true);
        problem.setVisible(false);
        problem.setManaged(false);
        preview.getStyleClass().add("dialog-code-preview");
        preview.setWrapText(true);
        preview.setMaxWidth(Double.MAX_VALUE);

        VBox root = new VBox(14, nameSection(), labelled("Type", typeRow),
                labelled("Value", valueRow), preview, bar);
        root.setPadding(new Insets(18));

        rebuild();
        EventBus.Subscription live = context.getEventBus().subscribe(
                CoreApplicationEvents.CodeUpdatedEvent.class, e -> rebuild(), true);
        stage.setOnHidden(e -> live.close());

        stage.setScene(ThemedWindows.scene(root, 560, 400));
        stage.setMinWidth(500);
        stage.setMinHeight(340);
        stage.showAndWait();
    }

    private Node nameSection() {
        nameField.setPromptText("what the variable holds — loginButton");
        nameField.setMinWidth(CONTROL_WIDTH);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        nameField.setOnAction(e -> rename());
        nameField.focusedProperty().addListener((obs, was, has) -> {
            if (was && !has) rename();
        });
        return new VBox(4, labelled("Name", nameField), problem);
    }

    /**
     * The whole form, read back off the live compilation unit.
     *
     * <p>Wholesale rather than piecemeal because retyping changes which editor the value needs: a date that
     * becomes a rectangle must lose its calendar, and reinterpreting what was in the old control is how a
     * rectangle once came back holding a date.
     */
    private void rebuild() {
        Optional<Local> found = find();
        if (found.isEmpty() && renamedFrom != null) {
            // The rename was refused (a broken rewrite, a lock): the file still says the old name.
            variableName = renamedFrom;
            found = find();
        }
        renamedFrom = null;
        if (found.isEmpty()) {
            typeRow.getChildren().setAll(hint("This variable is no longer declared here."));
            valueRow.getChildren().clear();
            preview.setText("");
            return;
        }
        Local local = found.get();
        ResolvedType type = ProjectAnalyzer.resolveType(local.statement().getType());
        if (!nameField.isFocused()) nameField.setText(local.name().getIdentifier());
        typeRow.getChildren().setAll(typeControl(local, type));
        valueRow.getChildren().setAll(valueControls(local, type));
        preview.setText(local.statement().toString().trim());
    }

    // --- Type --------------------------------------------------------------------------------------------

    /**
     * The type control: the curated picker when the declared type is one this editor can name, and the
     * declared type as a read-only chip when it is not.
     *
     * <p>The set offered is {@link BotType#declarable()} — everything but {@code void} — rather than
     * {@link BotType#storable()}, which is what the list screen offered and why {@code ImageTemplateGroup},
     * {@code Matches} and {@code CaptureSource} were missing from it. {@code storable()} answers a different
     * question: which types a <em>project variable</em> can hold, i.e. which have a value somebody types into
     * the Parameters dialog. Nothing about that constrains what a variable in the code may be.
     */
    private Node typeControl(Local local, ResolvedType type) {
        Optional<BotType.Choice> known = BotType.Choice.fromSourceName(type.qualifiedName())
                .filter(choice -> choice.type().declarable());
        if (known.isEmpty()) return keptChip(local.statement().getType().toString());

        BotTypePicker picker = new BotTypePicker(BotTypePicker.Purpose.LOCAL_VARIABLE);
        picker.setMinWidth(CONTROL_WIDTH);
        HBox.setHgrow(picker, Priority.ALWAYS);
        prefilling = true;
        picker.setChoice(known.get());
        prefilling = false;
        picker.choiceProperty().addListener((obs, old, now) -> {
            if (prefilling || now == null) return;
            find().ifPresent(fresh -> context.getCodeEditor()
                    .replaceVariableType(fresh.statement(), ResolvedType.named(now.sourceName())));
        });
        return picker;
    }

    /** A type shown but not offered: what the file says, with no control to change it. */
    private static Node keptChip(String sourceName) {
        Label chip = new Label(sourceName);
        chip.getStyleClass().add("kept-type-chip");
        chip.setTooltip(new Tooltip(sourceName
                + " isn't one of the types the editor offers, so it is kept exactly as the Java file writes it."
                + " The name and the value are still yours to change here."));
        return chip;
    }

    // --- Value -------------------------------------------------------------------------------------------

    /**
     * The starting-value controls: the block canvas's own picker for this type, plus the way to something the
     * pickers don't cover.
     *
     * <p>Reaching {@link PickerRegistry} is the point of the {@link ValueSlot} the pickers now take — the list
     * screen could not, so it grew a second, weaker family of widgets, and a template group or a capture source
     * rendered as raw source there while the identical slot on a block had a real editor. Where no picker
     * matches — text, a number, a flag, a character — {@link ValueEditors} supplies the literal editor those
     * five need and nothing more.
     */
    private List<Node> valueControls(Local local, ResolvedType type) {
        ValueSlot slot = ValueSlot.at(() -> find().map(Local::initializer).orElse(null));
        Node picker = PickerRegistry.pickerNodeFor(new PickerContext(context, slot, type, null, null, -1));
        Node editor = picker != null ? picker : literalEditor(local, type);
        return List.of(editor, expressionButton(type));
    }

    /**
     * The literal editor for the five types no picker claims, with the button that writes it.
     *
     * <p>A "Set" button rather than applying as you type, because these are the free-text controls: a number
     * being typed passes through states ("-", "1", "12") that are each a legal rewrite of the file, and
     * committing them would put a dozen entries in the undo history for one edit.
     */
    private Node literalEditor(Local local, ResolvedType type) {
        Optional<BotType> kind = BotType.Choice.fromSourceName(type.qualifiedName())
                .map(BotType.Choice::type)
                .filter(BotType::storable);
        if (kind.isEmpty() || local.initializer() == null) return sourceLabel(local);

        BotType botType = kind.get();
        ValueEditors.Editor editor = ValueEditors.editorFor(botType, readLiteral(local.initializer()),
                ValueEditors.Context.of(context.getConfig()));
        ValueEditors.stretch(editor.node());
        HBox.setHgrow(editor.node(), Priority.ALWAYS);

        Button set = new Button("Set");
        set.setOnAction(e -> {
            VariableWire.Literal literal = VariableWire.literalSource(botType, editor.read().get());
            if (literal == null) return;
            find().map(Local::initializer).ifPresent(node -> context.getCodeEditor()
                    .replaceWithRawExpression(node, literal.source(), literal.importFqn()));
        });
        HBox box = new HBox(6, editor.node(), set);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** What the file says, for a value that isn't a literal anyone could retype — a call, a variable, a sum. */
    private static Node sourceLabel(Local local) {
        Expression initializer = local.initializer();
        Label label = new Label(initializer == null ? "(nothing yet)" : initializer.toString());
        label.getStyleClass().add("dialog-hint");
        label.setWrapText(true);
        HBox.setHgrow(label, Priority.ALWAYS);
        return label;
    }

    /**
     * The way to a value no picker has a control for: the same expression menu the declare block's "+" opens,
     * so a variable can be started from a method call, another variable or a constant here too. It is what
     * makes "a picker for every type" true of the types whose values are <em>found</em> rather than written —
     * a {@code MatchResult}, a {@code Matches}.
     */
    private Node expressionButton(ResolvedType type) {
        Button change = new Button("…");
        change.setTooltip(new Tooltip("Start it from a call, another variable or a constant instead"));
        change.setOnAction(e -> find().ifPresent(local -> {
            Expression initializer = local.initializer();
            ContextMenu menu = ExpressionMenu.create(type, false, context, local.statement(), x -> true,
                    selection -> {
                        if (initializer != null) ExpressionMenu.applySelection(context, initializer, selection);
                        else context.getCodeEditor().setVariableInitializer(local.statement(), selection);
                    });
            menu.show(change, Side.BOTTOM, 0, 0);
        }));
        return change;
    }

    /**
     * The literal an editor is seeded from, for the five types that reach {@link #literalEditor}.
     *
     * <p>Deliberately narrow: an initializer that is a call or an expression has no literal form, and the
     * empty string is the honest seed for "what value would you like instead?". The list screen's reader was
     * far wider — enum constants, constructors, {@code parse("…")} calls — because it had to serve every type;
     * everything it covered beyond these five now has a picker that reads the AST directly.
     */
    private static String readLiteral(Expression expression) {
        return switch (expression) {
            case StringLiteral s -> s.getLiteralValue();
            case NumberLiteral n -> n.getToken();
            case BooleanLiteral b -> Boolean.toString(b.booleanValue());
            case CharacterLiteral c -> String.valueOf(c.charValue());
            case PrefixExpression p when p.getOperator() == PrefixExpression.Operator.MINUS ->
                    "-" + readLiteral(p.getOperand());
            case null, default -> "";
        };
    }

    // --- Rename ------------------------------------------------------------------------------------------

    /**
     * Renames, or says why not — in red under the field, with the field put back to the name the file actually
     * has. The rewrite is {@code renameLocalVariable}, which carries the use sites with it; that is the whole
     * reason renaming moved off the block, where it rewrote the declaration alone and left the uses behind.
     */
    private void rename() {
        Optional<Local> found = find();
        if (found.isEmpty()) return;
        Local local = found.get();
        String current = local.name().getIdentifier();
        String wanted = nameField.getText() == null ? "" : nameField.getText().trim();
        String refusal = VariableNames.problem(wanted, current, declaredIn(local.method()));
        if (refusal != null) {
            problem.setText(refusal);
            problem.setVisible(true);
            problem.setManaged(true);
            nameField.setText(current);
            return;
        }
        problem.setVisible(false);
        problem.setManaged(false);
        if (wanted.equals(current)) return;
        renamedFrom = current;
        variableName = wanted;
        context.getCodeEditor().renameLocalVariable(local.name(), wanted);
    }

    /** Every name {@code method} already binds: its parameters and each local it declares, at any depth. */
    private static Set<String> declaredIn(MethodDeclaration method) {
        Set<String> names = new HashSet<>();
        for (Object parameter : method.parameters()) {
            if (parameter instanceof SingleVariableDeclaration p) names.add(p.getName().getIdentifier());
        }
        if (method.getBody() == null) return names;
        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                names.add(fragment.getName().getIdentifier());
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration declaration) {
                names.add(declaration.getName().getIdentifier());
                return true;
            }
        });
        return names;
    }

    // --- Resolving the variable, every time --------------------------------------------------------------

    /** One declared local: the method it lives in, the statement, and the fragment carrying name and value. */
    private record Local(MethodDeclaration method, VariableDeclarationStatement statement,
                         VariableDeclarationFragment fragment) {

        SimpleName name() {
            return fragment.getName();
        }

        Expression initializer() {
            return fragment.getInitializer();
        }
    }

    /**
     * This dialog's variable in the compilation unit as it stands <em>now</em>.
     *
     * <p>Purely syntactic — no bindings — because the file being edited is routinely one that does not
     * compile, and a screen that emptied itself whenever the project had an error would be at its least useful
     * exactly when it is most wanted.
     */
    private Optional<Local> find() {
        Optional<CompilationUnit> cu = context.getState().getCompilationUnit();
        if (cu.isEmpty()) return Optional.empty();
        Local[] found = new Local[1];
        cu.get().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() == null) return false;
                if (methodName != null && !methodName.equals(method.getName().getIdentifier())) return false;
                method.getBody().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(VariableDeclarationStatement statement) {
                        for (Object fragment : statement.fragments()) {
                            if (found[0] == null && fragment instanceof VariableDeclarationFragment f
                                    && variableName.equals(f.getName().getIdentifier())) {
                                found[0] = new Local(method, statement, f);
                            }
                        }
                        return true;
                    }
                });
                return false;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    // --- Small helpers -----------------------------------------------------------------------------------

    /** Wide enough that a type name reads in full — the elision to {@code …} is what this screen replaces. */
    private static final double CONTROL_WIDTH = 240;

    private static HBox labelled(String text, Region control) {
        Label label = new Label(text);
        label.setMinWidth(70);
        control.setMinWidth(CONTROL_WIDTH);
        HBox.setHgrow(control, Priority.ALWAYS);
        HBox row = new HBox(10, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint");
        return label;
    }
}
