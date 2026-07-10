package com.botmaker.studio.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local, single-branch git history for one user project, backed by JGit (no system git binary). Provides
 * commit / tag / rollback over a plain {@code .git} inside the project directory. <strong>Linear only</strong>
 * — there are no branches; rollback rewinds the working tree to an earlier commit's content as a new commit on
 * top of the current tip, so nothing is ever lost.
 *
 * <p>Blocking / does file + git I/O — intended to run off the FX thread (the VCS panel wraps calls in a
 * background task).
 */
public final class ProjectVcs {

    /** One entry in the project history; {@code tags} is the (possibly empty) list of tags pointing here. */
    public record CommitInfo(String sha, String shortSha, String message, String author, Instant when,
                             List<String> tags) {}

    private static final PersonIdent DEFAULT_AUTHOR =
            new PersonIdent("BotMaker Studio", "studio@botmaker.local");

    /** Top-level names git should ignore (mirrors {@code ProjectArchive}'s publish exclusions). */
    private static final List<String> GITIGNORE_LINES = List.of(
            "target/", ".idea/", ".gradle/", "build/", "out/", "*.class");

    private final Path projectDir;
    private final PersonIdent author;

    public ProjectVcs(Path projectDir) {
        this(projectDir, DEFAULT_AUTHOR);
    }

    /** Uses {@code name}/{@code email} (e.g. the signed-in GitHub identity) as the commit author. */
    public ProjectVcs(Path projectDir, String name, String email) {
        this(projectDir, new PersonIdent(
                name == null || name.isBlank() ? DEFAULT_AUTHOR.getName() : name,
                email == null || email.isBlank() ? DEFAULT_AUTHOR.getEmailAddress() : email));
    }

    private ProjectVcs(Path projectDir, PersonIdent author) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.author = author;
    }

    /** True when the project directory already has a {@code .git} repository. */
    public boolean isRepo() {
        return Files.isDirectory(projectDir.resolve(".git"));
    }

    /**
     * Initializes a repo (with a {@code .gitignore}) and makes the initial commit. No-op if a repo already
     * exists — call this on project creation, or lazily before any other VCS action for a pre-existing project.
     */
    public void init() throws IOException {
        if (isRepo()) return;
        try (Git git = Git.init().setDirectory(projectDir.toFile()).call()) {
            writeGitignore();
            git.add().addFilepattern(".").call();
            git.commit().setAuthor(author).setCommitter(author).setMessage("Initial commit").call();
        } catch (Exception e) {
            throw new IOException("Failed to initialize project history: " + e.getMessage(), e);
        }
    }

    /** Lazily initializes the repo (migrating an older project) if it isn't one yet. */
    public void ensureInitialized() throws IOException {
        if (!isRepo()) init();
    }

    /**
     * Stages every change (additions, modifications, deletions of tracked files) and commits it. Returns the
     * new commit's short SHA, or {@code null} when there was nothing to commit.
     */
    public String commit(String message) throws IOException {
        ensureInitialized();
        try (Git git = open()) {
            git.add().addFilepattern(".").call();           // new + modified files
            git.add().addFilepattern(".").setUpdate(true).call(); // deletions of tracked files
            if (git.status().call().isClean()) return null;
            RevCommit c = git.commit().setAuthor(author).setCommitter(author)
                    .setMessage(message == null || message.isBlank() ? "Update" : message).call();
            return c.abbreviate(7).name();
        } catch (Exception e) {
            throw new IOException("Commit failed: " + e.getMessage(), e);
        }
    }

    /** Newest-first commit history, each annotated with any tags pointing at it. */
    public List<CommitInfo> history() throws IOException {
        if (!isRepo()) return List.of();
        try (Git git = open()) {
            Map<String, List<String>> tagsByCommit = tagsByCommit(git);
            List<CommitInfo> out = new ArrayList<>();
            for (RevCommit c : git.log().call()) {
                String sha = c.name();
                out.add(new CommitInfo(sha, c.abbreviate(7).name(), c.getShortMessage(),
                        c.getAuthorIdent().getName(), Instant.ofEpochSecond(c.getCommitTime()),
                        tagsByCommit.getOrDefault(sha, List.of())));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not read project history: " + e.getMessage(), e);
        }
    }

    /** A private (local-only) tag — never triggers a gallery publish. */
    public void tagPrivate(String name) throws IOException {
        createTag(name, "Private tag " + name);
    }

    /**
     * A public tag — the caller (publish flow) treats this as the signal to submit to the gallery. The tag
     * itself is an ordinary annotated tag; the "public" distinction is the action taken alongside it.
     */
    public void tagPublic(String name) throws IOException {
        createTag(name, "Public release " + name);
    }

    /**
     * Safely rewinds the project to the state at {@code sha} without losing any history: any pending changes
     * are committed first (snapshot), then a new commit is created on top of the current tip whose content
     * exactly matches {@code sha}. Fully linear and reflog-safe.
     */
    public void restoreTo(String sha) throws IOException {
        ensureInitialized();
        try (Git git = open()) {
            Repository repo = git.getRepository();
            ObjectId target = repo.resolve(sha);
            if (target == null) throw new IOException("Unknown commit: " + sha);

            if (!git.status().call().isClean()) {
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setAuthor(author).setCommitter(author)
                        .setMessage("Snapshot before rollback").call();
            }
            ObjectId tip = repo.resolve("HEAD");
            String shortTarget = target.abbreviate(7).name();

            // Hard-reset the working tree/index to the target, then soft-reset HEAD back to the tip so the
            // recovered content lands as one new commit — the intervening commits stay reachable in reflog.
            git.reset().setMode(ResetType.HARD).setRef(target.name()).call();
            git.reset().setMode(ResetType.SOFT).setRef(tip.name()).call();
            if (!git.status().call().isClean()) {
                git.commit().setAuthor(author).setCommitter(author)
                        .setMessage("Roll back to " + shortTarget).call();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Rollback failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void createTag(String name, String message) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Tag name must not be empty.");
        ensureInitialized();
        try (Git git = open()) {
            git.tag().setName(name.trim()).setMessage(message).setTagger(author).call();
        } catch (Exception e) {
            throw new IOException("Tag failed: " + e.getMessage(), e);
        }
    }

    /** Maps each tagged commit SHA (peeled to the commit) to the tag short-names pointing at it. */
    private Map<String, List<String>> tagsByCommit(Git git) throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        Repository repo = git.getRepository();
        for (Ref tag : git.tagList().call()) {
            Ref peeled = repo.getRefDatabase().peel(tag);
            ObjectId id = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : tag.getObjectId();
            String shortName = Repository.shortenRefName(tag.getName());
            map.computeIfAbsent(id.name(), k -> new ArrayList<>()).add(shortName);
        }
        return map;
    }

    private Git open() throws IOException {
        Repository repo = new FileRepositoryBuilder()
                .setGitDir(projectDir.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .build();
        return new Git(repo);
    }

    private void writeGitignore() throws IOException {
        Path gitignore = projectDir.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        Files.writeString(gitignore, String.join(System.lineSeparator(), GITIGNORE_LINES)
                + System.lineSeparator());
    }
}
