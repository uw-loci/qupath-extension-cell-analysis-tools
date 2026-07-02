"""
Compute per-marker histograms and auto-thresholds for phenotyping gating.

Provides multiple thresholding methods:
- Triangle: Best for skewed distributions with dominant negative peak
- GMM: 2-component Gaussian mixture model, threshold at intersection
- Gamma: Gamma distribution fit for right-skewed positive-only marker data
         (inspired by GammaGateR, Bioinformatics June 2024)

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  normalization: str ("zscore", "minmax", "percentile", "none")
  histogram_bins: int (optional) -- number of histogram bins (default 50)
  min_valid_values: int (optional) -- minimum valid values per marker (default 10)
  gmm_max_iter: int (optional) -- max GMM iterations (default 200)
  gamma_std_multiplier: float (optional) -- gamma threshold std multiplier (default 1.0)

Outputs (via task.outputs):
  histograms_json: str (JSON) -- per-marker histogram and threshold data
"""

import logging
import json
import warnings

logger = logging.getLogger("qpcat.thresholds")

import math

import numpy as np
import pandas as pd

# 1. Load data
task.update("Loading measurements for threshold computation...")
data = measurements.ndarray().copy()
n_cells, n_markers = data.shape
logger.info("Computing thresholds for %d cells x %d markers", n_cells, n_markers)

df = pd.DataFrame(data, columns=marker_names)

# 2. Normalize (same logic as run_phenotyping.py)
task.update("Normalizing measurements...")

if normalization == "zscore":
    std = df.std()
    std[std == 0] = 1
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

# 3. Compute per-marker histograms and thresholds
task.update("Computing histograms and thresholds...")

# Import optional dependencies (available in the pixi env)
try:
    from skimage.filters import threshold_triangle

    has_triangle = True
except ImportError:
    has_triangle = False
    logger.warning("scikit-image not available, triangle thresholding disabled")

try:
    from sklearn.mixture import GaussianMixture

    has_gmm = True
except ImportError:
    has_gmm = False
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
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                t = float(threshold_triangle(valid))
                # Clamp to data range
                t = max(float(bin_edges[0]), min(float(bin_edges[-1]), t))
                thresholds["triangle"] = round(t, 4)
        except Exception as e:
            logger.warning("Triangle threshold failed for '%s': %s", marker, str(e))

    # GMM (2-component Gaussian mixture)
    if has_gmm:
        try:
            gmm = GaussianMixture(
                n_components=2, random_state=42, max_iter=GMM_MAX_ITER
            )
            gmm.fit(valid.reshape(-1, 1))
            means = gmm.means_.flatten()
            stds = np.sqrt(gmm.covariances_.flatten())
            weights = gmm.weights_.flatten()

            # Sort by mean (lower = negative population, higher = positive)
            order = np.argsort(means)
            m1, m2 = means[order]
            s1, s2 = stds[order]
            w1, w2 = weights[order]

            # Threshold at the posterior-equal-probability crossing between the two
            # Gaussians, i.e. solve w1*N(x;m1,s1) == w2*N(x;m2,s2) for x between the
            # means. The mean-midpoint (m1+m2)/2 is only correct for equal variance
            # AND equal weight; for a narrow background peak plus a broad positive
            # tail (the common marker case) it over-gates. Reduces to the midpoint
            # when the two components are symmetric.
            t = None
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
                    t = min(between, key=lambda r: abs(r - mid))
            if t is None:
                # No valid crossing between the means (degenerate fit): fall back.
                t = (m1 + m2) / 2.0
            t = max(float(bin_edges[0]), min(float(bin_edges[-1]), float(t)))
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
    }

    if (i + 1) % 5 == 0:
        task.update("Computing thresholds... (%d/%d markers)" % (i + 1, n_markers))

# 4. Package outputs
task.update("Packaging threshold results...")
task.outputs["histograms_json"] = json.dumps(result)

logger.info("Threshold computation complete for %d markers", len(result))
