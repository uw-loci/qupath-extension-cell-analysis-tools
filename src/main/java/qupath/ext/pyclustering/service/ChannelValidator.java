package qupath.ext.pyclustering.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;

/**
 * Validates measurement channel consistency across project images
 * and checks marker availability for loaded rule sets.
 */
public class ChannelValidator {

    private static final Logger logger = LoggerFactory.getLogger(ChannelValidator.class);

    private ChannelValidator() {}

    /**
     * Result of validating measurement consistency across project images.
     */
    public record ValidationResult(
            List<String> commonMeasurements,
            Map<String, List<String>> uniqueToImage,
            boolean allConsistent,
            int totalImages,
            int imagesChecked) {}

    /**
     * Result of checking which markers from a rule set are present/missing.
     */
    public record MarkerMatch(List<String> present, List<String> missing) {}

    /**
     * Validate that all project images have the same measurements.
     * Iterates images, reads detections, computes intersection and per-image unique sets.
     */
    public static ValidationResult validateProjectMeasurements(
            Project<BufferedImage> project, Consumer<String> progress) {

        var imageEntries = project.getImageList();
        int totalImages = imageEntries.size();
        int imagesChecked = 0;

        Set<String> commonSet = null;
        Map<String, Set<String>> perImageMeasurements = new LinkedHashMap<>();

        for (var entry : imageEntries) {
            if (progress != null) {
                progress.accept("Checking " + entry.getImageName()
                        + " (" + (imagesChecked + 1) + "/" + totalImages + ")...");
            }

            try {
                ImageData<BufferedImage> imageData = entry.readImageData();
                Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();

                if (detections.isEmpty()) {
                    logger.info("Skipping {} - no detections", entry.getImageName());
                    continue;
                }

                List<String> measurements = MeasurementExtractor.getAllMeasurements(detections);
                Set<String> measurementSet = new LinkedHashSet<>(measurements);

                perImageMeasurements.put(entry.getImageName(), measurementSet);

                if (commonSet == null) {
                    commonSet = new LinkedHashSet<>(measurementSet);
                } else {
                    commonSet.retainAll(measurementSet);
                }

                imagesChecked++;
            } catch (Exception e) {
                logger.warn("Failed to read image data for {}: {}",
                        entry.getImageName(), e.getMessage());
            }
        }

        if (commonSet == null) {
            commonSet = Collections.emptySet();
        }

        // Compute per-image unique measurements (present in that image but not in common set)
        Map<String, List<String>> uniqueToImage = new LinkedHashMap<>();
        boolean allConsistent = true;

        for (var entry : perImageMeasurements.entrySet()) {
            Set<String> unique = new LinkedHashSet<>(entry.getValue());
            unique.removeAll(commonSet);
            if (!unique.isEmpty()) {
                uniqueToImage.put(entry.getKey(), new ArrayList<>(unique));
                allConsistent = false;
            }
        }

        logger.info("Channel validation: {} images checked, {} common measurements, consistent={}",
                imagesChecked, commonSet.size(), allConsistent);

        return new ValidationResult(
                new ArrayList<>(commonSet),
                uniqueToImage,
                allConsistent,
                totalImages,
                imagesChecked);
    }

    /**
     * Check which markers from a rule set are available in the current measurement list.
     */
    public static MarkerMatch validateMarkers(List<String> ruleSetMarkers, List<String> available) {
        Set<String> availableSet = new HashSet<>(available);
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String marker : ruleSetMarkers) {
            if (availableSet.contains(marker)) {
                present.add(marker);
            } else {
                missing.add(marker);
            }
        }
        return new MarkerMatch(present, missing);
    }
}
