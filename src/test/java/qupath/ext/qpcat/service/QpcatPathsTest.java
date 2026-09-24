package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link QpcatPaths}.
 * <p>
 * Everything QP-CAT writes belongs under one {@code qpcat/} folder. Cellular
 * neighborhoods used to write to a top-level {@code qpcat-cellular-neighborhoods/} and
 * name each run by eight characters of a UUID ("737ac095"), which said nothing about
 * when the run happened, over what, or with which settings.
 */
class QpcatPathsTest {

    @Test
    void everyOutputPathLivesUnderTheOneRoot() {
        for (String p : new String[] {
            QpcatPaths.CLUSTER_RESULTS,
            QpcatPaths.CLUSTER_CONFIGS,
            QpcatPaths.PHENOTYPE_RULES,
            QpcatPaths.SPATIAL_STATS,
            QpcatPaths.FIGURES,
            QpcatPaths.LOGS,
            QpcatPaths.CELLULAR_NEIGHBORHOODS,
            QpcatPaths.TEMP
        }) {
            assertThat(p).startsWith(QpcatPaths.ROOT + "/");
        }
    }

    @Test
    void aRunFolderSaysWhenAndWithWhatSettings() {
        assertThat(QpcatPaths.cnRunFolderName(LocalDateTime.of(2026, 6, 28, 17, 35, 0), 20, 10))
                .isEqualTo("cn_20260628_173500_k20_n10");
    }

    @Test
    void runFoldersSortChronologicallyByName() {
        String earlier = QpcatPaths.cnRunFolderName(LocalDateTime.of(2026, 6, 28, 17, 35, 0), 20, 10);
        String later = QpcatPaths.cnRunFolderName(LocalDateTime.of(2026, 6, 28, 17, 36, 0), 20, 10);
        assertThat(earlier).isLessThan(later);
    }

    @Test
    void theScratchFolderStaysHidden() {
        assertThat(QpcatPaths.TEMP).isEqualTo("qpcat/.temp");
    }
}
