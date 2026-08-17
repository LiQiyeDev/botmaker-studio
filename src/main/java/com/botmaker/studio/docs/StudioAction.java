package com.botmaker.studio.docs;

/**
 * A place in Studio a {@link WorkflowStep} can point at — the closed set of destinations the workflow guide
 * knows how to send someone to.
 *
 * <p>Each constant carries <b>both</b> renderings of "go here", because the guide has two audiences and they
 * cannot use the same one: the in-app dialog gets a {@link #buttonLabel()} for a real button that runs the
 * matching action, and {@code WORKFLOW.md} gets a {@link #menuPath()} — a reader of the file has no button to
 * press, only a menu to find. Keeping the pair on one constant is what stops the doc from naming a menu entry
 * the dialog no longer opens.
 *
 * <p>Typed rather than a {@code String} key (repo convention): a step that points at a destination Studio
 * cannot open is a compile error, not a dead button. The set is exactly the destinations the guide points at
 * — {@code WorkflowDocTest} fails on a constant no step uses — so a menu entry Studio has but the workflow
 * does not walk you through (Manage Libraries, for one) simply isn't here.
 */
public enum StudioAction {

    PROJECT_SETUP("Open Project Setup ▸", "Project ▸ Project Setup… (or 🧭 Setup on the toolbar)"),
    CAPTURE_TARGETS("Open Capture Targets ▸", "🎯 Capture Targets on the toolbar — it shows the current one"),
    LAUNCH_TARGET("Open Launch Target ▸", "🚀 on the toolbar — it shows the current target"),
    CAPTURE_TEMPLATES("Open Capture Templates ▸", "✂ Templates on the toolbar"),
    RESOURCES("Open Resource Manager ▸", "Project ▸ Resource Manager… (or 🗂 Resources on the toolbar)"),
    ACTIVITY_FLOW("Open Activity Flow ▸", "Project ▸ Activity Flow… (or 🔀 Flow on the toolbar)"),
    OVERLAY_EDITOR("Open Overlay Editor ▸", "⧉ Overlay on the toolbar, or F9 anywhere"),
    REMOTE_PILOT("Enable Remote Pilot ▸", "View ▸ Enable Remote Pilot… (or 🎮 Pilot on the toolbar)"),
    PUBLISH("Open Publish ▸", "Project ▸ Publish to Gallery…"),
    GALLERY("Open the Gallery ▸", "Project ▸ Browse Gallery…");

    private final String buttonLabel;
    private final String menuPath;

    StudioAction(String buttonLabel, String menuPath) {
        this.buttonLabel = buttonLabel;
        this.menuPath = menuPath;
    }

    /** What the in-app walkthrough's jump button says. */
    public String buttonLabel() {
        return buttonLabel;
    }

    /** Where a reader of {@code WORKFLOW.md} finds it by hand — no button available there. */
    public String menuPath() {
        return menuPath;
    }
}
