package com.botmaker.studio.ui.app.vars;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The rename guard. It matters more than its size suggests: the declare block no longer renames in place, so
 * this is the only check standing between a typed name and {@code renameLocalVariable}, which rewrites every
 * use site of the variable and would rewrite them to {@code class} as happily as to {@code target}.
 */
class VariableNamesTest {

    private static final Set<String> DECLARED = Set.of("target", "attempts", "found");

    @Test
    void aValidRenameIsAccepted() {
        assertNull(VariableNames.problem("nextTarget", "target", DECLARED));
        assertNull(VariableNames.problem("_hidden", "target", DECLARED));
        assertNull(VariableNames.problem("slot2", "target", DECLARED));
    }

    @Test
    void renamingSomethingToItsOwnNameIsNotADuplicate() {
        // The field commits on focus loss as well as on Enter, so "clicked into it and back out" is the
        // commonest path through here and must not be an error.
        assertNull(VariableNames.problem("target", "target", DECLARED));
        assertNull(VariableNames.problem("  target  ", "target", DECLARED));
    }

    @Test
    void aNameAlreadyDeclaredInTheSameMethodIsRefused() {
        String problem = VariableNames.problem("attempts", "target", DECLARED);
        assertNotNull(problem);
        assertEquals(true, problem.contains("attempts"), problem);
    }

    @Test
    void aKeywordIsRefused() {
        for (String keyword : List.of("class", "new", "int", "if", "true", "null", "_")) {
            assertNotNull(VariableNames.problem(keyword, "target", DECLARED), keyword);
        }
    }

    @Test
    void somethingThatIsNotAnIdentifierIsRefused() {
        assertNotNull(VariableNames.problem("", "target", DECLARED));
        assertNotNull(VariableNames.problem("   ", "target", DECLARED));
        assertNotNull(VariableNames.problem("2fast", "target", DECLARED));
        assertNotNull(VariableNames.problem("has space", "target", DECLARED));
        assertNotNull(VariableNames.problem("no-dash", "target", DECLARED));
        assertNotNull(VariableNames.problem("$generated", "target", DECLARED),
                "$ is legal Java and reserved for generated code — not a name to hand a person");
    }

    @Test
    void aNullDeclaredSetIsAnEmptyOneRatherThanAThrow() {
        assertNull(VariableNames.problem("anything", "target", null));
    }
}
