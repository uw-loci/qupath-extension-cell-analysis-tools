"""
Compute per-marker histograms and auto-thresholds for phenotyping gating.

Provides multiple thresholding methods:
- Triangle: Best for skewed distributions with dominant negative peak
- GMM: 2-component Gaussian mixture model, threshold at intersection
- Gamma: Gamma distribution fit for right-skewed positive-only marker data
         (inspired by GammaGateR, Bioinformatics June 2024)

Triangle and GMM are fitted to the values between any point mass at either
end of the range (a floor of identical values, e.g. cells with no signal, or
values clipped by percentile scaling); see interior_values().

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  normalization: str ("zscore", "minmax", "percentile", "none")
  histogram_bins: int (optional) -- number of histogram bins (default 50)
  min_valid_values: int (optional) -- minimum valid values per marker (default 10)
  gmm_max_iter: int (optional) -- max GMM iterations (default 200)
  gamma_std_multiplier: float (optional) -- gamma threshold std multiplier (default 1.0)

Outputs (via task.outputs):
  histograms_json: str (JSON) -- per-marker histogram, threshold data and
    "quantiles" (QUANTILE_COUNT values at evenly spaced probabilities 0..1)
    from which the dialog computes the fraction of cells a threshold calls
    positive; "scaling" ({offset, scale, low, high}, the normalization as
    numbers, so the dialog can judge a cell as the run will); "gmm" (the two
    fitted components, lower mean first, when the fit succeeded)
"""

import importlib.util
import logging
import json
import warnings

logger = logging.getLogger("qpcat.thresholds")

import math

import numpy as np
import pandas as pd

# Probabilities 0, 0.001, ..., 1: the dialog's positive fraction is exact to
# 0.1% of cells.
QUANTILE_COUNT = 1001


def interior_values(values, min_mass=0.005, min_keep=10):
    """Drop a point mass of identical values at either end of the range.

    A floor of exact zeros (cells with no signal) or the values clipped to p1 /
    p99 by percentile scaling are not part of any Gaussian or of a histogram
    slope; fitted with the rest, they pull GMM and Triangle onto the spike.
    An end is dropped when at least 2 values and ``min_mass`` of all values
    sit exactly on it. Returns ``values`` unchanged when fewer than
    ``min_keep`` would remain.
    """
    keep = np.ones(len(values), dtype=bool)
    for end in (values.min(), values.max()):
        at_end = values == end
        if at_end.sum() >= 2 and at_end.mean() >= min_mass:
            keep &= ~at_end
    if keep.sum() < min_keep:
        return values
    return values[keep]


def gmm_crossing(m1, s1, w1, m2, s2, w2):
    """Point between the means where w1*N(x;m1,s1) == w2*N(x;m2,s2).

    Falls back to the mean midpoint when the curves do not cross between the
    means (degenerate fit).
    """
    if s1 > 0 and s2 > 0 and w1 > 0 and w2 > 0:
        a = 1.0 / (2.0 * s2 * s2) - 1.0 / (2.0 * s1 * s1)
        b = m1 / (s1 * s1) - m2 / (s2 * s2)
        c = (
            -(m1 * m1) / (2.0 * s1 * s1)
            + (m2 * m2) / (2.0 * s2 * s2)
            + math.log(w1 / s1)
            - math.log(w2 / s2)
        )
        roots = []
        if abs(a) < 1e-12:
            if abs(b) > 1e-12:
                roots = [-c / b]
        else:
            disc = b * b - 4.0 * a * c
            if disc >= 0:
                sq = math.sqrt(disc)
                roots = [(-b + sq) / (2.0 * a), (-b - sq) / (2.0 * a)]
        lo, hi = min(m1, m2), max(m1, m2)
        mid = (m1 + m2) / 2.0
        between = [r for r in roots if lo <= r <= hi]
        if between:
            return min(between, key=lambda r: abs(r - mid))
    return (m1 + m2) / 2.0


def normalization_params(df, normalization):
    """Per-column (offset, scale, low, high) for the chosen normalization.

    A value becomes (clip(value, low, high) - offset) / scale. low and high are
    None unless the method clips. Same statistics as run_phenotyping.py.
    """
    if normalization == "zscore":
        std = df.std()
        std[std == 0] = 1
        return df.mean(), std, None, None
    if normalization == "minmax":
        dmin = df.min()
        drange = df.max() - dmin
        drange[drange == 0] = 1
        return dmin, drange, None, None
    if normalization == "percentile":
        p1 = df.quantile(0.01)
        p99 = df.quantile(0.99)
        drange = p99 - p1
        drange[drange == 0] = 1
        return p1, drange, p1, p99
    zeros = pd.Series(0.0, index=df.columns)
    return zeros, zeros + 1.0, None, None


def gmm_fit(values, max_iter=200):
    """2-component GMM fitted to interior_values(values), lower mean first."""
    from sklearn.mixture import GaussianMixture

    fit_values = interior_values(values)
    gmm = GaussianMixture(n_components=2, random_state=42, max_iter=max_iter)
    gmm.fit(fit_values.reshape(-1, 1))
    means = gmm.means_.flatten()
    stds = np.sqrt(gmm.covariances_.flatten())
    weights = gmm.weights_.flatten()
    order = np.argsort(means)
    return {
        "means": [float(m) for m in means[order]],
        "stds": [float(s) for s in stds[order]],
        "weights": [float(w) for w in weights[order]],
        "n_fit": int(len(fit_values)),
    }


def gmm_threshold(fit):
    """Where the two components of a gmm_fit() result cross."""
    m1, m2 = fit["means"]
    s1, s2 = fit["stds"]
    w1, w2 = fit["weights"]
    return float(gmm_crossing(m1, s1, w1, m2, s2, w2))


def triangle_threshold(values):
    """Triangle threshold (skimage), fitted to interior_values(values)."""
    from skimage.filters import threshold_triangle

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        return float(threshold_triangle(interior_values(values)))


# 1. Load data
task.update("Loading measurements for threshold computation...")
# The Java side ships float32 when the values round-trip exactly (QuPath
# stores detection measurements as float32 anyway), so widen to float64
# HERE before any arithmetic. Doing the maths in float32 would change
# results -- normalization and the distribution fits below are sensitive
# to it -- which is exactly the regression this upcast exists to prevent.
data = np.array(measurements.ndarray(), dtype=np.float64, copy=True)
n_cells, n_markers = data.shape
logger.info("Computing thresholds for %d cells x %d markers", n_cells, n_markers)

df = pd.DataFrame(data, columns=marker_names)

# 2. Normalize (same logic as run_phenotyping.py)
task.update("Normalizing measurements...")

norm_offset, norm_scale, clip_low, clip_high = normalization_params(df, normalization)
df_clipped = (
    df if clip_low is None else df.clip(lower=clip_low, upper=clip_high, axis=1)
)
df_norm = (df_clipped - norm_offset) / norm_scale

# 3. Compute per-marker histograms and thresholds
task.update("Computing histograms and thresholds...")

# Import optional dependencies (available in the pixi env)
has_triangle = importlib.util.find_spec("skimage") is not None
if not has_triangle:
    logger.warning("scikit-image not available, triangle thresholding disabled")

has_gmm = importlib.util.find_spec("sklearn") is not None
if not has_gmm:
    logger.warning("scikit-learn not available, GMM thresholding disabled")

try:
    from scipy.stats import gamma as gamma_dist

    has_gamma = True
except ImportError:
    has_gamma = False
    logger.warning("scipy not available, gamma thresholding disabled")

# Read preferences (injected by Appose, fall back to defaults)
try:
    N_BINS = int(histogram_bins)
except NameError:
    N_BINS = 50

try:
    MIN_VALID = int(min_valid_values)
except NameError:
    MIN_VALID = 10

try:
    GMM_MAX_ITER = int(gmm_max_iter)
except NameError:
    GMM_MAX_ITER = 200

try:
    GAMMA_STD_MULT = float(gamma_std_multiplier)
except NameError:
    GAMMA_STD_MULT = 1.0

result = {}

for i, marker in enumerate(list(marker_names)):
    values = df_norm.iloc[:, i].values
    valid = values[np.isfinite(values)]

    if len(valid) < MIN_VALID:
        logger.warning(
            "Marker '%s' has too few valid values (%d), skipping", marker, len(valid)
        )
        continue

    # Histogram
    counts, bin_edges = np.histogram(valid, bins=N_BINS)
    thresholds = {}

    # Triangle method (skimage)
    if has_triangle:
        try:
            t = triangle_threshold(valid)
            t = max(float(bin_edges[0]), min(float(bin_edges[-1]), t))
            thresholds["triangle"] = round(t, 4)
        except Exception as e:
            logger.warning("Triangle threshold failed for '%s': %s", marker, str(e))

    # GMM (2-component Gaussian mixture); the threshold is the posterior-equal
    # crossing between the two means, not the mean midpoint.
    fit = None
    if has_gmm:
        try:
            fit = gmm_fit(valid, max_iter=GMM_MAX_ITER)
            t = gmm_threshold(fit)
            t = max(float(bin_edges[0]), min(float(bin_edges[-1]), t))
            thresholds["gmm"] = round(t, 4)
        except Exception as e:
            logger.warning("GMM threshold failed for '%s': %s", marker, str(e))

    # Gamma mixture (GammaGateR-inspired)
    if has_gamma:
        try:
            # Shift values to be strictly positive for gamma fitting
            shift = 0.0
            if valid.min() <= 0:
                shift = abs(valid.min()) + 0.001
            pos_values = valid + shift

            # Fit gamma distribution
            alpha, loc, scale = gamma_dist.fit(pos_values, floc=0)
            mode = max(0, (alpha - 1) * scale) if alpha > 1 else 0
            std_val = np.sqrt(alpha) * scale

            # Threshold at mode + GAMMA_STD_MULT*std (separates background from positive)
            t = float(mode + GAMMA_STD_MULT * std_val - shift)
            t = max(float(bin_edges[0]), min(float(bin_edges[-1]), t))
            thresholds["gamma"] = round(t, 4)
        except Exception as e:
            logger.warning("Gamma threshold failed for '%s': %s", marker, str(e))

    result[marker] = {
        "counts": [int(c) for c in counts],
        "bin_edges": [round(float(e), 6) for e in bin_edges],
        "thresholds": thresholds,
        "quantiles": [
            float(q) for q in np.quantile(valid, np.linspace(0.0, 1.0, QUANTILE_COUNT))
        ],
        "scaling": {
            "offset": float(norm_offset.iloc[i]),
            "scale": float(norm_scale.iloc[i]),
            "low": None if clip_low is None else float(clip_low.iloc[i]),
            "high": None if clip_high is None else float(clip_high.iloc[i]),
        },
    }
    if fit is not None:
        result[marker]["gmm"] = fit

    if (i + 1) % 5 == 0:
        task.update("Computing thresholds... (%d/%d markers)" % (i + 1, n_markers))

# 4. Package outputs
task.update("Packaging threshold results...")
task.outputs["histograms_json"] = json.dumps(result)

logger.info("Threshold computation complete for %d markers", len(result))
