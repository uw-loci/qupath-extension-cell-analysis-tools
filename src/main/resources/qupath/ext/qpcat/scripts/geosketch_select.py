"""
Geometric-sketching cell selection for the VEST 3D export ("Representative sketch" mode).

Density-aware subsampling: densely populated regions of feature space are sampled more
aggressively while sparse regions are retained, which increases the relative
representation of rare populations compared with uniform-random sampling. A per-class
floor is enforced on top (via top-up sketching) so no cluster is squeezed out by class
imbalance.

Algorithm (gs / gs_gap) VENDORED from geosketch, MIT License, Copyright (c) 2018 brianhie
  https://github.com/brianhie/geosketch
  Hie B, Cho H, DeMeo B, Bryson B, Berger B. "Geometric sketching compactly summarizes
  the single-cell transcriptomic landscape." Cell Systems 8(6):483-493.e7 (2019).
  https://doi.org/10.1016/j.cels.2019.05.003
Vendored (rather than pip-installed) so the mode adds no new environment dependency --
gs_gap needs only numpy. The gs_gap body below is reproduced faithfully; only the
verbose logging hook was adapted to this module's logger.

Inputs (injected by Appose):
  measurements: NDArray (N_cells x N_markers, float64) -- ALL clustered cells
  marker_names: list[str]
  cluster_labels: list[int] -- cluster id per cell (for the per-class floor)
  normalization: str ("zscore", "minmax", "percentile", "none")
  global_cap: int -- target sketch size across all cells
  min_per_class: int -- per-cluster floor (honored where that many cells exist)
  seed: int (default 42)

Optional inputs:
  percentile_low / percentile_high: float -- percentile-normalization clip bounds

Outputs (via task.outputs):
  selected_indices: NDArray (K,) int32 -- row indices into `measurements` to keep
"""

import logging
import sys

logger = logging.getLogger("qpcat.geosketch")

import numpy as np
import pandas as pd
from appose import NDArray as PyNDArray


# ---------------------------------------------------------------------------
# VENDORED from geosketch (MIT, (c) 2018 brianhie). gs_gap reproduced faithfully;
# the only change is routing the optional `verbose` logging through this logger.
# ---------------------------------------------------------------------------
def _gs(X, N, **kwargs):
    """Geometric sketching. Wrapper around gs_gap()."""
    return _gs_gap(X, N, **kwargs)


def _gs_gap(
    X,
    N,
    k="auto",
    seed=None,
    replace=False,
    alpha=0.1,
    max_iter=200,
    one_indexed=False,
    verbose=0,
):
    """Sample from a data set according to a geometric plaid covering.

    X : numpy.ndarray of low-dimensional embeddings (rows = observations).
    N : desired sketch size. Returns a sorted list of indices into X.
    """
    n_samples, n_features = X.shape

    if seed is not None:
        np.random.seed(seed)
    if not replace and N > n_samples:
        raise ValueError(
            "Cannot sample {} elements from {} elements "
            "without replacement".format(N, n_samples)
        )
    if not replace and N == n_samples:
        if one_indexed:
            return list(np.array(range(N)) + 1)
        else:
            return list(range(N))
    if k == "auto":
        if replace:
            k = int(np.sqrt(n_samples))
        else:
            k = N
    if k < 1:
        raise ValueError("Cannot draw {} covering boxes.".format(k))

    # Translate to make data all positive.
    X = X - X.min(0)
    # Scale so that maximum value equals 1.
    X /= X.max()
    # Find max value along each dimension.
    X_ptp = np.ptp(X, 0)

    low_unit, high_unit = 0.0, max(X_ptp)
    unit = (low_unit + high_unit) / 4.0

    d_to_argsort = {}
    n_iter = 0
    while True:
        grid_table = np.zeros((n_samples, n_features))
        for d in range(n_features):
            if X_ptp[d] <= unit:
                continue
            points_d = X[:, d]
            if d not in d_to_argsort:
                d_to_argsort[d] = np.argsort(points_d)
            curr_start = None
            curr_interval = -1
            for sample_idx in d_to_argsort[d]:
                if curr_start is None or curr_start + unit < points_d[sample_idx]:
                    curr_start = points_d[sample_idx]
                    curr_interval += 1
                grid_table[sample_idx, d] = curr_interval

        grid = {}
        for sample_idx in range(n_samples):
            grid_cell = tuple(grid_table[sample_idx, :])
            if grid_cell not in grid:
                grid[grid_cell] = []
            grid[grid_cell].append(sample_idx)
        del grid_table

        if verbose:
            logger.debug("Found %d non-empty grid cells", len(grid))

        if len(grid) > k * (1 + alpha):
            low_unit = unit
            if high_unit is None:
                unit *= 2.0
            else:
                unit = (unit + high_unit) / 2.0
        elif len(grid) < k * (1 - alpha):
            high_unit = unit
            if low_unit is None:
                unit /= 2.0
            else:
                unit = (unit + low_unit) / 2.0
        else:
            break

        if (
            high_unit is not None
            and low_unit is not None
            and high_unit - low_unit < 1e-20
        ):
            break

        n_iter += 1
        if n_iter >= max_iter:
            sys.stderr.write(
                "WARNING: Max iterations reached, try increasing alpha parameter.\n"
            )
            break

    # Sample grid cell, then sample point within cell.
    valid_grids = set()
    gs_idx = []
    for n in range(N):
        if len(valid_grids) == 0:
            valid_grids = set(grid.keys())
        valid_grids_list = list(valid_grids)
        grid_cell = valid_grids_list[np.random.choice(len(valid_grids))]
        valid_grids.remove(grid_cell)
        sample = np.random.choice(list(grid[grid_cell]))
        if not replace:
            grid[grid_cell].remove(sample)
            if len(grid[grid_cell]) == 0:
                del grid[grid_cell]
        gs_idx.append(sample)

    if one_indexed:
        gs_idx = [idx + 1 for idx in gs_idx]

    return sorted(gs_idx)


# ---------------------------------------------------------------------------
# Task body
# ---------------------------------------------------------------------------
data = measurements.ndarray().copy()
n_cells, n_markers = data.shape
# cluster_labels arrives as an int32 NDArray (shared memory); tolerate a plain list too.
try:
    labels = np.asarray(cluster_labels.ndarray(), dtype=np.int64).copy()
except AttributeError:
    labels = np.asarray(list(cluster_labels), dtype=np.int64)
logger.info("Geosketch select: %d cells x %d markers", n_cells, n_markers)

df = pd.DataFrame(data, columns=list(marker_names))
n_nonfinite = int(np.count_nonzero(~np.isfinite(df.to_numpy(dtype=float))))
if n_nonfinite > 0:
    df = df.replace([np.inf, -np.inf], np.nan)
    df = df.fillna(df.median(numeric_only=True).fillna(0.0)).fillna(0.0)

try:
    norm = normalization
except NameError:
    norm = "zscore"
try:
    cap = int(global_cap)
except NameError:
    cap = 1000
try:
    floor = int(min_per_class)
except NameError:
    floor = 30
try:
    the_seed = int(seed)
except NameError:
    the_seed = 42
try:
    pct_low = float(percentile_low)
except NameError:
    pct_low = 0.01
try:
    pct_high = float(percentile_high)
except NameError:
    pct_high = 0.99

# Normalize the geosketch space so no single marker dominates the covering grid
# (same rules as embed_3d.py / run_clustering.py).
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

X = np.ascontiguousarray(df_norm.to_numpy(dtype=np.float64))

cap = max(1, min(cap, n_cells))


def _sketch(mat, n, seed):
    """geosketch that tolerates n >= rows and tiny inputs."""
    rows = mat.shape[0]
    n = int(min(n, rows))
    if n <= 0:
        return []
    if n >= rows:
        return list(range(rows))
    return _gs(mat.astype(np.float64, copy=True), n, seed=seed, replace=False)


# 1. Global density-aware sketch to the budget.
selected = set(_sketch(X, cap, the_seed))

# 2. Per-class floor top-up: any cluster below min(floor, size) gets extra cells drawn
#    (again by geosketch) from its not-yet-selected members. Keeps rare clusters visible.
floor = max(0, floor)
if floor > 0:
    for c in np.unique(labels):
        idx_c = np.where(labels == c)[0]
        have = sum(1 for i in idx_c if i in selected)
        need = min(floor, len(idx_c)) - have
        if need <= 0:
            continue
        remaining = np.array([i for i in idx_c if i not in selected], dtype=np.int64)
        if remaining.size == 0:
            continue
        pick_local = _sketch(X[remaining], need, the_seed + int(c) + 1)
        for j in pick_local:
            selected.add(int(remaining[j]))

sel = np.array(sorted(selected), dtype=np.int32)
logger.info(
    "Geosketch select: kept %d of %d cells (cap=%d, floor=%d)",
    sel.size,
    n_cells,
    cap,
    floor,
)

out = PyNDArray(dtype="int32", shape=[int(sel.size)])
np.copyto(out.ndarray(), sel)
task.outputs["selected_indices"] = out
