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


# 1. Parse inputs
task.update("Loading model checkpoint...", current=0, maximum=3)

data = measurements.ndarray().copy().astype(np.float32)
n_cells, n_markers = data.shape
logger.info("Received %d cells x %d markers for inference", n_cells, n_markers)

# Decode checkpoint
ckpt_bytes = base64.b64decode(model_state_base64)
buf = io.BytesIO(ckpt_bytes)
checkpoint = torch.load(buf, map_location='cpu', weights_only=False)

ckpt_n_markers = checkpoint['n_markers']
ldim = checkpoint['latent_dim']
n_classes = checkpoint['n_classes']
class_names = checkpoint['class_names']
norm_method = checkpoint['normalization']
norm_params = checkpoint['norm_params']
ckpt_markers = checkpoint['marker_names']

logger.info("Model: %d markers, latent_dim=%d, %d classes", ckpt_n_markers, ldim, n_classes)

# Validate marker compatibility
input_markers = list(marker_names)
if input_markers != ckpt_markers:
    logger.warning("Marker names differ from training. "
                    "Training: %s, Input: %s", ckpt_markers, input_markers)
    if n_markers != ckpt_n_markers:
        raise ValueError("Marker count mismatch: model expects %d, got %d" %
                         (ckpt_n_markers, n_markers))

# 2. Normalize using training statistics
task.update("Normalizing and encoding...", current=1, maximum=3)

if norm_method == "zscore" and norm_params.get('mean') is not None:
    mean = np.array(norm_params['mean'], dtype=np.float32)
    std = np.array(norm_params['std'], dtype=np.float32)
    std[std == 0] = 1
    data_norm = (data - mean) / std
elif norm_method == "minmax" and norm_params.get('min') is not None:
    dmin = np.array(norm_params['min'], dtype=np.float32)
    dmax = np.array(norm_params['max'], dtype=np.float32)
    drange = dmax - dmin
    drange[drange == 0] = 1
    data_norm = (data - dmin) / drange
else:
    data_norm = data.copy()

# 3. Load model and run inference
device = detect_device()
model = CellVAE(ckpt_n_markers, ldim, n_classes).to(device)
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
