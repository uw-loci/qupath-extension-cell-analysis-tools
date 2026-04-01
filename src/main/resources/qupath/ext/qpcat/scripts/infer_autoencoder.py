"""
[TEST FEATURE] Apply a trained autoencoder classifier to new cells.

Loads a previously trained CellVAE model checkpoint and encodes
new cell measurements through the encoder + classifier.
Used for applying a trained model across project images.

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)  [measurement mode]
  tile_images: NDArray (N_cells x C x H x W, float32)   [tile mode]
  marker_names: list[str]  [measurement mode]
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
# detect_device() available from model_utils loaded during init

LOGVAR_CLAMP_MIN = -10.0
LOGVAR_CLAMP_MAX = 10.0

# Architecture must match training exactly
class CellVAE(nn.Module):
    def __init__(self, n_markers, latent_dim, n_classes=0):
        super().__init__()
        self.n_markers = n_markers
        self.latent_dim = latent_dim
        self.n_classes = n_classes
        self.enc1 = nn.Linear(n_markers, 128)
        self.enc_ln1 = nn.LayerNorm(128)
        self.enc2 = nn.Linear(128, 64)
        self.enc_ln2 = nn.LayerNorm(64)
        self.fc_mu = nn.Linear(64, latent_dim)
        self.fc_logvar = nn.Linear(64, latent_dim)
        self.dec1 = nn.Linear(latent_dim, 64)
        self.dec_ln1 = nn.LayerNorm(64)
        self.dec2 = nn.Linear(64, 128)
        self.dec_ln2 = nn.LayerNorm(128)
        self.dec_mu = nn.Linear(128, n_markers)
        self.dec_logvar = nn.Linear(128, n_markers)
        if n_classes > 0:
            self.classifier = nn.Linear(latent_dim, n_classes)
        else:
            self.classifier = None

    def encode(self, x):
        h = F.leaky_relu(self.enc_ln1(self.enc1(x)))
        h = F.leaky_relu(self.enc_ln2(self.enc2(h)))
        mu = self.fc_mu(h)
        logvar = torch.clamp(self.fc_logvar(h), LOGVAR_CLAMP_MIN, LOGVAR_CLAMP_MAX)
        return mu, logvar

    def classify(self, z):
        if self.classifier is None:
            return None
        return self.classifier(z)


class ConvCellVAE(nn.Module):
    def __init__(self, n_channels, tile_size, latent_dim, n_classes=0, n_measurements=0):
        super().__init__()
        self.n_channels = n_channels
        self.tile_size = tile_size
        self.latent_dim = latent_dim
        self.n_classes = n_classes
        self.n_measurements = n_measurements
        self.encoder = nn.Sequential(
            nn.Conv2d(n_channels, 32, 3, stride=2, padding=1), nn.LeakyReLU(),
            nn.Conv2d(32, 64, 3, stride=2, padding=1), nn.LeakyReLU(),
            nn.Conv2d(64, 128, 3, stride=2, padding=1), nn.LeakyReLU(),
            nn.AdaptiveAvgPool2d(1), nn.Flatten(),
        )
        if n_measurements > 0:
            meas_hidden = min(64, max(16, n_measurements))
            self.meas_encoder = nn.Sequential(
                nn.Linear(n_measurements, meas_hidden),
                nn.LayerNorm(meas_hidden),
                nn.LeakyReLU(),
            )
            combined_dim = 128 + meas_hidden
        else:
            self.meas_encoder = None
            combined_dim = 128
        self.fc_mu = nn.Linear(combined_dim, latent_dim)
        self.fc_logvar = nn.Linear(combined_dim, latent_dim)
        self.dec_spatial = max(tile_size // 8, 1)
        self.dec_fc = nn.Linear(latent_dim, 128 * self.dec_spatial * self.dec_spatial)
        self.decoder = nn.Sequential(
            nn.ConvTranspose2d(128, 64, 3, stride=2, padding=1, output_padding=1), nn.LeakyReLU(),
            nn.ConvTranspose2d(64, 32, 3, stride=2, padding=1, output_padding=1), nn.LeakyReLU(),
            nn.ConvTranspose2d(32, n_channels, 3, stride=2, padding=1, output_padding=1),
        )
        if n_classes > 0:
            self.classifier = nn.Linear(latent_dim, n_classes)
        else:
            self.classifier = None

    def encode(self, x, measurements=None):
        h = self.encoder(x)
        if self.meas_encoder is not None and measurements is not None:
            m = self.meas_encoder(measurements)
            h = torch.cat([h, m], dim=1)
        mu = self.fc_mu(h)
        logvar = torch.clamp(self.fc_logvar(h), LOGVAR_CLAMP_MIN, LOGVAR_CLAMP_MAX)
        return mu, logvar

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
    ckpt_channels = checkpoint['n_channels']
    ckpt_tile_size = checkpoint['tile_size']
    n_cells_tile = int(n_cells)
    # Memory-map tiles from temp file (avoids loading all into RAM)
    raw_data = np.memmap(tile_file_path, dtype='<f4', mode='r',
                         shape=(n_cells_tile, ckpt_channels, ckpt_tile_size, ckpt_tile_size))
    n_cells = raw_data.shape[0]
    tile_size_mb = raw_data.nbytes / (1024 * 1024)
    logger.info("Tile inference: %d cells, %d channels (%.0f MB, memory-mapped)",
                n_cells, ckpt_channels, tile_size_mb)

    # Prepare normalization tensors for per-batch normalization (no full-array copy)
    tile_norm_mean = None
    tile_norm_std = None
    tile_norm_min = None
    tile_norm_range = None
    if norm_method == "zscore" and norm_params.get('mean') is not None:
        tile_norm_mean = torch.tensor(norm_params['mean'], dtype=torch.float32).view(1, -1, 1, 1)
        tile_norm_std = torch.tensor(norm_params['std'], dtype=torch.float32).view(1, -1, 1, 1)
        tile_norm_std[tile_norm_std == 0] = 1
    elif norm_method == "minmax" and norm_params.get('min') is not None:
        tile_norm_min = torch.tensor(norm_params['min'], dtype=torch.float32).view(1, -1, 1, 1)
        dmax_t = torch.tensor(norm_params['max'], dtype=torch.float32).view(1, -1, 1, 1)
        tile_norm_range = dmax_t - tile_norm_min
        tile_norm_range[tile_norm_range == 0] = 1

    data_norm = None  # tiles normalized per-batch in encode loop
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
    n_tile_meas = checkpoint.get('n_tile_measurements', 0)
    model = ConvCellVAE(ckpt_channels, ckpt_tile_size, ldim, n_classes,
                        n_measurements=n_tile_meas).to(device)
else:
    model = CellVAE(checkpoint['n_markers'], ldim, n_classes).to(device)
model.load_state_dict(checkpoint['state_dict'])
model.eval()

# Load hybrid measurements for tile mode if present
infer_tile_meas = None
infer_meas_mean = None
infer_meas_std = None
if use_tiles and n_tile_meas > 0:
    try:
        if tile_measurements is not None:
            infer_tile_meas = tile_measurements.ndarray().copy().astype(np.float32)
            ckpt_meas_mean = checkpoint.get('tile_meas_mean')
            ckpt_meas_std = checkpoint.get('tile_meas_std')
            if ckpt_meas_mean is not None:
                infer_meas_mean = torch.tensor(ckpt_meas_mean, dtype=torch.float32)
                infer_meas_std = torch.tensor(ckpt_meas_std, dtype=torch.float32)
            logger.info("Hybrid inference: %d measurements alongside tiles", n_tile_meas)
    except NameError:
        pass

with torch.no_grad():
    encode_bs = 512
    all_mu = []
    all_logits = []
    for i in range(0, n_cells, encode_bs):
        end = min(i + encode_bs, n_cells)
        batch_meas = None
        if use_tiles:
            batch = torch.tensor(np.array(raw_data[i:end]), dtype=torch.float32)
            if tile_norm_mean is not None:
                batch = (batch - tile_norm_mean) / tile_norm_std
            elif tile_norm_min is not None:
                batch = (batch - tile_norm_min) / tile_norm_range
            batch = batch.to(device)
            # Hybrid measurements
            if infer_tile_meas is not None:
                batch_meas = torch.tensor(infer_tile_meas[i:end], dtype=torch.float32)
                if infer_meas_mean is not None:
                    batch_meas = (batch_meas - infer_meas_mean) / (infer_meas_std + 1e-8)
                batch_meas = batch_meas.to(device)
        else:
            batch = torch.tensor(np.array(data_norm[i:end]), dtype=torch.float32).to(device)
        if use_tiles:
            mu_b, _ = model.encode(batch, batch_meas)
        else:
            mu_b, _ = model.encode(batch)
        all_mu.append(mu_b.cpu())
        if n_classes > 0 and model.classifier is not None:
            all_logits.append(model.classify(mu_b).cpu())

        if (i + encode_bs) % 5000 < encode_bs:
            task.update("Encoding cells %d/%d..." % (min(i + encode_bs, n_cells), n_cells),
                        current=1, maximum=3)

    mu_all = torch.cat(all_mu, dim=0)
    latent = mu_all.numpy()

    pred_labels = np.full(n_cells, -1, dtype=np.int32)
    pred_conf = np.zeros(n_cells, dtype=np.float32)

    if n_classes > 0 and model.classifier is not None and all_logits:
        logits_all = torch.cat(all_logits, dim=0)
        probs = F.softmax(logits_all, dim=1).numpy()
        pred_labels = probs.argmax(axis=1).astype(np.int32)
        pred_conf = probs.max(axis=1).astype(np.float32)

logger.info("Inference complete: %d cells encoded", n_cells)
if n_classes > 0:
    for i, name in enumerate(class_names):
        count = int((pred_labels == i).sum())
        logger.info("  Predicted %s: %d cells", name, count)

# Release tile memmap so Java can delete the temp file (Windows file locking)
if use_tiles:
    if hasattr(raw_data, '_mmap') and raw_data._mmap is not None:
        raw_data._mmap.close()
    del raw_data
    if 'data_norm' in dir():
        del data_norm
    import gc
    gc.collect()

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
