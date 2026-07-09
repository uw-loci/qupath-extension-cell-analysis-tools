"""
Main clustering script for QP-CAT Appose tasks.

Inputs (injected by Appose 0.10.0 -- accessed as variables, NOT task.inputs):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  algorithm: str ("leiden", "kmeans", "hdbscan", "agglomerative", "minibatchkmeans", "gmm")
  algorithm_params: dict (algorithm-specific parameters)
  normalization: str ("zscore", "minmax", "percentile", "none")
  embedding_method: str ("umap", "pca", "tsne", "none")
  embedding_n_components: int (2 or 3, default 2) -- embedding dimensionality.
    2 keeps the historical NAME1/NAME2 scatter; 3 adds a genuine third axis
    (NAME1/NAME2/NAME3) for downstream 3D viewers.
  embedding_params: dict (method-specific parameters; all optional)
    shared:  random_state (int, default 42)
    umap:    n_neighbors (int, default 15), min_dist (float, default 0.1),
             metric (str, default "euclidean")
    tsne:    perplexity (float, default = tsne_perplexity_default pref),
             learning_rate (float|str, default 200.0),
             n_iter / max_iter (int, default 1000),
             early_exaggeration (float, default 12.0)
    pca:     n_components (int, default 2)

Optional inputs:
  generate_plots: bool (default False)
  output_dir: str (directory for plot images)
  top_n_markers: int (default 5)
  n_representatives: int (default 5) -- top-K representative cells per cluster
  spatial_coords: NDArray (N_cells x 2, float64) -- cell XY centroids for spatial analysis
  enable_batch_correction: bool (default False)
  batch_labels: list[int] -- image index per cell for batch correction
  spatial_knn: int (default 15) -- k neighbors for spatial feature smoothing
  tsne_perplexity_default: float (default 30.0) -- fallback t-SNE perplexity
  hdbscan_min_samples_default: int (default 5) -- fallback HDBSCAN min_samples
  minibatch_kmeans_batch_size: int (default 1024) -- fallback MiniBatchKMeans batch_size
  banksy_pca_dims_default: int (default 20) -- fallback BANKSY PCA dimensions
  plot_dpi: int (default 150) -- DPI for saved plot images
  image_labels: list[int] -- image index per cell (multi-image runs); splits the
    spatial distribution plot per image instead of overlaying coordinate frames
  image_names: list[str] -- image name per index, for the per-image plot titles/keys

Outputs (via task.outputs):
  cluster_labels: NDArray (N_cells,) int32
  n_clusters: int
  embedding: NDArray (N_cells x embedding_n_components) float64
    (if embedding_method != "none"; second dim is 2 or 3)
  cluster_stats: NDArray (n_clusters x N_markers) float64 -- per-cluster marker means
  marker_rankings: str (JSON) -- top markers per cluster with scores
  paga_connectivity: NDArray (n_clusters x n_clusters) float64 -- PAGA graph weights
  paga_cluster_names: str (JSON) -- ordered cluster names for PAGA matrix
  nhood_enrichment: NDArray (n_clusters x n_clusters) float64 -- neighborhood z-scores
  nhood_cluster_names: str (JSON) -- cluster names for enrichment matrix
  spatial_autocorr: str (JSON) -- per-marker Moran's I scores
  plot_paths: str (JSON) -- dict of plot type -> file path (if generate_plots)
"""

import sys
import os
import logging

logger = logging.getLogger("qpcat.clustering")

import contextlib
import warnings

import numpy as np
import pandas as pd
from appose import NDArray as PyNDArray


def _phase_for_fraction(f):
    """Map a monotonic progress fraction to a stable phase token. The fractions
    in the _progress calls below are the phase-start markers; this keeps the
    token <-> fraction mapping in one place next to them. The Java side renders a
    phase checklist from these tokens (and ignores any it did not expect)."""
    if f < 0.15:
        return "normalize"
    if f < 0.45:
        return "embed"
    if f < 0.73:
        return "cluster"
    if f < 0.98:
        return "spatial"
    if f < 1.0:
        return "plots"
    return "apply"


def _progress(frac, message, phase=None):
    """Emit a determinate progress update (fraction in 0..1) with a message.
    The message is prefixed "phase|" with a stable phase token (derived from the
    fraction unless given) so the Java side can advance a phase checklist; it
    strips the token before display. Fractions are phase-start markers and
    monotonic; a skipped phase just jumps forward."""
    try:
        f = max(0.0, min(1.0, float(frac)))
    except (TypeError, ValueError):
        f = 0.0
    token = phase if phase else _phase_for_fraction(f)
    task.update(token + "|" + message, current=int(f * 1000), maximum=1000)


def _supported_kwargs(fn, **kw):
    """Filter kwargs to those `fn` actually accepts, so we can pass
    single-threading hints (numba_parallel / n_jobs / show_progress_bar / seed)
    to squidpy without a TypeError when a given version renamed or dropped one."""
    import inspect

    try:
        params = inspect.signature(fn).parameters
    except (TypeError, ValueError):
        return kw
    if any(p.kind == p.VAR_KEYWORD for p in params.values()):
        return kw
    return {k: v for k, v in kw.items() if k in params}


# 1. Reshape input NDArray to numpy and release shared memory
data = measurements.ndarray().copy()
n_cells, n_markers = data.shape
logger.info("Received %d cells x %d markers", n_cells, n_markers)

df = pd.DataFrame(data, columns=marker_names)

# Guard against non-finite measurements. UMAP, KMeans, and Leiden/BANKSY all
# reject NaN/Inf, and a marker can legitimately be NaN for some cells when
# QuPath could not compute it (this is what made BANKSY fail with
# "Input contains NaN"). Impute per-column with the column median so every cell
# is kept and stays row-aligned with its spatial coordinates (BANKSY indexes
# cells by row, so dropping rows would misalign the spatial graph).
n_nonfinite = int(np.count_nonzero(~np.isfinite(df.to_numpy(dtype=float))))
if n_nonfinite > 0:
    df = df.replace([np.inf, -np.inf], np.nan)
    col_median = df.median(numeric_only=True).fillna(0.0)
    df = df.fillna(col_median).fillna(0.0)
    logger.warning(
        "Imputed %d non-finite measurement value(s) with per-column "
        "median (NaN/Inf are not valid clustering input)",
        n_nonfinite,
    )

# Read optional spatial coordinates early (needed by BANKSY and spatial analysis)
try:
    spatial_data = spatial_coords.ndarray().copy()
    has_spatial_coords = True
    logger.info("Spatial coordinates loaded (%d cells)", spatial_data.shape[0])
except NameError:
    spatial_data = None
    has_spatial_coords = False

# Optional per-cell image identity (multi-image runs). Used to split the spatial
# distribution plot per image instead of overlaying different coordinate frames.
try:
    image_labels_list = list(image_labels)
except NameError:
    image_labels_list = None
try:
    image_names_list = list(image_names)
except NameError:
    image_names_list = None

# Read preference-backed defaults (injected from Java QpcatPreferences)
try:
    pref_spatial_knn = spatial_knn
except NameError:
    pref_spatial_knn = 15
try:
    pref_tsne_perplexity = tsne_perplexity_default
except NameError:
    pref_tsne_perplexity = 30.0
try:
    pref_hdbscan_min_samples = hdbscan_min_samples_default
except NameError:
    pref_hdbscan_min_samples = 5
try:
    pref_minibatch_batch_size = minibatch_kmeans_batch_size
except NameError:
    pref_minibatch_batch_size = 1024
try:
    pref_banksy_pca_dims = banksy_pca_dims_default
except NameError:
    pref_banksy_pca_dims = 20
try:
    pref_plot_dpi = plot_dpi
except NameError:
    pref_plot_dpi = 150

# Spatial stats expansion (v1) inputs
try:
    pref_spatial_graph_type = spatial_graph_type
except NameError:
    pref_spatial_graph_type = "knn"
try:
    pref_spatial_graph_k = spatial_graph_k
except NameError:
    pref_spatial_graph_k = 15
try:
    pref_spatial_graph_radius = spatial_graph_radius
except NameError:
    pref_spatial_graph_radius = -1.0
try:
    pref_spatial_graph_delaunay_max_edge = spatial_graph_delaunay_max_edge
except NameError:
    pref_spatial_graph_delaunay_max_edge = -1.0
try:
    pref_spatial_permutations = spatial_permutations
except NameError:
    pref_spatial_permutations = 0
try:
    pref_use_squidpy_smoothing = use_squidpy_graph_for_smoothing
except NameError:
    pref_use_squidpy_smoothing = False
# Neighborhood enrichment + Moran's I are gated on the explicit "spatial
# analysis" choice. Previously they ran whenever spatial COORDS were present,
# which is always true for BANKSY -- so every BANKSY run triggered them
# uninvited (and nhood_enrichment is the step that deadlocked on Windows).
# Default True only matters if an older caller omits the flag.
try:
    pref_enable_spatial_analysis = enable_spatial_analysis
except NameError:
    pref_enable_spatial_analysis = True
try:
    pref_enable_ripley = enable_ripley
except NameError:
    pref_enable_ripley = False
try:
    pref_enable_geary = enable_geary
except NameError:
    pref_enable_geary = False
try:
    pref_enable_co_occurrence_pairwise = enable_co_occurrence_pairwise
except NameError:
    pref_enable_co_occurrence_pairwise = False
try:
    pref_enable_co_occurrence_one_vs_rest = enable_co_occurrence_one_vs_rest
except NameError:
    pref_enable_co_occurrence_one_vs_rest = False

# v0.3 spatial graph overlay flags. push_connections_to_viewer drives the
# Java-side rebuild of PathObjectConnections; the edge COO is emitted
# unconditionally so the Java side can also rebuild on demand from saved
# results without a re-run. write_node_measurements toggles per-cell
# QPCAT spatial: columns; write_component_measurements toggles emission
# of connected-component labels (the Java side does the groupby fan-out).
try:
    pref_write_node_measurements = write_node_measurements
except NameError:
    pref_write_node_measurements = True
try:
    pref_write_component_measurements = write_component_measurements
except NameError:
    pref_write_component_measurements = False

# Pixel calibration scaling for QPCAT spatial: distance / triangle-area
# columns. Java passes PixelCalibration.getAveragedPixelSizeMicrons() when
# the image has a calibration, otherwise 1.0 (columns stay in pixels for
# uncalibrated images). See F1 in 06_test_response.md.
try:
    pref_spatial_pixel_size_um = float(pixel_size_um)
except (NameError, TypeError, ValueError):
    pref_spatial_pixel_size_um = 1.0

# Phase 5 enhancement: persist spatial-stats plots as PNG. Gated by the
# qpcat.spatial.persistPlots preference (default true). When the parent
# task is also generating plots (generate_plots + output_dir), the new
# spatial-stats PNGs land in the same directory so Feature B's batch
# figure exporter can pick them up alongside the existing matplotlib
# outputs.
try:
    pref_spatial_persist_plots = spatial_persist_plots
except NameError:
    pref_spatial_persist_plots = True

# 2. Normalize
_progress(
    0.05, "Normalizing measurements (%d cells x %d markers)..." % (n_cells, n_markers)
)

if normalization == "zscore":
    std = df.std()
    std[std == 0] = 1  # avoid division by zero for constant columns
    df_norm = (df - df.mean()) / std
elif normalization == "minmax":
    dmin = df.min()
    dmax = df.max()
    drange = dmax - dmin
    drange[drange == 0] = 1
    df_norm = (df - dmin) / drange
elif normalization == "percentile":
    p1 = df.quantile(0.01)
    p99 = df.quantile(0.99)
    drange = p99 - p1
    drange[drange == 0] = 1
    df_norm = df.clip(lower=p1, upper=p99, axis=1)
    df_norm = (df_norm - p1) / drange
else:
    df_norm = df.copy()

logger.info("Normalization: %s", normalization)

# 2a. Spatial feature smoothing (graph convolution on k-nearest neighbor graph)
# Approach inspired by LazySlide (MIT License)
# Zheng, Y. et al. Nature Methods (2026). https://doi.org/10.1038/s41592-026-03044-7
try:
    do_spatial_smoothing = enable_spatial_smoothing
except NameError:
    do_spatial_smoothing = False
try:
    smoothing_iters = spatial_smoothing_iterations
except NameError:
    smoothing_iters = 1

if do_spatial_smoothing and has_spatial_coords:
    task.update("Applying spatial feature smoothing...")
    import scipy.sparse as sp

    n = len(spatial_data)

    if pref_use_squidpy_smoothing:
        # Hybrid graph reuse path (Phase 2 contract #2): same squidpy
        # spatial_neighbors graph backs smoothing AND the new statistics.
        # Pure-A row-normalised connectivity (no +I diagonal); produces
        # subtly different cluster labels at boundaries vs the legacy
        # (A + I) path. Gated behind qpcat.spatial.useSquidpyGraphForSmoothing
        # so existing projects retain bit-for-bit reproducibility.
        # spatial_stats is registered as an importable module by
        # ApposeClusteringService at init (task scripts run via exec("<string>"),
        # so there is no __file__ and the bundled sibling scripts are not on
        # sys.path -- a file-based fallback cannot work here).
        try:
            from spatial_stats import build_smoothing_adjacency_squidpy
        except ImportError as _e:
            raise RuntimeError(
                "spatial_stats module is not available -- the QP-CAT analysis "
                "environment was not initialized with it. Rebuild the analysis "
                "environment (Utilities > Rebuild) and try again."
            ) from _e
        adj_norm = build_smoothing_adjacency_squidpy(
            spatial_data,
            graph_type=pref_spatial_graph_type,
            k=pref_spatial_graph_k,
            radius=pref_spatial_graph_radius,
            delaunay_max_edge=pref_spatial_graph_delaunay_max_edge,
        )
        logger.info(
            "Spatial smoothing using squidpy graph (%s, pure-A row-normalised)",
            pref_spatial_graph_type,
        )
    else:
        # Legacy path - sklearn kNN with (A + I) row-normalisation.
        # This path is byte-stable with respect to prior QP-CAT releases.
        from sklearn.neighbors import NearestNeighbors

        k = min(pref_spatial_knn, n - 1)
        nn = NearestNeighbors(n_neighbors=k, metric="euclidean")
        nn.fit(spatial_data)
        distances, indices = nn.kneighbors(spatial_data)

        # Build row-normalized adjacency matrix (A + I)
        rows = np.repeat(np.arange(n), k)
        cols = indices.ravel()
        adj = sp.csr_matrix((np.ones(len(rows)), (rows, cols)), shape=(n, n))
        adj = adj + sp.eye(n)
        row_sums = np.array(adj.sum(axis=1)).flatten()
        adj_norm = sp.diags(1.0 / row_sums) @ adj
        logger.info(
            "Spatial smoothing using sklearn kNN ((A + I) row-normalised, k=%d)", k
        )

    smoothed = df_norm.values.copy()
    for it in range(smoothing_iters):
        smoothed = adj_norm @ smoothed
    df_norm = pd.DataFrame(smoothed, columns=df_norm.columns)
    logger.info("Spatial smoothing applied: iterations=%d", smoothing_iters)
elif do_spatial_smoothing and not has_spatial_coords:
    logger.warning(
        "Spatial smoothing requested but no spatial coordinates available, skipping"
    )

# 2b. Batch correction (Harmony, for multi-image clustering)


def _orient_batch_corrected(corrected, expected_shape):
    """Return the Harmony output oriented as (n_cells, n_markers).

    harmonypy's public `Harmony.Z_corr` property has returned
    (n_cells, n_features) since 0.2.0; the 0.0.x releases exposed the internal
    (n_features, n_cells) matrix instead. Accept either orientation so that a
    harmonypy bump inside our pinned range cannot silently transpose the
    feature matrix. Raise rather than guess when neither axis matches.
    """
    arr = np.asarray(corrected)
    if arr.shape == expected_shape:
        return arr
    if arr.T.shape == expected_shape:
        return arr.T
    raise ValueError(
        "Harmony returned an array of shape %s; expected %s or its transpose"
        % (arr.shape, expected_shape)
    )


try:
    do_batch = enable_batch_correction
except NameError:
    do_batch = False
try:
    batch_labels_list = batch_labels
except NameError:
    batch_labels_list = None

if do_batch and batch_labels_list is not None:
    task.update("Running batch correction (Harmony)...")
    try:
        import harmonypy as hm
    except ImportError as _hp_err:
        # The Java UI grays out the checkbox when the init-time probe reports
        # harmonypy missing, so reaching this branch means the probe and the
        # task script disagree about the env state - usually a stale install.
        raise RuntimeError(
            "Harmony batch correction is not available in this Python "
            "environment. Use Utilities > Rebuild Clustering Environment "
            "to refresh, or uncheck 'Batch correction (Harmony)' to run "
            "without it. Underlying error: %s" % _hp_err
        )

    n_batches = len(set(batch_labels_list))
    if n_batches > 1:
        meta_df = pd.DataFrame({"batch": [str(b) for b in batch_labels_list]})
        ho = hm.run_harmony(df_norm.values, meta_df, "batch")
        corrected = _orient_batch_corrected(ho.Z_corr, df_norm.shape)
        df_norm = pd.DataFrame(corrected, columns=df_norm.columns, index=df_norm.index)
        logger.info("Harmony batch correction applied (%d batches)", n_batches)
    else:
        logger.info("Skipping batch correction (only 1 batch)")

# 3. Dimensionality reduction
_progress(
    0.15,
    "Computing %s embedding (%d cells) -- this can take a while..."
    % (str(embedding_method).upper(), n_cells),
)

# Random seed for the embedding (exposed in the GUI "Advanced" panel; defaults
# to 42 so prior runs reproduce). Shared by UMAP / t-SNE / PCA.
embedding_seed = int(embedding_params.get("random_state", 42))

# Number of embedding components to compute. 2 (default) preserves the historical
# NAME1/NAME2 scatter byte-for-byte; 3 emits a genuine third axis (NAME1/2/3) for
# downstream 3D viewers. Passed as a top-level input from Java; defaults to 2.
try:
    embedding_n_components = int(embedding_n_components)
except NameError:
    embedding_n_components = 2
if embedding_n_components not in (2, 3):
    logger.warning(
        "embedding_n_components=%s unsupported (expected 2 or 3); using 2",
        embedding_n_components,
    )
    embedding_n_components = 2

embedding_result = None
if embedding_method == "umap":
    import umap

    n_neighbors = embedding_params.get("n_neighbors", 15)
    min_dist = embedding_params.get("min_dist", 0.1)
    metric = embedding_params.get("metric", "euclidean")
    logger.info(
        "UMAP: n_neighbors=%d, min_dist=%.2f, metric=%s, n_components=%d, seed=%d",
        n_neighbors,
        min_dist,
        metric,
        embedding_n_components,
        embedding_seed,
    )
    reducer = umap.UMAP(
        n_neighbors=n_neighbors,
        min_dist=min_dist,
        metric=metric,
        n_components=embedding_n_components,
        random_state=embedding_seed,
    )
    embedding_result = reducer.fit_transform(df_norm.values)

elif embedding_method == "pca":
    from sklearn.decomposition import PCA

    # The embedding output shape follows embedding_n_components (2 or 3); the
    # Java side reads the second dim from the NDArray shape and writes NAME1..K.
    pca = PCA(n_components=embedding_n_components, random_state=embedding_seed)
    embedding_result = pca.fit_transform(df_norm.values)
    logger.info(
        "PCA: explained variance = %s",
        [round(v, 4) for v in pca.explained_variance_ratio_],
    )

elif embedding_method == "tsne":
    import inspect
    from sklearn.manifold import TSNE

    perplexity = float(embedding_params.get("perplexity", pref_tsne_perplexity))
    # sklearn requires perplexity < n_samples; clamp so a too-large value (or a
    # tiny dataset) never errors the run.
    max_perp = max(1.0, float(n_cells) - 1.0)
    if perplexity > max_perp:
        logger.warning(
            "t-SNE perplexity %.1f >= n_cells; clamping to %.1f", perplexity, max_perp
        )
        perplexity = max_perp
    learning_rate = embedding_params.get("learning_rate", 200.0)
    early_exaggeration = float(embedding_params.get("early_exaggeration", 12.0))
    n_iter = int(embedding_params.get("n_iter", embedding_params.get("max_iter", 1000)))
    tsne_kwargs = dict(
        n_components=embedding_n_components,
        perplexity=perplexity,
        learning_rate=learning_rate,
        early_exaggeration=early_exaggeration,
        random_state=embedding_seed,
    )
    # sklearn renamed n_iter -> max_iter in 1.5; pick whichever this build takes.
    tsne_params = inspect.signature(TSNE.__init__).parameters
    if "max_iter" in tsne_params:
        tsne_kwargs["max_iter"] = n_iter
    else:
        tsne_kwargs["n_iter"] = n_iter
    tsne = TSNE(**tsne_kwargs)
    embedding_result = tsne.fit_transform(df_norm.values)
    logger.info(
        "t-SNE: perplexity=%.1f, learning_rate=%s, iterations=%d, "
        "early_exaggeration=%.1f, seed=%d",
        perplexity,
        str(learning_rate),
        n_iter,
        early_exaggeration,
        embedding_seed,
    )

elif embedding_method != "none":
    logger.warning("Unknown embedding method: %s, skipping", embedding_method)

# 4. Clustering
_progress(0.45, "Running %s clustering (%d cells)..." % (str(algorithm), n_cells))

labels = None

# Clustering seed. The GUI exposes a single "Random seed" that drives both the
# embedding and the (stochastic) clustering algorithm, so a run is fully
# reproducible from the UI. We read it from algorithm_params first, then fall
# back to the embedding seed, then 42, so headless (YAML) and older configs
# still behave. KMeans / MiniBatchKMeans / GMM / Leiden / BANKSY all consume it.
clustering_seed = int(
    algorithm_params.get(
        "random_state",
        algorithm_params.get("random_seed", embedding_params.get("random_state", 42)),
    )
)
logger.info("Clustering random seed: %d", clustering_seed)

if algorithm == "leiden":
    import scanpy as sc
    import anndata as ad

    n_neighbors = algorithm_params.get("n_neighbors", 50)
    resolution = algorithm_params.get("resolution", 1.0)
    logger.info("Leiden: n_neighbors=%d, resolution=%.2f", n_neighbors, resolution)

    adata = ad.AnnData(X=df_norm.values)
    sc.pp.neighbors(
        adata, n_neighbors=n_neighbors, use_rep="X", random_state=clustering_seed
    )
    sc.tl.leiden(
        adata,
        resolution=resolution,
        flavor="igraph",
        n_iterations=-1,
        random_state=clustering_seed,
    )
    labels = adata.obs["leiden"].astype(int).values

elif algorithm == "kmeans":
    from sklearn.cluster import KMeans

    n_clusters = algorithm_params.get("n_clusters", 10)
    logger.info("KMeans: n_clusters=%d", n_clusters)
    km = KMeans(n_clusters=n_clusters, n_init=10, random_state=clustering_seed)
    labels = km.fit_predict(df_norm.values)

elif algorithm == "hdbscan":
    from sklearn.cluster import HDBSCAN

    min_cluster_size = algorithm_params.get("min_cluster_size", 15)
    min_samples = algorithm_params.get("min_samples", pref_hdbscan_min_samples)
    logger.info(
        "HDBSCAN: min_cluster_size=%d, min_samples=%d", min_cluster_size, min_samples
    )
    hdb = HDBSCAN(min_cluster_size=min_cluster_size, min_samples=min_samples)
    labels = hdb.fit_predict(df_norm.values)

elif algorithm == "agglomerative":
    from sklearn.cluster import AgglomerativeClustering

    n_clusters = algorithm_params.get("n_clusters", 10)
    linkage = algorithm_params.get("linkage", "ward")
    logger.info("Agglomerative: n_clusters=%d, linkage=%s", n_clusters, linkage)
    agg = AgglomerativeClustering(n_clusters=n_clusters, linkage=linkage)
    labels = agg.fit_predict(df_norm.values)

elif algorithm == "minibatchkmeans":
    from sklearn.cluster import MiniBatchKMeans

    n_clusters = algorithm_params.get("n_clusters", 10)
    batch_size = algorithm_params.get("batch_size", pref_minibatch_batch_size)
    logger.info("MiniBatchKMeans: n_clusters=%d, batch_size=%d", n_clusters, batch_size)
    mbkm = MiniBatchKMeans(
        n_clusters=n_clusters, batch_size=batch_size, random_state=clustering_seed
    )
    labels = mbkm.fit_predict(df_norm.values)

elif algorithm == "gmm":
    from sklearn.mixture import GaussianMixture

    n_components = algorithm_params.get("n_components", 10)
    covariance_type = algorithm_params.get("covariance_type", "full")
    logger.info(
        "GMM: n_components=%d, covariance_type=%s", n_components, covariance_type
    )
    gmm = GaussianMixture(
        n_components=n_components,
        covariance_type=covariance_type,
        random_state=clustering_seed,
    )
    labels = gmm.fit_predict(df_norm.values)

elif algorithm == "banksy":
    if not has_spatial_coords:
        raise ValueError("BANKSY requires spatial coordinates (cell centroids)")

    # pybanksy 1.3.4 exposes a low-level API (initialize -> build matrix -> PCA
    # -> Leiden); there is no single run_banksy_search entry point (the older
    # name our code used does not exist in the published package, which is what
    # caused the ImportError). We drive the documented pipeline directly, which
    # also avoids run_banksy_multiparam's mandatory matplotlib plotting.
    import anndata as ad
    from banksy.initialize_banksy import initialize_banksy
    from banksy.embed_banksy import generate_banksy_matrix
    from banksy_utils.umap_pca import pca_umap
    from banksy.cluster_methods import run_Leiden_partition

    lambda_param = algorithm_params.get("lambda_param", 0.2)
    k_geom = algorithm_params.get("k_geom", 15)
    resolution = algorithm_params.get("resolution", 0.7)
    pca_dims = algorithm_params.get("pca_dims", pref_banksy_pca_dims)

    # Cap parameters to what the dataset supports (BANKSY errors otherwise on
    # small cell counts or few markers). The BANKSY matrix has (max_m+1) blocks
    # of n_markers columns (max_m=1 here), so PCA dims cannot exceed ~2*n_markers.
    k_geom = max(2, min(int(k_geom), n_cells - 1))
    capped_pca = max(2, min(int(pca_dims), n_cells - 1, 2 * n_markers))
    if capped_pca != pca_dims:
        logger.warning(
            "BANKSY pca_dims reduced from %d to %d for this dataset",
            pca_dims,
            capped_pca,
        )
    pca_dims = capped_pca
    logger.info(
        "BANKSY: lambda=%.2f, k_geom=%d, resolution=%.2f, pca_dims=%d",
        lambda_param,
        k_geom,
        resolution,
        pca_dims,
    )

    # Build AnnData with expression and spatial coordinates. BANKSY's coord_keys
    # is (x_obs_col, y_obs_col, spatial_obsm_key); initialize_banksy reads the
    # coordinates from adata.obsm[coord_keys[2]].
    adata_banksy = ad.AnnData(X=df_norm.values.astype(np.float32))
    adata_banksy.var_names = pd.Index(list(marker_names))
    adata_banksy.obsm["spatial"] = spatial_data
    adata_banksy.obs["x"] = spatial_data[:, 0]
    adata_banksy.obs["y"] = spatial_data[:, 1]
    coord_keys = ("x", "y", "spatial")

    # BANKSY prints a large volume of diagnostics via print() -> sys.stdout,
    # which is ALSO Appose's protocol channel. Those lines surface as
    # "[SERVICE-0] <INVALID>" and, on a full pipe, can stall the worker. Silence
    # stdout for the duration of each BANKSY call. task.update() stays OUTSIDE
    # the redirect so progress messages still reach the protocol channel.
    _progress(
        0.46, "BANKSY: initializing spatial neighbor graph (%d cells)..." % n_cells
    )
    with open(os.devnull, "w") as _devnull, contextlib.redirect_stdout(_devnull):
        banksy_dict = initialize_banksy(
            adata_banksy,
            coord_keys,
            num_neighbours=k_geom,
            nbr_weight_decay="scaled_gaussian",
            max_m=1,
            plt_edge_hist=False,
            plt_nbr_weights=False,
            plt_agf_angles=False,
            plt_theta=False,
        )

    _progress(0.49, "BANKSY: building spatially-augmented feature matrix...")
    with open(os.devnull, "w") as _devnull, contextlib.redirect_stdout(_devnull):
        banksy_dict, _banksy_matrix = generate_banksy_matrix(
            adata_banksy, banksy_dict, [lambda_param], 1, verbose=False
        )
        pca_umap(
            banksy_dict, pca_dims=[pca_dims], add_umap=False, plt_remaining_var=False
        )

    _progress(0.55, "BANKSY: Leiden clustering on spatial features...")
    with open(os.devnull, "w") as _devnull, contextlib.redirect_stdout(_devnull):
        results_df, _max_num_labels = run_Leiden_partition(
            banksy_dict,
            [resolution],
            num_nn=50,
            num_iterations=-1,
            partition_seed=clustering_seed,
            match_labels=False,
            annotations=None,
            max_labels=None,
        )

    # Labels live in the returned dataframe (a Label object with a .dense array),
    # NOT in adata.obs.
    if results_df is None or len(results_df.index) == 0:
        raise ValueError("BANKSY did not produce cluster labels")
    label_obj = results_df.loc[results_df.index[0], "labels"]
    labels = label_obj.dense if hasattr(label_obj, "dense") else np.asarray(label_obj)
    labels = np.asarray(labels).astype(int)
    logger.info(
        "BANKSY clustering complete: %d clusters for %d cells",
        len(set(labels.tolist())),
        len(labels),
    )

elif algorithm == "none":
    # Embedding only -- assign all cells to cluster 0 (no real clustering)
    labels = np.zeros(n_cells, dtype=np.int32)
    logger.info("Embedding-only mode: no clustering applied")

else:
    raise ValueError("Unknown clustering algorithm: %s" % algorithm)

# 5. Compute cluster statistics (per-cluster marker means on normalized data)
task.update("Computing cluster statistics...", current=3, maximum=6)

n_clusters_found = int(labels.max() + 1) if labels.min() >= 0 else int(labels.max() + 2)
# For algorithms that produce noise labels (-1), shift to 0-based
if labels.min() < 0:
    # Noise points get their own cluster at the end
    labels_shifted = labels.copy()
    labels_shifted[labels_shifted < 0] = labels.max() + 1
    n_clusters_found = int(labels_shifted.max() + 1)
else:
    labels_shifted = labels

df_norm["cluster"] = labels_shifted
cluster_means = df_norm.groupby("cluster").mean(numeric_only=True).values

logger.info("Clustering complete: %d clusters found", n_clusters_found)

# 5b. Representative cells per cluster (medoids).
# For each cluster, rank member cells by distance to the cluster center under
# two definitions and emit the top-K indices (into the original cell order, so
# the Java side maps them straight back to detections):
#   feature   -- distance to the cluster mean in the normalized feature space
#                (the same space cluster_stats lives in)
#   embedding -- distance to the cluster mean in the 2D embedding (skipped when
#                no embedding was computed)
# The first index in each list is the medoid (closest to center).
import json as _json_rep

try:
    rep_k = int(n_representatives)
except (NameError, TypeError, ValueError):
    rep_k = 5
rep_k = max(1, rep_k)

_feat_matrix = df_norm.drop(columns=["cluster"]).values
representatives = {}
for _c in range(n_clusters_found):
    _members = np.where(labels_shifted == _c)[0]
    if _members.size == 0:
        representatives[str(_c)] = {"feature": [], "embedding": []}
        continue

    # Feature-space ranking (cluster_means row _c is the normalized centroid).
    _fd = _feat_matrix[_members] - cluster_means[_c]
    _fdist = np.einsum("ij,ij->i", _fd, _fd)  # squared L2; ordering only
    _forder = _members[np.argsort(_fdist, kind="stable")][:rep_k]
    feat_idx = [int(i) for i in _forder]

    # Embedding-space ranking (optional).
    emb_idx = []
    if embedding_result is not None:
        _ec = embedding_result[_members]
        _ecenter = _ec.mean(axis=0)
        _ed = _ec - _ecenter
        _edist = np.einsum("ij,ij->i", _ed, _ed)
        _eorder = _members[np.argsort(_edist, kind="stable")][:rep_k]
        emb_idx = [int(i) for i in _eorder]

    representatives[str(_c)] = {"feature": feat_idx, "embedding": emb_idx}

task.outputs["representatives"] = _json_rep.dumps(representatives)
logger.info("Representative cells computed: top %d per cluster", rep_k)

# 6. Post-clustering analysis (marker ranking + PAGA)
_progress(0.66, "Ranking cluster markers (%d clusters)..." % n_clusters_found)

import scanpy as sc
import anndata as ad
import json
import math


def _finite_or_none(v):
    """Coerce NaN/Inf floats to None so json.dumps emits RFC-valid output.
    Python's default json.dumps writes NaN/Infinity as bare tokens, which
    strict JSON parsers (including Gson on the Java side) reject."""
    if isinstance(v, float) and not math.isfinite(v):
        return None
    return v


def _sanitize_json_tree(obj):
    """Recursively replace non-finite floats with None throughout obj."""
    if isinstance(obj, dict):
        return {k: _sanitize_json_tree(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_sanitize_json_tree(v) for v in obj]
    return _finite_or_none(obj)


# Build full AnnData for scanpy analysis
adata = ad.AnnData(X=df_norm.drop(columns=["cluster"]).values)
adata.var_names = pd.Index(list(marker_names))
cluster_labels_str = [str(x) for x in labels_shifted]
# Pin the Categorical's order to NUMERIC, not lexicographic. Without this,
# str categories sort as 0,1,10,11,12,13,2,3,... and scanpy assigns palette
# colors in that order -- which disagrees with the interactive embedding
# panel on the Java side (CLUSTER_COLORS[cluster_id % 20], numeric). Pinning
# explicit numeric category order keeps both views colored consistently.
_cluster_category_order = [str(x) for x in sorted({int(s) for s in cluster_labels_str})]
adata.obs["cluster"] = pd.Categorical(
    cluster_labels_str, categories=_cluster_category_order, ordered=False
)

if embedding_result is not None:
    adata.obsm["X_embed"] = embedding_result

# Compute neighbor graph (needed for PAGA and dendrogram)
n_neigh = min(15, n_cells - 1)
embedding_only = algorithm == "none"
can_analyze = n_neigh >= 2 and n_clusters_found > 1 and not embedding_only

if can_analyze:
    sc.pp.neighbors(adata, n_neighbors=n_neigh, use_rep="X")
    sc.tl.dendrogram(adata, groupby="cluster")

    # 6a. Marker ranking (Wilcoxon rank-sum test)
    try:
        top_n = top_n_markers
    except NameError:
        top_n = 5

    try:
        # Wilcoxon ranking is rank-based, so scores/pvals are valid on the
        # normalized matrix. But scanpy computes log fold-changes assuming
        # non-negative (log1p-style) expression; on signed data (z-score /
        # percentile) it does log2 of negative means -> NaN ("invalid value
        # encountered in log2"). Suppress that noise and compute interpretable
        # fold-changes ourselves from the RAW intensities below.
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            sc.tl.rank_genes_groups(adata, groupby="cluster", method="wilcoxon")

        # Per-cluster log2 fold-change of MEAN RAW intensity (cluster vs rest).
        # df holds the raw (NaN-imputed) intensities, row-aligned with
        # labels_shifted. Clip negatives (rare, e.g. background-subtracted) so
        # the ratio is well-defined; a small epsilon guards zero-mean markers.
        _raw = np.clip(df.to_numpy(dtype=float), 0.0, None)
        _eps = 1e-9
        _labels_arr = np.asarray(labels_shifted)
        _logfc_by_cluster = {}
        for _cid in adata.obs["cluster"].cat.categories:
            _in = _labels_arr == int(_cid)
            if _in.sum() == 0 or (~_in).sum() == 0:
                _logfc_by_cluster[str(_cid)] = {}
                continue
            _mean_in = _raw[_in].mean(axis=0)
            _mean_rest = _raw[~_in].mean(axis=0)
            _lfc = np.log2((_mean_in + _eps) / (_mean_rest + _eps))
            _logfc_by_cluster[str(_cid)] = {
                str(marker_names[j]): float(_lfc[j]) for j in range(len(marker_names))
            }

        marker_result = {}
        result_data = adata.uns["rank_genes_groups"]
        for cid in adata.obs["cluster"].cat.categories:
            markers_list = []
            names = result_data["names"][cid][:top_n]
            scores = result_data["scores"][cid][:top_n]
            pvals = result_data["pvals_adj"][cid][:top_n]
            _cluster_lfc = _logfc_by_cluster.get(str(cid), {})
            for i in range(len(names)):
                markers_list.append(
                    {
                        "name": str(names[i]),
                        "score": float(scores[i]),
                        "logfoldchange": _cluster_lfc.get(str(names[i]), float("nan")),
                        "pval_adj": float(pvals[i]),
                    }
                )
            marker_result[str(cid)] = markers_list

        task.outputs["marker_rankings"] = json.dumps(_sanitize_json_tree(marker_result))
        logger.info("Marker ranking complete: top %d markers per cluster", top_n)
    except Exception as e:
        logger.warning("Marker ranking failed: %s", e)

    # 6b. PAGA (cluster connectivity / trajectory graph)
    try:
        sc.tl.paga(adata, groups="cluster")
        paga_conn = adata.uns["paga"]["connectivities"].toarray()

        paga_nd = PyNDArray(dtype="float64", shape=list(paga_conn.shape))
        np.copyto(paga_nd.ndarray(), paga_conn.astype(np.float64))
        task.outputs["paga_connectivity"] = paga_nd
        task.outputs["paga_cluster_names"] = json.dumps(
            list(adata.obs["cluster"].cat.categories)
        )
        logger.info(
            "PAGA connectivity computed (%d x %d)",
            paga_conn.shape[0],
            paga_conn.shape[1],
        )
    except Exception as e:
        logger.warning("PAGA computation failed: %s", e)
else:
    logger.info("Skipping post-analysis (too few cells or clusters)")

# 6c. Spatial analysis (if coordinates provided)
has_spatial = has_spatial_coords

# Decide whether we are going to do anything spatial at all. The v0 path
# runs whenever spatial_coords are passed; the v1 path adds per-statistic
# toggles that gate the new heavy permutation tests.
any_v1_stats = (
    pref_enable_ripley
    or pref_enable_geary
    or pref_enable_co_occurrence_pairwise
    or pref_enable_co_occurrence_one_vs_rest
)

if has_spatial and n_clusters_found > 1:
    _progress(0.73, "Building spatial neighbor graph (%d cells)..." % n_cells)
    import squidpy as sq

    # The v1 spatial-stats helpers live in spatial_stats, registered as an
    # importable module by ApposeClusteringService at init. There is no usable
    # file-based fallback here -- task scripts run via exec("<string>") with no
    # __file__ and the sibling scripts are not on sys.path.
    try:
        import spatial_stats as _qpcat_spatial
    except ImportError as _e:
        raise RuntimeError(
            "spatial_stats module is not available -- the QP-CAT analysis "
            "environment was not initialized with it. Rebuild the analysis "
            "environment (Utilities > Rebuild) and try again."
        ) from _e

    adata.obsm["spatial"] = spatial_data
    adata.obsm["X_spatial"] = spatial_data  # for scanpy plotting (basis='spatial')
    logger.info("Spatial coordinates loaded (%d cells)", spatial_data.shape[0])

    # Build the spatial neighbor graph using the v1 explicit constructor
    # (kNN / Radius / Delaunay). Pre-v1 always used Delaunay; the new
    # default is kNN at k = 15 to match the smoothing default.
    try:
        _qpcat_spatial.build_spatial_graph(
            adata,
            graph_type=pref_spatial_graph_type,
            k=pref_spatial_graph_k,
            radius=pref_spatial_graph_radius,
            delaunay_max_edge=pref_spatial_graph_delaunay_max_edge,
        )
        logger.info("Spatial neighbor graph built (%s)", pref_spatial_graph_type)
        task.outputs["spatial_graph_type"] = pref_spatial_graph_type

        # v0.3 spatial graph overlay: emit edge COO + optional per-cell
        # node measurements + optional component labels. The edge COO is
        # always emitted so the Java side can rebuild PathObjectConnections
        # without re-running clustering. The measurement-write flags are
        # honoured Python-side so the wire payload stays minimal for
        # measurement-disabled runs.
        try:
            _node_payload = _qpcat_spatial.compute_spatial_node_measurements(
                adata.obsp["spatial_connectivities"],
                adata.obsp.get("spatial_distances"),
                spatial_data,
                pref_spatial_graph_type,
                pixel_size_um=pref_spatial_pixel_size_um,
            )
            _qpcat_spatial.emit_spatial_node_outputs(
                task,
                _node_payload,
                pref_spatial_graph_type,
                pref_write_node_measurements,
                pref_write_component_measurements,
            )
        except Exception as e:
            logger.warning("Spatial node measurements failed: %s", e)
    except Exception as e:
        logger.warning("Spatial neighbor graph failed: %s", e)
        has_spatial = False

if has_spatial and n_clusters_found > 1:
    # Serialize numba for the spatial permutation stats ONLY. squidpy's
    # nhood_enrichment / co-occurrence / autocorr use numba parallel loops that
    # DEADLOCK inside the Appose worker subprocess on Windows -- this is what
    # hung at nhood_enrichment. They are small-data/ROI tools, so serial costs
    # little (threads give an N-cores constant factor, not better scaling);
    # clustering / UMAP / BANKSY above already ran with full parallelism and are
    # NOT affected. Saved and restored after the section so the next run in this
    # worker keeps its threads.
    _numba_threads_saved = None
    try:
        import numba as _numba_mod

        _numba_threads_saved = _numba_mod.get_num_threads()
        _numba_mod.set_num_threads(1)
    except Exception:
        pass

    # Neighborhood enrichment + Moran's I, gated on the explicit spatial-analysis
    # choice so a BANKSY run (which always supplies coordinates) does not trigger
    # these uninvited.
    if pref_enable_spatial_analysis:
        # Neighborhood enrichment (z-score matrix)
        try:
            _progress(0.78, "Computing neighborhood enrichment (permutation test)...")
            sq.gr.nhood_enrichment(
                adata,
                cluster_key="cluster",
                **_supported_kwargs(
                    sq.gr.nhood_enrichment,
                    numba_parallel=False,
                    n_jobs=1,
                    show_progress_bar=False,
                    seed=0,
                )
            )
            nhood_data = adata.uns["cluster_nhood_enrichment"]
            zscore = nhood_data["zscore"]

            nhood_nd = PyNDArray(dtype="float64", shape=list(zscore.shape))
            np.copyto(nhood_nd.ndarray(), zscore.astype(np.float64))
            task.outputs["nhood_enrichment"] = nhood_nd
            task.outputs["nhood_cluster_names"] = json.dumps(
                list(adata.obs["cluster"].cat.categories)
            )
            logger.info(
                "Neighborhood enrichment computed (%d x %d)",
                zscore.shape[0],
                zscore.shape[1],
            )
        except Exception as e:
            logger.warning("Neighborhood enrichment failed: %s", e)

        # Spatial autocorrelation (Moran's I per marker)
        try:
            _progress(0.82, "Computing Moran's I spatial autocorrelation...")
            df_autocorr = sq.gr.spatial_autocorr(
                adata,
                mode="moran",
                **_supported_kwargs(
                    sq.gr.spatial_autocorr, n_jobs=1, show_progress_bar=False, seed=0
                )
            )
            autocorr_results = {}
            for marker in marker_names:
                if marker in df_autocorr.index:
                    row = df_autocorr.loc[marker]
                    autocorr_results[marker] = {
                        "I": float(row["I"]),
                        "pval": float(
                            row.get("pval_norm", row.get("pval_z_sim", float("nan")))
                        ),
                    }
            task.outputs["spatial_autocorr"] = json.dumps(
                _sanitize_json_tree(autocorr_results)
            )
            logger.info(
                "Spatial autocorrelation (Moran's I) computed for %d markers",
                len(autocorr_results),
            )
        except Exception as e:
            logger.warning("Spatial autocorrelation failed: %s", e)

    # ---- v1 spatial stats expansion ----
    # Each new statistic wraps in its own try/except via the helper module
    # and never escapes to the parent task. Java side checks task.outputs
    # for each key independently (hasRipley / hasGeary / etc.).
    n_perms = _qpcat_spatial.adaptive_permutations(
        n_cells, override=pref_spatial_permutations
    )

    # Phase 5: route PNG output for the new stats into the same directory
    # the existing matplotlib plots write to (output_dir, when set). If
    # the user disabled plots entirely (plot_dir is None) we skip the
    # savefig step regardless of persist_plots.
    try:
        _spatial_plot_dir = output_dir
    except NameError:
        _spatial_plot_dir = None
    _spatial_persist = bool(pref_spatial_persist_plots) and bool(_spatial_plot_dir)

    # Pre-init plot_paths so the spatial-stats helpers' on-disk PNGs can
    # be advertised to SavedClusteringResult.plotPaths under the keys
    # Feature B's PlotKind enum consumes (ripley_k, ripley_l, geary_c,
    # cooc_pairwise, cooc_one_vs_rest). Section 7 reuses the same dict
    # below and emits it via task.outputs['plot_paths'].
    plot_paths = {}

    if pref_enable_ripley:
        _progress(
            0.86,
            "Computing Ripley K and L (%d permutations on %d cells) "
            "-- this can take several minutes..." % (n_perms, n_cells),
        )
        _qpcat_spatial.run_ripley(
            adata,
            task,
            cluster_key="cluster",
            n_permutations=n_perms,
            graph_type=pref_spatial_graph_type,
            plot_dir=_spatial_plot_dir,
            plot_dpi=pref_plot_dpi,
            persist_plots=_spatial_persist,
        )
        if _spatial_persist:
            _ripley_path = os.path.join(
                _spatial_plot_dir, _qpcat_spatial.PLOT_FILE_RIPLEY
            )
            if os.path.exists(_ripley_path):
                # The single PNG carries both K and L panels; expose under
                # both PlotKind savedPlotKey values so either checkbox in
                # Feature B's exporter finds it.
                plot_paths["ripley_k"] = _ripley_path
                plot_paths["ripley_l"] = _ripley_path

    if pref_enable_geary:
        _progress(
            0.90,
            "Computing Geary's C (%d permutations on %d cells)..." % (n_perms, n_cells),
        )
        _qpcat_spatial.run_geary_c(
            adata,
            task,
            n_permutations=n_perms,
            measurements=list(marker_names),
            graph_type=pref_spatial_graph_type,
            plot_dir=_spatial_plot_dir,
            plot_dpi=pref_plot_dpi,
            persist_plots=_spatial_persist,
        )
        if _spatial_persist:
            _geary_path = os.path.join(
                _spatial_plot_dir, _qpcat_spatial.PLOT_FILE_GEARY
            )
            if os.path.exists(_geary_path):
                plot_paths["geary_c"] = _geary_path

    if pref_enable_co_occurrence_pairwise:
        _progress(
            0.94,
            "Computing co-occurrence, pairwise (%d cells) "
            "-- this can be slow..." % n_cells,
        )
        _qpcat_spatial.run_co_occurrence(
            adata,
            task,
            cluster_key="cluster",
            mode="pairwise",
            n_permutations=n_perms,
            spatial_data=spatial_data,
            graph_type=pref_spatial_graph_type,
            plot_dir=_spatial_plot_dir,
            plot_dpi=pref_plot_dpi,
            persist_plots=_spatial_persist,
        )
        if _spatial_persist:
            _cooc_p_path = os.path.join(
                _spatial_plot_dir, _qpcat_spatial.PLOT_FILE_COOC_PAIRWISE
            )
            if os.path.exists(_cooc_p_path):
                plot_paths["cooc_pairwise"] = _cooc_p_path

    if pref_enable_co_occurrence_one_vs_rest:
        _progress(0.97, "Computing co-occurrence, one-vs-rest (%d cells)..." % n_cells)
        _qpcat_spatial.run_co_occurrence(
            adata,
            task,
            cluster_key="cluster",
            mode="oneVsRest",
            n_permutations=n_perms,
            spatial_data=spatial_data,
            graph_type=pref_spatial_graph_type,
            plot_dir=_spatial_plot_dir,
            plot_dpi=pref_plot_dpi,
            persist_plots=_spatial_persist,
        )
        if _spatial_persist:
            _cooc_o_path = os.path.join(
                _spatial_plot_dir, _qpcat_spatial.PLOT_FILE_COOC_ONE_VS_REST
            )
            if os.path.exists(_cooc_o_path):
                plot_paths["cooc_one_vs_rest"] = _cooc_o_path

    if any_v1_stats:
        # Surface the resolved adaptive count to the Java side so the
        # audit log row can report the value actually used.
        task.outputs["spatial_n_permutations"] = int(n_perms)

    # Restore numba's thread count for any subsequent task in this worker.
    if _numba_threads_saved is not None:
        try:
            import numba as _numba_mod

            _numba_mod.set_num_threads(_numba_threads_saved)
        except Exception:
            pass

# 7. Generate plots (optional)
try:
    do_plots = generate_plots
except NameError:
    do_plots = False
try:
    plot_dir = output_dir
except NameError:
    plot_dir = None

if do_plots and plot_dir and can_analyze:
    _progress(0.98, "Generating plots...")
    import matplotlib.pyplot as plt

    os.makedirs(plot_dir, exist_ok=True)
    # plot_paths was pre-initialised in section 6c when spatial-stats ran,
    # so we either inherit the spatial PNG entries or start fresh here.
    try:
        plot_paths  # noqa: F821 -- name probe
    except NameError:
        plot_paths = {}

    # Dotplot with dendrogram -- fraction expressing + mean expression per cluster
    try:
        dp = sc.pl.dotplot(
            adata,
            var_names=list(marker_names),
            groupby="cluster",
            dendrogram=True,
            standard_scale="var",
            show=False,
            return_fig=True,
        )
        dotplot_path = os.path.join(plot_dir, "cluster_dotplot.png")
        dp.savefig(dotplot_path, dpi=pref_plot_dpi, bbox_inches="tight")
        plt.close("all")
        plot_paths["dotplot"] = dotplot_path
        logger.info("Saved dotplot: %s", dotplot_path)
    except Exception as e:
        logger.warning("Failed to generate dotplot: %s", e)

    # Matrix plot -- mean expression heatmap per cluster
    try:
        mp = sc.pl.matrixplot(
            adata,
            var_names=list(marker_names),
            groupby="cluster",
            dendrogram=True,
            standard_scale="var",
            show=False,
            return_fig=True,
        )
        matrixplot_path = os.path.join(plot_dir, "cluster_matrixplot.png")
        mp.savefig(matrixplot_path, dpi=pref_plot_dpi, bbox_inches="tight")
        plt.close("all")
        plot_paths["matrixplot"] = matrixplot_path
        logger.info("Saved matrixplot: %s", matrixplot_path)
    except Exception as e:
        logger.warning("Failed to generate matrixplot: %s", e)

    # PAGA graph -- cluster connectivity / trajectory
    try:
        sc.pl.paga(adata, show=False)
        paga_path = os.path.join(plot_dir, "paga_graph.png")
        plt.savefig(paga_path, dpi=pref_plot_dpi, bbox_inches="tight")
        plt.close("all")
        plot_paths["paga"] = paga_path
        logger.info("Saved PAGA graph: %s", paga_path)
    except Exception as e:
        logger.warning("Failed to generate PAGA graph: %s", e)

    # Stacked violin plot -- expression distribution per cluster
    if n_clusters_found > 1:
        try:
            sv = sc.pl.stacked_violin(
                adata,
                var_names=list(marker_names),
                groupby="cluster",
                dendrogram=True,
                show=False,
                return_fig=True,
            )
            violin_path = os.path.join(plot_dir, "stacked_violin.png")
            sv.savefig(violin_path, dpi=pref_plot_dpi, bbox_inches="tight")
            plt.close("all")
            plot_paths["stacked_violin"] = violin_path
            logger.info("Saved stacked violin: %s", violin_path)
        except Exception as e:
            logger.warning("Failed to generate stacked violin: %s", e)

    # Embedding scatter colored by cluster (if embedding was computed)
    if embedding_result is not None:
        try:
            fig, ax = plt.subplots(figsize=(8, 6))
            sc.pl.embedding(adata, basis="embed", color="cluster", show=False, ax=ax)
            embed_path = os.path.join(plot_dir, "cluster_embedding.png")
            fig.savefig(embed_path, dpi=pref_plot_dpi, bbox_inches="tight")
            plt.close("all")
            plot_paths["embedding"] = embed_path
            logger.info("Saved embedding plot: %s", embed_path)
        except Exception as e:
            logger.warning("Failed to generate embedding plot: %s", e)

    # Spatial plots (if spatial coordinates were provided)
    if has_spatial:
        # Neighborhood enrichment heatmap
        try:
            sq.pl.nhood_enrichment(adata, cluster_key="cluster", show=False)
            nhood_path = os.path.join(plot_dir, "nhood_enrichment.png")
            plt.savefig(nhood_path, dpi=pref_plot_dpi, bbox_inches="tight")
            plt.close("all")
            plot_paths["nhood_enrichment"] = nhood_path
            logger.info("Saved neighborhood enrichment heatmap: %s", nhood_path)
        except Exception as e:
            logger.warning("Failed to generate nhood enrichment plot: %s", e)

        # Spatial scatter colored by cluster. For multi-image runs produce ONE plot PER
        # IMAGE -- cells from different images share no coordinate frame, so overlaying
        # them is meaningless. Keys "spatial_scatter::<image name>" let the results dialog
        # offer an image dropdown; "spatial_scatter" (first image) stays for back-compat.
        try:
            clusters_cat = adata.obs["cluster"].cat.categories
            n_cats = len(clusters_cat)
            cmap = plt.cm.get_cmap("tab20" if n_cats > 10 else "tab10", n_cats)
            clusters_series = adata.obs["cluster"].values

            def _spatial_fig(coords, cluster_vals, title):
                fig, ax = plt.subplots(figsize=(10, 8))
                for idx, cl in enumerate(clusters_cat):
                    m = cluster_vals == cl
                    if not np.any(m):
                        continue
                    ax.scatter(
                        coords[m, 0],
                        coords[m, 1],
                        c=[cmap(idx)],
                        s=1,
                        alpha=0.5,
                        label=str(cl),
                        rasterized=True,
                    )
                ax.set_xlabel("X (pixels)")
                ax.set_ylabel("Y (pixels)")
                ax.set_aspect("equal")
                ax.invert_yaxis()  # image coordinates: Y increases downward
                ax.set_title(title)
                ax.legend(
                    title="Cluster",
                    markerscale=5,
                    fontsize="small",
                    loc="center left",
                    bbox_to_anchor=(1, 0.5),
                )
                return fig

            if image_labels_list is not None and len(set(image_labels_list)) > 1:
                labels_arr = np.asarray(image_labels_list)
                uniq = sorted(set(image_labels_list))
                max_images = 24
                first_path = None
                for k in uniq[:max_images]:
                    m = labels_arr == k
                    name = (
                        image_names_list[k]
                        if image_names_list is not None and k < len(image_names_list)
                        else "Image %d" % k
                    )
                    fig = _spatial_fig(
                        spatial_data[m],
                        clusters_series[m],
                        "Spatial distribution -- %s" % name,
                    )
                    p = os.path.join(plot_dir, "spatial_scatter_%d.png" % k)
                    fig.savefig(p, dpi=pref_plot_dpi, bbox_inches="tight")
                    plt.close(fig)
                    plot_paths["spatial_scatter::%s" % name] = p
                    if first_path is None:
                        first_path = p
                if first_path is not None:
                    plot_paths["spatial_scatter"] = first_path
                if len(uniq) > max_images:
                    logger.info(
                        "Spatial scatter: showed first %d of %d images",
                        max_images,
                        len(uniq),
                    )
                logger.info(
                    "Saved %d per-image spatial scatters", min(len(uniq), max_images)
                )
            else:
                fig = _spatial_fig(
                    spatial_data, clusters_series, "Spatial distribution by cluster"
                )
                spatial_path = os.path.join(plot_dir, "spatial_scatter.png")
                fig.savefig(spatial_path, dpi=pref_plot_dpi, bbox_inches="tight")
                plt.close(fig)
                plot_paths["spatial_scatter"] = spatial_path
                logger.info("Saved spatial scatter: %s", spatial_path)
            plt.close("all")
        except Exception as e:
            logger.warning("Failed to generate spatial scatter plot: %s", e)

    if plot_paths:
        task.outputs["plot_paths"] = json.dumps(plot_paths)
else:
    # do_plots was False but spatial-stats may still have populated
    # plot_paths in section 6c -- emit it so Feature B can find the
    # spatial PNGs even when matplotlib plots are disabled.
    try:
        if plot_paths:
            task.outputs["plot_paths"] = json.dumps(plot_paths)
    except NameError:
        pass

# 8. Package core outputs
_progress(1.0, "Packaging results...")

# Cluster labels
labels_nd = PyNDArray(dtype="int32", shape=[n_cells])
np.copyto(labels_nd.ndarray(), labels.astype(np.int32))
task.outputs["cluster_labels"] = labels_nd
task.outputs["n_clusters"] = n_clusters_found

# Embedding. Second dim follows the computed component count (2 or 3); the Java
# side reads it from the NDArray shape and writes NAME1/NAME2[/NAME3].
if embedding_result is not None:
    n_emb_dims = int(embedding_result.shape[1])
    emb_nd = PyNDArray(dtype="float64", shape=[n_cells, n_emb_dims])
    np.copyto(emb_nd.ndarray(), embedding_result.astype(np.float64))
    task.outputs["embedding"] = emb_nd

# Cluster statistics (means)
stats_nd = PyNDArray(dtype="float64", shape=list(cluster_means.shape))
np.copyto(stats_nd.ndarray(), cluster_means.astype(np.float64))
task.outputs["cluster_stats"] = stats_nd

logger.info("Results packaged and ready for Java side")
