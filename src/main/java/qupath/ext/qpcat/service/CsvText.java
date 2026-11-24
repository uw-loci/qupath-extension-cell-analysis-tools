package qupath.ext.qpcat.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The one set of rules for text cells in the CSV and TSV files QP-CAT writes
 * for spreadsheets.
 *
 * <p>Conventions: comma separator (CSV) or tab (TSV), a period as the decimal
 * point in every locale, LF line ends, UTF-8. A CSV written to a file starts
 * with a UTF-8 byte-order mark, because Excel reads a CSV without one in the
 * local 8-bit code page and mangles non-ASCII names; a TSV has none
 * (TraitHorizon reads the TSV and rejects a BOM). CSV text cells are quoted
 * (RFC 4180) when they hold a comma, quote, CR or LF; TSV cells have tab, CR and
 * LF replaced with a space. A text cell starting with {@code = + - @}, tab or CR
 * gets a leading apostrophe so a spreadsheet shows it as text instead of running
 * it as a formula; a cell that is a plain number keeps its sign.
 */
public final class CsvText {

    /** UTF-8 byte-order mark, written as an escape because this source stays ASCII. */
    public static final char BOM = '\uFEFF';

    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?|[+-]?Infinity");

    private CsvText() {}

    /**
     * One CSV text cell.
     *
     * @param raw the text; null is an empty cell
     * @return the cell, formula-guarded and quoted when needed
     */
    public static String cell(String raw) {
        String s = guardFormula(raw == null ? "" : raw);
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0
                && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /**
     * One TSV text cell. TSV has no quoting, so a tab, CR or LF would start a new
     * column or row: each becomes a space.
     *
     * @param raw the text; null is an empty cell
     * @return the cell, formula-guarded
     */
    public static String tsvCell(String raw) {
        return guardFormula(raw == null ? "" : raw)
                .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Prefix an apostrophe when a spreadsheet would read the text as a formula.
     *
     * @param s the text, not null
     * @return {@code s}, or {@code "'" + s}
     */
    public static String guardFormula(String s) {
        if (s.isEmpty()) return s;
        char c = s.charAt(0);
        if (c == '=' || c == '@' || c == '\t' || c == '\r') return "'" + s;
        if ((c == '+' || c == '-') && !NUMBER.matcher(s).matches()) return "'" + s;
        return s;
    }

    /**
     * Write CSV text to a file as UTF-8 with a byte-order mark, replacing the
     * file atomically.
     *
     * @param file the destination
     * @param csv  the table, without a BOM
     * @throws IOException on a write failure
     */
    public static void writeCsvFile(Path file, String csv) throws IOException {
        JsonFiles.writeAtomic(file, BOM + (csv == null ? "" : csv));
    }
}
