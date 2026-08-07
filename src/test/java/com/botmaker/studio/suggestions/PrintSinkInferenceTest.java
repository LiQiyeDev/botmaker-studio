package com.botmaker.studio.suggestions;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An argument slot in a print call constrains nothing.
 *
 * <p>The reported symptom was that dropping an SDK call or a function call <em>inside a Print block</em>
 * offered no methods at all. The chain: the Print block emits {@code BotMaker.print(…)};
 * {@link ProjectAnalyzer#inferExpectedType} resolved the slot to the declared parameter type; and
 * {@code MethodInvocationBlock.populateMethodList} filters the dropdown to methods whose return type is
 * compatible with it — so a class whose methods return {@code int} or {@code void} produced an empty list.
 *
 * <p>The rule is now structural: a parameter declared as {@code Object} accepts every reference type, so an
 * argument in that position is unconstrained. {@code System.out.print*} is the one sink that isn't declared
 * that way ({@code PrintStream} overloads per type), so it keeps an explicit clause — asserted below so the
 * path that already worked stays working.
 */
class PrintSinkInferenceTest {

    /** A stand-in for the SDK's {@code BotMaker.print(Object)}, so the test needs no SDK jar to resolve. */
    private static final String SRC = """
            package com.example;
            public class Demo {
                static class Sink {
                    static void print(Object value) {}
                    static void named(String value) {}
                }
                public void run() {
                    Sink.print("here");
                    Sink.named("here");
                    System.out.println("here");
                }
            }
            """;

    private static CompilationUnit parse() {
        return ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(),
                SRC,
                Paths.get("src", "main", "java").toAbsolutePath(),
                "Demo.java");
    }

    /** The first argument of the call spelled {@code call} — the slot a dropped block would land in. */
    private static Expression argumentOf(String call) {
        Expression[] found = {null};
        parse().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (node.toString().startsWith(call) && found[0] == null) {
                    found[0] = (Expression) node.arguments().getFirst();
                }
                return true;
            }
        });
        assertNotNull(found[0], "fixture must contain a call to " + call);
        return found[0];
    }

    @Test
    void anObjectParameterLeavesTheSlotUnconstrained() {
        assertTrue(ProjectAnalyzer.inferExpectedType(argumentOf("Sink.print")).isUnknown(),
                "an Object-typed parameter accepts anything, so the method dropdown must not be filtered");
    }

    @Test
    void systemOutPrintlnIsStillRecognised() {
        assertTrue(ProjectAnalyzer.inferExpectedType(argumentOf("System.out.println")).isUnknown(),
                "PrintStream.println(String) is a sink despite its declared parameter type");
    }

    /** The rule is about {@code Object}, not about the word "print" — an ordinary parameter still constrains. */
    @Test
    void anOrdinaryParameterStillResolvesToItsDeclaredType() {
        ResolvedType expected = ProjectAnalyzer.inferExpectedType(argumentOf("Sink.named"));
        assertEquals("String", expected.simpleName(),
                "a String parameter must still narrow the slot: " + expected);
    }
}
