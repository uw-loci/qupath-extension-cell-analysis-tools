# Changelog

All notable changes to QP-CAT (the QuPath cluster analysis tools extension) are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); QP-CAT is in pre-release so no formal semver compatibility commitment is made yet. Breaking changes within `0.x` are called out explicitly.

## [0.7.0] -- 2026-06-25

Closes the main gaps surfaced by reviewing the 2020 QuPath<->CytoMAP image.sc
guides against QP-CAT (gating on the embedding; physical-radius neighborhoods;
regions as QuPath objects; region connectivity). See REFERENCES.md (CytoMAP,
Stoltzfus et al. 2020).

### Added

- **Polygon gating on the embedding scatter -> select / classify cells.** The
  clustering Results "Embedding" tab gained a **Gate** tool: click to draw a
  polygon, double-click (or right-click) to close, Esc to cancel. Gated cells can
  be **selected in the open image** or **assigned a persistent classification**
  (e.g. "Gate 1") across **all** of their source images (saved per image, colors
  everywhere, survives reload). New `EmbeddingScatterPanel` gate mode +
  `GateApplier` (resolves cells by centroid, mirrors the project write-back loop)
  + shared `GateActionBar`.
- **New tool: "Plot & gate cells (2D)"** (Extensions > QP-CAT, after "Map cells in
  2D"). Plots cells across an image **scope** (Current / All / Specific, via the
  reusable picker) on either an existing 2D embedding (UMAP/t-SNE/PCA) or any two
  marker measurements (**biaxial**), colored by current classification, with the
  same gate/select/classify actions. Reads existing coordinates; for embeddings,
  run "Map cells in 2D" first. New `PlotAndGateDialog` + reusable `ScopeSection`.
- **Radius (micron) neighborhood windows** for cellular neighborhoods, alongside
  kNN. "Window by: Nearest neighbors (k) / Radius (um)" -- the radius window is
  the CytoMAP-style fixed physical neighborhood (density-aware, more
  interpretable). Microns are converted to pixels per image via pixel
  calibration (treated as pixels if uncalibrated).
- **Region annotations** from cellular neighborhoods (opt-in checkbox): each
  spatially-contiguous patch of a neighborhood becomes a convex-hull QuPath
  annotation classed **`QPCAT Region: <id>`** (distinct from the per-cell
  `QPCAT CN: <id>`), so regions are selectable/measurable objects -- the region
  map CytoMAP could not push back into QuPath. New `RegionAnnotationBuilder`
  (grid union-find + JTS convex hull).
- **Region adjacency / connectivity** output for joint runs: a row-normalized
  CN x CN matrix of how often neighborhoods border each other
  (`cn_region_adjacency.csv` + heatmap, shown in the cohort results dialog).

- **Multiple gates / manual annotation.** After assigning a class, the gate stays
  on the plot as a labelled outline (stored in data coords, so it tracks zoom/pan)
  and resets for the next selection -- so you can hand-annotate many populations in
  turn without losing track of earlier gates. "Clear" drops the active gate;
  "Clear all" removes the outlines.
- **Click-to-center.** Clicking a point on any embedding scatter now centers the
  viewer on that cell (opening its image if needed) and selects it, not just
  selects-if-already-open.
- **Custom embedding names + overwrite warning.** Embeddings are written as
  `NAME1`/`NAME2` (default the method name). Compute/clustering dialogs now expose
  a **Name** field, so two runs (e.g. different settings) can coexist as
  `UMAP_k15_*` and `UMAP_k50_*` instead of silently overwriting; reusing a name
  prompts a confirm. "Plot & gate" lists **any** `*1/*2` coordinate pair present
  (including renamed ones) instead of a fixed UMAP/t-SNE/PCA list, and reports how
  many cells were skipped for missing coordinates.

### Removed

- **"Add AI appearance features to cells..." (foundation-model feature
  extraction)** removed from the menu -- it saw little use and pulled in a heavy
  on-demand model download. The backend code is retained but unwired and can be
  reinstated on request; see HOW_TO_GUIDE "Removed features".

### Performance / internal

All output-preserving (verified numerically equivalent against the prior code on
synthetic data before adoption); no change to results or reproducibility.

- **Cellular-neighborhood region adjacency vectorized** (`cellular_neighborhoods.py`).
  The CN-CN border tally was a pure-Python double loop over every cell's neighbors;
  it is now a memory-bounded chunked `np.bincount` (numerically identical, incl. the
  diagonal and edge cases; ~10-20x faster on the tally). Chunked rather than a single
  bincount so cohorts of millions of cells do not materialize a giant edge array.
- **CN neighbor graph fit once per image and reused** between the windowing and
  region-adjacency passes (one `NearestNeighbors` fit per image instead of two;
  ~2-3x less NN work). Identical for sub-pixel float centroids; could differ only for
  exactly-tied integer coordinates.
- **Spatial-statistics per-cell loops vectorized** (`spatial_stats.py`): neighbor
  counts via `np.diff(indptr)`, CSR distance aggregates (mean/median/max/min) via
  segment-grouped `reduceat` + a lexsort median, and Delaunay per-vertex area
  aggregation via `np.add.at` / `np.maximum.at`. Exact match to the old loops
  (max abs diff ~1e-15); large speedups at high cell counts.
- **Foundation-model / autoencoder tile reads parallelized** (`ClusteringWorkflow`).
  Per-cell `readRegion` calls now run on a small bounded pool (disjoint output
  offsets, no locking). Falls back to sequential for small jobs. Concurrent reads
  are safe for the common large-image readers (verified against QuPath 0.7 source):
  BioFormats hands each call its own pooled reader under `synchronized(reader)`;
  OpenSlide relies on the native library's single-handle thread-safety; and
  `AbstractTileableImageServer` coalesces tiles through a concurrent cache.
- **Internal de-duplication:** the `tipLabel` helper (copy-pasted in 6 dialogs) is now
  one shared `UiLabels.tipLabel`; the inline image-scope block in the clustering and
  phenotyping dialogs now uses the shared `ScopeSection` control (behavior preserved,
  incl. the phenotyping scope tooltips and the batch-correction "all images" gating).

### Notes

- The gate writes a classification, so gating across a multi-image plot persists
  on every image -- arguably more useful than CytoMAP's transient selection.
- "Plot & gate" does not compute embeddings (use "Map cells in 2D" / clustering);
  it plots existing coordinates so it never needs the Python backend.

## [0.6.0] -- 2026-06-25

### Added

- **Joint cellular neighborhoods across a project (cohort analysis).** "Find
  cellular neighborhoods" gained a **Scope** (Current image / All project images /
  Specific images), reusing the shared image picker. A joint run builds spatial
  windows *within* each image (cells in different slides are never neighbors) but
  **pools the composition vectors and runs one k-means**, so a `QPCAT CN: <id>`
  means the same cell-type mixture in every image -- the prerequisite for
  comparing neighborhoods across samples (Goltsev 2018 / Schurch 2020 /
  imcRtools). Labels are written back and **saved to every selected image**, and
  each image gets a scope-explicit Workflow record noting the labels came from a
  joint run (informational, not runnable -- a single-image re-run would produce
  different labels).
- **Per-sample neighborhood proportions.** Joint runs produce a per-image
  CN-proportion table (`cn_per_sample_proportions.csv` + an image x CN heatmap)
  shown in a results dialog -- the table you compare across samples.
- **Group-by-condition comparison.** A **Group images by** image-metadata key
  (e.g. `treatment`) adds per-group mean proportions (`cn_per_group_proportions.csv`
  + a group x CN heatmap) for direct condition-vs-condition comparison.
- **Cell-type panel divergence check.** When scope images do not share the same
  cell-type classes, the run proceeds on the union of classes (keeping vectors
  comparable) but the results dialog flags which images have a divergent panel.
- Joint results land in `qpcat-cellular-neighborhoods/<run-id>/` under the
  project (CSVs, heatmap PNGs, `cn_RUN_INFO.txt`); an **Open results folder**
  button and a How-To link are in the results dialog.
- **Advanced embedding settings (t-SNE / UMAP) in the clustering dialog.** The
  Dimensionality Reduction section now exposes, per **currently selected** method,
  the parameters the backend already accepted but the GUI hid: **t-SNE**
  perplexity (beside the dropdown) plus learning_rate, iterations, and
  early_exaggeration under an **Advanced** expander; **UMAP** metric under
  Advanced; and a shared embedding **random seed** (was hardcoded to 42).
  Perplexity defaults to the existing Preferences value. These persist in the
  saved config (round-trip via **Load Config from file...**) and have YAML
  headless parity (`umap_metric`, `tsne_learning_rate`, `tsne_iterations`,
  `tsne_early_exaggeration`, `embedding_seed`). t-SNE perplexity is clamped below
  the cell count automatically. PCA still exposes nothing (the embedding is 2-D).

### Documentation

- New **Recipes (worked examples)** chapter in the How-To guide. First recipe:
  cluster *on* a UMAP / t-SNE embedding ("UMAP + HDBSCAN on UMAP1/UMAP2") via the
  existing two-step path (compute embedding, then cluster with only UMAP1/UMAP2
  selected, HDBSCAN, normalization None), with the seed/repro and over-splitting
  caveats. Cross-linked from "Computing Embeddings Only".

### Changed

- The cellular-neighborhood Python task (`cellular_neighborhoods.py`) now accepts
  optional `image_ids`/`image_names` (per-image windowing + per-sample
  proportions) and `group_labels`/`group_names` (per-group proportions). With
  none supplied it behaves exactly as before (single-image, global window).
- Corrected the prior framing that cellular neighborhoods are "single-image by
  design": only the spatial **window** is within-image; neighborhoods are now
  definable jointly across the cohort, which is the standard CODEX/imcRtools
  workflow.

## [0.5.1] -- 2026-06-24

### Fixed

- **Compute Thresholds now pools across the selected Scope (phenotyping).** With
  an "All project images" or "Specific images" scope, **Compute Thresholds**
  previously built its histograms and auto-gates from the **open image only**,
  even though the run normalizes and gates across all scope images together --
  so the gates you set could mismatch what the run actually applied. It now pools
  the cells of the chosen scope (all / subset), exactly like the run, so the
  thresholds match. Single-image scope is unchanged. (New
  `ClusteringWorkflow.computeThresholdsProject`, sharing one image-loading helper
  with `runPhenotypingProject` so both pool the same cells.)

### Audited (no change needed)

- Checked every QP-CAT tool for the same "preview computed on the current image
  only" mismatch. Clustering already pools across scope in-run; the autoencoder
  computes normalization across its training set and bakes it into the model
  checkpoint (reused on apply); zero-shot phenotyping, feature extraction, embedding,
  and cellular neighborhoods are single-image by design (no multi-image gating
  step). Phenotyping's Compute Thresholds (above) was the only gap.

## [0.5.0] -- 2026-06-24

### Added

- **Run clustering and phenotyping on a chosen subset of project images.** The
  **Scope** control now offers a third option, **Specific images...**, alongside
  *Current image* and *All project images*. Picking it opens a reusable image
  picker: a checkbox list of every project image with a **name filter**, a
  **metadata key/value filter**, and **Select all / Select none** (which act on
  whatever the filters currently show). Choose two images to test quickly, or one
  cohort/condition at a time, instead of being forced to run the whole project.
  The chosen images go through the existing multi-image path (cells combined +
  normalized together, results saved back per image).
- **Reusable `ProjectImageSelector` component.** The picker above is a
  self-contained, dependency-light JavaFX class (QuPath core + JavaFX only, no
  QP-CAT-specific imports) so it can be copied verbatim into any QuPath extension
  that needs a "run on a subset of the project" selector. Embed it as a node or
  pop it modally via `ProjectImageSelector.showDialog(...)`. Generalized from the
  image-selection pane in the QuIET export toolkit.

- **Reproducibility is now explicit and self-contained per run.** Every
  clustering run writes two files next to its auto-saved result:
  `<name>_config.json` (the exact configuration) and `<name>_RUN_INFO.txt` (a
  human-readable record of all parameters + how to reproduce). The results window
  gained an **Open results folder** button, the Run Clustering dialog gained a
  **Load Config from file...** button (reload any `<name>_config.json` to repeat a
  run -- pick the Scope, click Run), and a note by the Run button points at all
  this. A new How-To section 23 documents the four reproduction routes.
- **Workflow record now lands on every processed image, with explicit scope.**
  A multi-image (project / subset) clustering run previously recorded a Workflow
  step only on the open image; now **every** image it processed gets one. Each
  step states the scope, and for a joint run adds an explicit warning that the
  labels were computed *across N images* and that re-clustering this image alone
  would produce different labels. The step is an informational **record by
  design**, not a runnable command (an extension *could* embed a runnable command
  -- InstanSeg does -- but a naive re-run would silently re-cluster a single image
  when the original was multi-image; the record documents the scope instead). The
  multi-image scope label is also now accurate ("N project images" rather than
  always "Entire project").
- **Scope-safety warning when loading a config from a multi-image run.** Load
  Config from file now warns if the config came from a cross-image run: the image
  set is not stored, so you must set the Scope deliberately rather than
  accidentally re-running on the current image alone.
- **Joint multi-image clustering in the headless YAML batch (Linux servers).**
  The YAML batch gained `clustering.joint: true`, which clusters all of a
  project's resolved images **together** in one run -- globally consistent cluster
  IDs across images, the headless equivalent of the GUI's project scope -- instead
  of clustering each image independently (still the default). The image set comes
  from `scope.images`, so `images: all` clusters the whole project jointly and an
  explicit list/regex clusters a chosen **subset** jointly. The combined result is
  saved once under `result_name` (default `yaml_joint`) and labels + scope-aware
  Workflow records are written back to each image. Dispatched once per project;
  per-image steps (figure export) follow. See YAML_SCHEMA.md (`clustering.joint`).

## [0.4.1] -- 2026-06-23

Follow-up to 0.4.0 from a large BANKSY + spatial-statistics run (13k cells x 72 markers).

### Fixed

- **BANKSY no longer floods the Appose channel / risks stalling.** BANKSY prints a large volume of diagnostics via `print()`, which lands in Appose's stdout *protocol* channel (the `[SERVICE-0] <INVALID>` lines) and on a full pipe can stall the worker. The BANKSY pipeline calls are now wrapped in `redirect_stdout`, so the noise is suppressed while progress messages (sent outside the redirect) still get through.
- **Cluster marker log fold-changes are valid again.** Marker ranking runs on normalized data; under signed normalization (z-score / percentile) scanpy computed `log2` of negative means, producing `NaN` (the repeated `invalid value encountered in log2` warnings) so the logFC column was garbage. We now compute interpretable log2 fold-changes of mean **raw** intensity (cluster vs rest); the Wilcoxon scores and p-values (which drive the ranking) are unchanged. The noisy warning is also silenced.
- **Spatial statistics no longer deadlock (clustering hung indefinitely).** squidpy's neighborhood-enrichment / Ripley / Geary / co-occurrence permutation tests use numba (and joblib) parallel loops that **deadlock inside the Appose worker subprocess on Windows** -- a clustering run would wedge forever at `nhood_enrichment` with no way to recover but restarting QuPath. These spatial *permutation* statistics now run single-threaded (numba serialized for the section, `n_jobs=1`, progress bars off). This is scoped to the spatial stats only: clustering, UMAP, t-SNE, PCA and BANKSY keep full parallelism, so throughput on large clustering jobs is unaffected (threads give those stats a constant-factor speedup, not better scaling, and they are small-data/ROI tools anyway).
- **Neighborhood enrichment + Moran's I are no longer triggered uninvited.** They previously ran whenever spatial coordinates were present -- which is *always* true for BANKSY -- so every BANKSY run launched the (deadlock-prone) neighborhood-enrichment step even if you never enabled spatial analysis. They are now gated on the explicit "Neighborhood enrichment + Moran's I" choice.

### Added

- **Phenotyping now has a project-wide scope.** The phenotyping dialog gained the
  same **Scope** control as clustering: **Current image** (default) or
  **All project images**. With the project scope the same rules and gates are
  applied to every image, all cells are normalized together (global gating, so a
  `pos` threshold means the same thing across the project), labels are written
  back, and each image is saved. Markers and gates are read from the current
  image (consistent-panel assumption, as in multi-image clustering).
- **Find cellular neighborhoods (spatial niches).** A new command --
  `Extensions > QP-CAT > Find cellular neighborhoods (spatial niches)...` --
  groups cells by the *cell-type composition of their local window* rather than
  by their own measurements, surfacing recurring micro-environments (tumor-immune
  boundaries, stroma, lymphoid aggregates) on top of an existing cluster or
  phenotype column. For each cell it builds a window of the k nearest neighbors,
  turns it into a per-type fraction vector, and k-means-clusters the vectors into
  N neighborhoods; cells are classified `QPCAT CN: <id>`. This is the *scalable*
  spatial-niche path -- a nearest-neighbor tree plus a small k-means, O(n*k),
  single-process -- so it works on very large slides where the permutation-based
  spatial statistics do not. Optional CN x cell-type log2-enrichment heatmap; a
  busy indicator and a **Cancel** that applies no labels if cancelled before it
  finishes; provenance `run_id` / `params_hash` recorded in the Workflow history
  and audit log. Mirrors the neighborhood method of Goltsev et al. (Cell 2018)
  and Schurch et al. (Cell 2020) and the windowed-composition workflow of
  Windhager et al. (Nat Protoc 2023); distinct from BANKSY (expression-space
  kernel). See REFERENCES.md and How-To chapter 22.
- **Spatial-statistics time estimate + skip/cancel.** When spatial statistics are enabled, clustering first probes their cost on small subsamples (100 / 1000 / 2000 cells, shown as "Testing estimated time..."), extrapolates to the full cell count, and prompts with the estimate and three choices: **Run spatial stats**, **Skip spatial stats** (clustering still completes), or **Cancel run**. A **Cancel** button is also available throughout the run. Because results are only written to objects after the task finishes, cancelling -- before or during -- leaves the project untouched (no partial measurements). The clustering progress bar is now determinate (see below) so a long run reads as progress, not a hang.

### Changed

- **Representative-cell crops now use the image's display settings.** In the clustering Results "Representative cells" gallery, crops were read as raw pixels, so multichannel / fluorescence cells rendered as washed-out junk. They are now colorized with the image's brightness/contrast and channel settings -- the LIVE viewer display for the open image -- so they match what you see in the viewer. A new **"Update from viewer"** button re-renders the crops after you adjust the viewer (brightness/contrast/channels).
- **Warn before pushing a dense spatial-graph overlay.** When the graph averages more than ~4 connections per cell (kNN with k>4, or Delaunay at ~6), the viewer overlay renders as an unhelpful solid white mass -- and QP-CAT cannot yet colour-code edges or nodes to make it readable. The push now prompts with the average connections-per-cell and lets you decline the on-screen overlay; the graph and its measurements are still computed and saved either way. (The previous prompt only fired on a very high total edge count, so typical dense graphs whited out the view with no warning.)
- **Clearer phenotyping rule semantics (docs + dialog).** Clarified, in both the rules dialog and the How-To guide, that matching is **first-match-wins** (a cell matching several rules takes the topmost -- it is *not* marked Unknown for matching more than one) and that a rule is an **AND** of its conditions. The common surprise is spelled out: setting the other markers to `neg` makes a rule *exclusive*, so a cell positive for two markers matches neither single-marker rule and becomes "Unknown"; to label every cell positive for a marker, set only that marker to `pos` and leave the rest as `--`. No behavior change -- the matching logic was already correct (addresses issue #6, which was a rule-authoring/expectations mismatch). The phenotyping results summary now also breaks the **Unknown** bucket into *negative for all rule markers* / *positive for exactly one* / *positive for >= 2 markers*, so an over-strict rule set (a large "positive for >= 2" count) is obvious at a glance.
- **Phenotyping marker columns now have an explicit `ignore` state, and `--` means "unselected".** Following a user suggestion (issue #6), the per-marker dropdown offers `pos` / `neg` / `ignore` / `--`. `--` is the survey-style "not chosen yet" placeholder: Run Phenotyping prompts you to confirm any columns still left as `--` (they will be ignored), while `ignore` is the deliberate, silent choice. Both `--` and `ignore` leave the marker out of matching; only `pos`/`neg` gate.
- **Clustering now shows real, determinate progress.** Instead of a perpetually bouncing bar with a single static "Running spatial analysis..." label, the progress bar advances through the actual phases (normalize -> embed -> cluster -> marker ranking -> spatial graph -> neighborhood enrichment -> Moran's I -> Ripley -> Geary -> co-occurrence -> packaging), and the status text names the current step with cell/permutation counts -- e.g. "Computing Ripley K and L (1000 permutations on 13286 cells) -- this can take several minutes...". This makes long permutation-test phases (which are genuinely slow on large datasets) read as *working*, not hung.

## [0.4.0] -- 2026-06-22

Bug-fix release closing two user-reported issues (phenotyping thresholds #5, BANKSY #4) plus a systemic JavaFX spinner fix that affects every dialog.

### Fixed

- **Phenotyping auto-thresholding now respects user input (#5).** Four related bugs, all surfacing most clearly under `Normalization: None (raw values)`:
  - The histogram threshold spinner was hardcoded to a `[0, 5]` range, so any raw-intensity (or negative Z-score) threshold was silently clamped -- e.g. it parked at `5` and every auto-threshold method looked identical. The spinner range/step now adapt to each marker's actual data extent.
  - **Apply to All Markers** always applied the Triangle method regardless of the dropdown, because it read the first key of the thresholds map (always `triangle`) instead of the selected method. It now uses the method actually selected in the histogram panel.
  - Switching `Normalization` no longer overwrites the user's **Default gate** with the per-mode default; the value is preserved and only clamped into the new valid range.
  - The selected auto-threshold method is kept (and shown per-marker) when stepping between marker columns instead of resetting to `Manual`.
  - The rules table now seeds one starter rule when markers are selected. The per-marker gate spinners live in the column headers, but a JavaFX `TableView` with zero rows never shows its horizontal scrollbar -- so with many markers the off-screen columns (and their gates) were unreachable until you manually added a rule. A starter row makes every column scrollable immediately.
  - **Visible "busy" feedback** for phenotyping operations (Compute Thresholds, Run Phenotyping, Validate Channels). The progress bar lives at the bottom of a long scrolling dialog and was easy to miss -- e.g. Compute Thresholds takes a few seconds to compute and plot, and looked like nothing was happening. These now set a wait cursor (visible wherever the pointer is) and disable the triggering button (Compute Thresholds reads "Computing...") for the duration.
- **Editable spinners now commit typed values on focus loss (project-wide).** A long-standing JavaFX behaviour (JDK-8150946) means an editable `Spinner` only commits typed text when Enter is pressed; clicking a button (Run / Apply / Compute) moves focus away without committing, so the code read the previous value and the typed input was ignored. A new `SpinnerUtils.commitOnFocusLoss` helper is wired into all ~40 editable spinners across the clustering, autoencoder, feature-extraction, phenotyping, embedding, and figure-export dialogs.
- **BANKSY clustering works again (#4).** The code imported `run_banksy_search` from `banksy.run_banksy`, which does not exist in the published pybanksy 1.3.4 (`ImportError`). The BANKSY branch now drives pybanksy's documented low-level pipeline (`initialize_banksy` -> `generate_banksy_matrix` -> `pca_umap` -> `run_Leiden_partition`), validated against the real package. `k_geom`/`pca_dims` are capped to what the dataset supports.
- **Non-finite measurements no longer abort clustering (#4).** NaN/Inf values (e.g. a marker QuPath could not compute for some cells) caused `ValueError: Input contains NaN` in UMAP. Measurements are now imputed per-column with the column median before normalization, keeping every cell row-aligned with its spatial coordinates.
- **Spatial analysis no longer crashes with `No module named 'spatial_stats'`.** Task scripts run via `exec("<string>")`, so they have no `__file__` and the bundled sibling scripts are not on `sys.path`; `import spatial_stats` (spatial smoothing + spatial statistics) failed, and the old `__file__`-based fallback raised `NameError: name '__file__' is not defined`. `spatial_stats` is now registered as an importable module in the persistent Appose worker at init. No environment rebuild needed. This also reaches the headless / YAML-batch clustering path, which shares the same service.
- **Honest input-cell count in the failure audit log.** A failed clustering run logged `Input: 0 cells` (a hardcoded placeholder) instead of the real count; it now reports the current image's detection count.

### Changed

- **Find cell populations (clustering): the Measurements list now uses checkboxes plus a text filter.** Previously it was a multi-select list where a single plain click silently cleared every other selection. Each measurement now has its own checkbox (toggling one never affects the others), and a filter box above the list narrows it case-insensitively as you type. Checked items stay checked even while filtered out, so you can narrow, check, clear the filter, and repeat. "Select All"/"Select None" act on the currently shown rows; "Select 'Mean' only" still applies across all measurements.

## [0.3.9] -- 2026-06-19

Minor release. Every tool dialog now carries a "Help: Documentation" link to the HOW_TO_GUIDE chapter that describes it, so the docs are one click away from inside each tool.

### Added

- **Per-tool documentation links.** A shared `QpcatDocLinks.linkBar(anchor)` adds a compact, right-aligned "Help: Documentation" row at the top of each dialog, opening the relevant HOW_TO_GUIDE chapter in the browser. Wired into: Find cell populations (clustering), Map cells in 2D (embedding), Add AI appearance features (feature extraction), Classify cells by appearance (autoencoder), Rename or merge cell populations (cluster management), Export figures, and Set up analysis environment. The Rule-Based and Zero-Shot phenotyping dialogs (links added in 0.3.7) and the clustering results dialog (per-tab links) already had them, so every QP-CAT tool now links to its docs.

## [0.3.8] -- 2026-06-18

Minor release. Goal-first renaming and regrouping of the QP-CAT menu so each command says *why you'd use it* (the outcome), not which algorithm it is. Biologists/pathologists were skipping items like "Autoencoder Classifier" because the name described the technique, not the task.

### Changed

- **Menu items renamed to lead with the goal**, technique kept in parentheses:
  - Setup Clustering Environment -> **Set up analysis environment (first run)...** (it powers every tool, not just clustering)
  - Run Clustering... -> **Find cell populations (clustering)...**
  - Quick Cluster -> **Find cell populations (quick presets)**
  - Compute Embedding Only... -> **Map cells in 2D (UMAP / PCA / t-SNE)...**
  - Run Phenotyping... -> **Label cells by marker rules (phenotyping)...**
  - Zero-Shot Phenotyping (BiomedCLIP)... -> **Label cells from a text description (AI / zero-shot)...**
  - Extract Foundation Model Features... -> **Add AI appearance features to cells...**
  - Autoencoder Classifier... -> **Classify cells by appearance (deep learning)...**
  - Manage Clusters... -> **Rename or merge cell populations...**
  - Export AnnData (.h5ad)... -> **Export cells for Python / scanpy (AnnData)...**
  - Export Figures... -> **Export figures (batch)...**
  - Rebuild Clustering Environment -> **Rebuild analysis environment**
- **Menu regrouped by intent** with separators: explore (clustering / quick presets / 2D map) -> label (marker rules / text-AI) -> appearance (AI features / deep-learning classifier) -> manage & results -> export -> utilities & help. Previously phenotyping was separated from clustering by the quick-cluster submenu and the two AI tools sat mid-list.
- HOW_TO_GUIDE and YAML-batch troubleshooting updated to the new menu paths (section titles / anchors and in-dialog button names are unchanged).

## [0.3.7] -- 2026-06-17

Minor release. Phenotyping-dialog clarity fixes, plus per-tool documentation links and explicit image-type support across the phenotyping tools.

### Fixed

- **Marker columns in Run Phenotyping were indistinguishable.** `shortenMarkerName` returned only the channel segment, so selecting several statistics of one channel (e.g. `Cell: PCNA: Mean` and `Cell: PCNA: Median`) produced multiple identical `PCNA` column headers and the only way to tell them apart was the hover tooltip. Headers now show **channel + statistic** (e.g. `PCNA Mean`, `PCNA Median`, `Nucleus PCNA Max`); only a generic leading `Cell`/`Detection` compartment is dropped. The full QuPath name remains in the header tooltip.
- **Gate spinner range did not match the normalization.** The threshold spinners were hardcoded to `0.0-5.0` regardless of scaling -- so Min-Max/Percentile (which produce `[0, 1]`) let you type an out-of-range `5`, and Z-score (centered at 0) could not take a negative gate. Ranges are now normalization-aware: Min-Max / Percentile `0.0-1.0`, Z-score `-3.0 to 3.0`, None = raw units. Both the default-gate and per-marker spinners (and their bounds) update when you change normalization.

### Added

- **Prominent gate range/units banner + pos/neg legend.** The previously faint gray `[0,1]` hint is now a highlighted box that states, for the selected normalization, the valid gate range and that a cell is `pos` when its value is `>= the gate`. The rules section explains the columns (channel + statistic), the `pos` / `neg` / `--` choices, and where the gate spinner lives, and points to **Compute Thresholds** for data-driven gates.
- **Per-tool documentation links.** A new shared `QpcatDocLinks` helper adds a "Documentation" link (and, for phenotyping, an "Auto-thresholding" link) opening the relevant HOW_TO_GUIDE chapter. Wired into the Run Phenotyping and Zero-Shot Phenotyping dialogs; the clustering results dialog now shares the same base URL.
- **Explicit "supported image types" guidance in each dialog.** Rule-Based Phenotyping states it works on any image with per-cell intensity measurements (multiplex IF, fluorescence, brightfield/IHC, H&E after stain separation) because it reads measurements, not pixels. Zero-Shot Phenotyping states it scores RGB tile crops with BiomedCLIP -- best for brightfield H&E / IHC, with fluorescence/multiplex flagged as experimental (false-color RGB rendering is out-of-distribution).

### Docs

- HOW_TO_GUIDE chapters 6 and 9 gain "Supported image types" notes and the normalization-dependent gate-range explanation; chapter 9 now documents the actual prompt **table** (name + prompt columns) and settings, replacing the stale "text area, one per line" description.

## [0.3.6] -- 2026-06-17

Minor release. Clustering results are now always reloadable and every run is recorded, closing a gap where a bare clustering run (no analysis plots / no spatial statistics) silently left nothing on disk: the cluster labels were applied to the objects, but the rich results interface could not be reopened because the results dialog -- the only place with a "Save Results" button -- never appeared, so `qpcat/cluster_results/` stayed empty and "View Past Results" reported no saved data.

### Added

- **Auto-save every clustering / embedding run.** On a successful run the result is now persisted automatically to `<project>/qpcat/cluster_results/` with a timestamped, scope-tagged name (e.g. `auto_20260617_193235_leiden`), so it is always reopenable via Extensions > QPCAT > View Past Results -- no button click required. The previous manual "Save Results..." button remains as "Save a named copy..." for saving an additional, user-named copy.
- **Results dialog now always opens** after a run, even when no plots or spatial statistics were generated (previously gated on `hasPlots() || hasMarkerRankings() || hasSpatialAutocorr() || hasAnySpatialStats()`). Bare runs get a Summary tab plus the save-location footer.
- **Save-location footer** in the results dialog: a read-only, copyable field showing exactly where the result is stored on disk and this run's size (and, when viewing a past result, where it was loaded from + its size).
- **Native QuPath Workflow step.** Each run appends a `DefaultScriptableWorkflowStep` to the open image's command-history Workflow (the Workflow tab, exportable as a script), recording the algorithm, parameters, normalization, embedding, cluster/cell counts, and how to reopen the saved result. The existing per-project audit log under `qpcat/logs/` is unchanged.
- **Manage Saved Results dialog** (Extensions > QPCAT > Manage Saved Results...): a checkbox list of all saved results -- name, timestamp, summary, scope, origin (auto vs named), and per-result size -- with multi-select delete. The header shows the results folder path and its total on-disk size.
- **Over-5 warning per scope.** When more than five saved results accumulate for the same scope (a single image, or "Entire project" for project-wide runs), a notification points the user to "Manage saved results..." to prune old ones. Auto-saves are never deleted automatically -- removal is always the user's explicit choice.

### Notes

This release does not recover results from runs made before 0.3.6: only the cluster labels were persisted on those objects (not the embedding coordinates or marker rankings), so the rich interface for an old run cannot be reconstructed. Re-running clustering on the same objects with the same parameters reproduces an equivalent result (graph-based methods are deterministic given a fixed seed), and from 0.3.6 onward it auto-saves.

## [0.3.5] -- 2026-06-15

Patch release. Makes the Appose Python environment reproducible so an extension update installs the exact tested package versions instead of re-resolving against whatever conda-forge / PyPI published that day. This permanently closes the `pkg_resources` / `setuptools` failure family that recurred whenever the resolver floated `setuptools` to a `pkg_resources`-less 81+.

### Fixed

- **Environment no longer re-resolves on update (root cause of the recurring `No module named 'pkg_resources'`).** The env was rebuilt from scratch whenever `pixi.toml` changed (which an update does), so loose `>=` bounds let the resolver pull the day's latest. `setuptools` (a transitive dep, not one we list) drifted past 81, which removed `pkg_resources`, breaking the `squidpy -> spatialdata -> xarray_schema` import. v0.3.5 bundles a committed `pixi.lock` pinning the full transitive tree and installs the env with `pixi install --frozen`, so it installs the locked versions and never re-resolves. `setuptools` is also explicitly capped `>=65,<81` as a belt-and-suspenders bound.
- **`syncManifest` now stages the lockfile alongside the manifest** (it no longer deletes the lock to force a re-resolve), and the cause-chain detector that surfaces the Windows `os error 32` file-lock recovery steps now walks the full exception cause chain instead of only the top-level `pixi build failed` message, so it actually fires.

### Notes

Editing the on-disk `pixi.toml` by hand does not persist: the extension re-stages both `pixi.toml` and `pixi.lock` from the bundled jar resources on every launch (this is by design -- the bundled manifest is the source of truth). The fix is to run this version, not to edit the staged file. On first run after updating, delete the partially-built env (`~/.local/share/appose/qupath-qpcat/`, especially its `.pixi/` folder) so the new locked env builds clean.

## [0.3.4] -- 2026-05-31

Patch release. Closes the D1 known-limitation from v0.3.0 (project-clustering produced cross-image phantom neighbour counts in per-cell measurements) and adds a Windows file-lock detector that gives the user actionable recovery steps instead of a raw `pixi build failed` stack trace.

### Fixed

- **D1: project clustering per-cell aggregates were global, not per-image.** `MeasurementExtractor.extractMultiImage` concatenates per-image pixel coordinates into a single global array, so a cell at (100, 100) in image A and a cell at (100, 100) in image B became spatial neighbours in the global Delaunay / kNN graph. `SpatialGraphPayload.slice` correctly dropped cross-image *edges*, so the rendered overlay was always per-image-correct, but the per-cell `QPCAT spatial: Num neighbors` and `Mean / Median / Max / Min distance` were copied verbatim from the global aggregates -- they included the phantom edges. Fix: `slice()` now leaves per-cell aggregates null (with a Javadoc note explaining why), and `applySpatialGraphPayload` recomputes them per-image from the sliced edge COO + this image's centroids + this image's pixel calibration. Triangle areas (Delaunay-only) and component labels remain global-graph values on multi-image runs -- recomputing triangle areas would require per-image Delaunay re-triangulation, which is deferred to a future release. Single-image clustering is unchanged (bypasses `slice` entirely).

### Added

- **Windows file-lock recovery instructions when the Pixi build fails with `os error 32`.** Distinct failure mode from the `pkg_resources` / `xarray_schema` family v0.3.2 self-heals: Pixi cannot replace a file inside `.pixi/envs/default/Library/share/proj/proj.db` (or any other DB / SQLite file in the env) because another process is holding it open. Common causes: an orphan Java/Python process from the prior QuPath session, antivirus scanning the file, or an interrupted previous install. v0.3.4 detects the canonical "failed to link" + "os error 32" / "being used by another process" signature and emits a six-step PowerShell recovery script to the log + a short notification pointing at it. Does NOT auto-wipe -- the blocking process may still be writing to the env, and a mid-install wipe risks corruption.

### Notes

D1 fix is fully transparent: existing projects pick up correct per-cell aggregates on the next clustering run; no rerun-from-scratch needed unless you saved out measurements you want to recompute. The CHANGELOG known-limitation entry for D1 in v0.3.0 is now closed.

## [0.3.3] -- 2026-05-29

Patch release. Adds a one-click way to wipe the spatial-graph overlay from the current image.

### Added

- **`Extensions > QP-CAT > Utilities > Clear cell connections...`** -- removes every `PathObjectConnectionGroup` attached to the current image (QP-CAT's own overlay, a legacy QuPath core Delaunay Clustering run, or anything else that wrote to QuPath's `PathObjectConnections` slot) and clears QP-CAT's same-class filter stash. QuPath core has no built-in clear action -- the only way to drop these groups was to re-run cell detection (which discards the detections that carry the edges). Reports the number of groups and edges removed via an info notification, records a workflow step (`SpatialConnectionsScripts.clearConnections(getCurrentImageData())`), and writes a `SPATIAL OVERLAY CLEAR` row to the operation audit log.
- **Public scripting facade**: `SpatialConnectionsScripts.clearConnections(ImageData)` returns a `ClearResult` with `getNGroupsRemoved()` and `getNEdgesRemoved()` for batch scripts. See [HOW_TO_GUIDE chapter 21](documentation/HOW_TO_GUIDE.md#clearing-the-overlay----utilities--clear-cell-connections-clear-connections).

### Notes

The action is reversible -- re-running clustering with Viewer overlay enabled or clicking "Push to viewer now" on any saved result repopulates the connection group. The overlay payload lives in `ImageData` properties only; saved results on disk are untouched.

## [0.3.2] -- 2026-05-29

Patch release. Quick Delaunay learns to honor your preferences, gains a "(custom)..." sibling for per-invoke overrides, and auto-recovers from a class of stale-Pixi-env failures that v0.2.8's setuptools pin doesn't always reach.

### Added

- **`Extensions > QP-CAT > Quick Cluster > Quick Delaunay (custom)...`** -- a new sibling to the existing one-click Quick Delaunay. Pops a small two-field dialog (Distance threshold with unit auto-selected by the current image's pixel calibration + Limit edges to same class), then runs the same Leiden + Delaunay-smoothing code path with those overrides. Use the one-click `Quick Delaunay` for muscle-memory runs and `Quick Delaunay (custom)...` for per-tissue tweaks.
- **Auto-recovery from stale Pixi environments.** The init verify step now also imports `squidpy`, catching the `xarray_schema -> pkg_resources` failure (the well-known signature where the env's `pixi.toml` says `setuptools >= 65` but the resolved env never installed it). When detected, QP-CAT wipes `~/.local/share/appose/qupath-qpcat/.pixi/` + `pixi.lock` automatically and tells the user to restart QuPath; the next launch rebuilds the env cleanly. Other stale-env signatures covered: `No module named 'setuptools'`, `'xarray_schema'`, `'spatialdata'`, `'squidpy'`.

### Fixed

- **Quick Delaunay ignored `qpcat.spatial.delaunayMaxEdge*` and `qpcat.spatial.limitEdgesBySameClass` preferences** -- it hard-coded `delaunayMaxEdge = -1` (no pruning) and never applied the same-class filter even when the preference was set. Now reads both from `QpcatPreferences` (microns when the image is calibrated, pixels otherwise) and applies the same-class filter after the run via `SpatialConnectionsScripts.applySameClassFilter`. Configure once in `Edit > Preferences > QP-CAT: Run Clustering` and every subsequent Quick Delaunay run respects the values.

### Upgrade

If your Windows or macOS workstation is currently stuck on the `ModuleNotFoundError: No module named 'pkg_resources'` error from squidpy import, install v0.3.2 and launch QuPath. The init verify will catch the stale env, wipe it automatically, and ask you to restart -- second launch rebuilds and clustering works again.

Manual workaround (if for any reason the auto-wipe doesn't run): close QuPath, delete `~/.local/share/appose/qupath-qpcat/.pixi/` and `pixi.lock`, relaunch.

## [0.3.1] -- 2026-05-29

Patch release. Three coordinated fixes pulled from a v0.2.x training-run log: the VAE training pathway also dropped `Cluster N` labels (v0.2.10 only fixed the pie chart display path), residual `[TEST FEATURE]` strings the v0.2.8 cleanup missed, and two noisy Dask + scanpy FutureWarnings worth silencing in the Python console.

### Fixed

- **VAE training pathway dropped `Cluster N` detection classifications** even after the user explicitly opted into "Existing detection classifications." `ClusteringWorkflow.extractClassLabels` (the actual training-data extraction path) still carried the same `name.startsWith("Cluster ")` skip the v0.2.10 pie chart fix removed from `AutoencoderDialog.refreshClassDistribution`. Symptoms in the Python log: `Received N cells, 0 classes` / `Labeled: 0, Unlabeled: N, Classes: 0` even though the pie chart correctly showed the cluster classes. Drop the skip in the `useDetections` branch to match v0.2.10. The skip is retained in the Locked Annotations and Point Annotations branches where cluster annotations would be contamination.
- **Residual `[TEST FEATURE]` / `[TEST]` strings** in Python log messages (`train_autoencoder.py` module docstring + final "training complete" log; `infer_autoencoder.py` module docstring + final "inference complete" log), the menu item (`strings.properties` `menu.autoencoderClassifier`), the preference category name (`QpcatPreferences.CATEGORY_VAE`), and three Java comments / docstrings in `SetupQPCAT` and `ClusteringWorkflow`. The v0.2.8 cleanup only addressed the live dialog title / notification surface; these leaked through.

### Changed

- **Silenced two FutureWarnings** that fire on every Appose worker init and clutter the Python console without conveying anything actionable:
  - Dask `legacy DataFrame implementation is deprecated` -- now opts into `dataframe.query-planning = True` at init.
  - scanpy `__version__ is deprecated` -- warnings filter.
  Both wired in `init_services.py`. The Appose numpy / Windows hang warning is from Appose itself (https://github.com/apposed/appose/issues/23) and remains visible -- known harmless noise.

### Upgrade

No Appose env rebuild required. Both Python and Java changes ship inside the JAR (`init_services.py`, `train_autoencoder.py`, `infer_autoencoder.py` are JAR-bundled scripts loaded fresh from resources every task).

## [0.3.0] -- 2026-05-29

Minor release. Spatial graph overlay -- QP-CAT now writes its spatial neighbor graph back into QuPath's `PathObjectConnections` slot so the legacy **View -> Show object connections** menu item renders the kNN / Radius / Delaunay graphs that drive every Spatial Statistics call. Includes legacy Delaunay-clustering parity for per-cell node measurements, per-component aggregate measurements, the post-hoc same-class edge filter, and the micron-aware Delaunay edge-pruning threshold.

### Added

- **Spatial graph overlay** -- every Spatial Statistics run pushes the spatial neighbor graph (kNN, Radius, or Delaunay) to QuPath's `PathObjectConnections` slot so **View -> Show object connections** renders the edges. New "Viewer overlay" sub-section in the Run Clustering dialog's Spatial Statistics pane (default on; 250000-edge prompt threshold). Inspired by the image.sc Delauney-clustering thread (https://forum.image.sc/t/qupath-delauney-clustering/49959/5). See [HOW_TO_GUIDE chapter 21](documentation/HOW_TO_GUIDE.md#21-spatial-graph-overlay), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#spatial-graph-overlay), and [REFERENCES](documentation/REFERENCES.md#spatial-graph-overlay-pathobjectconnections).
- **Per-cell node measurements** -- `QPCAT spatial: Num neighbors`, `QPCAT spatial: Mean / Median / Max / Min distance` written to every cell on every Spatial Statistics run (default on; toggle via `qpcat.spatial.writeNodeMeasurements`). For Delaunay graphs only, `QPCAT spatial: Mean / Max triangle area` are also written. Distances scale to microns when the image has pixel calibration; otherwise they stay in pixels (matching the v0.2.7 spatial-stats unit treatment). One-for-one drop-in for the legacy QuPath core Delaunay Clustering plugin's `Delaunay: ...` columns.
- **Per-component aggregate measurements** -- opt-in (default off, `qpcat.spatial.writeComponentMeasurements`). For each graph-connected component, writes `QPCAT component: size` and `QPCAT component: mean: <existing measurement>` to every cell in the component. Deliberately uses the `component` namespace to disambiguate from Leiden phenotype clusters; see the [Component vs Cluster](documentation/BEST_PRACTICES.md#component-vs-cluster-naming) worked example.
- **Limit edges to same class (post-hoc filter)** -- toggleable filter in the Viewer-overlay sub-section that rebuilds the displayed connections to within-class edges only. Mirrors the legacy plugin's `Limit by class` option but applied post-hoc so users can phenotype first, then filter, without re-running the graph build.
- **Micron-aware Delaunay max-edge threshold** -- new `qpcat.spatial.delaunayMaxEdgeUm` preference; the dialog shows the micron spinner on calibrated images and the existing pixel spinner on uncalibrated ones (decide-once at dialog open).
- **Push to viewer now** button on the Run Clustering dialog -- rebuilds the viewer overlay from the most recent saved spatial-stats result without re-running clustering. Useful when opening projects that pre-date v0.3 or after toggling the same-class filter.
- **Public Groovy scripting facade** at `qupath.ext.qpcat.scripting.SpatialConnectionsScripts` with `pushConnectionsToViewer(ImageData, String)` and `applySameClassFilter(ImageData, boolean)`. See [SCRIPTING.md](documentation/SCRIPTING.md#spatialconnectionsscripts).

### Changed

- **`spatial_graph_delaunay_max_edge` passed to Python is now resolved Java-side** -- the canonical micron value (`qpcat.spatial.delaunayMaxEdgeUm`) is converted to pixels using the current image's pixel calibration; on uncalibrated images the existing pixel preference still applies. Zero change to the Python contract.

### Fixed during pre-release testing

- F1: per-cell `QPCAT spatial:` distance + triangle-area columns now scale to microns when pixel calibration is present; unchanged in pixels when uncalibrated. Docs previously claimed micron-aware behaviour that the code did not implement.
- F2: edge-count prompt threshold (default 250000) now triggers an actual confirmation dialog when crossed. Previously it logged a warning and silently skipped the viewer push.
- F3: component-measurement feedback-loop guard now skips QP-CAT's other prefixes (UMAP, tSNE, PCA, Cluster, FM_, ZS_, AE_) in addition to `QPCAT spatial:` and `QPCAT component:`. Prevents nonsense `QPCAT component: mean: UMAP1` columns on rerun.
- F4: pushing a saved spatial-stats result that predates v0.3 (no edge COO on disk) now shows a clear warning instead of a misleading success notification.
- F5: `SpatialConnectionsScripts.pushConnectionsToViewer` and `applySameClassFilter` now write to the project-wide OperationLogger audit trail alongside the per-image workflow step.
- F8: "Push to viewer now" surfaces the saved-result name when one result is present and opens a picker when multiple results are saved. Previously silently iterated and picked the first.
- F9: same-class filter toggle now surfaces the edge-count change as a transient notification, with an explicit "cells may be un-classified" message when the filter empties the overlay.

### Known limitations

- The underlying QuPath `PathObjectConnections` API is marked `@Deprecated` in 0.7. QP-CAT uses it deliberately because the planned `DelaunayTools.Subdivision` replacement cannot represent kNN or Radius graphs. v0.3 ships an "uses today's API while it exists" overlay; the next major QuPath release that removes the deprecated API will need QP-CAT to ship either a custom overlay or wait for an `Subdivision` superset. See [HOW_TO_GUIDE chapter 21 -- API deprecation note](documentation/HOW_TO_GUIDE.md#api-deprecation-note).
- macOS and Windows have not yet been smoke-tested in v0.3; the release was developed and statically verified on Linux WSL2.
- Saved-result push-to-viewer in v0.3 attaches an empty connection group when the saved bundle predates the edge-COO write path (legacy `SavedClusteringResult` JSON does not yet persist the COO). Re-run clustering once on v0.3 to populate the new payload.
- Project clustering builds a single global spatial graph from concatenated per-image pixel coordinates. Cells at the same coordinate in different images can become spurious neighbours in the global graph; per-segment edges are filtered out by `SpatialGraphPayload.slice` but per-cell measurement aggregates remain global. Use single-image clustering for v0.3 if cross-image neighbours would distort your analysis. Targeted fix in v0.3.1.

## [0.2.10] -- 2026-05-28

Patch release. Results-dialog UX pass, Autoencoder preflight + cleanup, Quick Cluster Delaunay entry.

### Added

- **Heatmap, Dotplot, and Matrix Plot tabs are now adjacent** in the Results dialog (previously Dotplot / Matrix Plot landed several tabs later). The three expression-overview tabs each carry a "Compare expression views" hyperlink that opens a popup explaining when to use each (Heatmap for live exploration, Matrix Plot for figures, Dotplot when fraction-expressing matters).
- **"Documentation" hyperlink on every Results-dialog tab** opens the relevant subsection of the new `documentation/HOW_TO_GUIDE.md` Chapter 20 ("Results dialog reference"), which adds one subsection per tab.
- **Quick Cluster -> Quick Delaunay (Leiden + Delaunay smoothing)** menu entry runs Leiden clustering with spatial smoothing backed by a squidpy Delaunay graph. Useful as a one-click "graph-aware" alternative to Quick Leiden for users coming off QuPath core's legacy Delaunay clustering tool.
- **VAE training preflight checks** in the Autoencoder dialog:
  - One-time-per-session reminder that VAE training is CPU-bound and slow vs. QuPath's regular ML classifiers.
  - Confirmation prompt with diagnostic copy when the class-distribution scan returned zero labeled cells.
  - "Save before training?" prompt when the current image has unsaved hierarchy edits (offers to call `ProjectImageEntry.saveImageData`).
- **VAE temp-file cleanup**: dialog open sweeps `<project>/.qpcat_temp/qpcat_*.bin` files older than 1 hour. Catches orphan tile buffers from crashed training / apply / eval runs without touching files belonging to a concurrent run on another QuPath instance.

### Fixed

- **VAE classifier "Existing detection classifications" reported "No labeled cells found" when the project contained `Cluster N` classifications from a prior QP-CAT run.** The label scan dropped any class name starting with `Cluster ` even when the user had explicitly opted into using existing detection classes. The skip is retained in the Locked Annotations and Point Annotations paths (where cluster annotations would be contamination) and dropped from the existing-detection-classifications path.
- **PAGA Trajectory tab said "transcriptional similarity / transcriptionally distinct"**; QP-CAT is primarily used with fluorescent antibody / IF / IMC intensities, not scRNA-seq. Reworded to "expression similarity / distinct expression profiles".

### Changed

- **Autoencoder dialog parameter controls now lock during training, apply, and evaluate.** Spinners, combos, radios, and checkboxes bind their `disableProperty` to a new `trainingInProgress` `BooleanProperty`. Buttons keep their existing imperative `setDisable()` (their enable state depends on whether a trained model exists).

## [0.2.9] -- 2026-05-28

Patch release. Four Results-dialog bugs surfaced by v0.2.8 testing.

### Fixed

- **Marker Rankings tab rendered as a single line.** `scanpy.tl.rank_genes_groups` produces NaN/Inf `logfoldchanges` whenever a marker is absent from a comparison group; Python's `json.dumps` writes these as bare `NaN` / `Infinity` tokens, which Gson's strict parser rejects, and the catch fell back to returning the raw single-line JSON dump. Sanitized NaN/Inf to `null` Python-side (new `_sanitize_json_tree` helper, applied to `marker_rankings` and `spatial_autocorr` outputs), added Gson lenient parsing + null-safe formatting on the Java side as defense-in-depth (so existing saved results with the old NaN-emitting payloads still display), and bumped the TextArea `prefRowCount` to 30.
- **Dotplot and Matrix Plot outputs were drastically smaller than the Heatmap tab.** The plot-loading loop hardcoded `ImageView.setFitWidth(800)` for every PNG, which made `ScrollPane.setFitToWidth(true)` a no-op. Narrow-aspect plots (dotplot, matrixplot) ended up much shorter than wide-aspect plots (heatmap) at the same nominal width. Image-view width now binds to the ScrollPane viewport width with an 800 px floor, so every plot scales with the dialog.
- **Embedding Plot cluster colors disagreed with the interactive UMAP / embedding panel.** `pd.Categorical(cluster_labels_str)` without an explicit `categories=` argument defaulted to lexicographic order (`0, 1, 10, 11, 12, 13, 2, 3, ...`); scanpy then assigned palette colors in that order, while the interactive Java panel uses `CLUSTER_COLORS[cluster_id % 20]` (numeric). Pinned the Categorical's category order to numeric so both views agree.

## [0.2.8] -- 2026-05-28

Small patch release for remote-system testing. Fixes a Pixi env regression that blocked spatial-stats on Windows, plus an Autoencoder dialog polish pass.

### Fixed

- **Spatial-stats failed on Windows with `ModuleNotFoundError: No module named 'pkg_resources'`.** `xarray_schema` (transitive: `squidpy` -> `spatialdata` -> `xarray_schema`) still imports `pkg_resources` at module load, and modern Pixi envs do not preinstall `setuptools` (which provides it). Pinned `setuptools >= 65` in `pixi.toml`; `syncPixiToml()` detects the change and triggers a one-time incremental env rebuild on next launch.
- **Autoencoder dialog warning banner was unreadable in dark mode.** The "WARNING: Applying the classifier will REPLACE..." banner had a hardcoded pink background (`#F8D7DA`) but no explicit text color, so dark-theme default light-gray text washed out. Pinned `-fx-text-fill: #721C24` (the dark-red companion to the alert-danger background) so it reads cleanly in both themes.

### Changed

- **Dropped `[TEST]` / `[TEST FEATURE]` framing across the Autoencoder Cell Classifier dialog.** The classifier has been working well in practice; the experimental-feature signposting is no longer warranted. Affected surfaces: dialog title, header text, standalone yellow `TEST FEATURE` banner (removed entirely), Apply confirmation prompt, Save/Load/Evaluate dialog titles, and all six toast notifications (train ok/err, apply ok/err, save ok/err, load ok/err).

## [0.2.7] -- 2026-05-14

Four substantial features added since v0.2.4. All [Beta] markers reflect first-release scope; user feedback welcome.

### Added

- **Cluster Explainer (LLM) [Beta]** -- new tab on the cluster results dialog that turns each cluster's top-marker statistics into a plain-English phenotype suggestion with rationale. Anthropic Claude (BYO key, in-memory only, with a `QPCAT_ANTHROPIC_KEY` env-var fallback) and local Ollama are supported in v1; OpenAI is intentionally not supported. The full prompt + response are captured in the project audit log on every call, with `Authorization:` headers and `sk-ant-*` keys scrubbed on both the Java and Python sides before logging. Result rows are persisted to `SavedClusteringResult` so reopening past results does not re-pay the API call. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC). See [HOW_TO_GUIDE chapter 10](documentation/HOW_TO_GUIDE.md#10-explaining-clusters-with-an-llm-beta), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#when-to-use-the-llm-cluster-explainer), and [TROUBLESHOOTING_LLM_EXPLAINER.md](documentation/TROUBLESHOOTING_LLM_EXPLAINER.md).
- **Spatial Statistics Expansion** -- Ripley K/L (dual-panel chart with Poisson null overlay), Geary's C, and co-occurrence (pairwise + one-vs-rest), all backed by squidpy. Explicit graph constructors -- kNN (default), Radius, Delaunay -- replace the previous hidden defaults; the same graph backs every post-clustering statistic so results are apples-to-apples. BANKSY keeps its own internal neighbor model. Adaptive permutation defaults (1000 / 100 / 50 by cell count) with override via the `qpcat.spatial.permutations` preference. Matplotlib PNG output gated by `qpcat.spatial.persistPlots` (default `true`) so the Multi-Figure Batch Export dialog can pick them up. Public Groovy-callable scripting facades at `qupath.ext.qpcat.scripting.SpatialGraphScripts` and `SpatialStatsScripts`. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC). See [HOW_TO_GUIDE chapter 17](documentation/HOW_TO_GUIDE.md#17-spatial-statistics-ripley-geary-co-occurrence), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#choosing-spatial-statistics), and [SCRIPTING.md](documentation/SCRIPTING.md#spatialstatsscripts).
- **Multi-Figure Batch Export** -- new **Extensions > QP-CAT > Export Figures...** dialog. Pick images, plot kinds, format (PNG or TIFF), DPI, output directory, and a filename pattern with `{image}` / `{plot}` / `{result_name}` / `{date}` / `{ext}` tokens. Image-subset selection is mandatory (current / all / checkbox-list-of-images). Saved matplotlib plots (dotplot, matrix plot, PAGA, stacked violin, scanpy embedding, neighborhood enrichment, spatial scatter, Ripley K/L, Geary's C, co-occurrence) export headlessly; JavaFX-only kinds (heatmap canvas, embedding scatter canvas, autoencoder pie, histogram) require the interactive dialog. SVG / PDF / EPS are deferred to v1.1. Public Groovy facade at `qupath.ext.qpcat.scripting.FigureExportScripts`. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC). See [HOW_TO_GUIDE chapter 18](documentation/HOW_TO_GUIDE.md#18-exporting-figures), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#when-to-batch-export-figures), and [SCRIPTING.md `FigureExportScripts`](documentation/SCRIPTING.md#figureexportscripts).
- **YAML Headless Batch** -- the first headless-mode feature in QP-CAT. Run clustering + phenotyping + spatial-stats + figure-export from a single YAML config via QuPath's `script` subcommand: `QuPath script qpcat_batch.groovy --args=<config.yaml>`. `--args=--dry-run` validates the schema + scope without dispatching work. The schema (`version: "1.0"`) covers `audit`, `scope`, `clustering`, `phenotyping` (with optional `llm_explainer`), `spatial_stats`, `figure_export`, `on_error`, and `workers`. The `figure_export.figures` slug `ripley` is shorthand that expands to both `ripley_k` and `ripley_l`. 18 of 21 validation error codes implemented in v1 (3 deferred to v1.1: marker-not-found, ambiguous-project, override-out-of-scope). Workers > 1 warns and coerces to 1 in v1. Public Groovy facade at `qupath.ext.qpcat.scripting.YamlBatchScripts`. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s `openimc workflow <config.yaml>` command. See [HOW_TO_GUIDE chapter 19](documentation/HOW_TO_GUIDE.md#19-yaml-headless-batch), [YAML_SCHEMA.md](documentation/YAML_SCHEMA.md), [TROUBLESHOOTING_YAML_BATCH.md](documentation/TROUBLESHOOTING_YAML_BATCH.md), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#yaml-batch-mode), and [SCRIPTING.md `YamlBatchScripts`](documentation/SCRIPTING.md#yamlbatchscripts).

### Changed

- Python environment version bumped to `0.2.6` (the Appose env version, distinct from the extension version). The pixi.toml gained two pure-Python dependencies (`anthropic`, `requests`) for the LLM explainer; Appose performs a one-time incremental rebuild (~30 seconds to 2 minutes) on first launch after upgrade -- the full ~1.5-2.5 GB clustering env is not re-downloaded.
- Anthropic default model strings updated to `claude-sonnet-4-5` (default) and `claude-opus-4-7` (dropdown alternative); reflected throughout the dialog and docs.

### Documentation

- New files: `documentation/SCRIPTING.md`, `documentation/YAML_SCHEMA.md`, `documentation/TROUBLESHOOTING_LLM_EXPLAINER.md`, `documentation/TROUBLESHOOTING_YAML_BATCH.md`.
- Updated files: `README.md`, `documentation/HOW_TO_GUIDE.md` (new chapters 10, 17, 18, 19), `documentation/BEST_PRACTICES.md` (new sections for LLM explainer, spatial stats, batch figure export, YAML batch), `documentation/REFERENCES.md` (Ripley 1976, Geary 1954, Delaunay 1934, expanded squidpy entry).

### Known limitations

- macOS and Windows have not yet been smoke-tested in v1; the release was developed and statically verified on Linux.
- Headless phenotyping is deferred to v1.1 (the YAML batch surfaces a WARN row per image).
- The headless figure-export path is inline-parallel to `BatchFigureExporter`; v1.1 will fold it into the single path.
- JavaFX-source plot rows in the batch-export dialog are visible-but-disabled with a `(GUI-only -- not exportable in v1)` tag.
- Several smaller persona-test findings deferred to v1.1: Groovy scripting beyond the spatial-stats / figure-export / YAML-batch surfaces, CMD+Enter on macOS, `QPCAT_ANTHROPIC_KEY_FILE` fallback, per-cluster async LLM calls.

## [0.2.4] -- 2025

The last release before the v0.2.7 feature wave. See the [GitHub release notes](https://github.com/uw-loci/qupath-extension-cell-analysis-tools/releases/tag/v0.2.4) for the changeset.
