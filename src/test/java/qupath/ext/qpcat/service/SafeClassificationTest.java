package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.lib.regions.ImagePlane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ResultApplier#setClassification} is the choke point that keeps QuPath's
 * per-object log nags out of our loops.
 *
 * <p>QuPath warns once per object when handed the NULL class or an invalid one.
 * Harmless on a single call; at detection scale it is a flood -- one 304,083-cell
 * run wrote 67,228 WARN lines and buried the messages that mattered. The exposure
 * is real wherever the class is built from a name, because
 * {@code PathClass.fromString(null)} returns the NULL class and a blank name
 * yields an invalid one, so ONE bad cluster / gate / phenotype name costs one
 * warning per cell.
 */
class SafeClassificationTest {

    private static PathObject detection() {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 1, 1, ImagePlane.getDefaultPlane()));
    }

    @Test
    void theNullClassClearsInsteadOfWarning() {
        PathObject det = detection();
        det.setPathClass(PathClass.fromString("Tumor"));
        ResultApplier.setClassification(det, PathClass.getNullClass());
        assertThat(det.getPathClass()).isNull();
    }

    @Test
    void aNameThatCameBackNullIsTheNullClass() {
        // The actual failure path: fromString(null) is NOT null, it is the NULL
        // class singleton -- which is exactly what QuPath nags about.
        PathClass fromNull = PathClass.fromString(null);
        assertThat(fromNull).isSameAs(PathClass.getNullClass());

        PathObject det = detection();
        ResultApplier.setClassification(det, fromNull);
        assertThat(det.getPathClass()).isNull();
    }

    @Test
    void nullItselfIsAccepted() {
        PathObject det = detection();
        det.setPathClass(PathClass.fromString("Tumor"));
        ResultApplier.setClassification(det, null);
        assertThat(det.getPathClass()).isNull();
    }

    @Test
    void aRealClassIsSetUnchanged() {
        PathObject det = detection();
        PathClass pc = PathClass.fromString("Cluster 3");
        ResultApplier.setClassification(det, pc);
        assertThat(det.getPathClass()).isSameAs(pc);
    }

    @Test
    void aBlankNameThrowsRatherThanProducingAnInvalidClass() {
        // Worth pinning because it BOUNDS the exposure. A blank cluster / gate /
        // phenotype name fails loudly at construction, so it can never reach a
        // per-object loop and warn 300,000 times. Only the null name is silent,
        // which is why that is the case the helper exists for. If a QuPath
        // release ever softens this to an invalid-but-constructible class, this
        // test goes red and the flood risk is back.
        assertThatThrownBy(() -> PathClass.fromString("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
