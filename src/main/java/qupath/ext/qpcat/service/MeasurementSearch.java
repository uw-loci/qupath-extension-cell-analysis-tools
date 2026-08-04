package qupath.ext.qpcat.service;

import java.util.Locale;

/**
 * The predicate behind the "Find marker" boxes on the Marker Rankings and
 * Marker Fingerprints tabs.
 *
 * <p>Plain case-insensitive substring match against the <b>whole measurement
 * name</b> -- Ctrl-F semantics. A dialog has no Ctrl-F, so this stands in for
 * it, and it should behave the way a user expects Ctrl-F to behave: what you
 * type is matched against what you can see.</p>
 *
 * <p><b>History, so this is not "simplified" back.</b> The first version matched
 * against a reduced form of the name, with compartment and statistic words
 * (<i>cell</i>, <i>nucleus</i>, <i>mean</i>, <i>std.dev</i>...) stripped out.
 * That was designed for a UI where the user would PICK a marker from a generated
 * list, and it shipped as free-text search instead, where it actively broke
 * things: the haystack was transformed but the needle was not, so typing a field
 * name copied off the screen -- {@code "Membrane: 18_Ki-67"} -- matched nothing.
 * The underscore compounded it, because it was a separator in the reduction, so
 * even {@code "18_Ki-67"} missed. See issue #13.</p>
 *
 * <p>Consequence, accepted deliberately: typing {@code mean} now matches every
 * mean measurement. For free-text search that is correct -- the user asked for
 * it, and the match count says what happened.</p>
 */
public final class MeasurementSearch {

    private MeasurementSearch() {}

    /**
     * Does this measurement name contain the search text?
     *
     * @param measurementName the full QuPath measurement name, e.g.
     *                        {@code "Membrane: 18_Ki-67: Mean"}
     * @param query           user text; blank or null matches everything, because
     *                        an empty search box is not a filter
     */
    public static boolean matches(String measurementName, String query) {
        if (query == null || query.isBlank()) return true;
        if (measurementName == null) return false;
        return measurementName.toLowerCase(Locale.ROOT)
                .contains(query.trim().toLowerCase(Locale.ROOT));
    }
}
