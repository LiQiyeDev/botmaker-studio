package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source an Add Function dialog produces.
 *
 * <p>Two things the old {@code addMethodToClass} could not do, both asserted here: a signature with parameters
 * at all, and a signature whose types resolve. The second is why this path takes an {@code EditContext}.
 *
 * <p><b>The fixtures were SDK types until 2026-09-01</b> — {@code Point}, {@code MatchResult},
 * {@code Matches} — and the import assertion beside them was the point of the test: a {@code Point} parameter
 * written into a file with no {@code import com.botmaker.sdk.api.geometry.Point} is a red file. That is no
 * longer how it works and the test says so instead. {@code BotType} offers no SDK types of its own now; every
 * non-primitive it offers is written <b>fully qualified</b>, which is what makes the import unnecessary rather
 * than merely missing. {@code java.util.List} is the one import still added, because {@code List<T>} is
 * written with a simple name.
 */
class AddFunctionEditTest {

    private static final String SOURCE = """
            package test;

            public class Subject {
                public void first() {
                }
            }
            """;

    private ProjectState state;
    private CodeEditor editor;
    private String lastCode;

    @BeforeEach
    void setUp() {
        state = new ProjectState();
        Path path = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, SOURCE));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        EventBus bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode = e.newCode());

        state.setCompilationUnit(com.botmaker.studio.parser.helpers.SourceParser.parse(SOURCE));
        editor = new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state));
    }

    private TypeDeclaration subject() {
        return (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
    }

    @Test
    void aVoidFunctionWithNoParametersIsAnEmptyMethod() {
        editor.addFunctionToClass(subject(),
                new FunctionDraft("goHome", BotType.Choice.of(BotType.NOTHING), List.of()), 1);

        assertNotNull(lastCode, "adding a function should publish a code update");
        assertTrue(lastCode.contains("void goHome()"), lastCode);
    }

    @Test
    void parametersAreWrittenAndAQualifiedTypeNeedsNoImport() {
        editor.addFunctionToClass(subject(), new FunctionDraft("dueOn", BotType.Choice.of(BotType.YES_NO),
                List.of(new FunctionDraft.Parameter("when", BotType.Choice.of(BotType.DATE)),
                        new FunctionDraft.Parameter("tries", BotType.Choice.of(BotType.WHOLE_NUMBER)))), 1);

        assertTrue(lastCode.contains("boolean dueOn(java.time.LocalDate when, int tries)"), lastCode);
        assertTrue(!lastCode.contains("import java.time.LocalDate;"),
                "a qualified parameter type resolves on its own, so nothing is imported for it:\n" + lastCode);
        // A non-void function needs a return to compile; the seed is the type's own default, not null.
        assertTrue(lastCode.contains("return false;"), lastCode);
    }

    @Test
    void aListReturnTypeIsParameterisedAndSeededWithAnEmptyList() {
        editor.addFunctionToClass(subject(),
                new FunctionDraft("allDue", BotType.Choice.listOf(BotType.DATE), List.of()), 1);

        assertTrue(lastCode.contains("List<java.time.LocalDate> allDue()"), lastCode);
        // List is written with a simple name, so it is the one thing here that still needs importing.
        assertTrue(lastCode.contains("import java.util.List;"), lastCode);
        assertTrue(lastCode.contains("return List.of();"), lastCode);
    }

    @Test
    void aReturnTypeIsSeededFromItsOwnDefaultNotWithNull() {
        // The reason BotType carries a default per type: "gives back a Duration" compiles before it is filled
        // in. For a plugin-contributed type that default is the plugin's own SourceSeed expression.
        editor.addFunctionToClass(subject(),
                new FunctionDraft("howLong", BotType.Choice.of(BotType.DURATION), List.of()), 1);

        assertTrue(lastCode.contains("return java.time.Duration.ofSeconds(0);"), lastCode);
    }
}
