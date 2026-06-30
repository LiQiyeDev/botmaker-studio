package com.botmaker.events;

import com.botmaker.core.AbstractCodeBlock;
import com.botmaker.core.CodeBlock;
import com.botmaker.project.UserLibrary;
import com.botmaker.palette.BlockType;
import com.botmaker.ui.dnd.DropInfo;
import com.botmaker.ui.dnd.MoveBlockInfo;
import org.eclipse.lsp4j.Diagnostic;

import java.util.List;

public class CoreApplicationEvents {

    // --- Editing / UI ---

    public record CodeUpdatedEvent(String newCode, String previousCode,
                                   boolean markNewIdentifiersAsUnedited) implements ApplicationEvent {
        public CodeUpdatedEvent(String newCode, String previousCode) {
            this(newCode, previousCode, false);
        }
    }

    public record DiagnosticsUpdatedEvent(List<Diagnostic> diagnostics) implements ApplicationEvent {
        public DiagnosticsUpdatedEvent {
            diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        }
    }

    /** Published when a palette block is dropped onto a body/class drop zone (add a new block). */
    public record BlockDropRequestedEvent(DropInfo info) implements ApplicationEvent {}
    /** Published when an existing block is dropped onto a drop zone (reorder/move). */
    public record BlockMoveRequestedEvent(MoveBlockInfo info) implements ApplicationEvent {}

    public record UIRefreshRequestedEvent(String code) implements ApplicationEvent {}
    public record BlockHighlightEvent(CodeBlock block) implements ApplicationEvent {}
    public record UIBlocksUpdatedEvent(AbstractCodeBlock rootBlock) implements ApplicationEvent {}
    public record BlockAddedEvent(BlockType blockType) implements ApplicationEvent {}

    public record HistoryStateChangedEvent(boolean canUndo, boolean canRedo) implements ApplicationEvent {}

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
    public record ActivitiesChangedEvent(com.botmaker.project.activity.ActivitiesConfig config)
            implements ApplicationEvent {}

    /** Published after the project's saved image templates change (added / renamed / deleted). */
    public record ResourcesChangedEvent() implements ApplicationEvent {}

    /** Request to open the Resource Manager dialog (e.g. from a block's image-template picker). */
    public record OpenResourceManagerEvent() implements ApplicationEvent {}

    // --- Status / output ---

    public record StatusMessageEvent(String message) implements ApplicationEvent {}
    public record OutputAppendedEvent(String text) implements ApplicationEvent {}
    public record OutputClearedEvent() implements ApplicationEvent {}

    /** Published when the running program signals it is blocking on stdin; {@code type} is e.g. {@code int}. */
    public record InputRequestedEvent(String type) implements ApplicationEvent {}
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
            permits DebugStartRequestedEvent, DebugStepOverRequestedEvent,
                    DebugContinueRequestedEvent, DebugStopRequestedEvent {}

    public record DebugStartRequestedEvent() implements DebugControlRequest {}
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
