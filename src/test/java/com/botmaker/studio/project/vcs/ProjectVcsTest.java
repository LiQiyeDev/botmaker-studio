package com.botmaker.studio.project.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectVcsTest {

    @Test
    void initCreatesRepoWithInitialCommitAndGitignore(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        ProjectVcs vcs = new ProjectVcs(dir);
        assertFalse(vcs.isRepo());

        vcs.init();

        assertTrue(vcs.isRepo());
        assertTrue(Files.exists(dir.resolve(".gitignore")));
        List<ProjectVcs.CommitInfo> history = vcs.history();
        assertEquals(1, history.size());
        assertEquals("Initial commit", history.get(0).message());
    }

    @Test
    void commitRecordsChangesAndNoOpsWhenClean(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.init();

        Files.writeString(dir.resolve("Bot.java"), "class Bot { int a; }");
        assertTrue(vcs.commit("add field") != null, "a real change should produce a commit");
        assertEquals(2, vcs.history().size());

        assertNull(vcs.commit("nothing changed"), "a clean tree should not create a commit");
        assertEquals(2, vcs.history().size());
    }

    @Test
    void ensureInitializedMigratesExistingProject(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.ensureInitialized();
        assertTrue(vcs.isRepo());
        assertEquals(1, vcs.history().size());
    }

    @Test
    void tagsAreSurfacedInHistory(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.init();
        vcs.tagPrivate("v0.0.1");
        vcs.tagPublic("v1.0.0");

        List<String> tags = vcs.history().get(0).tags();
        assertTrue(tags.contains("v0.0.1"));
        assertTrue(tags.contains("v1.0.0"));
    }

    @Test
    void statusBucketsWorkingTreeChanges(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "v1");
        vcs.init();
        assertTrue(vcs.status().isClean(), "a freshly committed tree is clean");

        Files.writeString(dir.resolve("Bot.java"), "v2");           // modify a tracked file
        Files.writeString(dir.resolve("New.java"), "brand new");    // untracked

        ProjectVcs.FileStatus status = vcs.status();
        assertFalse(status.isClean());
        assertTrue(status.modified().contains("Bot.java"), "modified: " + status.modified());
        assertTrue(status.untracked().contains("New.java"), "untracked: " + status.untracked());
        assertEquals("modified", status.labelled().get("Bot.java"));
        assertEquals("new", status.labelled().get("New.java"));
    }

    @Test
    void diffShowsTrackedFileChangeAndDiscardReverts(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "original\n");
        vcs.init();

        Files.writeString(dir.resolve("Bot.java"), "changed\n");
        String diff = vcs.diff("Bot.java");
        assertTrue(diff.contains("-original"), "diff should show the removed line:\n" + diff);
        assertTrue(diff.contains("+changed"), "diff should show the added line:\n" + diff);

        vcs.discard("Bot.java");
        assertEquals("original\n", Files.readString(dir.resolve("Bot.java")));
        assertTrue(vcs.status().isClean(), "discarding the only change makes the tree clean again");
    }

    @Test
    void restoreToRewindsContentAsNewCommitKeepingHistory(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "v1");
        vcs.init();
        String firstSha = vcs.history().get(0).sha();

        Files.writeString(dir.resolve("Bot.java"), "v2");
        Files.writeString(dir.resolve("New.java"), "added later");
        vcs.commit("v2 + new file");
        assertEquals(2, vcs.history().size());

        vcs.restoreTo(firstSha);

        // Working tree matches the first commit's content again…
        assertEquals("v1", Files.readString(dir.resolve("Bot.java")));
        assertFalse(Files.exists(dir.resolve("New.java")), "file added after the target should be gone");
        // …and history grew rather than shrank (nothing lost).
        List<ProjectVcs.CommitInfo> history = vcs.history();
        assertEquals(3, history.size());
        assertTrue(history.get(0).message().startsWith("Roll back to"));
    }
}
