package qupath.ext.qpcat.scripting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpcat.model.ExportOptions;
import qupath.ext.qpcat.model.ExportResult;
import qupath.ext.qpcat.model.OutputFormat;
import qupath.ext.qpcat.model.PlotKind;
import qupath.ext.qpcat.service.BatchFigureExporter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public, FX-free, Groovy-callable facade for QP-CAT's batch figure
 * export surface. Wraps
 * {@link BatchFigureExporter#exportProject(ExportOptions, java.util.function.BiFunction)}
 * with a single options-map signature mirroring {@code SpatialStatsScripts}.
 * <p>
 * Consumed by Feature C's YAML batch executor and any user-authored
 * Groovy workflow that needs reproducible figure export.
 *
 * <p><strong>Stability promise (v1).</strong> Package path
 * ({@code qupath.ext.qpcat.scripting}), class name
 * ({@code FigureExportScripts}), method name ({@code exportFigures}),
 * and the recognised option-key set listed in the SCRIPTING.md docs are
 * part of QP-CAT's public scripting API. Breaking changes go through a
 * deprecation period of at least one minor version.</p>
 *
 * <p><strong>Headless plot scope.</strong> Only matplotlib-backed plots
 * are exportable from a script; JavaFX plots require the interactive
 * dialog. JavaFX plot kinds in the {@code plotKinds} option are recorded
 * as failures in the returned {@link ExportResult} (the script does not
 * raise).</p>
 */
public final class FigureExportScripts {

    private static final Logger logger = LoggerFactory.getLogger(FigureExportScripts.class);

    private FigureExportScripts() {}

    /**
     * Export figures from a saved clustering result for one or more
     * project images.
     *
     * <p>Recognised keys:</p>
     * <ul>
     *   <li>{@code imageNames} -- {@code List<String>}. Empty / null /
     *       containing {@code "*"} = every image in the project.</li>
     *   <li>{@code plotKinds} -- {@code List<String>}. Plot kind slugs
     *       (e.g. {@code "dotplot"}, {@code "matrixplot"}). Empty / null
     *       = every matplotlib plot kind. Unknown slugs are recorded as
     *       failures.</li>
     *   <li>{@code formats} -- {@code List<String>}. Subset of
     *       {@code "png"}, {@code "tiff"}. Default {@code ["png"]}.</li>
     *   <li>{@code dpi} -- int. Default 300.</li>
     *   <li>{@code outputDir} -- {@code String} or {@code Path}. Created
     *       if missing. Required.</li>
     *   <li>{@code filenamePattern} -- {@code String}. Must include
     *       {@code {image}}, {@code {plot}}, {@code {ext}}. Default
     *       {@code "{image}_{plot}.{ext}"}.</li>
     *   <li>{@code resultName} -- {@code String}. Saved-result name to
     *       export from. If omitted, the per-image fall-back logic in
     *       {@link BatchFigureExporter} picks the best match.</li>
     *   <li>{@code overwriteExisting} -- boolean. Default false.</li>
     *   <li>{@code skipMissingPlots} -- boolean. Default true.</li>
     * </ul>
     *
     * @param opts options map; may be null (defaults used throughout)
     * @return the populated {@link ExportResult}; never null
     * @throws IOException on output-directory / pattern errors
     */
    public static ExportResult exportFigures(Map<String, ?> opts) throws IOException {
        ExportOptions options = buildOptions(opts);
        return BatchFigureExporter.exportProject(options, null);
    }

    /**
     * Build a fully-resolved {@link ExportOptions} from a Groovy-style
     * options map, applying defaults for absent keys. Public for unit
     * tests; the dialog uses its own constructor path.
     */
    public static ExportOptions buildOptions(Map<String, ?> opts) {
        ExportOptions options = new ExportOptions();

        if (opts == null) {
            // Plot kinds default to every matplotlib-backed plot
            options.setPlotKinds(matplotlibKinds());
            return options;
        }

        // imageNames
        Object imageNamesRaw = opts.get("imageNames");
        if (imageNamesRaw instanceof List<?> list && !list.isEmpty()) {
            List<String> names = new ArrayList<>();
            boolean wildcard = false;
            for (Object o : list) {
                if (o == null) continue;
                String s = o.toString();
                if ("*".equals(s)) wildcard = true;
                else names.add(s);
            }
            if (wildcard) {
                options.setScope(ExportOptions.Scope.ALL);
            } else {
                options.setScope(ExportOptions.Scope.SUBSET);
                options.setImageSubset(names);
            }
        } else {
            options.setScope(ExportOptions.Scope.CURRENT);
        }

        // plotKinds
        Object plotKindsRaw = opts.get("plotKinds");
        Set<PlotKind> kinds = new LinkedHashSet<>();
        if (plotKindsRaw instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) {
                if (o == null) continue;
                PlotKind k = PlotKind.fromSlug(o.toString());
                if (k != null) kinds.add(k);
                else logger.warn("[figure-export] Unknown plot kind slug '{}'", o);
            }
        }
        if (kinds.isEmpty()) {
            kinds = matplotlibKinds();
        }
        options.setPlotKinds(kinds);

        // formats
        Object formatsRaw = opts.get("formats");
        Set<OutputFormat> formats = EnumSet.noneOf(OutputFormat.class);
        if (formatsRaw instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) {
                if (o == null) continue;
                OutputFormat fmt = OutputFormat.fromExtension(o.toString());
                if (fmt != null) formats.add(fmt);
                else logger.warn("[figure-export] Unknown output format '{}'", o);
            }
        }
        if (formats.isEmpty()) {
            formats = EnumSet.of(OutputFormat.PNG);
        }
        options.setOutputFormats(formats);

        // dpi
        Object dpiRaw = opts.get("dpi");
        if (dpiRaw instanceof Number n) {
            options.setDpi(n.intValue());
        } else if (dpiRaw instanceof String s) {
            try { options.setDpi(Integer.parseInt(s.trim())); }
            catch (NumberFormatException ignore) {}
        }

        // outputDir
        Object outDirRaw = opts.get("outputDir");
        if (outDirRaw instanceof Path p) {
            options.setOutputDir(p);
        } else if (outDirRaw != null) {
            options.setOutputDir(Path.of(outDirRaw.toString()));
        }

        // filenamePattern
        Object patternRaw = opts.get("filenamePattern");
        if (patternRaw != null) {
            options.setFilenamePattern(patternRaw.toString());
        }

        // resultName
        Object resultNameRaw = opts.get("resultName");
        if (resultNameRaw != null) {
            options.setResultName(resultNameRaw.toString());
        }

        // booleans
        Object overwriteRaw = opts.get("overwriteExisting");
        if (overwriteRaw instanceof Boolean b) options.setOverwriteExisting(b);

        Object skipMissingRaw = opts.get("skipMissingPlots");
        if (skipMissingRaw instanceof Boolean b) options.setSkipMissingPlots(b);

        return options;
    }

    /**
     * Convenience helper: every matplotlib-backed plot kind. Useful for
     * Groovy callers who want "all the saved plots."
     */
    public static Set<PlotKind> matplotlibKinds() {
        Set<PlotKind> out = new LinkedHashSet<>();
        for (PlotKind kind : PlotKind.values()) {
            if (kind.getSource() == PlotKind.Source.MATPLOTLIB) out.add(kind);
        }
        return out;
    }
}
