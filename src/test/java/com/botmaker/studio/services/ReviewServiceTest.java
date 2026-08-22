package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-back half of the review model: what a refactor wrote into the source, listed for the user, and
 * removed one entry at a time as they work through it.
 *
 * <p>What is pinned here is that the <b>source is the truth</b>. Nothing caches the list, so every assertion
 * below is about what the files say — including the two that matter most: an entry marked reviewed is gone
 * from the file rather than from a list in memory, and the open buffer and the file on disk never disagree
 * about it.
 */
class ReviewServiceTest {

    @Test
    void everyEntryOfEveryMarkedFunctionIsARow(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot;

                class Miner {
                    @NeedsReview({"the first thing", "the second thing"})
                    void mine() {}

                    void rest() {}

                    @NeedsReview("the third thing")
                    void haul() {}
                }
                """);

        List<ReviewService.Item> items = ReviewService.scan(config, null);

        assertEquals(3, items.size(), items.toString());
        assertEquals(List.of("mine", "mine", "haul"), items.stream().map(ReviewService.Item::function).toList());
        assertEquals(List.of("the first thing", "the second thing", "the third thing"),
                items.stream().map(ReviewService.Item::entry).toList());
        assertTrue(items.getFirst().where().startsWith("Miner.java · mine()"), items.getFirst().where());
    }

    @Test
    void aProjectNothingHasRewrittenListsNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", "class Miner { void mine() {} }\n");

        assertTrue(ReviewService.scan(config, null).isEmpty());
    }

    @Test
    void markingOneReviewedLeavesTheOthers(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot;

                class Miner {
                    @NeedsReview({"the first thing", "the second thing"})
                    void mine() {}
                }
                """);

        List<ReviewService.Item> items = ReviewService.scan(config, null);
        assertTrue(ReviewService.markReviewed(config, null, items.getFirst()));

        String source = Files.readString(config.sourceRoot().resolve("Miner.java"));
        assertFalse(source.contains("the first thing"), source);
        assertTrue(source.contains("the second thing"), source);
        assertEquals(1, ReviewService.scan(config, null).size());
    }

    /** The last entry takes the annotation with it — and its import, so nothing is left naming a dead type. */
    @Test
    void markingTheLastOneReviewedClearsTheFunction(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot.activities;

                import com.refbot.NeedsReview;

                class Miner {
                    @NeedsReview("the only thing")
                    void mine() {}
                }
                """);

        ReviewService.markReviewed(config, null, ReviewService.scan(config, null).getFirst());

        String source = Files.readString(config.sourceRoot().resolve("Miner.java"));
        assertFalse(source.contains("@NeedsReview"), source);
        assertFalse(source.contains("import com.refbot.NeedsReview;"), source);
        assertTrue(source.contains("void mine()"), "the function itself stays:\n" + source);
        assertTrue(ReviewService.scan(config, null).isEmpty());
    }

    /**
     * Two functions of the same name in one file: the entry text is what identifies a row, so the mark that
     * carries it is the one stripped.
     */
    @Test
    void theEntryIdentifiesTheMarkRatherThanTheFunctionName(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot;

                class Miner {
                    @NeedsReview("about the one with no inputs")
                    void mine() {}

                    @NeedsReview("about the one with a depth")
                    void mine(int depth) {}
                }
                """);

        ReviewService.Item second = ReviewService.scan(config, null).get(1);
        ReviewService.markReviewed(config, null, second);

        String source = Files.readString(config.sourceRoot().resolve("Miner.java"));
        assertTrue(source.contains("about the one with no inputs"), source);
        assertFalse(source.contains("about the one with a depth"), source);
    }

    /**
     * The user may have reverted the change through Project History, or edited the mark away by hand, since
     * the list was drawn. That is a re-scan, not an error.
     */
    @Test
    void anEntryThatIsNoLongerThereChangesNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Path file = write(config, "Miner.java", "class Miner { void mine() {} }\n");

        assertFalse(ReviewService.markReviewed(config, null,
                new ReviewService.Item(file, "mine", 1, "something that was never written")));
    }

    /** Same rule as every rewrite in Studio: the buffer is the truth, and the disk is kept equal to it. */
    @Test
    void theOpenBufferIsStrippedTogetherWithTheFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Path file = write(config, "Miner.java", "class Miner { void mine() {} }\n");
        ProjectState state = new ProjectState();
        ProjectFile open = new ProjectFile(file, """
                class Miner {
                    @NeedsReview("look at this")
                    void mine() {}
                }
                """);
        state.addFile(open);

        List<ReviewService.Item> items = ReviewService.scan(config, state);
        assertEquals(1, items.size(), "the buffer's mark is the one that counts, not the stale disk copy");

        ReviewService.markReviewed(config, state, items.getFirst());

        assertFalse(open.getContent().contains("@NeedsReview"), open.getContent());
        assertEquals(open.getContent(), Files.readString(file));
    }

    private static Path write(ProjectConfig config, String name, String source) throws IOException {
        Path file = config.sourceRoot().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file.toAbsolutePath().normalize();
    }

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("refbot", root);
        Files.createDirectories(config.sourceRoot());
        return config;
    }
}
