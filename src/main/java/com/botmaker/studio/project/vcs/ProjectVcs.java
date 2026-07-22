package com.botmaker.studio.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Local, single-branch git history for one user project, backed by JGit (no system git binary). Provides
 * commit / tag / rollback over a plain {@code .git} inside the project directory, plus an optional
 * {@code origin} remote for a plain backup push ({@link #remoteUrl()} / {@link #setRemote} / {@link #push}) —
 * pushing is <em>not</em> publishing: it copies the history to a (private) GitHub repo and touches neither the
 * gallery nor a release. <strong>Linear only</strong>
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

    /**
     * The working tree's changes relative to {@code HEAD}, bucketed by kind, as POSIX-style relative paths.
     * {@code added}/{@code modified}/{@code removed} are tracked changes staged or unstaged; {@code untracked}
     * is new files git isn't yet following. Empty sets when the tree is clean (or the project isn't a repo).
     */
    public record FileStatus(java.util.SortedSet<String> added, java.util.SortedSet<String> modified,
                             java.util.SortedSet<String> removed, java.util.SortedSet<String> untracked) {

        public boolean isClean() {
            return added.isEmpty() && modified.isEmpty() && removed.isEmpty() && untracked.isEmpty();
        }

        /** All changed paths, sorted, each mapped to its one-word status label. */
        public java.util.SortedMap<String, String> labelled() {
            java.util.SortedMap<String, String> out = new java.util.TreeMap<>();
            untracked.forEach(p -> out.put(p, "new"));
            added.forEach(p -> out.put(p, "added"));
            modified.forEach(p -> out.put(p, "modified"));
            removed.forEach(p -> out.put(p, "deleted"));
            return out;
        }

        public static FileStatus empty() {
            return new FileStatus(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        }
    }

    private static final PersonIdent DEFAULT_AUTHOR =
            new PersonIdent("BotMaker Studio", "studio@botmaker.local");

    /**
     * Names git should ignore. Mirrors {@code ProjectArchive}'s publish exclusions for build output, and adds
     * the local-only editor state {@code ProjectArchive} also excludes but that additionally shouldn't clutter
     * local history: the Reader/Editor opt-in marker. (Provenance, {@code botmaker-source.json}, is deliberately
     * left tracked — it is worth versioning so a rollback keeps a bot's origin.)
     */
    private static final List<String> GITIGNORE_LINES = List.of(
            "target/", ".idea/", ".gradle/", "build/", "out/", "*.class",
            com.botmaker.studio.project.ProjectMode.MARKER);

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

    /**
     * The working tree's changes against {@code HEAD}, bucketed for the VCS panel's changed-files tree. New
     * files added but not committed since {@code init} show up as {@code added}/{@code untracked}. Clean (and
     * empty) when the project has no repo yet.
     */
    public FileStatus status() throws IOException {
        if (!isRepo()) return FileStatus.empty();
        try (Git git = open()) {
            org.eclipse.jgit.api.Status s = git.status().call();
            java.util.SortedSet<String> added = new TreeSet<>(s.getAdded());
            added.addAll(s.getChanged());                 // staged modifications count as added-to-index
            java.util.SortedSet<String> modified = new TreeSet<>(s.getModified());
            java.util.SortedSet<String> removed = new TreeSet<>(s.getRemoved());
            removed.addAll(s.getMissing());               // tracked-but-deleted-on-disk
            java.util.SortedSet<String> untracked = new TreeSet<>(s.getUntracked());
            return new FileStatus(added, modified, removed, untracked);
        } catch (Exception e) {
            throw new IOException("Could not read project status: " + e.getMessage(), e);
        }
    }

    /**
     * A unified text diff of one working-tree file against {@code HEAD} (what committing it would record).
     * Returns an empty string when there is no difference, or a placeholder note for a binary change. The
     * path is the POSIX-style project-relative path from {@link #status()}.
     */
    public String diff(String relativePath) throws IOException {
        if (!isRepo()) return "";
        try (Git git = open(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter fmt = new DiffFormatter(out)) {
            Repository repo = git.getRepository();
            fmt.setRepository(repo);
            fmt.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(relativePath));

            ObjectId head = repo.resolve("HEAD^{tree}");
            List<DiffEntry> entries;
            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                if (head != null) oldTree.reset(reader, head);
                // HEAD tree (or an empty one for a repo with no commit) vs the working directory.
                entries = fmt.scan(head != null ? oldTree : new org.eclipse.jgit.treewalk.EmptyTreeIterator(),
                        new FileTreeIterator(repo));
            }
            for (DiffEntry e : entries) {
                fmt.format(e);
            }
            fmt.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Could not diff " + relativePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Discards a single file's uncommitted changes, restoring it to its {@code HEAD} content ({@code git
     * checkout -- path}). A far narrower undo than {@link #restoreTo}. No-op for an untracked file (there is
     * no committed version to restore — the caller deletes it instead if desired).
     */
    public void discard(String relativePath) throws IOException {
        ensureInitialized();
        try (Git git = open()) {
            git.checkout().addPath(relativePath).call();
        } catch (Exception e) {
            throw new IOException("Could not discard " + relativePath + ": " + e.getMessage(), e);
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
    // Remote (backup push — not publishing)
    // -------------------------------------------------------------------------

    /** The remote this project pushes to. Only one is ever configured, so it needs no name in the API. */
    private static final String ORIGIN = "origin";

    /** The configured {@code origin} URL, or {@code null} when the project has no remote (the default). */
    public String remoteUrl() {
        if (!isRepo()) return null;
        try (Git git = open()) {
            String url = git.getRepository().getConfig().getString("remote", ORIGIN, "url");
            return url == null || url.isBlank() ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    /** Points {@code origin} at {@code url} ({@code git remote add}/{@code set-url} in one call). */
    public void setRemote(String url) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("Remote URL must not be empty.");
        ensureInitialized();
        try (Git git = open()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", ORIGIN, "url", url.trim());
            config.setString("remote", ORIGIN, "fetch", "+refs/heads/*:refs/remotes/" + ORIGIN + "/*");
            config.save();
        } catch (Exception e) {
            throw new IOException("Could not set the remote: " + e.getMessage(), e);
        }
    }

    /**
     * Pushes the current branch and its tags to {@code origin}, authenticating with a GitHub token (used as
     * the HTTPS username, which is how GitHub accepts a PAT/OAuth token). Returns the branch that was pushed.
     *
     * <p>This is a <em>backup</em> push, deliberately non-forcing: a remote that has commits the project
     * doesn't is reported as such rather than being overwritten — that state means someone published or edited
     * on GitHub, and silently clobbering it is exactly what a backup must not do.
     */
    public String push(String token) throws IOException {
        if (token == null || token.isBlank()) throw new IOException("Not signed in to GitHub.");
        if (!isRepo()) throw new IOException("Nothing to push — this project has no history yet.");
        if (remoteUrl() == null) throw new IOException("This project has no 'origin' remote yet.");
        try (Git git = open()) {
            String branch = git.getRepository().getBranch();
            Iterable<PushResult> results = git.push()
                    .setRemote(ORIGIN)
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                    .setPushTags()
                    .setForce(false)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                    .call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    checkUpdate(update);
                }
            }
            return branch;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Push failed: " + e.getMessage(), e);
        }
    }

    /** Turns one ref's push outcome into a readable failure; a successful/no-op update passes silently. */
    private static void checkUpdate(RemoteRefUpdate update) throws IOException {
        switch (update.getStatus()) {
            case OK, UP_TO_DATE -> { /* pushed, or already there */ }
            case REJECTED_NONFASTFORWARD -> throw new IOException(
                    "The remote has commits this project doesn't — pull or publish instead of pushing.");
            case REJECTED_OTHER_REASON -> throw new IOException("Push rejected: "
                    + (update.getMessage() == null ? "the remote refused the update." : update.getMessage()));
            default -> throw new IOException("Push failed (" + update.getStatus() + ")"
                    + (update.getMessage() == null ? "." : ": " + update.getMessage()));
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
