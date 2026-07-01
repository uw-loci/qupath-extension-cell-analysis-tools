package qupath.ext.qpcat.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Regenerates the two color-dependent clustering PNGs (embedding scatter and
 * spatial scatter) from cached embedding / labels / coordinates using a supplied
 * cluster palette -- WITHOUT re-running clustering or embedding. Backs the
 * "Regenerate static plots" action and the auto-regenerate preference in the
 * Results dialog. Runs the standalone {@code regenerate_plots} Appose task
 * (numpy + matplotlib only, so startup is fast).
 */
public final class PlotRegenerator {

    private static final Logger logger = LoggerFactory.getLogger(PlotRegenerator.class);

    private PlotRegenerator() {}

    /**
     * Regenerate the color-dependent PNGs into {@code outputDir}. Must be called
     * off the JavaFX application thread (it blocks on the Appose round-trip).
     *
     * @param embedding      N x 2 embedding coordinates (required)
     * @param labels         cluster id per cell, -1 = noise (required, length N)
     * @param coords         N x 2 centroids for the spatial scatter, or null
     * @param clusterColors  "#RRGGBB" per cluster id (index = id); may be null
     * @param embeddingName  axis-label stem (e.g. "UMAP"); null -> "Embedding"
     * @param dpi            output resolution
     * @param outputDir      directory to write the PNGs into
     * @param progress       optional progress-message sink
     * @return map of plot key ("embedding", "spatial_scatter") -> absolute path
     */
    public static Map<String, String> regenerate(double[][] embedding, int[] labels,
                                                  double[][] coords, List<String> clusterColors,
                                                  String embeddingName, int dpi, Path outputDir,
                                                  Consumer<String> progress) throws IOException {
        if (embedding == null || labels == null) {
            throw new IllegalArgumentException("embedding and labels are required");
        }
        if (embedding.length != labels.length) {
            throw new IllegalArgumentException("embedding rows (" + embedding.length
                    + ") != labels length (" + labels.length + ")");
        }
        try {
            return ApposeClusteringService.withExtensionClassLoader(
                    () -> runTask(embedding, labels, coords, clusterColors,
                            embeddingName, dpi, outputDir, progress));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Plot regeneration failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> runTask(double[][] embedding, int[] labels,
                                               double[][] coords, List<String> clusterColors,
                                               String embeddingName, int dpi, Path outputDir,
                                               Consumer<String> progress) throws IOException {
        int nCells = embedding.length;

        NDArray embNd = new NDArray(NDArray.DType.FLOAT64,
                new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, 2));
        var ebuf = embNd.buffer().asDoubleBuffer();
        for (int i = 0; i < nCells; i++) {
            ebuf.put(embedding[i][0]);
            ebuf.put(embedding[i][1]);
        }

        NDArray labelsNd = new NDArray(NDArray.DType.INT32,
                new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells));
        labelsNd.buffer().asIntBuffer().put(labels);

        NDArray coordsNd = null;
        if (coords != null && coords.length == nCells) {
            coordsNd = new NDArray(NDArray.DType.FLOAT64,
                    new NDArray.Shape(NDArray.Shape.Order.C_ORDER, nCells, 2));
            var cbuf = coordsNd.buffer().asDoubleBuffer();
            for (int i = 0; i < nCells; i++) {
                cbuf.put(coords[i][0]);
                cbuf.put(coords[i][1]);
            }
        }

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("embedding", embNd);
        inputs.put("cluster_labels", labelsNd);
        inputs.put("output_dir", outputDir.toString());
        inputs.put("plot_dpi", dpi);
        inputs.put("embedding_name", embeddingName != null ? embeddingName : "Embedding");
        if (clusterColors != null && !clusterColors.isEmpty()) {
            inputs.put("cluster_colors", new ArrayList<>(clusterColors));
        }
        if (coordsNd != null) {
            inputs.put("spatial_coords", coordsNd);
        }

        try {
            Task task = ApposeClusteringService.getInstance().runTaskWithListener(
                    "regenerate_plots", inputs,
                    event -> {
                        if (event.responseType == ResponseType.UPDATE && event.message != null) {
                            report(progress, event.message);
                        }
                    });

            Map<String, String> out = new LinkedHashMap<>();
            Object pathsJson = task.outputs.get("plot_paths");
            if (pathsJson != null) {
                JsonObject obj = JsonParser.parseString(String.valueOf(pathsJson)).getAsJsonObject();
                for (var e : obj.entrySet()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
            logger.info("Regenerated {} plot(s) into {}", out.size(), outputDir);
            return out;
        } finally {
            embNd.close();
            labelsNd.close();
            if (coordsNd != null) coordsNd.close();
        }
    }

    private static void report(Consumer<String> progress, String msg) {
        if (progress != null) {
            try { progress.accept(msg); } catch (Exception ignore) { /* UI sink */ }
        }
    }
}
