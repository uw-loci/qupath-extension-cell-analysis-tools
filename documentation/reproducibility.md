# Reproducibility and run cost

Getting back to an earlier run, and understanding what makes a run slow.

- [Reproducing a run](#reproducing-a-run)
- [Saving configurations](#saving-configurations)
- [The audit log](#the-audit-log)
- [What makes a run slow](#what-makes-a-run-slow)

## Reproducing a run

Every run auto-saves to `<project>/qpcat/cluster_results/`. Four routes back, from
"look again" to "run it on a server".

**1. View it again, nothing recomputed.**
**Results & populations > View Past Results...** reloads the labels, plots and spatial
statistics exactly as computed.

**2. Re-run in the GUI with the same settings.**
In the results window click **Open results folder**. Beside the result you will find
`<name>_config.json` (the exact configuration) and `<name>_RUN_INFO.txt` (a readable
record of every parameter, plus these steps). In the clustering dialog use **Load Config
from file...**, pick that JSON, set the **Scope**, and run.

The config does **not** pin the image set, so set the scope yourself. That is deliberate:
a config that silently re-ran on whatever images happened to be selected would produce
different labels that look valid.

**3. Keep a canonical recipe.**
**Save Config... / Load Config...** store named configs in
`<project>/qpcat/cluster_configs/`.

**4. Run it headless.**
Translate the parameters in `RUN_INFO.txt` into a batch YAML and run `qpcat_batch.groovy`.
See [Batch runs](batch.md) and the [YAML reference](yaml-reference.md). This is the route
for a server, CI, or many projects.

### What the Workflow tab does here

QuPath records each command in its **Workflow** tab, and QP-CAT adds a step to every image
a run touched -- including, for a multi-image run, a note that the labels were computed
*jointly* across N images.

That step is an informational **record, not a runnable command**, on purpose. An extension
*can* embed a runnable command (InstanSeg does), so this is a choice: a naive "re-run this
step" would re-cluster whichever single image is open, even when the original was a joint
run, producing different labels that look valid. The record documents the scope so that
trap is visible instead.

Double-clicking the step does not re-open the dialog with values filled in either. That
replay exists only for QuPath's own built-in commands -- there is no public API for a
third-party dialog to be rehydrated from a workflow step. Moot here, since the step is
informational anyway.

What it is good for: a per-image audit trail of what produced that image's labels.

## Saving configurations

| What | Saved by | Stored in |
|---|---|---|
| Clustering configuration | **Save Config...** in the clustering dialog | `<project>/qpcat/cluster_configs/` |
| Phenotype rule set | **Save Rules...** in the phenotyping dialog | `<project>/qpcat/phenotype_rules/` |

## The audit log

Every operation is appended to `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` -- plain text,
one file per day. Each entry records the timestamp, operation type, every input parameter,
a result summary and the duration.

```
=== CLUSTERING === 2026-03-09 14:23:05
  Algorithm: Leiden (graph-based)
  Algorithm params: {n_neighbors=50, resolution=1.0}
  Normalization: zscore
  Embedding: umap
  Measurements: 15 markers
  Input: 12847 cells
  Result: Clustering complete: 8 clusters found for 12847 cells.
  Duration: 4.2s
```

## What makes a run slow

Every setting below trades run time against something else. They are documented in place, next
to the features they belong to -- this section exists so you can see the whole picture at once
when a run is taking longer than you expected, and so the **cost** of each speed-up is stated
next to the saving.

They are grouped by what you give up, not by how much time you save. Nothing here is a
"performance" switch you can flip freely: only the first group is free.

### Free -- you only lose the output itself

| Setting | Where | Effect |
|---|---|---|
| **Ripley's L, co-occurrence, Geary's C, neighbourhood enrichment** | Run Clustering > Spatial statistics | Usually the single largest cost of a run, minutes to hours on large cohorts. Each is independent; turn on only the ones you will read. |
| **Spatial permutations** | Run Clustering > Spatial statistics | `0` picks adaptively by cell count (1000 / 100 / 50). A fixed high value multiplies the cost of every permuted statistic. |
| **Generate plots** | Run Clustering > Analysis | Skips the static matplotlib figures. The interactive tabs still work. |

QP-CAT estimates the spatial-statistics cost **before** committing to it: if the estimate is
large, a prompt appears with the number and a one-minute countdown. Computation has already
started when you see it, and continues if you do nothing, so an unattended run is never blocked
by that dialog.

**Pre-run cost summary.** Directly above the Run button in the Clustering dialog, a line summarizes
all the non-free settings you have configured. It reads either "This run is repeatable and
comparable to other default runs" (when no costs apply) or "This run: " followed by the costs
separated by semicolons -- so the information is visible at the moment you press Run, not hidden
in tooltips. The summary updates live as you change settings.

### Costs reproducibility -- identical runs can differ

This is the only group where running the same thing twice can give you a different answer.

| Setting | Where | Effect |
|---|---|---|
| **UMAP speed vs reproducibility** | Run Clustering > Embedding > Advanced | `auto` (default) pins the seed below 200,000 cells and uses every core above it. `reproducible` always pins the seed -- **6-8x slower on 16 cores**, because umap-learn disables all parallelism the moment a `random_state` is supplied. `fast` never pins it. |

The run records which path it took, so a layout that is not bit-reproducible is never silently
non-reproducible. See [Reproducing a run](#reproducing-a-run).

### Costs comparability -- repeatable, but different from runs without it

Everything here is seeded and fully repeatable: the same cells, settings and seed give the same
answer every time. What changes is the result *relative to the same run with the option off*.
That is a different cost from the group above, and worth keeping separate in your head.

| Setting | Where | Effect |
|---|---|---|
| **Reduce features with PCA before clustering** | Run Clustering > Analysis | Off by default. On wide panels (hundreds of marker x compartment features) a large saving, and usually less noisy. Repeatable, but the clusters differ from the same run with it off -- see [Clustering](clustering.md#reducing-features-with-pca-first). |
| **Spatial feature smoothing** | Run Clustering > Analysis | Slower, but repeatable: the result differs from the same run without it. Adds a graph-convolution pre-step so every algorithm sees the smoothed feature matrix. |
| **MiniBatch KMeans** instead of KMeans | Run Clustering > Algorithm | Much faster on large cohorts, at some cost in cluster quality. |
| **BANKSY PCA dimensions** | Preferences | Fewer dimensions is faster and retains less variance. Repeatable, but BANKSY runs at different values are not comparable. |

Runs that differ in any of these are not comparable with each other. Choose once per study
rather than per run.

### Costs completeness of a figure

| Setting | Where | Effect |
|---|---|---|
| **Plot Feature Limit** | Preferences (default 40) | Above this, the dot plot / matrix plot / stacked violin show the most discriminative features rather than all of them. The stacked violin fits a curve per feature *per cluster*, so this is the dominant plotting cost on wide panels. The figure states how many of how many it shows. |
| **Plot DPI** | Preferences (default 150) | Higher is slower to write and larger on disk. Also scales the results window's "Save plot..." export. |
| Embedding scatter decimation | automatic | Above a threshold the interactive scatter draws a subsample; the tab title says so. Not configurable, and it never changes the clustering. |

### Not a setting, but the biggest lever

Cell count dominates everything above. If a run is impractical, clustering a **representative
subset** of images first is usually more informative than waiting on the full cohort -- the
scope picker in the Run Clustering dialog takes a specific image subset. QP-CAT also refuses
runs it can predict will exhaust memory, naming the statistic and the limit rather than failing
part-way.

### GPU

Only the autoencoder is GPU-accelerated today. Clustering and spatial statistics do not use a
GPU, so the GPU environment variant costs a several-GB download and buys nothing for those
workflows. See [Setup](setup.md#cpu-or-gpu).

---
