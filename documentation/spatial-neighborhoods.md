# Neighborhoods and the spatial graph

Three related things: the **graph** that says which cells are neighbours, the
**overlay** that draws it in the viewer, and **cellular neighborhoods**, which cluster
cells by what surrounds them rather than by what they express.

- [The spatial graph](#the-spatial-graph)
- [Independent areas](#independent-areas)
- [Cellular neighborhoods](#cellular-neighborhoods)
- [The viewer overlay](#the-viewer-overlay)

## Cellular neighborhoods

`Extensions > QP-CAT > Explore & spatial > Find cellular neighborhoods (spatial niches)...`

A **cellular neighborhood (CN)** groups cells by the *cell-type mixture around
them* rather than by their own measurements. Two T cells get different CN labels
if one sits in a dense tumor nest and the other in a lymphoid aggregate. CNs
surface recurring micro-environments -- tumor-immune boundaries, stroma,
follicles -- as a layer of structure on top of cell typing.

This is QP-CAT's fast, scalable spatial-niche analysis. It is O(n*k) (a
nearest-neighbor tree plus a small k-means) and runs single-process, so it works
on very large slides where the permutation-based spatial statistics in
[chapter 17](spatial-statistics.md) become slow.

### Prerequisite: an existing cell-type column

CN needs each cell to already carry a categorical label -- a **cluster** (from
[chapter 2](clustering.md)) or a **phenotype** (from
[chapter 6](phenotyping.md#running-phenotyping)). The dialog reads the current detection
classifications, reports how many classes it found, and refuses to run on fewer
than two. Cells with no classification are counted as a single `Unclassified`
type so every window is complete.

### Scope: one image, or the whole cohort (joint runs)

Most experiments have many images, and the question is usually *how does
neighborhood composition change with the biology/treatment, or what fraction of
each sample is a given neighborhood*. That only works if a `QPCAT CN` value
means the **same** cell-type mixture in every image. The dialog's **Scope** does
exactly this:

- **Current image** -- the single-slide run described below.
- **All project images** / **Specific images...** -- a **joint** run. Spatial
  windows are still built *within* each image (cells in different slides are
  never neighbors), but the windows from all images are **pooled and clustered
  once** so the neighborhoods are defined across the whole cohort. Labels are
  written back and saved to every image, and a **per-sample proportion** table is
  produced. **Specific images...** opens the shared image picker (filter by name
  or metadata, select-all/none).

> **Run clustering or phenotyping across the same images first.** The cell-type
> column must be consistent across the cohort -- the same class names meaning the
> same thing -- or the composition vectors are not comparable. If some images
> carry classes others lack, the run still proceeds (the union of classes keeps
> the vectors aligned) but the results dialog flags the divergent panel.

**Group images by** (joint runs only) groups the images by an image-**metadata**
key -- e.g. `treatment` or `condition` -- and additionally reports the mean
neighborhood proportions per group, for comparing composition across conditions.
Leave it at *(no grouping)* to skip.

## Independent areas

Cells in different pieces of tissue are never neighbors -- a cell in one TMA core
is not a neighbor of a cell in the next core, even if they are in the same image.
By default, the **image is the area** -- cells stay separate across images, but
any two cells within one image can end up in the same neighborhood window.

You can partition cells **below** the image level by configuring **Independent areas**.
Each area gets its own neighborhood windows (no window ever crosses an area boundary),
and the cohort tables automatically group results per area instead of per image.

**When to use:** tissue samples with multiple independent regions in one file --
a TMA with many cores scanned in a single image, or a multi-section slide.

**How to configure:**
1. Expand the **Independent areas** section in the dialog.
2. Click **Add level** to define one or more partitioning levels (e.g., `Tissue`
   annotations to separate cores or sections).
3. The preview shows how many areas were found and if any cells lack an
   assignment. Empty areas are skipped; sparse areas are handled (spatial graph
   parameters are capped per area so one small core does not reduce k for the rest).
   **TMA cores flagged missing are skipped too**, and the preview says how many --
   so a ragged grid does not fill the export with blank rows. Detections inside a
   missing core are still analysed, but land in `(unassigned)`.
4. For a **joint** run across multiple images, the same area levels are applied to
   every image. A cell belongs to an area if it **falls inside** that region --
   matched geometrically, from the cell's centroid, not from the object
   hierarchy. Parent links are not used, so this works even if cell detection
   ran before the cores or annotations existed, or the hierarchy has been
   edited since. QP-CAT never modifies your hierarchy to make this work.

### Step-by-step

1. Run clustering or phenotyping first so cells are classified -- across **all**
   the images you intend to analyze jointly, so their labels are consistent.
2. Open **Find cellular neighborhoods**. Confirm the "cell-type classes" line
   lists the types you expect. If you just (re)classified, click **Refresh**.
3. Pick the **Scope** (Current image / All project images / Specific images).
   For a cohort, optionally set **Group images by** a metadata key.
4. Optionally configure **Independent areas** if your images have multiple tissue
   sections or cores in one file.
5. **Window by** -- how each cell's window is defined:
   - **Nearest neighbors (k)**: the k nearest cells. Density-adaptive (the window
     shrinks in dense tissue). 20-30 is a common start; Schurch et al. used ~10.
   - **Radius (um)**: every cell within a fixed physical radius (the CytoMAP-style
     neighborhood -- more interpretable and density-aware). 50 um is a common
     start. Needs image pixel calibration; an uncalibrated image treats the radius
     as pixels (converted per image, so differing pixel sizes are handled).
6. **Number of neighborhoods** -- how many CNs to group the windows into
   (k-means). Try a few values; 6-12 is typical.
7. **Render enrichment heatmap** (on by default) writes the heatmaps (see below).
8. Click **Find neighborhoods**. A progress bar and WAIT cursor show while it
   runs; **Cancel** stops it -- if you cancel before it finishes, **nothing
   is written**.

### What happens to your data

- Each detection gets a numeric **measurement** `QPCAT CN` (0-based neighborhood
  id). Your cell-type **classification is preserved** -- it is the input to the
  analysis. Color cells by neighborhood with **Measure > Show measurement maps**
  and pick `QPCAT CN`.
- Re-running just overwrites the `QPCAT CN` measurement; it never touches the
  cell-type classification.
- The run is recorded as a step in the image's **Workflow** history (with the
  `run_id` and `params_hash`) and as a `CELLULAR NEIGHBORHOODS` row in the
  operation audit log. For **joint** runs the step is recorded on **every**
  processed image and says explicitly that the neighborhoods were defined jointly
  across N images -- re-running on one image alone would produce *different*
  labels. (This Workflow step is informational, not a runnable command; to
  reproduce, re-run with the same image scope.)
- For **joint** runs, labels are written back and **saved to every selected
  image**, and the cohort tables + heatmaps are written to a results folder under
  the project (`qpcat-cellular-neighborhoods/<run-id>/`).

### Reading the enrichment heatmap

Rows are neighborhoods, columns are cell types. Each cell is the **log2 fold
enrichment** of that cell type in the neighborhood versus its overall frequency
across the slide (red = enriched, blue = depleted). Read each row across to name
the neighborhood ("CN 3 = tumor + macrophage, depleted of B cells"). For a
single-image run the heatmap is written to a temporary folder and the dialog
offers to open it; for a joint run it lands in the results folder.

### Reading the cohort outputs (joint runs)

When the joint run finishes, a results dialog shows:

- **Per-sample neighborhood proportions** -- a table (and `cn_per_sample_proportions.csv`)
  giving, for each sample (image or independent area, depending on configuration), the
  fraction of its cells in each CN. When areas are configured, rows are areas instead
  of images, and the CSV's first column is headed `area` rather than `image` so the
  file says what its rows are. This is the table you compare across samples: "CN 2 is
  8% of the control but 31% of the treated slide" (or "8% of core 1 but 31% of core
  2"). A `cn_per_sample_proportions.png` heatmap visualizes it (sample x CN).
- **Per-group neighborhood proportions** (only when you set **Group images by**) --
  the same proportions pooled per metadata group, plus `cn_per_group_proportions.csv`
  and a `cn_per_group_proportions.png` heatmap (group x CN), for a direct
  condition-vs-condition comparison. When areas are configured, grouping is applied
  to the underlying images, then areas are pooled within each group.
- **Neighborhood adjacency enrichment** -- a CN x CN table (and
  `cn_neighborhood_adjacency.csv` + heatmap) of how often neighborhoods border
  each other, as **log2(observed/expected)** computed from the cell
  spatial-neighbor graph (not from any drawn object). The observed/expected form
  divides out neighborhood frequency (so a common CN does not look adjacent to
  everything just because it is everywhere) and centers at 0 (>0 = more contact
  than expected from frequency, <0 = avoided). The heatmap hides the diagonal
  (within-CN contact always dominates) and uses a diverging colormap. Reads which
  micro-environments abut each other more than chance ("CN 1 tumor is enriched for
  contact with CN 3 tumor-edge").
- **Open results folder** opens the folder containing those CSVs, the heatmap
  PNGs, and `cn_RUN_INFO.txt` (parameters + the note that neighborhoods were
  defined jointly).

To inspect *which* cells make up a neighborhood in a particular sample, open that
image -- the `QPCAT CN` measurement is saved there -- and color by it via
**Measure > Show measurement maps**.

### Visualizing neighborhoods

Neighborhoods are shown by **coloring the cells** (the `QPCAT CN` measurement) --
which is how CytoMAP-style neighborhood maps read: open **Measure > Show
measurement maps** and pick `QPCAT CN`. (An earlier pre-release build also drew
convex-hull "region" polygons per neighborhood; these were dropped because a
spatially pervasive neighborhood's contiguous patch connects across the whole
tissue, producing giant overlapping hulls with poor correspondence to the cells.
The neighborhood-adjacency enrichment above captures which neighborhoods abut each
other -- from the actual cells -- without needing polygons.)

### How this differs from BANKSY

BANKSY ([chapter 2](clustering.md), as a clustering algorithm) mixes each
cell's *expression* with a neighborhood-averaged kernel **before** clustering, so
it produces cell types/domains that are spatially smoothed. CN clusters the
*composition of already-assigned types* in a window, so it produces a higher-order
map of tissue micro-environments. Use BANKSY to type cells with spatial context;
use CN to find niches once cells are typed. Sources: Goltsev et al. (Cell 2018),
Schurch et al. (Cell 2020), Windhager et al. (Nat Protoc 2023) -- see
[References](references.md).

---
## The spatial graph

<a name="the-spatial-graph"></a>
Every spatial method starts by deciding which cells are neighbours. The constructor
-- kNN, fixed radius, or Delaunay -- changes results as much as the statistic does:
Delaunay is parameter-free but adds long edges across gaps (cap them with the
max-edge control), kNN adapts to local density but fixes neighbourhood size
everywhere, and fixed-radius uses a real micron radius but over-connects dense
regions. Set it in the Spatial statistics block of the clustering dialog; the same
graph drives every statistic in the run.

## The viewer overlay

QP-CAT v0.3 makes the spatial neighbor graph visible. Every time you run Spatial Statistics ([chapter 17](spatial-statistics.md)), QP-CAT builds a per-cell graph -- kNN, Radius, or Delaunay -- and the same graph drives every spatial metric in the run. Until v0.3 that graph lived only as a sparse matrix inside the Python session and was invisible from the viewer. v0.3 pushes the graph back to QuPath as a `PathObjectConnections` object so you can toggle the edges on and off via **View -> Show object connections**, the same menu that drove QuPath core's now-deprecated Delaunay Clustering tool. You can also write per-cell neighbor measurements and per-component aggregate measurements to the measurement table on the same run, mirroring the legacy plugin's output one-for-one.

<a name="overview"></a>
### Overview

The overlay is a sanity-check tool first and a presentation tool second. Seeing the edges your neighborhood-enrichment, Ripley K/L, Geary's C, and co-occurrence runs are using catches three classes of mistake at a glance: a Delaunay graph spanning a tissue gap; a kNN graph with k too high in dense regions; a Radius graph too sparse for the cell density. Use the overlay early to validate the graph, then turn it off for the rest of the analysis.

<a name="view-show-object-connections"></a>
### Turning the overlay on -- View -> Show object connections

The toggle is a QuPath core menu item: **View -> Show object connections**. QP-CAT's job is to populate the data the menu reads from; QuPath core's job is to draw the edges. If the menu is unchecked, nothing renders -- check it once after your first v0.3 run and QuPath remembers the choice.

By default the menu is off (QuPath core default `OverlayOptions.showConnections = false`); the overlay color is fixed to translucent black on brightfield, translucent white on fluorescence (QuPath core's choice, not a QP-CAT preference); the alpha drops at high downsample so the edges fade out at whole-slide zoom.

<a name="viewer-overlay-group"></a>
### The Spatial Statistics "Viewer overlay" group

A new sub-section at the bottom of the Spatial Statistics section of the **Find cell populations (clustering)...** dialog. The controls:

- **Push graph edges to viewer** (default on) -- top-level toggle. When on, the graph is materialised as `PathObjectConnections` after every Spatial Statistics run.
- **Prompt above N edges** (default 250000) -- when the graph has more than this many undirected edges, QP-CAT prompts before pushing.
- **Delaunay max edge (microns)** -- shown only when the current image has a pixel-size calibration; an equivalent pixel-only spinner sits in the Graph constructor row above for uncalibrated images.
- **Write per-cell node measurements** (default on) -- writes `QPCAT spatial:` columns to every cell.
- **Write component cluster measurements** (default off) -- writes `QPCAT component:` columns for each graph-connected component.
- **Limit edges to same class (post-hoc filter)** (default off) -- hides cross-class edges in the overlay.
- **Push to viewer now** -- retroactive rebuild, see below.

<a name="push-to-viewer-now"></a>
### Push to viewer now -- retroactive overlay for saved results

If you ran spatial stats in v0.3 without "Push graph edges to viewer" enabled, or you opened a project saved with v0.2.x that has no `PathObjectConnections` on disk, the **Push to viewer now** button reads the most recent saved spatial-stats result and reconstructs the connections without re-running clustering. This is the workflow for testers handed a finished project to look at. The button re-applies both the overlay and the per-cell / per-component measurements (honouring the current toggles); it does not re-run any statistic.

<a name="edge-count-threshold"></a>
### The 250k-edge prompt threshold

When the graph has more than 250,000 undirected edges, QP-CAT prompts before pushing -- a large graph rendered at high zoom can make panning feel sluggish. The threshold is `qpcat.spatial.connectionsPromptThreshold` under **Edit > Preferences > QP-CAT: Run Clustering**; raise it (e.g., to 1000000) to suppress the prompt for big slides, or lower it for cautious behavior on slow machines.

The threshold counts undirected edges (each pair `(i, j)` listed once). kNN(k=15) on 100k cells produces ~1.5M directed edges -- roughly 750k after the i<j dedup. Delaunay on 100k cells produces ~300k undirected edges.

> **Multi-image / cohort runs:** you are asked **once for the whole run**, not once per image -- the decision (based on the densest image) is applied to every image. After the run, a single dialog lists the average connections per cell for each image plus the run mean.

<a name="per-cell-neighbor-measurements"></a>
### Per-cell neighbor measurements

QP-CAT v0.3 writes `QPCAT spatial: Num neighbors`, `QPCAT spatial: Mean distance`, `QPCAT spatial: Median distance`, `QPCAT spatial: Max distance`, and `QPCAT spatial: Min distance` to every cell in the measurement table whenever Spatial Statistics runs. Distances are in microns when the image has pixel calibration, in pixels otherwise -- both cases write the same column name. For Delaunay graphs only, `QPCAT spatial: Mean triangle area` and `QPCAT spatial: Max triangle area` are also written. These are the columns the legacy QuPath core Delaunay Clustering tool wrote as `Delaunay: ...`.

Preference toggle: `qpcat.spatial.writeNodeMeasurements`, default on. Note that kNN and Radius graphs do not have triangle measurements because no triangulation exists for those graph types -- QP-CAT does not invent a fake value.

<a name="per-component-aggregate-measurements"></a>
### Per-component aggregate measurements

Opt-in. When `qpcat.spatial.writeComponentMeasurements` is enabled (default off), QP-CAT writes `QPCAT component: size` (number of cells in the graph-connected component this cell belongs to) and `QPCAT component: mean: <existing measurement>` for every existing numeric measurement on the cell. This mirrors the legacy `Cluster mean: <X>` / `Cluster size` output, with the deliberate rename to "component" to avoid confusion with Leiden phenotype clusters.

This is a wide measurement-table expansion (`n_existing_measurements` new columns) -- that is why opt-in is the right default. See [Component vs cluster](#component-vs-cluster-naming) for the worked example.

<a name="limit-edges-by-class"></a>
### Limit edges to same class

After phenotyping, toggle `qpcat.spatial.limitEdgesBySameClass` to filter the rendered overlay to within-class edges only. Useful for visualising same-cell-type neighborhoods after a Leiden + Phenotyping pass. Mirrors the legacy plugin's `Limit by class` option but applies post-hoc -- you do not have to re-run the graph build.

Toggling off restores the unfiltered graph without a rebuild; cells with no class (null pathClass) drop their edges entirely under the filter.

<a name="component-vs-cluster"></a>
### Component vs Cluster -- the naming convention

Two different things share the word "cluster" in spatial analysis: a Leiden cluster (a phenotype cluster from QP-CAT's clustering pipeline) and a graph-connected component (a maximal set of cells reachable through neighbor edges). QP-CAT uses **cluster** for the Leiden output and **component** for the graph-connected output, even though the legacy QuPath core plugin called the graph-connected set "Cluster" too. See [Component vs cluster](#component-vs-cluster-naming) for a worked example of why they differ.

Short worked example: two CD8 T cells far apart in tissue can share the same Leiden cluster (same phenotype) but live in different graph-connected components (no neighbor path between them). Conversely, two cells in the same graph-connected component can have different Leiden cluster labels (one is CD8, one is CD4, but they touch).

<a name="legacy-delaunay-clustering-migration"></a>
### Legacy Delaunay-clustering migration table

If you arrived here from QuPath core's Delaunay Clustering plugin, this table maps every legacy output to its QP-CAT v0.3 equivalent. Same data, new column names, same place in the workflow.

| Legacy QuPath core feature | QP-CAT v0.3 equivalent | Notes |
|---|---|---|
| Connecting-line overlay (View -> Show object connections) | Same menu item; QP-CAT populates `PathObjectConnections` after a Spatial Statistics run | Same QuPath core overlay code; same colors; same alpha-by-downsample behavior. |
| `Delaunay: Num neighbors` | `QPCAT spatial: Num neighbors` | Default on. Written for kNN / Radius / Delaunay -- not just Delaunay. |
| `Delaunay: Mean distance` | `QPCAT spatial: Mean distance` | Microns when calibration present, pixels otherwise. |
| `Delaunay: Median distance` | `QPCAT spatial: Median distance` | Same unit policy. |
| `Delaunay: Max distance` | `QPCAT spatial: Max distance` | Same unit policy. |
| `Delaunay: Min distance` | `QPCAT spatial: Min distance` | Same unit policy. |
| `Delaunay: Mean triangle area` | `QPCAT spatial: Mean triangle area` | Delaunay graph only; kNN and Radius leave this column blank. |
| `Delaunay: Max triangle area` | `QPCAT spatial: Max triangle area` | Delaunay graph only. |
| `Cluster mean: <X>` (aggregate) | `QPCAT component: mean: <X>` | Renamed to "component" to disambiguate from Leiden clusters; opt-in via preference. |
| `Cluster size` (aggregate) | `QPCAT component: size` | Same opt-in. |
| `Limit by class` (build-time filter) | `qpcat.spatial.limitEdgesBySameClass` (post-hoc filter) | Applied after the graph is built so you can phenotype first, then filter. |
| `Distance threshold` (microns / pixels auto-switch) | `qpcat.spatial.delaunayMaxEdgeUm` (canonical) plus `qpcat.spatial.delaunayMaxEdge` fallback | Dialog shows the unit that matches the current image's calibration. |

<a name="api-deprecation-note"></a>
### Note on the underlying QuPath API

Honest disclosure: the QuPath core `PathObjectConnections` API that QP-CAT writes into is marked `@Deprecated` in QuPath 0.7. QuPath core plans to replace it with a new `DelaunayTools.Subdivision` API that is fundamentally a Delaunay-triangulation type and cannot represent kNN or Radius graphs. QP-CAT will need that gap closed (or its own custom overlay) before the legacy API is removed, so v0.3 is an explicit "uses today's API while it exists" deliverable. The `@Deprecated` JavaDoc on `PathObjectConnectionGroup` and `DefaultPathObjectConnectionGroup` says only "v0.6.0, to be replaced by `qupath.lib.analysis.DelaunayTools.Subdivision`" -- there is no public issue-tracker reference at the JavaDoc level; track the QuPath GitHub issue tracker for the eventual removal milestone.

<a name="scripting-push-connections-to-viewer"></a>
### Scripting -- pushConnectionsToViewer

`SpatialConnectionsScripts.pushConnectionsToViewer(imageData, resultName)` reads a saved spatial-stats result by name and materializes its graph as `PathObjectConnections` on the given `ImageData`. Equivalent to clicking "Push to viewer now" in the dialog. Useful for batch operations or for restoring the overlay across all images in a project after a v0.2.x to v0.3 upgrade.

```groovy
import qupath.ext.qpcat.scripting.SpatialConnectionsScripts

// Push the saved spatial-stats result named "default" onto the current viewer
SpatialConnectionsScripts.pushConnectionsToViewer(
    getCurrentImageData(),
    "default"
)
```

<a name="troubleshooting"></a>
### Quick troubleshooting

Four things to check when the overlay does not appear after a run:

1. Is **View -> Show object connections** checked? (Default off in fresh installs.)
2. Was the 250k-edge prompt declined? (Lower or raise the threshold to taste; see above.)
3. Is the result on disk? See [chapter 16](reproducibility.md#the-audit-log) for the audit-log row.
4. The project was created in v0.2.x and the saved spatial-stats result predates the edge-COO write path -- re-run clustering once on v0.3 to populate the overlay.

<a name="clear-connections"></a>
<a name="clearing-the-overlay----utilities--clear-cell-connections-clear-connections"></a>
### Clearing the overlay -- `Setup & help > Clear cell connections...`

`Extensions > QP-CAT > Setup & help > Clear cell connections...` removes every `PathObjectConnectionGroup` attached to the current image -- QP-CAT's own overlay, a legacy QuPath core Delaunay Clustering run, or anything else that wrote to QuPath's `PathObjectConnections` slot. Use this when connections stack across runs (overlays from previous clustering passes that QP-CAT replaces, but other tools' overlays it leaves alone), when a stale overlay from a different result is hiding the one you want to see, or simply when you want to turn the viewer off without disabling the **View -> Show object connections** menu globally.

The action is reversible: re-running clustering with **Viewer overlay** enabled, or clicking **Push to viewer now** on a saved result, repopulates the connection group. No data is lost; the overlay payload lives in `ImageData` properties only, not in the saved result on disk.

Equivalent script:

```groovy
import qupath.ext.qpcat.scripting.SpatialConnectionsScripts

SpatialConnectionsScripts.clearConnections(getCurrentImageData())
```

Returns a `ClearResult` with `getNGroupsRemoved()` and `getNEdgesRemoved()` for batch scripting. Records a workflow step so the operation is replayable from the image's history, and writes a `SPATIAL OVERLAY CLEAR` row to the project's operation audit log.

---
### When the overlay helps

The spatial graph overlay is a sanity-check tool first, a presentation tool second, and a "leave it on all the time" tool never. This chapter covers when the overlay is informative versus noisy, when to enable per-class edge filtering, and how to read the per-cell and per-component measurements without confusing graph-connected components with Leiden phenotype clusters.

<a name="overlay-informative"></a>
### When the overlay is informative

The overlay earns its keep on small-to-medium populations where you can actually see individual edges -- typically under ~50,000 cells per annotated region, or a single zoomed-in tissue niche on a larger slide. Use it to:

- confirm a Delaunay graph is not spanning a tissue gap (look for long straight edges crossing background);
- confirm a kNN graph is not over-connecting dense regions (cells in the dense area should have ~k visible edges, no more);
- confirm a Radius graph is not under-connecting sparse regions (isolated cells should still have at least one or two neighbors).

<a name="overlay-noisy"></a>
### When the overlay is noisy

Above ~250,000 edges -- which is roughly a 50,000-cell Delaunay graph, or a 25,000-cell kNN(k=15) graph -- the overlay degrades from sanity-check to confetti. Edges blur into a uniform gray haze at whole-slide zoom; panning feels sluggish; nothing about the graph structure is visible. At 1,000,000+ edges QuPath's stock connections overlay was not designed for this density and pan/zoom interactions become jerky. Recommended: turn the overlay on once at a representative zoom level, sanity-check, turn it off, run the rest of the analysis without it.

<a name="limit-edges-by-class-when"></a>
### When to enable `limitEdgesBySameClass`

Enable when you want to *visualise* same-cell-type neighborhoods after a Leiden + Phenotyping pass -- e.g., showing CD8 T cell clusters touching each other, or fibroblast networks. Leave off when you want to see the full graph the spatial statistics actually ran on. The filter is purely a viewer affordance; it does not retroactively change any computed statistic.

Edge cases: cells with no pathClass (null) drop their edges entirely under the filter. If you phenotyped only part of the cell population, expect to see large patches of empty overlay where unphenotyped cells live. The fix is to run phenotyping over the whole population, not to disable the filter.

<a name="component-vs-cluster-naming"></a>
### Component vs Cluster naming

QP-CAT v0.3 writes per-component aggregate measurements as `QPCAT component: ...` instead of `Cluster ...` -- a deliberate rename. The reason: a Leiden cluster (from QP-CAT's clustering pipeline) and a graph-connected component (from a kNN / Radius / Delaunay graph) are different things, and the legacy QuPath core plugin reusing "Cluster" for the graph-connected set has confused users on image.sc more than once.

Worked example. Suppose you have two CD8 T cells, both Leiden cluster 3, sitting on opposite sides of a 5 mm tumor:

- Same Leiden cluster: yes. Both have the same phenotype.
- Same graph-connected component: no. There is no neighbor path between them in any reasonable spatial graph.

Conversely, two touching cells in the same Delaunay component can have different Leiden cluster labels -- one is a CD8 T cell (Leiden 3), the other is a CD4 T cell (Leiden 7). They share a `QPCAT component: ...` aggregate row because they touch, but their `Cluster` (Leiden) labels are distinct.

Rule of thumb: **Cluster** answers "what cell type is this?" **Component** answers "what spatial neighborhood is this in?" The two are independent.

A practical aside on the three-colon `QPCAT component: mean: <Marker>` header layout: at default Measurements-table column widths the prefix may truncate the marker name on narrow tables. Drag the column wider or use the column-visibility menu to filter to the columns you need.

<a name="api-deprecation-log"></a>
### The deprecated-API warn-once log line

On the first push of connections per session, QuPath 0.7 logs:

`Legacy 'Delaunay cluster features 2D' connections are being shown in the viewer - this command is deprecated, and support will be removed in a future version`

This is harmless. QuPath core's `PathObjectConnections` API is marked `@Deprecated` for replacement by `DelaunayTools.Subdivision` in a future major release, but the old API is fully functional in 0.7 and is the only API surface that can carry kNN and Radius graphs (the `Subdivision` replacement is Delaunay-only). The warning fires once per session, not per push -- subsequent pushes within the same session do not re-trigger it. Do not flag this as a bug; document it in user issue reports if it appears.

<a name="clearing-the-overlay"></a>
### Clearing the overlay between runs

QP-CAT's own clustering runs replace the previously-attached connection group with the new one -- they do not stack. Connections from other tools (the legacy QuPath core Delaunay Clustering plugin in particular) do stack alongside QP-CAT's overlay, because QuPath has no built-in clear-connections action and re-running cell detection is the only stock way to drop them. Use **Setup & help > Clear cell connections...** when an overlay from a previous tool or a previous saved-result push is in the way; the action is reversible -- the underlying spatial data is untouched on disk, so you can repopulate the overlay via **Push to viewer now** on any saved result.

<a name="performance-and-threshold"></a>
### Performance and the 250k threshold

The default `qpcat.spatial.connectionsPromptThreshold` value of 250000 edges is empirical. It is the count at which QuPath's stock connections overlay (which draws every edge with a single `Graphics2D.draw(Line2D)` call per edge) starts to feel sluggish at typical whole-slide zoom on commodity hardware. Below 250000, pan and zoom are smooth. Above 250000, frame time grows roughly linearly with edge count, viewport-culled. Above 1000000 the experience is noticeably degraded even with the cull.

Adjust the threshold to taste. Raise it on a fast workstation; lower it on a remote-desktop session or a slow machine. The threshold is per-machine, not per-project -- it lives in QuPath preferences, which are user-scoped.
