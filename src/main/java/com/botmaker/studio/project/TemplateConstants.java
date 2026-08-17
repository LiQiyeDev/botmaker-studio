package com.botmaker.studio.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The generated {@code Templates} class — one {@code public static final String} constant per image template —
 * and the two-way mapping between a template's file name and the constant that names it.
 *
 * <p><b>Why a constant and not the path.</b> Generated source used to spell a template as
 * {@code new ImageTemplate("src/main/resources/images/ytuj.png")}: a path repeated at every use site, invisible
 * to the compiler, and wrong in every one of them the moment the file is renamed. {@code Templates.YTUJ} is the
 * same string declared once. Renaming a template now regenerates one line and breaks the build at each use site
 * that has to change, rather than leaving a bot that compiles and finds nothing at run time. It needs no SDK
 * support — the constant is an ordinary {@code String}, so a developer working without Studio can write the
 * class by hand or keep using literals.
 *
 * <p><b>Why the mapping is exact.</b> A constant is only useful if Studio can read it back: the picker that
 * renders a chip has {@code Templates.YTUJ} in the AST and needs the file it stands for. Rather than carrying a
 * side table (which would be one more thing that can disagree with the images folder), the two names are a
 * bijection — the file is {@code ytuj.png} and the constant is {@code YTUJ}. That is what
 * {@link com.botmaker.studio.services.ImageTemplateLibrary#sanitizeName} lowercasing buys: a template name is a
 * lowercase Java identifier, so uppercasing it is reversible and can't collide.
 *
 * <p>A template whose name predates that rule (mixed case, or a {@code -}) simply has no constant:
 * {@link #constantFor} answers null, the generated class skips it, and Studio keeps writing its path as a string
 * literal. Both spellings are read, so the two kinds coexist in one project and an old bot keeps compiling.
 */
public final class TemplateConstants {

    /** The generated class's simple name, in the project's base package next to {@code Activities}. */
    public static final String CLASS_NAME = "Templates";

    /** Where every template file lives, project-root-relative — the other half of the name↔path mapping. */
    public static final String IMAGES_PREFIX = "src/main/resources/images/";

    private TemplateConstants() {}

    /**
     * The constant naming the template called {@code baseName}, or {@code null} when that name cannot be one —
     * anything that isn't a lowercase Java identifier, which is every name written before Studio started
     * lowercasing them.
     */
    public static String constantFor(String baseName) {
        if (baseName == null || baseName.isBlank()) return null;
        if (!baseName.equals(baseName.toLowerCase(Locale.ROOT))) return null;
        if (!Character.isJavaIdentifierStart(baseName.charAt(0)) || baseName.charAt(0) == '$') return null;
        for (int i = 1; i < baseName.length(); i++) {
            char c = baseName.charAt(i);
            if (c != '_' && !Character.isLetterOrDigit(c)) return null;
            if (c > 127) return null;   // an identifier Java accepts but a constant nobody wants to read
        }
        return baseName.toUpperCase(Locale.ROOT);
    }

    /** The template file name a constant stands for, or {@code null} when {@code constant} isn't one of ours. */
    public static String baseNameFor(String constant) {
        if (constant == null || constant.isBlank()) return null;
        String lower = constant.toLowerCase(Locale.ROOT);
        return constant.equals(constantFor(lower)) ? lower : null;
    }

    /** The project-relative path a constant stands for, or {@code null} when {@code constant} isn't one. */
    public static String pathForConstant(String constant) {
        String baseName = baseNameFor(constant);
        return baseName == null ? null : IMAGES_PREFIX + baseName + ".png";
    }

    /** The constant for a project-relative template path, or {@code null} when that path has none. */
    public static String constantForPath(String path) {
        if (path == null || !path.startsWith(IMAGES_PREFIX) || !path.endsWith(".png")) return null;
        return constantFor(path.substring(IMAGES_PREFIX.length(), path.length() - ".png".length()));
    }

    /**
     * The whole {@code Templates.java} source for {@code baseNames}, in the given package.
     *
     * <p>Written even with no templates at all, for the same reason the generated {@code Activities} class is:
     * a hand-written {@code import com.<pkg>.Templates;} must keep compiling after the last template is
     * deleted. Names with no constant are listed in a comment rather than dropped silently, so the one question
     * the class raises ("why isn't mine here?") is answered in the file itself.
     */
    public static String generateSource(String packageName, List<String> baseNames) {
        List<String> named = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String baseName : baseNames == null ? List.<String>of() : baseNames) {
            if (constantFor(baseName) != null) named.add(baseName);
            else if (baseName != null && !baseName.isBlank()) skipped.add(baseName);
        }
        named.sort(String::compareTo);

        StringBuilder out = new StringBuilder();
        out.append("package com.").append(packageName).append(";\n\n");
        out.append("/**\n");
        out.append(" * Every image template in this project, by name.\n");
        out.append(" *\n");
        out.append(" * <p>Use one wherever a template is wanted: {@code new ImageTemplate(Templates.").append(
                named.isEmpty() ? "MY_TEMPLATE" : named.getFirst().toUpperCase(Locale.ROOT)).append(")}.\n");
        out.append(" * Naming the file here rather than repeating its path at every use site means a rename is\n");
        out.append(" * one edit, and a use site that has to change fails to compile instead of failing to find.\n");
        out.append(" *\n");
        out.append(" * <p>GENERATED by BotMaker Studio from the images folder — edits are overwritten whenever a\n");
        out.append(" * template is added, renamed or deleted. Add a template through Studio, not by editing here.\n");
        out.append(" */\n");
        out.append("public final class ").append(CLASS_NAME).append(" {\n\n");
        out.append("    private ").append(CLASS_NAME).append("() {}\n");
        for (String baseName : named) {
            out.append("\n    public static final String ").append(constantFor(baseName))
                    .append(" = \"").append(IMAGES_PREFIX).append(baseName).append(".png\";\n");
        }
        if (!skipped.isEmpty()) {
            out.append("\n    // No constant for: ").append(String.join(", ", skipped)).append('\n');
            out.append("    // A template is named here only when its file name is a lowercase identifier;\n");
            out.append("    // rename it in the resource manager to give it one.\n");
        }
        out.append("}\n");
        return out.toString();
    }
}
