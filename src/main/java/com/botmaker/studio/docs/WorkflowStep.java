package com.botmaker.studio.docs;

import java.util.List;

/**
 * One step of the BotMaker workflow: a heading, a one-line summary, the paragraphs that explain it, and
 * optionally the place in Studio it sends you.
 *
 * <p>Deliberately free of any rendering: {@link com.botmaker.studio.ui.app.GettingStartedDialog} turns it into
 * JavaFX and {@link WorkflowMarkdown} turns it into Markdown, so neither can hold prose the other lacks.
 *
 * @param title      the heading, without a number — the renderers number the list themselves
 * @param summary    one sentence, shown under the heading in both renderings
 * @param body       the explanation, one entry per paragraph; may be empty when the summary says it all
 * @param action     where in Studio this step happens, or {@code null} when it is about the canvas you are
 *                   already looking at
 */
public record WorkflowStep(String title, String summary, List<String> body, StudioAction action) {

    public WorkflowStep {
        body = body == null ? List.of() : List.copyOf(body);
    }

    public static WorkflowStep of(String title, String summary, StudioAction action, String... body) {
        return new WorkflowStep(title, summary, List.of(body), action);
    }
}
