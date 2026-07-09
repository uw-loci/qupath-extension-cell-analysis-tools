"""
Foundation model feature extraction for QP-CAT.

Extracts tile-level feature embeddings from vision foundation models
for use in downstream clustering and phenotyping.

Approach inspired by LazySlide (MIT License).
Zheng, Y. et al. Nature Methods (2026). https://doi.org/10.1038/s41592-026-03044-7

Only models with commercially-permissive licenses (Apache 2.0, MIT) are used.

Inputs (injected by Appose 0.10.0 -- accessed as variables, NOT task.inputs):
  tile_images: NDArray (N_tiles x H x W x C, uint8) -- batch of tile images
  model_name: str -- foundation model identifier
  batch_size: int -- inference batch size (default 32)

Optional inputs:
  hf_token: str -- HuggingFace auth token for gated models

Outputs (via task.outputs):
  features: NDArray (N_tiles x embed_dim, float32) -- feature embeddings
  embed_dim: int -- embedding dimensionality
  model_used: str -- actual model name used
"""
import sys
import logging

logger = logging.getLogger("qpcat.features")

import numpy as np
import torch
from appose import NDArray as PyNDArray
# FOUNDATION_MODELS and detect_device() available from model_utils loaded during init

# 1. Parse inputs
tiles = tile_images.ndarray().copy()  # (N, H, W, C) uint8
n_tiles = tiles.shape[0]
logger.info("Received %d tiles of shape %s", n_tiles, tiles.shape[1:])

try:
    bs = batch_size
except NameError:
    bs = 32

try:
    token = hf_token
except NameError:
    token = None

if model_name not in FOUNDATION_MODELS:
    raise ValueError("Unknown model: %s. Available: %s" % (
        model_name, list(FOUNDATION_MODELS.keys())))

timm_id, expected_dim, model_license = FOUNDATION_MODELS[model_name]
logger.info("Loading %s (%s, embed_dim=%d)", model_name, model_license, expected_dim)

# 2. Load model via timm
task.update("Loading foundation model: %s..." % model_name, current=0, maximum=3)

import timm
from timm.data import resolve_data_config
from timm.data.transforms_factory import create_transform

# Set HF token if provided (for gated models)
if token:
    import os
    os.environ["HF_TOKEN"] = token

try:
    model = timm.create_model(timm_id, pretrained=True, num_classes=0)
except KeyError as _cfg_err:
    # timm raises a bare KeyError: 'architecture' when the HuggingFace repo is a
    # transformers model rather than a timm one -- its config.json has
    # "architectures" (plural). Translate it into something actionable.
    if "architecture" not in str(_cfg_err):
        raise
    raise RuntimeError(
        "Model '%s' (%s) is not loadable by timm: its HuggingFace config.json "
        "has no 'architecture' key, which means it is a transformers model, not "
        "a timm one. This is a bug in the QP-CAT model registry, not a problem "
        "with your environment -- please report it with the model name."
        % (model_name, timm_id)
    ) from _cfg_err
model.eval()

device = detect_device()

model = model.to(device)

# Get the model's expected transform (resize, normalize, etc.)
data_config = resolve_data_config(model.pretrained_cfg)
transform = create_transform(**data_config, is_training=False)

# 3. Extract features in batches
task.update("Extracting features...", current=1, maximum=3)

all_features = []
n_batches = (n_tiles + bs - 1) // bs

for batch_idx in range(n_batches):
    start = batch_idx * bs
    end = min(start + bs, n_tiles)
    batch_tiles = tiles[start:end]  # (B, H, W, C) uint8

    # Convert to PIL-like format for timm transform
    # timm transforms expect PIL images or tensors
    from PIL import Image
    batch_tensors = []
    for i in range(len(batch_tiles)):
        img = Image.fromarray(batch_tiles[i])
        tensor = transform(img)
        batch_tensors.append(tensor)

    batch_tensor = torch.stack(batch_tensors).to(device)

    with torch.no_grad(), torch.autocast(device_type=device,
                                          enabled=(device == "cuda")):
        features = model(batch_tensor)

    # Handle different output formats
    if isinstance(features, dict):
        # Some models return dicts; use the main embedding key
        for key in ["x_norm_clstoken", "cls_token", "x", "last_hidden_state"]:
            if key in features:
                features = features[key]
                break
        if isinstance(features, dict):
            features = list(features.values())[0]

    if features.dim() == 3:
        # (B, num_tokens, embed_dim) -> take CLS token or mean pool
        features = features[:, 0]  # CLS token (first position)

    all_features.append(features.cpu().float().numpy())

    if (batch_idx + 1) % 5 == 0 or batch_idx == n_batches - 1:
        logger.info("Feature extraction: batch %d/%d", batch_idx + 1, n_batches)

features_array = np.concatenate(all_features, axis=0)  # (N_tiles, embed_dim)
actual_dim = features_array.shape[1]
logger.info("Feature extraction complete: %d tiles x %d dimensions", n_tiles, actual_dim)

# 4. Package outputs
task.update("Packaging results...", current=2, maximum=3)

features_nd = PyNDArray(dtype="float32", shape=[n_tiles, actual_dim])
np.copyto(features_nd.ndarray(), features_array.astype(np.float32))

task.outputs["features"] = features_nd
task.outputs["embed_dim"] = actual_dim
task.outputs["model_used"] = model_name

logger.info("Feature extraction results packaged")
