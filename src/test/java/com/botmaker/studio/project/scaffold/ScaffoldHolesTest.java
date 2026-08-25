package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Studio end of the hole contract: every {@code NAME:generation} an SDK declares has a producer here, no
 * producer is ever dropped, and every one of them is actually compiled by the corpus.
 *
 * <h2>Three questions, because no one of them implies the others</h2>
 *
 * <ol>
 *   <li><b>Does this Studio know every hole the SDK it ships against declares?</b> Read straight out of
 *       {@link TemplateStore#bundled()} — the jar this module already resolved to build — so it is offline and
 *       needs no download. A hole with no {@link ScaffoldToken} means Studio would refuse to write that file
 *       against its own SDK, which is not a runtime surprise anybody should have to discover.
 *   <li><b>Has a producer been dropped?</b> That question cannot be answered from any jar, because the SDKs
 *       it is about are <em>published</em> ones this test will never resolve. So it is answered from
 *       {@code scaffold-holes.txt}, the committed ledger of every key that has ever shipped — the same
 *       pattern, and the same reasoning, as the SDK's {@code api-surface.txt}. Deleting a producer silently
 *       un-supports every bot pinned to an SDK still declaring it.
 *   <li><b>Is every hole ever <em>compiled</em>?</b> {@link ScaffoldCompileTest} hands whole rendered projects
 *       to {@code javac}, which is the strongest check available — but only over what the corpus happens to
 *       exercise. A producer can be written, declared in the manifest and never once put in front of a
 *       compiler. This closes that: every declared key must be filled <b>non-trivially</b> by at least one
 *       {@link ScaffoldCorpus} model, and the failure names the corpus rather than the hole, because the
 *       repair is a model.
 * </ol>
 *
 * <p><b>Non-trivially</b> is the load-bearing word: {@code ProjectCreator} legitimately passes {@code ""} for
 * {@code ACTIVITY_IMPORT}, {@code SINGLETONS} and {@code ALL} in a project with no activities, and an empty
 * fill compiles the template's frame rather than anything Studio produced.
 *
 * <h2>What this cannot see</h2>
 *
 * <p>A compile only ever validates the generation the pinned jar's frame declares. Once an SDK moves
 * {@code FLOW:1 → FLOW:2}, the {@code :1} frame is gone from that jar and no corpus model can compile the
 * {@code :1} producer again — it is held by the ledger, and by whatever older jar a developer opens a real
 * project against. That is the honest limit, stated so the backstop is not over-trusted.
 */
class ScaffoldHolesTest {

    /** The committed ledger: every {@code NAME:generation} Studio has ever been able to produce. */
    private static final Path LEDGER = Path.of("scaffold-holes.txt");

    /** {@code -Dbotmaker.scaffold.writeHoles=true} rewrites the ledger instead of asserting against it. */
    private static final boolean WRITE = Boolean.getBoolean("botmaker.scaffold.writeHoles");

    private static final String REGENERATE =
            "  mvn -pl botmaker-studio test -Dtest=ScaffoldHolesTest -Dbotmaker.scaffold.writeHoles=true";

    @AfterEach
    void clearObserver() {
        TemplateStore.fillObserver = null;
    }

    /** Every hole the SDK Studio builds against declares is one this Studio can produce. */
    @Test
    void everyHoleTheSdkDeclaresHasAProducer() {
        Set<String> producible = ScaffoldToken.allKeys();
        List<String> orphans = new ArrayList<>();
        for (String hole : declaredHoles()) {
            if (!producible.contains(hole)) orphans.add(hole);
        }
        if (!orphans.isEmpty()) {
            fail("""
                    The SDK Studio builds against declares %s, which no ScaffoldToken can produce.

                    A hole with no producer is not ignorable in the way an *unknown* hole is: Studio would
                    refuse to write that generated file rather than leave a default standing. Either add the
                    constant (or the generation, if the name is already there) to ScaffoldToken and fill it at
                    the call site, or the SDK's fence is a typo.

                    Studio can produce: %s"""
                    .formatted(String.join(", ", orphans), String.join(", ", producible)));
        }
    }

    /**
     * The ledger holds every key that ever shipped, and no producer is dropped from it.
     *
     * <p>Both directions are checked, but they are not the same kind of finding. A key in the ledger with no
     * producer is a <b>removal</b> — the thing this file exists to refuse. A producer missing <em>from</em>
     * the ledger is just an addition nobody recorded, and the fix is to regenerate.
     */
    @Test
    void noProducerIsEverDropped() throws IOException {
        Set<String> producible = ScaffoldToken.allKeys();
        if (WRITE) {
            Set<String> merged = new TreeSet<>(producible);
            merged.addAll(ledger());
            Files.writeString(LEDGER, header() + String.join("\n", merged) + "\n");
            System.out.println("   (wrote " + LEDGER.toAbsolutePath() + ": " + merged.size() + " holes)");
            return;
        }

        Set<String> shipped = ledger();
        if (shipped.isEmpty()) {
            fail("scaffold-holes.txt is missing or empty. Generate it with:\n" + REGENERATE);
        }

        List<String> dropped = new ArrayList<>(shipped);
        dropped.removeAll(producible);
        if (!dropped.isEmpty()) {
            fail("""
                    %s shipped in a released Studio and has no producer any more.

                    That is not a refactor — it silently un-supports every bot pinned to an SDK that still
                    declares the hole, and no test can catch it later because those SDKs are published and are
                    never resolved here. Keep the old generation beside the new one in ScaffoldToken; a
                    constant may hold several.""".formatted(String.join(", ", dropped)));
        }

        List<String> unrecorded = new ArrayList<>(producible);
        unrecorded.removeAll(shipped);
        if (!unrecorded.isEmpty()) {
            fail("scaffold-holes.txt does not list " + String.join(", ", unrecorded)
                    + ", which this Studio can produce. The ledger is generated — regenerate it with:\n"
                    + REGENERATE + "\nand commit it beside the change that added the producer.");
        }
    }

    /**
     * Every declared hole is filled with something real by at least one corpus model — so that
     * {@link ScaffoldCompileTest} has actually put each producer's output in front of {@code javac}.
     */
    @Test
    void everyHoleIsCompiledByTheCorpus(@TempDir Path root) throws Exception {
        Set<String> filled = new LinkedHashSet<>();
        TemplateStore.fillObserver = (template, fills) -> fills.forEach((hole, text) -> {
            if (!text.isBlank()) filled.add(hole);
        });

        for (ScaffoldCorpus.Model model : ScaffoldCorpus.models()) {
            ProjectConfig config = ProjectConfig.forProject("actbot", root.resolve(slug(model.name())));
            ScaffoldCorpus.render(model, config);
        }
        assertFalse(filled.isEmpty(), "the corpus filled no holes at all — the observer is not being reached");

        // A RESERVED hole is one Studio claims and never produces text for, so demanding a model that fills
        // it is demanding a fixture that cannot exist. Checked in the other direction instead: if one ever
        // does get filled, the claim in ScaffoldToken has stopped being true and the exemption must go.
        List<String> reserved = new ArrayList<>();
        for (ScaffoldToken token : ScaffoldToken.values()) {
            if (token.isReserved()) reserved.addAll(token.keys());
        }
        List<String> wronglyReserved = new ArrayList<>(reserved);
        wronglyReserved.retainAll(filled);
        if (!wronglyReserved.isEmpty()) {
            fail(String.join(", ", wronglyReserved) + " is marked RESERVED in ScaffoldToken — Studio claims"
                    + " it and never produces text for it — but the corpus filled it with something."
                    + " Whichever of the two is now wrong, they have to agree.");
        }

        List<String> never = new ArrayList<>(declaredHoles());
        never.removeAll(filled);
        never.removeAll(reserved);
        if (!never.isEmpty()) {
            fail("""
                    No project in ScaffoldCorpus fills %s with anything, so no compile has ever seen what
                    Studio writes there.

                    The repair is a model, not a hole: add (or extend) one in ScaffoldCorpus.models() whose
                    activity model reaches it, exactly as a new storable type is expected to turn up in "%s".
                    An empty fill does not count — it compiles the SDK's own frame and nothing of ours."""
                    .formatted(String.join(", ", never), ScaffoldCorpus.RICHEST));
        }
    }

    // ---- the two sources -------------------------------------------------------------------------------

    /** Every {@code NAME:generation} the bundled SDK's manifest declares, across all its templates. */
    private static Set<String> declaredHoles() {
        TemplateStore store = TemplateStore.bundled();
        if (store.isEmpty()) {
            fail("the SDK on Studio's own classpath ships no scaffold templates — build it with"
                    + " `mvn -pl botmaker-sdk -am install` from the umbrella root");
        }
        Set<String> holes = new TreeSet<>();
        for (TemplateStore.Template template : store.templates()) holes.addAll(template.holes());
        return holes;
    }

    private static Set<String> ledger() throws IOException {
        if (!Files.exists(LEDGER)) return Set.of();
        Set<String> holes = new TreeSet<>();
        for (String line : Files.readAllLines(LEDGER)) {
            String text = line.strip();
            if (!text.isEmpty() && !text.startsWith("#")) holes.add(text);
        }
        return holes;
    }

    private static String header() {
        return """
                # Every scaffold hole this Studio, or any released one, has ever been able to produce —
                # one NAME:generation per line, sorted. Generated; do not edit by hand:
                #
                #%s
                #
                # A line is never removed. A hole's generation changes when its *shape* does, and a bot pinned
                # to an older SDK still asks for the older one, so dropping a producer breaks a project that
                # no test here can resolve. See docs/refactor/23-scaffold-contract.md.
                """.formatted(REGENERATE.substring(1));
    }

    private static String slug(String name) {
        return name.replaceAll("[^a-zA-Z0-9]+", "-");
    }
}
