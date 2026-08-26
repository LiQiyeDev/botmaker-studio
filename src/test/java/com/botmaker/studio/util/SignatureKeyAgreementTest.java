package com.botmaker.studio.util;

import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.MemberEntry;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two vocabularies of a signature key must agree, for <b>every</b> method in the real SDK jar.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>One overload is identified by one string, and two parts of Studio derive that string by different
 * routes. {@code ProjectAnalyzer} builds a {@link MethodSignature} from the ClassGraph index and asks it for
 * {@link MethodSignature#signatureKey()}; {@code services/SdkSurfaceService} never builds one — it answers
 * questions about members the user has not inserted — and calls
 * {@link MethodSignature#signatureKeyOf(MethodInfo)} on the raw {@link MethodInfo} instead.
 *
 * <p>A disagreement between them has no symptom. The SDK's catalog names an overload, the key it produces
 * matches nothing the menu holds, and the entry silently never appears — indistinguishable from never having
 * been catalogued at all. So the check is not "the derivation looks right" but "they spell every real method
 * the same way", run against the actual SDK, which is where the awkward cases live: varargs (the descriptor
 * is an array, the key is the element), generics ({@code Consumer<MatchResult>}), primitives, arrays, and
 * types the index does not resolve.
 *
 * <p><b>There is a third vocabulary since phase 7</b>, and it is the one curation actually flows through:
 * {@link MethodSignature#signatureKeyOf(com.botmaker.plugin.api.catalog.MemberId)}, over the JVM descriptor
 * a catalog's method reference carried. {@code SdkSurfaceService.offeredSignatures} spells its answer that
 * way and the menus filter their own {@code MethodSignature}s with it, so the two must agree for every
 * member the bundled catalog names — which is what the second test below asserts.
 */
class SignatureKeyAgreementTest {

    private static final String API_PACKAGE = "com.botmaker.sdk.api";

    @Test
    void bothVocabulariesSpellEveryMethodInTheSdkTheSameWay() {
        Path jar = sdkJar();
        assumeTrue(jar != null && jar.toString().endsWith(".jar"),
                "the SDK is on the classpath as a directory (a reactor build); this test needs the jar the "
                        + "index actually scans");

        TypeSummaryManager index = new TypeSummaryManager(Set.of(API_PACKAGE));
        index.refresh(List.of(jar.toString()));
        List<ClassInfo> types = index.getAllTypes();
        assertTrue(types.size() > 20,
                "scanned only " + types.size() + " api.* types — the jar is probably not the SDK's, which "
                        + "would make the comparison below vacuous");

        ProjectAnalyzer analyzer = new ProjectAnalyzer(index, new ProjectState());
        List<String> disagreements = new ArrayList<>();
        int compared = 0;

        for (ClassInfo ci : types) {
            // What the menus and blocks see: MethodSignatures, keyed the long way round.
            Set<String> viaSignature = analyzer.getMethods(ci.getSimpleName(), false).stream()
                    .map(s -> s.name() + "(" + s.signatureKey() + ")")
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            // What SdkSurfaceService sees: MethodInfo, keyed directly.
            Set<String> viaMethodInfo = new LinkedHashSet<>();
            for (MethodInfo mi : ci.getMethodInfo()) {
                if (!mi.isPublic() || mi.isSynthetic() || mi.isBridge()) continue;
                viaMethodInfo.add(mi.getName() + "(" + MethodSignature.signatureKeyOf(mi) + ")");
                compared++;
            }

            for (String key : viaMethodInfo) {
                if (!viaSignature.contains(key)) {
                    disagreements.add(ci.getSimpleName() + "." + key + " — no MethodSignature spells it that way");
                }
            }
        }

        assertTrue(compared > 100, "compared only " + compared + " methods; the scan found too little to trust");
        assertEquals(List.of(), disagreements,
                "signatureKeyOf(MethodInfo) and MethodSignature.signatureKey() disagree. Every one of these "
                        + "is an overload that a catalog can name in the SDK and Studio never offer, with no "
                        + "error anywhere. Fix MethodSignature.simpleNameOf to match "
                        + "ProjectAnalyzer.toMethodSignature:\n  " + String.join("\n  ", disagreements));
    }

    /**
     * The third vocabulary: every member the bundled catalog names must be spelled by a real
     * {@link MethodSignature} the menus can hold.
     *
     * <p>The catalog is authoritative about <em>what to offer</em> and the index about <em>what exists</em>,
     * and the two meet on this one string. A catalogued member whose key matches nothing is the same silent
     * failure as above, reached from the direction curation actually travels — and it is the direction where
     * varargs bites hardest, since a descriptor cannot say varargs and the key must anyway.
     */
    @Test
    void everyMemberTheCatalogNamesIsSpelledByARealSignature() {
        Path jar = sdkJar();
        assumeTrue(jar != null && jar.toString().endsWith(".jar"),
                "the SDK is on the classpath as a directory (a reactor build); this test needs the jar the "
                        + "index actually scans");

        TypeSummaryManager index = new TypeSummaryManager(Set.of(API_PACKAGE));
        index.refresh(List.of(jar.toString()));
        ProjectAnalyzer analyzer = new ProjectAnalyzer(index, new ProjectState());

        List<String> disagreements = new ArrayList<>();
        int compared = 0;

        for (FacadeEntry facade : PluginHost.bundled().facades()) {
            Set<String> viaSignature = analyzer.getMethods(facade.simpleName(), false).stream()
                    .map(s -> s.name() + "(" + s.signatureKey() + ")")
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (MemberEntry member : facade.members()) {
                if (member.id().isConstructor()) continue;
                String key = member.id().name() + "(" + MethodSignature.signatureKeyOf(member.id()) + ")";
                compared++;
                if (!viaSignature.contains(key)) {
                    disagreements.add(facade.simpleName() + "." + key + " — catalogued, but no "
                            + "MethodSignature spells it that way");
                }
            }
        }

        assertTrue(compared > 100, "compared only " + compared + " catalogued members; the bundled catalog "
                + "found too little to trust");
        assertEquals(List.of(), disagreements,
                "signatureKeyOf(MemberId) and MethodSignature.signatureKey() disagree. Every one of these is "
                        + "a member the SDK's catalog offers and Studio's menus can never match, with no "
                        + "error anywhere:\n  " + String.join("\n  ", disagreements));
    }

    /** The jar the SDK's own classes came from — the same artifact a bot resolves and the index scans. */
    private static Path sdkJar() {
        try {
            return Path.of(com.botmaker.sdk.api.vision.ImageFinder.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
