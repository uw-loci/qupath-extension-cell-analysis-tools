"""
Compute per-marker histograms and auto-thresholds for phenotyping gating.

Provides multiple thresholding methods:
- Triangle: Best for skewed distributions with dominant negative peak
- GMM: 2-component Gaussian mixture model, threshold at intersection; 1, 2
       and 3 components are compared by BIC and a note says when 2 is not
       the best fit (one population, or three)
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
    fitted components, lower mean first, when the fit succeeded, with
    "bic_components", the component count BIC prefers, "means3" when that
    is 3, and "note" when it is not 2)
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

# The 1/2/3-component BIC comparison fits at most this many values. Two more
# fits (1 and 3 components) on every value would triple the GMM cost on a
# million-cell run; on a 50,000-value sample all three fits take about 0.2 s
# in total, and the component count they pick does not change with more values.
BIC_SAMPLE = 50000
# 1 or 3 components are reported over 2 only when their BIC is lower by at
# least this much. A BIC difference approximates twice the log Bayes factor,
# and 6 or more is "strong" evidence (Kass and Raftery 1995); a smaller
# difference leaves 2, the count the gate is built on, unchallenged.
BIC_MARGIN = 6.0


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


def gmm_fit(values, max_iter=200):
    """2-component GMM fitted to interior_values(values), lower mean first.

    Also fits 1 and 3 components (same seed and initialization) to at most
    BIC_SAMPLE of the same values and compares the three by BIC:
    "bic_components" is the count BIC prefers (2 unless 1 or 3 wins by
    BIC_MARGIN), "means3" the sorted means of the 3-component fit when it
    wins. The gate stays on the 2-component fit either way.
    """
    from sklearn.mixture import GaussianMixture

    fit_values = interior_values(values)
    column = fit_values.reshape(-1, 1)
    gmm = GaussianMixture(n_components=2, random_state=42, max_iter=max_iter)
    gmm.fit(column)
    means = gmm.means_.flatten()
    stds = np.sqrt(gmm.covariances_.flatten())
    weights = gmm.weights_.flatten()
    order = np.argsort(means)
    fit = {
        "means": [float(m) for m in means[order]],
        "stds": [float(s) for s in stds[order]],
        "weights": [float(w) for w in weights[order]],
        "n_fit": int(len(fit_values)),
        "n_values": int(len(values)),
    }

    # Subsample for the comparison (see BIC_SAMPLE); the 2-component fit is
    # reused when every value is in it, so this costs two extra fits then.
    # The comparison is advisory: if a 1- or 3-component fit cannot be made
    # (a few near-identical values give an ill-defined covariance), the gate
    # above stands and the count stays at 2, as before the comparison existed.
    fit["bic_components"] = 2
    try:
        sample = column
        if len(column) > BIC_SAMPLE:
            picked = np.random.default_rng(42).choice(
                fit_values, BIC_SAMPLE, replace=False
            )
            sample = picked.reshape(-1, 1)
        bic = {}
        means3 = None
        for k in (1, 2, 3):
            if k == 2 and sample is column:
                model = gmm
            else:
                model = GaussianMixture(
                    n_components=k, random_state=42, max_iter=max_iter
                )
                model.fit(sample)
            bic[k] = float(model.bic(sample))
            if k == 3:
                means3 = sorted(float(m) for m in model.means_.flatten())
        better = [k for k in (1, 3) if bic[k] <= bic[2] - BIC_MARGIN]
        best = min(better, key=bic.get) if better else 2
        fit["bic_components"] = best
        if best == 3:
            fit["means3"] = means3
    except Exception as e:  # advisory only; never costs the gate
        logger.warning("GMM component comparison skipped: %s", e)
    return fit


def gmm_threshold(fit):
    """Where the two components of a gmm_fit() result cross."""
    m1, m2 = fit["means"]
    s1, s2 = fit["stds"]
    w1, w2 = fit["weights"]
    return float(gmm_crossing(m1, s1, w1, m2, s2, w2))


def gmm_note(fit, threshold):
    """What the BIC comparison says against the 2-component gate, or None.

    fit is a gmm_fit() result and threshold its gmm_threshold(). One
    population: the gate splits a single distribution. Three: which pair of
    groups the gate separates, with the three means.
    """
    k = fit.get("bic_components", 2)
    if k == 1:
        if fit["n_fit"] < fit["n_values"]:
            return (
                "one population by BIC among the %d cells outside the spike - "
                "the gate splits a single distribution; those cells may all be "
                "positive, or all negative." % fit["n_fit"]
            )
        return (
            "one population by BIC - the gate splits a single distribution; "
            "this marker may have no positive cells."
        )
    if k == 3:
        m1, m2, m3 = fit["means3"]
        means = "means %.3g, %.3g, %.3g" % (m1, m2, m3)
        if threshold < m2:
            return (
                "three populations by BIC (%s) - the gate separates the negative "
                "group from the dim and bright groups; consider a manual threshold "
                "between the dim and bright groups." % means
            )
        return (
            "three populations by BIC (%s) - the gate separates the negative and "
            "dim groups from the bright group; consider a manual threshold between "
            "the negative and dim groups if dim cells count as positive." % means
        )
    return None


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

# 2. Normalize (model_utils.normalization_params, as run_phenotyping.py gates)
task.update("Normalizing measurements...")

norm_offset, norm_scale, clip_low, clip_high = normalization_params(df, normalization)
df_clipped = (
    df if clip_low is None else df.clip(lower=clip_low, upper=clip_high, axis=1)
)
df_norm = (df_clipped - norm_offset) / norm_scale

# 3. Compute per-marker histograms and thresholds
task.update("Computing histograms and thresholds...")

# Import optional dependencies (available in the pixi env). Each load is
# logged on its own line: a Windows worker hung somewhere after the line
# above (issue #24) and the log has to say which step never returned.
logger.info("Loading scikit-image (triangle threshold)")
has_triangle = importlib.util.find_spec("skimage") is not None
if has_triangle:
    import skimage.filters  # noqa: F401 - loaded here so the log shows it; used in triangle_threshold
else:
    logger.warning("scikit-image not available, triangle thresholding disabled")

logger.info("Loading scikit-learn (Gaussian mixture)")
has_gmm = importlib.util.find_spec("sklearn") is not None
if has_gmm:
    import sklearn.mixture  # noqa: F401 - same, used in gmm_fit
else:
    logger.warning("scikit-learn not available, GMM thresholding disabled")

logger.info("Loading scipy.stats (gamma fit)")
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

# One thread for the fits: numba/BLAS fan-out in the worker has deadlocked on
# Windows (issue #24); the limit is lifted again when the loop ends.
with single_threaded():  # noqa: F821 - loaded from model_utils at worker init
    for i, marker in enumerate(list(marker_names)):
        values = df_norm.iloc[:, i].values
        valid = values[np.isfinite(values)]

        if len(valid) < MIN_VALID:
            logger.warning(
                "Marker '%s' has too few valid values (%d), skipping",
                marker,
                len(valid),
            )
            continue

        # Histogram
        counts, bin_edges = np.histogram(valid, bins=N_BINS)
        thresholds = {}

        # Triangle method (skimage)
        if has_triangle:
            logger.info("Marker %d/%d '%s': triangle", i + 1, n_markers, marker)
            try:
                t = triangle_threshold(valid)
                t = max(float(bin_edges[0]), min(float(bin_edges[-1]), t))
                thresholds["triangle"] = round(t, 4)
            except Exception as e:
                logger.warning("Triangle threshold failed for '%s': %s", marker, str(e))

        # GMM (2-component Gaussian mixture); the threshold is the posterior-equal
        # crossing between the two means, not the mean midpoint. A note says when
        # BIC prefers 1 or 3 components; the gate does not move for it.
        fit = None
        if has_gmm:
            logger.info("Marker %d/%d '%s': Gaussian mixture", i + 1, n_markers, marker)
            try:
                fit = gmm_fit(valid, max_iter=GMM_MAX_ITER)
                t = gmm_threshold(fit)
                note = gmm_note(fit, t)
                if note is not None:
                    fit["note"] = note
                    logger.info("Marker '%s': GMM fit, %s", marker, note)
                t = max(float(bin_edges[0]), min(float(bin_edges[-1]), t))
                thresholds["gmm"] = round(t, 4)
            except Exception as e:
                logger.warning("GMM threshold failed for '%s': %s", marker, str(e))

        # Gamma mixture (GammaGateR-inspired)
        if has_gamma:
            logger.info("Marker %d/%d '%s': gamma", i + 1, n_markers, marker)
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
                float(q)
                for q in np.quantile(valid, np.linspace(0.0, 1.0, QUANTILE_COUNT))
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
