package com.botmaker.studio.services;

import com.botmaker.sdk.authoring.TagCatalog;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.ValueWire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Parameters dialog's rail, which is a decision rather than a widget: which buckets exist, what each holds,
 * and — the one that matters — that no variable can end up in none of them.
 */
class VariableRailModelTest {

    private static TagCatalog catalog() {
        return TagCatalog.of(List.of("Mining", "Fishing"), List.of("Timing"));
    }

    /** Keyed by the persisted id, which is what a type <em>is</em> since the vocabulary opened. */
    private static ActivityVariable variable(String name, String typeId, String tag) {
        return ActivityVariable.create(name, ValueWire.one(typeId)).withTag(tag);
    }

    private static List<ActivityVariable> variables() {
        return List.of(
                variable("RETRIES", "WHOLE_NUMBER", "Mining"),
                variable("ORE", "TEXT", "Mining"),
                variable("BAIT", "TEXT", "Fishing"),
                variable("DEBUG", "YES_NO", ""),
                variable("GAP", "DURATION", "Timing"));
    }

    @Test
    void theRailIsAllThenCategoriesThenEachDeclaredGroup() {
        List<VariableRailModel.Row> rows = VariableRailModel.rows(variables(), catalog());

        assertEquals(List.of("All variables (5)", "#Categories", "General (1)",
                        "#Activity categories", "Mining (2)", "Fishing (1)",
                        "#Custom categories", "Timing (1)"),
                rows.stream().map(VariableRailModelTest::render).toList());
    }

    /** Both computed rows exist even with nothing in them: a bucket you cannot select is one you cannot fill. */
    @Test
    void allAndGeneralAreOfferedByAnEmptyProject() {
        List<VariableRailModel.Row> rows = VariableRailModel.rows(List.of(), TagCatalog.empty());

        assertEquals(List.of("All variables (0)", "#Categories", "General (0)"),
                rows.stream().map(VariableRailModelTest::render).toList());
    }

    /**
     * The forward-and-backward compatibility case: an activity was deleted, so a variable carries a tag nothing
     * declares any more. It must still have a home, or a value would be invisible in the one dialog that edits
     * it while still being generated into the bot.
     */
    @Test
    void aVariableFiledUnderAVanishedTagIsListedUnderGeneral() {
        List<ActivityVariable> variables = List.of(variable("ORE", "TEXT", "Smelting"));

        List<ActivityVariable> general = VariableRailModel.in(variables, ActivityVariable.GENERAL, catalog());

        assertEquals(List.of("ORE"), general.stream().map(ActivityVariable::name).toList());
        assertEquals(1, VariableRailModel.in(variables, VariableRailModel.ALL, catalog()).size());
    }

    @Test
    void everyVariableIsReachableFromExactlyOneTagRow() {
        List<ActivityVariable> variables = variables();
        TagCatalog catalog = catalog();

        for (ActivityVariable v : variables) {
            long homes = VariableRailModel.rows(variables, catalog).stream()
                    .filter(r -> r instanceof VariableRailModel.TagRow t && !t.tag().equals(VariableRailModel.ALL))
                    .map(r -> ((VariableRailModel.TagRow) r).tag())
                    .filter(tag -> VariableRailModel.in(variables, tag, catalog).contains(v))
                    .count();
            assertEquals(1, homes, v.name() + " should be listed under exactly one tag");
        }
    }

    @Test
    void aTagIsMatchedHoweverItIsSpelled() {
        List<ActivityVariable> variables = List.of(variable("RETRIES", "WHOLE_NUMBER", "mining"));

        assertTrue(VariableRailModel.in(variables, "Mining", catalog()).contains(variables.getFirst()),
                "the catalog is case-insensitive, so the rail must be too");
    }

    private static String render(VariableRailModel.Row row) {
        return switch (row) {
            case VariableRailModel.Heading heading -> "#" + heading.text();
            case VariableRailModel.TagRow tag -> tag.tag() + " (" + tag.count() + ")";
        };
    }
}
