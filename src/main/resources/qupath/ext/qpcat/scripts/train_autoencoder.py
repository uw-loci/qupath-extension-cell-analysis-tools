"""
[TEST FEATURE] Semi-supervised VAE for cell classification.

Trains a variational autoencoder with an optional classifier head on
cell marker measurements. When some cells have user-assigned labels,
the model learns a latent space that both reconstructs measurements
AND separates labeled cell types (semi-supervised).

Architecture follows the scANVI pattern (Xu et al. 2021, Molecular Systems Biology)
adapted for continuous protein measurements (Gaussian NLL instead of ZINB).

VAE best practices implemented:
- Gaussian NLL reconstruction with learned per-feature variance
- Cyclical KL annealing (Fu et al. 2019) with configurable beta_max
- Free bits regularization (Kingma et al. 2016) to prevent dim collapse
- LayerNorm in encoder/decoder (preferred over BatchNorm for VAEs)
- Logvar clamping [-10, 10] for numerical stability
- Label-fraction-scaled classification weight
- Unsupervised pre-training phase before classification loss
- Feature dropout augmentation
- Active units monitoring (latent dims with meaningful variance)
- Per-feature R-squared reconstruction quality metric
- Adam optimizer (no weight decay -- conflicts with KL regularization)
- ReduceLROnPlateau scheduler (standard for VAEs)

Training infrastructure (adapted from DL pixel classifier):
- Class weighting (inverse-frequency) for imbalanced cell populations
- Validation split (20%) with stratified sampling
- Early stopping with best-model restoration
- Mixed precision training (AMP) on CUDA
- Gradient clipping (max_norm=1.0)

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)  [measurement mode]
  tile_images: NDArray (N_cells x C x H x W, float32)   [tile mode]
  marker_names: list[str]
  labels: list[int] -- class index per cell (-1 = unlabeled)
  label_names: list[str] -- class name for each index
  input_mode: str ("measurements" or "tiles")
  latent_dim: int (default 16)
  n_epochs: int (default 100)
  learning_rate: float (default 0.001)
  batch_size: int (default 128)
  supervision_weight: float (default 1.0)
  normalization: str ("zscore", "minmax", "none")
  validation_split: float (default 0.2)
  early_stopping_patience: int (default 15, 0 = disabled)
  enable_class_weights: bool (default True)
  enable_augmentation: bool (default True)

Outputs (via task.outputs):
  latent_features: NDArray (N_cells x latent_dim, float32)
  predicted_labels: NDArray (N_cells,) int32
  prediction_confidence: NDArray (N_cells,) float32
  n_classes: int
  final_recon_loss: float
  final_class_accuracy: float
  best_val_accuracy: float
  best_epoch: int
  active_units: int
  model_state_base64: str -- base64-encoded state dict
"""
import sys
import json
import logging
import base64
import io
import math

logger = logging.getLogger("qpcat.autoencoder")

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, TensorDataset, Subset
from torch.nn.utils import clip_grad_norm_
from appose import NDArray as PyNDArray

# ==================== VAE Architecture ====================

# VAE best practices:
# - LayerNorm instead of BatchNorm (BatchNorm interacts poorly with
#   stochastic sampling; LayerNorm is per-sample and deterministic)
# - Logvar clamped to [-10, 10] for numerical stability
# - Gaussian NLL with learned variance for reconstruction
# - Classifier operates on sampled z (not mean) for robustness

LOGVAR_CLAMP_MIN = -10.0
LOGVAR_CLAMP_MAX = 10.0

# These defaults are overridden by values from QP-CAT Advanced Preferences
# (Edit > Preferences > QP-CAT: Autoencoder (Advanced))
FREE_BITS = 0.25
N_KL_CYCLES = 4
KL_BETA_MAX = 0.5
KL_RAMP_FRACTION = 0.8
PRETRAIN_FRACTION = 0.1


class CellVAE(nn.Module):
    """
    Variational Autoencoder with LayerNorm and optional semi-supervised head.

    Encoder: input -> LN -> 128 -> LReLU -> LN -> 64 -> LReLU -> (mu, logvar)
    Decoder: latent -> 64 -> LReLU -> LN -> 128 -> LReLU -> LN -> (recon_mu, recon_logvar)
    Classifier: latent -> n_classes (optional)
    """

    def __init__(self, n_markers, latent_dim, n_classes=0):
        super().__init__()
        self.n_markers = n_markers
        self.latent_dim = latent_dim
        self.n_classes = n_classes

        # Encoder with LayerNorm
        self.enc1 = nn.Linear(n_markers, 128)
        self.enc_ln1 = nn.LayerNorm(128)
        self.enc2 = nn.Linear(128, 64)
        self.enc_ln2 = nn.LayerNorm(64)
        self.fc_mu = nn.Linear(64, latent_dim)
        self.fc_logvar = nn.Linear(64, latent_dim)

        # Decoder with LayerNorm -- outputs both mean and logvar for Gaussian NLL
        self.dec1 = nn.Linear(latent_dim, 64)
        self.dec_ln1 = nn.LayerNorm(64)
        self.dec2 = nn.Linear(64, 128)
        self.dec_ln2 = nn.LayerNorm(128)
        self.dec_mu = nn.Linear(128, n_markers)
        self.dec_logvar = nn.Linear(128, n_markers)

        # Classifier head (only if supervised/semi-supervised)
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

    def reparameterize(self, mu, logvar):
        std = torch.exp(0.5 * logvar)
        eps = torch.randn_like(std)
        return mu + eps * std

    def decode(self, z):
        h = F.leaky_relu(self.dec_ln1(self.dec1(z)))
        h = F.leaky_relu(self.dec_ln2(self.dec2(h)))
        recon_mu = self.dec_mu(h)
        recon_logvar = torch.clamp(self.dec_logvar(h), LOGVAR_CLAMP_MIN, LOGVAR_CLAMP_MAX)
        return recon_mu, recon_logvar

    def classify(self, z):
        if self.classifier is None:
            return None
        return self.classifier(z)

    def forward(self, x):
        mu, logvar = self.encode(x)
        z = self.reparameterize(mu, logvar)
        recon_mu, recon_logvar = self.decode(z)
        class_logits = self.classify(z)
        return recon_mu, recon_logvar, mu, logvar, z, class_logits


class ConvCellVAE(nn.Module):
    """
    Convolutional VAE for tile-based cell classification.
    Uses LayerNorm where applicable (after flatten for FC layers).
    Conv layers use no normalization (small model, AdaptiveAvgPool handles scale).
    """

    def __init__(self, n_channels, tile_size, latent_dim, n_classes=0):
        super().__init__()
        self.n_channels = n_channels
        self.tile_size = tile_size
        self.latent_dim = latent_dim
        self.n_classes = n_classes

        self.encoder = nn.Sequential(
            nn.Conv2d(n_channels, 32, 3, stride=2, padding=1),
            nn.LeakyReLU(),
            nn.Conv2d(32, 64, 3, stride=2, padding=1),
            nn.LeakyReLU(),
            nn.Conv2d(64, 128, 3, stride=2, padding=1),
            nn.LeakyReLU(),
            nn.AdaptiveAvgPool2d(1),
            nn.Flatten(),
        )

        self.fc_mu = nn.Linear(128, latent_dim)
        self.fc_logvar = nn.Linear(128, latent_dim)

        self.dec_spatial = max(tile_size // 8, 1)
        self.dec_fc = nn.Linear(latent_dim, 128 * self.dec_spatial * self.dec_spatial)

        self.decoder = nn.Sequential(
            nn.ConvTranspose2d(128, 64, 3, stride=2, padding=1, output_padding=1),
            nn.LeakyReLU(),
            nn.ConvTranspose2d(64, 32, 3, stride=2, padding=1, output_padding=1),
            nn.LeakyReLU(),
            nn.ConvTranspose2d(32, n_channels, 3, stride=2, padding=1, output_padding=1),
        )

        if n_classes > 0:
            self.classifier = nn.Linear(latent_dim, n_classes)
        else:
            self.classifier = None

    def encode(self, x):
        h = self.encoder(x)
        mu = self.fc_mu(h)
        logvar = torch.clamp(self.fc_logvar(h), LOGVAR_CLAMP_MIN, LOGVAR_CLAMP_MAX)
        return mu, logvar

    def reparameterize(self, mu, logvar):
        std = torch.exp(0.5 * logvar)
        eps = torch.randn_like(std)
        return mu + eps * std

    def decode(self, z):
        h = F.leaky_relu(self.dec_fc(z))
        h = h.view(-1, 128, self.dec_spatial, self.dec_spatial)
        h = self.decoder(h)
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
        # ConvVAE uses MSE (no learned variance for spatial output)
        return recon, None, mu, logvar, z, class_logits


# ==================== Loss Functions ====================

def gaussian_nll_loss(recon_mu, recon_logvar, x):
    """
    Gaussian negative log-likelihood with learned per-feature variance.
    Better than MSE: lets the model learn heteroscedastic noise, which is
    important for fluorescence data where brighter signals have higher variance.
    """
    # 0.5 * (log(2*pi) + logvar + (x - mu)^2 / exp(logvar))
    return 0.5 * torch.mean(
        recon_logvar + (x - recon_mu).pow(2) / (torch.exp(recon_logvar) + 1e-8)
    )


def kl_divergence_free_bits(mu, logvar, free_bits=FREE_BITS):
    """
    KL divergence with free bits (Kingma et al. 2016).
    Each latent dimension gets a minimum KL of free_bits nats.
    Prevents individual dimensions from collapsing to the prior.
    """
    kl_per_dim = -0.5 * (1 + logvar - mu.pow(2) - logvar.exp())
    kl_per_dim = kl_per_dim.mean(dim=0)  # average over batch, keep dim
    kl_clamped = torch.clamp(kl_per_dim, min=free_bits)
    return kl_clamped.sum()


def get_kl_beta(epoch, total_epochs, n_cycles=N_KL_CYCLES, beta_max=KL_BETA_MAX,
                ramp_fraction=KL_RAMP_FRACTION):
    """
    Cyclical KL annealing (Fu et al. 2019).
    Ramps KL weight 0->beta_max over ramp_fraction of each cycle, then holds.
    Multiple cycles let the model re-explore before being regularized.
    """
    cycle_len = total_epochs / n_cycles
    position = (epoch % cycle_len) / cycle_len
    if position < ramp_fraction:
        return beta_max * (position / ramp_fraction)
    return beta_max


# ==================== Early Stopping ====================

class EarlyStopping:
    """Stops training when validation metric stops improving."""

    def __init__(self, patience=15, min_delta=0.001):
        self.patience = patience
        self.min_delta = min_delta
        self.counter = 0
        self.best_score = None
        self.best_epoch = 0
        self.best_state = None
        self.should_stop = False

    def step(self, score, epoch, model):
        if self.best_score is None or score > self.best_score + self.min_delta:
            self.best_score = score
            self.best_epoch = epoch
            self.best_state = {k: v.cpu().clone() for k, v in model.state_dict().items()}
            self.counter = 0
        else:
            self.counter += 1
            if self.counter >= self.patience:
                self.should_stop = True

    def restore_best(self, model):
        if self.best_state is not None:
            model.load_state_dict(self.best_state)
            logger.info("Restored best model from epoch %d (score=%.4f)",
                        self.best_epoch + 1, self.best_score)


# ==================== Data Augmentation ====================

def augment_tile(tile, flip_h=True, flip_v=True, rot90=True, elastic=False,
                 elastic_alpha=120.0, intensity_mode="none", intensity_amount=0.2,
                 gauss_noise=0.05):
    """
    Tile-mode augmentation using torch operations (no albumentations needed).
    Applied per-tile in the DataLoader. tile shape: (C, H, W).
    """
    import random
    # Spatial: flips and 90-degree rotation (same as DL pixel classifier)
    if flip_h and random.random() > 0.5:
        tile = torch.flip(tile, [2])  # flip W
    if flip_v and random.random() > 0.5:
        tile = torch.flip(tile, [1])  # flip H
    if rot90 and random.random() > 0.5:
        k = random.choice([1, 2, 3])
        tile = torch.rot90(tile, k, [1, 2])

    # Elastic deformation (simplified: random affine + noise)
    if elastic and random.random() > 0.7:
        # Simple approach: add small random displacement field
        _, h, w = tile.shape
        noise_scale = elastic_alpha / max(h, w) * 0.01
        dx = torch.randn(1, h, w) * noise_scale
        dy = torch.randn(1, h, w) * noise_scale
        grid_y, grid_x = torch.meshgrid(
            torch.linspace(-1, 1, h), torch.linspace(-1, 1, w), indexing='ij')
        grid = torch.stack([grid_x + dx.squeeze(), grid_y + dy.squeeze()], dim=-1).unsqueeze(0)
        tile = F.grid_sample(tile.unsqueeze(0), grid, mode='bilinear',
                             padding_mode='zeros', align_corners=True).squeeze(0)

    # Intensity augmentation
    if intensity_mode == "brightfield" and intensity_amount > 0:
        # Correlated: same brightness/contrast shift for all channels
        brightness = 1.0 + (random.random() - 0.5) * 2 * intensity_amount
        contrast = 1.0 + (random.random() - 0.5) * 2 * intensity_amount * 0.5
        tile = tile * contrast + (brightness - 1.0)
    elif intensity_mode == "fluorescence" and intensity_amount > 0:
        # Per-channel: independent scaling per channel
        c = tile.shape[0]
        scales = 1.0 + (torch.rand(c, 1, 1) - 0.5) * 2 * intensity_amount
        tile = tile * scales

    # Gaussian noise
    if gauss_noise > 0:
        tile = tile + torch.randn_like(tile) * gauss_noise

    return tile


class TileMmapDataset(torch.utils.data.Dataset):
    """Dataset that reads tiles from a memory-mapped file on demand."""

    def __init__(self, mmap_array, labels, norm_method, mean_stat, std_stat,
                 dmin_stat, dmax_stat, augment_config=None):
        self.mmap = mmap_array
        self.labels = labels
        self.norm_method = norm_method
        self.aug_config = augment_config  # dict or None
        if mean_stat is not None:
            self.mean = torch.tensor(mean_stat, dtype=torch.float32).view(-1, 1, 1)
            self.std = torch.tensor(std_stat, dtype=torch.float32).view(-1, 1, 1)
        else:
            self.mean = self.std = None
        if dmin_stat is not None:
            self.dmin = torch.tensor(dmin_stat, dtype=torch.float32).view(-1, 1, 1)
            drange = dmax_stat - dmin_stat
            drange[drange == 0] = 1
            self.drange = torch.tensor(drange, dtype=torch.float32).view(-1, 1, 1)
        else:
            self.dmin = self.drange = None

    def __len__(self):
        return len(self.mmap)

    def __getitem__(self, idx):
        tile = torch.tensor(np.array(self.mmap[idx]), dtype=torch.float32)
        if self.norm_method == "zscore" and self.mean is not None:
            tile = (tile - self.mean) / self.std
        elif self.norm_method == "minmax" and self.dmin is not None:
            tile = (tile - self.dmin) / self.drange
        # Apply tile augmentation if configured
        if self.aug_config is not None:
            tile = augment_tile(tile, **self.aug_config)
        label = self.labels[idx] if idx < len(self.labels) else -1
        return tile, torch.tensor(label, dtype=torch.long)


def augment_measurements(data, noise_std=0.02, scale_range=0.1, dropout_p=0.1):
    """
    Measurement-space augmentation:
    - Gaussian noise (2% of feature std)
    - Per-feature random scaling (+/- 10%)
    - Feature dropout (10% chance per feature zeroed out)
    """
    # Gaussian noise
    augmented = data + torch.randn_like(data) * noise_std
    # Per-feature scaling
    scale = 1.0 + (torch.rand(data.shape[1], device=data.device) - 0.5) * 2 * scale_range
    augmented = augmented * scale
    # Feature dropout (zero out random features)
    mask = torch.bernoulli(torch.full_like(data, 1.0 - dropout_p))
    augmented = augmented * mask
    return augmented


# ==================== Diagnostics ====================

def count_active_units(mu_all, threshold=0.01):
    """
    Count latent dimensions with meaningful variance (active units).
    Should be close to latent_dim. Low count = posterior collapse.
    """
    var_per_dim = mu_all.var(dim=0)
    return int((var_per_dim > threshold).sum().item())


def compute_r_squared(original, reconstructed):
    """Per-feature R-squared between input and reconstruction."""
    ss_res = ((original - reconstructed) ** 2).sum(dim=0)
    ss_tot = ((original - original.mean(dim=0)) ** 2).sum(dim=0)
    r2 = 1.0 - ss_res / (ss_tot + 1e-8)
    return r2.cpu().numpy()


# ==================== Main Script ====================

# 1. Parse inputs
try:
    mode = input_mode
except NameError:
    mode = "measurements"

use_tiles = (mode == "tiles")

if use_tiles:
    img_channels = int(n_channels)
    img_tile_size = int(tile_size)
    n_cells_tile = int(n_cells)
    # Memory-map tiles from temp file (little-endian float32, written by Java).
    # mmap_mode='r' avoids loading the entire file into RAM.
    tile_path = tile_file_path
    raw_tiles = np.memmap(tile_path, dtype='<f4', mode='r',
                          shape=(n_cells_tile, img_channels, img_tile_size, img_tile_size))
    n_cells = raw_tiles.shape[0]
    tile_size_mb = raw_tiles.nbytes / (1024 * 1024)
    logger.info("Tile mode: %d cells, %d channels, %dx%d tiles (%.0f MB, memory-mapped)",
                n_cells, img_channels, img_tile_size, img_tile_size, tile_size_mb)
    data = None
    n_markers = 0
else:
    data = measurements.ndarray().copy().astype(np.float32)
    n_cells, n_markers = data.shape
    raw_tiles = None
    img_channels = 0
    img_tile_size = 0

label_array = np.array(labels, dtype=np.int32)
class_names = list(label_names)
n_classes = len(class_names)

logger.info("Received %d cells, %d classes", n_cells, n_classes)

# Parse optional parameters with defaults
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
try:
    val_split = validation_split
except NameError:
    val_split = 0.2
try:
    es_patience = early_stopping_patience
except NameError:
    es_patience = 15
try:
    do_class_weights = enable_class_weights
except NameError:
    do_class_weights = True
try:
    do_augmentation = enable_augmentation
except NameError:
    do_augmentation = True

# Tile augmentation config
tile_aug_config = None
if do_augmentation:
    try:
        tile_aug_config = {
            'flip_h': bool(aug_flip_h) if 'aug_flip_h' in dir() else True,
            'flip_v': bool(aug_flip_v) if 'aug_flip_v' in dir() else True,
            'rot90': bool(aug_rotation_90) if 'aug_rotation_90' in dir() else True,
            'elastic': bool(aug_elastic) if 'aug_elastic' in dir() else False,
            'elastic_alpha': float(aug_elastic_alpha) if 'aug_elastic_alpha' in dir() else 120.0,
            'intensity_mode': str(aug_intensity_mode) if 'aug_intensity_mode' in dir() else 'none',
            'intensity_amount': float(aug_intensity_amount) if 'aug_intensity_amount' in dir() else 0.2,
            'gauss_noise': float(aug_gauss_noise) if 'aug_gauss_noise' in dir() else 0.05,
        }
    except Exception:
        tile_aug_config = None

# Advanced parameters from Preferences (override module-level defaults)
try:
    KL_BETA_MAX = float(kl_beta_max)
except NameError:
    pass
try:
    N_KL_CYCLES = int(kl_cycles)
except NameError:
    pass
try:
    KL_RAMP_FRACTION = float(kl_ramp_fraction)
except NameError:
    pass
try:
    FREE_BITS = float(free_bits)
except NameError:
    pass
try:
    PRETRAIN_FRACTION = float(pretrain_fraction)
except NameError:
    pass
try:
    aug_noise = float(aug_noise_std)
except NameError:
    aug_noise = 0.02
try:
    aug_scale = float(aug_scale_range)
except NameError:
    aug_scale = 0.1
try:
    aug_drop = float(aug_dropout_p)
except NameError:
    aug_drop = 0.1
try:
    grad_clip = float(grad_clip_norm)
except NameError:
    grad_clip = 1.0
try:
    lr_sched_factor = float(lr_scheduler_factor)
except NameError:
    lr_sched_factor = 0.5
try:
    lr_sched_patience = int(lr_scheduler_patience)
except NameError:
    lr_sched_patience = 10

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

mean_stat = None
std_stat = None
dmin_stat = None
dmax_stat = None

if use_tiles:
    # For tiles: compute normalization stats by streaming through mmap,
    # but don't create a full normalized copy (too much memory).
    # Normalization is applied per-batch in the training loop instead.
    if norm_method == "zscore":
        # Compute per-channel mean/std by streaming in chunks
        chunk_size = 1000
        ch_sum = np.zeros(img_channels, dtype=np.float64)
        ch_sq_sum = np.zeros(img_channels, dtype=np.float64)
        for i in range(0, n_cells, chunk_size):
            chunk = np.array(raw_tiles[i:min(i + chunk_size, n_cells)], dtype=np.float32)
            ch_sum += chunk.sum(axis=(0, 2, 3))
            ch_sq_sum += (chunk ** 2).sum(axis=(0, 2, 3))
        n_pixels = n_cells * img_tile_size * img_tile_size
        mean_stat = (ch_sum / n_pixels).astype(np.float32)
        std_stat = np.sqrt(ch_sq_sum / n_pixels - mean_stat ** 2).astype(np.float32)
        std_stat[std_stat == 0] = 1
        logger.info("Tile normalization (zscore): mean=%s, std=%s",
                    mean_stat.tolist(), std_stat.tolist())
    elif norm_method == "minmax":
        chunk_size = 1000
        ch_min = np.full(img_channels, np.inf, dtype=np.float32)
        ch_max = np.full(img_channels, -np.inf, dtype=np.float32)
        for i in range(0, n_cells, chunk_size):
            chunk = np.array(raw_tiles[i:min(i + chunk_size, n_cells)], dtype=np.float32)
            ch_min = np.minimum(ch_min, chunk.min(axis=(0, 2, 3)))
            ch_max = np.maximum(ch_max, chunk.max(axis=(0, 2, 3)))
        dmin_stat = ch_min
        dmax_stat = ch_max
    else:
        pass  # no normalization
    # raw_tiles stays as mmap; normalization applied per-batch in training loop
    data_norm = None
else:
    if norm_method == "zscore":
        mean_stat = data.mean(axis=0)
        std_stat = data.std(axis=0)
        std_stat[std_stat == 0] = 1
        data_norm = (data - mean_stat) / std_stat
    elif norm_method == "minmax":
        dmin_stat = data.min(axis=0)
        dmax_stat = data.max(axis=0)
        drange = dmax_stat - dmin_stat
        drange[drange == 0] = 1
        data_norm = (data - dmin_stat) / drange
    else:
        data_norm = data.copy()
    data_norm_tiles = None

# 3. Compute class weights (inverse-frequency)
class_weight_tensor = None
if has_labels and do_class_weights and n_classes > 1:
    class_counts = np.array([int((label_array == i).sum()) for i in range(n_classes)])
    class_counts = np.maximum(class_counts, 1)
    weights = 1.0 / class_counts
    weights = weights / weights.sum() * n_classes
    class_weight_tensor = torch.tensor(weights, dtype=torch.float32)
    logger.info("Class weights: %s", {name: "%.2f" % w for name, w in zip(class_names, weights)})

# Scale supervision weight by label fraction (scANVI practice)
# This accounts for classification loss only applying to labeled cells
effective_sup_weight = sup_weight
if has_labels and n_labeled < n_cells:
    effective_sup_weight = sup_weight * (n_cells / max(n_labeled, 1))
    logger.info("Supervision weight scaled by label fraction: %.2f -> %.2f",
                sup_weight, effective_sup_weight)

# Pre-training phase: unsupervised epochs before classification loss
pretrain_epochs = int(epochs * PRETRAIN_FRACTION) if has_labels else 0
logger.info("Pre-training (unsupervised): %d epochs", pretrain_epochs)

# 4. Train/validation split
# detect_device() is available from model_utils loaded during init
device = detect_device()

if use_tiles:
    # Don't load all tiles into a tensor -- use a Dataset that reads from mmap
    # and normalizes per-batch
    data_tensor = None  # handled by TileDataset below
else:
    data_tensor = torch.tensor(data_norm, dtype=torch.float32)

label_tensor = torch.tensor(label_array, dtype=torch.long)

# Build dataset
if use_tiles:
    full_dataset = TileMmapDataset(raw_tiles, label_array, norm_method,
                                    mean_stat, std_stat, dmin_stat, dmax_stat,
                                    augment_config=tile_aug_config if do_augmentation else None)
else:
    full_dataset = TensorDataset(data_tensor, label_tensor)

if val_split > 0 and n_cells > 20:
    from sklearn.model_selection import train_test_split
    indices = np.arange(n_cells)
    strat_labels = label_array.copy()
    strat_labels[strat_labels < 0] = n_classes
    try:
        train_idx, val_idx = train_test_split(
            indices, test_size=val_split, random_state=42, stratify=strat_labels)
    except ValueError:
        train_idx, val_idx = train_test_split(
            indices, test_size=val_split, random_state=42)
    train_dataset = Subset(full_dataset, train_idx)
    val_dataset = Subset(full_dataset, val_idx)
    n_val = len(val_idx)
    logger.info("Train/val split: %d/%d (%.0f%% validation)", len(train_idx), n_val, val_split * 100)
else:
    train_dataset = full_dataset
    val_dataset = None
    n_val = 0

train_loader = DataLoader(train_dataset, batch_size=bs, shuffle=True, drop_last=False,
                           num_workers=0, pin_memory=(device == "cuda"))
val_loader = DataLoader(val_dataset, batch_size=bs, shuffle=False,
                         num_workers=0) if val_dataset else None

# 5. Build model and optimizer
if use_tiles:
    model = ConvCellVAE(img_channels, img_tile_size, ldim,
                        n_classes if has_labels else 0).to(device)
else:
    model = CellVAE(n_markers, ldim, n_classes if has_labels else 0).to(device)

# Adam without weight decay (KL already regularizes; weight decay conflicts)
optimizer = torch.optim.Adam(model.parameters(), lr=lr, eps=1e-8)

# ReduceLROnPlateau (standard for VAEs, monitors val loss or train loss)
scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
    optimizer, mode='min', factor=lr_sched_factor, patience=lr_sched_patience, min_lr=1e-6)

# Mixed precision (CUDA only)
use_amp = (device == "cuda")
scaler = torch.amp.GradScaler("cuda") if use_amp else None

# Early stopping
early_stopper = EarlyStopping(patience=es_patience) if es_patience > 0 else None

if class_weight_tensor is not None:
    class_weight_tensor = class_weight_tensor.to(device)

logger.info("Training config: latent=%d, epochs=%d (pretrain=%d), lr=%g, bs=%d, "
            "val=%.0f%%, beta_max=%.2f, free_bits=%.2f, "
            "early_stop=%s, class_weights=%s, augment=%s, AMP=%s",
            ldim, epochs, pretrain_epochs, lr, bs, val_split * 100,
            KL_BETA_MAX, FREE_BITS,
            "patience=%d" % es_patience if es_patience > 0 else "off",
            "on" if class_weight_tensor is not None else "off",
            "on" if do_augmentation else "off",
            "on" if use_amp else "off")

# 6. Training loop
best_val_acc = -1.0
best_epoch = 0
train_acc = 0.0
avg_recon = 0.0
avg_kl = 0.0
n_active = 0

for epoch in range(epochs):
    # Compute cyclical KL beta for this epoch
    kl_beta = get_kl_beta(epoch, epochs)

    # Classification active after pre-training phase
    classify_active = has_labels and epoch >= pretrain_epochs

    # --- Train phase ---
    model.train()
    total_recon = 0.0
    total_kl = 0.0
    train_correct = 0
    train_labeled = 0
    n_batches = 0

    for batch_data, batch_labels in train_loader:
        batch_data = batch_data.to(device)
        batch_labels = batch_labels.to(device)

        # Augmentation target: original (clean) data for reconstruction
        target_data = batch_data
        if do_augmentation and not use_tiles:
            batch_data = augment_measurements(batch_data, aug_noise, aug_scale, aug_drop)

        with torch.amp.autocast(device, enabled=use_amp):
            if use_tiles:
                recon, _, mu, logvar, z, class_logits = model(batch_data)
                recon_loss = F.mse_loss(recon, target_data, reduction='mean')
            else:
                recon_mu, recon_logvar, mu, logvar, z, class_logits = model(batch_data)
                recon_loss = gaussian_nll_loss(recon_mu, recon_logvar, target_data)

            kl_loss = kl_divergence_free_bits(mu, logvar)
            loss = recon_loss + kl_beta * kl_loss

            # Classification loss (only after pre-training)
            if classify_active and class_logits is not None:
                labeled_in_batch = batch_labels >= 0
                if labeled_in_batch.any():
                    class_loss = F.cross_entropy(
                        class_logits[labeled_in_batch],
                        batch_labels[labeled_in_batch],
                        weight=class_weight_tensor)
                    loss = loss + effective_sup_weight * class_loss

                    preds = class_logits[labeled_in_batch].argmax(dim=1)
                    train_correct += (preds == batch_labels[labeled_in_batch]).sum().item()
                    train_labeled += labeled_in_batch.sum().item()

        optimizer.zero_grad()
        if use_amp:
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            clip_grad_norm_(model.parameters(), max_norm=grad_clip)
            scaler.step(optimizer)
            scaler.update()
        else:
            loss.backward()
            clip_grad_norm_(model.parameters(), max_norm=grad_clip)
            optimizer.step()

        total_recon += recon_loss.item()
        total_kl += kl_loss.item()
        n_batches += 1

    avg_recon = total_recon / max(n_batches, 1)
    avg_kl = total_kl / max(n_batches, 1)
    train_acc = train_correct / max(train_labeled, 1)

    # Step scheduler on average loss
    scheduler.step(avg_recon + avg_kl)

    # --- Validation phase ---
    val_acc = -1.0
    if val_loader and has_labels and classify_active:
        model.eval()
        val_correct = 0
        val_labeled = 0
        with torch.no_grad():
            for batch_data, batch_labels in val_loader:
                batch_data = batch_data.to(device)
                batch_labels = batch_labels.to(device)
                if use_tiles:
                    _, _, mu_v, _, _, class_logits_v = model(batch_data)
                else:
                    _, _, mu_v, _, _, class_logits_v = model(batch_data)
                if class_logits_v is not None:
                    labeled_v = batch_labels >= 0
                    if labeled_v.any():
                        preds_v = class_logits_v[labeled_v].argmax(dim=1)
                        val_correct += (preds_v == batch_labels[labeled_v]).sum().item()
                        val_labeled += labeled_v.sum().item()
        val_acc = val_correct / max(val_labeled, 1)

        if val_acc > best_val_acc:
            best_val_acc = val_acc
            best_epoch = epoch

    # Early stopping
    if early_stopper is not None:
        if val_acc >= 0:
            early_stopper.step(val_acc, epoch, model)
        else:
            early_stopper.step(-avg_recon, epoch, model)

    # Logging
    if (epoch + 1) % 10 == 0 or epoch == 0 or epoch == epochs - 1:
        msg = "Epoch %d/%d: recon=%.4f, KL=%.4f, beta=%.3f" % (
            epoch + 1, epochs, avg_recon, avg_kl, kl_beta)
        if classify_active and has_labels:
            msg += ", train_acc=%.1f%%" % (train_acc * 100)
        elif epoch < pretrain_epochs:
            msg += " [pretrain]"
        if val_acc >= 0:
            msg += ", val_acc=%.1f%%" % (val_acc * 100)
        current_lr = optimizer.param_groups[0]['lr']
        msg += ", lr=%.2e" % current_lr
        logger.info(msg)
        task.update(msg, current=epoch + 1, maximum=epochs + 2)

    if early_stopper and early_stopper.should_stop:
        logger.info("Early stopping at epoch %d (patience=%d, best=%d)",
                    epoch + 1, es_patience, early_stopper.best_epoch + 1)
        task.update("Early stopping at epoch %d (best: epoch %d)" %
                    (epoch + 1, early_stopper.best_epoch + 1))
        break

# Restore best model
if early_stopper and early_stopper.best_state is not None:
    early_stopper.restore_best(model)
    best_epoch = early_stopper.best_epoch

accuracy = train_acc

# 7. Encode ALL cells and predict
task.update("Encoding all cells...", current=epochs + 1, maximum=epochs + 2)
model.eval()

with torch.no_grad():
    # Encode in batches to handle large datasets
    encode_loader = DataLoader(full_dataset, batch_size=bs * 2, shuffle=False, num_workers=0)
    all_mu = []
    all_logits = []

    for batch_data_enc, _ in encode_loader:
        batch_data_enc = batch_data_enc.to(device)
        mu_batch, _ = model.encode(batch_data_enc)
        all_mu.append(mu_batch.cpu())
        if has_labels and model.classifier is not None:
            logits_batch = model.classify(mu_batch)
            all_logits.append(logits_batch.cpu())

    mu_all = torch.cat(all_mu, dim=0)
    latent = mu_all.numpy()

    pred_labels = np.full(n_cells, -1, dtype=np.int32)
    pred_conf = np.zeros(n_cells, dtype=np.float32)

    if has_labels and model.classifier is not None and all_logits:
        logits_all = torch.cat(all_logits, dim=0)
        probs = F.softmax(logits_all, dim=1).numpy()
        pred_labels = probs.argmax(axis=1).astype(np.int32)
        pred_conf = probs.max(axis=1).astype(np.float32)

    # Diagnostics
    n_active = count_active_units(mu_all)
    logger.info("Active latent units: %d / %d", n_active, ldim)
    if n_active < ldim * 0.5:
        logger.warning("Low active units (%d/%d) -- possible posterior collapse. "
                       "Try reducing latent_dim or increasing epochs.", n_active, ldim)

    # Per-feature reconstruction quality (measurement mode only)
    if not use_tiles:
        all_data = data_tensor.to(device)
        recon_mu_all, _ = model.decode(mu_all.to(device))
        r2 = compute_r_squared(all_data, recon_mu_all)
        mean_r2 = float(r2.mean())
        logger.info("Reconstruction R-squared: mean=%.3f, min=%.3f, max=%.3f",
                    mean_r2, float(r2.min()), float(r2.max()))
        poor_features = [n for n, v in zip(list(marker_names), r2) if v < 0.5]
        if poor_features:
            logger.warning("Poorly reconstructed features (R2<0.5): %s", poor_features)

logger.info("Encoding complete: %d cells x %d latent dims", n_cells, ldim)

# 7b. Release tile memmap so Java can delete the temp file (Windows file locking)
if use_tiles and raw_tiles is not None:
    # Close the memmap file handle explicitly
    if hasattr(raw_tiles, '_mmap') and raw_tiles._mmap is not None:
        raw_tiles._mmap.close()
    del raw_tiles
    # Also release dataset/dataloader references
    del full_dataset, train_loader
    if val_loader is not None:
        del val_loader
    import gc
    gc.collect()
    logger.info("Tile memmap released for temp file cleanup")

# 8. Save model checkpoint
model_b64 = ""
if save_path:
    torch.save(model.state_dict(), save_path)
    logger.info("Model saved to %s", save_path)

buf = io.BytesIO()
checkpoint_data = {
    'state_dict': model.state_dict(),
    'input_mode': mode,
    'latent_dim': ldim,
    'n_classes': n_classes,
    'class_names': class_names,
    'normalization': norm_method,
    'norm_params': {
        'mean': mean_stat.tolist() if mean_stat is not None else None,
        'std': std_stat.tolist() if std_stat is not None else None,
        'min': dmin_stat.tolist() if dmin_stat is not None else None,
        'max': dmax_stat.tolist() if dmax_stat is not None else None,
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

# 9. Package outputs
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
task.outputs["best_val_accuracy"] = float(best_val_acc) if best_val_acc >= 0 else -1.0
task.outputs["best_epoch"] = int(best_epoch + 1)
task.outputs["active_units"] = n_active
task.outputs["model_state_base64"] = model_b64
task.outputs["label_names_json"] = json.dumps(class_names)

logger.info("[TEST FEATURE] Autoencoder training complete (best epoch: %d, active units: %d/%d)",
            best_epoch + 1, n_active, ldim)
