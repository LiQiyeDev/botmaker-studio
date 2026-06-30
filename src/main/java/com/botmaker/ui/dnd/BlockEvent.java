package com.botmaker.ui.dnd;

import com.botmaker.core.CodeBlock;
import javafx.event.Event;
import javafx.event.EventType;

public class BlockEvent extends Event {
    public static final EventType<BlockEvent> ANY = new EventType<>(Event.ANY, "BLOCK_EVENT");

    public BlockEvent(EventType<? extends Event> eventType) {
        super(eventType);
    }

    public static class BreakpointToggleEvent extends BlockEvent {
        public static final EventType<BreakpointToggleEvent> TOGGLE_BREAKPOINT = new EventType<>(ANY, "TOGGLE_BREAKPOINT");

        private final CodeBlock block;
        private final boolean isEnabled;

        public BreakpointToggleEvent(CodeBlock block, boolean isEnabled) {
            super(TOGGLE_BREAKPOINT);
            this.block = block;
            this.isEnabled = isEnabled;
        }

        public CodeBlock getBlock() { return block; }
        public boolean isEnabled() { return isEnabled; }
    }
}