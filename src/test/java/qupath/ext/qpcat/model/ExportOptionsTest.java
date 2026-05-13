package qupath.ext.qpcat.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 invariants for {@link ExportOptions} and {@link ExportResult}.
 * Both POJOs are part of the locked cross-feature contract and must
 * survive a Gson round-trip (Feature C's YAML batch serialises them).
 */
class ExportOptionsTest {

    @Test
    void defaultsAreSensible() {
        ExportOptions opts = new ExportOptions();
        assertThat(opts.getScope()).isEqualTo(ExportOptions.Scope.CURRENT);
        assertThat(opts.getDpi()).isEqualTo(ExportOptions.DEFAULT_DPI);
        assertThat(opts.getFilenamePattern()).isEqualTo(ExportOptions.DEFAULT_PATTERN);
        assertThat(opts.getOutputFormats()).containsExactly(OutputFormat.PNG);
        assertThat(opts.getPlotKinds()).isEmpty();
        assertThat(opts.isOverwriteExisting()).isFalse();
        assertThat(opts.isSkipMissingPlots()).isTrue();
    }

    @Test
    void copyIsIndependent() {
        ExportOptions a = new ExportOptions();
        a.setScope(ExportOptions.Scope.SUBSET);
        a.setImageSubset(List.of("img1", "img2"));
        Set<PlotKind> kinds = new LinkedHashSet<>();
        kinds.add(PlotKind.DOTPLOT);
        a.setPlotKinds(kinds);
        a.setOutputFormats(EnumSet.of(OutputFormat.PNG, OutputFormat.TIFF));
        a.setOutputDir(Path.of("/tmp/out"));

        ExportOptions b = a.copy();
        // Mutating b's collections must not affect a
        b.getImageSubset().add("img3");
        b.getPlotKinds().add(PlotKind.HEATMAP);
        b.getOutputFormats().remove(OutputFormat.TIFF);

        assertThat(a.getImageSubset()).hasSize(2);
        assertThat(a.getPlotKinds()).hasSize(1);
        assertThat(a.getOutputFormats()).contains(OutputFormat.TIFF);
    }

    @Test
    void emptyFormatsFallsBackToPng() {
        ExportOptions opts = new ExportOptions();
        opts.setOutputFormats(EnumSet.noneOf(OutputFormat.class));
        assertThat(opts.getOutputFormats()).containsExactly(OutputFormat.PNG);
    }

    @Test
    void emptyPatternFallsBackToDefault() {
        ExportOptions opts = new ExportOptions();
        opts.setFilenamePattern("");
        assertThat(opts.getFilenamePattern()).isEqualTo(ExportOptions.DEFAULT_PATTERN);
        opts.setFilenamePattern(null);
        assertThat(opts.getFilenamePattern()).isEqualTo(ExportOptions.DEFAULT_PATTERN);
    }

    @Test
    void gsonRoundTripsFieldsOfInterest() {
        ExportOptions opts = new ExportOptions();
        opts.setScope(ExportOptions.Scope.SUBSET);
        opts.setImageSubset(new ArrayList<>(List.of("img1", "img2", "img3")));
        opts.setOutputFormats(EnumSet.of(OutputFormat.PNG, OutputFormat.TIFF));
        opts.setDpi(600);
        opts.setFilenamePattern("{result_name}_{image}_{plot}.{ext}");
        opts.setResultName("Leiden_r1");
        opts.setOverwriteExisting(true);

        Gson gson = new Gson();
        String json = gson.toJson(opts);
        ExportOptions back = gson.fromJson(json, ExportOptions.class);

        assertThat(back.getScope()).isEqualTo(ExportOptions.Scope.SUBSET);
        assertThat(back.getImageSubset()).containsExactly("img1", "img2", "img3");
        assertThat(back.getOutputFormats()).containsExactlyInAnyOrder(OutputFormat.PNG, OutputFormat.TIFF);
        assertThat(back.getDpi()).isEqualTo(600);
        assertThat(back.getFilenamePattern()).isEqualTo("{result_name}_{image}_{plot}.{ext}");
        assertThat(back.getResultName()).isEqualTo("Leiden_r1");
        assertThat(back.isOverwriteExisting()).isTrue();
    }

    @Test
    void exportResultGsonRoundTrip() {
        ExportResult r = new ExportResult();
        r.setFilesWritten(7);
        r.addBytes(1024L * 1024L);
        r.addFailure("img_x: file exists");
        r.setCancelled(false);

        Gson gson = new Gson();
        String json = gson.toJson(r);
        ExportResult back = gson.fromJson(json, ExportResult.class);

        assertThat(back.getFilesWritten()).isEqualTo(7);
        assertThat(back.getTotalBytes()).isEqualTo(1024L * 1024L);
        assertThat(back.getFailures()).containsExactly("img_x: file exists");
        assertThat(back.isCancelled()).isFalse();
        assertThat(back.summary()).contains("7 files written");
    }
}
