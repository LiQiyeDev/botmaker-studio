package com.botmaker.studio.project.scaffold;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the scaffold out of the SDK jar, and the two directions a token can be unknown.
 *
 * <p>The templates themselves are the SDK's to check — its build compiles them and its
 * {@code ScaffoldTemplatesTest} asserts every declared token is one matched pair of fences. What is Studio's
 * is this: that it finds them, that the manifest it reads agrees with the files that came with it, and that
 * filling a token replaces exactly the fenced region and nothing else.
 */
class TemplateStoreTest {

    private static final Set<String> EXPECTED_TEMPLATES = Set.of(
            "ENTRY_POINT", "GO_HOME", "POPUPS", "ACTIVITY_STUB",
            "ACTIVITIES", "ACTIVITY_REGISTRY", "FLOW_DRIVER");

    @Test
    void theBundledSdkShipsEveryTemplateStudioWrites() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        assertFalse(store.isEmpty(), "the SDK Studio compiles against must carry its own scaffold templates");
        for (String id : EXPECTED_TEMPLATES) {
            assertTrue(store.require(id).source().startsWith("package com.botmaker.sdk.templates"),
                    id + " should be a template in the SDK's own package");
        }
    }

    @Test
    void aTemplateThisSdkDoesNotShipIsRefusedByName() {
        ScaffoldUnsupported refusal = assertThrows(ScaffoldUnsupported.class,
                () -> TemplateStore.bundled().require("NO_SUCH_TEMPLATE"));
        assertTrue(refusal.getMessage().contains("NO_SUCH_TEMPLATE"), refusal.getMessage());
    }

    /**
     * The forward-compatibility rule, from Studio's side: a token it does not fill keeps its default, and the
     * file still compiles. That is what lets a <em>newer</em> SDK add tokens this Studio has never heard of.
     */
    @Test
    void aTokenLeftAloneKeepsItsDefault() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        TemplateStore.Template driver = store.require("FLOW_DRIVER");

        String rendered = store.render(driver, "mybot", null, Map.of("MAX_STEPS", "42"));

        assertTrue(rendered.contains("MAX_STEPS = 42;"), rendered);
        assertTrue(rendered.contains("STEP_DELAY_MS = 0;"),
                "an unfilled token keeps the SDK's own default:\n" + rendered);
        assertTrue(rendered.contains("FlowGraph.node(\"Example\""),
                "and so does an unfilled multi-line one:\n" + rendered);
        assertTrue(TemplateStore.unfilledTokens(rendered).isEmpty(),
                "the fences themselves never survive into a bot's source");
    }

    /**
     * And the other direction, which is <b>not</b> ignorable: Studio holding a fragment the template has
     * nowhere to put means part of the user's project would be silently dropped.
     */
    @Test
    void aTokenTheTemplateDoesNotDeclareIsRefusedByName() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        TemplateStore.Template goHome = store.require("GO_HOME");

        ScaffoldUnsupported refusal = assertThrows(ScaffoldUnsupported.class,
                () -> store.render(goHome, "mybot", null, Map.of("WHAT_IS_THIS", "x")));
        assertTrue(refusal.getMessage().contains("WHAT_IS_THIS"), refusal.getMessage());
    }

    @Test
    void renderingRewritesThePackageAndTheClassName() throws Exception {
        TemplateStore store = TemplateStore.bundled();
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("OUTCOMES", "NEXT, BAG_FULL");
        tokens.put("ENABLED", "Activities.Mining");

        String rendered = store.render(store.require("ACTIVITY_STUB"), "mybot", "Mining", tokens);

        assertTrue(rendered.startsWith("package com.mybot.activities;"), rendered);
        assertTrue(rendered.contains("public class Mining extends Activity<Mining.Outcome>"), rendered);
        assertTrue(rendered.contains("import com.mybot.Activities;"), rendered);
        assertTrue(rendered.contains("public enum Outcome { NEXT, BAG_FULL }"), rendered);
        assertTrue(rendered.contains("return Activities.Mining;"), rendered);
        assertFalse(rendered.contains("ActivityStub"), "nothing may still name the template:\n" + rendered);
        assertFalse(rendered.contains("com.botmaker.sdk.templates"), rendered);
    }

    /**
     * A jar with no {@code botmaker-templates/} in it is an SDK from before they existed. It falls back to the
     * ones Studio ships rather than failing — the same fail-open rule {@code ScaffoldCheck} follows, and the
     * reason a project pinned to an old SDK still opens.
     */
    @Test
    void aJarWithNoTemplatesFallsBackToTheBundledOnes() throws Exception {
        TemplateStore store = TemplateStore.forJar(java.nio.file.Path.of("no", "such.jar"));
        assertFalse(store.isEmpty());
        assertEquals(TemplateStore.bundled().require("GO_HOME").source(), store.require("GO_HOME").source());
    }
}
