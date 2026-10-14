# Setup

First-time only. QP-CAT downloads its own Python environment (~1.5-2.5 GB); the
rest of the menu stays hidden until it exists.

## Install

1. **Extensions > QP-CAT > Setup & help > Set up analysis environment (first run)...**
2. Set the location and compute variant if you need to (below) -- both are easier to
   choose now than to change later.
3. **Setup Environment**, then wait. 5-15 minutes, mostly download.
4. Close the dialog when it reports success. The rest of the menu appears.

## Where the environment goes

Default is `~/.local/share/appose/qupath-qpcat/` -- right on most machines, including
Windows, where it resolves under `%USERPROFILE%`.

Change it when the home directory is quota-limited. On HPC and managed desktops an
install this size fails there with `Quota exceeded (os error 122)`, and the extension
cannot be used at all. Point it at scratch or project storage instead.

Changing the location builds a **new** environment. The old one is left alone, and
QP-CAT offers to remove it only after the new one is built and verified -- never
automatically, because until then the old one is the only working copy.

After setup: **Edit > Preferences > QP-CAT: Python environment > Environment location**.

## CPU or GPU

**Only the autoencoder uses a GPU.** Clustering, embedding, phenotyping, spatial
statistics and cellular neighborhoods run on the CPU whatever hardware you have.

| Variant | Installs on | GPU used for |
|---|---|---|
| **CPU** (default) | any machine | nothing |
| **GPU / CUDA** | **only** machines with an NVIDIA GPU | the autoencoder |

If your work is clustering and spatial statistics, stay on CPU. The GPU variant costs a
several-GB download and buys nothing.

These cannot be one environment that uses a GPU when it finds one. A pixi lockfile pins
exact package builds, a CPU build of PyTorch cannot use a GPU, and pixi validates the
`__cuda` virtual package on **every** install -- which is every QuPath launch. So a
CUDA-pinned environment on a machine without an NVIDIA GPU does not run slowly, it
**refuses to start**. That is what blocked an HPC deployment
([issue #15](https://github.com/uw-loci/qupath-extension-cell-analysis-tools/issues/15)),
and why CPU is the default.

Switching builds a separate environment. Both live side by side, so switching back does
not rebuild.

After setup: **Edit > Preferences > QP-CAT: Python environment > Compute variant**.

### Which GPU, on a multi-GPU machine

On a machine with more than one NVIDIA card, **Edit > Preferences > QP-CAT: Python
environment > GPU device (all Appose extensions)** chooses the card the Python worker uses.
The default, `auto`, picks the card with the most free memory when the worker first starts:
the compute card on a desktop that also has a display card, the least-loaded card on a
shared server. A number is the index `nvidia-smi` shows. The cards found and the one chosen
are written to the QuPath log at worker start. Changes apply to workers started after the
change, so restart QuPath to move a running worker. If QuPath was started by a cluster
scheduler that sets `CUDA_VISIBLE_DEVICES`, `auto` keeps that allocation.

### What a GPU actually accelerates

| Operation | Device |
|---|---|
| Autoencoder, tile mode | **GPU** -- the real case. 64x64 tiles at 10,000 cells take 1-3 hours on CPU. |
| Autoencoder, measurement mode | GPU, marginally. The network is small. |
| Clustering (Leiden, KMeans, HDBSCAN, GMM, agglomerative) | CPU. No GPU code path exists. |
| BANKSY, UMAP | CPU. pybanksy and umap-learn are CPU-only. |
| Spatial statistics, cellular neighborhoods | CPU. Permutation tests are CPU-bound. |
| Phenotyping | CPU. Threshold arithmetic. |

UMAP, neighbour-graph construction and permutation statistics all have GPU
implementations elsewhere (RAPIDS `cuml`, `cugraph`). Adopting any of them is tracked
internally; nothing is promised here beyond the table.

### "I have a GPU but it is not being used"

Every autoencoder run logs the device it chose, and distinguishes the two reasons it
falls back to CPU -- the fixes are completely different:

- **`torch.version.cuda is None`** -- you are on the CPU environment. Switch the Compute
  variant preference and let the GPU environment build.
- **a CUDA build that cannot see a usable GPU** -- the environment is right; the driver
  or the device is the problem.

## When setup fails

Check the internet connection and free space (~2.5 GB). **Setup & help > Rebuild
analysis environment** starts over from scratch.

**Windows file lock** (`failed to link ... os error 32 ... being used by another
process`) -- something is holding a file open inside the environment. QP-CAT logs a full
PowerShell recovery script; the short version is: close QuPath entirely, kill any
leftover `java.exe` / `python.exe`, delete `%USERPROFILE%\.local\share\appose\qupath-qpcat\.pixi`
and `pixi.lock`, then relaunch. Add `%USERPROFILE%\.local\share\appose\` as an
antivirus exclusion if it recurs. Reboot if deleting still fails -- that releases every
handle.

**Windows `DLL load failed ... %1 is not a valid Win32 application`** (reported against
`_igraph`, but the same applies to any module). Windows raises this when a binary in the
load chain is not a valid 64-bit `.dll`/`.pyd` -- truncated, empty, or the wrong
architecture. It names the module you imported, **not the file that is actually broken**:
`_igraph.pyd` itself is usually fine and one of the DLLs it depends on
(`igraph.dll`, and in turn `glpk`, `gmp`, `libxml2`, BLAS/LAPACK, the MSVC runtime) is
the bad one. Checking only the `.pyd` will tell you it is 64-bit and healthy.

**Try the Visual C++ Redistributable first.** This is the confirmed cause of the one
report we have: conda-forge's `python-igraph` requires `vc14_runtime >= 14.44.35208`,
and an older system MSVC runtime produces exactly this error. Install the latest
[Visual C++ Redistributable (x64)](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist),
then rebuild the environment as below. It is a two-minute check and it does not depend
on working out which file is at fault.

If that does not do it, find the real culprit by scanning every binary in the
environment:

```
"%USERPROFILE%\.local\share\appose\qupath-qpcat\.pixi\envs\default\python.exe" tools\qpcat_check_dlls.py
```

(`tools/qpcat_check_dlls.py` is in the QP-CAT repository; it uses only the standard
library.) It prints any file that is empty, truncated, or not x64.

If it finds something, **clear the package cache as well as the environment.** The
environment is rebuilt from cached downloads, so deleting only `.pixi` re-extracts the
same corrupt bytes and the failure returns unchanged:

1. Close QuPath; kill any leftover `java.exe` / `python.exe`.
2. Delete `%USERPROFILE%\.local\share\appose\qupath-qpcat\`.
3. Delete the pixi/rattler package cache -- `%LOCALAPPDATA%\rattler` by default, or
   whatever `PIXI_CACHE_DIR` / `RATTLER_CACHE_DIR` is set to.
4. Add `%USERPROFILE%\.local\share\appose\` as an antivirus exclusion. Real-time
   scanners truncating or quarantining a DLL mid-extract is a common cause.
5. Relaunch and run **Setup & help > Rebuild analysis environment**.

If the scan reports no problems, the environment is intact -- which points back at the
MSVC runtime above, since a system DLL is not inside the environment for the scan to
find.

**Stale `pkg_resources` / `xarray_schema` import on launch** is a different failure.
QP-CAT detects it, wipes the environment and asks you to restart; the second launch
rebuilds.

More failures: [Troubleshooting](troubleshooting.md).
