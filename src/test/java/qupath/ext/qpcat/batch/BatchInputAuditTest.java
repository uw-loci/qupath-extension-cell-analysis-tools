package qupath.ext.qpcat.batch;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.service.InputAudit;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The batch dry run makes the run's input audit: E027 for a refusal, W014 for a notice. */
class BatchInputAuditTest {

    private static PathObject cell(double x, double y, int z) {
        return PathObjects.createCellObject(
                ROIs.createRectangleROI(x - 4, y - 4, 8, 8, ImagePlane.getPlane(z, 0)), null);
    }

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entry(String id, String name,
                                                          List<PathObject> objects) throws Exception {
        PathObjectHierarchy h = new PathObjectHierarchy();
        h.addObjects(objects);
        ProjectImageEntry<BufferedImage> e = mock(ProjectImageEntry.class);
        when(e.getID()).thenReturn(id);
        when(e.getImageName()).thenReturn(name);
        when(e.readHierarchy()).thenReturn(h);
        return e;
    }

    @Test
    void theDryRunReadsEachImageOnceForNamesAndTheAudit() throws Exception {
        PathObject a = cell(10, 10, 0);
        PathObject twin = cell(10, 10, 0);
        twin.setID(a.getID());
        InputAudit audit = InputAudit.of(InputAudit.Workflow.CLUSTERING);
        BatchMeasurements.read(List.of(entry("1", "a.tif", List.of(a, twin, cell(30, 30, 0)))),
                audit);
        List<ValidationIssue> issues = YamlBatchOrchestrator.inputIssues(audit.report(), "/p");
        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.getCode()).isEqualTo("E027");
            assertThat(i.getSeverity()).isEqualTo(ValidationIssue.Severity.ERROR);
            assertThat(i.format()).contains("repeat another detection's object id")
                    .contains("scope.projects['/p']");
        });
    }

    @Test
    void aNoticeIsAWarning() throws Exception {
        InputAudit audit = InputAudit.of(InputAudit.Workflow.CLUSTERING);
        BatchMeasurements.read(List.of(entry("1", "stack.tif",
                List.of(cell(10, 10, 0), cell(10, 10, 2)))), audit);
        List<ValidationIssue> issues = YamlBatchOrchestrator.inputIssues(audit.report(), "/p");
        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.getCode()).isEqualTo("W014");
            assertThat(i.getMessage()).startsWith("Cells from more than one z-slice");
        });
    }
}
