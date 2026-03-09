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

Outputs (via task.outputs):
  histograms_json: str (JSON) -- per-marker histogram and threshold data
"""
import logging
import json
import warnings

logger = logging.getLogger("pyclustering.thresholds")

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

N_BINS = 50
result = {}

for i, marker in enumerate(list(marker_names)):
    values = df_norm.iloc[:, i].values
    valid = values[np.isfinite(values)]

    if len(valid) < 10:
        logger.warning("Marker '%s' has too few valid values (%d), skipping", marker, len(valid))
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
            gmm = GaussianMixture(n_components=2, random_state=42, max_iter=200)
            gmm.fit(valid.reshape(-1, 1))
            means = gmm.means_.flatten()
            stds = np.sqrt(gmm.covariances_.flatten())
            weights = gmm.weights_.flatten()

            # Sort by mean (lower = negative population, higher = positive)
            order = np.argsort(means)
            m1, m2 = means[order]
            s1, s2 = stds[order]

            # Threshold at midpoint between the two means
            t = float((m1 + m2) / 2)
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

            # Threshold at mode + 1*std (separates background from positive)
            t = float(mode + std_val - shift)
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
task.outputs['histograms_json'] = json.dumps(result)

logger.info("Threshold computation complete for %d markers", len(result))
