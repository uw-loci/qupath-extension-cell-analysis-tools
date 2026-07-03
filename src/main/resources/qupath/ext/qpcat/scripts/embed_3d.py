"""
Compute a 3D dimensionality-reduction embedding for the VEST 3D viewer export.

Standalone Appose task (like spatial_stats_standalone / regenerate_plots). It does
NOT cluster and does NOT touch the QuPath hierarchy -- it only maps the feature
matrix to N x 3 coordinates so the Java side can write a VEST bundle.

Inputs (injected by Appose -- accessed as variables, NOT task.inputs):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  normalization: str ("zscore", "minmax", "percentile", "none")
  method: str ("umap", "pca", "tsne")

Optional inputs:
  n_neighbors: int (UMAP, default 15)
  min_dist: float (UMAP, default 0.1)
  tsne_perplexity: float (t-SNE; <= 0 => auto from cell count)
  percentile_low: float (percentile-normalization lower clip, default 0.01)
  percentile_high: float (percentile-normalization upper clip, default 0.99)
  seed: int (default 42)

Outputs (via task.outputs):
  embedding3d: NDArray (N_cells x 3, float64)
"""

import logging

logger = logging.getLogger("qpcat.embed3d")

import numpy as np
import pandas as pd
from appose import NDArray as PyNDArray

# ---- inputs -------------------------------------------------------------
data = measurements.ndarray().copy()
n_cells, n_markers = data.shape
logger.info("VEST embed_3d: %d cells x %d markers", n_cells, n_markers)

df = pd.DataFrame(data, columns=list(marker_names))

# Guard against non-finite values (UMAP/PCA reject NaN/Inf). Impute per-column
# median, matching run_clustering.py.
n_nonfinite = int(np.count_nonzero(~np.isfinite(df.to_numpy(dtype=float))))
if n_nonfinite > 0:
    df = df.replace([np.inf, -np.inf], np.nan)
    col_median = df.median(numeric_only=True).fillna(0.0)
    df = df.fillna(col_median).fillna(0.0)
    logger.warning("Imputed %d non-finite value(s) with per-column median", n_nonfinite)

try:
    norm = normalization
except NameError:
    norm = "zscore"

try:
    embed_method = method
except NameError:
    embed_method = "umap"

try:
    umap_neighbors = int(n_neighbors)
except NameError:
    umap_neighbors = 15

try:
    umap_min_dist = float(min_dist)
except NameError:
    umap_min_dist = 0.1

try:
    embed_seed = int(seed)
except NameError:
    embed_seed = 42

try:
    tsne_perp_in = float(tsne_perplexity)
except NameError:
    tsne_perp_in = 0.0

try:
    pct_low = float(percentile_low)
except NameError:
    pct_low = 0.01

try:
    pct_high = float(percentile_high)
except NameError:
    pct_high = 0.99

# ---- normalize (same rules as run_clustering.py) ------------------------
if norm == "zscore":
    std = df.std().replace(0, 1).fillna(1)
    df_norm = (df - df.mean()) / std
elif norm == "minmax":
    dmin = df.min()
    drange = (df.max() - dmin).replace(0, 1).fillna(1)
    df_norm = (df - dmin) / drange
elif norm == "percentile":
    lo = max(0.0, min(pct_low, pct_high))
    hi = min(1.0, max(pct_low, pct_high))
    p1 = df.quantile(lo)
    p99 = df.quantile(hi)
    drange = (p99 - p1).replace(0, 1).fillna(1)
    df_norm = (df.clip(lower=p1, upper=p99, axis=1) - p1) / drange
else:
    df_norm = df.copy()

x = df_norm.to_numpy(dtype=np.float64)

# A 3D embedding needs at least 3 samples; fall back to zero-padding otherwise.
if n_cells < 4:
    logger.warning("Too few cells (%d) for a 3D embedding; emitting zeros.", n_cells)
    embedding = np.zeros((n_cells, 3), dtype=np.float64)
elif embed_method == "pca":
    from sklearn.decomposition import PCA

    embedding = PCA(n_components=3, random_state=embed_seed).fit_transform(x)
elif embed_method == "tsne":
    from sklearn.manifold import TSNE

    # perplexity must stay < n_samples. Use the caller's value when given (>0),
    # else auto from cell count; always clamp to a valid range for small sets.
    if tsne_perp_in and tsne_perp_in > 0:
        perplexity = tsne_perp_in
    else:
        perplexity = min(30.0, max(5.0, (n_cells - 1) / 3.0))
    perplexity = float(max(2.0, min(perplexity, (n_cells - 1) / 3.0)))
    embedding = TSNE(
        n_components=3, random_state=embed_seed, perplexity=perplexity, init="pca"
    ).fit_transform(x)
else:  # umap (default)
    import umap

    n_neigh = int(min(umap_neighbors, max(2, n_cells - 1)))
    embedding = umap.UMAP(
        n_components=3,
        n_neighbors=n_neigh,
        min_dist=umap_min_dist,
        random_state=embed_seed,
    ).fit_transform(x)

embedding = np.ascontiguousarray(np.asarray(embedding, dtype=np.float64))
logger.info("VEST embed_3d: produced %s embedding", str(embedding.shape))

out = PyNDArray(dtype="float64", shape=[n_cells, 3])
np.copyto(out.ndarray(), embedding)
task.outputs["embedding3d"] = out
