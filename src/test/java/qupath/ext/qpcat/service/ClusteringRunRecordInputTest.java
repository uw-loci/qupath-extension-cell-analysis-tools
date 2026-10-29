package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.CellRef;
import qupath.ext.qpcat.model.ClusteringConfig;
import qupath.ext.qpcat.model.ClusteringResult;
import qupath.ext.qpcat.model.InputSelection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** RUN_INFO and the audit log list every image and annotation a run used. */
class ClusteringRunRecordInputTest {

    private static ClusteringResult twoImages() {
        ClusteringResult r = new ClusteringResult(new int[3], 1, null, null, new String[0]);
        r.setCellRefs(new CellRef[] {
                new CellRef("1", "a.tif", "o1", 0, 0, 1),
                new CellRef("2", "b.tif", "o2", 0, 0, 1),
                new CellRef("2", "b.tif", "o3", 0, 0, 1)});
        return r;
    }

    @Test
    void runInfoListsEveryImageWithItsCells() {
        String info = ClusteringRunRecord.buildRunInfo("r", new ClusteringConfig(), twoImages(),
                "2 project images");
        assertThat(info).contains("Images (2):\n  - a.tif  (1 cells)\n  - b.tif  (2 cells)\n");
    }

    @Test
    void runInfoListsTheSelectedAnnotations() {
        ClusteringResult r = new ClusteringResult(new int[1], 1, null, null, new String[0]);
        r.setInputSelection(InputSelection.ofAnnotations(List.of(
                new InputSelection.Annotation("Region 1", "Tumor", "a1", 409))));
        String info = ClusteringRunRecord.buildRunInfo("r", new ClusteringConfig(), r, "x.tif");
        assertThat(info).contains("Input       : 1 annotation (Tumor x1)\n"
                + "  - Tumor  'Region 1'  id=a1  409 detections inside\n");
    }

    @Test
    void theAuditLogKeepsTheFullImageList() {
        Map<String, String> params = new LinkedHashMap<>();
        OperationLogger.addRunInput(params, twoImages());
        assertThat(params).containsEntry("Images", "a.tif (1); b.tif (2)");
    }
}
