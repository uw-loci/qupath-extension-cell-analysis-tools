package qupath.ext.qpcat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The Choose k sweep runs run_clustering.py's preparation, cut at one marker line.
 * python_tests/test_choose_k_prepared_matrix.py runs the result.
 */
class ChooseKPreparationTest {

    private static final Path RUN_SCRIPT =
            Path.of("src/main/resources/qupath/ext/qpcat/scripts/run_clustering.py");

    @Test
    void theShippedScriptIsCutBeforeTheEmbedding() throws IOException {
        String run = Files.readString(RUN_SCRIPT, StandardCharsets.UTF_8);
        String head = ApposeClusteringService.preparationOf(run);
        assertTrue(head.contains("cluster_matrix = df_norm.values"));
        assertTrue(head.contains("\"PCA precursor: %d features -> %d components"));
        assertFalse(head.contains("import knn_cache"));
        assertFalse(head.contains("import umap"));
    }

    @Test
    void aMissingOrRepeatedMarkerIsRefused() {
        String marker = ApposeClusteringService.END_OF_PREPARATION;
        assertThrows(IOException.class, () -> ApposeClusteringService.preparationOf("x = 1\n"));
        assertThrows(IOException.class, () -> ApposeClusteringService.preparationOf(
                "a\n" + marker + "\nb\n" + marker + "\n"));
    }

    @Test
    void theCutEndsAtTheMarker() throws IOException {
        String marker = ApposeClusteringService.END_OF_PREPARATION;
        assertEquals("a = 1\n",
                ApposeClusteringService.preparationOf("a = 1\n" + marker + "\nb = 2\n"));
    }
}
