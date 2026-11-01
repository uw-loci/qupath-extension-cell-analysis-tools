package qupath.ext.qpcat.model;

import java.math.BigDecimal;

/**
 * How a cellular-neighborhood run defined each cell's window: its nearest
 * neighbors, or every cell within a physical radius.
 *
 * @param kNeighbors    window size when not in radius mode (nearest neighbors, includes self)
 * @param radiusMode    true when the window is a radius
 * @param radiusMicrons window radius in microns, used in radius mode
 */
public record CnWindow(int kNeighbors, boolean radiusMode, double radiusMicrons) {

    /** @return the short form for a step name, {@code k=20} or {@code radius 50 um} */
    public String summary() {
        return radiusMode ? "radius " + radiusText() + " um" : "k=" + kNeighbors;
    }

    /** @return the sentence form, {@code k=20 nearest neighbors} or {@code every cell within 50 um} */
    public String detail() {
        return radiusMode
                ? "every cell within " + radiusText() + " um"
                : "k=" + kNeighbors + " nearest neighbors";
    }

    /** @return the folder-name form, {@code k20} or {@code r50um} */
    public String folderTag() {
        return radiusMode ? "r" + radiusText() + "um" : "k" + kNeighbors;
    }

    private String radiusText() {
        return BigDecimal.valueOf(radiusMicrons).stripTrailingZeros().toPlainString();
    }
}
