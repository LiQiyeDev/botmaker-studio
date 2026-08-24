package com.botmaker.studio.project.scaffold;

import java.util.Map;

/**
 * Verify, then emit — the one step both writers of generated code take before anything reaches the disk.
 *
 * <p>{@code ProjectCreator} runs it over the whole scaffold at creation; {@code ActivityService} runs it over
 * {@link ScaffoldSurface.Origin#REGENERATED} on every save of the activity model. The sequence is the same
 * either way and is stated once here so the two cannot drift: ask {@link ScaffoldCheck} what the pinned jar
 * can carry, apply {@link ScaffoldRepair} when the jar's own pointers say what to write instead, and throw
 * {@link ScaffoldUnsupported} rather than write when either says no.
 *
 * <h2>The other half, which has already happened by the time this runs</h2>
 *
 * <p>A generated file has two authors, so verify-then-emit has two checks, at two moments. The <b>frame</b>
 * is the pinned SDK's own template, and what can go wrong with it is a <em>token</em>: Studio holding a
 * fragment that jar's template has nowhere to put. {@link TemplateStore#render} refuses that, by name, while
 * rendering — before this method is reached and before anything is on disk, which is all the ordering that
 * matters. What is left for here is the <b>fragments</b>: the SDK elements Studio writes into those tokens,
 * which is exactly what {@link ScaffoldSurface} declares and no more.
 *
 * <h2>All or nothing</h2>
 *
 * <p>What comes back is <b>every</b> file that was rendered — rewritten or byte-identical — so a caller writes
 * this map instead of the one it rendered and never has to merge the two. And it either comes back whole or
 * not at all: {@link ScaffoldRepair} refuses a whole batch when one file in it is beyond the rewriter,
 * because a generated file that does not compile is worse than a refusal. Regenerated files hold no user code
 * and have a shape entirely of our making, so there is no half-correct state worth keeping — the bar is
 * flawless or untouched, and untouched still compiles against the jar it was written for.
 */
public final class ScaffoldEmitter {

    private ScaffoldEmitter() {}

    /**
     * Checks {@code rendered} against {@code facts} and returns what to write.
     *
     * @param rendered {@code name -> source}, the text the generators just produced in memory
     * @param facts    the SDK the project pins; facts that are not
     *                 {@linkplain ScaffoldCheck.SdkFacts#indexed() indexed} fail open
     * @param origin   which generator's elements to ask about, or null for the whole surface
     * @param version  the SDK version, named in the refusal so the user knows which pin to change
     * @return every rendered file, repaired where the pointers asked for it
     * @throws ScaffoldUnsupported when nothing may be written — the element is gone with no survivor, or the
     *                             survivor cannot be expressed in the emitted text
     */
    public static Map<String, String> emit(Map<String, String> rendered, ScaffoldCheck.SdkFacts facts,
                                           ScaffoldSurface.Origin origin, String version)
            throws ScaffoldUnsupported {
        ScaffoldCheck.Result check = origin == null
                ? ScaffoldCheck.of(facts) : ScaffoldCheck.of(facts, origin);
        if (!check.canEmit()) throw new ScaffoldUnsupported(check.refusal());
        if (check.substitutions().isEmpty()) return rendered;

        ScaffoldRepair.Outcome repaired = ScaffoldRepair.apply(rendered, check.substitutions());
        if (!repaired.canEmit()) {
            throw new ScaffoldUnsupported("SDK " + version + " has moved something Studio writes into the "
                    + "files it generates, and the move is not one Studio can apply on its own: "
                    + String.join("; ", repaired.unexpressed())
                    + ". Update Studio (Help ▸ Check for updates), or pick an SDK version this Studio knows.");
        }
        System.out.println("   (repaired " + check.substitutions().size()
                + " scaffold element(s) against SDK " + version + ")");
        return repaired.sources();
    }
}
