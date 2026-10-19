package qupath.ext.qpcat.service;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.model.SavedClusteringResult;
import qupath.ext.qpcat.service.ClusteringResultManager.ResultEntry;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code listResultEntries} orders by each result's own timestamp (mtime only as
 * the fallback), and re-reads a file only when its mtime or size changed.
 */
class ClusteringResultManagerListingTest {

    @TempDir
    Path dir;

    private Project<BufferedImage> project;
    private Path results;

    @BeforeEach
    void setUp() throws Exception {
        project = Projects.createProject(dir.toFile(), BufferedImage.class);
        results = dir.resolve(QpcatPaths.CLUSTER_RESULTS);
        Files.createDirectories(results);
    }

    private void write(String name, String timestamp, String summaryAlgorithm, long mtimeMillis) throws Exception {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setName(name);
        saved.setTimestamp(timestamp);
        saved.setAlgorithm(summaryAlgorithm);
        saved.setNClusters(2);
        Path file = results.resolve(name + ".json");
        Files.writeString(file, new Gson().toJson(saved));
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMillis));
    }

    @Test
    void orderIsByTheSavedTimestampNewestFirstWhateverTheFileTimes() throws Exception {
        long now = System.currentTimeMillis();
        write("a_oldest_touched_last", "2026-10-09T21:50:01.043321512", "kmeans", now + 30_000);
        write("b_newest_touched_first", "2026-10-11T04:18:55.993515823", "kmeans", now - 30_000);
        write("c_middle", "2026-10-10T12:39:17", "leiden", now);
        List<ResultEntry> entries = ClusteringResultManager.listResultEntries(project);
        assertThat(entries).extracting(e -> e.name)
                .containsExactly("b_newest_touched_first", "c_middle", "a_oldest_touched_last");
        // listResults (names only) keeps its file-time order for its own callers
        assertThat(ClusteringResultManager.listResults(project))
                .containsExactly("a_oldest_touched_last", "c_middle", "b_newest_touched_first");
    }

    @Test
    void aResultWithoutATimestampFallsBackToItsFileTime() throws Exception {
        long now = System.currentTimeMillis();
        write("stamped", "2026-10-10T12:00:00", "kmeans", now - 60_000);
        Files.writeString(results.resolve("unstamped.json"), "{\"name\":\"unstamped\",\"nClusters\":1}");
        Files.setLastModifiedTime(results.resolve("unstamped.json"), FileTime.fromMillis(now + 60_000));
        assertThat(ClusteringResultManager.listResultEntries(project)).extracting(e -> e.name)
                .containsExactly("unstamped", "stamped");
        Files.setLastModifiedTime(results.resolve("unstamped.json"), FileTime.fromMillis(0));
        assertThat(ClusteringResultManager.listResultEntries(project)).extracting(e -> e.name)
                .containsExactly("stamped", "unstamped");
    }

    @Test
    void unchangedFilesAreServedFromTheCacheAndChangedOnesReRead() throws Exception {
        long now = System.currentTimeMillis();
        write("r", "2026-10-10T12:00:00", "kmeans", now - 60_000);
        ResultEntry first = ClusteringResultManager.listResultEntries(project).get(0);
        assertThat(first.summary).contains("kmeans");

        // Same mtime and size, different content: the cache (deliberately) still answers.
        Path file = results.resolve("r.json");
        String json = Files.readString(file).replace("kmeans", "leiden");
        Files.writeString(file, json);
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - 60_000));
        assertThat(ClusteringResultManager.listResultEntries(project).get(0).summary).contains("kmeans");

        // A changed mtime invalidates it.
        Files.setLastModifiedTime(file, FileTime.fromMillis(now));
        assertThat(ClusteringResultManager.listResultEntries(project).get(0).summary).contains("leiden");

        // Callers get their own copy: mutating it does not poison the cache.
        ResultEntry mine = ClusteringResultManager.listResultEntries(project).get(0);
        mine.summary = "scribbled";
        assertThat(ClusteringResultManager.listResultEntries(project).get(0).summary).contains("leiden");

        // A deleted file leaves the listing (and the cache).
        Files.delete(file);
        assertThat(ClusteringResultManager.listResultEntries(project)).isEmpty();
    }

    @Test
    void timestampParsingAcceptsLocalAndOffsetFormsAndRejectsJunk() {
        assertThat(ClusteringResultManager.parseTimestampMillis("2026-10-11T04:18:55.993515823")).isNotNull();
        assertThat(ClusteringResultManager.parseTimestampMillis("2026-10-11T04:18")).isNotNull();
        assertThat(ClusteringResultManager.parseTimestampMillis("2026-10-11T04:18:55+02:00")).isNotNull();
        assertThat(ClusteringResultManager.parseTimestampMillis("yesterday")).isNull();
        assertThat(ClusteringResultManager.parseTimestampMillis(null)).isNull();
        assertThat(ClusteringResultManager.parseTimestampMillis("")).isNull();
    }

    @Test
    void theListSaysNoiseRatherThanCountingItAsACluster() throws Exception {
        SavedClusteringResult saved = new SavedClusteringResult();
        saved.setName("hdb");
        saved.setTimestamp("2026-10-15T10:00:00");
        saved.setAlgorithm("hdbscan");
        saved.setNClusters(6);
        saved.setNCells(4197);
        saved.setNoiseRowIndex(5);
        saved.setNNoiseCells(606);
        Files.writeString(results.resolve("hdb.json"), new Gson().toJson(saved));
        // View Past Results reads the scalar-only listing; Manage reads the entries.
        assertThat(ClusteringResultManager.listResultSummaries(project).get("hdb"))
                .contains("5 clusters (+606 noise), 4197 cells");
        assertThat(ClusteringResultManager.listResultEntries(project))
                .extracting(e -> e.summary)
                .singleElement().asString()
                .startsWith("5 clusters (+606 noise), 4197 cells");
    }
}
