"""
[TEST FEATURE] Semi-supervised VAE for cell classification.

Trains a variational autoencoder with an optional classifier head on
cell marker measurements. When some cells have user-assigned labels,
the model learns a latent space that both reconstructs measurements
AND separates labeled cell types (semi-supervised).

Architecture follows the scANVI pattern (Xu et al. 2021, Molecular Systems Biology)
adapted for continuous protein measurements (Gaussian likelihood instead of ZINB).

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  labels: list[int] -- class index per cell (-1 = unlabeled)
  label_names: list[str] -- class name for each index
  latent_dim: int (default 16)
  n_epochs: int (default 100)
  learning_rate: float (default 0.001)
  batch_size: int (default 128)
  supervision_weight: float (default 1.0)
  normalization: str ("zscore", "minmax", "none")

Optional inputs:
  model_save_path: str -- path to save trained model checkpoint

Outputs (via task.outputs):
  latent_features: NDArray (N_cells x latent_dim, float32)
  predicted_labels: NDArray (N_cells,) int32
  prediction_confidence: NDArray (N_cells,) float32
  n_classes: int
  final_recon_loss: float
  final_class_accuracy: float
  model_state_base64: str -- base64-encoded state dict
"""
import sys
import json
import logging
import base64
import io

logger = logging.getLogger("qpcat.autoencoder")

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, TensorDataset
from appose import NDArray as PyNDArray

# ==================== VAE Architecture ====================

class CellVAE(nn.Module):
    """
    Variational Autoencoder with optional semi-supervised classifier head.

    Encoder: input -> 128 -> 64 -> (mu, logvar) of size latent_dim
    Decoder: latent_dim -> 64 -> 128 -> input (Gaussian reconstruction)
    Classifier: latent_dim -> n_classes (optional, for labeled cells)
    """

    def __init__(self, n_markers, latent_dim, n_classes=0):
        super().__init__()
        self.n_markers = n_markers
        self.latent_dim = latent_dim
        self.n_classes = n_classes

        # Encoder
        self.enc1 = nn.Linear(n_markers, 128)
        self.enc2 = nn.Linear(128, 64)
        self.fc_mu = nn.Linear(64, latent_dim)
        self.fc_logvar = nn.Linear(64, latent_dim)

        # Decoder
        self.dec1 = nn.Linear(latent_dim, 64)
        self.dec2 = nn.Linear(64, 128)
        self.dec_out = nn.Linear(128, n_markers)

        # Classifier head (only if supervised/semi-supervised)
        if n_classes > 0:
            self.classifier = nn.Linear(latent_dim, n_classes)
        else:
            self.classifier = None

    def encode(self, x):
        h = F.relu(self.enc1(x))
        h = F.relu(self.enc2(h))
        return self.fc_mu(h), self.fc_logvar(h)

    def reparameterize(self, mu, logvar):
        std = torch.exp(0.5 * logvar)
        eps = torch.randn_like(std)
        return mu + eps * std

    def decode(self, z):
        h = F.relu(self.dec1(z))
        h = F.relu(self.dec2(h))
        return self.dec_out(h)

    def classify(self, z):
        if self.classifier is None:
            return None
        return self.classifier(z)

    def forward(self, x):
        mu, logvar = self.encode(x)
        z = self.reparameterize(mu, logvar)
        recon = self.decode(z)
        class_logits = self.classify(z)
        return recon, mu, logvar, z, class_logits


class ConvCellVAE(nn.Module):
    """
    Convolutional VAE for tile-based cell classification.

    Takes multi-channel image tiles (NCHW format) and learns a latent
    representation via convolutional encoder/decoder. Supports variable
    channel counts for multiplexed imaging (IMC, CODEX, IF panels).

    Encoder: Conv2d layers -> AdaptiveAvgPool -> flatten -> (mu, logvar)
    Decoder: Linear -> unflatten -> ConvTranspose2d layers -> output
    Classifier: latent_dim -> n_classes (optional)
    """

    def __init__(self, n_channels, tile_size, latent_dim, n_classes=0):
        super().__init__()
        self.n_channels = n_channels
        self.tile_size = tile_size
        self.latent_dim = latent_dim
        self.n_classes = n_classes

        # Encoder: conv layers that reduce spatial dims
        self.encoder = nn.Sequential(
            nn.Conv2d(n_channels, 32, 3, stride=2, padding=1),
            nn.ReLU(),
            nn.Conv2d(32, 64, 3, stride=2, padding=1),
            nn.ReLU(),
            nn.Conv2d(64, 128, 3, stride=2, padding=1),
            nn.ReLU(),
            nn.AdaptiveAvgPool2d(1),  # -> (B, 128, 1, 1)
            nn.Flatten(),             # -> (B, 128)
        )

        self.fc_mu = nn.Linear(128, latent_dim)
        self.fc_logvar = nn.Linear(128, latent_dim)

        # Decoder: reconstruct spatial output
        # Compute decoded spatial size (tile_size // 8 due to 3x stride-2 convs)
        self.dec_spatial = max(tile_size // 8, 1)
        self.dec_fc = nn.Linear(latent_dim, 128 * self.dec_spatial * self.dec_spatial)

        self.decoder = nn.Sequential(
            nn.ConvTranspose2d(128, 64, 3, stride=2, padding=1, output_padding=1),
            nn.ReLU(),
            nn.ConvTranspose2d(64, 32, 3, stride=2, padding=1, output_padding=1),
            nn.ReLU(),
            nn.ConvTranspose2d(32, n_channels, 3, stride=2, padding=1, output_padding=1),
        )

        # Classifier head
        if n_classes > 0:
            self.classifier = nn.Linear(latent_dim, n_classes)
        else:
            self.classifier = None

    def encode(self, x):
        h = self.encoder(x)
        return self.fc_mu(h), self.fc_logvar(h)

    def reparameterize(self, mu, logvar):
        std = torch.exp(0.5 * logvar)
        eps = torch.randn_like(std)
        return mu + eps * std

    def decode(self, z):
        h = F.relu(self.dec_fc(z))
        h = h.view(-1, 128, self.dec_spatial, self.dec_spatial)
        h = self.decoder(h)
        # Crop or pad to original tile size if needed
        return h[:, :, :self.tile_size, :self.tile_size]

    def classify(self, z):
        if self.classifier is None:
            return None
        return self.classifier(z)

    def forward(self, x):
        mu, logvar = self.encode(x)
        z = self.reparameterize(mu, logvar)
        recon = self.decode(z)
        class_logits = self.classify(z)
        return recon, mu, logvar, z, class_logits


def vae_loss(recon, x, mu, logvar):
    """Gaussian reconstruction loss + KL divergence."""
    recon_loss = F.mse_loss(recon, x, reduction='mean')
    kl_loss = -0.5 * torch.mean(1 + logvar - mu.pow(2) - logvar.exp())
    return recon_loss, kl_loss


# ==================== Main Script ====================

# 1. Parse inputs
try:
    mode = input_mode
except NameError:
    mode = "measurements"

use_tiles = (mode == "tiles")

if use_tiles:
    # Tile mode: NCHW float32
    raw_tiles = tile_images.ndarray().copy().astype(np.float32)
    n_cells = raw_tiles.shape[0]
    img_channels = int(n_channels)
    img_tile_size = int(tile_size)
    logger.info("Tile mode: %d cells, %d channels, %dx%d tiles",
                n_cells, img_channels, img_tile_size, img_tile_size)
    data = None
    n_markers = 0
else:
    # Measurement mode
    data = measurements.ndarray().copy().astype(np.float32)
    n_cells, n_markers = data.shape
    raw_tiles = None
    img_channels = 0
    img_tile_size = 0

label_array = np.array(labels, dtype=np.int32)
class_names = list(label_names)
n_classes = len(class_names)

logger.info("Received %d cells x %d markers, %d classes", n_cells, n_markers, n_classes)

try:
    ldim = latent_dim
except NameError:
    ldim = 16

try:
    epochs = n_epochs
except NameError:
    epochs = 100

try:
    lr = learning_rate
except NameError:
    lr = 0.001

try:
    bs = batch_size
except NameError:
    bs = 128

try:
    sup_weight = supervision_weight
except NameError:
    sup_weight = 1.0

try:
    norm_method = normalization
except NameError:
    norm_method = "zscore"

try:
    save_path = model_save_path
except NameError:
    save_path = None

# Count labeled vs unlabeled
labeled_mask = label_array >= 0
n_labeled = int(labeled_mask.sum())
n_unlabeled = n_cells - n_labeled
has_labels = n_labeled > 0

logger.info("Labeled: %d, Unlabeled: %d, Classes: %d", n_labeled, n_unlabeled, n_classes)
if has_labels:
    for i, name in enumerate(class_names):
        count = int((label_array == i).sum())
        logger.info("  Class %d (%s): %d cells", i, name, count)

# 2. Normalize
task.update("Normalizing...", current=0, maximum=epochs + 2)

mean = None
std = None
dmin = None
dmax = None

if use_tiles:
    # Per-channel normalization for tiles
    if norm_method == "zscore":
        # Compute per-channel mean/std across all tiles
        mean = raw_tiles.mean(axis=(0, 2, 3), keepdims=True)  # (1, C, 1, 1)
        std = raw_tiles.std(axis=(0, 2, 3), keepdims=True)
        std[std == 0] = 1
        data_norm_tiles = (raw_tiles - mean) / std
        # Flatten for checkpoint storage
        mean = mean.squeeze()
        std = std.squeeze()
    elif norm_method == "minmax":
        dmin = raw_tiles.min(axis=(0, 2, 3), keepdims=True)
        dmax = raw_tiles.max(axis=(0, 2, 3), keepdims=True)
        drange = dmax - dmin
        drange[drange == 0] = 1
        data_norm_tiles = (raw_tiles - dmin) / drange
        dmin = dmin.squeeze()
        dmax = dmax.squeeze()
    else:
        data_norm_tiles = raw_tiles.copy()
    data_norm = None
else:
    # Measurement normalization
    if norm_method == "zscore":
        mean = data.mean(axis=0)
        std = data.std(axis=0)
        std[std == 0] = 1
        data_norm = (data - mean) / std
    elif norm_method == "minmax":
        dmin = data.min(axis=0)
        dmax = data.max(axis=0)
        drange = dmax - dmin
        drange[drange == 0] = 1
        data_norm = (data - dmin) / drange
    else:
        data_norm = data.copy()
    data_norm_tiles = None

# 3. Build model and optimizer
from model_utils import detect_device
device = detect_device()

if use_tiles:
    model = ConvCellVAE(img_channels, img_tile_size, ldim,
                        n_classes if has_labels else 0).to(device)
    data_tensor = torch.tensor(data_norm_tiles, dtype=torch.float32)
else:
    model = CellVAE(n_markers, ldim, n_classes if has_labels else 0).to(device)
    data_tensor = torch.tensor(data_norm, dtype=torch.float32)

optimizer = torch.optim.Adam(model.parameters(), lr=lr)

label_tensor = torch.tensor(label_array, dtype=torch.long)
dataset = TensorDataset(data_tensor, label_tensor)
loader = DataLoader(dataset, batch_size=bs, shuffle=True, drop_last=False)

# 4. Train
logger.info("Training VAE: latent_dim=%d, epochs=%d, lr=%g, batch_size=%d",
            ldim, epochs, lr, bs)

best_loss = float('inf')
for epoch in range(epochs):
    model.train()
    total_recon = 0.0
    total_kl = 0.0
    total_class = 0.0
    n_batches = 0
    correct = 0
    total_labeled = 0

    for batch_data, batch_labels in loader:
        batch_data = batch_data.to(device)
        batch_labels = batch_labels.to(device)

        recon, mu, logvar, z, class_logits = model(batch_data)
        recon_loss, kl_loss = vae_loss(recon, batch_data, mu, logvar)

        loss = recon_loss + kl_loss

        # Classification loss (only for labeled cells in this batch)
        if has_labels and class_logits is not None:
            labeled_in_batch = batch_labels >= 0
            if labeled_in_batch.any():
                class_loss = F.cross_entropy(
                    class_logits[labeled_in_batch],
                    batch_labels[labeled_in_batch])
                loss = loss + sup_weight * class_loss
                total_class += class_loss.item()

                preds = class_logits[labeled_in_batch].argmax(dim=1)
                correct += (preds == batch_labels[labeled_in_batch]).sum().item()
                total_labeled += labeled_in_batch.sum().item()

        optimizer.zero_grad()
        loss.backward()
        optimizer.step()

        total_recon += recon_loss.item()
        total_kl += kl_loss.item()
        n_batches += 1

    avg_recon = total_recon / n_batches
    avg_kl = total_kl / n_batches
    accuracy = correct / total_labeled if total_labeled > 0 else 0.0

    if (epoch + 1) % 10 == 0 or epoch == 0 or epoch == epochs - 1:
        msg = "Epoch %d/%d: recon=%.4f, KL=%.4f" % (epoch + 1, epochs, avg_recon, avg_kl)
        if has_labels:
            msg += ", acc=%.1f%%" % (accuracy * 100)
        logger.info(msg)
        task.update(msg, current=epoch + 1, maximum=epochs + 2)

# 5. Encode all cells and predict
task.update("Encoding all cells...", current=epochs + 1, maximum=epochs + 2)
model.eval()

with torch.no_grad():
    all_data = data_tensor.to(device)
    mu_all, _ = model.encode(all_data)
    latent = mu_all.cpu().numpy()  # Use mean (not sampled z) for deterministic output

    pred_labels = np.full(n_cells, -1, dtype=np.int32)
    pred_conf = np.zeros(n_cells, dtype=np.float32)

    if has_labels and model.classifier is not None:
        logits = model.classify(mu_all)
        probs = F.softmax(logits, dim=1).cpu().numpy()
        pred_labels = probs.argmax(axis=1).astype(np.int32)
        pred_conf = probs.max(axis=1).astype(np.float32)

logger.info("Encoding complete: %d cells x %d latent dims", n_cells, ldim)

# 6. Save model checkpoint
model_b64 = ""
if save_path:
    torch.save(model.state_dict(), save_path)
    logger.info("Model saved to %s", save_path)

# Also encode as base64 for Appose transfer
buf = io.BytesIO()
checkpoint_data = {
    'state_dict': model.state_dict(),
    'input_mode': mode,
    'latent_dim': ldim,
    'n_classes': n_classes,
    'class_names': class_names,
    'normalization': norm_method,
    'norm_params': {
        'mean': mean.tolist() if mean is not None else None,
        'std': std.tolist() if std is not None else None,
        'min': dmin.tolist() if dmin is not None else None,
        'max': dmax.tolist() if dmax is not None else None,
    },
}
if use_tiles:
    checkpoint_data['n_channels'] = img_channels
    checkpoint_data['tile_size'] = img_tile_size
else:
    checkpoint_data['n_markers'] = n_markers
    checkpoint_data['marker_names'] = list(marker_names)

torch.save(checkpoint_data, buf)
model_b64 = base64.b64encode(buf.getvalue()).decode('ascii')

# 7. Package outputs
task.update("Packaging results...", current=epochs + 2, maximum=epochs + 2)

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
task.outputs["final_recon_loss"] = float(avg_recon)
task.outputs["final_class_accuracy"] = float(accuracy) if has_labels else -1.0
task.outputs["model_state_base64"] = model_b64
task.outputs["label_names_json"] = json.dumps(class_names)

logger.info("[TEST FEATURE] Autoencoder training complete")
