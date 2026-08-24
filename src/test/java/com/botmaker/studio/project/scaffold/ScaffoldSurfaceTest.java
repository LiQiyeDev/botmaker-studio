package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.scaffold.ScaffoldSurface.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ScaffoldSurface} is a claim about two things, and this checks it against both.
 *
 * <ol>
 *   <li><b>The SDK has these.</b> Studio compiles against one SDK, so every entry can be looked up in it
 *       right here. Rename {@code Wire#duration} over there, reinstall, and <em>Studio's</em> build fails
 *       naming that member — instead of the rename surfacing as a broken file in somebody's project.</li>
 *   <li><b>Studio writes these.</b> A declaration nothing emits any more is not harmless: it is what
 *       {@link ScaffoldCheck} would refuse a newer SDK over, for an element no generated file contains. So
 *       every entry has to turn up somewhere in {@link ScaffoldCorpus}.</li>
 * </ol>
 *
 * <h2>The direction that is deliberately not checked here</h2>
 *
 * <p>The reverse of (2) — an <em>injected</em> element that nobody declared — used to be checked, by parsing
 * the generators' output with {@code ScaffoldScan}. It is not any more, and the reason is that the parse can
 * no longer tell Studio's fragments from the SDK's own template frame around them: both are in the same file
 * by the time anything can read it, and the frame is not Studio's to declare.
 *
 * <p>What that direction protected against was a project pinned to a <em>newer</em> SDK losing an undeclared
 * element, which would show up as a project that does not compile rather than as a clean refusal. It is worth
 * being plain that this is a real, if narrow, loss. What makes it a fair trade: {@link ScaffoldCompileTest}
 * now compiles the whole assembled output of four models against the real jar, which catches far more than
 * the old scan ever did in the backward direction; and the set that has to be declared by hand went from
 * everything a generated file names to the handful of members Studio drops between the fences — small enough
 * to read in one screen, next to the four methods that emit them.
 */
class ScaffoldSurfaceTest {

    // ------------------------------------------------------------------
    // 1 — the SDK Studio builds against has every declared element
    // ------------------------------------------------------------------

    @Test
    void everyDeclaredElementExistsInTheSdk() {
        List<String> missing = new ArrayList<>();
        for (Element e : ScaffoldSurface.all()) {
            Class<?> type;
            try {
                type = Class.forName(e.type());
            } catch (ClassNotFoundException absent) {
                missing.add(e.type() + " (the type itself)");
                continue;
            }
            if (e.isType()) continue;
            if (!hasMember(type, e.member(), e.arity())) {
                missing.add(e.line());
            }
        }
        if (missing.isEmpty()) return;
        fail("The SDK Studio compiles against no longer has: " + String.join(", ", missing)
                + ". Studio injects these into the files it generates, so a project made by this build would"
                + " not compile. Either the SDK moved them — follow its @ReplacedBy pointer and update the"
                + " generator and ScaffoldSurface together — or the declaration was wrong.");
    }

    private static boolean hasMember(Class<?> type, String member, int arity) {
        if (ScaffoldSurface.CTOR.equals(member)) {
            for (Constructor<?> c : type.getConstructors()) {
                if (c.getParameterCount() == arity) return true;
            }
            return false;
        }
        for (Method m : type.getMethods()) {
            if (m.getName().equals(member) && m.getParameterCount() == arity) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 2 — nothing declared is stale
    // ------------------------------------------------------------------

    @Test
    void everyDeclaredElementIsSomethingAGeneratorActuallyWrites(@TempDir Path root) throws Exception {
        StringBuilder corpus = new StringBuilder();
        List<ScaffoldCorpus.Model> models = ScaffoldCorpus.models();
        for (int i = 0; i < models.size(); i++) {
            ProjectConfig config = ProjectConfig.forProject("actbot", root.resolve("model" + i));
            ScaffoldCorpus.render(models.get(i), config).values()
                    .forEach(source -> corpus.append(source).append('\n'));
        }
        String all = corpus.toString();

        List<String> stale = new ArrayList<>();
        for (Element e : ScaffoldSurface.all()) {
            if (!Pattern.compile(spelling(e)).matcher(all).find()) stale.add(e.line());
        }
        if (stale.isEmpty()) return;
        fail("ScaffoldSurface declares elements no generator writes any more: " + String.join(", ", stale)
                + ". A declaration with nothing behind it is not inert — ScaffoldCheck would refuse an SDK"
                + " that dropped it, for a member no generated file contains. Delete the line, or add the"
                + " model that exercises it to ScaffoldCorpus if this is a shape the corpus is missing.");
    }

    /**
     * How an element is spelled in generated source: {@code Type.member(} for a member, the bare type name as
     * a whole word for a type. Simple names, because the generators import rather than qualify.
     */
    private static String spelling(Element e) {
        String simple = e.type().substring(e.type().lastIndexOf('.') + 1);
        return e.isType()
                ? "\\b" + Pattern.quote(simple) + "\\b"
                : Pattern.quote(simple + "." + e.member() + "(");
    }
}
