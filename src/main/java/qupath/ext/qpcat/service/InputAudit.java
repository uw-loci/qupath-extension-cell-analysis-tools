package qupath.ext.qpcat.service;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one check every workflow makes on the data it is about to analyze, before
 * any work: imported data must never change a result without the user being told.
 *
 * <p>A workflow builds one audit, adds each image's objects as it loads them (one
 * pass over the objects, nothing kept but counts) and asks for the
 * {@link InputReport}: <b>refusals</b> stop the run with one message, <b>notices</b>
 * are shown once and saved with the result. The checks themselves are the pure
 * functions in {@link InputChecks}; {@link PixelSizes} and
 * {@link MeasurementExtractor.MissingCounter} are reused, not repeated.
 *
 * <p>What each workflow refuses and what it only notes is decided here, in one
 * place, by {@link Workflow}.
 */
public final class InputAudit {

    /** What a workflow does about objects without an outline (a null or empty ROI). */
    public enum Outlines {
        /** Refuse the run: every object must have a position (cell references, distances). */
        REFUSE,
        /** The workflow leaves them out; the audit says how many. */
        EXCLUDE,
        /** Positions are not used; nothing to say. */
        IGNORE
    }

    /** The workflows that audit their input, and how strict each is. */
    public enum Workflow {
        CLUSTERING("Clustering", true, Outlines.REFUSE),
        PHENOTYPING("Phenotyping", false, Outlines.IGNORE),
        CELLULAR_NEIGHBORHOODS("Cellular neighborhoods", true, Outlines.REFUSE),
        SPATIAL_STATISTICS("Spatial statistics", true, Outlines.REFUSE),
        ANNDATA_EXPORT("AnnData export", true, Outlines.REFUSE),
        ANNDATA_IMPORT("AnnData import", true, Outlines.IGNORE),
        VEST_EXPORT("VEST export", false, Outlines.EXCLUDE),
        CROP_TABLE_EXPORT("Crop and table export", false, Outlines.EXCLUDE);

        private final String label;
        private final boolean refusesRepeatedIds;
        private final Outlines outlines;

        Workflow(String label, boolean refusesRepeatedIds, Outlines outlines) {
            this.label = label;
            this.refusesRepeatedIds = refusesRepeatedIds;
            this.outlines = outlines;
        }

        /** The name used in messages. */
        public String label() { return label; }

        /**
         * Whether repeated object ids refuse the run. They do where cells are later
         * found again by id (saved results, gates, imports, an AnnData file's cell
         * names); an export or phenotyping run notes them, one row per copy.
         */
        public boolean refusesRepeatedIds() { return refusesRepeatedIds; }

        /**
         * What objects without an outline do: refuse a run that records or measures
         * positions (a clustering result's cell references, distances, the AnnData
         * file's spatial coordinates); the two crop exports leave them out with a
         * notice; phenotyping and the import never use a position.
         */
        public Outlines outlines() { return outlines; }
    }

    /** A run refused by the audit; the message is for the user. */
    public static final class InputRefusedException extends IOException {
        private static final long serialVersionUID = 1L;

        public InputRefusedException(String message) {
            super(message);
        }
    }

    private record ImageTally(String id, String name, InputChecks.Tally tally,
                              double pixelSizeUm, double pixelWidthUm, double pixelHeightUm) {
        double anisotropy() {
            return InputChecks.pixelAnisotropy(pixelWidthUm, pixelHeightUm);
        }
    }

    /** Pixels whose width and height differ by more than this fraction are reported. */
    static final double ANISOTROPY_NOTICE = 0.01;

    private final Workflow workflow;
    private List<String> measurements = List.of();
    private boolean reportMissing;
    private boolean spatial;
    private Collection<String> reservedColumns = List.of();
    private String reservedOutput;
    private final List<ImageTally> images = new ArrayList<>();

    private InputAudit(Workflow workflow) {
        this.workflow = workflow;
    }

    /**
     * Start the audit of one run.
     *
     * @param workflow what is about to run
     * @return the audit, to configure and fill
     */
    public static InputAudit of(Workflow workflow) {
        return new InputAudit(workflow);
    }

    /**
     * The measurements the run uses: their names are checked, and with
     * {@code reportMissing} their missing or infinite values are counted and noted.
     * A workflow with a missing-values policy of its own (clustering) passes false:
     * its policy reports them.
     */
    public InputAudit measurements(List<String> names, boolean reportMissing) {
        this.measurements = names == null ? List.of() : List.copyOf(new LinkedHashSet<>(names));
        this.reportMissing = reportMissing && !this.measurements.isEmpty();
        return this;
    }

    /** Whether the run measures distances between cells (refuses across planes). */
    public InputAudit spatial(boolean spatial) {
        this.spatial = spatial;
        return this;
    }

    /**
     * Column names the output adds itself; a measurement of the same name is noted.
     *
     * @param names  the output's own column names
     * @param output what the output is, for the message ("the AnnData file")
     */
    public InputAudit reservedColumns(Collection<String> names, String output) {
        this.reservedColumns = names == null ? List.of() : names;
        this.reservedOutput = output;
        return this;
    }

    /**
     * Count one image's objects: exactly those the run will analyze.
     *
     * @param imageId   project entry id, or null
     * @param imageName display name
     * @param objects   the objects; walked once, not kept
     * @param server    the image's server for its pixel size, or null
     */
    public void addImage(String imageId, String imageName,
                         Collection<? extends PathObject> objects, ImageServer<?> server) {
        InputChecks.Tally tally = new InputChecks.Tally(reportMissing ? measurements : null);
        tally.addAll(objects);
        double w = Double.NaN;
        double h = Double.NaN;
        double um = PixelSizes.umOrNaN(server);
        if (PixelSizes.isCalibrated(um)) {
            try {
                PixelCalibration cal = server.getPixelCalibration();
                if (cal != null && cal.hasPixelSizeMicrons()) {
                    w = cal.getPixelWidthMicrons();
                    h = cal.getPixelHeightMicrons();
                }
            } catch (RuntimeException e) {
                // An unreadable calibration is no calibration (PixelSizes.umOrNaN).
            }
        }
        images.add(new ImageTally(imageId, imageName, tally, um, w, h));
    }

    /** The refusals and notices for every image added so far. */
    public InputReport report() {
        List<String> refusals = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        List<String> names = new ArrayList<>();
        for (ImageTally t : images) names.add(t.name());
        Map<String, Integer> dupNames = InputChecks.duplicateNames(names);

        Map<String, Integer> noOutline = new LinkedHashMap<>();
        Map<String, Integer> repeated = new LinkedHashMap<>();
        Map<String, Set<String>> planes = new LinkedHashMap<>();
        Set<String> classNames = new LinkedHashSet<>();
        List<MeasurementExtractor.MissingReport> missing = new ArrayList<>();
        List<PixelSizes.NamedPixelSize> sizes = new ArrayList<>();
        List<String> nonSquare = new ArrayList<>();
        int cells = 0;
        int tiles = 0;
        int other = 0;
        int objects = 0;
        for (ImageTally t : images) {
            String label = InputChecks.imageLabel(t.name(), t.id(), dupNames);
            InputChecks.Tally tally = t.tally();
            objects += tally.objects();
            cells += tally.cells();
            tiles += tally.tiles();
            other += tally.otherDetections();
            noOutline.put(label, tally.withoutOutline());
            repeated.put(label, tally.repeatedIds());
            planes.put(label, tally.planes());
            classNames.addAll(tally.classNames());
            missing.add(tally.missing());
            sizes.add(new PixelSizes.NamedPixelSize(label, t.pixelSizeUm()));
            if (t.anisotropy() > ANISOTROPY_NOTICE) {
                nonSquare.add(label + " (" + um(t.pixelWidthUm()) + " x " + um(t.pixelHeightUm())
                        + " um)");
            }
        }

        String outlines = InputChecks.noOutlineRefusal(noOutline);
        if (outlines != null && workflow.outlines() == Outlines.REFUSE) {
            refusals.add(outlines + " " + InputChecks.NO_OUTLINE_FIX);
        } else if (outlines != null && workflow.outlines() == Outlines.EXCLUDE) {
            notices.add(outlines + " They are left out.");
        }
        String rep = InputChecks.repeatedIdMessage(repeated);
        if (rep != null) {
            if (workflow.refusesRepeatedIds()) {
                refusals.add(rep + " " + InputChecks.REPEATED_ID_FIX);
            } else {
                notices.add(rep + " Each copy is its own row.");
            }
        }
        add(spatial ? refusals : notices,
                InputChecks.planeMessage(InputChecks.multiPlaneImages(planes), spatial));
        // A workflow whose distances must be in microns refuses an uncalibrated image
        // itself, before loading (PixelSizes.refusalMessage); here a spatial run that
        // goes ahead in pixels on some images says so.
        if (spatial) {
            List<String> px = PixelSizes.uncalibrated(sizes);
            if (!px.isEmpty() && px.size() < sizes.size()) {
                notices.add("No pixel size on " + InputChecks.listed(px) + ": distances there "
                        + "are in pixels.");
            }
            // Non-square pixels: distances use one pixel size, the average of width and
            // height, as QuPath's own measurements do. Said once so the rounding is known.
            if (!nonSquare.isEmpty()) {
                notices.add("Pixel width and height differ on " + InputChecks.listed(nonSquare)
                        + ": distances use their average, as QuPath's own measurements do.");
            }
        }

        add(notices, InputChecks.tileNotice(cells, tiles, other));
        add(notices, InputChecks.duplicateNameNotice(dupNames));
        notices.addAll(InputChecks.measurementNameNotices(measurements));
        add(notices, InputChecks.reservedNameNotice(measurements, reservedColumns, reservedOutput));
        notices.addAll(InputChecks.classNameNotices(classNames));
        if (reportMissing) {
            add(notices, InputChecks.missingNotice(InputChecks.merge(missing)));
        }
        String scope = workflow.label() + " on " + InputChecks.count(objects) + " object(s) in "
                + images.size() + " image(s)" + (images.isEmpty() ? "" : ": "
                + InputChecks.listed(names));
        return new InputReport(workflow, refusals, notices, scope);
    }

    /** A pixel size to four significant digits, no trailing zeros: 0.5, 0.65, 0.3245. */
    private static String um(double value) {
        return new BigDecimal(value).round(new MathContext(4)).stripTrailingZeros().toPlainString();
    }

    private static void add(List<String> list, String message) {
        if (message != null) list.add(message);
    }

    /** What the audit found: refusals block the run, notices are told once and saved. */
    public static final class InputReport {
        private final Workflow workflow;
        private final List<String> refusals;
        private final List<String> notices;
        private final String scope;

        InputReport(Workflow workflow, List<String> refusals, List<String> notices, String scope) {
            this.workflow = workflow;
            this.refusals = List.copyOf(refusals);
            this.notices = List.copyOf(notices);
            this.scope = scope;
        }

        public Workflow workflow() { return workflow; }
        public List<String> refusals() { return refusals; }
        public List<String> notices() { return notices; }

        /** "Clustering on 1,234 object(s) in 2 image(s): a.tif, b.tif". */
        public String scope() { return scope; }

        public boolean isRefused() { return !refusals.isEmpty(); }

        /** Every refusal in one message, or null when the run may go ahead. */
        public String refusalMessage() {
            if (refusals.isEmpty()) return null;
            return workflow.label() + " was not run.\n\n" + String.join("\n\n", refusals);
        }

        /**
         * Stop the run when it is refused.
         *
         * @throws InputRefusedException with {@link #refusalMessage()}
         */
        public InputReport requireAccepted() throws InputRefusedException {
            if (isRefused()) throw new InputRefusedException(refusalMessage());
            return this;
        }
    }
}
