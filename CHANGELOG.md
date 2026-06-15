# Changelog

All notable changes to QP-CAT (the QuPath cluster analysis tools extension) are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); QP-CAT is in pre-release so no formal semver compatibility commitment is made yet. Breaking changes within `0.x` are called out explicitly.

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
