# Results

The results window opens after every run, even a bare one, and reopens later via
**Extensions > QP-CAT > Results & populations > View Past Results...**. Every tab is
one view of the same result.

- [Saving and reopening](#saving-and-reopening)
- [Reading expression: heatmap, matrix plot, dot plot](#reading-expression)
- [Embedding](#embedding)
- [Composition tabs](#composition-tabs)
- [Representative cells](#representative-cells)
- [Cluster confidence](#cluster-confidence-tab)
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
| **Heatmap** (interactive) | Column-normalised mean expression per cluster. Hover for values and cell counts; Ctrl+scroll or the zoom buttons to resize. | Exploring |
| **Matrix Plot** (PNG) | The same, plus row and column dendrograms. Zoom -/+/Fit/100%, or Ctrl+scroll. | Figures |
| **Dot plot** (PNG) | Dot size = fraction of cells expressing; colour = mean expression. Same zoom controls. | When fraction-expressing changes the reading |

Matrix Plot for figures, Heatmap for exploration, Dot plot when a marker is high in a few
cells rather than low in many -- a distinction the other two cannot show, because they
collapse to per-cluster means.

In the Heatmap, red is high relative expression and blue is low, per marker across
clusters. This is the tab that tells you which markers define each cluster, and the one
to work from when annotating cell types. Hover over a square for the mean value and the number
of cells in that cluster -- a mean over 40 cells and a mean over 4,000 look identical here and
are not equally trustworthy. Zoom with the **-** / **+** / **Reset** buttons or Ctrl+scroll;
zooming out past the point where the labels are legible is deliberate, because the pattern
across the whole panel is what this view is for.

The heatmap draws **every measurement the run clustered on**, whatever kind it is -- size and
shape measurements appear alongside intensities if they were selected for the run. A map showing
only `...: Mean` columns is a run in which only those were ticked.

<a name="heatmap-colours"></a>
### Choosing the colour map

The **Colours** box offers the colour maps QuPath has installed, **including any `.tsv` maps
you have added to QuPath's `colormaps` directory** -- so a lab palette shows up here too. The
choice is remembered.

**Only maps suited to the data are listed**, and that is the point of the control rather than a
limitation of it:

| Your normalization | Family offered | Default | Why |
|---|---|---|---|
| **Z-score** (the default) | diverging | Blue-White-Red | Zero means "average for this marker", so a scale with a pale midpoint states something true |
| None, Min-Max, Percentile | sequential | Viridis | There is no meaningful zero. A diverging map would put its pale middle on an arbitrary number and invite reading it as average -- a false claim made in colour |

QuPath ships no diverging map (Viridis, Inferno, Magma, Plasma and Svidro2 are all sequential),
so QP-CAT registers its own: **Blue-White-Red** (what it has always drawn, kept as the default
so an upgrade does not recolour an existing figure), **Red-Blue (RdBu)** and **Orange-Purple
(PuOr)**. The last two are ColorBrewer scales and are the safer choices for colour-blind
readers.

The same two choices also colour the figures Python renders -- the scanpy dot plot and matrix
plot, and the [co-occurrence](#co-occurrence-tabs) heatmaps -- wherever matplotlib has the same
map. A map of your own has no matplotlib equivalent, so those figures keep their defaults
rather than failing. Co-occurrence picks its family from the data for the same reason as above:
diverging only when the values actually straddle the null of 1.0.

**Rows/columns...** opens a picker with one checkbox per cluster and one per measurement, each
list with a text filter and **All** / **None** / **Only these**. Ticking redraws straight away.
Use it when the two measurements you want to compare sit ten rows apart in a 40-measurement
panel: hide the rest and they end up side by side. Nothing is recomputed -- these are the same
means, shown alone -- but note that with **Scale** set to *Shared across markers* the reach of
the colour is taken over what is shown, so colours do change when you filter. The title says
"showing N of M" whenever a filter is on.

<a name="embedding-tab-interactive"></a>
<a name="embedding-plot-tab"></a>
<a name="spatial-scatter-tab"></a>
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

**Clusters are computed in your measurements (or their principal components), not in the
embedding coordinates.** Distances *within* a cluster mean something because nearby cells
in the embedding are usually nearby in the measurement space. Distances *between* clusters
do not -- embeddings preserve local topology, not global geometry. A cluster can therefore
appear in two or more separated regions of this plot. That is not an error; local
neighbourhoods are what matter.

## Composition tabs

Four groupings of the same question -- where does each cluster sit? -- plus one that asks
a different question. **By image**, **by annotation**, **by area** and **by class** each
show a table (Counts / Row % toggle, **Copy table (TSV)**) and one pie per group, and each
exports via **Export figure + table...** or in bulk from [Exporting](exporting.md).

[**By group**](#composition-by-group-tab) is the odd one out: it *tests* rather than
describes, so it has a statistics table instead of pies, and a block of warnings above the
numbers that you should read before the numbers.

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

<a name="composition-by-group-tab"></a>
### By group -- the only tab that tests

Everything else here describes. This one asks whether a cluster is **more or less
abundant in one group of images than another**: control versus treated, responder versus
non-responder, primary versus metastasis. It is the question most cohort studies end on.

Pick the **image-metadata key** that says which group each image belongs to (set these in
QuPath's project pane, or with the
[Project Metadata Browser](https://github.com/uw-loci/qupath-extension-project-metadata-browser)
extension). The grouping is read **live from the project**, not stored in the result, so a
run you saved months ago can be compared by a condition recorded afterwards -- and by a
different condition tomorrow -- without re-clustering.

**The unit of replication is the image, not the cell.** A thousand cells from one slide
are one observation of that slide. The test therefore compares per-image proportions, so a
section with 100,000 cells cannot outvote three with 10,000 each. Treating cells as
independent replicates is the most common inflation of significance in this kind of work
([Lazic et al. 2018](references.md#unit-of-replication)).

The test is rank-based -- no normality assumption, which matters because proportions are
bounded and these designs are small. Two groups get a **Mann-Whitney U** test, more than
two a **Kruskal-Wallis H**. Both are exact for the small designs this tab is for: every
arrangement of the group labels is enumerated, including when images tie at the same
proportion. `q` is the Benjamini-Hochberg adjusted p across the clusters in the table.

#### Read these before the numbers

**A small design cannot reach significance however large the effect.** With three images
per group there are only C(6,3) = 20 ways to arrange the labels, so the most extreme
possible split carries 1/20 in each tail: the smallest two-sided p a rank test can return
is **0.1**. Nothing in that table can cross 0.05, and a tool that printed q-values without
saying so would be inviting you to read noise. The tab states the bound for your design
explicitly. Five per group is the first size that can reach 0.05 at all (2/252 = 0.008).

**Cluster proportions are compositional.** They sum to 1, so one cluster rising forces the
others down. The per-cluster tests here are not independent, and Benjamini-Hochberg assumes
nothing about that dependence -- a set of "significant" clusters may be one real shift and
its arithmetic shadow. For a model that handles this properly, copy the table out and use
**scCODA** ([Büttner et al. 2021](references.md#sccoda)) or **propeller**
([Phipson et al. 2022](references.md#propeller)). This tab is a screen, not a publication
analysis.

**It cannot tell biology from batch.** The clustering never saw the group labels, so the
test is not circular. But if a cluster lives in one image, a group difference in its
abundance is a difference in staining. Read [By image](#composition-by-image-tab) first.

**Images with no value for the key are left out, and the tab says how many.** Missing
metadata is not a condition, so they are never pooled into an extra group. The same goes
for an image that contributed no clustered cell at all (all noise, or filtered out): a row
of zero proportions would be a fabricated observation, not an empty one.

**Group medians are the honest summary.** When the design cannot reach significance, read
the median column and report the effect as a description.

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

<a name="cluster-confidence-tab"></a>
## Cluster confidence -- how marginal was each label?

Every algorithm here assigns each cell to exactly **one** cluster, so a cell the method
thought was 51/49 reads identically to one it thought was 100/0. That is how a gradient --
an EMT-like transition, a maturation or activation axis -- becomes an arbitrary line drawn
through the middle of a continuum, with nothing in the output to say so.

Three of the algorithms already compute a per-cell quantity that says which of those a cell
was. This tab reports it, and the run writes it to every cell as a measurement.

| Algorithm | Quantity | Is it a probability? |
|---|---|---|
| **GMM** | posterior probability of the assigned component | **Yes** -- calibrated |
| **HDBSCAN** | condensed-tree membership strength (noise cells are 0) | No, but bounded 0-1 |
| **KMeans / MiniBatch KMeans** | separation margin `(d2-d1)/d2` over the nearest two centroids | **No** -- a distance ratio with no calibration |
| Leiden, Agglomerative, BANKSY | none exists | -- |

**The tab does not appear for the bottom row.** That is the honest outcome: those methods
produce no such quantity, and an empty column would imply every label was certain. Nothing
is fabricated for them.

**The three are not merged into one column**, and the measurement name says which you have
(`QPCAT confidence: Posterior`, `... Membership strength`, `... Separation margin`).
A margin of 0.8 does **not** mean 80% confident.

### Read the ambiguous share, not the median

The table's headline column is the **percentage of cells below 0.90**, because the average
is nearly useless here. Measured with a two-component GMM on synthetic data:

| statistic | two separated blobs | one uniform continuum |
|---|---|---|
| median confidence | 1.0000 | 0.9925 |
| mean confidence | 1.0000 | 0.9293 |
| 10th percentile | 1.0000 | 0.7329 |
| **share below 0.90** | **0.0%** | **22.5%** |

The medians are indistinguishable. The share is not. Most points on a line are still nearer
one end than the other, so the cells that carry the information are a minority and any
average buries them.

The 0.90 threshold is a reporting convention, not a test. It was chosen because it separates
those two cases.

### What to do with it

- **Map it.** The measurement is on every cell, so **Measure > Show measurement maps** will
  show you *where* the uncertain cells are. Scattered uncertainty is noise. A **band** of it
  between two clusters is a gradient, and that boundary should not be read as a cell-type
  distinction.
- **Read the "Most likely alternative" column.** For the ambiguous cells in a cluster, it
  names the cluster they would most likely have joined instead. Two clusters that are each
  other's alternative are candidates to merge.
- **GMM also gets an entropy column** (`QPCAT confidence: Entropy`, 0 certain to 1 uniform).
  It uses the whole posterior rather than just the top component, so it separates "0.5 / 0.5"
  from "0.5 / 0.25 / 0.25".

<a name="marker-rankings-tab"></a>
<a name="marker-fingerprints-tab"></a>
## Marker rankings and fingerprints

**Marker Rankings** ranks the markers that best separate each cluster from the rest, using
scanpy's Wilcoxon rank-sum test:

- **Score** -- the rank-sum statistic. **Its sign matters**: positive means the marker is
  higher in this cluster, negative means lower.
- **Log2FC** -- log2 fold change of mean raw intensity vs all others.
- **Adj. P-val** -- what scanpy returns, kept so you can cross-reference it.
  **It is not evidence that the cluster is real.**

Use the top markers as cell-type starting points -- high CD3 and CD8 suggests cytotoxic
T cells -- then validate against the heatmap.

> **Why the p-value is not a significance test.** The clusters were built from these same
> measurements, so a test of whether those measurements differ between the clusters is
> circular: its null hypothesis was already false before it ran. Measured in the shipped
> environment on 600 cells of pure noise containing no clusters at all, Leiden still found
> 11-13 groups and **47% of the rows a table like this would show came back at adjusted
> p < 0.05**, the smallest at 2e-14. The tab prints this caveat above the table so it
> travels with any numbers you copy out. Full explanation, and what to do instead, in
> [the marker p-values are circular](clustering.md#marker-pvalues-are-circular).

**Marker Fingerprints** draws the same information per cluster as a compact profile, for
comparing clusters at a glance rather than reading a table.

> **These tabs may be missing.** The ranking compares each cluster against all others,
> so a result with only one group has nothing to compare against and the tabs do not appear.
> Other reasons include having too few cells to build a neighbor graph, or the ranking itself
> failing. If the tabs are absent, check the **quality warnings** shown at the top of the
> results window for why.

<a name="paga-trajectory-tab"></a>
## PAGA Trajectory -- read this before using it

Partition-based graph abstraction. Each node is a cluster, sized by cell count; each
edge is weighted by how connected two clusters are in the nearest-neighbour graph,
compared with what random assignment would give. A thick edge means the two clusters'
cells are **adjacent in expression space** -- many of one cluster's near neighbours
belong to the other.

**What it was built for.** PAGA comes from developmental single-cell RNA-seq
([Wolf et al. 2019](references.md#paga)), where cells genuinely occupy a continuum and
the question is the branching topology of a differentiation process. Its output is
designed to be read as a trajectory.

**Why that matters here.** A tissue sectioned at one moment is usually an inventory of
**discrete** cell types -- T cells, macrophages, fibroblasts, tumour -- with no process
moving cells between them. PAGA has no way to report that. It will always return a
graph, and the graph will always look confident. Adjacency in expression space is
exactly what you would expect between two phenotypically similar populations whether or
not anything connects them biologically.

So:

- **A strong edge is evidence of similarity, not of differentiation.** Reading it as a
  trajectory is a claim you bring to the figure, not one the figure supplies.
- **Reasonable to use** when you have a prior reason to expect a continuum: a maturation
  or activation gradient, a polarisation axis, an EMT-like transition.
- **Not reasonable** as a generic "how do my clusters relate" figure. For that, the
  [Heatmap](#heatmap-tab)'s dendrogram orders clusters by expression similarity and makes no
  claim about process.

**It is off by default**, because it is the only consumer of the per-cell
nearest-neighbour graph and that is the slowest step after clustering itself. That
default is about run time, not a judgement on the method -- turning it on costs minutes,
and interpreting it costs more care than any other tab here.

**If you want to learn the method properly**, the Galaxy Training Network's
[trajectory analysis tutorial](references.md#trajectory-teaching) walks the whole scanpy
ladder -- force-directed graph, diffusion map, PAGA, diffusion pseudotime -- on a real
dataset, and is blunter about the risk than most tool documentation: you must know the
biology *before* you look, and these methods "can pretty easily force branches to appear
even if they are not biologically real." QP-CAT ships the PAGA rung of that ladder and
nothing above it, deliberately; whether the rest belongs here is still open.

<a name="spatial-autocorrelation-tab"></a>
<a name="gearys-c-tab"></a>
<a name="ripley-l-tab"></a>
<a name="neighborhood-enrichment-tab"></a>
<a name="cluster-explainer-llm-tab"></a>
## Spatial tabs

These appear when the corresponding statistic ran. What each one answers, and when to
choose it, is in [Spatial statistics](spatial-statistics.md).

| Tab | Reads as |
|---|---|
| **Spatial Autocorrelation** (Moran's I) | I > 0 clustered, ~0 random, < 0 dispersed. High I with a significant p-value means tissue-level structure -- a good BANKSY candidate. Zoom controls available. |
| **Geary's C** | C < 1 nearby cells similar, ~1 random, > 1 dissimilar. Weights local detail more than Moran's I. Zoom controls available. |
| **Ripley L** | Plotted **relative to random**: each curve minus its own simulated-random median, so the flat line at zero is randomness. Above zero = clustering at that radius; below = dispersion; inside the cluster's dashed band = not distinguishable from random. Untick **Relative to random** for the raw L(r). For multi-image runs, an **Area** selector shows each area's curves one at a time. |
| **Co-occurrence** | P(neighbour is B \| centre is A) / P(neighbour is B) by radius. > 1 enriched, < 1 depleted, **1.0 no association**. [Full description below](#co-occurrence-tabs). Zoom controls available. For multi-image runs, an **Area** selector shows each area's table one at a time. |
| **Cluster Explainer (LLM)** | Per-cluster cell-type suggestions. See [LLM explainer](llm-explainer.md). Always validate against Marker Rankings. |

<a name="co-occurrence-tabs"></a>
### Co-occurrence: the value, the radius, and what each view aggregates

Four tabs come from this one statistic -- a table and a figure, each pairwise and
one-vs-rest -- and they do not all show the same thing. This is where the in-app
**Documentation** link on all four lands.

**The value.** For an ordered pair of clusters (A, B) at radius r:

```
P(a cell within r of an A cell is a B cell)
-------------------------------------------
P(any cell is a B cell)
```

So **1.0 means no association**: B is as common near A as it is anywhere. Above 1 means
A's neighbourhood is enriched for B at that radius, below 1 means depleted. The
denominator is the tissue-wide frequency of B, which is why a rare cluster can show a
large ratio from few cells -- read it alongside the cluster's cell count.

**It is descriptive. There is no permutation test and no p-value**, unlike Ripley's L
and Geary's C. A ratio of 1.4 is not "significant enrichment"; it is 1.4.

**No neighbour graph is involved.** `squidpy.gr.co_occurrence` takes coordinates and
distances, not a connectivity graph -- it has no `connectivity_key` parameter. So
**changing the kNN k, the radius or the Delaunay pruning does not change these numbers.**
Those settings drive neighbourhood enrichment, Moran's I and Geary's C. Before QP-CAT
0.18.0 the figures and the table printed a `graph: knn` line, which was simply untrue;
it has been replaced by the radius line described next.

**Where the radii come from.** Leave **Min radius** and **Max radius** unset and QP-CAT
derives them from cell density rather than the image size: **50 linearly spaced bins**,
from the **median nearest-neighbour distance** up to
`min(20 x median_nn, max(2 x median_nn, 0.5 x diagonal))` -- roughly one cell spacing to
twenty, never past half the area's diagonal. Density, not the bounding box, because a
fixed fraction of a bounding-box diagonal degenerates on thin, elongated or sparse
regions, where every bin comes out empty or saturated.

Each figure's title and the top of each table state **the bins actually used**, so you
never have to infer them:

```
Radius: 12.4 to 248.6 px, 50 bins
```

Units follow the image: **um** for a calibrated image, **px** otherwise, and the line
says which.

**What each view aggregates -- the one that catches people out:**

| View | Rows / cells | Radius |
|---|---|---|
| Co-occurrence (pairwise) **table** | one column per ordered cluster pair | one row **per radius bin** |
| `co_occurrence_pairwise.png` **figure** | cluster x cluster heatmap | **one named radius** |
| `co_occurrence_curves.png` **figure** | one panel per cluster, one line per partner | per radius bin |
| Co-occurrence (one vs rest) **table** | one column per cluster | one row **per radius bin** |
| `co_occurrence_one_vs_rest.png` **figure** | cluster x radius heatmap | per radius bin |

The **curves** figure is the one to read a conclusion off: it is the presentation
`squidpy.pl.co_occurrence` uses and the one its tutorials interpret, and a conclusion
from this statistic is always quoted *at a distance*. The **matrix** is the overview at
a single radius, which its title names.

**The bins are cumulative discs, not rings.** squidpy counts every pair within
distance r (`d2 <= thresholds[r]`), so bin 30 contains everything bins 1-29 counted.
Two consequences:

- The profile decays **monotonically toward 1.0** as r grows, because at a large enough
  radius the neighbourhood is the whole tissue and the ratio is 1 by construction. A
  ring of depletion just outside a cluster cannot appear as a dip below 1 -- the
  short-range enrichment is still inside the count. If you need ring-shaped structure,
  that is Ripley's L, not this.
- **Averaging over the bins is therefore not an average of independent scales**, and QP-CAT
  no longer does it. Measured on a synthetic tissue where one partner was tightly wrapped
  around the reference type: short-range ratio **1.75**, mean over the 50 auto-chosen bins
  **1.13** -- about 18% of the excess over 1 retained. With the auto interval only about
  **4% of the bins sit at or below twice the median nearest-neighbour distance**, so a mean
  is dominated by radii of 2 to 20 cell spacings. The matrix is drawn **at one radius**
  instead, named in its title.

**Which radius the matrix uses.** *Spatial statistics > Matrix radius* in Run Clustering,
in the image's units. Left at **0** it picks about five median nearest-neighbour distances
-- the near end of the profile, where the ratio still discriminates. The figure title
always states the radius and bin it used, so the number can be quoted the way a published
co-occurrence value is.

**The first bins are noisy.** Few pairs fall inside the smallest discs, so the leftmost
points of a curve can swing widely on small counts. Judge a curve by its shape over
several bins, not by its first point.

**The ratio is compositional.** `p(exp|cond)` is the share of a neighbourhood made up of
the partner type, so enriching some partners necessarily dilutes the others. In the same
test a partner distributed *uniformly* at random read as **0.81** -- apparent depletion
-- purely because the reference type's neighbourhoods were crowded with the partners
that were genuinely enriched. Depletion of one type is not independent evidence of
avoidance when another type is strongly enriched.

**How this compares with how co-occurrence is usually shown.** squidpy's own
`squidpy.pl.co_occurrence` plots the score **against distance**, one panel per cluster,
and its tutorials interpret it that way -- there is no mean-over-radii matrix in
squidpy's API or examples. The conventional cluster-by-cluster *matrix* in this field is
**neighbourhood enrichment**, a permutation z-score at one graph definition, which QP-CAT
also computes and shows in its own tab. If what you want is a square matrix to read
associations off, that tab is the one with an established interpretation.

**Choosing the radius range itself** (as opposed to which radius the matrix shows) is
available from the [scripting API](scripting.md#spatialstatsscripts), not the dialog:
`SpatialStatsScripts.coOccurrence` takes `minRadius`, `maxRadius` and `nIntervals`. Pick a
range with a distance scale in mind -- 20 um for cell-contact-level questions, 50-100 um
for niche-level -- and keep it inside the tissue, since bins wider than the area contain
few pairs.

See [Spatial statistics](spatial-statistics.md#when-to-use-each-statistic) for when to
reach for co-occurrence rather than neighbourhood enrichment or Ripley's L.

### Exporting spatial statistics tables

The **Geary's C**, **Ripley L**, and **Co-occurrence** tabs display numeric tables optimized for on-screen reading (fixed-width layout). For re-analysis in a spreadsheet or stats package, each table can be exported as CSV in **long form** -- one row per observation, every column named explicitly.

Buttons above each table:
- **Copy text** -- copies the table exactly as shown, spacing preserved (Geary's C and Co-occurrence only; Ripley L is a chart).
- **Copy CSV** -- copies comma-separated rows in long form, ready to paste into a spreadsheet or R/Python.
- **Save CSV...** -- writes the long-form CSV to a file (default filename based on the statistic: `qpcat_geary_c.csv`, `qpcat_ripley_l.csv`, `qpcat_cooccurrence_pairwise.csv`, `qpcat_cooccurrence_one_vs_rest.csv`).

**Why long form?** The on-screen table for pairwise co-occurrence is one column per ordered cluster pair (20 clusters = 400 columns, labels scroll away). CSV writes one row per pair, so every comparison is explicit and legible in a cell editor or data frame.

### Ripley L: controlling cluster visibility

The **Ripley L** chart opens showing the first cluster only, with **Recommend 1 at a time** under **Show clusters**. Each cluster draws three lines, not one: its curve, plus the two dashed edges of its own simulated-random band. Seven clusters is therefore twenty-one lines, and the question the chart exists to answer -- is this curve outside *its* band, and at which radii -- cannot be read off it. With one cluster shown the axes rescale onto that cluster and the answer is direct.

The panel to the right lists every cluster with a checkbox, each in its curve's own colour. Tick another to compare two; past two or three, matching a curve to its band gets hard. **All** shows every cluster at once, which is useful for spotting which ones differ from the rest. **None** hides them all.

The random reference is never hidden, by either button. It is what the curves are read against, so an empty chart without it would be unreadable. In the default **Relative to random** view it is the flat line at zero labelled *Random (simulated)*; results saved before simulated envelopes existed instead show the analytical *Poisson null* diagonal.

**When the run covered several areas** -- several images, or an image split under
**Independent areas** -- an **Area** dropdown appears at the top and the chart draws one area
at a time, labelled with the image or area name and a count of how many were measured. There
is no combined curve: the combined point pattern would describe how the pieces of tissue were
arranged rather than anything inside any of them.

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
and their embedding measurements directly. Loading starts automatically about 5 seconds
after the results window opens; this avoids dead time waiting for a click.

**As with the 2D embedding**, clusters are computed in your measurements (or their
principal components), not in the 3D coordinates shown here. A cluster can appear in
multiple separated regions of this view.

For the standalone viewer and the export path, see
[Exporting](exporting.md#vest-3d-export).
