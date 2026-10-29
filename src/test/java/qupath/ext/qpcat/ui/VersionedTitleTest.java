package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionedTitleTest {

    @Test
    void appendsTheVersion() {
        assertThat(VersionedTitle.of("QPCAT - Run Clustering", "0.23.3"))
                .isEqualTo("QPCAT - Run Clustering  v. 0.23.3");
    }

    @Test
    void anUnpackagedRunKeepsTheBareTitle() {
        assertThat(VersionedTitle.of("QPCAT - Run Clustering", null)).isEqualTo("QPCAT - Run Clustering");
        assertThat(VersionedTitle.of("QPCAT - Run Clustering", "dev")).isEqualTo("QPCAT - Run Clustering");
    }
}
