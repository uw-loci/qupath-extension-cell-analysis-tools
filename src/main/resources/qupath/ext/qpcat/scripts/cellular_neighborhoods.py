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

MULTI-IMAGE (joint cohort) mode
-------------------------------
When image_ids is supplied, spatial windows are built INDEPENDENTLY per image
(cells in different slides are never neighbors), then ALL composition vectors
are pooled and clustered with ONE k-means so a neighborhood id means the same
cell-type mixture in every image -- the only way per-sample CN proportions are
comparable across the cohort (Goltsev 2018 / Schurch 2020 / imcRtools fit CNs
across the whole dataset, then compare CN frequencies between groups). The
script then reports per-sample CN proportions and, when group_labels is given,
per-group mean proportions for condition/treatment comparisons.

Inputs (injected by Appose 0.10.0):
  spatial_coords: NDArray (N_cells x 2, float64) -- centroid X, Y in pixels
  cell_type_labels: list[int] -- categorical index per cell (>=0; -1 allowed
                    and treated as its own "unassigned" type)
  class_names: list[str] -- display name per cell-type index
  k_neighbors: int -- window size (number of nearest neighbors, includes self)
  n_neighborhoods: int -- number of CN clusters for k-means
  seed: int (optional) -- random seed for k-means reproducibility (default 0)
  generate_heatmap: bool (optional) -- render a CN x cell-type enrichment heatmap
  output_dir: str (optional) -- directory to write the PNG(s) into
  image_ids: list[int] (optional) -- per-cell source-image index (0..n_images-1);
             enables per-image windowing + per-sample proportion outputs
  image_names: list[str] (optional) -- display name per image index
  group_labels: list[int] (optional) -- per-IMAGE group index (0..n_groups-1)
             for condition/treatment comparison (work "B")
  group_names: list[str] (optional) -- display name per group index
  window_mode: str (optional) -- "knn" (default) or "radius"
  radius_microns: float (optional) -- window radius in microns (radius mode)
  pixel_sizes_um: list[float] (optional) -- per-image um/px for radius->pixels

Outputs (via task.outputs):
  neighborhood_labels: NDArray (N_cells,) int32 -- CN id per cell (0..N-1)
  n_neighborhoods: int -- actual number of neighborhoods produced
  neighborhood_counts: str (JSON) -- {cn_id: n_cells}
  composition_json: str (JSON) -- {class_names, mean_composition[CN][class]}
  enrichment_json: str (JSON) -- {class_names, log2_enrichment[CN][class]}
  heatmap_path: str -- path to the CN x cell-type enrichment heatmap PNG, or ""
  per_sample_proportions_json: str (JSON) -- {image_names, n_neighborhoods,
                    proportions[image][cn], counts[image][cn]} or "" if single-image
  per_sample_heatmap_path: str -- image x CN proportion heatmap PNG, or ""
  group_proportions_json: str (JSON) -- {group_names, proportions[group][cn]} or ""
  group_heatmap_path: str -- group x CN proportion heatmap PNG, or ""
  region_adjacency_json: str (JSON) -- {n_cn, matrix[CN][CN]} row-normalized
  region_adjacency_heatmap_path: str -- CN x CN adjacency heatmap PNG, or ""
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

# Optional multi-image (joint cohort) inputs.
try:
    image_ids_arr = np.asarray(image_ids, dtype=np.int64)
    if image_ids_arr.shape[0] != n_cells:
        logger.warning("image_ids length (%d) != n_cells (%d); ignoring",
                       image_ids_arr.shape[0], n_cells)
        image_ids_arr = None
except NameError:
    image_ids_arr = None
try:
    img_names = list(image_names)
except NameError:
    img_names = None
try:
    group_labels_arr = np.asarray(group_labels, dtype=np.int64)
except NameError:
    group_labels_arr = None
try:
    grp_names = list(group_names)
except NameError:
    grp_names = None

# Window definition: "knn" (default) or "radius" (physical microns). For radius
# mode, pixel_sizes_um gives the per-image um/px so the radius converts to pixels
# per block (pixel size may differ across images).
try:
    win_mode = str(window_mode)
except NameError:
    win_mode = "knn"
try:
    radius_um = float(radius_microns)
except NameError:
    radius_um = 0.0
try:
    pixel_sizes = [float(v) for v in pixel_sizes_um]
except NameError:
    pixel_sizes = None


def _radius_px_for(image_index):
    """Convert the radius (microns) to pixels for one image; falls back to
    treating the radius as pixels when the image is uncalibrated."""
    ps = 1.0
    if pixel_sizes is not None and 0 <= image_index < len(pixel_sizes):
        ps = pixel_sizes[image_index]
    if ps is None or ps <= 0:
        ps = 1.0
    return radius_um / ps

# n_cn cannot exceed the number of cells; k handled per-image below.
n_cn = max(1, min(n_cn, n_cells))

# Distinct source images (joint mode requires >1 to mean anything).
if image_ids_arr is not None:
    unique_images = np.unique(image_ids_arr)
    multi_image = unique_images.shape[0] > 1
else:
    unique_images = np.array([0])
    multi_image = False
logger.info("k_neighbors=%d, n_neighborhoods=%d, n_classes=%d, n_images=%d, multi_image=%s",
            k, n_cn, n_classes, unique_images.shape[0], multi_image)

from sklearn.neighbors import NearestNeighbors


def _composition_for_block(block_coords, block_labels, k_req, radius_px=None):
    """Per-cell cell-type composition within a single image block. With
    radius_px set, the window is every cell within that radius (CytoMAP-style
    physical neighborhood); otherwise it is the k nearest neighbors. n_jobs=1
    keeps this single-process: Appose runs the task in a worker subprocess where
    joblib/loky fan-out has deadlocked, and the tree query is fast enough that
    parallelism buys little."""
    n_block = block_coords.shape[0]
    comp = np.zeros((n_block, n_classes), dtype=np.float64)
    if radius_px is not None and radius_px > 0:
        nn = NearestNeighbors(radius=radius_px, algorithm="auto", n_jobs=1)
        nn.fit(block_coords)
        neigh = nn.radius_neighbors(block_coords, return_distance=False)
        for i in range(n_block):
            idxs = neigh[i]
            if idxs.size == 0:
                comp[i, block_labels[i]] = 1.0   # isolated cell -> self-only window
                continue
            cc = np.bincount(block_labels[idxs], minlength=n_classes).astype(np.float64)
            comp[i, :] = cc / float(idxs.size)
        return comp
    k_local = max(1, min(k_req, n_block))
    nn = NearestNeighbors(n_neighbors=k_local, algorithm="auto", n_jobs=1)
    nn.fit(block_coords)
    _, nbr_idx = nn.kneighbors(block_coords)  # (n_block, k_local), includes self
    neighbor_labels = block_labels[nbr_idx]   # (n_block, k_local)
    for c in range(n_classes):
        comp[:, c] = np.count_nonzero(neighbor_labels == c, axis=1) / float(k_local)
    return comp


# 2 + 3. Build spatial windows and per-cell composition vectors. In joint mode
# we window WITHIN each image (cells in different slides are not neighbors);
# in single-image mode this is one block over all cells.
radius_mode = (win_mode == "radius") and radius_um > 0
composition = np.zeros((n_cells, n_classes), dtype=np.float64)
if multi_image:
    _update("Building per-image spatial windows (%s) across %d images..."
            % (("radius=%.1fum" % radius_um) if radius_mode else ("k=%d" % k),
               unique_images.shape[0]))
    for img in unique_images:
        sel = np.flatnonzero(image_ids_arr == img)
        if sel.size == 0:
            continue
        r_px = _radius_px_for(int(img)) if radius_mode else None
        composition[sel, :] = _composition_for_block(
            coords[sel, :], labels_arr[sel], k, radius_px=r_px)
else:
    if radius_mode:
        _update("Building spatial windows (radius=%.1f um)..." % radius_um)
        r_px = _radius_px_for(0)
    else:
        _update("Building spatial windows (k=%d nearest neighbors)..." % k)
        r_px = None
    composition[:, :] = _composition_for_block(coords, labels_arr, k, radius_px=r_px)

# 4. Cluster the POOLED composition vectors into N cellular neighborhoods.
#    One global k-means is what makes a CN id mean the same mixture in every
#    image, so per-sample proportions are comparable.
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


def _save_heatmap(matrix, row_labels, col_labels, title, cbar_label,
                  cmap, vmin, vmax, fname):
    """Write a labelled heatmap PNG; returns the path or "" on failure."""
    try:
        os.makedirs(out_dir, exist_ok=True)
        import matplotlib
        matplotlib.use("Agg")  # belt-and-braces; init already sets this
        import matplotlib.pyplot as plt

        n_rows = matrix.shape[0]
        n_cols = matrix.shape[1]
        fig_w = max(6.0, 0.6 * n_cols + 2.0)
        fig_h = max(4.0, 0.5 * n_rows + 1.5)
        fig, ax = plt.subplots(figsize=(fig_w, fig_h))
        im = ax.imshow(matrix, aspect="auto", cmap=cmap, vmin=vmin, vmax=vmax)
        ax.set_xticks(range(n_cols))
        ax.set_xticklabels(col_labels, rotation=45, ha="right", fontsize=8)
        ax.set_yticks(range(n_rows))
        ax.set_yticklabels(row_labels, fontsize=8)
        ax.set_title(title)
        cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
        cbar.set_label(cbar_label)
        fig.tight_layout()
        path = os.path.join(out_dir, fname)
        fig.savefig(path, dpi=150, bbox_inches="tight")
        plt.close("all")
        logger.info("Saved heatmap: %s", path)
        return path
    except Exception as e:
        logger.warning("Failed to render heatmap %s: %s", fname, e)
        return ""


# 6. CN x cell-type enrichment heatmap (rows = neighborhoods, cols = cell types).
heatmap_path = ""
if want_heatmap and out_dir:
    _update("Rendering enrichment heatmap...")
    vmax = float(np.nanmax(np.abs(enrichment))) if enrichment.size else 1.0
    if not np.isfinite(vmax) or vmax <= 0:
        vmax = 1.0
    cn_row_labels = ["CN %d (n=%d)" % (cn, counts[str(cn)]) for cn in range(n_cn)]
    heatmap_path = _save_heatmap(
        enrichment, cn_row_labels, names,
        "Cellular-neighborhood enrichment (log2 vs overall)",
        "log2 fold enrichment", "RdBu_r", -vmax, vmax, "cn_enrichment.png")

# 7. Multi-image (cohort) summaries: per-sample CN proportions + per-group means.
per_sample_json = ""
per_sample_heatmap_path = ""
group_json = ""
group_heatmap_path = ""
if multi_image:
    _update("Summarizing per-sample neighborhood proportions...")
    n_images = unique_images.shape[0]
    # Map the (possibly sparse) image ids to 0..n_images-1 row order.
    img_order = list(unique_images)
    sample_counts = np.zeros((n_images, n_cn), dtype=np.int64)
    for r, img in enumerate(img_order):
        sel = image_ids_arr == img
        cn_for_img = cn_labels[sel]
        for cn in range(n_cn):
            sample_counts[r, cn] = int(np.count_nonzero(cn_for_img == cn))
    row_totals = sample_counts.sum(axis=1, keepdims=True).astype(np.float64)
    row_totals[row_totals == 0] = 1.0
    sample_props = sample_counts / row_totals

    if img_names is not None and len(img_names) >= int(max(img_order)) + 1:
        sample_labels = [str(img_names[int(i)]) for i in img_order]
    else:
        sample_labels = ["image %d" % int(i) for i in img_order]

    per_sample_json = json.dumps({
        "image_names": sample_labels,
        "n_neighborhoods": n_cn,
        "proportions": sample_props.tolist(),
        "counts": sample_counts.tolist(),
    })

    if want_heatmap and out_dir:
        per_sample_heatmap_path = _save_heatmap(
            sample_props, sample_labels,
            ["CN %d" % cn for cn in range(n_cn)],
            "Per-sample neighborhood proportions",
            "fraction of image's cells", "viridis", 0.0,
            float(sample_props.max()) if sample_props.size else 1.0,
            "cn_per_sample_proportions.png")

    # Work "B": per-group mean proportions for condition/treatment comparison.
    if group_labels_arr is not None and group_labels_arr.shape[0] >= n_images:
        _update("Summarizing per-group neighborhood proportions...")
        # group_labels is per IMAGE index, aligned to img_order rows.
        grp_for_row = np.array([int(group_labels_arr[int(i)]) for i in img_order],
                               dtype=np.int64)
        unique_groups = np.unique(grp_for_row[grp_for_row >= 0])
        if unique_groups.size >= 1:
            grp_props = np.zeros((unique_groups.shape[0], n_cn), dtype=np.float64)
            grp_labels_out = []
            for gi, g in enumerate(unique_groups):
                member_rows = np.flatnonzero(grp_for_row == g)
                # Pool cells across the group's images (weights large samples
                # more, matching how cohort CN frequencies are usually compared).
                pooled = sample_counts[member_rows, :].sum(axis=0).astype(np.float64)
                tot = max(1.0, pooled.sum())
                grp_props[gi, :] = pooled / tot
                if grp_names is not None and int(g) < len(grp_names):
                    grp_labels_out.append("%s (n=%d)" % (str(grp_names[int(g)]),
                                                          member_rows.size))
                else:
                    grp_labels_out.append("group %d (n=%d)" % (int(g), member_rows.size))
            group_json = json.dumps({
                "group_names": grp_labels_out,
                "n_neighborhoods": n_cn,
                "proportions": grp_props.tolist(),
            })
            if want_heatmap and out_dir:
                group_heatmap_path = _save_heatmap(
                    grp_props, grp_labels_out,
                    ["CN %d" % cn for cn in range(n_cn)],
                    "Per-group neighborhood proportions",
                    "fraction of group's cells", "viridis", 0.0,
                    float(grp_props.max()) if grp_props.size else 1.0,
                    "cn_per_group_proportions.png")

# 7b. Region adjacency: how often neighborhoods border each other. For each
# within-image spatial edge whose endpoints sit in different neighborhoods, tally
# the CN-CN pair; sum across images; row-normalize so row a is the distribution
# of region a's neighboring regions (diagonal = within-region edges).
region_adjacency_json = ""
region_adjacency_heatmap_path = ""


def _region_adjacency():
    adj = np.zeros((n_cn, n_cn), dtype=np.float64)
    imgs = unique_images if multi_image else np.array([0])
    for img in imgs:
        sel = (np.flatnonzero(image_ids_arr == img) if multi_image
               else np.arange(n_cells))
        if sel.size < 2:
            continue
        c = coords[sel]
        lab = cn_labels[sel]
        if radius_mode:
            r_px = _radius_px_for(int(img))
            nn = NearestNeighbors(radius=r_px, n_jobs=1)
            nn.fit(c)
            neigh = nn.radius_neighbors(c, return_distance=False)
            for i in range(sel.size):
                for j in neigh[i]:
                    if j <= i:
                        continue
                    a, b = int(lab[i]), int(lab[j])
                    adj[a, b] += 1.0
                    adj[b, a] += 1.0
        else:
            k_local = max(2, min(k + 1, sel.size))
            nn = NearestNeighbors(n_neighbors=k_local, n_jobs=1)
            nn.fit(c)
            _, idx = nn.kneighbors(c)
            for i in range(sel.size):
                for jj in range(1, idx.shape[1]):
                    j = int(idx[i, jj])
                    if j <= i:
                        continue
                    a, b = int(lab[i]), int(lab[j])
                    adj[a, b] += 1.0
                    adj[b, a] += 1.0
    return adj


try:
    _update("Computing region adjacency...")
    adj = _region_adjacency()
    row_sums = adj.sum(axis=1, keepdims=True)
    row_sums[row_sums == 0] = 1.0
    adj_norm = adj / row_sums
    region_adjacency_json = json.dumps({"n_cn": n_cn, "matrix": adj_norm.tolist()})
    if want_heatmap and out_dir:
        region_adjacency_heatmap_path = _save_heatmap(
            adj_norm, ["CN %d" % cn for cn in range(n_cn)],
            ["CN %d" % cn for cn in range(n_cn)],
            "Region adjacency (fraction of a region's neighbors)",
            "fraction of neighboring edges", "magma", 0.0,
            float(adj_norm.max()) if adj_norm.size else 1.0,
            "cn_region_adjacency.png")
except Exception as e:
    logger.warning("Region adjacency failed: %s", e)

# 8. Package outputs.
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
task.outputs["per_sample_proportions_json"] = per_sample_json
task.outputs["per_sample_heatmap_path"] = per_sample_heatmap_path
task.outputs["group_proportions_json"] = group_json
task.outputs["group_heatmap_path"] = group_heatmap_path
task.outputs["region_adjacency_json"] = region_adjacency_json
task.outputs["region_adjacency_heatmap_path"] = region_adjacency_heatmap_path

logger.info("Cellular-neighborhood results packaged")
