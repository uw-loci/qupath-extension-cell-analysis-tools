package qupath.ext.qpcat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpcat.model.RipleyResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** One set of CSV/TSV cell rules everywhere QP-CAT writes a table (H34, H49, H55). */
class CsvTextTest {

    @Test
    void formulaStartsGetAnApostropheAndNumbersKeepTheirSign() {
        assertThat(CsvText.guardFormula("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvText.guardFormula("@cmd")).isEqualTo("'@cmd");
        assertThat(CsvText.guardFormula("+CD8")).isEqualTo("'+CD8");
        assertThat(CsvText.guardFormula("-CD8")).isEqualTo("'-CD8");
        assertThat(CsvText.guardFormula("\tx")).isEqualTo("'\tx");
        assertThat(CsvText.guardFormula("\rx")).isEqualTo("'\rx");
        assertThat(CsvText.guardFormula("-1.5")).isEqualTo("-1.5");
        assertThat(CsvText.guardFormula("+2e-3")).isEqualTo("+2e-3");
        assertThat(CsvText.guardFormula("-Infinity")).isEqualTo("-Infinity");
        assertThat(CsvText.guardFormula("CD8+")).isEqualTo("CD8+");
        assertThat(CsvText.guardFormula("")).isEmpty();
    }

    @Test
    void csvCellsQuoteCommaQuoteCrAndLf() {
        assertThat(CsvText.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvText.cell("a\rb")).isEqualTo("\"a\rb\"");
        assertThat(CsvText.cell("a\nb")).isEqualTo("\"a\nb\"");
        assertThat(CsvText.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvText.cell("=HYPERLINK(\"u\",\"c\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"u\"\",\"\"c\"\")\"");
        assertThat(CsvText.cell(null)).isEmpty();
    }

    @Test
    void tsvCellsCannotStartAColumnOrARow() {
        assertThat(CsvText.tsvCell("a\tb\nc\rd")).isEqualTo("a b c d");
        assertThat(CsvText.tsvCell("=cmd")).isEqualTo("'=cmd");
    }

    @Test
    void aCsvFileStartsWithAByteOrderMark(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.csv");
        CsvText.writeCsvFile(f, "a,b\n1,2\n");
        byte[] bytes = Files.readAllBytes(f);
        assertThat(new byte[] {bytes[0], bytes[1], bytes[2]})
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(bytes, StandardCharsets.UTF_8).substring(1)).isEqualTo("a,b\n1,2\n");
    }

    /** Minimal RFC 4180 reader: rows of cells, honoring quotes across line breaks. */
    static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        return rows;
    }

    @Test
    void aPerAreaTableKeepsANameWithALineBreakInOneRow() {
        // An imported GeoJSON can name a cluster or an area with a line break; the
        // per-area writer used to split its own output on "\n" and cut the row in two.
        RipleyResult r = new RipleyResult();
        r.setRadii(new double[] {10});
        r.setClusterNames(List.of("T cells\nCD8"));
        r.setLValues(new double[][] {{1.5}});
        r.setKUnavailable(true);
        Map<String, RipleyResult> byArea = new LinkedHashMap<>();
        byArea.put("core\r\nA-1", r);

        List<List<String>> rows = parse(SpatialStatsCsv.ripleyCsvPerArea(byArea, null));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("area", "cluster", "radius", "k", "l");
        assertThat(rows.get(1)).containsExactly("core\r\nA-1", "T cells\nCD8", "10.0", "", "1.5");
    }

    @Test
    void theCompositionTableQuotesABareCarriageReturn() {
        CompositionFigure f = CompositionFigure.tally(new int[] {0, 1}, 2, -1,
                new String[] {"slide\r1", "slide\r1"}, "Image");
        List<List<String>> rows = parse(f.toCsv());
        assertThat(rows.get(1).get(0)).isEqualTo("slide\r1");
        assertThat(f.toCsv()).contains("\"slide\r1\"");
    }
}
