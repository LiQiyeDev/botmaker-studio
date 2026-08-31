package com.botmaker.studio.events;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.ui.dnd.DropInfo;
import com.botmaker.studio.ui.dnd.ExpressionDropInfo;
import com.botmaker.studio.ui.dnd.MoveBlockInfo;
import org.eclipse.lsp4j.Diagnostic;

import java.util.List;

public class CoreApplicationEvents {

    // --- Editing / UI ---

    /**
     * One file's text changed — and, for a change that spanned files, what it wrote to the others.
     *
     * <p>{@code newCode}/{@code previousCode} are the <em>active</em> file, the one the canvas is showing.
     * {@code alsoChanged} is every other file the same edit wrote, and it exists for one listener: history.
     * A signature migration rewrites the declaration here and its calls in three other files, and an undo that
     * only knows about this one puts back a call site that no longer matches. {@code label} is that change in
     * the words the Undo menu can show.
     */
    public record CodeUpdatedEvent(String newCode, String previousCode,
                                   boolean markNewIdentifiersAsUnedited,
                                   String label, List<FileEdit> alsoChanged) implements ApplicationEvent {
        public CodeUpdatedEvent {
            alsoChanged = alsoChanged != null ? List.copyOf(alsoChanged) : List.of();
        }

        /** A write nobody named, touching only the file it was published for — the open-migration path. */
        public CodeUpdatedEvent(String newCode, String previousCode) {
            this(newCode, previousCode, false, null, List.of());
        }
    }

    /** One file another file's edit also rewrote: what it said, and what it says now. */
    public record FileEdit(java.nio.file.Path path, String previousContent, String newContent) {}

    public record DiagnosticsUpdatedEvent(List<Diagnostic> diagnostics) implements ApplicationEvent {
        public DiagnosticsUpdatedEvent {
            diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        }
    }

    /** Published when a palette block is dropped onto a body/class drop zone (add a new block). */
    public record BlockDropRequestedEvent(DropInfo info) implements ApplicationEvent {}
    /** Published when an existing block is dropped onto a drop zone (reorder/move). */
    public record BlockMoveRequestedEvent(MoveBlockInfo info) implements ApplicationEvent {}
    /** Published when a block is dropped onto an expression slot (fill the slot, not the body). */
    public record ExpressionDropRequestedEvent(ExpressionDropInfo info) implements ApplicationEvent {}

    public record UIRefreshRequestedEvent(String code) implements ApplicationEvent {}
    public record BlockHighlightEvent(CodeBlock block) implements ApplicationEvent {}
    public record UIBlocksUpdatedEvent(AbstractCodeBlock rootBlock) implements ApplicationEvent {}
    public record BlockAddedEvent(BlockType blockType) implements ApplicationEvent {}

    /** Whether ↶/↷ are available, and what each would take back — the menu items say so by name. */
    public record HistoryStateChangedEvent(boolean canUndo, boolean canRedo,
                                           String undoLabel, String redoLabel) implements ApplicationEvent {}

    /** Published after the project's user libraries have been changed and the classpath re-resolved. */
    public record LibrariesChangedEvent(List<UserLibrary> libraries) implements ApplicationEvent {
        public LibrariesChangedEvent {
            libraries = libraries != null ? List.copyOf(libraries) : List.of();
        }
    }

    /**
     * Published after the project's activities (global config variables) have changed: {@code activities.json}
     * and the generated {@code Activities.java} have been rewritten and project state refreshed.
     */
    public record ActivitiesChangedEvent(com.botmaker.studio.project.activity.ActivitiesConfig config)
            implements ApplicationEvent {}

    /**
     * Published after the project's {@link com.botmaker.studio.project.StudioProjectSettings} change
     * (e.g. capture targets added/removed or the default target switched).
     */
    public record SettingsChangedEvent(com.botmaker.studio.project.StudioProjectSettings settings)
            implements ApplicationEvent {}

    // ResourcesChangedEvent was published after a picture was added, renamed or deleted, "so open template
    // pickers can refresh". It was deleted on 2026-08-31: it had four publishers and never a subscriber, and
    // no picker needed one — each re-reads the library when it opens its gallery.

    /** Request to open the Resource Manager dialog (e.g. from a block's image-template picker). */
    public record OpenResourceManagerEvent() implements ApplicationEvent {}

    /**
     * Request to close and re-open the current project from disk — used after a VCS rollback rewrites the
     * working tree, since the in-memory ASTs are now stale and would otherwise be written back over the
     * restored files on the next save. Handled by {@code BotMakerStudio}, which re-runs its open path.
     */
    public record ProjectReloadRequestedEvent() implements ApplicationEvent {}

    // --- Status / output ---

    public record StatusMessageEvent(String message) implements ApplicationEvent {}
    public record OutputAppendedEvent(String text) implements ApplicationEvent {}
    public record OutputClearedEvent() implements ApplicationEvent {}

    /**
     * A decoded telemetry frame from the running bot (a template match / click / search region), republished
     * from the {@code com.botmaker.shared.ipc} server for the live window-preview panel to draw as an overlay.
     */
    public record ViewFeedbackEvent(com.botmaker.shared.ipc.TelemetryEvent feedback) implements ApplicationEvent {}

    /**
     * Published when the running program signals it is blocking on stdin. {@code kind} is {@code null} when
     * the {@code BM-INPUT} marker named a read this Studio does not know — a bot built against a newer SDK —
     * in which case the prompt falls back to asking for plain text rather than not appearing at all.
     */
    public record InputRequestedEvent(com.botmaker.studio.palette.InputKind kind) implements ApplicationEvent {}
    /** Published by the UI to deliver a line of input to the running program's stdin. */
    public record SendInputEvent(String text) implements ApplicationEvent {}

    // --- Execution lifecycle ---

    public record CompilationRequestedEvent() implements ApplicationEvent {}
    public record ExecutionRequestedEvent() implements ApplicationEvent {}
    public record StopRunRequestedEvent() implements ApplicationEvent {}
    public record ProgramStartedEvent() implements ApplicationEvent {}
    public record ProgramStoppedEvent() implements ApplicationEvent {}

    // --- User history requests ---

    public record UndoRequestedEvent() implements ApplicationEvent {}
    public record RedoRequestedEvent() implements ApplicationEvent {}
    public record CopyRequestedEvent() implements ApplicationEvent {}
    public record PasteRequestedEvent() implements ApplicationEvent {}

    // --- Breakpoints ---

    public record BreakpointToggledEvent(CodeBlock block, boolean enabled) implements ApplicationEvent {}

    // --- Debug control requests (user-initiated). Subscribe to the family to receive all. ---

    public sealed interface DebugControlRequest extends ApplicationEvent
            permits DebugStartRequestedEvent, FollowStartRequestedEvent, DebugStepOverRequestedEvent,
                    DebugContinueRequestedEvent, DebugStopRequestedEvent {}

    public record DebugStartRequestedEvent() implements DebugControlRequest {}
    /**
     * Request to launch in "follow" (trace) mode: attach like debug but auto-resume past every executed
     * block, highlighting each live and never pausing at breakpoints. Handled by {@code DebuggingService}.
     */
    public record FollowStartRequestedEvent() implements DebugControlRequest {}
    public record DebugStepOverRequestedEvent() implements DebugControlRequest {}
    public record DebugContinueRequestedEvent() implements DebugControlRequest {}
    public record DebugStopRequestedEvent() implements DebugControlRequest {}

    // --- Debug session lifecycle (engine-initiated). Subscribe to the family to receive all. ---

    public sealed interface DebugSessionEvent extends ApplicationEvent
            permits DebugSessionStartedEvent, DebugSessionResumedEvent,
                    DebugSessionFinishedEvent, DebugSessionPausedEvent {}

    public record DebugSessionStartedEvent() implements DebugSessionEvent {}
    public record DebugSessionResumedEvent() implements DebugSessionEvent {}
    public record DebugSessionFinishedEvent() implements DebugSessionEvent {}
    public record DebugSessionPausedEvent(int lineNumber, CodeBlock block) implements DebugSessionEvent {}
}
