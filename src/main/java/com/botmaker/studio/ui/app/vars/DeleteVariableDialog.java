package com.botmaker.studio.ui.app.vars;

import com.botmaker.studio.parser.UseFix;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What happens to a variable's uses when the variable goes — asked once, applied to all.
 *
 * <p>The ✕ on a declare block used to remove the line and nothing else, so deleting {@code int attempts = 0}
 * that three lines later read {@code attempts} produced a file that does not compile. This screen is the
 * missing question, and it is only shown when there is one to ask: an unused variable still deletes with one
 * press and no dialog.
 *
 * <p>Two answers, because there are only two that don't invent work: put the type's default where each use was,
 * or point them at another variable of the same type. "Create a new variable" was considered and left out — it
 * is the delete you were about to do, undone, with a rename on top. Cancel is the third button, not a third
 * answer.
 *
 * <h2>Syntactic, like the screen next door</h2>
 *
 * <p>The candidate list is gathered by walking the method, not from {@code VariableScopeVisitor}: that reads
 * {@code IVariableBinding}s, which a file mid-edit routinely doesn't have, and a delete dialog whose "point
 * them somewhere" option empties itself whenever the project has an error would be useless exactly when it is
 * most wanted. Same reasoning as {@link EditVariableDialog}, which resolves its variable the same way.
 */
public final class DeleteVariableDialog {

    private DeleteVariableDialog() {}

    /**
     * Deletes {@code decl}, asking about its uses first when it has any.
     *
     * @param decl the declaration behind the ✕ that was pressed
     */
    public static void confirmAndDelete(CodeEditorService context, Window owner, VariableDeclarationStatement decl) {
        if (context == null || decl == null) return;
        if (decl.fragments().size() != 1
                || !(decl.fragments().getFirst() instanceof VariableDeclarationFragment fragment)) {
            context.getCodeEditor().deleteStatement(decl);
            return;
        }
        MethodDeclaration method = AstRewriteHelper.enclosingMethod(decl);
        List<SimpleName> uses = AstRewriteHelper.referencesWithin(method, fragment.getName());
        if (uses.isEmpty()) {
            context.getCodeEditor().deleteStatement(decl);
            return;
        }
        ask(context, owner, decl, fragment, method, uses)
                .ifPresent(fix -> context.getCodeEditor().deleteVariable(decl, fix));
    }

    // --- The window --------------------------------------------------------------------------------------

    private static java.util.Optional<UseFix> ask(CodeEditorService context, Window owner,
                                                  VariableDeclarationStatement decl,
                                                  VariableDeclarationFragment fragment,
                                                  MethodDeclaration method, List<SimpleName> uses) {
        String name = fragment.getName().getIdentifier();
        ResolvedType type = ProjectAnalyzer.resolveType(decl.getType());
        String defaultValue = defaultValueSource(context, decl, type);
        List<String> candidates = sameTypedInScope(method, decl, fragment, uses);

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Delete Variable");

        Label headline = new Label(useCountLine(name, uses.size(), method));
        headline.getStyleClass().add("dialog-headline");
        headline.setWrapText(true);

        ToggleGroup choice = new ToggleGroup();
        RadioButton useDefault = new RadioButton("Replace those uses with a default value — " + defaultValue);
        useDefault.setToggleGroup(choice);
        useDefault.setSelected(true);

        RadioButton usePointer = new RadioButton("Point them at another variable");
        usePointer.setToggleGroup(choice);
        ComboBox<String> other = new ComboBox<>();
        other.getItems().setAll(candidates);
        if (!candidates.isEmpty()) other.getSelectionModel().selectFirst();
        // Disabled rather than absent when nothing qualifies: the screen keeps its shape, and "there is no other
        // variable of this type here" is itself the answer to why the option can't be taken.
        usePointer.setDisable(candidates.isEmpty());
        other.setDisable(true);
        other.disableProperty().bind(usePointer.selectedProperty().not());
        HBox pointerRow = new HBox(10, usePointer, other);
        pointerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(other, Priority.ALWAYS);

        UseFix[] result = new UseFix[1];
        Button delete = new Button("Delete");
        delete.getStyleClass().add("primary-button");
        delete.setDefaultButton(true);
        delete.setOnAction(e -> {
            result[0] = usePointer.isSelected() && other.getValue() != null
                    ? new UseFix.Rename(other.getValue())
                    : UseFix.DEFAULT;
            stage.close();
        });
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spacer, cancel, delete);
        bar.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14, headline, useDefault, pointerRow, bar);
        root.setPadding(new Insets(18));
        stage.setScene(ThemedWindows.scene(root, 520, 240));
        stage.setMinWidth(460);
        stage.setMinHeight(220);
        stage.showAndWait();
        return java.util.Optional.ofNullable(result[0]);
    }

    private static String useCountLine(String name, int count, MethodDeclaration method) {
        String where = method == null ? "" : " in " + method.getName().getIdentifier() + "()";
        return "\"" + name + "\" is used " + count + (count == 1 ? " time" : " times") + where + ".";
    }

    /**
     * The default as it will be written, so the option can show it rather than describe it.
     *
     * <p>Built with the same factory the rewrite uses, on the live AST but with no rewriter of its own — this is
     * a node made to be printed and thrown away, never applied.
     */
    private static String defaultValueSource(CodeEditorService context, VariableDeclarationStatement decl,
                                             ResolvedType type) {
        Expression value = InitializerFactory.createDefaultInitializer(decl.getAST(), type,
                context.getState().getCompilationUnit().orElse(null), context.getState());
        return value == null ? "null" : value.toString();
    }

    /**
     * Every other variable of the same declared type that {@code method} binds before the first use — the
     * parameters and the locals, matched on the type <em>as written</em>.
     *
     * <p>Textual type matching is the honest test without bindings: {@code List<Point>} and {@code List<Point>}
     * are the same type here, and anything that only a compiler could tell apart is not something to offer as a
     * silent substitution anyway.
     */
    private static List<String> sameTypedInScope(MethodDeclaration method, VariableDeclarationStatement decl,
                                                 VariableDeclarationFragment fragment, List<SimpleName> uses) {
        if (method == null) return List.of();
        String wanted = decl.getType().toString();
        String excluded = fragment.getName().getIdentifier();
        int firstUse = uses.stream().mapToInt(SimpleName::getStartPosition).min().orElse(Integer.MAX_VALUE);
        Set<String> names = new LinkedHashSet<>();

        for (Object parameter : method.parameters()) {
            if (parameter instanceof SingleVariableDeclaration p && p.getType().toString().equals(wanted)
                    && !p.getName().getIdentifier().equals(excluded)) {
                names.add(p.getName().getIdentifier());
            }
        }
        if (method.getBody() != null) {
            method.getBody().accept(new ASTVisitor() {
                @Override
                public boolean visit(VariableDeclarationStatement statement) {
                    if (statement == decl || !statement.getType().toString().equals(wanted)) return true;
                    for (Object each : statement.fragments()) {
                        if (each instanceof VariableDeclarationFragment f
                                && !f.getName().getIdentifier().equals(excluded)
                                && f.getStartPosition() < firstUse) {
                            names.add(f.getName().getIdentifier());
                        }
                    }
                    return true;
                }
            });
        }
        return new ArrayList<>(names);
    }
}
