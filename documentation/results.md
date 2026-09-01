# Results

The results window opens after every run, even a bare one, and reopens later via
**Extensions > QP-CAT > Results & populations > View Past Results...**. Every tab is
one view of the same result.

- [Saving and reopening](#saving-and-reopening)
- [Reading expression: heatmap, matrix plot, dot plot](#reading-expression)
- [Embedding](#embedding)
- [Composition tabs](#composition-tabs)
- [Representative cells](#representative-cells)
- [Marker rankings and fingerprints](#marker-rankings-and-fingerprints)
- [Spatial tabs](#spatial-tabs)
- [Saving a plot](#saving-a-plot)
- [Cluster colours](#cluster-colours)
- [3D view](#3d-view)

## Saving and reopening

Every successful run **auto-saves** to `<project>/qpcat/cluster_results/` under a
timestamped, scope-tagged name (`auto_20260617_193235_leiden`). Nothing to click. The
footer shows where it went and its size on disk; the run is also recorded as a step in
QuPath's Workflow tab and in `<project>/qpcat/logs/`.

- **Save a named copy...** adds a human-named copy alongside the auto-save.
- **Manage saved results...** lists every saved result with name, timestamp, summary,
  scope, origin and size, for multi-select deletion.

Past five results for one scope, QP-CAT warns and points at Manage saved results.
Auto-saves are never deleted automatically.

> A run made before v0.3.6 persisted only its cluster labels, not the embedding or
> marker rankings, so its results window cannot be rebuilt. Re-run to regenerate an
> equivalent result.

<a name="heatmap-tab"></a>
<a name="matrix-plot-tab"></a>
<a name="dotplot-tab"></a>
<a name="stacked-violin-tab"></a>
<a name="reading-expression"></a>
## Reading expression: heatmap, matrix plot, dot plot

Three views of the same per-cluster-per-marker matrix, side by side deliberately.

| View | Shows | Use for |
|---|---|---|
| **Heatmap** (interactive) | Column-normalised mean expression per cluster. Hover for values, scroll to zoom. | Exploring |
| **Matrix Plot** (PNG) | The same, plus row and column dendrograms. | Figures |
| **Dot plot** (PNG) | Dot size = fraction of cells expressing; colour = mean expression. | When fraction-expressing changes the reading |

Matrix Plot for figures, Heatmap for exploration, Dot plot when a marker is high in a few
cells rather than low in many -- a distinction the other two cannot show, because they
collapse to per-cluster means.

In the Heatmap, red is high relative expression and blue is low, per marker across
clusters. This is the tab that tells you which markers define each cluster, and the one
to work from when annotating cell types.

<a name="embedding-tab-interactive"></a>
<a name="embedding-plot-tab"></a>
<a name="spatial-scatter-tab"></a>
<a name="paga-trajectory-tab"></a>
## Embedding

Interactive 2D scatter of every cell, coloured by cluster. Scroll to zoom, middle-drag to
pan, hover for details. The plot fills the window and redraws as you resize. A scrollable
legend lists each cluster with its live colour and cell count.

**Click a point** to ring it, load a crop preview, open that cell's image (switching
images if needed), centre the viewer on it and select it in the hierarchy. That closes
the loop from an abstract point back to the cell on the slide -- the fastest way to
ground-truth a boundary point or an outlier.

Above **150,000 cells the plot draws a subsample** and says so in its title
("UMAP 2D scatter (1,240,000 cells, showing 150,000)"). The sample is stratified by
cluster with a floor of 500 points each, so a rare population is never proportioned away.
Zooming restores every point once the visible subset fits. **Display only** -- clustering
ran on all cells, and gating, hover and navigation all use the full dataset. A million
overlapping dots is both slow and misleading, since the canvas saturates and dense and
very-dense regions look identical. (CATALYST plots a 1,000-cell-per-sample UMAP over
clusters computed on every cell; umap-learn's own `umap.plot` switches representation
above `width * height / 10` points.)

Distances *within* a cluster mean something. Distances *between* clusters do not --
embeddings preserve local topology, not global geometry.

## Composition tabs

Four groupings of the same question: where does each cluster sit? Each shows a table
(Counts / Row % toggle, **Copy table (TSV)**) and one pie per group, and each exports via
**Export figure + table...** or in bulk from [Exporting](exporting.md).

<a name="composition-by-image-tab"></a>
<a name="composition-by-image"></a>
### By image

The batch-effect check, and worth making a habit on any project-wide run. Biologically
meaningful clusters span images -- the same cell type appears in every slide containing it.

**If each cluster is confined to one image** -- its pie one solid colour, one non-zero
cell per table row -- the run separated cells by image, not by phenotype. That is a batch
effect. Usual causes: per-image differences in staining or illumination (z-score and
min-max are computed globally, not per image, so a consistent per-image offset survives
into the clustering), or clustering on a measurement only some images carry.

Remedies, in order: enable **Batch correction (Harmony)**, drop measurements that differ
systematically by image, or confirm the images were stained and imaged under matched
conditions.

> A measurement present on some images and absent on others is excluded automatically,
> with a warning naming it, precisely because it would become an image-discriminating
> constant. Seeing that warning means the offered measurement list included something the
> other images lack.

<a name="composition-by-annotation-tab"></a>
### By annotation

Grouped by each cell's **parent annotation** -- the named region it was inside when the
run happened. Appears **only for annotation-input runs** (you had annotations selected
when you launched). Cells outside any annotation group under `(none)`.

<a name="composition-by-area-tab"></a>
### By area

Grouped by [independent area](spatial-neighborhoods.md#independent-areas) -- one row per
TMA core or tissue section. This is the core-to-core comparison: *"cluster 2 is 8% of
core A-1 but 31% of A-4."*

Appears for 2-200 areas. Above that the table would be unreadable, so it is omitted and
the same numbers go to `<result>_areas_summary.csv`; a log line says so rather than the
tab silently vanishing.

A row labelled **`(unassigned)`** is not one of your classes -- it is cells that fell in
no region at the level you chose, typically inside a core but in an unannotated gap. It
is scoped to the deepest level that *did* match (`A-1 | (unassigned)`), so those cells are
never pooled across cores.

<a name="composition-by-class-tab"></a>
### By class

Grouped by the **annotation class** a cell sits in -- Tumor, Stroma -- pooled across
images and areas. Appears when at least two classes are present.

This is the counterpart to areas: **areas decide which cells may share a spatial graph;
class decides how results are compared.** Compartments inside one area are deliberately
not separated spatially, because Tumor and Stroma in a core are continuous tissue and the
interface is usually what is being measured. This tab is where you read them apart.

Two details: it keys on the **class**, never the annotation name, so a slide with hundreds
of named regions still gives a handful of rows. And membership is geometric -- the cell's
centroid inside the annotation -- with the **innermost** classified annotation winning, so
a cell in a Tumor annotation inside Tissue reads as Tumor.

<a name="representative-cells-tab"></a>
## Representative cells

Per-cluster gallery of crops of the most typical cells, ranked by distance to the cluster
centre, with the **medoid** outlined. Click a thumbnail to open its image and centre on
the cell. **Save montages** writes one PNG strip per cluster to
`<project>/qpcat/cluster_results/<name>_plots/cluster_<c>_representatives.png`.

- **Center** -- *Feature-space medoid* (default; nearest the cluster mean in the
  normalized measurement space the clustering used) or *Embedding-space medoid*.
- **Crop x bbox** -- the crop window as a multiple of each cell's bounding box
  (default 3x), so cells fill a consistent fraction of every thumbnail whatever the
  magnification.
- **Show each cluster's top channels** -- renders each cluster's crops in *that cluster's*
  top-ranked markers, plus one **Fixed channel** common to all (normally the nuclear
  stain, to keep an anatomical reference). A legend of the channels used is appended to
  each row.
- **Channels** -- how many ranked markers per cluster (default 4). The fixed channel does
  not count towards it.
- **Fixed channel** -- defaults to the first channel whose name contains DAPI, Hoechst,
  SYTOX, DRAQ5, TO-PRO, PI, Nucleus, Nuclear or DNA. **(none)** adds no fixed channel.

Channels are matched by looking for a channel name inside each measurement name, which
works across detection engines that name things differently ("CD8: Cell: Mean",
"Cell: CD8 mean"). A marker matching no channel is left out rather than erroring; if
nothing matches, the crops fall back to the viewer's current channels.

> **The trade-off is comparability.** Once each cluster is drawn in different channels the
> montages **cannot be compared with each other** -- brightness, contrast and colour no
> longer mean the same thing between them. Read them one at a time, as "what does a typical
> cell of this cluster look like in the markers that define it". The panel says so while the
> option is on, and **Save montages** writes a `WARNING.txt` beside the PNGs so the caveat
> travels with the images. See Schmied C, Nelson MS, Avilov S, et al., *Nature Methods*
> **21**, 170-181 (2024), [doi:10.1038/s41592-023-01987-9](https://doi.org/10.1038/s41592-023-01987-9).

A medoid is a real observed cell, not a synthetic prototype, and "representative" means
typical, not pure. Read these alongside the heatmap and marker rankings.

<a name="marker-rankings-tab"></a>
<a name="marker-fingerprints-tab"></a>
## Marker rankings and fingerprints

**Marker Rankings** gives the top differentially expressed markers per cluster from
scanpy's Wilcoxon rank-sum test:

- **Score** -- test statistic; higher means stronger differential expression vs all
  other clusters.
- **Log2FC** -- log2 fold change vs all others; positive means upregulated here.
- **Adj. P-val** -- Benjamini-Hochberg adjusted; smaller is more significant.

Use the top markers as cell-type starting points -- high CD3 and CD8 suggests cytotoxic
T cells -- then validate against the heatmap.

**Marker Fingerprints** draws the same information per cluster as a compact profile, for
comparing clusters at a glance rather than reading a table.

<a name="spatial-autocorrelation-tab"></a>
<a name="gearys-c-tab"></a>
<a name="ripley-k-and-l-tab"></a>
<a name="co-occurrence-tabs"></a>
<a name="neighborhood-enrichment-tab"></a>
<a name="cluster-explainer-llm-tab"></a>
## Spatial tabs

These appear when the corresponding statistic ran. What each one answers, and when to
choose it, is in [Spatial statistics](spatial-statistics.md).

| Tab | Reads as |
|---|---|
| **Spatial Autocorrelation** (Moran's I) | I > 0 clustered, ~0 random, < 0 dispersed. High I with a significant p-value means tissue-level structure -- a good BANKSY candidate. |
| **Geary's C** | C < 1 nearby cells similar, ~1 random, > 1 dissimilar. Weights local detail more than Moran's I. |
| **Ripley K / L** | Curve above the dashed Poisson null = clustering at that radius; below = dispersion. |
| **Co-occurrence** | P(neighbour is B \| centre is A) / P(neighbour is B) by radius. > 1 enriched, < 1 depleted. "One vs rest" is the smaller read when you care about one cluster. |
| **Cluster Explainer (LLM)** | Per-cluster cell-type suggestions. See [LLM explainer](llm-explainer.md). Always validate against Marker Rankings. |

## Saving a plot

**Save plot...** exports whichever tab is on top as a PNG, exactly as displayed. The
Heatmap, Marker Fingerprints, Embedding and 3D View tabs are drawn live and have no other
export, so this is the only way to get them out.

The chooser opens in this result's own folder. The PNG includes content scrolled out of
view, and resolution follows the **Plot DPI** preference, so it matches the figures
QP-CAT writes itself rather than being a screenshot. The default filename is the tab
name.

For many plots across many images, use [batch figure export](exporting.md).

## Cluster colours

**Extensions > QP-CAT > Results & populations > Apply cluster color palette...** sets the
palette. Colours update live in the embedding, the composition pies and the viewer.

The static matplotlib PNGs are already on disk and do not recolour by themselves. Turn on
**Auto-Regenerate Static Plots on Color Change** in preferences if you want them
rewritten -- it costs a Python round-trip each time, which is why it is off by default.

## 3D view

Present when the result carries a 3D embedding. It reads the clustered images' detections
and their UMAP1/2/3 measurements directly, and is built the first time you select the tab.
For the standalone viewer and the export path, see
[Exporting](exporting.md#vest-3d-export).
