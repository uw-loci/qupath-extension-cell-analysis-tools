# QP-CAT -- Scripting (Groovy)

Programmatic access to QP-CAT's spatial graph and spatial-statistics surface, callable from QuPath workflow scripts. v1 covers spatial graph construction and the stats catalog (Ripley K/L, Geary's C, co-occurrence, Moran's I, neighborhood enrichment). Clustering, phenotyping, embeddings, and the LLM explainer are **not** scriptable in v1 -- see the v2 roadmap at the bottom of this page.

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

> **Stability promise (v1).** The package path (`qupath.ext.qpcat.scripting`), class names (`SpatialGraphScripts`, `SpatialStatsScripts`), method names (`buildGraph`, `ripley`, `gearyC`, `coOccurrence`, `moranI`, `neighborhoodEnrichment`), and recognised option keys listed in this document are part of QP-CAT's public scripting API. Breaking changes will be announced in release notes and accompanied by a deprecation period of at least one minor version. New option keys may be added at any time (additive); semantics of existing keys will not change without notice.

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

## Result types

When the dialog dispatches the staged option maps, results materialise as:

- `qupath.ext.qpcat.model.RipleyResult` -- carries cluster names, the radius axis, per-cluster K(r) and L(r) curves, the Poisson null curves, and the per-cluster p-values.
- `qupath.ext.qpcat.model.GearyCResult` -- per-marker `{c, pValue}` map.
- `qupath.ext.qpcat.model.CoOccurrenceResult` -- the radius-vs-ratio tensor (`[clusterA][clusterB][interval]` for pairwise, `[cluster][1][interval]` for one-vs-rest), the interval axis, and the cluster names.

All three are Gson-serialisable and persist on `SavedClusteringResult.spatialStats` so reopening a saved result re-renders the same charts and tables.

## Adaptive permutation default

Use `SpatialStatsScripts.PERMUTATIONS_ADAPTIVE` (== -1) to request the adaptive default. The Java side resolves the actual permutation count from the cell count just before dispatching the Python task; the resolved value is recorded in the audit-log row for each statistic.

## Audit log

Every staged statistic that runs through the dialog produces one row in the project's `qpcat/logs/qpcat_YYYY-MM-DD.log` file:

- `=== SPATIAL GRAPH === ...` -- one row per graph build
- `=== SPATIAL STATS RIPLEY === ...` -- one row per Ripley K/L run
- `=== SPATIAL STATS GEARY === ...` -- one row per Geary's C run
- `=== SPATIAL STATS COOC PAIRWISE === ...` -- one row per pairwise co-occurrence run
- `=== SPATIAL STATS COOC ONE-VS-REST === ...` -- one row per one-vs-rest run

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
