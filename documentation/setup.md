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

**Only the autoencoder and Harmony batch correction use a GPU through this variant.** Clustering,
embedding, phenotyping, spatial statistics and cellular neighborhoods run on the CPU whatever
hardware you have. The one optional exception is Leiden's neighbor graph and partition, which
can run on the GPU in a separate RAPIDS environment you install yourself; see
[Neighbor graph and Leiden on the GPU](#neighbor-graph-and-leiden-on-the-gpu).

| Variant | Installs on | GPU used for |
|---|---|---|
| **CPU** (default) | any machine | nothing |
| **GPU / CUDA** | **only** machines with an NVIDIA GPU | the autoencoder, and Harmony when the run fits in GPU memory |

If your work is clustering and spatial statistics without batch correction, stay on CPU. The
GPU variant costs a several-GB download and speeds up only the autoencoder and Harmony.

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

### One OpenMP thread on Windows

On Windows the worker starts with `OMP_NUM_THREADS=1`
([issue #24](https://github.com/uw-loci/qupath-extension-cell-analysis-tools/issues/24):
Compute Thresholds hung there until the variable was set, and a limit applied inside the task
did not help). scikit-learn (including its HDBSCAN), MKL and CPU PyTorch then run on one
thread; UMAP runs on numba's own pool and is not affected. The switch is **Edit > Preferences > QP-CAT: Python
environment > Limit the Python worker to one OpenMP thread**, on by default for Windows and off
elsewhere; it applies the next time the worker starts, and a value you set yourself in the
system environment is left alone. **System Info...** shows what the worker was started with.

### Which GPU, on a multi-GPU machine

On a machine with more than one NVIDIA card, **Edit > Preferences > QP-CAT: Python
environment > GPU device (all Appose extensions)** chooses the card the Python worker uses.
The default, `auto`, picks the card with the most free memory when the worker first starts:
the compute card on a desktop that also has a display card, the least-loaded card on a
shared server. A number is the index `nvidia-smi` shows. The cards found and the one chosen
are written to the QuPath log at worker start. Changes apply to workers started after the
change, so restart QuPath to move a running worker. If QuPath was started by a cluster
scheduler that sets `CUDA_VISIBLE_DEVICES`, `auto` keeps that allocation.

### Neighbor graph and Leiden on the GPU

For a Leiden run, QP-CAT can build the neighbor graph and run Leiden in a separate RAPIDS
environment, in two steps that each get a fresh process, so the first one's card memory is
free before the second starts:

1. **Graph.** The neighbor search runs on the GPU: an exact brute-force search up to 400,000
   cells, NN-descent (cuVS, through rapids-singlecell) above. The fuzzy connectivities scanpy
   would compute are then built on top, on the CPU.
2. **Leiden.** The graph goes to cuGraph as a sparse matrix and is partitioned at every
   resolution the run asks for. The modularity of each partition is computed again from the
   graph and logged beside cuGraph's own value, which is sometimes higher than the partition
   it returns actually has.

The design follows the GPU pipeline that Clement Cazorla (INSERM) built and benchmarked for the
working group: 6 million cells by 25 markers, k=50, two resolutions, in 26 minutes on a 16 GB
card under Windows 11 and WSL2, against about 47 hours for a CPU pipeline on 4 million cells.

RAPIDS runs on Linux only. On Windows the process runs inside WSL2 and QuPath starts it
through `wsl.exe`; the matrix crosses as one file written into the WSL file system, the graph
and the labels come back the same way (about a second each way at 1 million cells).

#### Setup

1. Install WSL2 with a Linux distribution (Windows only), and an NVIDIA driver that supports
   CUDA 13 (`nvidia-smi` inside WSL shows the GPU).
2. Install [pixi](https://pixi.sh) (0.72 or later, the lock format the file uses), copy
   [documentation/gpu-graph/pixi.toml](gpu-graph/pixi.toml) and its `pixi.lock` into a
   folder on a local disk (on a networked home directory, use scratch), and run
   `pixi install --frozen` there. The environment is about 6 GB and pins RAPIDS 26.10, CUDA 13,
   Python 3.12. QP-CAT never installs, changes or removes it.
3. Under **Edit > Preferences > QP-CAT: Python environment**, two preferences:

   | Preference | Value |
   |---|---|
   | **GPU graph and Leiden: Python** | the environment's Python, `<folder>/.pixi/envs/default/bin/python`; on Windows the path inside WSL2, a plain absolute path with no spaces |
   | **GPU graph and Leiden: scratch folder** | where the three hand-over files go; blank means `$TMPDIR` when set, else a folder beside the environment on Linux, and `~/.cache/qpcat-gpu` inside WSL2 on Windows |
4. In **Find cell populations (clustering)...** with Leiden selected, tick **Neighbor graph and
   Leiden on the GPU (RAPIDS)**. The tick is remembered. The option only appears once the
   Python preference is set.

#### What to expect

- **Two memory limits, card and host.** A graph has about 1.44 x cells x k nonzeros on real
  data (1.64 on synthetic blobs). The card's peak is the Leiden step, about 45 bytes per
  nonzero; the graph step uses about a quarter of that. Host RAM peaks in the graph step,
  where the connectivities are built on the CPU: about 47 bytes per nonzero. The pre-run
  check warns when the card estimate is above 14 GB or the host estimate is close to what is
  free, which on Windows is the memory WSL2 is allowed, not the machine's. In practice host
  RAM is the ceiling under WSL2: a 19 GB WSL2 tops out near 4 million cells at k=50.
- **Measured** on a 16 GB RTX 5060 Ti under WSL2, synthetic 8-blob cells by 25 features, k=50,
  one resolution (2026-11-24):

  | Cells | Nonzeros | Card, Leiden step | Host RAM, graph step | Time, quiet machine | Time, shared machine |
  |---|---|---|---|---|---|
  | 500,000 | 41M | 1.8 GB | 2.3 GB | 32 s | 84 s |
  | 1,000,000 | 81M | 2.9 GB | 3.7 GB | 55 s | 87 s |
  | 2,000,000 | 163M | 5.9 GB | 7.5 GB | 1.7 min | 1.9 min |
  | 3,000,000 | 245M | 10.7 GB | 11.5 GB | 2.6 min | 3.0 min |

  The memory and the shared-machine times are from the shipped code, measured while other
  CPU-heavy jobs ran (the CPU part of the graph step is what slows). The quiet-machine times
  are from an earlier run the same day that differed only in how the graph reached cuGraph (that
  version needed 40-54 bytes per nonzero on the card, against 36-44 now). For comparison,
  the single-process version this replaced took 70 s, 3.0 min and 5.3 min at 1, 2 and 3
  million cells. Larger sizes did not fit in the host RAM free on that WSL2.
- **When the card runs out.** On native Linux a Leiden step that runs out of card memory is
  retried once with CUDA managed memory, which can borrow host RAM and is slower. WSL2 does not
  support that, so there the run falls back to the CPU with the reason; the Windows driver may
  instead spill into host RAM on its own, and the run then slows several-fold. The run summary
  says which memory mode ran ("device memory", or "managed memory, after the card ran out
  with device memory").
- **Reproducibility.** Up to 400,000 cells the exact search gives the same graph every time,
  and a repeat run with the same seed gives the same labels. Above 400,000 cells NN-descent
  is approximate and not repeatable: two runs share about two thirds of their neighbor lists,
  and on the 11,306-cell synthetic ground-truth set two NN-descent runs agree at ARI 0.83
  where the exact search agrees at 1.0. Its neighbors are 98-99 % of the exact ones. The
  run summary records which search ran ("exact brute-force search, repeatable" or
  "approximate NN-descent search, varies from run to run").
- A GPU run differs from a CPU run of the same settings about as two seeds differ. Measured
  on the 11,306-cell synthetic ground-truth set (`n_neighbors` 15 and 50, resolutions 0.5
  and 1.0, seed 42, exact search): CPU path against GPU path ARI 0.91-0.94, CPU seed 42
  against seed 7 ARI 0.93-0.94, and each path against the truth within 0.03 of the other.
  The run summary ends with "Graph and Leiden on the GPU." when it ran there.
- If the process cannot run (no Python set, WSL refusing to start, a library missing, a driver
  too old for the build), Leiden runs on the CPU, a notification says why, and the run
  summary records "cpu (gpu requested; ...)". A successful run records the card, the library
  versions, the search and the memory mode. The process's own log is `run.log` in the job
  folder (both steps write to it), kept when it fails.
- One Windows wrinkle: a "Run as administrator" compatibility flag on
  `C:\Program Files\WSL\wsl.exe` stops any unelevated program, QuPath included, from starting
  WSL ("The requested operation requires elevation"). Clear it under the file's Properties >
  Compatibility.
- Batch files: `graph_backend: gpu` on the `clustering` block ([YAML reference](yaml-reference.md)).

### What a GPU actually accelerates

| Operation | Device |
|---|---|
| Autoencoder, tile mode | **GPU** -- the real case. 64x64 tiles at 10,000 cells take 1-3 hours on CPU. |
| Autoencoder, measurement mode | GPU, marginally. The network is small. |
| Harmony batch correction | **GPU** in the GPU variant, when the run's cells fit in the card's free memory. Otherwise CPU, and the log and the run record say so. The GPU and the CPU can converge after different iteration counts, so the run record names the device. The opt-in harmonypy 2.1.0 (see [Harmony version](../README.md#batch-correction-harmony)) always runs on the CPU. |
| Leiden: the neighbor graph and the partition | **GPU, optional**, through a RAPIDS environment you install yourself; see [Neighbor graph and Leiden on the GPU](#neighbor-graph-and-leiden-on-the-gpu). Everything after the partition (PAGA, marker ranking, plots) stays on the CPU. |
| KMeans, HDBSCAN, GMM, agglomerative | CPU. No GPU code path exists. |
| BANKSY, UMAP | CPU. pybanksy and umap-learn are CPU-only. |
| Spatial statistics, cellular neighborhoods | CPU. Permutation tests are CPU-bound. |
| Phenotyping | CPU. Threshold arithmetic. |

UMAP and permutation statistics have GPU implementations elsewhere (RAPIDS `cuml`, `cugraph`).
The neighbor graph is the exception, covered by the Leiden row above. Adopting any of the
others is tracked internally; nothing is promised here beyond the table.

### "I have a GPU but it is not being used"

Every autoencoder run logs the device it chose, and distinguishes the two reasons it
falls back to CPU -- the fixes are completely different:

- **`torch.version.cuda is None`** -- you are on the CPU environment. Switch the Compute
  variant preference and let the GPU environment build.
- **a CUDA build that cannot see a usable GPU** -- the environment is right; the driver
  or the device is the problem.
- **Harmony ran on the CPU** -- the run's cells would not fit in the GPU's free memory, so
  it was not moved to the GPU (the log warns, and `RUN_INFO.txt` says `on the CPU`). A
  smaller run, or a card with more free memory, moves it.

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
