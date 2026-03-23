package qupath.ext.qpcat.service;

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
        private final List<ImageSegment> imageSegments;

        ExtractionResult(double[][] data, String[] measurementNames,
                         List<PathObject> detections, List<ImageSegment> imageSegments) {
            this.data = data;
            this.measurementNames = measurementNames;
            this.detections = detections;
            this.imageSegments = imageSegments;
        }

        public double[][] getData() { return data; }
        public String[] getMeasurementNames() { return measurementNames; }
        public List<PathObject> getDetections() { return detections; }
        public int getNCells() { return data.length; }
        public int getNMeasurements() { return measurementNames.length; }

        /**
         * Returns image segments tracking which detections belong to which image.
         * For single-image extractions, this contains one segment.
         */
        public List<ImageSegment> getImageSegments() { return imageSegments; }

        /** True if this extraction spans multiple project images. */
        public boolean isMultiImage() {
            return imageSegments != null && imageSegments.size() > 1;
        }
    }

    /**
     * Tracks a contiguous range of detections that belong to the same image.
     * Used for multi-image project clustering to map results back to the
     * correct image for saving.
     */
    public static class ImageSegment {
        private final Object imageEntry;  // ProjectImageEntry<BufferedImage>
        private final Object imageData;   // ImageData<BufferedImage>
        private final int startIndex;     // inclusive index into the combined detection list
        private final int endIndex;       // exclusive index

        public ImageSegment(Object imageEntry, Object imageData, int startIndex, int endIndex) {
            this.imageEntry = imageEntry;
            this.imageData = imageData;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }

        public Object getImageEntry() { return imageEntry; }
        public Object getImageData() { return imageData; }
        public int getStartIndex() { return startIndex; }
        public int getEndIndex() { return endIndex; }
        public int getCount() { return endIndex - startIndex; }
    }

    /**
     * Extracts selected measurements from detections into a 2D array.
     * For single-image use.
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

        List<PathObject> detectionList = detections.stream()
                .filter(p -> p instanceof PathDetectionObject)
                .collect(Collectors.toList());

        if (detectionList.isEmpty()) {
            throw new IllegalArgumentException("No detection objects found in selection");
        }

        String[] measurementNames = resolveMeasurementNames(detectionList, measurements);

        logger.info("Extracting {} measurements from {} detections",
                measurementNames.length, detectionList.size());

        double[][] data = extractDataMatrix(detectionList, measurementNames);

        // Single-image: one segment covering all detections
        List<ImageSegment> segments = List.of(
                new ImageSegment(null, null, 0, detectionList.size()));

        return new ExtractionResult(data, measurementNames, detectionList, segments);
    }

    /**
     * Extracts selected measurements from detections across multiple images,
     * tracking which detections belong to which image for result write-back.
     *
     * @param imageDetections list of (imageEntry, imageData, detections) tuples
     * @param measurements    measurement names to include (null = all)
     * @return combined extraction result with per-image segment tracking
     */
    public ExtractionResult extractMultiImage(
            List<ImageDetectionGroup> imageDetections,
            List<String> measurements) {

        if (imageDetections == null || imageDetections.isEmpty()) {
            throw new IllegalArgumentException("No image detection groups provided");
        }

        // Combine all detections into a single list, tracking segments
        List<PathObject> allDetections = new ArrayList<>();
        List<ImageSegment> segments = new ArrayList<>();

        for (ImageDetectionGroup group : imageDetections) {
            List<PathObject> dets = group.detections.stream()
                    .filter(p -> p instanceof PathDetectionObject)
                    .collect(Collectors.toList());

            if (dets.isEmpty()) continue;

            int start = allDetections.size();
            allDetections.addAll(dets);
            int end = allDetections.size();

            segments.add(new ImageSegment(
                    group.imageEntry, group.imageData, start, end));
        }

        if (allDetections.isEmpty()) {
            throw new IllegalArgumentException("No detection objects found across selected images");
        }

        String[] measurementNames = resolveMeasurementNames(allDetections, measurements);

        logger.info("Extracting {} measurements from {} detections across {} images",
                measurementNames.length, allDetections.size(), segments.size());

        double[][] data = extractDataMatrix(allDetections, measurementNames);

        return new ExtractionResult(data, measurementNames, allDetections, segments);
    }

    /**
     * Groups detections with their parent image entry and data for multi-image extraction.
     */
    public static class ImageDetectionGroup {
        public final Object imageEntry;
        public final Object imageData;
        public final Collection<? extends PathObject> detections;

        public ImageDetectionGroup(Object imageEntry, Object imageData,
                                    Collection<? extends PathObject> detections) {
            this.imageEntry = imageEntry;
            this.imageData = imageData;
            this.detections = detections;
        }
    }

    // ==================== Internal Helpers ====================

    private String[] resolveMeasurementNames(List<PathObject> detections, List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested.toArray(new String[0]);
        }
        return discoverMeasurements(detections);
    }

    private double[][] extractDataMatrix(List<PathObject> detections, String[] measurementNames) {
        double[][] data = new double[detections.size()][measurementNames.length];
        int skippedNaN = 0;

        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            var ml = det.getMeasurements();
            for (int j = 0; j < measurementNames.length; j++) {
                Number val = ml.get(measurementNames[j]);
                if (val != null && !Double.isNaN(val.doubleValue())) {
                    data[i][j] = val.doubleValue();
                } else {
                    data[i][j] = 0.0;
                    skippedNaN++;
                }
            }
        }

        if (skippedNaN > 0) {
            logger.warn("Replaced {} NaN values with 0.0", skippedNaN);
        }

        return data;
    }

    private String[] discoverMeasurements(List<PathObject> detections) {
        Set<String> names = new LinkedHashSet<>();
        for (PathObject det : detections) {
            names.addAll(det.getMeasurements().keySet());
        }
        return names.toArray(new String[0]);
    }

    /**
     * Returns measurement names that end with a specific suffix (e.g., "Mean")
     * from the given detections.
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
     * Extracts XY centroid coordinates from detection ROIs.
     *
     * @param detections ordered list of detections
     * @return double[nCells][2] array of (centroidX, centroidY) in pixel coordinates
     */
    public static double[][] extractCentroids(List<PathObject> detections) {
        double[][] coords = new double[detections.size()][2];
        for (int i = 0; i < detections.size(); i++) {
            var roi = detections.get(i).getROI();
            coords[i][0] = roi.getCentroidX();
            coords[i][1] = roi.getCentroidY();
        }
        return coords;
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
