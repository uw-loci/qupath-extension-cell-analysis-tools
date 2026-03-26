"""
[TEST FEATURE] Apply a trained autoencoder classifier to new cells.

Loads a previously trained CellVAE model checkpoint and encodes
new cell measurements through the encoder + classifier.
Used for applying a trained model across project images.

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  model_state_base64: str -- base64-encoded model checkpoint

Outputs (via task.outputs):
  latent_features: NDArray (N_cells x latent_dim, float32)
  predicted_labels: NDArray (N_cells,) int32
  prediction_confidence: NDArray (N_cells,) float32
  n_classes: int
  label_names_json: str (JSON)
"""
import sys
import json
import logging
import base64
import io

logger = logging.getLogger("qpcat.autoencoder.infer")

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from appose import NDArray as PyNDArray
from model_utils import detect_device

# Import the model class (same architecture as training)
# Inline to avoid import issues in Appose scripts
class CellVAE(nn.Module):
    def __init__(self, n_markers, latent_dim, n_classes=0):
        super().__init__()
        self.n_markers = n_markers
        self.latent_dim = latent_dim
        self.n_classes = n_classes
        self.enc1 = nn.Linear(n_markers, 128)
        self.enc2 = nn.Linear(128, 64)
        self.fc_mu = nn.Linear(64, latent_dim)
        self.fc_logvar = nn.Linear(64, latent_dim)
        self.dec1 = nn.Linear(latent_dim, 64)
        self.dec2 = nn.Linear(64, 128)
        self.dec_out = nn.Linear(128, n_markers)
        if n_classes > 0:
            self.classifier = nn.Linear(latent_dim, n_classes)
        else:
            self.classifier = None

    def encode(self, x):
        h = F.relu(self.enc1(x))
        h = F.relu(self.enc2(h))
        return self.fc_mu(h), self.fc_logvar(h)

    def classify(self, z):
        if self.classifier is None:
            return None
        return self.classifier(z)


class ConvCellVAE(nn.Module):
    """Convolutional VAE (must match training architecture exactly)."""
    def __init__(self, n_channels, tile_size, latent_dim, n_classes=0):
        super().__init__()
        self.n_channels = n_channels
        self.tile_size = tile_size
        self.latent_dim = latent_dim
        self.n_classes = n_classes
        self.encoder = nn.Sequential(
            nn.Conv2d(n_channels, 32, 3, stride=2, padding=1), nn.ReLU(),
            nn.Conv2d(32, 64, 3, stride=2, padding=1), nn.ReLU(),
            nn.Conv2d(64, 128, 3, stride=2, padding=1), nn.ReLU(),
            nn.AdaptiveAvgPool2d(1), nn.Flatten(),
        )
        self.fc_mu = nn.Linear(128, latent_dim)
        self.fc_logvar = nn.Linear(128, latent_dim)
        self.dec_spatial = max(tile_size // 8, 1)
        self.dec_fc = nn.Linear(latent_dim, 128 * self.dec_spatial * self.dec_spatial)
        self.decoder = nn.Sequential(
            nn.ConvTranspose2d(128, 64, 3, stride=2, padding=1, output_padding=1), nn.ReLU(),
            nn.ConvTranspose2d(64, 32, 3, stride=2, padding=1, output_padding=1), nn.ReLU(),
            nn.ConvTranspose2d(32, n_channels, 3, stride=2, padding=1, output_padding=1),
        )
        if n_classes > 0:
            self.classifier = nn.Linear(latent_dim, n_classes)
        else:
            self.classifier = None

    def encode(self, x):
        h = self.encoder(x)
        return self.fc_mu(h), self.fc_logvar(h)

    def classify(self, z):
        if self.classifier is None:
            return None
        return self.classifier(z)


# 1. Load checkpoint and determine mode
task.update("Loading model checkpoint...", current=0, maximum=3)

ckpt_bytes = base64.b64decode(model_state_base64)
buf = io.BytesIO(ckpt_bytes)
checkpoint = torch.load(buf, map_location='cpu', weights_only=False)

ldim = checkpoint['latent_dim']
n_classes = checkpoint['n_classes']
class_names = checkpoint['class_names']
norm_method = checkpoint['normalization']
norm_params = checkpoint['norm_params']
ckpt_mode = checkpoint.get('input_mode', 'measurements')
use_tiles = (ckpt_mode == 'tiles')

# 2. Load input data based on mode
task.update("Normalizing and encoding...", current=1, maximum=3)

if use_tiles:
    raw_data = tile_images.ndarray().copy().astype(np.float32)
    n_cells = raw_data.shape[0]
    ckpt_channels = checkpoint['n_channels']
    ckpt_tile_size = checkpoint['tile_size']
    logger.info("Tile inference: %d cells, %d channels", n_cells, ckpt_channels)

    # Per-channel normalization (reshape stats to NCHW broadcast shape)
    if norm_method == "zscore" and norm_params.get('mean') is not None:
        mean = np.array(norm_params['mean'], dtype=np.float32).reshape(1, -1, 1, 1)
        std = np.array(norm_params['std'], dtype=np.float32).reshape(1, -1, 1, 1)
        std[std == 0] = 1
        data_norm = (raw_data - mean) / std
    elif norm_method == "minmax" and norm_params.get('min') is not None:
        dmin = np.array(norm_params['min'], dtype=np.float32).reshape(1, -1, 1, 1)
        dmax = np.array(norm_params['max'], dtype=np.float32).reshape(1, -1, 1, 1)
        drange = dmax - dmin
        drange[drange == 0] = 1
        data_norm = (raw_data - dmin) / drange
    else:
        data_norm = raw_data.copy()
else:
    raw_data = measurements.ndarray().copy().astype(np.float32)
    n_cells, n_markers = raw_data.shape
    ckpt_n_markers = checkpoint['n_markers']
    logger.info("Measurement inference: %d cells x %d markers", n_cells, n_markers)

    if n_markers != ckpt_n_markers:
        raise ValueError("Marker count mismatch: model expects %d, got %d" %
                         (ckpt_n_markers, n_markers))

    if norm_method == "zscore" and norm_params.get('mean') is not None:
        mean = np.array(norm_params['mean'], dtype=np.float32)
        std = np.array(norm_params['std'], dtype=np.float32)
        std[std == 0] = 1
        data_norm = (raw_data - mean) / std
    elif norm_method == "minmax" and norm_params.get('min') is not None:
        dmin = np.array(norm_params['min'], dtype=np.float32)
        dmax = np.array(norm_params['max'], dtype=np.float32)
        drange = dmax - dmin
        drange[drange == 0] = 1
        data_norm = (raw_data - dmin) / drange
    else:
        data_norm = raw_data.copy()

# 3. Load model and run inference
device = detect_device()
if use_tiles:
    model = ConvCellVAE(ckpt_channels, ckpt_tile_size, ldim, n_classes).to(device)
else:
    model = CellVAE(checkpoint['n_markers'], ldim, n_classes).to(device)
model.load_state_dict(checkpoint['state_dict'])
model.eval()

with torch.no_grad():
    data_tensor = torch.tensor(data_norm, dtype=torch.float32).to(device)
    mu, _ = model.encode(data_tensor)
    latent = mu.cpu().numpy()

    pred_labels = np.full(n_cells, -1, dtype=np.int32)
    pred_conf = np.zeros(n_cells, dtype=np.float32)

    if n_classes > 0 and model.classifier is not None:
        logits = model.classify(mu)
        probs = F.softmax(logits, dim=1).cpu().numpy()
        pred_labels = probs.argmax(axis=1).astype(np.int32)
        pred_conf = probs.max(axis=1).astype(np.float32)

logger.info("Inference complete: %d cells encoded", n_cells)
if n_classes > 0:
    for i, name in enumerate(class_names):
        count = int((pred_labels == i).sum())
        logger.info("  Predicted %s: %d cells", name, count)

# 4. Package outputs
task.update("Packaging results...", current=2, maximum=3)

latent_nd = PyNDArray(dtype="float32", shape=[n_cells, ldim])
np.copyto(latent_nd.ndarray(), latent.astype(np.float32))
task.outputs["latent_features"] = latent_nd

pred_nd = PyNDArray(dtype="int32", shape=[n_cells])
np.copyto(pred_nd.ndarray(), pred_labels)
task.outputs["predicted_labels"] = pred_nd

conf_nd = PyNDArray(dtype="float32", shape=[n_cells])
np.copyto(conf_nd.ndarray(), pred_conf)
task.outputs["prediction_confidence"] = conf_nd

task.outputs["n_classes"] = n_classes
task.outputs["label_names_json"] = json.dumps(class_names)

logger.info("[TEST FEATURE] Autoencoder inference complete")
