package qupath.ext.qpcat.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #19: a radius run must be recorded as a radius, not as the unused k. */
class CnWindowTest {

    @Test
    void kWindowNamesK() {
        CnWindow window = new CnWindow(20, false, 50.0);
        assertThat(window.summary()).isEqualTo("k=20");
        assertThat(window.detail()).isEqualTo("k=20 nearest neighbors");
        assertThat(window.folderTag()).isEqualTo("k20");
    }

    @Test
    void radiusWindowNeverMentionsTheUnusedK() {
        CnWindow window = new CnWindow(20, true, 50.0);
        assertThat(window.summary()).isEqualTo("radius 50 um");
        assertThat(window.detail()).isEqualTo("every cell within 50 um");
        assertThat(window.folderTag()).isEqualTo("r50um");
    }

    @Test
    void aFractionalRadiusKeepsItsFraction() {
        assertThat(new CnWindow(20, true, 12.5).folderTag()).isEqualTo("r12.5um");
    }
}
