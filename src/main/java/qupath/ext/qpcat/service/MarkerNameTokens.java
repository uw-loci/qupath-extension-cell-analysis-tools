package qupath.ext.qpcat.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reduces a QuPath measurement name to the marker it is about, by discarding the
 * compartment and statistic boilerplate around it.
 *
 * <p>{@code "Cell: CD8 mean"}, {@code "Nucleus: CD8: Max"} and {@code "Mean_CD8"}
 * all reduce to {@code "CD8"}. That reduction is what makes a marker searchable:
 * a user looking for CD8 should find it in every compartment and every statistic,
 * and should NOT be able to "search" for {@code Mean} and match half the panel.</p>
 *
 * <p><b>Token removal, never substring removal.</b> There is no single
 * measurement-naming convention -- {@link ChannelMatcher} documents four seen in
 * the wild, with the compartment before, after, or absent -- so this cannot parse
 * positionally. It splits on the separators and drops whole tokens that match the
 * exclusion vocabulary. Substring stripping would turn a marker named
 * {@code MinK} into {@code K} and {@code Meanwell} into {@code well}.</p>
 *
 * <p>FX-free and static, so it can be unit-tested directly; the traps here are
 * all in the tokenising, which is exactly the part worth pinning.</p>
 */
public final class MarkerNameTokens {

    private MarkerNameTokens() {}

    /**
     * Words that describe WHERE or HOW a measurement was taken, rather than WHAT
     * it measured. Compared case-insensitively against whole tokens.
     *
     * <p>Extend this when a detection engine introduces new boilerplate --
     * anything a user would never search for on its own belongs here.</p>
     */
    public static final Set<String> EXCLUDED_TOKENS = Set.of(
            // Compartments
            "cell", "cytoplasm", "membrane", "nucleus",
            // Statistics. "std.dev" and "stddev" are the same word to a user, and
            // the trailing period is stripped before comparison, so "Std.Dev."
            // arrives here as "std.dev".
            "mean", "max", "min", "median", "std.dev", "stddev", "variance");

    /** Separators seen across the different measurement schemas. */
    private static final String SEPARATORS = "[:;,\\s_/]+";

    /**
     * The marker part of a measurement name.
     *
     * @param measurementName e.g. {@code "Cell: CD8 mean"}
     * @return e.g. {@code "CD8"}; the original (trimmed) name when every token is
     *         boilerplate, since an empty label is worse than a redundant one; an
     *         empty string for null/blank input
     */
    public static String strip(String measurementName) {
        if (measurementName == null) return "";
        String trimmed = measurementName.trim();
        if (trimmed.isEmpty()) return "";

        List<String> kept = new ArrayList<>();
        for (String token : trimmed.split(SEPARATORS)) {
            if (token.isEmpty()) continue;
            if (!isBoilerplate(token)) kept.add(token);
        }
        // Everything was boilerplate ("Nucleus: Mean" measures nothing nameable).
        // Returning the original keeps the row findable rather than blank.
        return kept.isEmpty() ? trimmed : String.join(" ", kept);
    }

    /** True when this single token is compartment/statistic boilerplate. */
    public static boolean isBoilerplate(String token) {
        return EXCLUDED_TOKENS.contains(normalise(token));
    }

    /**
     * Lowercase, and drop leading/trailing punctuation so {@code "Std.Dev."} and
     * {@code "(Mean)"} compare equal to their bare forms. Internal periods are
     * preserved -- they are what makes {@code std.dev} one word.
     */
    private static String normalise(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        int from = 0;
        int to = t.length();
        while (from < to && !Character.isLetterOrDigit(t.charAt(from))) from++;
        while (to > from && !Character.isLetterOrDigit(t.charAt(to - 1))) to--;
        return t.substring(from, to);
    }

    /**
     * Does this measurement match a user's search text?
     *
     * <p>Matched against the STRIPPED name, which is what makes the exclusion
     * list meaningful: typing {@code mean} matches nothing, because no marker is
     * called "mean". Case-insensitive substring, so {@code cd8} finds
     * {@code CD8a}.</p>
     *
     * @param query blank/null matches everything (an empty search box is not a filter)
     */
    public static boolean matches(String measurementName, String query) {
        if (query == null || query.isBlank()) return true;
        if (measurementName == null) return false;
        return strip(measurementName).toLowerCase(Locale.ROOT)
                .contains(query.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The distinct marker vocabulary across a set of measurement names, in
     * case-insensitive alphabetical order. Feeds the search box's "what can I
     * type here" hint.
     */
    public static List<String> distinctMarkers(Collection<String> measurementNames) {
        if (measurementNames == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : measurementNames) {
            String marker = strip(name);
            if (!marker.isEmpty()) seen.add(marker);
        }
        List<String> out = new ArrayList<>(seen);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The exclusion vocabulary, sorted -- for showing the user what is ignored. */
    public static List<String> excludedTokensSorted() {
        List<String> out = new ArrayList<>(EXCLUDED_TOKENS);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }

    /** Convenience for callers holding an array. */
    public static List<String> distinctMarkers(String... measurementNames) {
        return distinctMarkers(Arrays.asList(measurementNames));
    }
}
