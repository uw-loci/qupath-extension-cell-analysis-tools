package qupath.ext.qpcat.service;

import qupath.ext.qpcat.model.CellRef;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathTileObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The checks {@link InputAudit} makes on imported data, each a pure function of
 * the objects or names it is given, so each can be tested on its own.
 *
 * <p>One {@link Tally} per image walks the objects once and collects everything
 * the object checks need; the message builders turn tallies and names into the
 * sentences a user reads.
 */
public final class InputChecks {

    /** How many names a message lists before "and N more". */
    static final int MAX_LISTED = 5;

    /** What to do about detections that repeat an object id. */
    public static final String REPEATED_ID_FIX = "To remove the repeats (keeping the first "
            + "copy of each), run this script on the image: def seen = [] as Set; "
            + "removeObjects(getDetectionObjects().findAll { !seen.add(it.getID()) }, true). "
            + "If the copies are different cells, give them new ids instead by replacing "
            + "removeObjects(...) with .each { it.refreshID() } (earlier saved results then no "
            + "longer find those cells).";

    /** What to do about objects without an outline. */
    public static final String NO_OUTLINE_FIX = "Such objects usually come from a GeoJSON "
            + "feature with no geometry. To remove them, run this script on the image: "
            + "removeObjects(getDetectionObjects().findAll { it.getROI() == null "
            + "|| it.getROI().isEmpty() }, true)";

    private InputChecks() {}

    /** One image's objects, counted in one pass. */
    public static final class Tally {
        private int objects;
        private int cells;
        private int tiles;
        private int otherDetections;
        private int withoutOutline;
        private final CellMatcher.KeyIndex<Boolean> ids = new CellMatcher.KeyIndex<>();
        private final Set<String> planes = new TreeSet<>();
        private final Set<String> classNames = new LinkedHashSet<>();
        private final MeasurementExtractor.MissingCounter missing;

        /** @param measurements measurements whose missing values to count; null or empty for none */
        public Tally(List<String> measurements) {
            this.missing = measurements == null || measurements.isEmpty()
                    ? null : new MeasurementExtractor.MissingCounter(measurements);
        }

        /** Count one object. */
        public void add(PathObject p) {
            if (p == null) return;
            objects++;
            if (p.isCell()) {
                cells++;
            } else if (p instanceof PathTileObject) {
                tiles++;
            } else {
                otherDetections++;
            }
            var roi = p.getROI();
            if (roi == null || roi.isEmpty()) {
                withoutOutline++;
            } else {
                planes.add(planeLabel(roi.getZ(), roi.getT()));
            }
            ids.add(CellRef.idOf(p), Boolean.TRUE);
            String cls = CellClasses.nameOf(p);
            if (cls != null) classNames.add(cls);
            if (missing != null) missing.add(p);
        }

        /** Count every object of a collection. */
        public Tally addAll(Collection<? extends PathObject> objs) {
            if (objs != null) for (PathObject p : objs) add(p);
            return this;
        }

        public int objects() { return objects; }
        public int cells() { return cells; }
        public int tiles() { return tiles; }
        public int otherDetections() { return otherDetections; }
        public int withoutOutline() { return withoutOutline; }
        /** Objects whose id an earlier object already had. */
        public int repeatedIds() { return ids.repeats(); }
        /** Ids more than one object has. */
        public int sharedIds() { return ids.sharedKeys(); }
        /** The z-slice / time point pairs the outlines lie on, as "z=0, t=0". */
        public Set<String> planes() { return planes; }
        public Set<String> classNames() { return classNames; }
        /** Missing-value counts, or null when no measurements were given. */
        public MeasurementExtractor.MissingReport missing() {
            return missing == null ? null : missing.report();
        }
    }

    /**
     * An image's name for a message or a table: the name, followed by its entry id
     * when another image in the same run has that name.
     *
     * @param name     the display name
     * @param id       the project entry id, or null
     * @param dupNames from {@link #duplicateNames}
     * @return the label
     */
    public static String imageLabel(String name, String id, Map<String, Integer> dupNames) {
        return dupNames.containsKey(String.valueOf(name)) && id != null
                ? name + " [entry " + id + "]" : name;
    }

    /** The plane label used in messages. */
    static String planeLabel(int z, int t) {
        return "z=" + z + ", t=" + t;
    }

    /**
     * The sentence naming the analyzed object types when tiles are among them.
     *
     * @param cells   cell objects
     * @param tiles   tile objects
     * @param other   other detections
     * @return the notice, or null when no tile is analyzed
     */
    public static String tileNotice(int cells, int tiles, int other) {
        if (tiles == 0) return null;
        return "Analyzed objects: " + count(cells) + " cells, " + count(other)
                + " other detections and " + count(tiles) + " tiles. Tiles are squares of the "
                + "image (from Create tiles or a pixel classifier), not cells, and are analyzed as "
                + "if they were cells.";
    }

    /**
     * The refusal for objects without an outline.
     *
     * @param perImage image name to the count of objects with a null or empty ROI
     * @return the message, or null when there are none
     */
    public static String noOutlineRefusal(Map<String, Integer> perImage) {
        Map<String, Integer> hits = nonZero(perImage);
        if (hits.isEmpty()) return null;
        int total = hits.values().stream().mapToInt(Integer::intValue).sum();
        return count(total) + " object(s) have no outline (an empty or missing ROI), so QP-CAT "
                + "cannot place them: " + listCounts(hits) + ". " + NO_OUTLINE_FIX;
    }

    /**
     * The message for detections that repeat an object id within an image.
     *
     * @param perImage image name to the count of repeated objects
     * @return the message, or null when there are none
     */
    public static String repeatedIdMessage(Map<String, Integer> perImage) {
        Map<String, Integer> hits = nonZero(perImage);
        if (hits.isEmpty()) return null;
        int total = hits.values().stream().mapToInt(Integer::intValue).sum();
        return count(total) + " detection(s) repeat another detection's object id in the same "
                + "image (" + listCounts(hits) + "), usually because the same objects were "
                + "imported twice, so each repeated cell is counted twice and cannot be told "
                + "apart from its copy later.";
    }

    /**
     * The planes an analysis spans, when there is more than one.
     *
     * @param planesByImage image name to its planes
     * @return image name to its planes, for the images on more than one plane, in order
     */
    public static Map<String, Set<String>> multiPlaneImages(Map<String, Set<String>> planesByImage) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        planesByImage.forEach((image, planes) -> {
            if (planes.size() > 1) out.put(image, planes);
        });
        return out;
    }

    /**
     * The sentence for objects on several z-slices or time points of one image.
     *
     * @param multi  image name to planes, from {@link #multiPlaneImages}
     * @param refuse true when the analysis measures distances between cells
     * @return the message, or null when every image is on one plane
     */
    public static String planeMessage(Map<String, Set<String>> multi, boolean refuse) {
        if (multi.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        multi.forEach((image, planes) -> parts.add(image + " (" + planes.size() + " planes)"));
        String where = listed(parts);
        if (refuse) {
            return "Cells lie on more than one z-slice or time point: " + where + ". Distances "
                    + "between cells are measured in x and y only, so cells on different planes "
                    + "would become neighbors. Select an annotation on one plane (only the cells "
                    + "on that plane are then used) or run on an image with one plane.";
        }
        return "Cells from more than one z-slice or time point are pooled into one analysis: "
                + where + ".";
    }

    /**
     * Groups of images in scope that share a name.
     *
     * @param names one name per image, in scope order
     * @return name to how many images carry it, for names used more than once
     */
    public static Map<String, Integer> duplicateNames(List<String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String n : names) counts.merge(String.valueOf(n), 1, Integer::sum);
        counts.values().removeIf(c -> c < 2);
        return counts;
    }

    /**
     * The sentence for images in scope that share a name.
     *
     * @param dups from {@link #duplicateNames}
     * @return the notice, or null when every name is distinct
     */
    public static String duplicateNameNotice(Map<String, Integer> dups) {
        if (dups.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        dups.forEach((name, n) -> parts.add("'" + name + "' (" + n + " images)"));
        return "Several images in this run share a name: " + listed(parts) + ". QP-CAT keeps them "
                + "apart by their project entry, but tables and figures that show only the name "
                + "cannot; rename them in the project to tell them apart.";
    }

    /**
     * Problems with measurement names that QuPath accepts but that make two names look
     * alike or a typed name miss: spaces at either end, control characters, and names
     * equal once trimmed and case-folded. Each is one sentence.
     *
     * @param names the measurement names in play
     * @return the sentences, empty when every name is clean
     */
    public static List<String> measurementNameNotices(List<String> names) {
        List<String> out = new ArrayList<>();
        if (names == null) return out;
        List<String> untrimmed = new ArrayList<>();
        List<String> control = new ArrayList<>();
        Map<String, List<String>> folded = new LinkedHashMap<>();
        for (String n : names) {
            if (n == null) continue;
            if (!n.equals(n.strip())) untrimmed.add("'" + n + "'");
            if (n.codePoints().anyMatch(Character::isISOControl)) control.add(printable(n));
            folded.computeIfAbsent(n.strip().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(n);
        }
        if (!untrimmed.isEmpty()) {
            out.add("Measurement names with a space at the start or end: " + listed(untrimmed)
                    + ". A name typed without the space does not find them.");
        }
        if (!control.isEmpty()) {
            out.add("Measurement names with a line break, tab or other control character: "
                    + listed(control) + ".");
        }
        List<String> alike = new ArrayList<>();
        folded.values().forEach(group -> {
            if (group.size() > 1) alike.add(String.join(" / ", group.stream()
                    .map(g -> "'" + printable(g) + "'").toList()));
        });
        if (!alike.isEmpty()) {
            out.add("Measurements that differ only in case or spaces are used as separate "
                    + "measurements: " + listed(alike) + ". If they are one marker imported twice, "
                    + "it counts twice.");
        }
        return out;
    }

    /**
     * Measurement names that are also the name of a column the output adds.
     *
     * @param names    the measurement names
     * @param reserved the output's own column names
     * @param output   what the output is, for the message
     * @return the notice, or null when none collides
     */
    public static String reservedNameNotice(List<String> names, Collection<String> reserved,
                                            String output) {
        if (names == null || reserved == null || reserved.isEmpty()) return null;
        List<String> hits = new ArrayList<>();
        for (String n : names) {
            if (n != null && reserved.contains(n)) hits.add("'" + n + "'");
        }
        if (hits.isEmpty()) return null;
        return "Measurement(s) " + listed(hits) + " share a name with a column " + output
                + " adds for its own use; tools that look a column up by name (scanpy's "
                + "sc.get.obs_df, for one) refuse the name as ambiguous.";
    }

    /**
     * Class names on the analyzed cells that QP-CAT cannot write back unchanged, or that
     * look like the word it shows for "no class".
     *
     * @param classNames the class names in play
     * @return the sentences, empty when every name is plain
     */
    public static List<String> classNameNotices(Collection<String> classNames) {
        List<String> out = new ArrayList<>();
        if (classNames == null) return out;
        List<String> reserved = new ArrayList<>();
        List<String> unusable = new ArrayList<>();
        for (String c : classNames) {
            if (c == null) continue;
            if (CellClasses.isReservedName(c)) reserved.add("'" + c + "'");
            try {
                CellClasses.canonicalName(c, "Class");
            } catch (CellClasses.ClassNameException e) {
                unusable.add(e.getMessage());
            }
        }
        if (!reserved.isEmpty()) {
            out.add("Real class(es) " + listed(reserved) + " are spelled like the word QP-CAT "
                    + "shows for cells with no class; those cells are shown as '"
                    + CellClasses.UNCLASSIFIED_DISPLAY + " (no class)' instead.");
        }
        out.addAll(unusable);
        return out;
    }

    /**
     * How far a pixel is from square.
     *
     * @param widthUm  pixel width
     * @param heightUm pixel height
     * @return |w - h| / max(w, h), or NaN when either is unknown
     */
    public static double pixelAnisotropy(double widthUm, double heightUm) {
        if (!PixelSizes.isCalibrated(widthUm) || !PixelSizes.isCalibrated(heightUm)) {
            return Double.NaN;
        }
        return Math.abs(widthUm - heightUm) / Math.max(widthUm, heightUm);
    }

    /**
     * The sentence for missing or infinite values of the measurements in play.
     *
     * @param report from {@link Tally#missing()}, merged over the images
     * @return the notice, or null when nothing is missing
     */
    public static String missingNotice(MeasurementExtractor.MissingReport report) {
        if (report == null || !report.any()) return null;
        List<String> parts = new ArrayList<>();
        report.perMeasurement().forEach((m, n) -> parts.add("'" + m + "' (" + count(n) + ")"));
        return count(report.objectsWithMissing()) + " of " + count(report.totalObjects())
                + " objects lack a finite value for a measurement in use: " + listed(parts) + ".";
    }

    /** Merge per-image missing reports into one. */
    public static MeasurementExtractor.MissingReport merge(
            List<MeasurementExtractor.MissingReport> reports) {
        int total = 0;
        int with = 0;
        Map<String, Integer> per = new LinkedHashMap<>();
        for (MeasurementExtractor.MissingReport r : reports) {
            if (r == null) continue;
            total += r.totalObjects();
            with += r.objectsWithMissing();
            r.perMeasurement().forEach((m, n) -> per.merge(m, n, Integer::sum));
        }
        return new MeasurementExtractor.MissingReport(total, with, per);
    }

    // ---- text helpers ----

    static String count(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static Map<String, Integer> nonZero(Map<String, Integer> perImage) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (perImage != null) {
            perImage.forEach((k, v) -> {
                if (v != null && v > 0) out.put(k, v);
            });
        }
        return out;
    }

    private static String listCounts(Map<String, Integer> hits) {
        List<String> parts = new ArrayList<>();
        hits.forEach((image, n) -> parts.add(image + ": " + count(n)));
        return listed(parts);
    }

    /** Up to {@link #MAX_LISTED} items, then "and N more". */
    static String listed(List<String> items) {
        if (items.size() <= MAX_LISTED) return String.join(", ", items);
        return String.join(", ", items.subList(0, MAX_LISTED)) + " and "
                + (items.size() - MAX_LISTED) + " more";
    }

    private static String printable(String name) {
        StringBuilder sb = new StringBuilder();
        name.codePoints().forEach(c -> {
            if (Character.isISOControl(c)) {
                sb.append(String.format(Locale.ROOT, "\\u%04x", c));
            } else {
                sb.appendCodePoint(c);
            }
        });
        return sb.toString();
    }
}
