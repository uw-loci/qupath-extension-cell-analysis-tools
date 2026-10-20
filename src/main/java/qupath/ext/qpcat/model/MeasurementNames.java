package qupath.ext.qpcat.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits a QuPath measurement name around its marker, so views can show the
 * marker in bold. Mirrors {@code split_measurement_name} in the Python
 * {@code model_utils.py}; both are tested against
 * {@code measurement_name_cases.json}.
 */
public final class MeasurementNames {

    /**
     * A name in three parts that always join back to it.
     *
     * @param before text before the marker, e.g. {@code "Nucleus: "}
     * @param marker the marker, e.g. {@code "CD3"}
     * @param after  text after the marker, e.g. {@code " std dev"}
     */
    public record Parts(String before, String marker, String after) {
        /** @return the parts joined */
        public String text() {
            return before + marker + after;
        }
    }

    private static final List<String> STATISTICS = List.of(
            "std dev", "std.dev.", "mean", "median", "min", "max", "sum", "range", "variance");
    private static final Set<String> COMPARTMENTS = Set.of(
            "nucleus", "cytoplasm", "membrane", "cell");

    private MeasurementNames() {}

    /**
     * Split a measurement name around its marker: {@code "Nucleus: CD3 std dev"},
     * {@code "Cell: PCNA: Mean"} and the channel-first {@code "CD3: Nucleus: Mean"}
     * all give marker {@code "CD3"}/{@code "PCNA"}. A missing part is empty.
     *
     * @param name a measurement name; null is treated as empty
     * @return the parts
     */
    public static Parts split(String name) {
        String n = name == null ? "" : name;
        String[] parts = n.split(": ", -1);
        int last = parts.length - 1;
        if (parts.length >= 3 && STATISTICS.contains(parts[last].toLowerCase(Locale.ROOT))) {
            int i = COMPARTMENTS.contains(parts[last - 1].toLowerCase(Locale.ROOT)) ? last - 2 : last - 1;
            String before = i == 0 ? "" : String.join(": ", List.of(parts).subList(0, i)) + ": ";
            return new Parts(before, parts[i], n.substring(before.length() + parts[i].length()));
        }
        String before = parts.length > 1 ? n.substring(0, n.length() - parts[last].length()) : "";
        String rest = parts[last];
        String lower = rest.toLowerCase(Locale.ROOT);
        for (String stat : STATISTICS) {
            if (lower.endsWith(" " + stat) && rest.length() > stat.length() + 1) {
                int cut = rest.length() - stat.length() - 1;
                return new Parts(before, rest.substring(0, cut), rest.substring(cut));
            }
        }
        return new Parts(before, rest, "");
    }

    /**
     * Compact display name: drop a leading generic "Cell"/"Detection" when two or
     * more parts follow it, and join the remaining parts with spaces.
     * Every other compartment and the statistic are kept, so "PCNA Mean" and
     * "PCNA Median" stay distinguishable.
     *
     * @param fullName a measurement name
     * @return the shortened name, or "" for null
     */
    public static String shorten(String fullName) {
        if (fullName == null) return "";
        String[] parts = fullName.split(":\\s*");
        if (parts.length <= 1) return fullName.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = dropsGenericCompartment(parts) ? 1 : 0; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(parts[i].trim());
        }
        return sb.toString();
    }

    /**
     * {@link #split} for the {@link #shorten shortened} name: parts that join to
     * exactly {@code shorten(fullName)}. When the two cannot be lined up the whole
     * name is returned as {@code before} with no marker, so nothing is bolded.
     *
     * @param fullName a measurement name
     * @return the parts of the shortened name
     */
    public static Parts splitShortened(String fullName) {
        String shortName = shorten(fullName);
        Parts p = split(fullName);
        String before = p.before().replaceAll(":\\s*", " ");
        String[] segments = fullName == null ? new String[0] : fullName.split(":\\s*");
        if (dropsGenericCompartment(segments) && before.startsWith(segments[0] + " ")) {
            before = before.substring(segments[0].length() + 1);
        }
        Parts out = new Parts(before, p.marker(), p.after().replaceAll(":\\s*", " "));
        return !out.marker().isEmpty() && out.text().equals(shortName)
                ? out : new Parts(shortName, "", "");
    }

    private static boolean dropsGenericCompartment(String[] parts) {
        if (parts.length < 3) return false;
        String first = parts[0].trim();
        return first.equalsIgnoreCase("Cell") || first.equalsIgnoreCase("Detection");
    }
}
