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

    /** Where the rendered file lives, relative to the Studio repo root. */
    public static final Path FILE = Path.of("WORKFLOW.md");

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
        for (Workflow.Reference ref : Workflow.furtherReading()) {
            md.append("- [").append(ref.title()).append("](").append(ref.path()).append(") — ")
                    .append(ref.why()).append('\n');
        }
        return md.toString();
    }

    /** Regenerates {@link #FILE} in the working directory (run it from the Studio repo root). */
    public static void main(String[] args) throws Exception {
        Files.writeString(FILE, render(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + FILE.toAbsolutePath());
    }
}
