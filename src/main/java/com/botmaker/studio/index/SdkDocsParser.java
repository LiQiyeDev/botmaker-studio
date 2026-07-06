package com.botmaker.studio.index;

import com.botmaker.studio.palette.SdkDocs;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds an {@link SdkDocs} by parsing SDK {@code .java} sources with Eclipse JDT, pulling each public
 * method's Javadoc summary, real parameter names, and {@code @param} descriptions. The Studio does NOT
 * compile against the SDK, so the input is the resolved {@code botmaker-sdk:<version>:sources} jar
 * (see {@code services/SdkDocsService}); nothing here loads SDK classes.
 */
public final class SdkDocsParser {

    private SdkDocsParser() {}

    /** Parse every {@code .java} entry under {@code com/botmaker/sdk/api/} in a sources jar. */
    public static SdkDocs fromSourcesJar(Path sourcesJar) {
        Map<String, Map<String, List<SdkDocs.Overload>>> docs = new LinkedHashMap<>();
        try (InputStream fis = java.nio.file.Files.newInputStream(sourcesJar);
             ZipInputStream zip = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".java")) {
                    continue;
                }
                // Only the public API surface carries user-facing Javadoc worth showing.
                if (!name.contains("com/botmaker/sdk/api/")) {
                    continue;
                }
                String source = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                parseSource(source, docs);
            }
        } catch (IOException e) {
            System.err.println("SdkDocsParser: could not read sources jar " + sourcesJar + ": " + e.getMessage());
            return SdkDocs.EMPTY;
        }
        return new SdkDocs(docs);
    }

    private static void parseSource(String source, Map<String, Map<String, List<SdkDocs.Overload>>> docs) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.isConstructor() || !Modifier.isPublic(node.getModifiers())) {
                    return false;
                }
                String className = enclosingTypeName(node);
                if (className == null) {
                    return false;
                }
                docs.computeIfAbsent(className, k -> new LinkedHashMap<>())
                        .computeIfAbsent(node.getName().getIdentifier(), k -> new ArrayList<>())
                        .add(toOverload(node));
                return false;
            }
        });
    }

    private static String enclosingTypeName(MethodDeclaration node) {
        if (node.getParent() instanceof org.eclipse.jdt.core.dom.AbstractTypeDeclaration type) {
            return type.getName().getIdentifier();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static SdkDocs.Overload toOverload(MethodDeclaration node) {
        Javadoc javadoc = node.getJavadoc();
        String summary = "";
        Map<String, String> paramDocs = new LinkedHashMap<>();
        if (javadoc != null) {
            for (TagElement tag : (List<TagElement>) javadoc.tags()) {
                if (tag.getTagName() == null) {
                    summary = renderFragments(tag.fragments());
                } else if (TagElement.TAG_PARAM.equals(tag.getTagName())) {
                    List<?> frags = tag.fragments();
                    if (!frags.isEmpty() && frags.get(0) instanceof SimpleName pname) {
                        paramDocs.put(pname.getIdentifier(), renderFragments(frags.subList(1, frags.size())));
                    }
                }
            }
        }

        List<SdkDocs.Param> params = new ArrayList<>();
        for (SingleVariableDeclaration p : (List<SingleVariableDeclaration>) node.parameters()) {
            String pname = p.getName().getIdentifier();
            String type = p.getType().toString() + (p.isVarargs() ? "..." : "");
            params.add(new SdkDocs.Param(pname, type, paramDocs.getOrDefault(pname, "")));
        }
        return new SdkDocs.Overload(summary, params);
    }

    /** Flatten Javadoc fragments (text + inline {@code}/{@link} tags) into a single collapsed line. */
    private static String renderFragments(List<?> fragments) {
        StringBuilder sb = new StringBuilder();
        for (Object frag : fragments) {
            if (frag instanceof TextElement text) {
                sb.append(text.getText());
            } else if (frag instanceof TagElement inline) {
                sb.append(' ').append(renderFragments(inline.fragments()));
            } else {
                sb.append(frag);
            }
            sb.append(' ');
        }
        // Drop the handful of HTML formatting tags in the SDK Javadoc (<p>, <em>, <ul>…); deliberately
        // narrow so generic types inside {@code List<ImageTemplate>} are preserved.
        String text = sb.toString().replaceAll("(?i)</?(p|em|b|i|strong|code|pre|ul|ol|li|br)\\b[^>]*>", " ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
