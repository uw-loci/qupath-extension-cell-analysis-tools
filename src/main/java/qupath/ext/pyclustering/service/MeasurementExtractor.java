package qupath.ext.pyclustering.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts measurement data from QuPath detection objects into arrays
 * suitable for transfer to Python via Appose NDArray.
 */
public class MeasurementExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MeasurementExtractor.class);

    /**
     * Result of measurement extraction containing the data matrix,
     * column names, and the ordered list of detection objects for
     * result mapping.
     */
    public static class ExtractionResult {
        private final double[][] data;
        private final String[] measurementNames;
        private final List<PathObject> detections;

        ExtractionResult(double[][] data, String[] measurementNames, List<PathObject> detections) {
            this.data = data;
            this.measurementNames = measurementNames;
            this.detections = detections;
        }

        public double[][] getData() { return data; }
        public String[] getMeasurementNames() { return measurementNames; }
        public List<PathObject> getDetections() { return detections; }
        public int getNCells() { return data.length; }
        public int getNMeasurements() { return measurementNames.length; }
    }

    /**
     * Extracts selected measurements from detections into a 2D array.
     *
     * @param detections   detection objects to extract from
     * @param measurements measurement names to include (null = all measurements)
     * @return extraction result with data, names, and detection references
     */
    public ExtractionResult extract(Collection<? extends PathObject> detections,
                                     List<String> measurements) {
        if (detections == null || detections.isEmpty()) {
            throw new IllegalArgumentException("No detection objects provided");
        }

        // Build ordered list of detections (filter to detection objects only)
        List<PathObject> detectionList = detections.stream()
                .filter(p -> p instanceof PathDetectionObject)
                .collect(Collectors.toList());

        if (detectionList.isEmpty()) {
            throw new IllegalArgumentException("No detection objects found in selection");
        }

        // Determine measurement columns
        String[] measurementNames;
        if (measurements != null && !measurements.isEmpty()) {
            measurementNames = measurements.toArray(new String[0]);
        } else {
            measurementNames = discoverMeasurements(detectionList);
        }

        logger.info("Extracting {} measurements from {} detections",
                measurementNames.length, detectionList.size());

        // Extract data matrix
        double[][] data = new double[detectionList.size()][measurementNames.length];
        int skippedNaN = 0;

        for (int i = 0; i < detectionList.size(); i++) {
            PathObject det = detectionList.get(i);
            var ml = det.getMeasurements();
            for (int j = 0; j < measurementNames.length; j++) {
                Number val = ml.get(measurementNames[j]);
                if (val != null && !Double.isNaN(val.doubleValue())) {
                    data[i][j] = val.doubleValue();
                } else {
                    data[i][j] = 0.0;  // Replace NaN with 0 for clustering
                    skippedNaN++;
                }
            }
        }

        if (skippedNaN > 0) {
            logger.warn("Replaced {} NaN values with 0.0", skippedNaN);
        }

        return new ExtractionResult(data, measurementNames, detectionList);
    }

    /**
     * Discovers all measurement names present across detections.
     * Returns a stable-ordered unique set.
     */
    private String[] discoverMeasurements(List<PathObject> detections) {
        Set<String> names = new LinkedHashSet<>();
        for (PathObject det : detections) {
            names.addAll(det.getMeasurements().keySet());
        }
        return names.toArray(new String[0]);
    }

    /**
     * Returns measurement names that end with a specific suffix (e.g., "Mean")
     * from the given detections. Useful for filtering to channel intensity measurements.
     */
    public static List<String> filterMeasurementsBySuffix(
            Collection<? extends PathObject> detections, String suffix) {
        Set<String> names = new LinkedHashSet<>();
        for (PathObject det : detections) {
            for (String name : det.getMeasurements().keySet()) {
                if (name.endsWith(suffix)) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * Returns all unique measurement names from the given detections.
     */
    public static List<String> getAllMeasurements(Collection<? extends PathObject> detections) {
        Set<String> names = new LinkedHashSet<>();
        for (PathObject det : detections) {
            names.addAll(det.getMeasurements().keySet());
        }
        return new ArrayList<>(names);
    }
}
