package com.botmaker.studio.docs;

import java.util.List;

/**
 * <b>How a bot actually runs</b> — the generated scaffolding's control flow, as a diagram rather than a
 * paragraph.
 *
 * <p>This is the one thing the workflow guide could not explain in prose. Every other step is a thing you
 * <em>do</em>, in an order, and a numbered list is the right shape for that. The runtime is not a list: it is a
 * loop with a side channel, and describing "the driver runs the current activity, then picks the next one from
 * the outcome it reported, unless the popup guard fires first" in a sentence is exactly how people came away
 * believing their activities run top to bottom, once each.
 *
 * <p>Like {@link Workflow}, the words live here and only here — {@link WorkflowMarkdown} renders them as a
 * Mermaid graph (which GitHub draws) and
 * {@link com.botmaker.studio.ui.app.GettingStartedDialog} as a column of boxes. Neither renderer holds any
 * text of its own, so the drawn diagram and the committed one cannot disagree.
 *
 * <p><b>The model is deliberately a straight chain plus two annotations</b> (a loop-back edge and one aside),
 * not a general graph. That is genuinely the runtime's shape, and it is what lets a renderer with no layout
 * engine — a {@code VBox} with arrows between the rows — draw it faithfully rather than approximately.
 */
public final class RuntimeDiagram {

    private RuntimeDiagram() {
    }

    public static final String TITLE = "How a bot runs";

    public static final String INTRO =
            "Your activities are not a script that runs top to bottom. The generated FlowDriver holds one "
            + "current activity, runs it, and asks the flow graph what follows the outcome it reported — so "
            + "the shape of a run is a loop, and it is the graph you drew that decides where it goes next.";

    /**
     * One box in the chain.
     *
     * @param id     the Mermaid node id — short, stable, and never shown to a reader
     * @param title  what the box says
     * @param detail one sentence under it, in the dialog and in the doc's legend
     * @param shape  how the box is drawn
     */
    public record Node(String id, String title, String detail, Shape shape) {}

    /** A box's outline — the three roles the runtime has: an endpoint, a step, and the branching driver. */
    public enum Shape {
        /** A rounded terminal: where a run begins, and the value that comes back out of an activity. */
        TERMINAL("([", "])"),
        /** An ordinary step that does something. */
        STEP("[", "]"),
        /** The decision — the only node with more than one way out. */
        DECISION("{{", "}}");

        private final String open;
        private final String close;

        Shape(String open, String close) {
            this.open = open;
            this.close = close;
        }

        /** The Mermaid delimiters around the label. */
        String wrap(String label) {
            return open + '"' + label + '"' + close;
        }
    }

    /** The run, top to bottom. The last node loops back to {@link #loopTarget()} — see {@link #LOOP_NOTE}. */
    public static List<Node> chain() {
        return List.of(
                new Node("start", "main() — your bot class",
                        "Installs the popup guard and hands control to Bot.start, which supervises the whole "
                        + "run and restarts the game through GoHome if it crashes or gets stuck.",
                        Shape.TERMINAL),
                new Node("launch", "Launch target",
                        "The game or app you declared is started if it isn't already running. Nothing is "
                        + "captured or clicked until it is up.",
                        Shape.STEP),
                new Node("driver", "FlowDriver — which activity now?",
                        "The current node of the flow graph. This is the only place that decides what runs "
                        + "next; an activity never calls another activity.",
                        Shape.DECISION),
                new Node("run", "That activity's run()",
                        "The blocks you authored: capture, match, click, wait. A disabled activity is stepped "
                        + "over here, following the wire it would have taken.",
                        Shape.STEP),
                new Node("outcome", "The outcome it returns",
                        "One of the activity's own named outcomes — the label on the wire leaving it in the "
                        + "flow editor.",
                        Shape.TERMINAL));
    }

    /** The id the last node of the {@link #chain()} goes back to, closing the loop. */
    public static String loopTarget() {
        return "driver";
    }

    public static final String LOOP_NOTE =
            "The driver follows the wire leaving that outcome and runs whatever is on the other end — for as "
            + "long as there is one. A run ends when the outcome it reported has no wire leaving it: an "
            + "unwired outcome is the stop, and there is no terminal node to draw.";

    /** The side channel: the popup guard, which interrupts vision rather than sitting in the chain. */
    public static Node guard() {
        return new Node("guard", "Popup guard",
                "Popups.run() is called before every vision step, whichever activity is running — so a daily "
                + "reward covering the button is dismissed by one file instead of by every activity that "
                + "might trip over it.",
                Shape.STEP);
    }

    /** Which node of the chain the guard attaches to, and the label on that edge. */
    public static String guardAttachesTo() {
        return "run";
    }

    public static final String GUARD_EDGE_LABEL = "before every vision step";

    /**
     * The whole diagram as a Mermaid {@code flowchart}, generated from the model above so a node added here
     * appears in the doc without anyone editing Markdown.
     */
    public static String mermaid() {
        List<Node> chain = chain();
        StringBuilder out = new StringBuilder("flowchart TD\n");
        for (Node node : chain) {
            out.append("    ").append(node.id()).append(node.shape().wrap(node.title())).append('\n');
        }
        for (int i = 0; i < chain.size() - 1; i++) {
            out.append("    ").append(chain.get(i).id()).append(" --> ").append(chain.get(i + 1).id()).append('\n');
        }
        out.append("    ").append(chain.getLast().id()).append(" -- \"the wire you drew\" --> ")
                .append(loopTarget()).append('\n');
        Node guard = guard();
        out.append("    ").append(guard.id()).append(guard.shape().wrap(guard.title())).append('\n');
        out.append("    ").append(guard.id()).append(" -. \"").append(GUARD_EDGE_LABEL).append("\" .-> ")
                .append(guardAttachesTo()).append('\n');
        return out.toString();
    }
}
