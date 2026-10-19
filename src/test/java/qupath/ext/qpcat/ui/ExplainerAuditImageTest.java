package qupath.ext.qpcat.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.ClusteringResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit log's Image row names the images the result was clustered over,
 * not whichever image happens to be open in the viewer.
 */
class ExplainerAuditImageTest {

    private static ClusteringResult over(String... images) {
        ClusteringResult r = new ClusteringResult(
                new int[images.length], 1, null, null, new String[]{"m"});
        CellRef[] refs = new CellRef[images.length];
        for (int i = 0; i < images.length; i++) {
            refs[i] = new CellRef("id-" + images[i], images[i], "o" + i, 0, 0, 1);
        }
        r.setCellRefs(refs);
        return r;
    }

    @Test
    void aMultiImageResultIsCountedNotNamedAfterOneImage() {
        assertThat(ClusterExplainerPanel.resultImages(over("tme_00", "tme_01", "tme_00")))
                .isEqualTo("2 images");
    }

    @Test
    void aSingleImageResultIsNamed() {
        assertThat(ClusterExplainerPanel.resultImages(over("tme_03", "tme_03")))
                .isEqualTo("tme_03");
    }

    @Test
    void aResultWithoutImageReferencesFallsBack() {
        assertThat(ClusterExplainerPanel.resultImages(
                new ClusteringResult(new int[]{0}, 1, null, null, new String[]{"m"})))
                .isNull();
    }
}
