package qupath.ext.qpcat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.color.ColorMaps;
import qupath.lib.color.ColorMaps.ColorMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The colour maps QP-CAT offers, split by family.
 *
 * <p>Built on QuPath's own {@link ColorMaps} registry rather than a private list,
 * so a user's own {@code .tsv} maps show up here too and the names match what they
 * see in QuPath's measurement maps.
 *
 * <h2>Only the right family is offered</h2>
 *
 * <p>A diverging map promises that its midpoint means something. Un-normalized
 * intensities have no meaningful zero, so painting them blue-white-red invites
 * reading the middle of an arbitrary range as "average" -- which is a false
 * statement made in colour. {@link #sequential()} and {@link #diverging()} are
 * therefore separate, and a caller offers exactly one of them based on whether its
 * data has a centre. This is the same split scanpy makes by defaulting matrixplot
 * to viridis and exposing {@code vcenter} separately.
 *
 * <h2>QuPath has no built-in diverging map</h2>
 *
 * <p>Its bundled set is Viridis, Inferno, Magma, Plasma and Svidro2 -- all
 * sequential. QP-CAT registers its own diverging maps on first use, including the
 * blue-white-red it has always drawn, which stays the default so an existing
 * figure does not change colour on upgrade.
 */
public final class QpcatColorMaps {

    private static final Logger logger = LoggerFactory.getLogger(QpcatColorMaps.class);

    /** The sequential map QP-CAT has always used; stays the default. */
    public static final String DEFAULT_SEQUENTIAL = "Viridis";

    /** The diverging map QP-CAT has always drawn; stays the default. */
    public static final String DEFAULT_DIVERGING = "Blue-White-Red";

    /**
     * QuPath's bundled maps, every one of them sequential.
     *
     * <p>Listed rather than detected because the registry does not record a
     * family, and guessing one from the colours would be a heuristic that silently
     * misfiles a user's map.
     */
    private static final List<String> BUILT_IN_SEQUENTIAL =
            List.of("Viridis", "Inferno", "Magma", "Plasma", "Svidro2");

    private static boolean installed;

    private QpcatColorMaps() {}

    /**
     * Registers QP-CAT's diverging maps and loads the user's own, once.
     *
     * <p>QuPath only reads the user colour-map directory when its Measurement Map
     * pane is first constructed, so a QP-CAT dialog opened before that pane would
     * otherwise see only the bundled maps. Calling this makes the behaviour the
     * same either way.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            ColorMaps.installColorMaps(
                    divergingMap("Blue-White-Red",
                            new double[]{0.0, 1.0, 1.0},
                            new double[]{0.0, 1.0, 0.0},
                            new double[]{1.0, 1.0, 0.0}),
                    // ColorBrewer RdBu, reversed so red is high -- the convention in
                    // expression heatmaps, and colour-blind safe.
                    divergingMap("Red-Blue (RdBu)",
                            new double[]{0.020, 0.820, 0.969, 0.404},
                            new double[]{0.188, 0.898, 0.718, 0.0},
                            new double[]{0.380, 0.941, 0.588, 0.122}),
                    // ColorBrewer PuOr, reversed. The safest diverging option for
                    // red-green colour blindness.
                    divergingMap("Orange-Purple (PuOr)",
                            new double[]{0.177, 0.600, 0.969, 0.600},
                            new double[]{0.0, 0.600, 0.969, 0.333},
                            new double[]{0.294, 0.788, 0.969, 0.020}));
        } catch (Exception e) {
            logger.warn("Could not register QP-CAT diverging colour maps: {}", e.getMessage());
        }
        installUserMaps();
    }

    /** One diverging map from its stops, dark-low through pale-middle to dark-high. */
    private static ColorMap divergingMap(String name, double[] r, double[] g, double[] b) {
        return ColorMaps.createColorMap(name, r, g, b);
    }

    /**
     * Loads the user's own {@code .tsv} maps from QuPath's colour-map directory.
     *
     * <p>Best-effort and silent on a missing directory, which is the normal case:
     * most users have never added one.
     */
    private static void installUserMaps() {
        try {
            Path dir = qupath.lib.gui.UserDirectoryManager.getInstance()
                    .getColormapsDirectoryPath();
            if (dir != null && Files.isDirectory(dir)) {
                ColorMaps.installColorMaps(dir);
                logger.debug("Installed user colour maps from {}", dir);
            }
        } catch (Exception e) {
            // A missing or unreadable user directory must never stop a dialog
            // opening; the bundled maps are always there.
            logger.debug("No user colour maps installed: {}", e.getMessage());
        }
    }

    /**
     * Maps safe for data with no meaningful zero.
     *
     * <p>Anything in the registry that is not one of QP-CAT's diverging maps,
     * which puts a user's own map here. That is the deliberate default: a map
     * someone added by hand is far more likely to be sequential, and offering it
     * for sequential data is the harmless direction to be wrong in.
     *
     * @return ordered name -> map, never empty
     */
    public static Map<String, ColorMap> sequential() {
        install();
        Map<String, ColorMap> all = ColorMaps.getColorMaps();
        Set<String> divergingNames = divergingNames();
        Map<String, ColorMap> out = new LinkedHashMap<>();
        // Bundled ones first, in a familiar order, then anything else.
        for (String name : BUILT_IN_SEQUENTIAL) {
            ColorMap cm = all.get(name);
            if (cm != null) {
                out.put(name, cm);
            }
        }
        for (Map.Entry<String, ColorMap> e : all.entrySet()) {
            if (!divergingNames.contains(e.getKey())) {
                out.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        if (out.isEmpty()) {
            // Registry unavailable: the panel's own painter is the fallback, and
            // an empty combo would be worse than no combo.
            logger.warn("No sequential colour maps found in the QuPath registry");
        }
        return out;
    }

    /**
     * Maps safe for data with a meaningful zero (z-scores, log ratios).
     *
     * @return ordered name -> map
     */
    public static Map<String, ColorMap> diverging() {
        install();
        Map<String, ColorMap> all = ColorMaps.getColorMaps();
        Map<String, ColorMap> out = new LinkedHashMap<>();
        for (String name : divergingNames()) {
            ColorMap cm = all.get(name);
            if (cm != null) {
                out.put(name, cm);
            }
        }
        return out;
    }

    /** The maps QP-CAT registers as diverging, in offer order. */
    private static Set<String> divergingNames() {
        return new LinkedHashSet<>(List.of(
                DEFAULT_DIVERGING, "Red-Blue (RdBu)", "Orange-Purple (PuOr)"));
    }

    /**
     * Maps for whichever family the data belongs to.
     *
     * @param centred true when zero means something in the values
     * @return the offerable maps
     */
    public static Map<String, ColorMap> forData(boolean centred) {
        return centred ? diverging() : sequential();
    }

    /**
     * The default map name for a family.
     *
     * @param centred true when zero means something in the values
     * @return the name QP-CAT drew before the choice existed
     */
    public static String defaultFor(boolean centred) {
        return centred ? DEFAULT_DIVERGING : DEFAULT_SEQUENTIAL;
    }

    /**
     * Resolves a remembered name, falling back rather than failing.
     *
     * @param name    a map name, possibly stale or from the other family
     * @param centred true when zero means something in the values
     * @return a map from the right family; never null while the registry has one
     */
    public static ColorMap resolve(String name, boolean centred) {
        Map<String, ColorMap> family = forData(centred);
        if (name != null && family.containsKey(name)) {
            return family.get(name);
        }
        ColorMap fallback = family.get(defaultFor(centred));
        if (fallback != null) {
            return fallback;
        }
        // Last resort: anything in the family, then anything at all.
        List<ColorMap> any = new ArrayList<>(family.values());
        if (!any.isEmpty()) {
            return any.get(0);
        }
        return ColorMaps.getDefaultColorMap();
    }

    /**
     * The matplotlib name for a map, for the figures Python renders.
     *
     * <p>Only the names that exist in both namespaces cross over. QuPath's bundled
     * sequential maps happen to be matplotlib's too, and QP-CAT's diverging maps are
     * ColorBrewer scales matplotlib also ships. A user's own {@code .tsv} has no
     * matplotlib equivalent, so it returns null and the caller keeps its default
     * rather than passing a name that would raise.
     *
     * @param name a QP-CAT / QuPath colour-map name
     * @return the matplotlib colormap name, or null when there is no equivalent
     */
    public static String matplotlibName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "Viridis" -> "viridis";
            case "Inferno" -> "inferno";
            case "Magma" -> "magma";
            case "Plasma" -> "plasma";
            case "Blue-White-Red" -> "bwr";
            case "Red-Blue (RdBu)" -> "RdBu_r";
            case "Orange-Purple (PuOr)" -> "PuOr_r";
            default -> null;
        };
    }
}
