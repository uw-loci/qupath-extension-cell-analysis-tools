# GPU acceleration: what uses it, and what does not

**Short version: today only the autoencoder uses a GPU.** Everything else in QP-CAT --
clustering, embedding, phenotyping, spatial statistics, cellular neighborhoods -- runs on
the CPU regardless of what hardware you have. Choosing the GPU environment will not make
them faster.

## Choosing an environment

QP-CAT ships **two** Python environments, selected in
**Edit > Preferences > QP-CAT: Python environment > Compute variant**:

| Variant | Installs on | GPU used |
|---|---|---|
| **CPU** (default) | any machine | never |
| **GPU / CUDA** | **only** machines with an NVIDIA GPU | autoencoder only |

They cannot be a single environment that "uses the GPU when one is present". A pixi
lockfile pins exact package builds, and a CPU build of PyTorch cannot use a GPU at all.
Worse, pixi validates the `__cuda` virtual package on **every** install -- which is every
QuPath launch -- so a CUDA-pinned environment does not merely run slowly on a machine
without an NVIDIA GPU: **it refuses to start.** That is what blocked an HPC deployment
([issue #15](https://github.com/uw-loci/qupath-extension-cell-analysis-tools/issues/15)),
and it is why CPU is the default.

Switching variants builds a **separate** environment (several GB, and a fresh download).
The two live side by side under different names, so switching back does not rebuild.

## What actually benefits

| Operation | Device | Notes |
|---|---|---|
| **Autoencoder -- tile mode** | **GPU** | The real case. README's own timings call a GPU *Required* for 64x64 tiles at 10,000 cells (1-3 hours on CPU). |
| **Autoencoder -- measurement mode** | GPU, marginally | Minimal to moderate benefit; the network is small. |
| Clustering (Leiden, KMeans, HDBSCAN, GMM, agglomerative) | CPU | scikit-learn / igraph / leidenalg. No GPU code path exists. |
| BANKSY | CPU | pybanksy is CPU-only. |
| UMAP / embedding | CPU | umap-learn is CPU-only. |
| Spatial statistics (Ripley, Geary, Moran, co-occurrence) | CPU | squidpy; permutation tests are CPU-bound. |
| Cellular neighborhoods | CPU | |
| Phenotyping | CPU | Threshold arithmetic; trivially fast. |

If your work is clustering and spatial statistics -- which is most of QP-CAT -- **stay on
the CPU environment.** The GPU variant costs you a several-GB download and buys nothing.

## Diagnosing "I have a GPU but it isn't being used"

QP-CAT logs the device it selected at the start of every autoencoder run, and distinguishes
the two reasons it can fall back to CPU:

- **`torch.version.cuda is None`** -- you are on the **CPU environment**. Switch the
  Compute variant preference and let the GPU environment build.
- **a CUDA build that cannot see a usable GPU** -- the environment is right and the driver
  or the device is the problem.

The distinction matters because the fixes are completely different, which is why the log
says which one it is rather than just "using CPU".

## Wanted: more of QP-CAT on the GPU

The table above is thin, and that is a limitation rather than a design choice. UMAP,
neighbour-graph construction and the permutation-based spatial statistics all have GPU
implementations in the wider ecosystem (RAPIDS `cuml`, `cugraph`). Adopting any of them is
tracked in `claude-reports/TODO_LIST.md`; nothing is promised here that is not in the table.
