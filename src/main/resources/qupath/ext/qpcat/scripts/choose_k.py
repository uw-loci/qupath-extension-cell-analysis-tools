"""
Sweep k and report the three statistics people are told to use for choosing it.

QP-CAT's KMeans note recommended choosing k "with elbow/silhouette/gap methods"
without providing any of them, which a user reasonably read as a claim the tool
did. This computes all three on the SAME matrix the clustering would use -- same
measurements, same normalization, same PCA precursor -- because a k chosen on a
different matrix is a k chosen for a different problem.

What each statistic is, and what it is worth:

  inertia   Within-cluster sum of squares. Falls monotonically with k, so there
            is no maximum to find: the "elbow" is a kink read by eye, and the
            kink is often absent or ambiguous. Reported because it is asked for
            and because its SHAPE is informative, not because it answers the
            question. Schubert 2023 (SIGKDD Explor. 25(1):36-42) argues it
            should not be used at all; the curve is shown with that said plainly.

  silhouette  Mean over cells of (b - a) / max(a, b), where a is the mean
            distance to the cell's own cluster and b to the nearest other
            cluster. Ranges -1..1, higher is better, and it HAS a maximum, so it
            can be argmaxed. O(n^2) in time and memory, so it is computed on a
            subsample and the subsample size is reported with it.
            (Rousseeuw 1987.)

  gap       log(expected inertia under a null) - log(observed inertia), where
            the null is uniform data over the same PCA-aligned bounding box.
            Tibshirani's rule is the SMALLEST k whose gap is within one standard
            error of the next k's -- not the argmax -- and that is what is
            reported as the suggestion. Alone among the three it can say "k = 1",
            i.e. there is no cluster structure here at all.
            (Tibshirani, Walther and Hastie 2001.)

The three regularly disagree. That is the honest finding, not a bug, and the
caller is expected to show all three rather than pick one. (Fu and Perry 2020
survey why, and propose cross-validation instead.)

Inputs (injected by Appose):
  measurements: NDArray (N_cells x N_features, float64)
  marker_names: list[str]
  k_min: int        -- smallest k to try (>= 1; 1 only matters for the gap)
  k_max: int        -- largest k to try
  random_seed: int
  silhouette_max_cells: int   -- subsample cap for the silhouette (0 = no cap)
  gap_n_references: int       -- B reference datasets per k (0 = skip the gap)
  sweep_max_cells: int        -- subsample cap for the whole sweep (0 = no cap)

Outputs (via task.outputs):
  sweep_json: str (JSON) -- see build_sweep_result for the shape
"""

import json
import logging

logger = logging.getLogger("qpcat.choose_k")

import numpy as np

# Match run_clustering: numba/BLAS fan-out inside the Appose worker has
# deadlocked on Windows, and a diagnostic must not be the thing that hangs a
# session.
try:
    import threadpoolctl as _tpc

    _tpc_limits = _tpc.threadpool_limits(limits=1)
except Exception:  # pragma: no cover - optional dependency
    _tpc_limits = None


def subsample_rows(matrix, max_rows, seed):
    """Take at most max_rows rows, chosen without replacement.

    Returns (rows, n_used, was_subsampled) so the caller can SAY it subsampled
    rather than quietly reporting a statistic computed on a tenth of the data.

    :param matrix: 2-D array
    :param max_rows: cap, or 0/None for no cap
    :param seed: RNG seed, so the same run gives the same subsample
    """
    matrix = np.asarray(matrix, dtype=np.float64)
    n = matrix.shape[0]
    if not max_rows or max_rows <= 0 or n <= max_rows:
        return matrix, n, False
    rng = np.random.default_rng(seed)
    idx = rng.choice(n, size=int(max_rows), replace=False)
    idx.sort()
    return matrix[idx], int(max_rows), True


def elbow_suggestion(ks, inertias):
    """The k farthest from the straight line joining the curve's endpoints.

    The "kink", made reproducible. Reading an elbow by eye is not reproducible
    and two people get two answers, so the suggestion is computed -- but it is
    still a heuristic over a monotonically decreasing curve with no optimum, and
    the caller is expected to say so. This is the standard
    maximum-distance-to-chord construction.

    :param ks: cluster counts, ascending
    :param inertias: within-cluster sum of squares per k
    :return: the suggested k, or None when the curve is too short to have a kink
    """
    ks = np.asarray(ks, dtype=np.float64)
    inertias = np.asarray(inertias, dtype=np.float64)
    good = np.isfinite(inertias)
    ks, inertias = ks[good], inertias[good]
    if ks.size < 3:
        return None
    # Normalise both axes: without it the answer depends on the units of the
    # measurements, so z-scored and raw runs would elbow at different k.
    def unit(v):
        lo, hi = float(v.min()), float(v.max())
        return (v - lo) / (hi - lo) if hi > lo else np.zeros_like(v)

    x, y = unit(ks), unit(inertias)
    x0, y0, x1, y1 = x[0], y[0], x[-1], y[-1]
    denom = np.hypot(x1 - x0, y1 - y0)
    if denom == 0:
        return None
    dist = np.abs((y1 - y0) * (x - x0) - (x1 - x0) * (y - y0)) / denom
    return int(ks[int(np.argmax(dist))])


def gap_suggestion(ks, gaps, std_errors):
    """Tibshirani's rule: the smallest k whose gap reaches the next k's, minus its error.

    NOT the argmax. The argmax overestimates k, which is the whole reason the
    original paper states the rule this way.

    :param ks: cluster counts, ascending
    :param gaps: gap statistic per k
    :param std_errors: standard error of the gap per k
    :return: the suggested k, or None when no k satisfies the rule
    """
    ks = list(ks)
    for i in range(len(ks) - 1):
        g, g_next, s_next = gaps[i], gaps[i + 1], std_errors[i + 1]
        if not (np.isfinite(g) and np.isfinite(g_next) and np.isfinite(s_next)):
            continue
        if g >= g_next - s_next:
            return int(ks[i])
    # No k satisfied it: the curve is still rising at k_max, so the honest
    # answer is "the range was too small", not the largest k tried.
    return None


def reference_box(matrix, seed):
    """Uniform data over the PCA-aligned bounding box of the real data.

    Tibshirani's second, recommended null: a box aligned to the principal axes
    rather than the raw feature axes, so an elongated, rotated cloud is not
    compared against a null that is far larger than it. Falls back to the raw
    bounding box when the SVD does not converge.

    :param matrix: the real data (n_cells x n_features)
    :param seed: RNG seed
    :return: an array of the same shape, drawn uniformly over the box
    """
    matrix = np.asarray(matrix, dtype=np.float64)
    rng = np.random.default_rng(seed)
    try:
        centre = matrix.mean(axis=0)
        centred = matrix - centre
        _, _, vt = np.linalg.svd(centred, full_matrices=False)
        projected = centred @ vt.T
        lo, hi = projected.min(axis=0), projected.max(axis=0)
        drawn = rng.uniform(lo, hi, size=projected.shape)
        return drawn @ vt + centre
    except np.linalg.LinAlgError:
        lo, hi = matrix.min(axis=0), matrix.max(axis=0)
        return rng.uniform(lo, hi, size=matrix.shape)


def build_sweep_result(ks, inertias, silhouettes, gaps, gap_errors, meta, warnings):
    """Assemble the JSON the Java side reads.

    :param ks: cluster counts tried, ascending
    :param inertias: within-cluster sum of squares per k (NaN where not computed)
    :param silhouettes: mean silhouette per k (NaN for k = 1, which has none)
    :param gaps: gap statistic per k (NaN when the gap was skipped)
    :param gap_errors: standard error of the gap per k
    :param meta: dict of run facts (cell counts, caps, seed, whether subsampled)
    :param warnings: list of plain-language caveats for the caller to display
    :return: a JSON string
    """
    def clean(values):
        return [None if not np.isfinite(v) else float(v) for v in values]

    return json.dumps(
        {
            "ks": [int(k) for k in ks],
            "inertia": clean(inertias),
            "silhouette": clean(silhouettes),
            "gap": clean(gaps),
            "gap_std_error": clean(gap_errors),
            "suggested": {
                "elbow": elbow_suggestion(ks, inertias),
                "silhouette": (
                    int(ks[int(np.nanargmax(silhouettes))])
                    if np.any(np.isfinite(silhouettes))
                    else None
                ),
                "gap": gap_suggestion(ks, gaps, gap_errors),
            },
            "meta": meta,
            "warnings": warnings,
        }
    )


def sweep_k(
    matrix,
    k_min,
    k_max,
    seed=42,
    silhouette_max_cells=10000,
    gap_n_references=10,
    progress=None,
):
    """Fit KMeans at each k and compute the three statistics.

    :param matrix: the clustering matrix (n_cells x n_features), already
                   normalized exactly as the real run would normalize it
    :param k_min: smallest k (clamped to >= 1)
    :param k_max: largest k
    :param seed: KMeans seed and the RNG seed for every subsample / reference
    :param silhouette_max_cells: subsample cap for the silhouette (0 = no cap)
    :param gap_n_references: B reference datasets per k (0 = skip the gap)
    :param progress: optional callable(fraction, message)
    :return: the JSON string from build_sweep_result
    """
    from sklearn.cluster import KMeans
    from sklearn.metrics import silhouette_score

    matrix = np.asarray(matrix, dtype=np.float64)
    n_cells, n_features = matrix.shape
    k_min = max(1, int(k_min))
    k_max = max(k_min, int(k_max))
    warnings = []

    # k cannot exceed the number of points, and a k near n is meaningless even
    # when it is legal.
    if k_max >= n_cells:
        k_max = max(k_min, n_cells - 1)
        warnings.append(
            "k_max was reduced to %d: there are only %d cells to divide."
            % (k_max, n_cells)
        )

    ks = list(range(k_min, k_max + 1))
    inertias, silhouettes, gaps, gap_errors = [], [], [], []

    sil_sample, sil_n, sil_subsampled = subsample_rows(
        matrix, silhouette_max_cells, seed
    )
    if sil_subsampled:
        warnings.append(
            "The silhouette is computed on a random %d-cell subsample of %d "
            "(seeded, so it is reproducible). It is O(n^2) in the cell count, and "
            "the full set would need roughly %.1f GB just for the distance matrix."
            % (sil_n, n_cells, (n_cells * n_cells * 8) / 1e9)
        )

    total_steps = max(1, len(ks))
    for step, k in enumerate(ks):
        if progress is not None:
            progress(step / total_steps, "Fitting k = %d of %d..." % (k, k_max))

        if k == 1:
            # One cluster: inertia is the total sum of squares, the silhouette is
            # undefined (there is no other cluster to compare against), and the
            # gap is defined and is the whole point of including k = 1.
            inertias.append(float(((matrix - matrix.mean(axis=0)) ** 2).sum()))
            silhouettes.append(float("nan"))
        else:
            km = KMeans(n_clusters=k, n_init=10, random_state=seed)
            km.fit(matrix)
            inertias.append(float(km.inertia_))
            try:
                labels = (
                    km.labels_
                    if not sil_subsampled
                    else km.predict(sil_sample)
                )
                if len(np.unique(labels)) < 2:
                    silhouettes.append(float("nan"))
                else:
                    silhouettes.append(
                        float(
                            silhouette_score(
                                sil_sample if sil_subsampled else matrix, labels
                            )
                        )
                    )
            except Exception as e:
                logger.warning("Silhouette failed at k=%d: %s", k, e)
                silhouettes.append(float("nan"))

        if gap_n_references and gap_n_references > 0:
            try:
                ref_inertias = []
                for b in range(int(gap_n_references)):
                    ref = reference_box(matrix, seed + 1000 * (k + 1) + b)
                    if k == 1:
                        ref_inertias.append(
                            float(((ref - ref.mean(axis=0)) ** 2).sum())
                        )
                    else:
                        rkm = KMeans(n_clusters=k, n_init=3, random_state=seed + b)
                        rkm.fit(ref)
                        ref_inertias.append(float(rkm.inertia_))
                logs = np.log(np.maximum(np.asarray(ref_inertias), 1e-300))
                observed = np.log(max(inertias[-1], 1e-300))
                gaps.append(float(logs.mean() - observed))
                # Tibshirani's s_k: the sd of the reference logs, inflated by
                # sqrt(1 + 1/B) for the Monte-Carlo error of using B of them.
                sd = float(logs.std(ddof=0))
                gap_errors.append(sd * float(np.sqrt(1.0 + 1.0 / len(ref_inertias))))
            except Exception as e:
                logger.warning("Gap statistic failed at k=%d: %s", k, e)
                gaps.append(float("nan"))
                gap_errors.append(float("nan"))
        else:
            gaps.append(float("nan"))
            gap_errors.append(float("nan"))

    if k_min > 1 and gap_n_references:
        warnings.append(
            "The sweep starts at k = %d, so the gap statistic cannot report "
            "'no cluster structure at all' -- only k = 1 can say that. Sweep from "
            "k = 1 if that is a possibility worth ruling out." % k_min
        )

    meta = {
        "n_cells": int(n_cells),
        "n_features": int(n_features),
        "silhouette_cells": int(sil_n),
        "silhouette_subsampled": bool(sil_subsampled),
        "gap_n_references": int(gap_n_references or 0),
        "seed": int(seed),
        "k_min": int(k_min),
        "k_max": int(k_max),
    }
    if progress is not None:
        progress(1.0, "Sweep complete")
    return build_sweep_result(
        ks, inertias, silhouettes, gaps, gap_errors, meta, warnings
    )


# ---------------------------------------------------------------------------
# Module-level entry point (Appose injects the globals below)
# ---------------------------------------------------------------------------

try:
    _measurements = measurements  # noqa: F821
except NameError:  # pragma: no cover - only when run outside Appose
    _measurements = None

if _measurements is not None:
    _mat = np.asarray(_measurements.ndarray(), dtype=np.float64)

    try:
        _k_min = int(k_min)  # noqa: F821
    except NameError:
        _k_min = 2
    try:
        _k_max = int(k_max)  # noqa: F821
    except NameError:
        _k_max = 20
    try:
        _seed = int(random_seed)  # noqa: F821
    except NameError:
        _seed = 42
    try:
        _sil_cap = int(silhouette_max_cells)  # noqa: F821
    except NameError:
        _sil_cap = 10000
    try:
        _gap_b = int(gap_n_references)  # noqa: F821
    except NameError:
        _gap_b = 10
    try:
        _sweep_cap = int(sweep_max_cells)  # noqa: F821
    except NameError:
        _sweep_cap = 0

    _extra_warnings = []
    _mat_used, _n_used, _was_sub = subsample_rows(_mat, _sweep_cap, _seed)
    if _was_sub:
        _extra_warnings.append(
            "The whole sweep ran on a random %d-cell subsample of %d. k chosen on "
            "a subsample transfers to the full set only if the subsample kept every "
            "population; a rare cell type may simply not be in it."
            % (_n_used, _mat.shape[0])
        )

    def _report(frac, message):
        try:
            task.update(message=message, current=int(frac * 100), maximum=100)  # noqa: F821
        except Exception:
            pass

    _result = sweep_k(
        _mat_used,
        _k_min,
        _k_max,
        seed=_seed,
        silhouette_max_cells=_sil_cap,
        gap_n_references=_gap_b,
        progress=_report,
    )

    if _extra_warnings:
        _parsed = json.loads(_result)
        _parsed["warnings"] = _extra_warnings + _parsed.get("warnings", [])
        _result = json.dumps(_parsed)

    task.outputs["sweep_json"] = _result  # noqa: F821
    logger.info("k sweep complete: k = %d..%d on %d cells", _k_min, _k_max, _n_used)
