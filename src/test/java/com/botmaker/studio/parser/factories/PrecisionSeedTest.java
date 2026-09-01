package com.botmaker.studio.parser.factories;

import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a freshly inserted {@code Precision} slot is seeded with.
 *
 * <p>The rest of what this file used to hold — the source text the editor commits, the parse that reads it
 * back, and which knobs each call can act on — went to the SDK with the editor itself on 2026-08-30
 * ({@code com.botmaker.sdk.internal.plugin.editors.PrecisionEditors}, tested by {@code PrecisionEditorTest}
 * there). The seed stays here because it is not the editor's: {@link InitializerFactory} runs when a block is
 * placed, before any editor is asked for, and a wrong seed is uncompilable Java in the user's project rather
 * than a widget that looks odd.
 */
class PrecisionSeedTest {

    private static String seedFor(String typeName) {
        AST ast = AST.newAST(AST.getJLSLatest(), true);
        Expression seeded = InitializerFactory.createDefaultInitializer(ast, ResolvedType.named(typeName));
        return seeded == null ? null : seeded.toString();
    }

    @Test
    void aFreshSlotIsSeededWithTheNamedDefaultNotAnUncompilableConstructor() {
        // `new Precision()` would not compile — the record has required components. It also has to be the
        // *constant*: seeding a bare 12.0 would defeat the type, which exists so the call site says what the
        // number means.
        // Fully qualified because the seed is the plugin's sentence now (SdkPlugin.sourceSeeds), and a
        // plugin cannot know what the target file imports — the same reason the java.awt.Color seed beside
        // it in InitializerFactory has always been written out in full.
        assertEquals("com.botmaker.sdk.api.vision.Precision.DEFAULT", seedFor("Precision"));
    }
}
