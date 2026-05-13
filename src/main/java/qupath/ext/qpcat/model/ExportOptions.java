package qupath.ext.qpcat.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain Java options POJO for batch figure export. Gson-serialisable; no
 * JavaFX dependencies. Constructed by the v1 dialog and by Feature C's
 * YAML batch executor; consumed by
 * {@code BatchFigureExporter.exportProject(...)}.
 * <p>
 * Field shape is part of the locked cross-feature contract. Adding new
 * fields is allowed; renaming or repurposing existing fields is a
 * breaking change.
 */
public final class ExportOptions {

    /** Image-subset scope. */
    public enum Scope {
        /** Only the image currently open in QuPath's viewer. */
        CURRENT,
        /** Every image in the open project. */
        ALL,
        /** A user-picked subset of images by name (see {@link #imageSubset}). */
        SUBSET
    }

    /** Default filename pattern; must include {@code {image}}, {@code {plot}}, {@code {ext}}. */
    public static final String DEFAULT_PATTERN = "{image}_{plot}.{ext}";

    /** Default DPI for raster output. */
    public static final int DEFAULT_DPI = 300;

    private Scope scope = Scope.CURRENT;
    private List<String> imageSubset;                    // null unless scope == SUBSET
    private Set<PlotKind> plotKinds = new LinkedHashSet<>();
    private Set<OutputFormat> outputFormats = EnumSet.of(OutputFormat.PNG);
    private int dpi = DEFAULT_DPI;
    private Path outputDir;
    private String filenamePattern = DEFAULT_PATTERN;
    /** Saved-result name (clustering result key under qpcat/cluster_results/). Optional. */
    private String resultName;
    /** When true, overwrite existing files; when false, the exporter fails-fast on collision. */
    private boolean overwriteExisting = false;
    /** When true, missing plots are recorded as failures but the export continues. */
    private boolean skipMissingPlots = true;

    public ExportOptions() {}

    public Scope getScope() { return scope; }
    public void setScope(Scope scope) { this.scope = scope == null ? Scope.CURRENT : scope; }

    /** Image names (project entry names) when {@link #scope} == SUBSET; null otherwise. */
    public List<String> getImageSubset() { return imageSubset; }
    public void setImageSubset(List<String> imageSubset) {
        this.imageSubset = imageSubset == null ? null : new ArrayList<>(imageSubset);
    }

    public Set<PlotKind> getPlotKinds() { return plotKinds; }
    public void setPlotKinds(Set<PlotKind> plotKinds) {
        this.plotKinds = plotKinds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(plotKinds);
    }

    public Set<OutputFormat> getOutputFormats() { return outputFormats; }
    public void setOutputFormats(Set<OutputFormat> outputFormats) {
        this.outputFormats = outputFormats == null || outputFormats.isEmpty()
                ? EnumSet.of(OutputFormat.PNG)
                : EnumSet.copyOf(outputFormats);
    }

    public int getDpi() { return dpi; }
    public void setDpi(int dpi) { this.dpi = dpi; }

    public Path getOutputDir() { return outputDir; }
    public void setOutputDir(Path outputDir) { this.outputDir = outputDir; }

    public String getFilenamePattern() { return filenamePattern; }
    public void setFilenamePattern(String filenamePattern) {
        this.filenamePattern = (filenamePattern == null || filenamePattern.isEmpty())
                ? DEFAULT_PATTERN : filenamePattern;
    }

    public String getResultName() { return resultName; }
    public void setResultName(String resultName) { this.resultName = resultName; }

    public boolean isOverwriteExisting() { return overwriteExisting; }
    public void setOverwriteExisting(boolean overwriteExisting) {
        this.overwriteExisting = overwriteExisting;
    }

    public boolean isSkipMissingPlots() { return skipMissingPlots; }
    public void setSkipMissingPlots(boolean skipMissingPlots) {
        this.skipMissingPlots = skipMissingPlots;
    }

    /**
     * Defensive copy of this options POJO. Useful for snapshotting before
     * launching the export thread so the caller can mutate the original
     * (e.g. close the dialog) without affecting the in-flight export.
     */
    public ExportOptions copy() {
        ExportOptions c = new ExportOptions();
        c.scope = this.scope;
        c.imageSubset = this.imageSubset == null ? null : new ArrayList<>(this.imageSubset);
        c.plotKinds = new LinkedHashSet<>(this.plotKinds);
        c.outputFormats = EnumSet.copyOf(this.outputFormats);
        c.dpi = this.dpi;
        c.outputDir = this.outputDir;
        c.filenamePattern = this.filenamePattern;
        c.resultName = this.resultName;
        c.overwriteExisting = this.overwriteExisting;
        c.skipMissingPlots = this.skipMissingPlots;
        return c;
    }
}
