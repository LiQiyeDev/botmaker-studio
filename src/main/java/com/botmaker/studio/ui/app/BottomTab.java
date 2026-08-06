package com.botmaker.studio.ui.app;

/**
 * The four tabs of the bottom tool window, in the order they are added.
 *
 * <p>A closed set rather than an {@code int}: the shell used to say {@code selectBottomTab(0)} for Terminal,
 * {@code selectBottomTab(1)} for Errors and carry a computed {@code vcsTabIndex} field for the third — three
 * spellings of one fixed list, none of which the compiler could check. The title lives here too, so the tab
 * and the thing that raises it can't drift apart.
 */
enum BottomTab {
    TERMINAL("Terminal"),
    ERRORS("Errors"),
    EVENT_LOG("Event Log"),
    VCS("VCS");

    private final String title;

    BottomTab(String title) {
        this.title = title;
    }

    String title() {
        return title;
    }
}
