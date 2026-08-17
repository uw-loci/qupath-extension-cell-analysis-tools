package qupath.ext.qpcat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of the "Independent areas" configuration: a hierarchy level, plus --
 * for {@link AreaLevel#ANNOTATIONS} -- the annotation classes that count as
 * area boundaries at that level.
 * <p>
 * A list of these reads outermost-first and describes a nesting path. For a
 * hierarchy of {@code TMA core > Tissue > (Tumor | Stroma) > cells}:
 * <pre>
 *   [IMAGES, TMA_CORES]                      -> area = one core
 *   [IMAGES, ANNOTATIONS("Tissue")]          -> area = one Tissue annotation object
 *   [IMAGES, TMA_CORES, ANNOTATIONS("Tissue")] -> area = a Tissue annotation within a core
 * </pre>
 * In every case Tumor and Stroma stay together, because they are below the
 * deepest declared level.
 * <p>
 * Plain mutable POJO with a no-arg constructor so Gson round-trips it into the
 * saved run config without a custom adapter.
 */
public class AreaLevelSpec {

    private AreaLevel level = AreaLevel.IMAGES;

    /**
     * Annotation classes that mark an area boundary. Only meaningful when
     * {@link #level} is {@link AreaLevel#ANNOTATIONS}. Empty means "any
     * annotation, whatever its class".
     */
    private List<String> annotationClasses = new ArrayList<>();

    /** Required by Gson. */
    public AreaLevelSpec() {}

    public AreaLevelSpec(AreaLevel level) {
        this.level = level == null ? AreaLevel.IMAGES : level;
    }

    public AreaLevelSpec(AreaLevel level, List<String> annotationClasses) {
        this(level);
        setAnnotationClasses(annotationClasses);
    }

    public AreaLevel getLevel() {
        // Null-coalesce rather than throw: a config written before this field
        // existed, or hand-edited, must still load.
        return level == null ? AreaLevel.IMAGES : level;
    }

    public void setLevel(AreaLevel level) {
        this.level = level == null ? AreaLevel.IMAGES : level;
    }

    public List<String> getAnnotationClasses() {
        return annotationClasses == null ? List.of() : annotationClasses;
    }

    public void setAnnotationClasses(List<String> annotationClasses) {
        this.annotationClasses = annotationClasses == null
                ? new ArrayList<>() : new ArrayList<>(annotationClasses);
    }

    /** True when this level accepts an annotation of any class. */
    public boolean matchesAnyClass() {
        return getAnnotationClasses().isEmpty();
    }

    /**
     * True when the list can split cells below the image level. Images-only
     * cannot, so it is not worth resolving or shipping.
     */
    public static boolean hasSubImageLevels(List<AreaLevelSpec> levels) {
        if (levels == null) {
            return false;
        }
        for (AreaLevelSpec spec : levels) {
            if (spec != null && spec.getLevel() != AreaLevel.IMAGES) {
                return true;
            }
        }
        return false;
    }

    /** The default configuration: split by image only, i.e. today's behaviour. */
    public static List<AreaLevelSpec> imagesOnly() {
        List<AreaLevelSpec> levels = new ArrayList<>();
        levels.add(new AreaLevelSpec(AreaLevel.IMAGES));
        return levels;
    }

    @Override
    public String toString() {
        if (getLevel() == AreaLevel.ANNOTATIONS && !matchesAnyClass()) {
            return getLevel().getDisplayName() + " [" + String.join(", ", getAnnotationClasses()) + "]";
        }
        return getLevel().getDisplayName();
    }
}
