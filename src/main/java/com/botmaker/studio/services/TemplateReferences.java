package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.TemplateConstants;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where a template is used in the bot's own source, and how to point those uses somewhere else.
 *
 * <p><b>Why this exists.</b> Renaming a template used to move three files ({@link ImageTemplateLibrary#renameTemplate})
 * and regenerate {@code Templates.java} — which turns every {@code Templates.OLD} into a compile error, the
 * intended behaviour, but leaves the user to find and fix each one by hand. And a template written before the
 * lowercase rule has no constant at all, so its uses are raw path literals: renaming those breaks nothing at
 * compile time and everything at run time. Deleting had the same two problems, one step worse — the file is
 * gone, so there is nothing to point at.
 *
 * <p>So both operations ask this class first. It answers two questions:
 * <ul>
 *   <li><b>Who uses this?</b> {@link #find} — with file and line, so a refusal to delete can say where.</li>
 *   <li><b>Point them at that instead.</b> {@link #retarget} — a rename passes the new name, a delete passes
 *       the replacement template the user picked.</li>
 * </ul>
 *
 * <p><b>Text, not AST.</b> A template reference has exactly two spellings — the generated constant
 * ({@code Templates.OLD}, possibly package-qualified) and the project-relative path as a string literal
 * ({@code "src/main/resources/images/old.png"}) — and both are unambiguous tokens. Parsing every file in the
 * project to rewrite a token that cannot occur inside an identifier buys nothing, and would fail on exactly the
 * file that matters most: one that does not currently compile.
 *
 * <p><b>Both copies.</b> The editor holds open files in memory ({@link ProjectState}) and only writes them out
 * on run, so a rewrite that touched only the disk would be undone by the next save. Every operation here
 * rewrites the file on disk <em>and</em> the open buffer, which is why {@code state} is a parameter rather than
 * something the caller could skip.
 */
public final class TemplateReferences {

    private TemplateReferences() {}

    /** One use of a template: which file, which line (1-based) and the line's text, trimmed. */
    public record Use(Path file, int line, String text) {}

    /** Everything found for one template. Empty means the template can be deleted with nothing to fix. */
    public record Scan(String baseName, List<Use> uses) {

        public boolean isEmpty() { return uses.isEmpty(); }

        /** How many distinct files use it — what a refusal message leads with. */
        public int fileCount() {
            return (int) uses.stream().map(Use::file).distinct().count();
        }

        /** "3 blocks in 2 files" — the phrase both the delete refusal and the rename report open with. */
        public String describe() {
            return uses.size() + (uses.size() == 1 ? " use" : " uses")
                    + " in " + fileCount() + (fileCount() == 1 ? " file" : " files");
        }
    }

    /**
     * Every use of the template called {@code baseName} in the bot's own sources. The generated
     * {@code Templates.java} is skipped: it declares the constant rather than using it, and it is rewritten
     * from the images folder anyway.
     */
    public static Scan find(ProjectConfig config, ProjectState state, String baseName) {
        List<Use> uses = new ArrayList<>();
        Pattern pattern = anyReferenceTo(baseName);
        forEachSource(config, state, (file, source) -> {
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) uses.add(new Use(file, i + 1, lines[i].trim()));
            }
            return null;   // reading only
        });
        return new Scan(baseName, List.copyOf(uses));
    }

    /**
     * Rewrites every reference to {@code oldName} so it names {@code newName} instead, on disk and in the open
     * buffers, and returns the files that changed.
     *
     * <p>Each spelling is replaced with the same spelling: a constant reference becomes the new template's
     * constant, a path literal becomes the new template's path. A template whose new name has no constant (an
     * imported name that isn't a lowercase identifier) has its constant references rewritten to the path
     * literal instead — still correct, and the only thing left to write.
     */
    public static List<Path> retarget(ProjectConfig config, ProjectState state, String oldName, String newName) {
        Pattern constant = constantReferenceTo(oldName);
        Pattern literal = literalReferenceTo(oldName);
        String newConstant = TemplateConstants.constantFor(newName);
        String newLiteral = '"' + TemplateConstants.IMAGES_PREFIX + newName + ".png\"";
        String constantReplacement = Matcher.quoteReplacement(
                newConstant == null ? newLiteral : TemplateConstants.CLASS_NAME + "." + newConstant);
        String literalReplacement = Matcher.quoteReplacement(newLiteral);

        List<Path> changed = new ArrayList<>();
        forEachSource(config, state, (file, source) -> {
            String rewritten = source;
            if (constant != null) rewritten = constant.matcher(rewritten).replaceAll(constantReplacement);
            rewritten = literal.matcher(rewritten).replaceAll(literalReplacement);
            if (rewritten.equals(source)) return null;
            changed.add(file);
            return rewritten;
        });
        return List.copyOf(changed);
    }

    // ── the two spellings ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code Templates.OLD}, allowing a package qualifier in front and whitespace around the dot — only the
     * {@code Templates.OLD} part is matched, so a qualified use keeps its qualifier. Null when the name has no
     * constant (see {@link TemplateConstants}), in which case there is nothing of this shape to find.
     */
    private static Pattern constantReferenceTo(String baseName) {
        String constant = TemplateConstants.constantFor(baseName);
        return constant == null ? null
                : Pattern.compile("\\b" + TemplateConstants.CLASS_NAME + "\\s*\\.\\s*" + constant + "\\b");
    }

    /** The project-relative path as a whole string literal — how a template with no constant is written. */
    private static Pattern literalReferenceTo(String baseName) {
        return Pattern.compile(Pattern.quote('"' + TemplateConstants.IMAGES_PREFIX + baseName + ".png\""));
    }

    /** Either spelling, for the read-only scan. */
    private static Pattern anyReferenceTo(String baseName) {
        Pattern constant = constantReferenceTo(baseName);
        return constant == null ? literalReferenceTo(baseName)
                : Pattern.compile(constant.pattern() + "|" + literalReferenceTo(baseName).pattern());
    }

    // ── walking the bot's sources ───────────────────────────────────────────────────────────────────────

    /** What to do with one source file; return the rewritten text, or null to leave it alone. */
    @FunctionalInterface
    private interface Rewrite {
        String apply(Path file, String source);
    }

    /**
     * Visits every {@code .java} file the bot owns exactly once, preferring the open buffer's text over the
     * file on disk where a file is open — the buffer is what the user is looking at, and may hold edits the
     * disk has not seen. A returned rewrite is written to whichever copies exist.
     */
    private static void forEachSource(ProjectConfig config, ProjectState state, Rewrite rewrite) {
        Map<Path, ProjectFile> open = new LinkedHashMap<>();
        if (state != null) {
            for (ProjectFile file : state.getAllFiles()) {
                if (file.getPath() != null) open.put(file.getPath().toAbsolutePath().normalize(), file);
            }
        }
        Set<Path> seen = new LinkedHashSet<>();
        Path generated = config.templatesSourceFile().toAbsolutePath().normalize();

        for (Path file : javaFiles(config)) {
            if (file.equals(generated) || !seen.add(file)) continue;
            ProjectFile buffer = open.get(file);
            String source = buffer != null ? buffer.getContent() : read(file);
            if (source == null) continue;
            String rewritten = rewrite.apply(file, source);
            if (rewritten == null) continue;
            if (buffer != null) buffer.setContent(rewritten);
            write(file, rewritten);
        }
        // An open file outside the source root (a library source the user opened) is still worth rewriting if
        // it is one of ours; anything the walk already covered is skipped by `seen`.
        for (Map.Entry<Path, ProjectFile> entry : open.entrySet()) {
            if (entry.getKey().equals(generated) || !seen.add(entry.getKey())) continue;
            String rewritten = rewrite.apply(entry.getKey(), entry.getValue().getContent());
            if (rewritten == null) continue;
            entry.getValue().setContent(rewritten);
            if (Files.isRegularFile(entry.getKey())) write(entry.getKey(), rewritten);
        }
    }

    /** Every {@code .java} file under the project's source root, absolute and normalized. */
    private static List<Path> javaFiles(ProjectConfig config) {
        Path root = config.sourceRoot();
        if (root == null || !Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Couldn't list the project's sources: " + e.getMessage());
            return List.of();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return null;   // unreadable is not a use site; nothing to rewrite
        }
    }

    private static void write(Path file, String source) {
        try {
            Files.writeString(file, source);
        } catch (IOException e) {
            System.err.println("Couldn't update " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
