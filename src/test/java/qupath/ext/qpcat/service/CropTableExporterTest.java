package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.service.CropTableExporter.Format;
import qupath.ext.qpcat.service.CropTableExporter.Header;
import qupath.ext.qpcat.service.CropTableExporter.Row;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file TraitHorizon actually reads.
 *
 * <p>Its contract is narrow and it is someone else's: the first column must be
 * {@code filename}, every feature name must be unique, values must parse under
 * {@code d3.autoType}, and the filename column must name a file that exists.
 * Each of those is one assertion here, because a table we write and never
 * verify is a table that fails in a browser on someone else's machine.
 */
class CropTableExporterTest {

    /**
     * A measurement name with a non-ASCII character in it, which is the ordinary
     * case in QuPath. Spelled as an escape because this source stays ASCII.
     */
    private static final String MICRON_NAME = "Nucleus: Area \u00b5m^2";

    private static Header header(List<String> measurements) {
        return CropTableExporter.buildHeader(measurements, true, true);
    }

    // ---- the header ----

    @Test
    void filenameIsTheFirstColumn() {
        // Required by TraitHorizon: "The first column should be labeled filename".
        Header h = header(List.of("CD3", "CD8"));
        assertThat(h.columns()).startsWith("filename");
        assertThat(h.columns()).containsExactly(
                "filename", "CD3", "CD8", "class_index", "classification", "image");
    }

    @Test
    void theColumnsWeAddCanBeLeftOut() {
        Header h = CropTableExporter.buildHeader(List.of("CD3"), false, false);
        assertThat(h.columns()).containsExactly("filename", "CD3", "class_index");
        assertThat(h.textColumns()).isEmpty();
        assertThat(h.hideAxes()).isEmpty();
    }

    @Test
    void aMeasurementNamedLikeOneOfOurColumnsIsTheOneThatMoves() {
        // "Each feature name should be unique." Our columns are referenced by the
        // README's class_index mapping and by filter JSON, so the measurement is
        // renamed -- and visibly, with a suffix, not by silently dropping one.
        Header h = header(List.of("classification", "image", "class_index", "filename"));
        assertThat(h.columns()).containsExactly(
                "filename",
                "classification_measurement", "image_measurement",
                "class_index_measurement", "filename_measurement",
                "class_index", "classification", "image");
        assertThat(h.columns()).doesNotHaveDuplicates();
    }

    @Test
    void twoMeasurementsWithTheSameNameStillProduceUniqueColumns() {
        Header h = header(List.of("CD3", "CD3", "CD3"));
        assertThat(h.columns()).doesNotHaveDuplicates();
        assertThat(h.columns()).contains("CD3", "CD3_2", "CD3_2_2");
    }

    @Test
    void onlyTheTextColumnsAreOfferedToHideAxes() {
        // d3.autoType leaves a text column as a string, and TraitHorizon's docs
        // say to exclude those. A measurement whose NAME contains "classification"
        // is still a number and must stay plottable.
        Header h = header(List.of("CD3", "classification score"));
        assertThat(h.hideAxes()).containsExactly("classification", "image");
    }

    @Test
    void theGeneratedCommandNamesTheHiddenAxesAndQuotesPathsWithSpaces() {
        Header h = header(List.of("CD3"));
        String cmd = CropTableExporter.traitHorizonCommand(
                Path.of("/data/my project/images"), Path.of("/data/my project/features.tsv"), h);
        assertThat(cmd).isEqualTo("traithorizon \"/data/my project/images\" "
                + "\"/data/my project/features.tsv\" --hide_axes classification image");
    }

    @Test
    void aTableWithNoTextColumnsNeedsNoHideAxesFlag() {
        Header h = CropTableExporter.buildHeader(List.of("CD3"), false, false);
        String cmd = CropTableExporter.traitHorizonCommand(
                Path.of("/d/images"), Path.of("/d/features.tsv"), h);
        assertThat(cmd).isEqualTo("traithorizon /d/images /d/features.tsv");
        assertThat(cmd).doesNotContain("--hide_axes");
    }

    // ---- the numbers ----

    @Test
    void numbersAreWrittenInAFormDautoTypeAccepts() {
        // Integers, plain decimals and scientific notation are the three forms
        // TraitHorizon documents. A grouping separator or a comma decimal point
        // is not a number to d3, and in a CSV a comma would split the row.
        assertThat(CropTableExporter.formatNumber(0)).isEqualTo("0");
        assertThat(CropTableExporter.formatNumber(42)).isEqualTo("42");
        assertThat(CropTableExporter.formatNumber(-7)).isEqualTo("-7");
        assertThat(CropTableExporter.formatNumber(1234567)).isEqualTo("1234567");
        assertThat(CropTableExporter.formatNumber(0.5)).isEqualTo("0.500000");
        assertThat(CropTableExporter.formatNumber(-0.125)).isEqualTo("-0.125000");
        for (double v : new double[] {0, 42, -7, 1234567, 0.5, -0.125, 1.0e-9, 3.25e12}) {
            String s = CropTableExporter.formatNumber(v);
            assertThat(s).doesNotContain(",");
            assertThat(Double.parseDouble(s)).isCloseTo(v, within(Math.abs(v) * 1e-5 + 1e-12));
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double eps) {
        return org.assertj.core.data.Offset.offset(eps);
    }

    // ---- escaping ----

    @Test
    void tsvHasNoQuotingSoSeparatorsInsideAValueAreReplaced() {
        // A tab inside a value would create a column; a newline, a row. TSV has
        // no escape for either, so the character goes rather than the structure.
        assertThat(CropTableExporter.escape("a\tb", Format.TSV)).isEqualTo("a b");
        assertThat(CropTableExporter.escape("a\nb", Format.TSV)).isEqualTo("a b");
        assertThat(CropTableExporter.escape("a\r\nb", Format.TSV)).isEqualTo("a  b");
        assertThat(CropTableExporter.escape("Tumor: CD8+", Format.TSV))
                .isEqualTo("Tumor: CD8+");
        assertThat(CropTableExporter.escape(null, Format.TSV)).isEmpty();
    }

    @Test
    void csvQuotesInsteadOfLosingCharacters() {
        assertThat(CropTableExporter.escape("a,b", Format.CSV)).isEqualTo("\"a,b\"");
        assertThat(CropTableExporter.escape("say \"hi\"", Format.CSV))
                .isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CropTableExporter.escape("a\nb", Format.CSV)).isEqualTo("\"a\nb\"");
        assertThat(CropTableExporter.escape("plain", Format.CSV)).isEqualTo("plain");
    }

    // ---- the written files ----

    private static List<Row> twoRows() {
        return List.of(
                new Row("cell_000000.png", new double[] {1.5, 2}, 0, "Cluster 1", "slide A"),
                new Row("cell_000001.png", new double[] {-3, 0.25}, 1, "Cluster 2", "slide B"));
    }

    @Test
    void theTsvRoundTripsAsTabSeparatedRowsWithTheHeaderFirst(@TempDir Path dir)
            throws IOException {
        Header h = header(List.of("CD3", "CD8"));
        Path file = dir.resolve("features.tsv");
        CropTableExporter.writeTable(file, Format.TSV, h, twoRows());

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).split("\t", -1)).containsExactly(
                "filename", "CD3", "CD8", "class_index", "classification", "image");
        assertThat(lines.get(1).split("\t", -1)).containsExactly(
                "cell_000000.png", "1.50000", "2", "0", "Cluster 1", "slide A");
        // Every row has exactly as many cells as the header has columns -- a
        // short row is how "no missing values" fails in practice.
        for (String line : lines) {
            assertThat(line.split("\t", -1)).hasSize(h.size());
        }
    }

    @Test
    void theTsvCarriesNoByteOrderMarkBecauseItWouldRenameTheFilenameColumn(@TempDir Path dir)
            throws IOException {
        // TraitHorizon requires the first column to be named "filename"; d3's TSV
        // parser does not strip a BOM, so the required first column would arrive
        // named with the mark attached, and the file would be rejected for a
// reason invisible in a text editor.
        Path file = dir.resolve("features.tsv");
        CropTableExporter.writeTable(file, Format.TSV, header(List.of("CD3")), twoRows());
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes[0]).isNotEqualTo((byte) 0xEF);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).startsWith("filename\t");
    }

    @Test
    void theCsvCarriesAByteOrderMarkSoExcelReadsItAsUtf8(@TempDir Path dir) throws IOException {
        // Non-ASCII is ordinary in QuPath measurement names ("um^2"), and Excel
        // assumes the local code page without a BOM.
        Path file = dir.resolve("features.csv");
        CropTableExporter.writeTable(file, Format.CSV,
                header(List.of(MICRON_NAME)), twoRows());
        byte[] bytes = Files.readAllBytes(file);
        assertThat(new byte[] {bytes[0], bytes[1], bytes[2]})
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).contains(MICRON_NAME);
    }

    @Test
    void everyFilenameIsUnique(@TempDir Path dir) throws IOException {
        // "The filename column must not contain duplicate entries."
        Path file = dir.resolve("features.tsv");
        CropTableExporter.writeTable(file, Format.TSV, header(List.of("CD3")), twoRows());
        List<String> names = Files.readAllLines(file).stream().skip(1)
                .map(l -> l.split("\t", -1)[0]).toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void theTableIsWrittenEvenWhenItsFolderDoesNotExistYet(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("nested").resolve("deeper").resolve("features.tsv");
        CropTableExporter.writeTable(file, Format.TSV, header(List.of("CD3")), twoRows());
        assertThat(file).exists();
    }

    @Test
    void anEmptyTableStillHasItsHeader(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("features.tsv");
        CropTableExporter.writeTable(file, Format.TSV, header(List.of("CD3")), List.of());
        assertThat(Files.readAllLines(file)).hasSize(1);
    }

    // ---- the message a failed export leaves behind ----

    @Test
    void aMeasurementOnNoCellIsNamedRatherThanAveragedIntoAList() {
        // Found by running the exporter against the real synthetic dataset: a
        // measurement name that does not exist on these detections drops every
        // cell, and the old message led with five measurements at 100% and
        // truncated before reaching the culprit. It explained nothing.
        List<String> names = List.of("CD3", "CD8", "CD20", "CD68", "PanCK", "Nucleus: Area um^2");
        int[] present = {4528, 4528, 4528, 4528, 4528, 0};
        String msg = CropTableExporter.coverageSummary(names, present, 4528);
        assertThat(msg).contains("Nucleus: Area um^2");
        assertThat(msg).contains("on none of them");
        assertThat(msg).contains("Check the name");
    }

    @Test
    void severalAbsentMeasurementsAreAllNamed() {
        String msg = CropTableExporter.coverageSummary(
                List.of("a", "b", "c"), new int[] {100, 0, 0}, 100);
        assertThat(msg).contains("these measurements are").contains("b, c");
    }

    @Test
    void sparseCoverageIsReportedWorstFirst() {
        // Not in the order the user chose: this message only appears when
        // something went wrong, so it leads with what caused it.
        List<String> names = List.of("full", "half", "rare", "most");
        int[] present = {1000, 500, 30, 900};
        String msg = CropTableExporter.coverageSummary(names, present, 1000);
        assertThat(msg).startsWith("Scanned 1000 cell(s); lowest coverage: rare 3%");
        assertThat(msg.indexOf("rare")).isLessThan(msg.indexOf("half"));
        assertThat(msg.indexOf("half")).isLessThan(msg.indexOf("most"));
        assertThat(msg.indexOf("most")).isLessThan(msg.indexOf("full"));
    }

    @Test
    void theSummaryTruncatesButSaysSo() {
        List<String> names = new java.util.ArrayList<>();
        int[] present = new int[9];
        for (int i = 0; i < 9; i++) {
            names.add("m" + i);
            present[i] = 100 + i;
        }
        String msg = CropTableExporter.coverageSummary(names, present, 1000);
        assertThat(msg).contains("m0 10%").contains("m4").endsWith(", ...");
        assertThat(msg).doesNotContain("m8");
    }

    @Test
    void aSummaryWithNothingToSayIsEmpty() {
        assertThat(CropTableExporter.coverageSummary(List.of("a"), new int[] {1}, 0)).isEmpty();
        assertThat(CropTableExporter.coverageSummary(null, new int[] {1}, 10)).isEmpty();
        assertThat(CropTableExporter.coverageSummary(List.of("a"), null, 10)).isEmpty();
        // Mismatched lengths would otherwise index out of bounds inside an
        // error path, turning a bad export into a crash.
        assertThat(CropTableExporter.coverageSummary(
                List.of("a", "b"), new int[] {1}, 10)).isEmpty();
    }
}
