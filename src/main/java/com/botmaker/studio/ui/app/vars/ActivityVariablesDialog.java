package com.botmaker.studio.ui.app.vars;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.project.activity.VariableWire;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.app.params.ValueEditors;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the open activity declares, in one list: a row per local variable, with its name, its type and
 * its starting value, plus add and delete.
 *
 * <p>The editor already lets you change all three — on the block itself, one block at a time, wherever it
 * happens to sit in the program. What it does not do is <em>answer the question</em>: an activity of any size
 * gives no way to see what it declares without reading it end to end, and no way to rename something without
 * finding every place it is used. This is that view, and it is the reason the rename here goes through
 * {@link com.botmaker.studio.parser.CodeEditor#renameLocalVariable} — the use sites come with it — while the
 * name chip on a freshly dropped block, which by construction has no use sites, does not need to.
 *
 * <p><b>Not modal.</b> Every edit rewrites the file, which re-parses it and rebuilds the canvas behind the
 * dialog; blocking that out of view would make the screen look like it had done nothing. The rows are rebuilt
 * from the fresh AST on each {@link CoreApplicationEvents.CodeUpdatedEvent} for the same reason a stale
 * {@code ASTNode} cannot be edited twice.
 */
public final class ActivityVariablesDialog {

    private final CodeEditorService context;
    private final VBox rows = new VBox(4);

    private ActivityVariablesDialog(CodeEditorService context) {
        this.context = context;
    }

    /** Opens the screen over {@code owner} for whatever file the editor currently has open. */
    public static void show(CodeEditorService context, Window owner) {
        new ActivityVariablesDialog(context).open(owner);
    }

    private void open(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("Variables");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        ScrollPane scroller = new ScrollPane(rows);
        scroller.setFitToWidth(true);
        scroller.setPrefViewportHeight(320);
        rows.setPadding(new Insets(4, 4, 8, 4));

        VBox root = new VBox(8, scroller, new Separator(), addBar());
        root.setPadding(new Insets(12));
        root.setPrefWidth(560);
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        rebuild();
        EventBus.Subscription live = context.getEventBus().subscribe(
                CoreApplicationEvents.CodeUpdatedEvent.class, e -> rebuild(), true);
        dialog.setOnHidden(e -> live.close());
        dialog.show();
    }

    // --- The list ----------------------------------------------------------------------------------------

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

    private void rebuild() {
        rows.getChildren().clear();
        Map<MethodDeclaration, List<Local>> byMethod = collect();
        if (byMethod.isEmpty()) {
            rows.getChildren().add(hint("This activity declares no variables yet."));
            return;
        }
        byMethod.forEach((method, locals) -> {
            Label heading = new Label(method.getName().getIdentifier() + "()");
            heading.getStyleClass().add("dialog-subheading");
            rows.getChildren().add(heading);
            locals.forEach(local -> rows.getChildren().add(row(local)));
        });
    }

    /**
     * Every local declared in the open file, grouped by the method that declares it, in source order.
     *
     * <p>Purely syntactic — no bindings — because the file being edited is routinely one that does not
     * compile, and a screen that emptied itself whenever the project had an error would be at its least
     * useful exactly when it is most wanted.
     */
    private Map<MethodDeclaration, List<Local>> collect() {
        Map<MethodDeclaration, List<Local>> byMethod = new LinkedHashMap<>();
        Optional<CompilationUnit> cu = context.getState().getCompilationUnit();
        if (cu.isEmpty()) return byMethod;
        cu.get().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() == null) return false;
                List<Local> found = new ArrayList<>();
                method.getBody().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(VariableDeclarationStatement statement) {
                        for (Object fragment : statement.fragments()) {
                            if (fragment instanceof VariableDeclarationFragment f) {
                                found.add(new Local(method, statement, f));
                            }
                        }
                        return true;
                    }
                });
                if (!found.isEmpty()) byMethod.put(method, found);
                return false;
            }
        });
        return byMethod;
    }

    // --- One row -----------------------------------------------------------------------------------------

    private Node row(Local local) {
        ResolvedType type = com.botmaker.studio.suggestions.ProjectAnalyzer.resolveType(local.statement().getType());

        TextField name = new TextField(local.name().getIdentifier());
        name.setPrefColumnCount(12);
        name.setOnAction(e -> rename(local, name.getText()));
        name.focusedProperty().addListener((obs, was, has) -> {
            if (was && !has) rename(local, name.getText());
        });

        // A plain Button, not a MenuButton: the type menu is built fresh at click time (it can offer "Other
        // type…" over whatever the project index holds now), and a MenuButton would fight it with a popup of
        // its own.
        Button typeButton = new Button(type.simpleName() + "  ▾");
        typeButton.getStyleClass().add("variables-type");
        typeButton.setOnAction(e -> ExpressionMenu.showBotTypeMenu(typeButton, type, context,
                local.statement(),
                picked -> context.getCodeEditor().replaceVariableType(local.statement(), picked)));

        HBox row = new HBox(8, typeButton, name, new Label("="), valueEditor(local, type));
        row.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(spacer, deleteButton(local));
        row.getStyleClass().add("variables-row");
        return row;
    }

    /**
     * The starting-value control: the same {@link ValueEditors} widget the parameters and user views use, and
     * an Apply that writes the value back as a Java literal via {@link VariableWire#literalSource}.
     *
     * <p>A type outside the {@link BotType} whitelist — {@code Steam s = …}, a call into a user library —
     * shows its source instead. This screen is a list of what an activity declares, not a second block editor:
     * an expression that isn't a value somebody could write down is edited on the block, where the drop
     * targets and the argument pickers are.
     */
    private Node valueEditor(Local local, ResolvedType type) {
        Expression initializer = local.initializer();
        Optional<BotType> botType = BotType.Choice.fromSourceName(type.qualifiedName())
                .map(BotType.Choice::type)
                .filter(BotType::storable);
        if (initializer == null || botType.isEmpty()) {
            Label source = new Label(initializer == null ? "(nothing)" : initializer.toString());
            source.getStyleClass().add("dialog-hint");
            return source;
        }
        BotType kind = botType.get();
        ValueEditors.Editor editor = ValueEditors.editorFor(kind, seedWire(kind, initializer),
                ValueEditors.Context.of(context.getConfig()));
        Button apply = new Button("Set");
        apply.setOnAction(e -> {
            VariableWire.Literal literal = VariableWire.literalSource(kind, editor.read().get());
            if (literal == null) return;
            context.getCodeEditor().replaceWithRawExpression(initializer, literal.source(), literal.importFqn());
        });
        HBox box = new HBox(6, editor.node(), apply);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Button deleteButton(Local local) {
        Button delete = new Button("✕");
        delete.getStyleClass().add("variables-delete");
        delete.setTooltip(new javafx.scene.control.Tooltip("Delete this variable"));
        delete.setOnAction(e -> {
            List<SimpleName> uses = AstRewriteHelper.referencesWithin(local.method(), local.name());
            if (!uses.isEmpty()) {
                refuse(local, uses);
                return;
            }
            context.getCodeEditor().deleteStatement(local.statement());
        });
        return delete;
    }

    /**
     * Refuses a delete that would break the code, and says where. The list of lines is the point: "it is used"
     * leaves the user to search for the uses that a screen listing variables plainly already knows.
     */
    private void refuse(Local local, List<SimpleName> uses) {
        CompilationUnit cu = (CompilationUnit) local.name().getRoot();
        StringBuilder where = new StringBuilder();
        for (SimpleName use : uses) {
            where.append("\n  • line ").append(cu.getLineNumber(use.getStartPosition()));
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        ThemedWindows.apply(alert);
        alert.setTitle("Still in use");
        alert.setHeaderText("\"%s\" is used %d time%s".formatted(
                local.name().getIdentifier(), uses.size(), uses.size() == 1 ? "" : "s"));
        alert.setContentText("Remove or change those first, then delete it here." + where);
        alert.showAndWait();
    }

    private void rename(Local local, String newName) {
        String wanted = newName == null ? "" : newName.trim();
        if (wanted.isEmpty() || wanted.equals(local.name().getIdentifier())) return;
        context.getCodeEditor().renameLocalVariable(local.name(), wanted);
    }

    // --- Add ---------------------------------------------------------------------------------------------

    /**
     * The bar that adds one. It stays at the bottom of the dialog rather than at the end of the list, so it is
     * in the same place whether the activity declares two variables or forty.
     */
    private Node addBar() {
        MenuButton add = new MenuButton("Add a variable");
        for (BotType.Group group : BotType.Group.values()) {
            List<MenuItem> items = BotType.in(group).stream()
                    .filter(BotType::storable)
                    .map(type -> {
                        MenuItem item = new MenuItem(type.label());
                        item.setOnAction(e -> addVariable(type));
                        return item;
                    })
                    .toList();
            if (items.isEmpty()) continue;
            javafx.scene.control.Menu sub = new javafx.scene.control.Menu(group.label());
            sub.getItems().addAll(items);
            add.getItems().add(sub);
        }
        HBox bar = new HBox(8, add, hint("Added at the top of the method you are editing."));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /**
     * Adds a declaration of {@code type} to the method holding the block currently selected in the editor —
     * or, when nothing is selected, to the first method that has a body, which in a scaffolded activity is the
     * one the user is meant to fill in. There is no cursor in this dialog to mean "here".
     */
    private void addVariable(BotType type) {
        MethodDeclaration target = selectedMethod();
        if (target == null) target = firstMethodWithBody();
        if (target == null) return;
        // The catalog's own entry, not a copy of it: added this way, a variable added here is the same
        // statement dropping the palette block would have produced — including the name, which
        // StatementFactory makes unique against everything already declared in the method.
        context.getCodeEditor().addLocalVariable(target, BlockCatalog.declareBlockFor(type));
    }

    /** The method holding the block the editor has selected, or null when nothing is selected. */
    private MethodDeclaration selectedMethod() {
        return context.getState().getHighlightedBlock()
                .map(com.botmaker.studio.core.CodeBlock::getAstNode)
                .map(AstRewriteHelper::enclosingMethod)
                .filter(method -> method.getBody() != null)
                .orElse(null);
    }

    private MethodDeclaration firstMethodWithBody() {
        Optional<CompilationUnit> cu = context.getState().getCompilationUnit();
        if (cu.isEmpty()) return null;
        MethodDeclaration[] found = new MethodDeclaration[1];
        cu.get().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (found[0] == null && method.getBody() != null) found[0] = method;
                return false;
            }
        });
        return found[0];
    }


    // --- Reading a value back ----------------------------------------------------------------------------

    /**
     * The wire text to seed the editor with, read back off whatever the initializer currently is.
     *
     * <p>Deliberately narrow, and deliberately here rather than in {@code VariableWire}: it recognises the
     * shapes {@link VariableWire#literalSource} writes and the shapes the palette seeds, and answers the
     * type's default for anything else. It is not a general Java-to-wire reader and must not be mistaken for
     * one — an initializer that is a call, a variable or an arithmetic expression has no wire form at all, and
     * seeding the editor with a default is the honest answer to "what value would you like instead?".
     */
    private static String seedWire(BotType type, Expression initializer) {
        String read = readLiteral(initializer);
        if (read != null) return read;
        List<String> fallback = VariableWire.defaultWire(BotType.Choice.of(type));
        return fallback.isEmpty() ? "" : fallback.getFirst();
    }

    private static String readLiteral(Expression expression) {
        return switch (expression) {
            case StringLiteral s -> s.getLiteralValue();
            case NumberLiteral n -> n.getToken();
            case BooleanLiteral b -> Boolean.toString(b.booleanValue());
            case CharacterLiteral c -> String.valueOf(c.charValue());
            case PrefixExpression p when p.getOperator() == PrefixExpression.Operator.MINUS ->
                    "-" + readLiteral(p.getOperand());
            // `Direction.NORTH`, `Key.A` — the constant is the wire text.
            case Name n -> lastSegment(n.getFullyQualifiedName());
            // `new Point(3, 4)`, `new Precision(12.0, 4, 0)` — the arguments, comma-joined, are the wire text.
            case ClassInstanceCreation c -> joinArguments(c.arguments());
            // `LocalDate.parse("2026-08-19")`, `Color.decode("#FF0000")` — the string they were given.
            case MethodInvocation m when m.arguments().size() == 1
                    && m.arguments().getFirst() instanceof StringLiteral s -> s.getLiteralValue();
            case null, default -> null;
        };
    }

    private static String joinArguments(List<?> arguments) {
        StringBuilder joined = new StringBuilder();
        for (Object argument : arguments) {
            String read = argument instanceof Expression e ? readLiteral(e) : null;
            if (read == null) return null;
            if (!joined.isEmpty()) joined.append(',');
            joined.append(read);
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    private static String lastSegment(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint");
        return label;
    }
}
