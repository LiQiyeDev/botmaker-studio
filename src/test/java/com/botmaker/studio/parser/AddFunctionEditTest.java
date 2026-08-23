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
 * at all, and the imports the types in it need. The second is why this path takes an {@code EditContext} — a
 * {@code Point} parameter written into a file with no {@code import com.botmaker.sdk.api.geometry.Point} is a red file,
 * and the plain rewriter had no way to add one.
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
    void parametersAreWrittenAndTheirTypesImported() {
        editor.addFunctionToClass(subject(), new FunctionDraft("clickAt", BotType.Choice.of(BotType.YES_NO),
                List.of(new FunctionDraft.Parameter("where", BotType.Choice.of(BotType.POINT)),
                        new FunctionDraft.Parameter("tries", BotType.Choice.of(BotType.WHOLE_NUMBER)))), 1);

        assertTrue(lastCode.contains("boolean clickAt(Point where, int tries)"), lastCode);
        assertTrue(lastCode.contains("import com.botmaker.sdk.api.geometry.Point;"),
                "a Point parameter has to bring its import:\n" + lastCode);
        // A non-void function needs a return to compile; the seed is the type's own default, not null.
        assertTrue(lastCode.contains("return false;"), lastCode);
    }

    @Test
    void aListReturnTypeIsParameterisedAndSeededWithAnEmptyList() {
        editor.addFunctionToClass(subject(),
                new FunctionDraft("allMatches", BotType.Choice.listOf(BotType.MATCH_RESULT), List.of()), 1);

        assertTrue(lastCode.contains("List<MatchResult> allMatches()"), lastCode);
        assertTrue(lastCode.contains("import java.util.List;"), lastCode);
        assertTrue(lastCode.contains("import com.botmaker.sdk.api.vision.MatchResult;"), lastCode);
        assertTrue(lastCode.contains("return List.of();"), lastCode);
    }

    @Test
    void anSdkReturnTypeIsSeededFromTheCatalogueNotWithNull() {
        // The reason BotType carries a default per type: "gives back a Matches" compiles before it is filled in.
        editor.addFunctionToClass(subject(),
                new FunctionDraft("current", BotType.Choice.of(BotType.MATCHES), List.of()), 1);

        assertTrue(lastCode.contains("return Matches.none();"), lastCode);
    }
}
