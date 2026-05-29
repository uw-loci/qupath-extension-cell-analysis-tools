# QP-CAT -- Scripting (Groovy)

Programmatic access to QP-CAT's spatial graph, spatial-statistics, figure-export, and YAML-batch surfaces, callable from QuPath workflow scripts. v1 covers spatial graph construction, the stats catalog (Ripley K/L, Geary's C, co-occurrence, Moran's I, neighborhood enrichment), batch figure export, and the YAML headless-batch runner. Clustering, phenotyping, embeddings, and the LLM explainer are **not** scriptable in v1 -- see the v2 roadmap at the bottom of this page.

## When to script vs. use the dialog

- **Use the dialog** for exploratory analysis, one-off images, and any work where you want to inspect intermediate results visually.
- **Use the script** for batch processing across a project, reproducible recompute on saved results, or any pipeline that needs to integrate QP-CAT with other QuPath steps.

## Invocation pattern

QP-CAT scripting follows QuPath's standard pattern: import the static facade, call it from a Groovy script (typically inside **Automate > Script editor** or as part of a recorded workflow). The reference precedent is `qupath.ext.gatedobjclassifier.scripting.GatedObjectClassifierScripts` -- single options-map argument, static methods, no UI dependency.

```groovy
import qupath.ext.qpcat.scripting.SpatialGraphScripts
import qupath.ext.qpcat.scripting.SpatialStatsScripts

// Stage the graph constructor parameters
def graph = SpatialGraphScripts.buildGraph([
    type:    "knn",       // "knn" (default), "radius", or "delaunay"
    k:       15,          // kNN only
    radius:  -1,          // radius only; -1 = auto
    maxEdge: -1           // Delaunay only; -1 = no pruning
])

// Stage each statistic against the same graph
def ripley = SpatialStatsScripts.ripley(graph, [
    maxRadius:    -1,        // -1 = auto from data extent
    nPermutations: -1        // -1 = adaptive default (matches dialog)
])

def geary = SpatialStatsScripts.gearyC(graph, [
    nPermutations: -1
])
```

Each method returns a normalised, canonical options map. To execute, pass these maps to the clustering workflow via the standard `Run Clustering...` dialog or wire them into a recorded workflow step that calls into the dialog programmatically. v1 ships the option-map vocabulary as the public API; full end-to-end "run from script without the dialog" is on the v2 roadmap.

> **Stability promise (v1).** The package path (`qupath.ext.qpcat.scripting`), class names (`SpatialGraphScripts`, `SpatialStatsScripts`, `FigureExportScripts`, `YamlBatchScripts`), method names (`buildGraph`, `ripley`, `gearyC`, `coOccurrence`, `moranI`, `neighborhoodEnrichment`, `exportFigures`, `runBatch`), and recognised option keys listed in this document are part of QP-CAT's public scripting API. Breaking changes will be announced in release notes and accompanied by a deprecation period of at least one minor version. New option keys may be added at any time (additive); semantics of existing keys will not change without notice.

## SpatialGraphScripts

Static facade for graph construction. Returns a `Map<String, Object>` describing the graph constructor that subsequent `SpatialStatsScripts` methods reference.

### `buildGraph(Map opts) -> Map<String, Object>`

| Option key | Type | Default | Notes |
|---|---|---|---|
| `type` | String | `"knn"` | One of `"knn"`, `"radius"`, `"delaunay"`. Case-insensitive. |
| `k` | int | 15 | kNN only. Number of nearest neighbors per cell. |
| `radius` | double | -1 (auto) | Radius only. Pixel units of detection centroids. -1 = auto-derive from median nearest-neighbor distance times 5. |
| `maxEdge` | double | -1 (no pruning) | Delaunay only. Drop edges longer than this (pixel units). -1 = keep all. |

**Returns:** the canonical options map with the four keys above. Pass it to `SpatialStatsScripts` methods as the `graphHandle` argument so every statistic references the same neighborhood definition.

**Example:**

```groovy
// Build a radius-based graph at 30 microns (assuming 1 micron / pixel)
def graph = SpatialGraphScripts.buildGraph([
    type:   "radius",
    radius: 30.0
])
```

### `buildGraph() -> Map<String, Object>`

Convenience overload returning the literal-default map (kNN, k = 15, radius = -1, maxEdge = -1). Equivalent to `buildGraph([:])`.

## SpatialStatsScripts

Static facade for the spatial-statistics catalog. Each method takes a graph handle plus an options map and returns a normalised options map for the corresponding statistic.

### `ripley(graphHandle, Map opts) -> Map<String, Object>`

Stages Ripley's K and L for every cluster (or the subset listed in `clusters`).

| Option key | Type | Default | Notes |
|---|---|---|---|
| `maxRadius` | double | -1 (auto) | Largest r to evaluate (pixel units). |
| `nSteps` | int | 50 | Number of r values between 0 and `maxRadius`. |
| `nPermutations` | int | -1 (adaptive) | Permutation count. -1 = adaptive default (1000 / 100 / 50 by cell count). |
| `clusters` | List<String> | empty = all | Cluster labels to evaluate. Empty = all clusters present on detections. |
| `persistPlots` | boolean | true | Also write `ripley_k_l.png` to the per-result plot directory so the Multi-Figure Batch Export dialog can pick it up. Defaults to the `qpcat.spatial.persistPlots` preference when omitted. |

**Example:**

```groovy
def graph  = SpatialGraphScripts.buildGraph([type: "knn", k: 15])
def ripley = SpatialStatsScripts.ripley(graph, [
    maxRadius:     100.0,
    nSteps:        20,
    nPermutations: 100
])
```

### `gearyC(graphHandle, Map opts) -> Map<String, Object>`

Stages Geary's C for the listed measurements (or every numeric measurement when `measurements` is omitted).

| Option key | Type | Default | Notes |
|---|---|---|---|
| `measurements` | List<String> | empty = all numeric | Measurements to evaluate. |
| `nPermutations` | int | -1 (adaptive) | Permutation count. |
| `persistPlots` | boolean | true | Also write `geary_c.png` to the per-result plot directory. |

**Example:**

```groovy
def graph = SpatialGraphScripts.buildGraph()
def geary = SpatialStatsScripts.gearyC(graph, [
    measurements: ["CD3: Mean", "CD8: Mean", "CD20: Mean"]
])
```

### `moranI(graphHandle, Map opts) -> Map<String, Object>`

Same signature as `gearyC`; sets `method = "moran"` for downstream dispatch. Surfaces the existing v0 statistic so a Groovy script can re-run it against a different graph constructor without going through the dialog.

### `coOccurrence(graphHandle, Map opts) -> Map<String, Object>`

Stages co-occurrence as a function of radius.

| Option key | Type | Default | Notes |
|---|---|---|---|
| `mode` | String | `"pairwise"` | One of `"pairwise"`, `"oneVsRest"`. Accepts `"one_vs_rest"` / `"one-vs-rest"` as synonyms. |
| `minRadius` | double | -1 (auto) | Smallest r (pixel units). |
| `maxRadius` | double | -1 (auto) | Largest r (pixel units). |
| `nIntervals` | int | 50 | Number of radius bins. |
| `persistPlots` | boolean | true | Also write `co_occurrence_pairwise.png` or `co_occurrence_one_vs_rest.png` (per `mode`) to the per-result plot directory. |

**Example:**

```groovy
def graph = SpatialGraphScripts.buildGraph([type: "delaunay", maxEdge: 60.0])
def coOcc = SpatialStatsScripts.coOccurrence(graph, [
    mode:       "oneVsRest",
    minRadius:  10.0,
    maxRadius:  200.0,
    nIntervals: 40
])
```

### `neighborhoodEnrichment(graphHandle, Map opts = [:]) -> Map<String, Object>`

Stages the cluster-pair Z-score matrix (the existing v0 statistic). Options map is currently empty; reserved for future extension.

## SpatialConnectionsScripts

Static facade for the v0.3 spatial graph overlay. Materialises the spatial neighbor graph from a saved spatial-stats result as `PathObjectConnections` on the active `ImageData`, and toggles the post-hoc same-class edge filter without re-running clustering.

### `pushConnectionsToViewer(ImageData imageData, String resultName) -> void`

Reads the named saved spatial-stats result from the active project and materializes its graph as `PathObjectConnections` on `imageData`. Equivalent to clicking "Push to viewer now" in the **Run Clustering...** dialog. Raises `IllegalStateException` when no project is open, the named result does not exist, the saved result has no spatial-stats bundle, or the detection count does not match.

The action is recorded in the image's history workflow so it can be replayed.

```groovy
import qupath.ext.qpcat.scripting.SpatialConnectionsScripts

// Push the saved spatial-stats result named "default" onto the current viewer
SpatialConnectionsScripts.pushConnectionsToViewer(
    getCurrentImageData(),
    "default"
)
```

### `applySameClassFilter(ImageData imageData, boolean enabled) -> void`

Toggle the post-hoc same-class edge filter on the currently-attached `PathObjectConnections`. When `enabled` is `true`, the unfiltered source group is stashed under the ImageData property `QPCAT_SPATIAL_OVERLAY_SOURCE_GROUP`, a fresh filtered group is built via `removeGroup + addGroup`, and a hierarchy change event fires. When `enabled` is `false`, the stashed source group is restored.

Cells with no `PathClass` drop their edges entirely under the filter. This is intended behaviour -- the filter is a viewer affordance; it does not retroactively change any computed statistic.

```groovy
import qupath.ext.qpcat.scripting.SpatialConnectionsScripts

// Hide cross-class edges in the overlay
SpatialConnectionsScripts.applySameClassFilter(getCurrentImageData(), true)
```

The underlying `PathObjectConnections` API is marked `@Deprecated` in QuPath 0.7. See [REFERENCES.md -- Spatial Graph Overlay](REFERENCES.md#spatial-graph-overlay-pathobjectconnections) and [HOW_TO_GUIDE chapter 21 -- API deprecation note](HOW_TO_GUIDE.md#api-deprecation-note) for the rationale.

## Result types

When the dialog dispatches the staged option maps, results materialise as:

- `qupath.ext.qpcat.model.RipleyResult` -- carries cluster names, the radius axis, per-cluster K(r) and L(r) curves, the Poisson null curves, and the per-cluster p-values.
- `qupath.ext.qpcat.model.GearyCResult` -- per-marker `{c, pValue}` map.
- `qupath.ext.qpcat.model.CoOccurrenceResult` -- the radius-vs-ratio tensor (`[clusterA][clusterB][interval]` for pairwise, `[cluster][1][interval]` for one-vs-rest), the interval axis, and the cluster names.

All three are Gson-serialisable and persist on `SavedClusteringResult.spatialStats` so reopening a saved result re-renders the same charts and tables.

## FigureExportScripts

Static facade for batch figure export. Writes one or more plots from saved clustering results to a directory. Matches the behavior of the **Extensions > QP-CAT > Export Figures...** dialog but without the GUI -- callable from scripts, the **Run for project** workflow, and the QP-CAT YAML batch (Feature C).

> **Headless plot scope.** The scripting API exports only **saved matplotlib plots** (dotplot, matrix plot, PAGA, stacked violin, scanpy embedding, neighborhood enrichment matrix, spatial scatter, plus the Feature A spatial-stats line charts when the Feature A persisted-PNG path is implemented). The four JavaFX-rendered plots (heatmap canvas, embedding scatter canvas, autoencoder pie chart, histogram canvas) are GUI-thread-only and require the interactive **Export Figures** dialog -- the script call records them as failures in the returned `ExportResult` and continues. This restriction is per v1 architect design; v1.1 may lift it via off-screen scene-graph rendering on `Platform.runLater`.

### `exportFigures(Map opts) -> ExportResult`

Exports figures from one or more images to a directory.

| Option key | Type | Default | Notes |
|---|---|---|---|
| `imageNames` | `List<String>` | empty = current image only | Image names within the project. Empty = current image; explicit list = those images; `["*"]` = every image in the project. |
| `plotKinds` | `List<String>` | empty = every matplotlib kind | Plot kind slugs to export. Valid keys: `dotplot`, `matrixplot`, `paga`, `violin`, `embedding_scanpy`, `neighborhood`, `spatial_scatter`, `ripley_k`, `ripley_l`, `geary_c`, `cooc_pairwise`, `cooc_one_vs_rest`. (The four JavaFX-only kinds -- `heatmap`, `embedding_interactive`, `autoencoder_pie`, `histogram` -- are accepted but recorded as failures.) Unknown slugs are warned and skipped. |
| `formats` | `List<String>` | `["png"]` | One or more of `"png"`, `"tiff"`. v1.1 adds `"svg"`, `"pdf"`, `"eps"`. Unrecognised values are warned and skipped. |
| `dpi` | int | 300 | Output DPI for raster formats. Range 72-1200. Ignored for vector formats (when they land in v1.1). |
| `outputDir` | `String` or `Path` | required | Directory to write into. Created if it does not exist. |
| `filenamePattern` | `String` | `"{image}_{plot}.{ext}"` | Substitution tokens: `{image}`, `{plot}`, `{result_name}`, `{date}`, `{ext}`. Must contain at least `{image}`, `{plot}`, `{ext}`. |
| `resultName` | `String` | inferred from image | Saved-result name to export from. If omitted, the per-image fall-back logic in `BatchFigureExporter` picks the best match (a saved result whose name matches or starts with the image name; otherwise the most-recent saved result). |
| `overwriteExisting` | boolean | false | If false, the exporter fails-fast on the first existing file (per-file failure row in `ExportResult.failures`). |
| `skipMissingPlots` | boolean | true | If true, missing plots are recorded as failures but the export continues. |

**Returns:** `ExportResult` -- a thin POJO with `getFilesWritten()`, `getFailures()` (list of per-file failure messages, including "skipped (JavaFX-only plot...)" rows), `getTotalBytes()`, `isCancelled()`, and `summary()`.

**Logs:** one audit-log row (`FIGURE EXPORT`) on the project's `qpcat/logs/qpcat_YYYY-MM-DD.log` capturing output directory, image / plot subset, format / DPI, filename pattern, file count, and bytes. (The dialog produces the same log row; the headless path skips it on direct `BatchFigureExporter` calls, so wire `OperationLogger` yourself if you need audit rows from a Groovy pipeline.)

**Example: export a 5-image subset as PNG at 300 DPI**

```groovy
import qupath.ext.qpcat.scripting.FigureExportScripts

def imageSubset = [
    "Patient03_ROI1",
    "Patient03_ROI2",
    "Patient07_ROI1",
    "Patient09_ROI1",
    "Patient12_ROI2"
]

def result = FigureExportScripts.exportFigures([
    imageNames:      imageSubset,
    plotKinds:       [],                        // empty = every matplotlib kind
    formats:         ["png"],
    dpi:             300,
    outputDir:       "/home/me/paper-cd8/figures/2026-05-13_revision1",
    filenamePattern: "{image}_{plot}.{ext}",
    overwriteExisting: false,
    skipMissingPlots:  true
])

println "Wrote ${result.getFilesWritten()} files; ${result.getFailures().size()} failures"
result.getFailures().each { println "  $it" }
```

**Example: export every image, every matplotlib plot, as both PNG and TIFF**

```groovy
import qupath.ext.qpcat.scripting.FigureExportScripts

def result = FigureExportScripts.exportFigures([
    imageNames: ["*"],                                  // all images
    formats:    ["png", "tiff"],
    dpi:        600,
    outputDir:  "/home/me/group-meeting/figures"
])

println result.summary()
```

### Plot-kind reference

| Key | Source | JavaFX-only? |
|---|---|---|
| `dotplot` | scanpy `sc.pl.rank_genes_groups_dotplot` saved PNG | No -- exportable headlessly |
| `matrixplot` | scanpy `sc.pl.matrixplot` saved PNG | No |
| `paga` | scanpy `sc.pl.paga` saved PNG | No |
| `violin` | scanpy `sc.pl.stacked_violin` saved PNG | No |
| `embedding_scanpy` | scanpy `sc.pl.embedding` saved PNG | No |
| `neighborhood` | squidpy `sq.pl.nhood_enrichment` saved PNG | No |
| `spatial_scatter` | scanpy `sc.pl.spatial` saved PNG | No |
| `ripley_k`, `ripley_l`, `geary_c`, `cooc_pairwise`, `cooc_one_vs_rest` | Feature A persisted PNGs (when the spatial-stats PNG-output enhancement is enabled) | No (when persisted) |
| `heatmap`, `embedding_interactive` | JavaFX `Canvas.snapshot()` | **Yes -- requires open dialog** |
| `autoencoder_pie`, `histogram` | JavaFX `PieChart` / `Canvas` snapshot | **Yes -- requires open dialog** |

If `plotKinds` includes a JavaFX-only key when called from script mode, the call records it as a failure in `ExportResult.getFailures()` and continues with the remaining plots.

### Ripley slug shorthand

The YAML schema accepts `ripley` in `figure_export.figures` as shorthand that expands to both `ripley_k` and `ripley_l` at validation time. The Groovy `FigureExportScripts.exportFigures` facade does **not** auto-expand this shorthand -- pass both slugs explicitly (`plotKinds: ["ripley_k", "ripley_l"]`) or use the YAML batch entry point if you want the shorthand. See [YAML_SCHEMA.md "Filename slug shorthand"](YAML_SCHEMA.md#filename-slug-shorthand) for the YAML-side rule.

### Integration with the YAML batch (Feature C)

The YAML batch executor passes its `figure_export` config block straight into `FigureExportScripts.exportFigures(...)`. The YAML schema's `figure_export.images` becomes `imageNames`; `figure_export.figures` becomes `plotKinds` (with the `ripley` slug shorthand already expanded); `figure_export.formats` / `figure_export.dpi` / `figure_export.output_dir` map 1:1. The YAML batch is the primary consumer of this scripting surface in v1 -- the dialog is for ad-hoc / exploratory exports, the script for reproducible pipelines.

## YamlBatchScripts

Static facade for invoking the YAML headless-batch runner from a Groovy script. The same entry point that `qpcat_batch.groovy` uses, exposed for users who want to construct or transform the YAML config in Groovy before running it.

The primary user-facing surface for headless QP-CAT is the YAML config file consumed by `QuPath script qpcat_batch.groovy --args=config.yaml`. The Groovy facade is the layer underneath -- useful when you want to:

- Modify a config before running (e.g., bump `dpi` for a poster pass).
- Run the batch from inside an existing Groovy workflow that has other QuPath steps before / after.
- Re-use the validator + orchestrator from a custom CI harness.

For users who just want to run an existing YAML file from the command line, ignore this section and use `qpcat_batch.groovy` directly per [HOW_TO_GUIDE section 19](HOW_TO_GUIDE.md#19-yaml-headless-batch).

### `runBatch(Map opts) -> BatchOutcome`

Runs the YAML batch from a YAML file path or an inline YAML string.

| Option key | Type | Default | Notes |
|---|---|---|---|
| `config` | `Path` / `String` (path) / `String` (YAML content) | required | Either a path to a YAML file on disk, or an inline YAML string (auto-detected by leading `version:` token). |
| `dryRun` | boolean | `false` | If `true`, validate + describe without executing. Returns a `BatchOutcome` with `isDryRun() == true`. |
| `emitter` | `ProgressEmitter` | `StdoutProgressEmitter` | Override to capture progress in tests / other surfaces. |

**Returns:** `YamlBatchOrchestrator.BatchOutcome` -- a thin POJO with `getImages()` (per-image successes / failures), `getExitCode()`, `getTotalElapsedMs()`, `getYamlSha256()`, `isDryRun()`, `getFiguresWritten()`, `succeeded()` / `failed()`.

**Example: run an existing YAML file**

```groovy
import qupath.ext.qpcat.scripting.YamlBatchScripts

def outcome = YamlBatchScripts.runBatch([
    config: "/path/to/analysis.yaml"
])

println "Exit code: ${outcome.getExitCode()}"
println "Succeeded: ${outcome.succeeded()} / ${outcome.getImages().size()}"
outcome.getImages().findAll { !it.isSuccess() }.each {
    println "  FAILED: ${it.getImageName()} -- ${it.getError()}"
}
```

**Example: validate (dry-run) before running**

```groovy
import qupath.ext.qpcat.scripting.YamlBatchScripts

def yamlPath = "/path/to/analysis.yaml"

// Validate first -- non-zero exit code means E0xx errors surfaced.
def dry = YamlBatchScripts.runBatch([config: yamlPath, dryRun: true])
if (dry.getExitCode() != 0) {
    println "Validation failed; aborting."
    return
}

// All clear -- run for real.
def outcome = YamlBatchScripts.runBatch([config: yamlPath])
println "Run complete; exit code ${outcome.getExitCode()}"
```

### Integration with the YAML file entry point

`qpcat_batch.groovy` is a thin shim that parses its `--args` argument and calls `YamlBatchScripts.runBatch(config: <yaml-path>)`. Anything you can express via the YAML file works through the scripting facade as well. The file entry point is the recommended user-facing surface (diff-friendly, committable); the Groovy facade is for users who specifically need programmatic dispatch.

## Batch Workflows

A pure-Groovy workflow that walks a directory of projects:

```groovy
import qupath.ext.qpcat.scripting.YamlBatchScripts
import java.nio.file.Files
import java.nio.file.Paths

def yamlTemplate = """
version: '1.0'
scope:
  projects: [{{PROJECT}}]
clustering:
  type: leiden
  resolution: 0.8
  normalization: zscore
  random_seed: 42
figure_export:
  enabled: true
  output_dir: ./qpcat/figures
  figures: [dotplot, neighborhood]
  formats: [png]
  dpi: 300
on_error: continue
"""

def projectsRoot = Paths.get("/data/experiments")
def projectFiles = Files.walk(projectsRoot, 2)
    .filter { it.getFileName().toString() == "project.qpproj" }
    .collect()

projectFiles.each { pf ->
    def yaml = yamlTemplate.replace("{{PROJECT}}", pf.toString())
    def outcome = YamlBatchScripts.runBatch([
        config: yaml
    ])
    println "${pf}: exit=${outcome.getExitCode()}"
}
```

## Adaptive permutation default

Use `SpatialStatsScripts.PERMUTATIONS_ADAPTIVE` (== -1) to request the adaptive default. The Java side resolves the actual permutation count from the cell count just before dispatching the Python task; the resolved value is recorded in the audit-log row for each statistic.

## Audit log

Every staged statistic that runs through the dialog produces one row in the project's `qpcat/logs/qpcat_YYYY-MM-DD.log` file:

- `=== SPATIAL GRAPH === ...` -- one row per graph build
- `=== SPATIAL STATS RIPLEY === ...` -- one row per Ripley K/L run
- `=== SPATIAL STATS GEARY === ...` -- one row per Geary's C run
- `=== SPATIAL STATS COOC PAIRWISE === ...` -- one row per pairwise co-occurrence run
- `=== SPATIAL STATS COOC ONE-VS-REST === ...` -- one row per one-vs-rest run
- `=== LLM EXPLAIN === ...` -- one row per cluster-explainer call (provider, model, prompt template version, token counts; prompt + response captured under indented blocks)
- `=== YAML BATCH START === ...` / `=== YAML BATCH END === ...` -- bracketing rows for every YAML batch run, with YAML SHA-256 + duration + per-image outcome summary
- `--- FIGURE EXPORT --- ...` -- one row per dialog-driven batch figure export

Each row captures method name, graph type, permutation count, cell count, and a short result summary. Re-running with different graph constructors leaves a clear comparative trail.

## Not in v1 (deferred)

- **Clustering, embedding, phenotyping, autoencoder training/inference, foundation-model extraction, zero-shot phenotyping, LLM cluster explainer.** All still dialog-only.
- **Reading results back as `AnnData`.** Use the existing AnnData export from the dialog or the script-level **QP-CAT > Export AnnData** menu.
- **End-to-end "run a stats call from a script without the dialog."** v1 stages the option-map vocabulary; v2 will execute the full pipeline programmatically.

## v2 roadmap

- Scriptable clustering with the same options map as the dialog.
- Scriptable AnnData round-trip (`exportAnnData(...)`, `importAnnData(...)`).
- Result-attach helpers so script-generated stats can populate the `SavedClusteringResult` of the active session.

If you need any of the deferred features urgently, file an issue with your use case attached.
