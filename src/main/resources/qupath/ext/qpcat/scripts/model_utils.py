"""
Shared utilities for QP-CAT task scripts.

Provides device detection, UMAP execution resolution, cancellation checks, the
shared non-finite measurement guard, the measurement normalization and the
figure labels (bold markers, color
scale titles). Loaded into every task script's global scope by
ApposeClusteringService at init, so task scripts use these without importing.

Spatial feature smoothing approach inspired by LazySlide (MIT License).
Zheng, Y. et al. Nature Methods (2026). https://doi.org/10.1038/s41592-026-03044-7
"""

import contextlib
import logging
import warnings

import numpy as np
import torch

logger = logging.getLogger("qpcat.model_utils")


def impute_nonfinite(data, context="measurements"):
    """Replace NaN/Inf with the per-column median of that column's finite values.

    MeasurementExtractor (Java) deliberately leaves a measurement that QuPath
    could not compute as NaN, so the Python side can impute it -- filling 0.0
    in Java injected a fake extreme value that biased normalization. Every
    consumer of the measurements matrix must therefore sanitize before doing
    arithmetic on it: numpy's mean/std are NOT NaN-aware, so a single NaN
    silently turns a whole column of statistics into NaN, and the usual
    `std[std == 0] = 1` guard does not catch it.

    Rows are never dropped -- callers index cells by row and would misalign
    against spatial coordinates or CellRefs. A column with no finite value at
    all imputes to 0.0.

    Returns (imputed float64 array, n_nonfinite).
    """
    # Always copy: the caller passes an Appose NDArray view backed by shared
    # memory, which is released once the task returns its outputs.
    arr = np.array(data, dtype=np.float64, copy=True)
    if arr.ndim != 2:
        raise ValueError(
            "expected a 2-D (n_cells, n_markers) array, got shape %s" % (arr.shape,)
        )

    mask = ~np.isfinite(arr)
    n_nonfinite = int(np.count_nonzero(mask))
    if n_nonfinite == 0:
        return arr, 0

    arr[mask] = np.nan
    with warnings.catch_warnings():
        # An all-NaN column makes nanmedian emit RuntimeWarning and return NaN;
        # that is expected here and handled on the next line.
        warnings.simplefilter("ignore", category=RuntimeWarning)
        medians = np.nanmedian(arr, axis=0)
    medians = np.where(np.isfinite(medians), medians, 0.0)

    arr[mask] = medians[np.nonzero(mask)[1]]
    logger.warning(
        "Imputed %d non-finite %s value(s) with the per-column median "
        "(NaN/Inf are not valid analysis input)",
        n_nonfinite,
        context,
    )
    return arr, n_nonfinite


def normalization_params(df, method, pct_low=0.01, pct_high=0.99):
    """Per-column (offset, scale, low, high) for a normalization method.

    A value becomes (clip(value, low, high) - offset) / scale; low and high are
    None unless the method clips. NaN and +-Inf are skipped in every statistic,
    so one infinite value cannot move a marker's center or spread. A column
    that cannot give a spread (constant, or under "zscore" fewer than two
    finite values) gets scale 1, so its finite values come out as 0 rather
    than NaN; a missing value stays NaN.

    method: "zscore", "minmax", "percentile" (clip to the pct_low and pct_high
    quantiles, given as fractions in either order, then rescale to [0, 1]);
    anything else leaves the values as they are.

    Raises ValueError naming the measurement when a statistic of its finite
    values overflows double precision (magnitudes near 1e154 under "zscore",
    or a range wider than about 1.8e308), since every value would then
    normalize to the same number.
    """
    if method not in ("zscore", "minmax", "percentile"):
        zeros = df.count() * 0.0
        return zeros, zeros + 1.0, None, None
    if np.isinf(df.to_numpy(dtype=np.float64)).any():
        df = df.where(np.isfinite(df))
    if method == "zscore":
        offset, scale = df.mean().fillna(0.0), df.std().replace(0, 1).fillna(1)
        low = high = None
    elif method == "minmax":
        offset = df.min()
        scale, low, high = (df.max() - offset).replace(0, 1).fillna(1), None, None
    else:
        lo = max(0.0, min(pct_low, pct_high))
        hi = min(1.0, max(pct_low, pct_high))
        low = df.quantile(lo)
        high = df.quantile(hi)
        offset, scale = low, (high - low).replace(0, 1).fillna(1)
    require_finite_scale(df, method, offset, scale)
    return offset, scale, low, high


# Why a float32 cast of the user's values can produce Inf, for require_finite.
FLOAT32_RANGE_REASON = (
    "Values that large cannot be held in the float32 numbers this step works in "
    "(the limit is about 3.4e38). A value that large is usually a placeholder for a "
    "missing value written by the software that produced the data: replace such "
    "values with NaN, or leave the measurement out."
)


def require_finite(matrix, step, column_names=None, max_named=6, reason=None):
    """Fail with a useful message when a step has produced NaN or Inf.

    Without this the first thing to notice is the embedding, which reports
    "ValueError: Input contains NaN" from deep inside sklearn -- naming neither the
    step that introduced it nor the columns affected, and leaving the user to guess
    which option to turn off.

    Args:
        matrix: 2-D array to check.
        step: what just ran, named in the error.
        column_names: optional per-column names, used to name the offenders.
        max_named: how many column names to list before summarizing.
        reason: what the user should know and do; the default says the step
            itself is at fault. FLOAT32_RANGE_REASON for a float32 cast.

    Raises:
        ValueError: naming the step and the affected columns.
    """
    arr = np.asarray(matrix, dtype=np.float64)
    bad = ~np.isfinite(arr)
    if not bad.any():
        return
    cols = np.unique(np.nonzero(bad)[1]) if arr.ndim == 2 else np.array([])
    if column_names is not None and arr.ndim == 2:
        named = [
            str(column_names[c]) for c in cols[:max_named] if c < len(column_names)
        ]
    else:
        named = [str(int(c)) for c in cols[:max_named]]
    more = "" if len(cols) <= max_named else " (and %d more)" % (len(cols) - max_named)
    if reason is None:
        reason = (
            "This is a QP-CAT defect rather than a problem with your data; the run was "
            "stopped here so the cause is named instead of surfacing later as "
            "'Input contains NaN' from inside the embedding."
        )
    raise ValueError(
        "%s produced %d non-finite value(s) in %d column(s): %s%s. %s"
        % (step, int(bad.sum()), len(cols), ", ".join(named), more, reason)
    )


def refuse_duplicate_names(names):
    """Raise when two measurement names are equal, or equal after trimming and case folding.

    Results are keyed by name (marker rankings, gates, thresholds, the AnnData
    var names), so a second column of the same name overwrites the first, and
    two names that differ only in case or surrounding spaces read as one.
    """
    if names is None:
        return
    seen = {}
    clashes = []
    for name in names:
        key = str(name).strip().casefold()
        if key in seen:
            clashes.append("'%s' and '%s'" % (seen[key], name))
        else:
            seen[key] = name
    if clashes:
        raise ValueError(
            "Two selected measurements have the same name (ignoring case and "
            "surrounding spaces): %s. Results are listed by name, so one would hide "
            "or overwrite the other. Leave one of each pair out, or rename it."
            % "; ".join(clashes)
        )


def require_finite_scale(df, method, offset, scale):
    """Refuse when a column with finite values got a non-finite offset or scale."""
    has_values = df.notna().any().to_numpy()
    bad = has_values & ~(
        np.isfinite(offset.to_numpy(dtype=np.float64))
        & np.isfinite(scale.to_numpy(dtype=np.float64))
    )
    if not bad.any():
        return
    names = []
    for j in np.nonzero(bad)[0]:
        col = df.iloc[:, j]
        names.append("'%s' (largest magnitude %.3g)" % (df.columns[j], col.abs().max()))
    raise ValueError(
        "Cannot normalize (%s) measurement(s) %s: the values are too large for "
        "their mean, spread or range to be computed in double precision, so every "
        "cell would get the same normalized value. A value near 1e300 is usually a "
        "placeholder for a missing value written by the software that produced the "
        "data: replace such values with NaN, or leave the measurement out."
        % (method, ", ".join(names))
    )


def normalize_frame(df, method, pct_low=0.01, pct_high=0.99):
    """Normalize every column of a measurement DataFrame; returns a new frame.

    The one normalization for clustering, phenotyping, threshold previews, the
    VEST embedding and geosketch selection. See normalization_params.
    """
    if method not in ("zscore", "minmax", "percentile"):
        return df.copy()
    offset, scale, low, high = normalization_params(df, method, pct_low, pct_high)
    if low is not None:
        df = df.clip(lower=low, upper=high, axis=1)
    return (df - offset) / scale


def single_threaded():
    """A context that runs its body with every BLAS and OpenMP pool at one thread.

    numba and BLAS fan-out inside the Appose worker has deadlocked on Windows:
    the spatial permutation statistics (0.4.1), Choose k, and Compute Thresholds
    (issue #24). The guarded sections are small-data work, so one thread costs
    nothing measurable. The limit is restored on exit, so the next clustering
    run in this worker keeps its threads; a bare threadpool_limits() call would
    leave the worker single-threaded until QuPath restarts.
    """
    try:
        from threadpoolctl import threadpool_limits
    except ImportError:  # pragma: no cover - optional dependency
        return contextlib.nullcontext()
    return threadpool_limits(limits=1)


def detect_device():
    """Detect the best available compute device (cuda > mps > cpu), logging WHY
    so a CPU fallback on a GPU machine is diagnosable: a CPU-only torch build
    (torch.version.cuda is None) reads very differently from a CUDA torch build
    that cannot see a usable GPU (driver problem)."""
    cuda_build = getattr(torch.version, "cuda", None)
    if torch.cuda.is_available():
        try:
            name = torch.cuda.get_device_name(0)
        except Exception:
            name = "unknown GPU"
        logger.info(
            "Using CUDA GPU: %s (torch %s, CUDA %s)",
            name,
            torch.__version__,
            cuda_build,
        )
        return "cuda"
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        logger.info("Using Apple MPS (torch %s)", torch.__version__)
        return "mps"
    if cuda_build is None:
        logger.warning(
            "Using CPU -- this is a CPU-only torch build (torch %s, no "
            "CUDA). If you have an NVIDIA GPU, the Appose environment "
            "needs the CUDA torch build (update the extension to pick up "
            "the GPU-enabled lock; the env rebuilds automatically).",
            torch.__version__,
        )
    else:
        logger.warning(
            "Using CPU -- torch has CUDA %s support but no usable GPU "
            "was detected (torch %s). Check the NVIDIA driver / that the "
            "GPU is visible.",
            cuda_build,
            torch.__version__,
        )
    return "cpu"


# Cell count at or above which "auto" embedding mode drops the pinned seed in
# favor of parallelism. Measured on 16 cores, 30 markers, umap-learn 0.5.12:
#
#   n=100k   seeded 62.5 s   parallel+PCA-init 10.2 s
#   n=250k   seeded  175 s   parallel+PCA-init 22.4 s
#
# Below the threshold the seeded run is quick enough that byte-for-byte
# reproducibility is the better default; above it the seeded run grows into
# hours and reads as a hang.
EMBEDDING_FAST_MODE_CELLS = 200000

# Cell count above which UMAP's spectral initialization is replaced by PCA.
# spectral init runs ARPACK eigsh single-threaded regardless of n_jobs and
# scales about N^1.9 (0.2 s at 25k, 4.1 s at 100k, 23.2 s at 250k), and it can
# fail to converge and silently fall back to random init having spent the time.
EMBEDDING_PCA_INIT_CELLS = 100000


def resolve_umap_execution(n_cells, seed, mode):
    """Pick UMAP's parallelism and initialization for this dataset size.

    umap-learn couples reproducibility to serialism: passing any ``random_state``
    makes it force ``n_jobs=1`` (umap_.py:1950, applied to pynndescent via
    ``numba.set_num_threads``) AND compile the layout optimization without numba
    ``prange`` (``parallel=random_state is None``, umap_.py:2891). So a pinned
    seed costs every core in BOTH halves of UMAP -- roughly 6-8x on a 16-core
    box, and the gap widens with cell count.

    Parameters
    ----------
    n_cells : int
        Rows in the feature matrix.
    seed : int
        The user's random seed.
    mode : str
        "reproducible" -- always pin the seed (exactly the pre-0.9.7 behavior).
        "fast"         -- always drop the seed and use every core.
        "auto"         -- reproducible below EMBEDDING_FAST_MODE_CELLS, fast above.

    Returns
    -------
    (kwargs, reproducible, note)
        ``kwargs`` to splice into the ``umap.UMAP(...)`` call, whether the run is
        byte-reproducible, and a short human-readable reason for the log and the
        audit trail.
    """
    normalized = str(mode).lower() if mode is not None else "auto"
    if normalized not in ("auto", "fast", "reproducible"):
        logger.warning("Unknown embedding mode %r; using 'auto'", mode)
        normalized = "auto"

    if normalized == "auto":
        reproducible = n_cells < EMBEDDING_FAST_MODE_CELLS
    else:
        reproducible = normalized == "reproducible"

    kwargs = {}
    if reproducible:
        # Deliberately nothing but the seed. "reproducible" has to mean EXACTLY
        # the pre-0.9.7 call at any cell count -- init in particular changes the
        # layout, so adding it here would silently invalidate a user's earlier
        # figures. The whole point of this branch is that it is unchanged.
        kwargs["random_state"] = int(seed)
        note = "seeded (reproducible, single-threaded)"
    else:
        # random_state MUST be None -- any value re-triggers the n_jobs override.
        kwargs["random_state"] = None
        kwargs["n_jobs"] = -1
        note = "parallel (all cores, layout not bit-reproducible)"

        # Replace the spectral initialization once its ARPACK eigensolve stops
        # being cheap. This is only reached on the non-reproducible path, where
        # the layout is already free to differ between runs. Preference order for
        # large N is pca > tswspectral > spectral (umap-learn PR #924).
        if n_cells >= EMBEDDING_PCA_INIT_CELLS:
            kwargs["init"] = "pca"
            note += ", PCA init"

    if normalized == "auto":
        note += " [auto at %d cells]" % n_cells

    return kwargs, reproducible, note


def check_cancelled(task_obj):
    """Stop the run if the user pressed Cancel.

    Appose cancellation is COOPERATIVE: a CANCEL request only sets
    ``task.cancel_requested`` on the worker (appose python_worker.py), and the
    script has to notice. Nothing in QP-CAT used to look at the flag, so Cancel
    never actually stopped anything -- the task ran to completion and the UI
    stayed blocked, which is a large part of why a slow run read as a hang.

    Calling ``task.cancel()`` moves the task to CANCELED so the Java side stops
    waiting. Cancellation is only checked BETWEEN phases; a single long library
    call (a UMAP fit, a Leiden partition) still has to finish first.
    """
    if getattr(task_obj, "cancel_requested", False):
        logger.info("Cancellation requested -- stopping before the next phase")
        task_obj.cancel()
        raise RuntimeError("QPCAT_CANCELLED: cancelled by the user")


def report_progress(task_obj, message):
    """Send a progress line to the Java side; a failed send never aborts the run."""
    try:
        task_obj.update(message)
    except Exception:
        pass


# Default cluster colors, in numeric cluster-id order: matplotlib tab20 for
# clusters 0-19, then 20 deeper tones for 20-39, repeating after that. The same
# 40 values live in ClusterPalette.java on the Java side;
# python_tests/test_cluster_palette.py keeps the two copies identical, so the
# viewer overlay, the interactive plots and the matplotlib PNGs give one cluster
# one color. Task scripts see these names through the worker's global scope.
CLUSTER_PALETTE_HEX = [
    "#1F77B4",
    "#FF7F0E",
    "#2CA02C",
    "#D62728",
    "#9467BD",
    "#8C564B",
    "#E377C2",
    "#7F7F7F",
    "#BCBD22",
    "#17BECF",
    "#AEC7E8",
    "#FFBB78",
    "#98DF8A",
    "#FF9896",
    "#C5B0D5",
    "#C49C94",
    "#F7B6D2",
    "#C7C7C7",
    "#DBDB8D",
    "#9EDAE5",
    "#132E80",
    "#A65F19",
    "#2E731D",
    "#801313",
    "#5F1173",
    "#733811",
    "#A6197B",
    "#344373",
    "#736E1D",
    "#116E73",
    "#A67D4B",
    "#A69119",
    "#779926",
    "#19A666",
    "#11734C",
    "#19A691",
    "#1919A6",
    "#A619A6",
    "#73114C",
    "#A6194A",
]


def cluster_palette_hex(cluster_id):
    """Default "#RRGGBB" for a cluster id, wrapping past the 40 palette entries."""
    return CLUSTER_PALETTE_HEX[int(cluster_id) % len(CLUSTER_PALETTE_HEX)]


def split_measurement_name(name):
    """Split a QuPath measurement name into (before, marker, after).

    ``"Nucleus: CD3 std dev"`` -> ``("Nucleus: ", "CD3", " std dev")``,
    ``"Cell: PCNA: Mean"`` -> ``("Cell: ", "PCNA", ": Mean")`` and the
    channel-first form of the cell-detection extensions, ``"CD3: Nucleus: Mean"``,
    -> ``("", "CD3", ": Nucleus: Mean")``. The parts always join back to ``name``;
    a part that is not there is empty. Mirrored by ``MeasurementNames.split`` in
    Java; both are tested against ``measurement_name_cases.json``.
    """
    stats = (
        "std dev",
        "std.dev.",
        "mean",
        "median",
        "min",
        "max",
        "sum",
        "range",
        "variance",
    )
    compartments = ("nucleus", "cytoplasm", "membrane", "cell")
    parts = name.split(": ")
    if len(parts) >= 3 and parts[-1].lower() in stats:
        i = len(parts) - (3 if parts[-2].lower() in compartments else 2)
        before = ": ".join(parts[:i]) + ": " if i else ""
        return before, parts[i], name[len(before) + len(parts[i]) :]
    before = ": ".join(parts[:-1]) + ": " if len(parts) > 1 else ""
    rest = parts[-1]
    for stat in stats:
        if rest.lower().endswith(" " + stat) and len(rest) > len(stat) + 1:
            cut = len(rest) - len(stat) - 1
            return before, rest[:cut], rest[cut:]
    return before, rest, ""


def bold_marker_label(name):
    """Figure label for ``name`` with the marker part in bold (matplotlib mathtext).

    Characters mathtext would space out or reinterpret (a hyphen becomes a minus
    sign, an apostrophe a prime) are swapped for ones that draw as written. A name
    holding any other special character is returned unchanged, never mangled.
    """
    import re

    before, marker, after = split_measurement_name(name)
    if not marker or "$" in name or not re.match(r"^[A-Za-z0-9 .()/,'+_%-]+$", marker):
        return name
    safe = {
        " ": r"\ ",
        "-": "\u2010",
        "+": "{+}",
        ",": "{,}",
        "'": "\u2019",
        "_": r"\_",
        "%": r"\%",
    }
    body = "".join(safe.get(c, c) for c in marker)
    return before + r"$\mathbf{" + body + "}$" + after


def bold_marker_ticks(ax, axis="x"):
    """Re-label an axis's ticks with each marker in bold, keeping their layout."""
    labels = ax.get_xticklabels() if axis == "x" else ax.get_yticklabels()
    if not labels:
        return
    first = labels[0]
    style = {
        "rotation": first.get_rotation(),
        "rotation_mode": first.get_rotation_mode(),
        "ha": first.get_ha(),
        "va": first.get_va(),
        "fontsize": first.get_fontsize(),
    }
    text = [bold_marker_label(t.get_text()) for t in labels]
    if axis == "x":
        ax.set_xticks(ax.get_xticks())
        ax.set_xticklabels(text, **style)
    else:
        ax.set_yticks(ax.get_yticks())
        ax.set_yticklabels(text, **style)


def legend_title(statistic, normalization, per_marker_scaled=False):
    """Color-scale title for a cluster x marker figure that names its units.

    scanpy's default, "Mean expression in group", hides that the figures draw the
    run's NORMALIZED matrix. ``"Median"``, ``"zscore"`` ->
    ``"Median in cluster\\n(z-score)"``. The unit words are shared with the Java
    heatmap through ``normalization_units.json``.
    """
    if per_marker_scaled:
        units = "each marker scaled 0-1"
    else:
        units = {
            "zscore": "z-score",
            "minmax": "min-max 0-1",
            "percentile": "p1-p99 scaled 0-1",
        }.get(normalization, "raw values")
    return "%s in cluster\n(%s)" % (statistic, units)
