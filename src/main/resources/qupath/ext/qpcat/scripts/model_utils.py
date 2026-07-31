"""
Shared utilities for QP-CAT task scripts.

Provides device detection, the foundation model registry, and the shared
non-finite measurement guard. Loaded into every task script's global scope by
ApposeClusteringService at init, so task scripts use these without importing.

Foundation model integration inspired by LazySlide (MIT License).
Zheng, Y. et al. Nature Methods (2026). https://doi.org/10.1038/s41592-026-03044-7
"""

import logging
import warnings

import numpy as np
import torch

logger = logging.getLogger("qpcat.model_utils")

# Foundation model registry: name -> (timm_id, embed_dim, license)
# Only models with commercially-permissive licenses (Apache 2.0, MIT).
# Models are downloaded on-demand from HuggingFace, not bundled.
#
# Every id here MUST be a repo timm can load, i.e. its config.json carries a
# top-level "architecture" key. A *transformers* repo instead carries
# "architectures" (plural), and timm.create_model dies on it with a bare
# KeyError: 'architecture' -- see issue #8, where "dinov2-large" pointed at
# facebook/dinov2-large (a transformers repo) and could never have loaded.
# Check before adding a model:
#   curl -sL https://huggingface.co/<repo>/raw/main/config.json | grep architecture
FOUNDATION_MODELS = {
    "h-optimus-0": ("hf_hub:bioptimus/H-optimus-0", 1536, "Apache 2.0"),
    "virchow": ("hf_hub:paige-ai/Virchow", 2560, "Apache 2.0"),
    "hibou-l": ("hf_hub:histai/hibou-L", 1024, "Apache 2.0"),
    "hibou-b": ("hf_hub:histai/hibou-b", 768, "Apache 2.0"),
    # timm's DINOv2 mirror, not facebook/dinov2-large. The model card is
    # Apache-2.0 (same weights); the "cc-by-nc-4.0" string inside this repo's
    # pretrained_cfg is a stale timm default from before Meta relicensed DINOv2.
    # Native input size is 518, so tiles are upscaled from the configured size.
    "dinov2-large": (
        "hf_hub:timm/vit_large_patch14_dinov2.lvd142m",
        1024,
        "Apache 2.0",
    ),
}


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
# favour of parallelism. Measured on 16 cores, 30 markers, umap-learn 0.5.12:
#
#   n=100k   seeded 62.5 s   parallel+PCA-init 10.2 s
#   n=250k   seeded  175 s   parallel+PCA-init 22.4 s
#
# Below the threshold the seeded run is quick enough that byte-for-byte
# reproducibility is the better default; above it the seeded run grows into
# hours and reads as a hang.
EMBEDDING_FAST_MODE_CELLS = 200000

# Cell count above which UMAP's spectral initialisation is replaced by PCA.
# spectral init runs ARPACK eigsh single-threaded regardless of n_jobs and
# scales about N^1.9 (0.2 s at 25k, 4.1 s at 100k, 23.2 s at 250k), and it can
# fail to converge and silently fall back to random init having spent the time.
EMBEDDING_PCA_INIT_CELLS = 100000


def resolve_umap_execution(n_cells, seed, mode):
    """Pick UMAP's parallelism and initialisation for this dataset size.

    umap-learn couples reproducibility to serialism: passing any ``random_state``
    makes it force ``n_jobs=1`` (umap_.py:1950, applied to pynndescent via
    ``numba.set_num_threads``) AND compile the layout optimisation without numba
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
        "reproducible" -- always pin the seed (exactly the pre-0.9.7 behaviour).
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

        # Replace the spectral initialisation once its ARPACK eigensolve stops
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
