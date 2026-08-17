package com.botmaker.studio.docs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders {@link Workflow} as the committed {@code WORKFLOW.md}.
 *
 * <p>The file is generated rather than written by hand so that it and the in-app walkthrough are the same
 * words. {@code WorkflowDocTest} re-renders and compares, so an edit to {@link Workflow} that isn't
 * regenerated fails the build; run {@link #main} to regenerate.
 */
public final class WorkflowMarkdown {

    /**
     * The rendered file's name, and its path <em>relative to the Studio repo root</em>.
     *
     * <p>It stays relative because the test that guards it runs with the module as its working directory and
     * has to find the committed copy there. {@link #main}, though, resolves it against {@link #repoRoot()}
     * rather than against wherever it was invoked: the umbrella repo has a {@code WORKFLOW.md} of its own — a
     * signpost pointing at this one — and regenerating from the umbrella root used to overwrite that signpost
     * with the whole guide.
     */
    public static final Path FILE = Path.of("WORKFLOW.md");

    /** The directory {@link #FILE} is resolved against — {@code botmaker-studio/}, wherever it is checked out. */
    private static final String MODULE_DIR = "botmaker-studio";

    private static final String GENERATED_NOTICE =
            "<!-- Generated from com.botmaker.studio.docs.Workflow — edit that class, not this file.\n"
            + "     Regenerate: mvn -q exec:java -Dexec.mainClass=com.botmaker.studio.docs.WorkflowMarkdown -->";

    private WorkflowMarkdown() {
    }

    public static String render() {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(Workflow.TITLE).append("\n\n");
        md.append(GENERATED_NOTICE).append("\n\n");
        md.append(Workflow.INTRO).append("\n");
        appendRuntimeDiagram(md);

        int number = 1;
        for (WorkflowStep step : Workflow.steps()) {
            md.append("\n## ").append(number++).append(". ").append(step.title()).append("\n\n");
            md.append("> ").append(step.summary()).append("\n");
            for (String paragraph : step.body()) {
                md.append('\n').append(paragraph).append('\n');
            }
            if (step.action() != null) {
                md.append("\n*In Studio:* ").append(step.action().menuPath()).append('\n');
            }
        }

        md.append("\n## Further reading\n\n");
        appendFurtherReading(md);
        return md.toString();
    }

    /**
     * The runtime as a Mermaid graph plus a legend. The legend is not decoration: GitHub draws the graph, but
     * a diff, a plain-text viewer and a screen reader all see this file as text, and the sentence explaining
     * each box is the only place they can get it.
     */
    private static void appendRuntimeDiagram(StringBuilder md) {
        md.append("\n## ").append(RuntimeDiagram.TITLE).append("\n\n");
        md.append(RuntimeDiagram.INTRO).append("\n\n");
        md.append("```mermaid\n").append(RuntimeDiagram.mermaid()).append("```\n");
        for (RuntimeDiagram.Node node : RuntimeDiagram.chain()) {
            md.append("\n- **").append(node.title()).append("** — ").append(node.detail()).append('\n');
        }
        RuntimeDiagram.Node guard = RuntimeDiagram.guard();
        md.append("\n- **").append(guard.title()).append("** (").append(RuntimeDiagram.GUARD_EDGE_LABEL)
                .append(") — ").append(guard.detail()).append('\n');
        md.append('\n').append(RuntimeDiagram.LOOP_NOTE).append('\n');
    }

    private static void appendFurtherReading(StringBuilder md) {
        for (Workflow.Reference ref : Workflow.furtherReading()) {
            md.append("- [").append(ref.title()).append("](").append(ref.path()).append(") — ")
                    .append(ref.why()).append('\n');
        }
    }

    /** Regenerates {@link #FILE}, wherever it is run from — see {@link #repoRoot()}. */
    public static void main(String[] args) throws Exception {
        Path target = repoRoot().resolve(FILE);
        Files.writeString(target, render(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + target.toAbsolutePath());
    }

    /**
     * The Studio repo root, whether this was run from there or from the umbrella root above it. Anything else
     * is left to the caller's working directory, which is the honest answer for a checkout laid out some third
     * way — and is what the old behaviour was everywhere.
     */
    static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        Path submodule = here.resolve(MODULE_DIR);
        return Files.isDirectory(submodule) && !Files.exists(here.resolve("src/main/java/com/botmaker/studio"))
                ? submodule
                : here;
    }
}
