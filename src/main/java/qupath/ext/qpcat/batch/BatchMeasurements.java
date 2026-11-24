package qupath.ext.qpcat.batch;

import qupath.ext.qpcat.model.MeasurementNames;
import qupath.ext.qpcat.service.DetectionSelector;
import qupath.ext.qpcat.service.InputAudit;
import qupath.ext.qpcat.service.MeasurementExtractor;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The measurement names of one project's resolved images, and the batch file's
 * measurement and rule-marker names resolved against them before any work runs.
 *
 * <p>A name resolves to the measurement it equals exactly, else to the one
 * measurement whose {@link MeasurementNames#shorten shortened} name it equals
 * ({@code CD3 mean} for {@code Cell: CD3 mean}). A name that matches several
 * measurements, none, or a measurement some images lack is E010, with the
 * candidates in the message; the dry run reports the same.</p>
 */
public final class BatchMeasurements {

    private static final int MAX_LISTED = 8;

    private final Map<String, Set<String>> namesByImage;
    private final List<String> unreadable;
    private final Set<String> union = new LinkedHashSet<>();

    BatchMeasurements(Map<String, Set<String>> namesByImage, List<String> unreadable) {
        this.namesByImage = namesByImage;
        this.unreadable = unreadable;
        for (Set<String> names : namesByImage.values()) union.addAll(names);
    }

    /**
     * Read the detection measurement names of each image (cells only when the
     * image has cells, as every analysis does). Images without detections are
     * left out, as the runs skip them; an image that cannot be read is listed in
     * {@link #getUnreadable()}.
     *
     * @param entries the images of one project in the run
     * @param audit   the input audit to add each image's cells to, in the same pass; may be null
     * @return the names per image
     */
    public static BatchMeasurements read(List<ProjectImageEntry<BufferedImage>> entries,
                                         InputAudit audit) {
        Map<String, Set<String>> byImage = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        for (ProjectImageEntry<BufferedImage> entry : entries) {
            Collection<PathObject> detections;
            try {
                detections = entry.readHierarchy().getDetectionObjects();
            } catch (Exception e) {
                unreadable.add(entry.getImageName() + ": "
                        + BatchYamlParser.asciiSafe(e.getMessage()));
                continue;
            }
            if (detections.isEmpty()) continue;
            Set<String> names = new LinkedHashSet<>();
            List<PathObject> cells = DetectionSelector.filterToCellsWhenPresent(
                    detections, entry.getImageName());
            for (PathObject p : cells) {
                names.addAll(p.getMeasurements().keySet());
            }
            if (audit != null) {
                audit.addImage(entry.getID(), entry.getImageName(), cells, null);
            }
            byImage.put(entry.getImageName(), names);
        }
        return new BatchMeasurements(byImage, unreadable);
    }

    /** @return every measurement name found, in first-seen order */
    public Set<String> names() { return union; }

    /** @return the images that could not be read, as "name: reason" */
    public List<String> getUnreadable() { return unreadable; }

    /** @return the number of images with detections that were read */
    public int imageCount() { return namesByImage.size(); }

    /**
     * @param measurement a measurement name
     * @return the images (with detections) that lack it
     */
    public List<String> imagesWithout(String measurement) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : namesByImage.entrySet()) {
            if (!e.getValue().contains(measurement)) out.add(e.getKey());
        }
        return out;
    }

    /**
     * The measurements batch clustering uses when {@code clustering.measurements}
     * is left out: every "mean" measurement QP-CAT did not write itself
     * ({@link MeasurementExtractor#presetMeasurements}), present in every image.
     *
     * @return the measurement names, in first-seen order
     */
    public List<String> defaultClusteringMeasurements() {
        List<String> inEvery = new ArrayList<>();
        for (String n : union) {
            if (imagesWithout(n).isEmpty()) inEvery.add(n);
        }
        return MeasurementExtractor.presetMeasurements(inEvery);
    }

    /**
     * How one name from the batch file resolved.
     *
     * @param requested  the name as written
     * @param resolved   the measurement it stands for, or null
     * @param candidates the measurements it could mean when it did not resolve
     * @param ambiguous  true when it matched more than one measurement
     */
    public record Resolution(String requested, String resolved, List<String> candidates,
                             boolean ambiguous) {}

    /**
     * Resolve one name: exact match, else a unique match on the shortened name.
     *
     * @param requested the name as written
     * @param names     the measurement names to resolve against
     * @return the resolution; {@code resolved} is null when it did not resolve
     */
    public static Resolution resolve(String requested, Collection<String> names) {
        if (names.contains(requested)) return new Resolution(requested, requested, List.of(), false);
        List<String> byShortName = new ArrayList<>();
        for (String n : names) {
            if (MeasurementNames.shorten(n).equals(requested)) byShortName.add(n);
        }
        if (byShortName.size() == 1) {
            return new Resolution(requested, byShortName.get(0), List.of(), false);
        }
        if (byShortName.size() > 1) return new Resolution(requested, null, byShortName, true);
        List<String> byMarker = new ArrayList<>();
        for (String n : names) {
            if (MeasurementNames.split(n).marker().equals(requested)) byMarker.add(n);
        }
        return new Resolution(requested, null, byMarker, false);
    }

    /** What the batch runs with for one project, once every name is resolved. */
    public static final class Plan {
        private final List<ValidationIssue> issues = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private List<String> clusteringMeasurements;
        private boolean clusteringDefaulted;
        private final List<BatchYamlSchema.PhenotypeRuleEntry> rules = new ArrayList<>();
        private final List<String> phenotypingMeasurements = new ArrayList<>();

        /** @return E010 errors and W010 warnings */
        public List<ValidationIssue> getIssues() { return issues; }

        /** @return lines saying which names were resolved or defaulted, for stdout */
        public List<String> getNotes() { return notes; }

        /** @return the measurements clustering uses, or null when the run does not cluster */
        public List<String> getClusteringMeasurements() { return clusteringMeasurements; }

        /** @return true when {@code clustering.measurements} was left out */
        public boolean isClusteringDefaulted() { return clusteringDefaulted; }

        /** @return the rules with every marker replaced by its measurement name */
        public List<BatchYamlSchema.PhenotypeRuleEntry> getRules() { return rules; }

        /**
         * @return the measurements phenotyping reads: the rules' markers in rule order
         *         (the clustering panel when the rules test classes only)
         */
        public List<String> getPhenotypingMeasurements() { return phenotypingMeasurements; }

        /** @return true when the plan holds an error */
        public boolean hasErrors() {
            return issues.stream().anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR);
        }
    }

    /**
     * Resolve the clustering measurements and phenotyping rule markers of a
     * validated batch file against this project's images.
     *
     * @param schema       the validated batch file
     * @param clusters     true when the run clusters (not reuse_saved / skip)
     * @param projectLabel the project, for messages
     * @return the plan for this project
     */
    public Plan plan(BatchYamlSchema schema, boolean clusters, String projectLabel) {
        Plan plan = new Plan();
        String where = " in project '" + BatchYamlParser.asciiSafe(projectLabel) + "'";

        if (clusters) {
            List<String> requested = schema.getClustering().getMeasurements();
            if (requested == null || requested.isEmpty()) {
                plan.clusteringDefaulted = true;
                plan.clusteringMeasurements = defaultClusteringMeasurements();
                plan.notes.add("clustering.measurements not set: using every 'mean' measurement "
                        + "not written by QP-CAT and present in every image ("
                        + plan.clusteringMeasurements.size() + ")" + where + ": "
                        + quoted(plan.clusteringMeasurements, Integer.MAX_VALUE));
                if (plan.clusteringMeasurements.isEmpty()) {
                    plan.issues.add(ValidationIssue.error("E010", "clustering.measurements",
                            "not set, and no 'mean' measurement is present in every image" + where
                            + "; list the measurements to cluster on"));
                }
            } else {
                plan.clusteringMeasurements = new ArrayList<>();
                for (int i = 0; i < requested.size(); i++) {
                    String resolved = resolveOne(requested.get(i), "clustering.measurements[" + i
                            + "]", "measurement", false, where, plan);
                    if (resolved != null) plan.clusteringMeasurements.add(resolved);
                }
                List<String> resolved = plan.clusteringMeasurements;
                if (new LinkedHashSet<>(resolved).size() < resolved.size()) {
                    plan.issues.add(ValidationIssue.error("E023", "clustering.measurements",
                            "two entries resolve to the same measurement" + where + ": "
                            + quoted(resolved, Integer.MAX_VALUE)));
                }
            }
        }

        BatchYamlSchema.PhenotypingBlock pb = schema.getPhenotyping();
        if (pb != null && pb.isEnabled() && pb.getRules() != null) {
            Set<String> used = new LinkedHashSet<>();
            for (int r = 0; r < pb.getRules().size(); r++) {
                BatchYamlSchema.PhenotypeRuleEntry rule = pb.getRules().get(r);
                String base = "phenotyping.rules[" + r + "]";
                Map<String, String> renamed = new LinkedHashMap<>();
                resolveMarkers(rule.getRequireMarkers(), base + ".require_markers", where, plan,
                        renamed);
                resolveMarkers(rule.getExcludeMarkers(), base + ".exclude_markers", where, plan,
                        renamed);
                resolveMarkers(rule.getAnyMarkers(), base + ".any_markers", where, plan, renamed);
                BatchYamlSchema.PhenotypeRuleEntry copy =
                        rule.withMarkers(m -> renamed.getOrDefault(m, m));
                plan.rules.add(copy);
                used.addAll(copy.getRequireMarkers());
                used.addAll(copy.getExcludeMarkers());
                used.addAll(copy.getAnyMarkers());
            }
            plan.phenotypingMeasurements.addAll(used);
            if (used.isEmpty()) {
                // Class conditions only: the matrix is not gated, so any panel will do.
                plan.phenotypingMeasurements.addAll(plan.clusteringMeasurements != null
                        ? plan.clusteringMeasurements : defaultClusteringMeasurements());
            }
            // Two spellings of one marker can carry different gates only after resolving.
            ValidationResult gates = new ValidationResult();
            BatchYamlValidator.validateGateConsistency(plan.rules, gates);
            plan.issues.addAll(gates.getIssues());
        }
        return plan;
    }

    private void resolveMarkers(List<String> markers, String path, String where, Plan plan,
                                Map<String, String> renamed) {
        if (markers == null) return;
        for (String m : markers) {
            String resolved = resolveOne(m, path, "marker", true, where, plan);
            if (resolved != null) renamed.put(m, resolved);
        }
    }

    /**
     * Resolve one name, recording E010 / W010 and a note when a short name was
     * expanded. A rule marker some images lack is an error (phenotyping drops a
     * measurement missing from a whole image, and the rule would then match no
     * cells); a clustering measurement some images lack is a warning, since the
     * clustering run drops and reports it.
     */
    private String resolveOne(String requested, String path, String kind, boolean strictPresence,
                              String where, Plan plan) {
        Resolution res = resolve(requested, union);
        String req = "'" + BatchYamlParser.asciiSafe(requested) + "'";
        if (res.resolved() == null) {
            String message;
            if (res.ambiguous()) {
                message = req + " matches " + res.candidates().size() + " measurements" + where
                        + ": " + quoted(res.candidates(), MAX_LISTED)
                        + ". Write the full name of the one you mean, in quotes.";
            } else if (!res.candidates().isEmpty()) {
                message = req + " is not a measurement name; it is the marker in "
                        + res.candidates().size() + " measurements" + where + ": "
                        + quoted(res.candidates(), MAX_LISTED)
                        + ". Write the full name of the one you mean, in quotes.";
            } else {
                String guess = BatchYamlParser.suggest(requested, union);
                message = req + " is not a measurement of the " + imageCount()
                        + " image(s) read" + where
                        + ("?".equals(guess) ? "" : ". Did you mean '"
                                + BatchYamlParser.asciiSafe(guess) + "'?");
            }
            plan.issues.add(ValidationIssue.error("E010", path, kind + " " + message));
            return null;
        }
        if (!res.resolved().equals(requested)) {
            plan.notes.add(path + ": " + req + " resolved to '"
                    + BatchYamlParser.asciiSafe(res.resolved()) + "'");
        }
        List<String> lacking = imagesWithout(res.resolved());
        if (!lacking.isEmpty()) {
            String msg = "'" + BatchYamlParser.asciiSafe(res.resolved()) + "' is missing from "
                    + lacking.size() + " of " + imageCount() + " images" + where + ": "
                    + quoted(lacking, MAX_LISTED);
            plan.issues.add(strictPresence
                    ? ValidationIssue.error("E010", path, kind + " " + msg
                            + ". The rule could not be tested there; leave those images out "
                            + "of scope.images or add the measurement.")
                    : ValidationIssue.warning("W010", path, kind + " " + msg
                            + ". Clustering leaves it out of any run that includes one of "
                            + "those images."));
        }
        return res.resolved();
    }

    private static String quoted(List<String> names, int max) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.size() && i < max; i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(BatchYamlParser.asciiSafe(names.get(i))).append('\'');
        }
        if (names.size() > max) sb.append(", ... ").append(names.size() - max).append(" more");
        return sb.append(']').toString();
    }
}
