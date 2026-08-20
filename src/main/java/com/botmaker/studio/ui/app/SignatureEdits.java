package com.botmaker.studio.ui.app;

import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.parser.refactor.MethodReferences;
import com.botmaker.studio.parser.refactor.SignatureMigration;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.control.Alert;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The one way a signature changes: scan the project, refuse or preview, then write.
 *
 * <p>This is the ✎ dialog's own path, lifted out of {@code MethodDeclarationBlock} so that the controls sitting
 * next to it on the same header take it too. They did not. The dialog scanned the project, showed what would
 * happen to which file and let the user cancel; the {@code ×} beside it deleted the function outright, and the
 * chips on a constructor's parameter row retyped and removed inputs with a bare rewrite of the declaration.
 * Same block, same signature, two entirely different levels of care — which is why deleting a called function
 * broke the project with nothing on screen to warn about it.
 *
 * <h2>Three answers, and only three</h2>
 *
 * <ul>
 *   <li><b>Refused</b> — a file that does not parse, a call that cannot be judged, or a delete while something
 *       still calls it. Nothing is written and the reason names the file.</li>
 *   <li><b>Just saved</b> — nothing anywhere calls it, so there is nothing to preview and no window appears.</li>
 *   <li><b>Previewed</b> — anything else. Cancel leaves even the declaration untouched.</li>
 * </ul>
 *
 * <p><b>Why it lives under {@code ui/app} and not {@code parser/refactor}.</b> It shows dialogs. The refactor
 * package is deliberately free of JavaFX — that is what lets {@link SignatureMigration} be tested headlessly,
 * and the rules are worth more there than this sequencing is. What the parser owns is the answer; what this
 * owns is asking the user about it.
 */
public final class SignatureEdits {

    private SignatureEdits() {}

    /**
     * Carries an edited signature to every call in the project, asking first.
     *
     * @param before the signature as the file writes it today
     * @param after  what the user wants it to be
     */
    public static void apply(CodeEditorService context, Window owner, MethodDeclaration method,
                             FunctionDraft before, FunctionDraft after) {
        MethodReferences.Result references = MethodReferences.find(context.getState(), method);
        if (references.isRefusal()) {
            explainRefused(owner, method, references.refusal());
            return;
        }
        SignatureMigration.Plan plan = SignatureMigration.of(before, after, method, references.calls());
        if (plan.isEmpty()) {
            context.getCodeEditor().applyFunctionSignature(method, after);
            return;
        }
        if (!SignatureMigrationDialog.confirm(owner, method.getName().getIdentifier(), plan)) return;
        context.getCodeEditor().applyFunctionSignature(method, after, plan);
    }

    /**
     * Changes one thing about a signature and applies it through {@link #apply}.
     *
     * <p>This is what a header control is: a gesture that owns a single field. Building the draft with
     * {@link MethodSignatures#draftOf} and editing that one field means the rest of the signature — and in
     * particular every parameter's {@link FunctionDraft.Parameter#origin() origin} — travels through unchanged,
     * so a retype stays a retype rather than reading as "everything was replaced".
     */
    public static void edit(CodeEditorService context, Window owner, MethodDeclaration method,
                            UnaryOperator<FunctionDraft> change) {
        Optional<FunctionDraft> current = MethodSignatures.draftOf(method);
        if (current.isEmpty()) {
            explainUneditable(owner, method);
            return;
        }
        apply(context, owner, method, current.get(), change.apply(current.get()));
    }

    /**
     * Deletes the function — unless something calls it, in which case it refuses and says where.
     *
     * <p>The one gesture whose answer is "no" rather than a preview. Every other signature change has an honest
     * edit to make at a call site; a call to a function that no longer exists has none, and offering to
     * "migrate" it would mean inventing something to put there. So the calls come out first, by hand, and this
     * says how many there are and which files they are in.
     */
    public static void delete(CodeEditorService context, Window owner, MethodDeclaration method) {
        MethodReferences.Result references = MethodReferences.find(context.getState(), method);
        if (references.isRefusal()) {
            explainRefused(owner, method, references.refusal());
            return;
        }
        if (!references.calls().isEmpty()) {
            explainRefused(owner, method, stillUsed(method, references));
            return;
        }
        context.getCodeEditor().deleteMethod(method);
    }

    /** Why a delete cannot happen yet: the count, and where to go and undo it. */
    private static String stillUsed(MethodDeclaration method, MethodReferences.Result references) {
        int count = references.calls().size();
        String what = method.isConstructor() ? "It is still built" : "It is still called";
        return what + " " + count + (count == 1 ? " time" : " times") + ", in "
                + String.join(", ", references.fileNames())
                + ".\n\nRemove " + (count == 1 ? "that use" : "those uses") + " first and this will delete "
                + "cleanly. Nothing has changed.";
    }

    /** Why the change could not be made, naming the file that has to be fixed first. */
    public static void explainRefused(Window owner, MethodDeclaration method, String because) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("This change can't be made yet");
        alert.setHeaderText(method.getName().getIdentifier() + " wasn't changed");
        alert.setContentText(because);
        alert.showAndWait();
    }

    /** Says which part of the signature the editor cannot describe, and where to change it instead. */
    public static void explainUneditable(Window owner, MethodDeclaration method) {
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
}
