# Clustering

Finding cell populations from marker measurements. This is the main QP-CAT tool.

**Extensions > QP-CAT > Find cell populations (clustering)...**

- [Running a clustering](#running-a-clustering)
- [Choosing measurements](#choosing-measurements)
- [Choosing a normalization](#choosing-a-normalization)
- [Choosing an algorithm](#choosing-an-algorithm)
- [Algorithm parameters](#algorithm-parameters)
- [Spatial feature smoothing](#spatial-feature-smoothing)
- [Reducing features with PCA first](#reducing-features-with-pca-first)
- [Quick presets](#quick-presets)
- [Clustering several images together](#clustering-several-images-together)
- [Embeddings without clustering](#embeddings-without-clustering)

## Running a clustering

1. **Scope** -- current image, all project images, or a chosen subset.
   You do not need an image open: with a project open, pick the images first and
   QP-CAT reads their measurements directly, so the measurement list reflects what
   you picked rather than whatever was in the viewer.
2. **Measurements** -- **Select 'Mean' only** is the right default. See
   [Choosing measurements](#choosing-measurements).
3. **Normalization** -- Z-score unless you have a reason otherwise. See
   [Choosing a normalization](#choosing-a-normalization).
4. **Embedding** -- UMAP by default; `n_neighbors` 15, `min_dist` 0.1.
5. **Algorithm** -- Leiden by default. See [Choosing an algorithm](#choosing-an-algorithm).
6. **Analysis options** (the untitled block below the algorithm section):
   - *Generate analysis plots* -- the static PNGs (marker ranking, PAGA, dotplot)
   - *Neighborhood enrichment + Moran's I* -- the two spatial statistics that run
     alongside clustering
   - *Spatial feature smoothing* -- [below](#spatial-feature-smoothing)
   - *Reduce features with PCA before clustering* -- off by default,
     [below](#reducing-features-with-pca-first)
   - *Batch correction (Harmony)* -- enabled when the run spans several images **or**
     several independent areas within one image; the **Batch key** dropdown picks which
7. **Run Clustering**.

A line above the Run button says what this run trades -- reproducibility,
comparability, or nothing. See [Reproducibility and run cost](reproducibility.md).

### Before it runs

**Pre-flight scale check.** An amber box means the run will work but may be slow or
near your memory ceiling. A red box means it cannot finish on this machine and Run is
disabled; the message names the problem ("Agglomerative clustering needs about 50 GB
but you have 16 GB") and an alternative. Both are machine-dependent -- the same config
may warn on a laptop and run fine on a server.

If QP-CAT cannot read your machine's total memory it says so and shows only the
predicted requirement, as a warning. It will not block on a number it does not have.

**Classification overwrite.** Cluster labels are written as classifications, so
existing ones are replaced. For the current image the dialog counts the affected
detections and names the classes going away -- `Cluster 0, Cluster 1` means you are
re-running over your own output; `Tumor, Stroma` is worth stopping for. For a project
scope it states the consequence without a count, since counting would mean opening
every image to write a dialog.

**Back up first.** The first time you open any tool that can change classifications, a
one-time modal says so. Duplicate the project folder before continuing. Re-arm it from
**Edit > Preferences > QP-CAT > "Backup Warning Acknowledged"**. Read-only tools
(View Past Results, spatial statistics, Compute Embedding, Apply palette) never trigger it.

### What it writes

- A **PathClass** per detection -- "Cluster 0", "Cluster 1", ...
- **UMAP1/UMAP2** measurements (or PCA1/2, tSNE1/2) if an embedding was computed
- Viewer colours update to match

**Cells vs. detections.** If the hierarchy has cell objects, QP-CAT analyses only cells,
so subcellular spots nested inside cells are not clustered as though they were cells;
the count ignored is logged. With no cell objects (a nucleus-only detection method),
every detection is analysed.

## Choosing measurements

**Mean intensity**, not median, max, or area. Mean best represents average expression,
is comparable across cells of different sizes, and is the standard single-cell input.
**Select 'Mean' only** does this.

Leave out measurements that carry no biological signal -- DAPI/Hoechst (nuclear stain,
not a marker), autofluorescence channels, and morphology unless it is your question.
Irrelevant measurements add noise that obscures real structure.

Two more things worth checking:

- **Highly correlated markers** (two antibodies against one target) bias the clustering
  toward that target. Include one.
- **Very low signal markers** contribute mostly noise. Look at the histogram first.

Mixing surface markers, functional markers and transcription factors is fine and often
helps separate cell states.

## Choosing a normalization

| Method | Use when | Avoid when |
|---|---|---|
| **Z-score** | General clustering. Every marker weighted equally. | Distributions are extremely non-normal |
| **Min-Max [0,1]** | Gating, where an intuitive scale matters | Outliers dominate the range |
| **Percentile** | A robust Min-Max. Tolerates outliers. | Very few cells |
| **None** | Already normalized, or raw scale matters | Markers have very different scales |

Z-score for clustering: it stops a bright marker dominating purely because of its
intensity scale. Min-Max or Percentile for [phenotyping](phenotyping.md), so gate
thresholds land in a readable [0,1] range.

**Changing normalization moves your gates.** Typical gate values are around 0.0 for
Z-score, 0.3-0.7 for Min-Max and Percentile, and raw intensity units for None.

## Choosing an algorithm

<a name="cluster-labels-are-hypotheses"></a>
### A cluster label is a hypothesis, not a measured cell type

The number of clusters depends on the parameters you choose, and **every method here
assigns each cell to exactly one cluster** -- which hides gradients such as
epithelial-mesenchymal transitions. QP-CAT does not export soft or continuous
membership. So:

- **Re-run with different seeds and parameters** and check the boundary cells stay put.
  A cell that jumps clusters between runs does not have a reliable label.
- **Read the marker heatmap** rather than trusting one labelling; it shows which markers
  actually separate the clusters.
- Treat labels as a hypothesis to validate.

### Decision tree

```
Do you know how many clusters to expect?
  |
  +-- YES --> well-defined?  --> KMeans or Agglomerative
  |           roughly?       --> GMM (elliptical, overlapping populations)
  |
  +-- NO  --> is spatial location important?
                +-- YES --> BANKSY
                +-- NO  --> expecting noise / outliers?
                              +-- YES --> HDBSCAN
                              +-- NO  --> Leiden (the default)
```

### The methods, and what each gets wrong

<a name="caution-leiden"></a>
**Leiden** -- builds a neighbour graph and splits it into communities. Scales to millions
of cells, does not need k. Hard labels, so no gradients. There is no single correct
**resolution** -- sweep it, and note the graph's `n_neighbors` matters as much.
(Traag et al. 2019.)

<a name="caution-kmeans"></a>
**KMeans** -- partitions around k centroids. Fast, but assumes round, equal-size clusters
and is sensitive to initialisation (QP-CAT runs 10 inits). **QP-CAT computes no elbow,
silhouette or gap statistic** -- the algorithm exposes `n_clusters` and nothing else, so
choosing k means running a few values and comparing; the Heatmap and Marker Rankings tabs
are the quickest read. Those statistics are worth knowing about
([References](references.md)) and routinely disagree with each other. (Fu & Perry 2017.)

<a name="caution-minibatch-kmeans"></a>
**MiniBatch KMeans** -- KMeans on small random batches. Much faster, slightly noisier
boundaries, same caveats. Confirm anything important with a full KMeans run.

<a name="caution-agglomerative"></a>
**Agglomerative** -- merges cells into a dendrogram you cut at k. Results depend strongly
on **linkage and distance metric** (Ward + Euclidean is a safe default); say which you
used. O(n^2), so subsample large datasets, and merges are greedy and never undone.
(Gere 2023.)

<a name="caution-hdbscan"></a>
**HDBSCAN** -- finds clusters as dense regions; needs neither k nor a distance threshold.
Cells in no dense region are labelled **noise**: QP-CAT keeps them in the result and
counts them, leaves them Unclassified in the viewer, and excludes them from composition
charts. Noise is not a cluster.

*Know the failure mode before choosing it.* HDBSCAN can only cut where there is a **gap in
density**. Populations can be well separated and still have no gap -- cell morphometry
(area, perimeter, caliper, OD means) usually forms one connected cloud with denser and
sparser regions. HDBSCAN then merges the populations into **one large cluster** and writes
each population's sparse fringe off as noise, however `min_cluster_size` is set. Raising it
makes this worse.

*A degenerate result says nothing about your measurements.* On a 304,083-cell H&E TMA,
HDBSCAN over 12 morphology and OD measurements returned one cluster (77.9% of cells), a
15-cell artefact, and 22.1% noise. KMeans over the **identical matrix** separated the cores
91-99% cleanly, and the discarded noise was spread evenly across all four KMeans clusters
(27/25/24/23%) -- every population's fringe, not a set of outliers. **Evenly-distributed
noise is the tell.** When you see it, change the algorithm first, to Leiden or KMeans,
which partition the data whether or not it has density gaps. Adding intensity or texture
measurements is worth doing on its own merits but is not the fix.

Use HDBSCAN when you genuinely expect distinct populations and want the method to tell you
how many. Sweep `min_cluster_size` and reduce dimensionality first -- density estimates
weaken in high dimensions. (McInnes & Healy 2017.)

<a name="caution-gmm"></a>
**Gaussian Mixture (GMM)** -- fits elliptical, unequal-size clusters that defeat KMeans, so
it helps when populations overlap. **QP-CAT assigns each cell to its most likely component
as a hard label** and does not export the per-component probabilities, so it cannot by
itself represent "partly A, partly B". You set the number of components directly; it is not
chosen by BIC or AIC. GMM assumes roughly Gaussian markers, and QP-CAT offers no
transform that fixes skew -- the available normalizations rescale but do not reshape a
distribution. Transform outside QuPath if that matters.
(Scrucca et al. 2016; Baudry et al. 2010.)

<a name="caution-banksy"></a>
**BANKSY** -- augments each cell with a summary of its spatial neighbourhood, then
clusters, unifying cell typing and tissue-domain detection. **lambda** trades cell type
against tissue domain; over-weighting the spatial term smooths away true boundaries. Needs
accurate coordinates, and is still a hard partition. (Singhal et al. 2024.)

<a name="caution-spatial-graph"></a>
**The spatial neighbour graph** (k-NN, fixed-radius, Delaunay) is chosen separately and
changes results as much as the clustering parameters do. See
[Neighborhoods and the spatial graph](spatial-neighborhoods.md).

### Strengths and weaknesses at a glance

| Algorithm | Strengths | Weaknesses |
|---|---|---|
| **Leiden** | No k needed. Scales well. Suits biological data. | Resolution needs tuning. |
| **KMeans** | Fast, simple, reproducible when k is known. | Assumes spherical clusters; initialisation-sensitive. |
| **HDBSCAN** | Arbitrary shapes; identifies noise. | Slow on very large data; needs a density gap. |
| **Agglomerative** | Produces a hierarchy. | Needs k; O(n^2). |
| **GMM** | Elliptical, unequal, overlapping populations. | Hard labels in QP-CAT; assumes Gaussian markers. |
| **BANKSY** | Spatial context; finds tissue domains. | Needs coordinates; more parameters. |

## Algorithm parameters

Starting points, all of them the dialog defaults:

| Algorithm | Start with | Then |
|---|---|---|
| **Leiden** | resolution 1.0, n_neighbors 50 | raise resolution for more clusters |
| **KMeans / MiniBatch** | k = 10 | adjust from biology or the heatmap |
| **HDBSCAN** | min_cluster_size 15 | lower to find smaller clusters |
| **Agglomerative** | k = 10, Ward linkage | justify any other linkage |
| **GMM** | 10 components, full covariance | |
| **BANKSY** | lambda 0.2, k_geom 15, resolution 0.7 | raise lambda toward tissue domains |

The random seed (default 42) is shared by the embedding and by every stochastic
algorithm -- KMeans, MiniBatch KMeans, GMM, Leiden, BANKSY.

## Spatial feature smoothing

A graph-convolution pre-step: build a neighbour graph from cell centroids, row-normalize
the adjacency, and replace each cell's measurements with a weighted average over its
neighbours. The smoothed matrix is what the algorithm then clusters, which makes **any**
algorithm spatially aware, not just BANKSY.

**Use it when nearby cells should look alike** -- tissue domains, tumour
microenvironment, or to stop isolated cells picking up spurious labels.

**Do not use it when neighbours should differ** -- immune cells infiltrating a tumour,
where distinguishing infiltrate from surrounding tumour is the point -- when spatial
location is irrelevant to the question, or when you are already using BANKSY, which has
its own spatial weighting.

**Controls.** The dialog exposes **Iterations** (1-5, default 1); each pass smooths
again over the same graph, so more passes reach further. The neighbourhood size *k* is
not in the dialog -- it comes from the **Spatial kNN** preference (default 15). Larger
*k* or more iterations means broader domains and less cell-level detail.

### Smoothing or BANKSY

| | Smoothing | BANKSY |
|---|---|---|
| Approach | Pre-process, then cluster | Spatial information inside the model |
| Works with | Any algorithm | Leiden only |
| Controls | Iterations (+ the kNN preference) | lambda, k_geom, resolution |
| Best for | Quick spatial awareness with your preferred algorithm | Tissue domains as the primary goal |

## Reducing features with PCA first

Panels of many markers across many compartments make very wide matrices -- 2 markers
across 34 compartments is 442 features. Clustering that directly is slow and noisy, so
QP-CAT offers the standard scanpy pre-step: reduce to principal components, then embed
and cluster on those.

- **Off by default**, and a decision you make before a run starts.
- It only engages when there are **more features than components** -- the **PCA Precursor
  Components** preference (default 50) is both the target dimensionality and the
  threshold. An ordinary panel is untouched.
- **BANKSY is exempt**: it runs its own PCA over spatially-augmented features, and a
  generic precursor would corrupt that.
- Marker rankings, the heatmap, the dot plot values and the cluster means keep using your
  **original measurements**, so cluster identities stay interpretable. Only the embedding
  and the algorithm see the components.

**Repeatable, but not comparable.** The same cells, settings and seed give the same
clusters every time; this does not make a run non-reproducible. What changes is the
result relative to the *same run with it off* -- those two are not comparable, so choose
once per study. Every run records which it used, in the operation log, the Workflow tab
entry, and `RUN_INFO.txt`. A config saved before the option existed loads with it off, so
those runs still reproduce exactly.

## Quick presets

**Extensions > QP-CAT > Explore & spatial > Quick clustering presets**. Each selects all
"Mean" measurements, Z-score normalization and a UMAP embedding, then runs immediately --
no dialog. Good for a first look.

| Preset | Runs |
|---|---|
| Quick Leiden (auto) | Leiden, n_neighbors 50, resolution 1.0 |
| Quick KMeans (k=10) | KMeans, 10 clusters |
| Quick HDBSCAN (auto) | HDBSCAN, min_cluster_size 15 |
| Quick Delaunay | Leiden on features smoothed over a Delaunay graph |
| Quick Delaunay (custom)... | the same, with the graph and smoothing parameters exposed |

## Clustering several images together

Set the scope to **All project images** or a chosen subset. Every cell across those
images is clustered in one run, so "Cluster 3" means the same thing in all of them --
which per-image runs cannot give you.

Two things to watch:

- **The measurement panel must match.** A measurement present on some images and absent
  on others is dropped automatically, with a warning naming it, because it would
  otherwise act as an image-discriminating constant.
- **Check the Composition by image tab afterwards.** If each cluster sits in one image,
  you have clustered by batch rather than by phenotype. See
  [Results](results.md#composition-by-image) for the diagnosis and the remedies.

For physically separate tissue on one slide -- TMA cores, multiple sections -- see
[independent areas](spatial-neighborhoods.md#independent-areas), which also lets Harmony
treat those areas as the batch.

## Embeddings without clustering

**Extensions > QP-CAT > Explore & spatial > Map cells in 2D (UMAP / PCA / t-SNE)...**
computes an embedding and writes UMAP1/UMAP2 (or PCA/tSNE) measurements without assigning
any cluster labels. Nothing is reclassified, so it is safe to run on a project you have
already labelled -- useful for looking at structure before deciding how to cluster, or
for plotting an existing classification in 2D via
[Plot & gate](clusters.md#gating-cells-on-a-2d-plot).
