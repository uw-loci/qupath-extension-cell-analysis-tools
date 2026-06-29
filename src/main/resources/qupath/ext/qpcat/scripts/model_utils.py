"""
Shared utilities for QP-CAT foundation model scripts.

Provides device detection and the foundation model registry.
Used by extract_features.py and zero_shot_phenotyping.py.

Foundation model integration inspired by LazySlide (MIT License).
Zheng, Y. et al. Nature Methods (2026). https://doi.org/10.1038/s41592-026-03044-7
"""
import logging
import torch

logger = logging.getLogger("qpcat.model_utils")

# Foundation model registry: name -> (timm_id, embed_dim, license)
# Only models with commercially-permissive licenses (Apache 2.0, MIT).
# Models are downloaded on-demand from HuggingFace, not bundled.
FOUNDATION_MODELS = {
    "h-optimus-0": ("hf_hub:bioptimus/H-optimus-0", 1536, "Apache 2.0"),
    "virchow": ("hf_hub:paige-ai/Virchow", 2560, "Apache 2.0"),
    "hibou-l": ("hf_hub:histai/hibou-L", 1024, "Apache 2.0"),
    "hibou-b": ("hf_hub:histai/hibou-b", 768, "Apache 2.0"),
    "midnight": ("hf_hub:kaiko-ai/midnight", 1536, "MIT"),
    "dinov2-large": ("hf_hub:facebook/dinov2-large", 1024, "Apache 2.0"),
}


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
        logger.info("Using CUDA GPU: %s (torch %s, CUDA %s)",
                    name, torch.__version__, cuda_build)
        return "cuda"
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        logger.info("Using Apple MPS (torch %s)", torch.__version__)
        return "mps"
    if cuda_build is None:
        logger.warning("Using CPU -- this is a CPU-only torch build (torch %s, no "
                       "CUDA). If you have an NVIDIA GPU, the Appose environment "
                       "needs the CUDA torch build (update the extension to pick up "
                       "the GPU-enabled lock; the env rebuilds automatically).",
                       torch.__version__)
    else:
        logger.warning("Using CPU -- torch has CUDA %s support but no usable GPU "
                       "was detected (torch %s). Check the NVIDIA driver / that the "
                       "GPU is visible.", cuda_build, torch.__version__)
    return "cpu"
