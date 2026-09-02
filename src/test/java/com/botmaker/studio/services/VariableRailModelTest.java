package com.botmaker.studio.services;

import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.ValueWire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Parameters dialog's rail, which is a decision rather than a widget: which buckets exist, what each holds,
 * and — the one that matters — that no variable can end up in none of them.
 *
 * <p>The categories came from the picture library's {@code TagCatalog} until 2026-09-02 and are declared on a
 * {@link ParameterGroup} now, so the fixture is a list of plugin sections rather than an activity list. The
 * two headings the rail used to draw over the tags — <i>Activity categories</i>, <i>Custom categories</i> —
 * went with that split: a category has one origin now, the plugin that owns the section.
 */
class VariableRailModelTest {

    /** Two sections, as two plugins would declare them — the merge is the thing under test. */
    private static List<ParameterGroup> groups() {
        return List.of(
                ParameterGroup.of(ParameterGroup.DEFAULT_ID, "Parameters", List.of("Mining", "Fishing")),
                ParameterGroup.of("discord", "DiscordParameters", List.of("Timing")));
    }

    private static List<String> categories() {
        return VariableRailModel.categoriesOf(groups());
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
    void theRailIsAllThenCategoriesThenEachDeclaredCategory() {
        List<VariableRailModel.Row> rows = VariableRailModel.rows(variables(), categories());

        assertEquals(List.of("All variables (5)", "#Categories", "General (1)",
                        "Mining (2)", "Fishing (1)", "Timing (1)"),
                rows.stream().map(VariableRailModelTest::render).toList());
    }

    @Test
    void theCategoriesOfSeveralSectionsMergeInSectionOrderWithoutDuplicates() {
        // Two plugins may both call a category "Timing"; the rail is one list, so it must be listed once.
        List<ParameterGroup> overlapping = List.of(
                ParameterGroup.of(ParameterGroup.DEFAULT_ID, "Parameters", List.of("Timing", "Vision")),
                ParameterGroup.of("discord", "DiscordParameters", List.of("timing", "Webhooks")));

        assertEquals(List.of("Timing", "Vision", "Webhooks"), VariableRailModel.categoriesOf(overlapping),
                "first spelling wins, and it wins case-insensitively");
    }

    /** Both computed rows exist even with nothing in them: a bucket you cannot select is one you cannot fill. */
    @Test
    void allAndGeneralAreOfferedByAnEmptyProject() {
        List<VariableRailModel.Row> rows = VariableRailModel.rows(List.of(), List.of());

        assertEquals(List.of("All variables (0)", "#Categories", "General (0)"),
                rows.stream().map(VariableRailModelTest::render).toList());
    }

    /**
     * The forward-and-backward compatibility case: a variable carries a category nothing declares any more —
     * an older project's activity name, or a category the plugin dropped. It must still have a home, or a
     * value would be invisible in the one dialog that edits it while still being generated into the bot.
     */
    @Test
    void aVariableFiledUnderAVanishedCategoryIsListedUnderGeneral() {
        List<ActivityVariable> variables = List.of(variable("ORE", "TEXT", "Smelting"));

        List<ActivityVariable> general = VariableRailModel.in(variables, ActivityVariable.GENERAL, categories());

        assertEquals(List.of("ORE"), general.stream().map(ActivityVariable::name).toList());
        assertEquals(1, VariableRailModel.in(variables, VariableRailModel.ALL, categories()).size());
    }

    @Test
    void everyVariableIsReachableFromExactlyOneTagRow() {
        List<ActivityVariable> variables = variables();
        List<String> categories = categories();

        for (ActivityVariable v : variables) {
            long homes = VariableRailModel.rows(variables, categories).stream()
                    .filter(r -> r instanceof VariableRailModel.TagRow t && !t.tag().equals(VariableRailModel.ALL))
                    .map(r -> ((VariableRailModel.TagRow) r).tag())
                    .filter(tag -> VariableRailModel.in(variables, tag, categories).contains(v))
                    .count();
            assertEquals(1, homes, v.name() + " should be listed under exactly one category");
        }
    }

    @Test
    void aCategoryIsMatchedHoweverItIsSpelled() {
        List<ActivityVariable> variables = List.of(variable("RETRIES", "WHOLE_NUMBER", "mining"));

        assertTrue(VariableRailModel.in(variables, "Mining", categories()).contains(variables.getFirst()),
                "a category read out of the file is matched the way a user reads it");
        assertTrue(VariableRailModel.isDeclared(categories(), "MINING"),
                "and the declared-ness test agrees with the filter, or a variable would be in two rows");
    }

    private static String render(VariableRailModel.Row row) {
        return switch (row) {
            case VariableRailModel.Heading heading -> "#" + heading.text();
            case VariableRailModel.TagRow tag -> tag.tag() + " (" + tag.count() + ")";
        };
    }
}
