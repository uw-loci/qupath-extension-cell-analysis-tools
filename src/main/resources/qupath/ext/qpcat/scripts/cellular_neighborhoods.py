"""
Cellular-neighborhood (CN) detection for QP-CAT Appose tasks.

Given an EXISTING categorical label per cell (a cluster or phenotype) plus the
cells' XY centroids, this builds a spatial "window" of the k nearest neighbors
around every cell, turns each window into a cell-type COMPOSITION vector (the
fraction of each cell type among the window), and k-means-clusters those vectors
into N cellular neighborhoods. Cells in the same neighborhood sit in similar
local tissue mixtures (e.g. "tumor-immune boundary", "stroma", "lymphoid
aggregate"), independent of the cells' own type.

This is the scalable, "default-A" spatial niche analysis: NearestNeighbors with
a KD/Ball tree plus a small k-means is O(n*k) in practice and runs single-process
without the permutation machinery that makes squidpy's nhood_enrichment /
co-occurrence / Ripley pass slow on large slides. It mirrors the original
neighborhood method of Goltsev et al. (CODEX, Cell 2018) and Schurch et al.
(Cell 2020), and the windowed-composition workflow described for imcRtools by
Windhager et al. (Nat Protoc 2023). It is conceptually distinct from BANKSY
(Singhal et al., Nat Genet 2024), which augments each cell's EXPRESSION vector
with a neighborhood-averaged kernel before clustering; here we cluster the
neighborhood COMPOSITION of pre-assigned types, not expression.

Inputs (injected by Appose 0.10.0):
  spatial_coords: NDArray (N_cells x 2, float64) -- centroid X, Y in pixels
  cell_type_labels: list[int] -- categorical index per cell (>=0; -1 allowed
                    and treated as its own "unassigned" type)
  class_names: list[str] -- display name per cell-type index
  k_neighbors: int -- window size (number of nearest neighbors, includes self)
  n_neighborhoods: int -- number of CN clusters for k-means
  seed: int (optional) -- random seed for k-means reproducibility (default 0)
  generate_heatmap: bool (optional) -- render a CN x cell-type enrichment heatmap
  output_dir: str (optional) -- directory to write the heatmap PNG into

Outputs (via task.outputs):
  neighborhood_labels: NDArray (N_cells,) int32 -- CN id per cell (0..N-1)
  n_neighborhoods: int -- actual number of neighborhoods produced
  neighborhood_counts: str (JSON) -- {cn_id: n_cells}
  composition_json: str (JSON) -- {class_names, mean_composition[CN][class]}
  enrichment_json: str (JSON) -- {class_names, log2_enrichment[CN][class]}
  heatmap_path: str -- path to the enrichment heatmap PNG, or "" if none
"""
import logging
import json
import os

logger = logging.getLogger("qpcat.cellular_neighborhoods")

import numpy as np
from appose import NDArray as PyNDArray


def _update(msg):
    try:
        task.update(msg)
    except Exception:
        pass


# 1. Load inputs
_update("Loading cell positions and types...")
coords = spatial_coords.ndarray().astype(np.float64, copy=True)
n_cells = coords.shape[0]
logger.info("Cellular neighborhoods: %d cells", n_cells)

labels_in = np.asarray(cell_type_labels, dtype=np.int64)
if labels_in.shape[0] != n_cells:
    raise ValueError("cell_type_labels length (%d) != number of cells (%d)"
                     % (labels_in.shape[0], n_cells))

names = list(class_names)

# Any cell with label -1 (unassigned) becomes its own trailing "Unassigned"
# class so composition vectors still sum to 1 and the window is complete.
if np.any(labels_in < 0):
    unassigned_idx = len(names)
    names.append("Unassigned")
    labels_arr = labels_in.copy()
    labels_arr[labels_arr < 0] = unassigned_idx
else:
    labels_arr = labels_in
n_classes = len(names)

# Parameters with safe bounds.
try:
    k = int(k_neighbors)
except NameError:
    k = 20
try:
    n_cn = int(n_neighborhoods)
except NameError:
    n_cn = 10
try:
    rng_seed = int(seed)
except NameError:
    rng_seed = 0
try:
    want_heatmap = bool(generate_heatmap)
except NameError:
    want_heatmap = False
try:
    out_dir = output_dir
except NameError:
    out_dir = None

# k cannot exceed the number of cells; n_cn cannot exceed the number of cells.
k = max(1, min(k, n_cells))
n_cn = max(1, min(n_cn, n_cells))
logger.info("k_neighbors=%d, n_neighborhoods=%d, n_classes=%d", k, n_cn, n_classes)

# 2. Build the spatial windows (k nearest neighbors, including self).
#    n_jobs=1 keeps this single-process: Appose runs the task in a worker
#    subprocess where joblib/loky fan-out has deadlocked before, and the tree
#    query is already fast enough that parallelism buys little.
_update("Building spatial windows (k=%d nearest neighbors)..." % k)
from sklearn.neighbors import NearestNeighbors

nn = NearestNeighbors(n_neighbors=k, algorithm="auto", n_jobs=1)
nn.fit(coords)
_, nbr_idx = nn.kneighbors(coords)  # (n_cells, k), includes the cell itself

# 3. Composition vector per window: fraction of each cell type among the k
#    neighbors. Vectorized over cells and neighbors; the only loop is over the
#    (small) number of classes.
_update("Computing neighborhood composition...")
neighbor_labels = labels_arr[nbr_idx]  # (n_cells, k)
composition = np.zeros((n_cells, n_classes), dtype=np.float64)
for c in range(n_classes):
    composition[:, c] = np.count_nonzero(neighbor_labels == c, axis=1) / float(k)

# 4. Cluster the composition vectors into N cellular neighborhoods.
_update("Clustering windows into %d neighborhoods..." % n_cn)
from sklearn.cluster import KMeans

km = KMeans(n_clusters=n_cn, random_state=rng_seed, n_init=10)
cn_labels = km.fit_predict(composition).astype(np.int32)
actual_cn = int(len(np.unique(cn_labels)))
logger.info("Produced %d cellular neighborhoods", actual_cn)

# 5. Summaries: per-CN mean composition + log2 enrichment over the global
#    cell-type frequencies (the canonical Schurch-style CN x cell-type plot).
counts = {}
mean_comp = np.zeros((n_cn, n_classes), dtype=np.float64)
for cn in range(n_cn):
    mask = cn_labels == cn
    counts[str(cn)] = int(np.count_nonzero(mask))
    if counts[str(cn)] > 0:
        mean_comp[cn, :] = composition[mask].mean(axis=0)

global_freq = np.bincount(labels_arr, minlength=n_classes).astype(np.float64)
global_freq = global_freq / max(1.0, global_freq.sum())
eps = 1e-6
enrichment = np.log2((mean_comp + eps) / (global_freq[None, :] + eps))

# 6. Optional enrichment heatmap (rows = neighborhoods, cols = cell types).
heatmap_path = ""
if want_heatmap and out_dir:
    _update("Rendering enrichment heatmap...")
    try:
        os.makedirs(out_dir, exist_ok=True)
        import matplotlib
        matplotlib.use("Agg")  # belt-and-braces; init already sets this
        import matplotlib.pyplot as plt

        vmax = float(np.nanmax(np.abs(enrichment))) if enrichment.size else 1.0
        if not np.isfinite(vmax) or vmax <= 0:
            vmax = 1.0
        fig_w = max(6.0, 0.6 * n_classes + 2.0)
        fig_h = max(4.0, 0.5 * n_cn + 1.5)
        fig, ax = plt.subplots(figsize=(fig_w, fig_h))
        im = ax.imshow(enrichment, aspect="auto", cmap="RdBu_r",
                       vmin=-vmax, vmax=vmax)
        ax.set_xticks(range(n_classes))
        ax.set_xticklabels(names, rotation=45, ha="right", fontsize=8)
        ax.set_yticks(range(n_cn))
        ax.set_yticklabels(["CN %d (n=%d)" % (cn, counts[str(cn)])
                            for cn in range(n_cn)], fontsize=8)
        ax.set_title("Cellular-neighborhood enrichment (log2 vs overall)")
        cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
        cbar.set_label("log2 fold enrichment")
        fig.tight_layout()
        heatmap_path = os.path.join(out_dir, "cn_enrichment.png")
        fig.savefig(heatmap_path, dpi=150, bbox_inches="tight")
        plt.close("all")
        logger.info("Saved CN enrichment heatmap: %s", heatmap_path)
    except Exception as e:
        logger.warning("Failed to render CN heatmap: %s", e)
        heatmap_path = ""

# 7. Package outputs.
_update("Packaging results...")
labels_nd = PyNDArray(dtype="int32", shape=[n_cells])
np.copyto(labels_nd.ndarray(), cn_labels)
task.outputs["neighborhood_labels"] = labels_nd
task.outputs["n_neighborhoods"] = n_cn
task.outputs["neighborhood_counts"] = json.dumps(counts)
task.outputs["composition_json"] = json.dumps(
    {"class_names": names, "mean_composition": mean_comp.tolist()})
task.outputs["enrichment_json"] = json.dumps(
    {"class_names": names, "log2_enrichment": enrichment.tolist()})
task.outputs["heatmap_path"] = heatmap_path

logger.info("Cellular-neighborhood results packaged")
