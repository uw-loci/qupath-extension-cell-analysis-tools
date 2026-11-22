package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The count of cells whose measurement row another cell shared (Leiden clusters
 * such rows once) survives the trip to disk, and a result saved before the
 * count existed reads back as zero, which is what "not reported" means.
 */
class DuplicateRowsNoteTest {

    private static final Gson GSON = new Gson();

    private static ClusteringResult result() {
        return new ClusteringResult(new int[]{0, 0, 1}, 2, null, new double[2][1], new String[]{"m"});
    }

    @Test
    void theCountIsWrittenAndReadBack() {
        ClusteringResult r = result();
        assertThat(r.getDuplicateRows()).isZero();
        r.setDuplicateRows(2903);
        ClusteringResult reread = GSON.fromJson(GSON.toJson(r), ClusteringResult.class);
        assertThat(reread.getDuplicateRows()).isEqualTo(2903);
    }

    @Test
    void aResultWithoutTheFieldReadsAsZero() {
        String saved = GSON.toJson(result()).replace("\"duplicateRows\":0,", "").replace(",\"duplicateRows\":0", "");
        assertThat(saved).doesNotContain("duplicateRows");
        assertThat(GSON.fromJson(saved, ClusteringResult.class).getDuplicateRows()).isZero();
    }
}
