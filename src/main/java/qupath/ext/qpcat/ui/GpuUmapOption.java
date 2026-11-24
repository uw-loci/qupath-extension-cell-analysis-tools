package qupath.ext.qpcat.ui;

import javafx.scene.control.CheckBox;
import qupath.ext.qpcat.preferences.QpcatPreferences;

/**
 * The "UMAP on the GPU (RAPIDS)" tick, shared by the Clustering and Embedding dialogs.
 * It is offered only while a RAPIDS Python is configured, and remembered across runs.
 */
public final class GpuUmapOption {

    private GpuUmapOption() {}

    /** Whether the tick can be offered: the GPU (RAPIDS) Python preference is set. */
    public static boolean available() {
        return !QpcatPreferences.getGpuGraphPython().isEmpty();
    }

    /**
     * A new tick, selected as last left, writing its state back to the preference.
     *
     * @param onChange run after each change (pre-run checks, cost line); may be null
     */
    public static CheckBox create(Runnable onChange) {
        CheckBox box = new CheckBox("UMAP on the GPU (RAPIDS)");
        box.setSelected(QpcatPreferences.isGpuUmap());
        box.setTooltip(Tooltips.of(
                "Run UMAP with cuML in the RAPIDS environment set under Edit > Preferences > "
                + "QP-CAT: Python environment > GPU (RAPIDS): Python, as its own process (on "
                + "Windows inside WSL2, through wsl.exe). It gets the same matrix, n_neighbors, "
                + "min_dist, metric, dimensions and seed as the CPU UMAP; cells with identical "
                + "measurements share one point.\n\n"
                + "The layout is not the CPU's: two UMAP implementations place the same groups "
                + "differently. 'UMAP speed vs reproducibility' applies as on the CPU: a seeded "
                + "GPU layout repeats run to run on the same card and RAPIDS version; Fast drops "
                + "the seed and uses an approximate neighbor search.\n\n"
                + "If the process cannot run, UMAP runs on the CPU and a notification says why. "
                + "The run summary records which ran."));
        box.selectedProperty().addListener((o, a, b) -> {
            QpcatPreferences.setGpuUmap(b);
            if (onChange != null) {
                onChange.run();
            }
        });
        return box;
    }

    /** "gpu" when the tick is shown and selected, else "cpu". */
    public static String backend(CheckBox box) {
        return box != null && box.isVisible() && box.isSelected() && available() ? "gpu" : "cpu";
    }
}
