package qupath.ext.qpcat.scripting;

import org.junit.jupiter.api.Test;
import qupath.ext.qpcat.model.ExportOptions;
import qupath.ext.qpcat.model.OutputFormat;
import qupath.ext.qpcat.model.PlotKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 invariants for {@link FigureExportScripts}. The option-key set
 * is part of the v1 public scripting API consumed by Feature C's YAML
 * batch; regressions here mean the documented Groovy contract has
 * shifted.
 */
class FigureExportScriptsTest {

    @Test
    void buildOptionsAppliesDefaults() {
        ExportOptions opts = FigureExportScripts.buildOptions(null);
        assertThat(opts.getScope()).isEqualTo(ExportOptions.Scope.CURRENT);
        assertThat(opts.getDpi()).isEqualTo(ExportOptions.DEFAULT_DPI);
        assertThat(opts.getOutputFormats()).containsExactly(OutputFormat.PNG);
        // Plot kinds default to every matplotlib-backed plot
        assertThat(opts.getPlotKinds()).isNotEmpty();
        for (PlotKind kind : opts.getPlotKinds()) {
            assertThat(kind.getSource()).isEqualTo(PlotKind.Source.MATPLOTLIB);
        }
    }

    @Test
    void buildOptionsParsesAllImagesWildcard() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("imageNames", List.of("*"));
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getScope()).isEqualTo(ExportOptions.Scope.ALL);
    }

    @Test
    void buildOptionsParsesImageSubset() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("imageNames", List.of("img1", "img2"));
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getScope()).isEqualTo(ExportOptions.Scope.SUBSET);
        assertThat(result.getImageSubset()).containsExactly("img1", "img2");
    }

    @Test
    void buildOptionsParsesPlotKindSlugs() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("plotKinds", List.of("dotplot", "matrixplot", "ripley_k"));
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getPlotKinds()).containsExactly(
                PlotKind.DOTPLOT, PlotKind.MATRIXPLOT, PlotKind.RIPLEY_K);
    }

    @Test
    void buildOptionsIgnoresUnknownPlotKindSlugs() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("plotKinds", List.of("not_a_kind", "dotplot"));
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getPlotKinds()).containsExactly(PlotKind.DOTPLOT);
    }

    @Test
    void buildOptionsParsesFormats() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("formats", List.of("png", "tiff"));
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getOutputFormats()).containsExactlyInAnyOrder(
                OutputFormat.PNG, OutputFormat.TIFF);
    }

    @Test
    void buildOptionsParsesDpi() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("dpi", 600);
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getDpi()).isEqualTo(600);
    }

    @Test
    void buildOptionsParsesDpiFromString() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("dpi", "150");
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getDpi()).isEqualTo(150);
    }

    @Test
    void buildOptionsParsesFilenamePattern() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("filenamePattern", "{result_name}_{image}_{plot}.{ext}");
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getFilenamePattern())
                .isEqualTo("{result_name}_{image}_{plot}.{ext}");
    }

    @Test
    void buildOptionsParsesOutputDirString() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("outputDir", "/tmp/figures");
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.getOutputDir()).isNotNull();
        assertThat(result.getOutputDir().toString()).isEqualTo("/tmp/figures");
    }

    @Test
    void buildOptionsParsesBooleans() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("overwriteExisting", true);
        opts.put("skipMissingPlots", false);
        ExportOptions result = FigureExportScripts.buildOptions(opts);
        assertThat(result.isOverwriteExisting()).isTrue();
        assertThat(result.isSkipMissingPlots()).isFalse();
    }

    @Test
    void matplotlibKindsExcludesJavaFx() {
        var kinds = FigureExportScripts.matplotlibKinds();
        for (PlotKind kind : kinds) {
            assertThat(kind.getSource()).isEqualTo(PlotKind.Source.MATPLOTLIB);
        }
        assertThat(kinds).doesNotContain(PlotKind.HEATMAP, PlotKind.EMBEDDING_INTERACTIVE);
    }
}
