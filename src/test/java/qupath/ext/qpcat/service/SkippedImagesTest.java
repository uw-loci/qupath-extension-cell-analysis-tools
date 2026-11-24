package qupath.ext.qpcat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Images a project run could not read are named in its notes, not only in the log (H37). */
class SkippedImagesTest {

    private static final Logger LOG = LoggerFactory.getLogger(SkippedImagesTest.class);

    @Test
    void nothingSkippedNoNote() {
        SkippedImages s = new SkippedImages();
        assertThat(s.isEmpty()).isTrue();
        assertThat(s.note()).isNull();
        List<String> notes = List.of("a");
        assertThat(s.appendTo(notes)).isSameAs(notes);
    }

    @Test
    void eachImageIsNamedWithItsReason() {
        SkippedImages s = new SkippedImages();
        s.unreadable(LOG, "slide 1.svs", new IOException("File not found:\n  /data/slide 1.svs"));
        s.add(LOG, "slide 2.svs", "inference failed", new IllegalStateException());
        s.add(LOG, "slide 3.svs", "no object has every selected measurement", null);
        assertThat(s.size()).isEqualTo(3);
        assertThat(s.note()).isEqualTo("3 image(s) were left out of this run: "
                + "slide 1.svs (could not be read: File not found: /data/slide 1.svs); "
                + "slide 2.svs (inference failed: IllegalStateException); "
                + "slide 3.svs (no object has every selected measurement).");
        assertThat(s.appendTo(List.of("first"))).hasSize(2).first().isEqualTo("first");
        assertThat(s.appendTo(null)).hasSize(1);
    }

    @Test
    void longListsAreCut() {
        SkippedImages s = new SkippedImages();
        for (int i = 0; i < 13; i++) s.unreadable(LOG, "img" + i, new IOException("x"));
        assertThat(s.note()).startsWith("13 image(s)").contains("img9 (").doesNotContain("img10 (")
                .endsWith("; and 3 more.");
    }
}
