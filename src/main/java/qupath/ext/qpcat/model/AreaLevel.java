package qupath.ext.qpcat.model;

/**
 * A level in the object hierarchy at which cells are split into independent
 * analysis areas.
 * <p>
 * An "area" is a piece of tissue that is physically separate from every other
 * piece: a TMA core, a tissue section on a slide holding several, or a whole
 * image. Spatial neighbour graphs must never join cells from two different
 * areas -- the distance between them is not a distance through tissue, so any
 * edge crossing that boundary is an invented adjacency.
 * <p>
 * Levels are combined into an ordered list (see {@link AreaLevelSpec}) that
 * reads outermost-first. Everything BELOW the deepest declared level stays in
 * one area: with {@code [IMAGES, TMA_CORES]} the Tumor and Stroma annotations
 * inside a core share a graph, because they are continuous tissue and the
 * interface between them is usually the thing being measured.
 */
public enum AreaLevel {

    /**
     * Split by source image. Always the outermost level and never removable --
     * two images have unrelated coordinate frames, and pixel (100,100) in one
     * is not near pixel (100,100) in another.
     */
    IMAGES("images", "Images"),

    /**
     * Split by dearrayed TMA core. Resolved with
     * {@code PathObjectTools.getAncestorTMACore}, so it works regardless of how
     * many annotation layers sit between the core and the cells.
     */
    TMA_CORES("tma_cores", "TMA cores"),

    /**
     * Split by annotation. Areas are keyed on the annotation OBJECT, not its
     * class: three tissue sections on one slide commonly share a single
     * "Tissue" class, and keying on the class would merge them straight back
     * together. The selected classes decide which annotations count as area
     * boundaries; the class name is a display label only.
     */
    ANNOTATIONS("annotations", "Annotations");

    private final String id;
    private final String displayName;

    AreaLevel(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }

    @Override
    public String toString() { return displayName; }

    /**
     * Resolves a persisted id back to a level, defaulting to {@link #IMAGES}
     * for an unknown or missing value so an older or hand-edited config loads
     * rather than throwing.
     */
    public static AreaLevel fromId(String id) {
        if (id != null) {
            for (AreaLevel level : values()) {
                if (level.id.equalsIgnoreCase(id)) {
                    return level;
                }
            }
        }
        return IMAGES;
    }
}
