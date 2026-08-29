package com.botmaker.studio.project;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * A published bot used as a <b>starting template</b>: what it declares about itself, and the one thing that
 * changes when a copy of it becomes the user's own project.
 *
 * <h2>Why a template is a published bot</h2>
 *
 * <p>Studio composes exactly one starting point — a blank project, small enough to be honest about owning —
 * and every richer one is somebody's published project, downloaded from the gallery. A template therefore
 * needs no archetype, no bundled resource and no Studio release: it is a bot repo whose gallery entry carries
 * {@link com.botmaker.studio.sharing.GalleryEntry#TEMPLATE_TAG}, installed through the same path an ordinary
 * bot is.
 *
 * <h2>The project arrives as its author shipped it, except for its package</h2>
 *
 * <p>One {@value #FILE_NAME} at the project root, one key:
 *
 * <pre>
 * package=com.botmaker.gamebot
 * </pre>
 *
 * <p>That prefix is replaced with the user's own — {@code com.myfarmer} — and the directories move with it.
 * <b>Nothing else is renamed.</b> A template's entry class stays {@code GameBot}, its helper classes keep
 * their names, and its javadoc keeps its wording: what the author shipped is what demonstrably built for
 * them, and a copy that quietly renames their types is a copy whose stack traces and README stop matching.
 * The package is the exception because it is the one name that must not be shared — two projects in one
 * package cannot sit on one classpath, and a package named after somebody else's bot is the first thing a
 * user reads in their own file.
 *
 * <p>It is declared rather than guessed because a package prefix cannot be read off a directory tree without
 * deciding which of {@code com}, {@code com.botmaker} and {@code com.botmaker.gamebot} was meant. The author
 * knows, and says so in one line.
 *
 * <p>Since the entry class keeps the author's name, nothing may assume it is named after the project — see
 * {@link ProjectConfig#entrySourceFile()}, which finds it rather than deriving it.
 */
public final class TemplateProject {

    /** The declaration file, at the template project's root. */
    public static final String FILE_NAME = "botmaker-template.properties";

    private static final String KEY_PACKAGE = "package";

    /** Files whose bytes are not text and must be copied through untouched. */
    private static final List<String> BINARY_SUFFIXES =
            List.of(".png", ".jpg", ".jpeg", ".gif", ".ico", ".zip", ".jar", ".class", ".pdf");

    private final String packageName;

    private TemplateProject(String packageName) {
        this.packageName = packageName;
    }

    public String packageName() {
        return packageName;
    }

    /**
     * Reads {@value #FILE_NAME} from {@code projectDir}.
     *
     * @throws IOException if the file is missing or the key is blank — a template that does not say what its
     *                     package is cannot have it replaced, and a replacement that silently matches nothing
     *                     produces a project sitting in somebody else's package that still compiles, which is
     *                     the one failure worth refusing outright
     */
    public static TemplateProject read(Path projectDir) throws IOException {
        Path file = projectDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            throw new IOException("This template has no " + FILE_NAME + ", so BotMaker can't tell which "
                    + "package to rename. Ask its author to add one.");
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        }
        String pkg = properties.getProperty(KEY_PACKAGE, "").trim();
        if (pkg.isBlank()) {
            throw new IOException(FILE_NAME + " must set " + KEY_PACKAGE + ".");
        }
        return new TemplateProject(pkg);
    }

    /**
     * True when this declaration matches what is actually in {@code projectDir} — there are sources in the
     * declared package. Checked before publishing, where the author can still fix it.
     */
    public boolean matches(Path projectDir) {
        return Files.isDirectory(projectDir.resolve("src/main/java").resolve(packageName.replace('.', '/')));
    }

    /**
     * Rewrites {@code projectDir} in place so it lives in {@code newPackage}, and removes the declaration
     * file. The unpacked copy is the user's from here on.
     *
     * <p>The prefix is replaced everywhere in every text file, string literals included. That is right often
     * enough (a logger category, a resource path, a {@code Class.forName}) and harmless where it is not —
     * this is a prefix substitution and must not grow into a refactoring engine.
     */
    public void renameInto(Path projectDir, String newPackage) throws IOException {
        Path sources = projectDir.resolve("src/main/java").resolve(packageName.replace('.', '/'));
        if (!Files.isDirectory(sources)) {
            throw new IOException("This template declares package " + packageName
                    + ", but there are no sources in it. Ask its author to fix its " + FILE_NAME + ".");
        }

        // Text first, then the moves. Rewriting after the move would mean walking a tree whose shape has
        // already changed, and a half-moved tree is the state that is hardest to recover from.
        rewriteText(projectDir, newPackage);
        movePackage(projectDir, newPackage);
        Files.deleteIfExists(projectDir.resolve(FILE_NAME));
    }

    private void rewriteText(Path projectDir, String newPackage) throws IOException {
        for (Path file : textFilesUnder(projectDir)) {
            String before;
            try {
                before = Files.readString(file);
            } catch (MalformedInputException notText) {
                continue;   // a binary file with an unexpected extension: leave it exactly as it is
            }
            String after = before.replace(packageName, newPackage);
            if (!after.equals(before)) Files.writeString(file, after);
        }
    }

    /** Moves {@code src/**}{@code /<old package dirs>} to the new package's directories. */
    private void movePackage(Path projectDir, String newPackage) throws IOException {
        for (String root : List.of("src/main/java", "src/test/java")) {
            Path from = projectDir.resolve(root).resolve(packageName.replace('.', '/'));
            if (!Files.isDirectory(from)) continue;
            Path to = projectDir.resolve(root).resolve(newPackage.replace('.', '/'));
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            pruneEmptyDirectories(projectDir.resolve(root), to);
        }
    }

    /** Removes the now-empty {@code com/botmaker/…} shells the move left behind. */
    private static void pruneEmptyDirectories(Path root, Path keep) throws IOException {
        if (!Files.isDirectory(root)) return;
        List<Path> directories;
        try (var walk = Files.walk(root)) {
            directories = walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root) && !keep.startsWith(p))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path directory : directories) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) Files.delete(directory);
            }
        }
    }

    private static List<Path> textFilesUnder(Path projectDir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(projectDir)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase();
                if (BINARY_SUFFIXES.stream().noneMatch(name::endsWith)) out.add(path);
            }
        }
        return out;
    }
}
