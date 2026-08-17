# QP-CAT -- YAML Schema Reference

Field-by-field reference for the YAML config file consumed by `qpcat_batch.groovy`. v1 covers clustering, rule-based phenotyping, the LLM cluster explainer (optional), spatial statistics, and figure export. Schema version `1.0` (shorthand `1` accepted).

For the workflow narrative ("when to use, how to debug a failed run"), see [HOW_TO_GUIDE section 19](HOW_TO_GUIDE.md#19-yaml-headless-batch). For reproducibility / CI guidance, see [BEST_PRACTICES](BEST_PRACTICES.md#yaml-batch-mode). For error-by-error remediation, see [TROUBLESHOOTING_YAML_BATCH.md](TROUBLESHOOTING_YAML_BATCH.md).

Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s `openimc workflow <config.yaml>` command; QP-CAT's variant runs inside QuPath's `script` subcommand so the analysis surface is identical to the dialog.

## Top-level shape

A QP-CAT YAML config is a single document with the following top-level keys. Every key except `version` and `scope.projects` is optional; defaults are documented per block below.

```yaml
version: "1.0"
audit: { ... }
scope: { ... }
clustering: { ... }
phenotyping: { ... }
spatial_stats: { ... }
figure_export: { ... }
on_error: continue
workers: 1
```

> **Note:** Unknown top-level keys are reported as validation errors (E002), not silently ignored. This prevents typo'd block names (`clusterng:`) from being skipped without a warning.

## Stability promise

The YAML schema is part of QP-CAT's public surface. Breaking changes are announced in release notes with at least one minor-version deprecation window. The `version` field gates future migrations: a config that declares `version: "1.0"` will continue to be parseable by all v1.x releases. Schema version `2` introduces breaking changes; `version: "2.0"` configs are rejected by v1 with E007.

## `version` (required, string)

| | |
|---|---|
| Type | string (semver-ish) |
| Required | yes |
| Default | -- |
| Valid values | `"1.0"`, `"1"` (shorthand) |

Schema version. Must be present. v1 accepts `1.0` exactly; `1` is normalised internally to `1.0`. A future minor (`1.1`, `1.2`, ...) warns (W001) but parses; a future major (`2.0`+) hard-fails (E007). A non-semver-shaped string fails E017.

## `audit` (optional, object)

Controls the audit log written for every YAML batch run.

| Key | Type | Default | Notes |
|---|---|---|---|
| `log_dir` (alias `log_path`) | string (path) | `<project>/qpcat/logs` | Where the audit log lives. Relative paths resolve against the first project's directory. Absolute paths used as-is. |
| `log_level` | string | `INFO` | One of `DEBUG`, `INFO`, `WARN`, `ERROR`. Drops `INFO`-and-above when set to `WARN` / `ERROR`. |
| `run_name` | string | `qpcat_batch_<ts>` | Identifier used as the audit log filename prefix and in `OperationLogger` rows. Collisions get `_N` suffix. |
| `capture_prompts` | boolean | `false` | If `phenotyping.llm_explainer.enabled` is true, capture full prompt + response in the audit log. |

> **Note:** `audit.log_dir` resolves against the **first** project in the run when relative; the run log is per-run, not per-project. Users wanting per-project audit logs should write one YAML per project.

## `scope` (required, object)

Which projects and which images to operate on.

| Key | Type | Default | Notes |
|---|---|---|---|
| `projects` | list[string] | required (>=1 entry) | Absolute paths to QuPath `project.qpproj` files (or directories containing one). |
| `images` | string \| list[string] \| object | `"all"` | Image selector. See *Image scoping* below. |
| `skip_missing` | boolean | `false` | If `true`, an image name in the list that isn't in the project warns (W003) but continues. If `false`, hard-fails (E008). |
| `per_image_overrides` | list[object] | -- | Per-image config overrides (deep merge with the top-level blocks). |

```yaml
scope:
  projects:
    - /data/experiments/cohort_2025_q2/project.qpproj
    - /data/experiments/cohort_2025_q3/project.qpproj
  images: { regex: '^Patient[0-9]+_ROI[12]$' }
```

### Image scoping

The `images` field is a union:

| YAML value | Behavior |
|---|---|
| `all` | Every `ProjectImageEntry` in every project. |
| `[name1, name2, ...]` | Each entry matched against `entry.getImageName()`. Missing names hard-fail (E008) unless `skip_missing: true`. |
| `{glob: "<pattern>"}` | Shell-style glob (`*`, `?`, `[abc]`). Zero matches warns (W003). |
| `{regex: "<pattern>"}` | Java regex (anchored implicitly by `Matcher.matches()`). |

`glob` and `regex` are mutually exclusive (E006). Mixing list and map shape errors (E015).

> **Note:** `scope.images` names are project-relative. If two projects each have an image named `Patient03_ROI1`, both are processed. For per-project image lists, write two YAMLs.

## `clustering` (optional, object)

When `clustering` is omitted entirely, the batch skips clustering and expects every image to already have a saved result (for spatial-stats-only or figure-export-only runs).

| Key | Type | Default | Notes |
|---|---|---|---|
| `type` (alias `algorithm`) | string | -- (required unless `mode: reuse_saved`) | One of `leiden`, `louvain`, `kmeans`, `hdbscan`, `agglomerative`, `minibatch_kmeans`, `gmm`, `banksy`, `skip`. Case-insensitive. |
| `mode` | string | `run` | `run` (run a new clustering) or `reuse_saved` (skip clustering; attach `saved_result_name`). |
| `saved_result_name` | string | -- | Required when `mode: reuse_saved`. |
| `resolution` | double | `1.0` | Leiden / Louvain only. Range `(0, 10]`. |
| `k` | int | `10` | KMeans / MiniBatch KMeans only. Range `[2, 200]`. |
| `n_clusters` | int | `10` | Agglomerative / GMM only. |
| `min_cluster_size` | int | `15` | HDBSCAN only. Matches the dialog default; smaller = more clusters, also more noise rows. |
| `linkage` | string | `ward` | Agglomerative only. `ward`, `complete`, `average`, `single`. |
| `banksy_lambda` | double | `0.2` | BANKSY only. |
| `banksy_k_geom` | int | `15` | BANKSY only. |
| `normalization` | string | `zscore` | `none`, `zscore`, `minmax`, `percentile_99`, `log1p`. |
| `embedding` | string | `umap` | `none`, `umap`, `pca`, `tsne`. |
| `umap_n_neighbors` | int | `15` | UMAP only. Range `[2, 200]`. |
| `umap_min_dist` | double | `0.1` | UMAP only. |
| `umap_metric` | string | `euclidean` | UMAP only. `euclidean`, `manhattan`, `cosine`, `correlation`, `chebyshev`. |
| `pca_n_components` | int | `50` | PCA only. Minimum 2. |
| `tsne_perplexity` | double | `30.0` | t-SNE only. Must be < number of cells (clamped if not). |
| `tsne_learning_rate` | double | `200.0` | t-SNE only. Typical 100-1000. |
| `tsne_iterations` | int | `1000` | t-SNE only. Max optimization steps (typical 1,000-5,000). |
| `tsne_early_exaggeration` | double | `12.0` | t-SNE only. Typical 4-12. |
| `embedding_seed` | int | `42` | Random seed for the embedding (UMAP / t-SNE / PCA). Mirrors the GUI Advanced "random seed". |
| `embedding_execution_mode` | `auto` \| `reproducible` \| `fast` | `auto` | Whether UMAP may use every CPU core. umap-learn disables all parallelism when a seed is set, so a seeded run is 6-8x slower. `auto` = seeded below 200,000 cells, all cores above; `reproducible` = always seeded (identical output, one core); `fast` = always all cores (same clusters, layout varies per run). Recorded in the audit log. |
| `random_seed` | int | `42` | Reproducibility seed for the clustering algorithm. -1 = nondeterministic. |
| `result_name` | string | -- (auto-named) | Saved-result key under `<project>/qpcat/cluster_results/`. Defaults to `yaml_<image>` per-image. |
| `measurements` | list[string] | -- (all "Mean" measurements) | Marker set to cluster on. |
| `spatial_smoothing` | boolean | `false` | Run the graph-convolution pre-step. |
| `batch_correction` | boolean | `false` | Apply Harmony. Requires shared marker panel across projects. |
| `batch_key` | `images` \| `areas` | `images` | What Harmony treats as a batch. `areas` requires `area_levels`. See *Independent areas* below. |
| `area_levels` | list[object] | -- (one area per image) | Hierarchy levels that split cells into physically separate areas, so no spatial graph crosses a boundary. See *Independent areas* below. |
| `joint` | boolean | `false` | Cluster all of a project's resolved images **jointly** in one run (globally consistent cluster IDs across images), like the GUI "All / Specific images" scope. `false` = cluster each image **independently**. |

> **Note:** `clustering.mode: reuse_saved` skips re-clustering -- recommended for figure regeneration after paper revisions when cluster IDs must stay stable.

> **`joint` -- per-image vs cross-image clustering.** By default the batch clusters
> **each image on its own** (cluster "3" in image A is unrelated to "3" in image B).
> Set `joint: true` to cluster all resolved images **together** so a cluster ID
> means the same cell population across every image -- the headless equivalent of
> the GUI's project scope. The image set comes from `scope.images`: use
> `images: all` to cluster the whole project jointly, or an explicit list / regex
> to cluster a chosen **subset** jointly. With `joint: true` the combined result is
> saved once under `result_name` (default `yaml_joint`) and labels are written back
> to each image; `joint` is ignored for `mode: reuse_saved`.

### Independent areas (`clustering.area_levels`)

An **area** is a piece of tissue physically separate from every other piece: a TMA core, one of several sections on a slide, an image. No spatial graph -- BANKSY, spatial smoothing, Ripley's L, co-occurrence, Moran's I -- is ever built across two areas, because the distance between a cell in one core and a cell in the next is not a distance through tissue.

Without `area_levels` there is **one area per image**: cells from different images stay separate, but every cell within an image may share a graph with every other.

Levels read **outermost first**, and `images` is implicit -- it is always the outermost level and listing it is an error. Everything **below** the last level stays in one area:

| Key | Type | Notes |
|---|---|---|
| `level` | `tma_cores` \| `annotations` | Required. |
| `annotation_classes` | list[string] | `annotations` only. Which classes mark a boundary; omit or leave empty to treat any annotation as one. |

Areas are keyed on the annotation **object**, not its class -- three tissue sections on one slide usually share a single `Tissue` class, and grouping by class would merge them straight back together.

```yaml
# A 55-core TMA in one image: cluster jointly, but keep each core's
# spatial graph separate and correct batch effects core by core.
clustering:
  type: banksy
  batch_correction: true
  batch_key: areas
  area_levels:
    - level: tma_cores
```

```yaml
# Several tissue sections on one slide, each with Tumor / Stroma inside.
# Tumor and Stroma stay TOGETHER within a section -- they are continuous
# tissue, and the interface between them is usually the thing being measured.
clustering:
  type: leiden
  area_levels:
    - level: annotations
      annotation_classes: [Tissue]
```

> **Choosing a level.** Both extremes fail silently. Too deep (splitting on Tumor / Stroma) invents boundaries inside one specimen and deletes the interface between them; too shallow (images only) invents adjacency between separate specimens. Neither shows up in the output as anything but plausible clusters. The GUI's *Independent areas* control previews the resolved area count and any unassigned cells before the run; a headless run reports the same counts in the log.

> **Cells with no ancestor at the chosen level** -- a cell inside a core but outside any `Tissue` annotation -- get their own area, scoped to the deepest ancestor they did resolve (`A-1 | unassigned`). They are never merged across cores, and the count is warned so a partition cannot quietly swallow cells.

> **Small areas.** `k` is capped per area, so one sparse core cannot drag `k` down for the rest. An area too small for any graph contributes no neighbourhood term rather than borrowing neighbours from another specimen; cells are never dropped.

## `phenotyping` (optional, object)

Runs after clustering. Skipped entirely if omitted or `enabled: false`.

| Key | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. |
| `rules` | list[object] | `[]` | Rule list; each rule has the fields below. |
| `rules[].name` | string | required | Phenotype label (e.g. `T_cell`). |
| `rules[].require_markers` | list[string] | `[]` (required >=1) | Markers required positive. |
| `rules[].exclude_markers` | list[string] | `[]` | Markers required negative. |
| `rules[].require_min_zscore` | double | `1.0` | Minimum z-score for `require_markers`. |
| `rules[].exclude_max_zscore` | double | `1.0` | Maximum z-score for `exclude_markers`. |

> **One gate per marker.** These z-scores are written per rule, but the
> phenotyping engine applies **one gate per marker across all rules**. A marker
> gated at two different thresholds is rejected with **E021** rather than
> approximated -- rules are first-match-wins, so quietly picking one threshold
> would reroute cells with no trace in the log. Leaving the defaults never
> trips it.

> **Phenotyping runs once per project, not per image.** The gates are z-scores,
> which are only comparable across images when the cells are normalized
> together, so all resolved images are phenotyped in a single pass and each is
> saved with its labels. `clustering.normalization` and
> `clustering.measurements` supply the panel; `zscore` is used if absent.
| `llm_explainer` | object | -- | Optional LLM cluster-explainer block. See below. |

### `phenotyping.llm_explainer` (optional, object)

| Key | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | `false` | Set to `true` to invoke. |
| `provider` | string | -- (required when enabled) | `anthropic` or `ollama`. |
| `model` | string | -- (required when enabled) | Anthropic model name or Ollama model tag. |
| `key_from_env` (alias `api_key_env`) | string | -- (required when `provider: anthropic`) | Environment variable name holding the API key. **Never inline the key in YAML.** |
| `ollama_url` | string | `http://localhost:11434` | Ollama endpoint. |
| `timeout_seconds` | int | `60` | Per-cluster timeout. |
| `prompt_template_version` | string | `v1` | Pin the prompt template version. |

> **Note:** The Anthropic API key MUST come from an environment variable. The parser errors out (E018) if it sees a literal-looking key (`sk-ant-*`) in the YAML.

## `spatial_stats` (optional, object)

Runs as part of clustering when `clustering.mode: run`. With `reuse_saved`, statistics cannot be re-run without re-clustering (v1 limitation).

| Key | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. |
| `graph` | object | `{type: knn, k: 15}` | Graph constructor for every statistic. |
| `statistics` | list[string] | `[]` | At least one entry. Valid slugs below. |
| `permutations` | string \| int | `auto` | `auto` = Feature A adaptive default (1000/100/50 by cell count); integer 1-10000 = fixed. |
| `persist_plots` | boolean | `true` | Write per-stat PNGs to the saved-result's plot directory so `figure_export` can pick them up. |

Valid statistic slugs: `moran_i`, `geary_c`, `ripley` (alias of `ripley_k` + `ripley_l`), `ripley_k`, `ripley_l`, `co_occurrence_pairwise`, `co_occurrence_one_vs_rest`, `cooccurrence_pairwise`, `cooccurrence_one_vs_rest`, `neighborhood_enrichment`.

### Graph constructor sub-table

| Key | Type | Default | Notes |
|---|---|---|---|
| `type` | string | `knn` | `knn`, `radius`, `delaunay`. |
| `k` | int | `15` | kNN only. Range `[2, 200]`. |
| `radius` | double | `-1` (auto) | Radius only (pixel units). |
| `max_edge` | double | `-1` (no pruning) | Delaunay only (pixel units). |

> **Note:** `spatial_stats.statistics` slugs mirror Feature A's `SpatialStatsScripts` keys 1:1. See [SCRIPTING.md `SpatialStatsScripts`](SCRIPTING.md#spatialstatsscripts).

## `figure_export` (optional, object)

Dispatches into Feature B's `BatchFigureExporter`. Skipped entirely if omitted or `enabled: false`.

| Key | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. |
| `output_dir` | string (path) | -- (required when enabled) | Directory to write figures into. Relative paths resolve against the first project. |
| `figures` | string \| list[string] | `all_matplotlib` | Either `all_matplotlib` (the saved matplotlib PNGs), `all` (those **plus** the cluster-composition figures and tables), `none`, or a list of plot-kind slugs. |
| `formats` | list[string] | `[png]` | One or more of `png`, `tiff`. |
| `dpi` | int | `300` | Range `[72, 1200]`. |
| `filename_pattern` | string | `{image}_{plot}.{ext}` | Tokens: `{image}`, `{plot}`, `{result_name}`, `{date}`, `{ext}`. Must contain `{image}`, `{plot}`, `{ext}` (E012). |
| `result_name` | string | -- (falls back to `clustering.result_name`) | Saved-result key to export from. |
| `overwrite_existing` | boolean | `false` | If `false`, fails on first existing file. |
| `skip_missing_plots` | boolean | `true` | If `true`, missing plots are recorded as failures but the export continues. |

### Filename slug shorthand

The slug `ripley` in `figure_export.figures` is shorthand that **expands to both** `ripley_k` and `ripley_l` at validation time. The expansion is deduplicated: `[ripley_k, ripley, ripley_l]` is equivalent to `[ripley_k, ripley_l]`.

> **Note:** JavaFX-only plot kinds (`heatmap`, `embedding_interactive`, `autoencoder_pie`, `histogram`) cannot be exported headlessly and fail E011 at validation time. Drop them or use the interactive Export Figures dialog.

### Cluster-composition figures

The eight `composition_*` slugs are rendered in Java from the saved result, so they export headlessly like the matplotlib slugs -- no open image and no plotting options needed at clustering time. They come in four pie/table pairs, one per grouping axis: `_image`, `_annotation`, `_area` and `_class`.

They are written **once per run**, not once per image, because composition describes how the result's clusters split across all its images, annotations, areas or classes. Their `{image}` token expands to `all-images`, and the four table slugs always write `.csv` regardless of `formats`.

Each pair is conditional, and asking for one a result cannot support gives a recorded **failure naming the reason**, not a silent empty file:

| Pair | Needs |
|---|---|
| `_image` | per-cell image references (any result saved by a current version) |
| `_annotation` | a result clustered on annotation INPUT (keyed on the annotation's name) |
| `_area` | a run split into 2 or more [independent areas](#independent-areas-clusteringarea_levels) |
| `_class` | 2 or more annotation classes present (keyed on the CLASS, so it stays readable however many named regions there are) |


`figures: all` includes all eight; `figures: all_matplotlib` does not.

## `on_error` (optional, string)

| | |
|---|---|
| Type | string |
| Default | `continue` |
| Valid values | `continue`, `stop`, `retry:N` |

Per-image error policy. `continue` skips failed images and proceeds (exits 1 at end); `stop` aborts on first failure (exits 3); `retry:N` retries the failing image up to N times (0-10) before falling through.

## `workers` (optional, int)

| | |
|---|---|
| Type | int |
| Default | `1` |
| Valid values | `1` in v1 |

v1 clamps `workers` to 1; passing any other value warns (W002) and coerces. Parallel workers are reserved for v1.1.

---

## Examples

### Minimal config

```yaml
version: "1.0"
scope:
  projects: [/data/experiments/sanity_check/project.qpproj]
clustering: { type: leiden }
```

### Joint clustering across a subset (globally consistent cluster IDs)

```yaml
version: "1.0"
scope:
  projects: [/data/experiments/cohort_2025_q2/project.qpproj]
  images: [Patient01_ROI1, Patient02_ROI1]   # or 'images: all' for the whole project
clustering:
  type: leiden
  resolution: 0.8
  normalization: zscore
  embedding: umap
  joint: true                                 # cluster the listed images TOGETHER
  result_name: cohort_q2_joint
```

The two listed images are clustered in one pass, so a cluster ID means the same
population in both, and labels are written back to each. Drop `joint` (or set it
`false`) to cluster each image independently instead.

### Typical multi-image clustering + rule-based phenotyping

```yaml
version: "1.0"
audit:
  log_dir: ./qpcat/logs
scope:
  projects:
    - /data/experiments/cohort_2025_q2/project.qpproj
    - /data/experiments/cohort_2025_q3/project.qpproj
  images: { regex: '^Patient[0-9]+_ROI[12]$' }
clustering:
  type: leiden
  resolution: 0.8
  normalization: zscore
  embedding: umap
  joint: true
  spatial_smoothing: true
  random_seed: 42
phenotyping:
  rules:
    - name: T_cell
      require_markers: [CD3]
      require_min_zscore: 1.0
    - name: B_cell
      require_markers: [CD19, CD20]
      require_min_zscore: 1.0
    - name: Macrophage
      require_markers: [CD68]
      exclude_markers: [CD3, CD19]
      require_min_zscore: 1.0
spatial_stats:
  enabled: true
  graph: { type: knn, k: 15 }
  statistics: [neighborhood_enrichment, moran_i, ripley_l]
figure_export:
  enabled: true
  output_dir: ./qpcat/figures/2026-05-13_revision1
  figures: [dotplot, neighborhood, ripley_l, spatial_scatter]
  formats: [png]
  dpi: 300
on_error: continue
```

### Spatial-stats-only on saved results

```yaml
version: "1.0"
scope:
  projects: [/data/experiments/cohort_2025_q2/project.qpproj]
  images: all
clustering:
  mode: reuse_saved
  saved_result_name: leiden_res0.8_final
spatial_stats:
  enabled: true
  graph: { type: delaunay, max_edge: 80.0 }
  statistics: [ripley_k, ripley_l, geary_c, cooccurrence_pairwise]
  permutations: 1000
  persist_plots: true
figure_export:
  enabled: true
  output_dir: ./qpcat/figures/spatial_addendum
  figures: [ripley, geary_c, cooc_pairwise]   # 'ripley' expands to k+l
  formats: [png, tiff]
  dpi: 600
```

### Figure-export-only (regenerate from saved results)

```yaml
version: "1.0"
scope:
  projects: [/data/experiments/cohort_2025_q2/project.qpproj]
  images: all
clustering:
  mode: reuse_saved
  saved_result_name: leiden_res0.8_final
figure_export:
  enabled: true
  output_dir: ./qpcat/figures/2026-05-13_revision2_highres
  figures: all_matplotlib
  formats: [png, tiff]
  dpi: 600
  filename_pattern: "{result_name}_{image}_{plot}.{ext}"
  overwrite_existing: true
```

### With LLM cluster explainer

```yaml
version: "1.0"
audit:
  capture_prompts: true
scope:
  projects: [/data/experiments/cohort_2025_q2/project.qpproj]
  images: all
clustering:
  type: leiden
  resolution: 0.6
  normalization: zscore
  embedding: umap
phenotyping:
  rules:
    - name: T_cell
      require_markers: [CD3]
  llm_explainer:
    enabled: true
    provider: anthropic
    model: claude-sonnet-4-5
    key_from_env: QPCAT_ANTHROPIC_KEY
    prompt_template_version: v1
figure_export:
  enabled: true
  output_dir: ./qpcat/figures/llm_run
  figures: [dotplot, neighborhood]
  formats: [png]
  dpi: 300
```

---

## Validation surface

`qpcat_batch.groovy` validates the YAML against the schema before opening any project. Pass `--args=<file.yaml> --args=--dry-run` to validate without executing. Validation errors include:

- E001 Missing required field (e.g. `version`, `scope.projects`, `clustering.type`)
- E002 Unknown field at a known block (typo)
- E003 Type mismatch (e.g. `dpi: "300"` instead of `300`)
- E004 Value out of range
- E005 Enum mismatch
- E006 Mutually exclusive fields set (e.g. `glob` + `regex`)
- E007 Schema major version unsupported
- E008 Image listed in `scope.images` not found in any project
- E009 Unknown statistic slug
- E011 JavaFX-only plot kind requested (not exportable headlessly)
- E012 Filename pattern missing required token
- E014 Project file not found
- E015 Mixed list / map shape in `scope.images`
- E017 Malformed `version` string
- E018 Inline API key in YAML, or missing env var
- E019 Output directory not writable
- E020 Invalid override block in `per_image_overrides`

Warnings (non-fatal):

- W001 Schema minor-version mismatch
- W002 `workers > 1` coerced to 1
- W003 Image scope filter matched zero images for a project

> **Note:** Every validation error includes the YAML field path and an `E0xx` / `W0xx` code so CI scripts can grep deterministically.
