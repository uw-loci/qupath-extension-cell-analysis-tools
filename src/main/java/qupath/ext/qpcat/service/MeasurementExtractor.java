package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.MissingPolicy;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts measurement data from QuPath detection objects into arrays
 * suitable for transfer to Python via Appose NDArray.
 */
public class MeasurementExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MeasurementExtractor.class);

    private final MissingPolicy missingPolicy;

    /** An extractor that excludes objects lacking a value (the default policy). */
    public MeasurementExtractor() {
        this(MissingPolicy.EXCLUDE_OBJECTS);
    }

    /** @param missingPolicy what to do with an object lacking a value for a selected measurement */
    public MeasurementExtractor(MissingPolicy missingPolicy) {
        this.missingPolicy = missingPolicy == null ? MissingPolicy.EXCLUDE_OBJECTS : missingPolicy;
    }

    /** The policy this extractor applies. */
    public MissingPolicy getMissingPolicy() { return missingPolicy; }

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
        private List<String> droppedMeasurements;  // absent on >=1 whole image; excluded
        private MissingPolicy missingPolicy = MissingPolicy.EXCLUDE_OBJECTS;
        private int excludedObjects;
        private int[] excludedPerSegment = new int[0];
        private List<String> excludedMeasurements = List.of();
        private Map<String, Integer> missingPerMeasurement = Map.of();

        ExtractionResult(double[][] data, String[] measurementNames,
                         List<PathObject> detections, List<ImageSegment> imageSegments) {
            this.data = data;
            this.measurementNames = measurementNames;
            this.detections = detections;
            this.imageSegments = imageSegments;
        }

        /** The policy the run used for missing values. */
        public MissingPolicy getMissingPolicy() { return missingPolicy; }

        /** Objects left out because a selected measurement was missing (policy EXCLUDE_OBJECTS). */
        public int getExcludedObjects() { return excludedObjects; }

        /** The same count per image segment, aligned with {@link #getImageSegments()}. */
        public int[] getExcludedPerSegment() { return excludedPerSegment; }

        /** Measurements left out because a value was missing (policy EXCLUDE_MEASUREMENTS). */
        public List<String> getExcludedMeasurements() { return excludedMeasurements; }

        /**
         * Objects lacking a value, per selected measurement, before the policy was
         * applied; only measurements with at least one missing value are listed.
         */
        public Map<String, Integer> getMissingPerMeasurement() { return missingPerMeasurement; }

        /** One sentence for a log line or a results note; empty when nothing was missing. */
        public String missingSummary() {
            if (missingPerMeasurement.isEmpty()) return "";
            int affected = missingPerMeasurement.size();
            switch (missingPolicy) {
                case EXCLUDE_OBJECTS:
                    return excludedObjects + " object(s) excluded for a missing value in "
                            + affected + " selected measurement(s): " + missingPerMeasurement.keySet();
                case EXCLUDE_MEASUREMENTS:
                    return excludedMeasurements.size() + " measurement(s) with missing values left out, "
                            + "every object kept: " + excludedMeasurements;
                default:
                    return "missing values in " + affected + " selected measurement(s) replaced by the "
                            + "column median: " + missingPerMeasurement;
            }
        }

        public double[][] getData() { return data; }
        public String[] getMeasurementNames() { return measurementNames; }
        public List<PathObject> getDetections() { return detections; }
        public int getNCells() { return data.length; }
        public int getNMeasurements() { return measurementNames.length; }

        void setDroppedMeasurements(List<String> dropped) { this.droppedMeasurements = dropped; }

        /**
         * Selected measurements that were excluded because they were absent on
         * one or more entire images (multi-image extraction only). Such columns
         * would otherwise be filled with NaN for every cell in the missing
         * image(s) and, after imputation + normalization, act as an
         * image-discriminating constant -- a batch-effect artifact. Empty when
         * nothing was dropped.
         */
        public List<String> getDroppedMeasurements() {
            return droppedMeasurements == null ? List.of() : droppedMeasurements;
        }

        public boolean hasDroppedMeasurements() {
            return droppedMeasurements != null && !droppedMeasurements.isEmpty();
        }

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
        warnQpcatInputs(measurements);

        List<PathObject> detectionList = detections.stream()
                .filter(p -> p instanceof PathDetectionObject)
                .collect(Collectors.toList());

        if (detectionList.isEmpty()) {
            throw new IllegalArgumentException("No detection objects found in selection");
        }

        String[] requestedNames = resolveMeasurementNames(detectionList, measurements);

        // Single-image: one segment covering all detections
        Applied applied = applyMissingPolicy(detectionList, requestedNames,
                List.of(new ImageSegment(null, null, 0, detectionList.size())));

        logger.info("Extracting {} measurements from {} detections",
                applied.names().length, applied.detections().size());

        double[][] data = extractDataMatrix(applied.detections(), applied.names());

        ExtractionResult result = new ExtractionResult(data, applied.names(), applied.detections(),
                applied.segments());
        record(result, missingPolicy, applied);
        return result;
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
        warnQpcatInputs(measurements);

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

        String[] requestedNames = resolveMeasurementNames(allDetections, measurements);

        // Drop measurements that are absent on one or more ENTIRE images. Such a
        // measurement is present for some images' cells and missing for all cells
        // of at least one other image; the missing cells would be NaN-imputed to a
        // single value, turning the column into an image-discriminating constant
        // (a batch-effect artifact that makes clusters segregate by image rather
        // than by phenotype). Keeping only measurements present in every image
        // removes that artifact at the source. The offered list is discovered from
        // one image (the open one), so cross-image gaps are easy to hit.
        List<Set<String>> perImageKeys = new ArrayList<>();
        for (ImageSegment seg : segments) {
            Set<String> keys = new HashSet<>();
            for (int i = seg.getStartIndex(); i < seg.getEndIndex(); i++) {
                keys.addAll(allDetections.get(i).getMeasurements().keySet());
            }
            perImageKeys.add(keys);
        }

        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (String name : requestedNames) {
            boolean inEveryImage = true;
            for (Set<String> keys : perImageKeys) {
                if (!keys.contains(name)) {
                    inEveryImage = false;
                    break;
                }
            }
            (inEveryImage ? kept : dropped).add(name);
        }

        String[] measurementNames;
        List<String> droppedForReport;
        if (kept.isEmpty()) {
            // Pathological: every selected measurement is missing on some image.
            // Keep them all rather than produce an empty matrix, but warn loudly --
            // clustering is likely to separate cells by image in this case.
            logger.warn("All {} selected measurement(s) are absent on at least one "
                    + "image; keeping them anyway (clusters may separate by image). "
                    + "Check that every image carries the same measurements.",
                    requestedNames.length);
            measurementNames = requestedNames;
            droppedForReport = List.of();
        } else {
            measurementNames = kept.toArray(new String[0]);
            droppedForReport = dropped;
            if (!dropped.isEmpty()) {
                logger.warn("Excluding {} of {} selected measurement(s) not present on "
                        + "every image (would create image-discriminating constant "
                        + "columns -> batch effect): {}",
                        dropped.size(), requestedNames.length, dropped);
            }
        }

        Applied applied = applyMissingPolicy(allDetections, measurementNames, segments);

        logger.info("Extracting {} measurements from {} detections across {} images",
                applied.names().length, applied.detections().size(), applied.segments().size());

        double[][] data = extractDataMatrix(applied.detections(), applied.names());

        ExtractionResult result = new ExtractionResult(data, applied.names(), applied.detections(),
                applied.segments());
        result.setDroppedMeasurements(droppedForReport);
        record(result, missingPolicy, applied);
        return result;
    }

    /**
     * Groups detections with their parent image entry and data for multi-image extraction.
     */
    /** Objects lacking a value for a selected measurement, as the dialog shows it before a run. */
    public record MissingReport(int totalObjects, int objectsWithMissing,
                                Map<String, Integer> perMeasurement) {
        /** True when at least one object lacks at least one value. */
        public boolean any() { return objectsWithMissing > 0; }
    }

    /**
     * Counts, per measurement, the objects that lack a value (absent or NaN), and
     * the objects lacking at least one. Only measurements with a missing value
     * appear in the map, in the order given.
     *
     * @param detections   the objects in scope
     * @param measurements the measurements to check
     */
    public static MissingReport missingValues(Collection<? extends PathObject> detections,
                                              List<String> measurements) {
        Map<String, Integer> per = new LinkedHashMap<>();
        int withMissing = 0;
        int total = 0;
        for (PathObject det : detections) {
            if (!(det instanceof PathDetectionObject)) continue;
            total++;
            var ml = det.getMeasurements();
            boolean any = false;
            for (String m : measurements) {
                Number v = ml.get(m);
                if (v == null || Double.isNaN(v.doubleValue())) {
                    per.merge(m, 1, Integer::sum);
                    any = true;
                }
            }
            if (any) withMissing++;
        }
        return new MissingReport(total, withMissing, per);
    }

    /** What {@link #applyMissingPolicy} decided. */
    private record Applied(List<PathObject> detections, String[] names, List<ImageSegment> segments,
                           int excludedObjects, int[] excludedPerSegment,
                           List<String> excludedMeasurements, Map<String, Integer> missingPerMeasurement) {}

    /**
     * Applies the missing-values policy to the objects and measurements of a run.
     * EXCLUDE_OBJECTS drops every object lacking a value and rebuilds the segments;
     * EXCLUDE_MEASUREMENTS drops every measurement with a missing value; IMPUTE
     * leaves both for the worker's median imputation.
     *
     * @throws IllegalArgumentException when the policy would leave no object or no measurement
     */
    private Applied applyMissingPolicy(List<PathObject> detections, String[] names,
                                       List<ImageSegment> segments) {
        MissingReport report = missingValues(detections, List.of(names));
        if (!report.any()) {
            return new Applied(detections, names, segments, 0, new int[segments.size()],
                    List.of(), Map.of());
        }
        logger.warn("{} of {} object(s) lack a value for {} of the {} selected measurement(s): {}",
                report.objectsWithMissing(), report.totalObjects(), report.perMeasurement().size(),
                names.length, report.perMeasurement());
        switch (missingPolicy) {
            case EXCLUDE_MEASUREMENTS: {
                List<String> kept = new ArrayList<>();
                List<String> dropped = new ArrayList<>();
                for (String n : names) {
                    (report.perMeasurement().containsKey(n) ? dropped : kept).add(n);
                }
                if (kept.isEmpty()) {
                    throw new IllegalArgumentException("Every selected measurement has a missing value "
                            + "on some object, so excluding those measurements leaves nothing to run on. "
                            + "Exclude the objects instead, or replace the missing values.");
                }
                logger.warn("Policy {}: leaving out {} measurement(s): {}", missingPolicy, dropped.size(), dropped);
                return new Applied(detections, kept.toArray(new String[0]), segments, 0,
                        new int[segments.size()], dropped, report.perMeasurement());
            }
            case EXCLUDE_OBJECTS: {
                List<PathObject> kept = new ArrayList<>(detections.size() - report.objectsWithMissing());
                List<ImageSegment> newSegments = new ArrayList<>(segments.size());
                int[] perSegment = new int[segments.size()];
                for (int s = 0; s < segments.size(); s++) {
                    ImageSegment seg = segments.get(s);
                    int start = kept.size();
                    for (int i = seg.getStartIndex(); i < seg.getEndIndex(); i++) {
                        PathObject det = detections.get(i);
                        if (lacksAny(det, names)) {
                            perSegment[s]++;
                        } else {
                            kept.add(det);
                        }
                    }
                    newSegments.add(new ImageSegment(seg.getImageEntry(), seg.getImageData(), start, kept.size()));
                }
                if (kept.isEmpty()) {
                    throw new IllegalArgumentException("Every object lacks a value for a selected "
                            + "measurement, so excluding them leaves nothing to run on. Deselect the "
                            + "measurements with missing values, or replace the missing values.");
                }
                logger.warn("Policy {}: leaving out {} object(s); {} remain", missingPolicy,
                        report.objectsWithMissing(), kept.size());
                return new Applied(kept, names, newSegments, report.objectsWithMissing(), perSegment,
                        List.of(), report.perMeasurement());
            }
            default:
                logger.warn("Policy {}: the worker replaces each missing value with the column median",
                        missingPolicy);
                return new Applied(detections, names, segments, 0, new int[segments.size()],
                        List.of(), report.perMeasurement());
        }
    }

    private static boolean lacksAny(PathObject det, String[] names) {
        var ml = det.getMeasurements();
        for (String n : names) {
            Number v = ml.get(n);
            if (v == null || Double.isNaN(v.doubleValue())) return true;
        }
        return false;
    }

    private static void record(ExtractionResult result, MissingPolicy policy, Applied applied) {
        result.missingPolicy = policy;
        result.excludedObjects = applied.excludedObjects();
        result.excludedPerSegment = applied.excludedPerSegment();
        result.excludedMeasurements = applied.excludedMeasurements();
        result.missingPerMeasurement = applied.missingPerMeasurement();
    }

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
        int missing = 0;

        for (int i = 0; i < detections.size(); i++) {
            PathObject det = detections.get(i);
            var ml = det.getMeasurements();
            for (int j = 0; j < measurementNames.length; j++) {
                Number val = ml.get(measurementNames[j]);
                if (val != null && !Double.isNaN(val.doubleValue())) {
                    data[i][j] = val.doubleValue();
                } else {
                    // Leave missing values as NaN so the Python side imputes them
                    // per-column median (run_clustering.py / embed_3d.py both do).
                    // Filling 0.0 here injected a fake extreme value that biased
                    // normalization and clustering.
                    data[i][j] = Double.NaN;
                    missing++;
                }
            }
        }

        if (missing > 0) {
            logger.warn("Left {} missing measurement value(s) as NaN for downstream "
                    + "per-column median imputation", missing);
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
     * True for a mean-intensity measurement, matched CASE-INSENSITIVELY.
     * <p>
     * Detection engines disagree on the capitalisation: QuPath's own cell
     * measurements read {@code "Nucleus: DAPI mean"} (lower case), while others
     * emit {@code "CD8: Cell: Mean"}. Matching {@code "Mean"} literally --
     * which both the Select 'Mean' button and Quick Cluster used to do -- found
     * nothing at all on a standard QuPath project, and Quick Cluster refused to
     * start with "No 'Mean' measurements found" on data that was full of them.
     * <p>
     * One predicate, used by every caller, so the two cannot drift apart again.
     * The measurement FILTER box was already case-insensitive, which is what
     * made the button look arbitrary rather than simply wrong.
     */
    public static boolean isMeanMeasurement(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("mean");
    }

    /**
     * True for a median-intensity measurement, matched CASE-INSENSITIVELY.
     * <p>
     * Same reasoning as {@link #isMeanMeasurement(String)}: engines disagree on
     * capitalisation. "median" does not contain "mean" as a substring, so the two
     * predicates cannot both match one name.
     *
     * @param name measurement name, may be null
     * @return true if the name contains "median" in any capitalisation
     */
    public static boolean isMedianMeasurement(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("median");
    }

    /**
     * True for a measurement QP-CAT itself wrote -- embedding coordinates, spatial and
     * component columns, cellular-neighborhood labels.
     * <p>
     * These are OUTPUT. Feeding them back in as input clusters on a previous run's
     * answer, which is how a run came back with a cluster whose defining feature was
     * its own UMAP1.
     *
     * @param name measurement name, may be null
     * @return true if the name contains "QPCAT" in any capitalisation
     */
    public static boolean isQpcatMeasurement(String name) {
        return name != null && name.toUpperCase(java.util.Locale.ROOT).contains("QPCAT");
    }

    /**
     * The names among {@code measurements} that QP-CAT wrote itself (embedding
     * coordinates, {@code QPCAT spatial:}, {@code QPCAT component:}, {@code QPCAT CN}).
     * Empty for null.
     */
    public static List<String> qpcatMeasurementsIn(List<String> measurements) {
        if (measurements == null) return List.of();
        return measurements.stream().filter(MeasurementExtractor::isQpcatMeasurement).toList();
    }

    /**
     * The measurements the Quick presets run on: every "mean" measurement that
     * QP-CAT did not write itself. A previous run's {@code QPCAT spatial: Mean
     * distance} matches "mean" but is that run's output, and a one-click preset
     * must not cluster on it.
     */
    public static List<String> presetMeasurements(List<String> all) {
        return all.stream()
                .filter(MeasurementExtractor::isMeanMeasurement)
                .filter(n -> !isQpcatMeasurement(n))
                .toList();
    }

    /**
     * Logs a warning when the run's inputs include QP-CAT's own output. Not an
     * error: clustering on embedding coordinates is sometimes the intent (a
     * second pass on a UMAP). Every extraction path, GUI or batch, passes here.
     */
    static void warnQpcatInputs(List<String> measurements) {
        List<String> own = qpcatMeasurementsIn(measurements);
        if (!own.isEmpty()) {
            logger.warn("{} of the {} selected measurements are QP-CAT's own output from an "
                    + "earlier run: {}. Clustering on them clusters on that run's answer; "
                    + "untick them unless that is the intent.",
                    own.size(), measurements.size(), own);
        }
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
     * True when every value in {@code data} survives a float32 round-trip exactly.
     * <p>
     * QuPath stores detection measurements as float32 -- {@link qupath.lib.objects.PathDetectionObject}
     * creates its list with {@code MeasurementListType.FLOAT}, and
     * {@code NumericMeasurementList.FloatList.setValue} casts to {@code (float)}.
     * {@code PathCellObject} extends {@code PathDetectionObject}, so cells are float32
     * too. Widening those back to double and shipping float64 over Appose therefore
     * doubles the payload to carry 29 bits of guaranteed zeros.
     * <p>
     * This CHECKS rather than assumes, because the premise is about object
     * provenance, not about the current QuPath version: a measurement list
     * deserialized from an older project, or written by another extension, could in
     * principle be double-backed. The check costs one comparison per value on a
     * matrix we are copying anyway, and it turns a silent precision loss into an
     * explicit fallback.
     *
     * @return true if a float32 transfer is lossless for this matrix
     */
    public static boolean isFloat32Exact(double[][] data) {
        if (data == null) return false;
        for (double[] row : data) {
            if (row == null) continue;
            for (double v : row) {
                // NaN is fine -- it round-trips as NaN, and NaN != NaN so it needs
                // its own branch rather than an equality test.
                if (Double.isNaN(v)) continue;
                if ((double) (float) v != v) return false;
            }
        }
        return true;
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
