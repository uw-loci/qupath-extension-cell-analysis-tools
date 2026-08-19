package qupath.ext.qpcat.ui;

/**
 * One copy of the wording that explains each spatial statistic and each graph
 * constructor.
 * <p>
 * Two dialogs offer the same statistics -- Find cell populations (clustering)
 * and Spatial statistics on existing clusters -- and only the first explained
 * them. A user meeting "Geary's C (needs measurements)" for the first time in
 * the second dialog had nothing to hover. Keeping the strings here means the
 * two cannot drift into describing the same statistic differently, which is
 * worse than one of them saying nothing.
 * <p>
 * ASCII only: these reach logs and Windows consoles through the same paths as
 * every other string in the extension.
 */
final class SpatialStatTooltips {

    private SpatialStatTooltips() {}

    static final String RIPLEY =
            "Ripley's K and L per class, against a Poisson (complete spatial randomness)\n"
            + "null. The curve above the null means clustering at that radius; below it\n"
            + "means inhibition. K and L carry the same information -- L just flattens the\n"
            + "null to a horizontal line, which is easier to read.\n\n"
            + "Sensitive to the shape and size of the area, so compare curves only between\n"
            + "areas of similar size.";

    static final String COOC_PAIRWISE =
            "For each PAIR of classes, the ratio of observed to expected co-occurrence at\n"
            + "a range of radii. Surfaces pairs that systematically sit together or avoid\n"
            + "each other. Cost grows with the square of the class count.";

    static final String COOC_ONE_VS_REST =
            "For each class, its co-occurrence against all other classes combined.\n"
            + "Much cheaper than pairwise, and enough when you only want to flag whether\n"
            + "one population is spatially organised at all.";

    static final String NHOOD_ENRICHMENT =
            "Permutation test on the neighbour graph: are cells of class A adjacent to\n"
            + "class B more (or less) often than chance? Reported as a z-score per pair.\n"
            + "Unlike co-occurrence this uses the GRAPH, so it follows whatever graph\n"
            + "constructor is set above rather than a radius sweep.";

    static final String GEARY =
            "Geary's C per marker: local spatial autocorrelation. Near 0 means neighbouring\n"
            + "cells have similar values (clustered); near 2 means they differ (dispersed);\n"
            + "1 is no spatial structure.\n\n"
            + "Computed from the cells' MEASUREMENTS, not their labels, so it needs numeric\n"
            + "measurements to be present. Coordinate, embedding and cluster columns are\n"
            + "filtered out and zero-variance columns are dropped.";

    static final String MORAN =
            "Moran's I per marker: global spatial autocorrelation. Positive means high\n"
            + "values cluster together, negative means high values sit next to low ones,\n"
            + "near 0 means no spatial structure. The complement of Geary's C, which is\n"
            + "more sensitive to local differences.\n\n"
            + "Computed from MEASUREMENTS, so it needs numeric measurements present.";

    static final String GRAPH_TYPE =
            "How neighbours are defined -- this decides what every graph-based statistic\n"
            + "below actually measures.\n\n"
            + "knn: each cell's k nearest cells. Density-adaptive, so a neighbour in dense\n"
            + "tissue is physically closer than one in sparse tissue.\n"
            + "radius: every cell within a fixed distance. A fixed physical scale, but\n"
            + "sparse regions can end up with no neighbours at all.\n"
            + "delaunay: the triangulation's edges. Parameter-free, but produces long\n"
            + "edges across gaps unless you cap the maximum edge length.";

    static final String LABEL_SOURCE =
            "Which labels to analyse.\n\n"
            + "Current cell classifications: reads each cell's PathClass as it is now.\n"
            + "Saved QP-CAT result: matches a saved result's cluster labels to the cells in\n"
            + "memory by image and centroid, and does NOT write PathClasses -- so you can\n"
            + "analyse a saved result without modifying the hierarchy. Renamed or merged\n"
            + "clusters are keyed by their display names.";
}
