# Working with clusters

What to do with a result once you have one: judge it, rename it, split it, gate it,
or push it onto another image's detections.

- [Judging a result](#judging-a-result)
- [Modifying clusters: rename, merge, split, sub-cluster](#modifying-clusters)
- [The two editing paths, and which one you get](#the-two-paths-and-which-one-you-get)
- [Seeing a sub-cluster next to its parent clusters](#seeing-a-sub-cluster-next-to-the-clusters-it-came-from)
- [Sub-clustering](#sub-clustering-cluster-within-a-cluster)
- [Gating cells on a 2D plot](#gating-cells-on-a-2d-plot)
- [Saving and reloading a gate](#saving-and-reloading-a-gate)
- [Exporting crops and a feature table](#exporting-crops-and-a-feature-table)
- [Applying a saved result to detections](#applying-a-saved-result-to-detections)

## Judging a result

### Interactive Heatmap

- Each row is a cluster, each column is a marker
- Look for **distinct expression patterns** -- good clusters have markers that are clearly high in some clusters and low in others
- **Uniform rows** suggest the cluster may be splitting similar cells -- too many clusters
- **Very similar rows** suggest two clusters could be merged -- too few clusters

### Marker Rankings

- Top markers per cluster, ranked by a Wilcoxon rank-sum statistic (scanpy
  `rank_genes_groups`)
- **Read the ranking and the sign of the score** -- positive means higher in this cluster,
  negative means lower
- **The p-value is not evidence the cluster is real.** The clusters were built from these
  same measurements, so the test is circular; measured on pure noise with no clusters in it,
  47% of displayed rows still came back at adjusted p < 0.05. See
  [the marker p-values are circular](clustering.md#marker-pvalues-are-circular)
- If no marker separates a cluster even by rank, the clustering may be too fine-grained

### Embedding Scatter Plot

- Clusters should form visually distinct groups in UMAP/t-SNE space
- **Fragmented clusters** (same color scattered across the plot) may indicate poor clustering
- **Overlapping clusters** may indicate too many clusters specified
- **Click a point** to preview its cell (a crop loads below the plot) and select it in the
  hierarchy if its image is open; **double-click** to open that cell's image and center the
  field of view on it. Use this to ground-truth surprising points -- a cell sitting on a
  cluster boundary, or an outlier far from its group -- by looking at the actual pixels.
- Pan with a **middle-drag** (left-click is reserved for selection); scroll to zoom.

### Representative Cells

The **Representative cells** tab shows image crops of the most typical cells in each cluster.
For each cluster, cells are ranked by distance to the cluster center and the closest few are
shown (the **medoid** -- the single closest real cell -- is outlined). Click any crop to open
its image and center on it; **Save montages** writes one PNG strip per cluster next to the
other result plots.

Two definitions of "center" are offered:

- **Feature-space medoid** (default) -- the cell nearest the cluster mean in the normalized
  measurement space the clustering actually used. This is the most faithful answer to "what
  does a typical cell in this cluster look like".
- **Embedding-space medoid** -- the cell nearest the cluster's center in the 2D UMAP/t-SNE/PCA
  plot. Matches what you see as the visual middle of the blob, but the 2D embedding distorts
  true distances, so prefer feature-space unless you are specifically reasoning about the plot.

Caveats:

- A medoid is a **real cell, not a synthetic prototype or an average image** -- it is one
  observed cell that happens to sit near the center.
- "Representative" means **typical, not pure**. Overlapping clusters share borderline cells,
  and a cluster that is itself heterogeneous will have a medoid that under-represents its
  spread. Always read the representative crops alongside the Heatmap and Marker Rankings, not
  instead of them.
- The crop window is a multiple of each cell's bounding box (default 3x), so the cell fills a
  consistent fraction of every thumbnail regardless of objective magnification.

<a name="reading-the-composition-tabs"></a>
### Iterate

Clustering is rarely perfect on the first try. A typical workflow:
1. Run with defaults
2. Inspect heatmap and scatter plot
3. Adjust resolution/k, re-run
4. Merge or rename clusters for biological interpretability

---


---
<a name="renaming-and-merging"></a>
## Modifying clusters

One dialog does four things to the populations a clustering run produced:
**rename** them, **merge** several into one, **split** a merge back apart, and
**sub-cluster** one into sub-types. All four apply across every image the run covered.

**Extensions > QP-CAT > Results & populations > Modify cell populations (rename, merge, split,
sub-cluster)...**, or the **Modify clusters...** button in the Results window, which
pre-selects the result you are viewing.


Rename clusters to biological names (e.g. "Cluster 3" -> "CD8+ T Cells") or merge several
clusters into one -- **across every image the clustering run covered**, not just the open
image.

A rename or merge is a label edit, so it has to reach the same cells the run labelled. The
dialog is therefore built around a **saved clustering result**: it reads the run's per-cell
references and relabels the matching cells in all of the images that run touched (matched by
source image id + centroid, the same mechanism as "Apply saved result"). This is the
recommended and default path, and it is **non-destructive** -- your edit is written as a new
copy, and the original saved result is never changed.

You can open this dialog two ways: from the menu (**Extensions > QP-CAT > Results & populations > Modify cell populations (rename, merge, split, sub-cluster)...**), or from the **Results window** -- the **Modify clusters...** button in the "Cluster colors:" bar below the tabs. Launched from the Results window it **pre-selects the result you are viewing**, so the rename/merge is already scoped to exactly the images that result covers; you go straight to the cluster list.

### Using a saved result (recommended)

1. **Extensions > QP-CAT > Results & populations > Modify cell populations (rename, merge, split, sub-cluster)...** (or the **Modify clusters...** button in the Results window, which pre-selects the current result)
2. Under **Apply changes to**, leave **Use a saved clustering result (recommended)** selected
   and pick the run from the drop-down (each entry shows its timestamp and scope, e.g.
   "6 project images").
3. The list shows each cluster with its cell count (summed across all images in the run).
4. **To rename:** select one cluster, click **Rename...**, type the new name.
5. **To merge:** select two or more clusters (Ctrl/Cmd+click), click **Merge Selected**, type
   the merged name. Merged rows combine and show their constituent cluster numbers in
   brackets.
6. **To split a merge:** select the merged cluster, click **Split...**, and choose which of
   its constituents to separate out (all of them by default). See below.
7. Edits are **staged** -- nothing is written until you click **Apply**. (Use **Reset** to
   discard staged edits.)
8. Click **Apply**. You are asked to name the **new copy** (default `<result>_renamed`). QP-CAT
   then writes that copy and relabels the detections across all referenced images; a busy
   indicator runs while it works, and a summary reports how many cells were relabelled per
   image. Labels appear live -- no manual "Reload data" needed.

The original saved result and its plots are left untouched, so you can always go back to the
run's original labels.

### Splitting a merge apart

A merge is a **naming** operation: the clusters keep their own labels underneath, and the
merged row shows them in brackets (`Immune (4210 cells)  [Cluster 1, Cluster 4]`). Nothing is
discarded, so a merge you regret costs a click rather than a re-run.

Select the merged cluster and click **Split...**. The constituents are listed with their cell
counts, all selected:

- **Leave them all selected** to undo the merge -- each cluster goes back to its own name.
- **Deselect some** to keep those merged and separate out only the rest. Use this to pull one
  cluster out of a class that is otherwise right.

Like every other edit it is staged, so click **Apply** to write it. **Split...** is enabled
only for a row built from more than one cluster; to change a single cluster's name, use
**Rename...**.

This works on a merge you have just staged and on one applied in an earlier session -- reopen
the merged result from the drop-down and split it there. To undo an entire edit rather than one
merge, use **Step back** (next section).

### Renamed clusters in the Results window

Reopen the copy via **View Past Results...** and every tab shows your names -- the heatmap's
row labels, the embedding legend and hover text, the composition legend, pies and table
headers, the marker fingerprints, the representative-cell gallery and the Cluster Explainer.
Exported composition figures and CSVs carry them too, so a table reads `Tumor (n)` rather than
`Cluster 0 (n)`.

A partial rename is fine: any cluster you did not rename still shows as `Cluster N`. The
result's colour editor also follows the rename -- it edits the class your cells are actually
classified as, so recolouring "Tumor" recolours the overlay.

### Iterating, and stepping backwards

Refining phenotypes is rarely one pass, so the dialog is built to be run repeatedly and to be
undone.

**Every edit is a new version.** A rename/merge never modifies an existing saved result; it
writes a copy and records what that copy came from. So a session naturally leaves a chain --
`auto_20260804_leiden` -> `..._renamed` -> `..._renamed_v2` -- with every step still on disk.

**The chain is visible.** Each derived result shows its parent in
**Manage Saved Results...** (with the edit type: `<- split of '<parent>'`, `<- merge of...`, etc.), in the **View Past Results**
picker, in the Results-window title bar, and in the Manage Clusters status line. Without that,
five near-identical names read as five unrelated results rather than a history.

**Stepping back is one button.** With a derived result selected, **Step back to '<parent>'...**
re-applies the parent version's cluster names to the detections across the same images. It
does **not** delete anything -- both versions stay saved.

**Stepping forward is the same move.** **Put this version on the cells** re-applies whichever
saved result is currently selected. Step back, look, step forward again; or jump straight
between any two versions. Neither button writes a copy, so switching versions does not grow
the chain.

Three things worth knowing:

- **A merge is reversible because the raw labels are never rewritten.** Merging clusters 0 and
  1 into "Immune" maps two labels to one *name*; the per-cell integers stay 0 and 1. That is
  what lets the pre-merge version be restored exactly. A split works the same way in reverse.
- **Every derived result says where it came from, on disk.** Beside each result the folder
  holds `<name>_RUN_INFO.txt` and `<name>_config.json`. For a **sub-cluster** the record names
  the parent result and the parent class, and carries a Groovy script that re-runs it (loading
  the config sidecar, so the script cannot drift from what actually ran). For a **rename, merge
  or split** the record lists every name that changed, states which names now cover more than
  one cluster, and says how to undo it. Both are plain text -- open them, cite them, keep them
  with a figure.
- **Step back works on an edit or a sub-cluster, not on every result.** Anything that records a
  parent lights the button up and names the result it will return to. **Analyze current
  classifications** does not: it reads whatever the cells carry, so there is no single parent to
  return to. Use **Put this version on the cells** on the earlier result instead.
- **Do not delete a parent you might want back.** Step back needs the earlier result to still
  exist; if you delete it in Manage Saved Results, that rung of the ladder is gone. The listing
  shows which results are parents of others precisely so you can see what a deletion would
  cost.

### The two paths, and which one you get

The dialog has two ways of finding the cells to relabel. **You do not choose between them --
the dialog shows whichever applies**, because they are mutually exclusive: the manual path
exists only for the case the saved-result path cannot serve.

**Editing a saved result** (what you get whenever the project has one). The result records
each labelled cell by source image id and centroid, so an edit reaches exactly the cells that
run labelled, across every image it covered, and no others. It is written as a new copy, so
the original is never touched and you can step back. This is why it is preferred: correctness
comes from the recorded cell list, not from matching on a name.

**Opened from a Results window, the chooser lists only that result and its versions** -- what
it was derived from, and what has been derived from it (including its sub-clusters). Unrelated
runs are not offered. This matters because applying an edit does not just change what you are
looking at: it writes a copy of whichever result is selected *and* relabels detections across
the images that result covers, which may be a different set of images entirely. Opened from the
menu instead, with no result in mind, every saved result is listed.

**Choosing images manually** (only when the project has no saved result at all). There is no
recorded cell list to work from, so this path matches on the **current class name** instead
and relabels every detection carrying it in the images you pick. That is a weaker guarantee:
anything sharing the name is caught, whatever produced it. Its real use is data QP-CAT did not
create -- hand-drawn classifications, or classes imported from another tool -- where the point
is usually to tick **Save the result as a new saved result** and turn them into a result. After
that the saved-result path takes over and this one disappears.

> If you see the manual path and did not expect to, the project has no saved clustering result.
> Run a clustering analysis (QP-CAT auto-saves every run) and reopen the dialog.

### Sub-clustering: cluster within a cluster

Select **exactly one** cluster and click **Sub-cluster...** to re-cluster only that population.
The Run Clustering dialog reopens scoped to that class, and the result is written back as
`<name>.0`, `<name>.1`, ... replacing the parent class on those cells.

This is the answer to having too few clusters when raising the resolution globally would over-split
everything else: cluster on lineage markers first, then sub-cluster one lineage on its functional
markers.

- **Scope defaults to All project images**, unlike a normal run, which starts on the current
  image. Sub-clustering one image at a time gives each image its own `.0` derived from its own
  cells, so the sub-labels share a name without being the same population -- and nothing about
  the result says so. Pooling the class's cells across the selected images and clustering them
  **together in one run** makes `.0` mean the same thing everywhere; the labels are then written
  back to each image, and images with no cells of that class are skipped. *Current image* is
  still there when you want it.
- **It reads the class off the cells**, not the staged list. If you have renamed clusters but not
  yet applied them, use *Put this version on the cells* first.
- **Confirm carefully on a project-wide run.** Matching is by class **name**, so the confirmation
  dialog lists the actual cell count per image before anything is written. If a class name in
  another image came from somewhere other than this result, those cells would be re-classified
  too -- the counts are there so you see that before agreeing.
- The result is auto-saved like any other run: it appears in **View Past Results**, opens the full
  results window (heatmap, embedding, plots, 3D view), and its sub-clusters can themselves be
  renamed or merged in Manage Clusters.

---
## Seeing a sub-cluster next to the clusters it came from

Load a sub-cluster result and you see `Cluster 1.0`, `Cluster 1.1`, ... and nothing else --
no `Cluster 0`, no `Cluster 2`. That is not a display problem. A saved result is a snapshot of
**one run's cells**, and the sub-cluster run only extracted `Cluster 1`'s cells; the others were
never in it, so there is nothing to draw.

The combined labelling does exist -- on the objects. After sub-clustering the cells carry
`Cluster 0`, `Cluster 2` and `Cluster 1.0 ... N` all at once.

**Extensions > QP-CAT > Results & populations > Analyze current cell classifications...**
reads that state and computes the full analysis over it: heatmap, marker rankings, composition
tabs and embedding, with every population present. Marker Rankings then answers the question
sub-clustering on its own cannot -- how the sub-clusters differ from the parent's *other*
clusters, not just from each other.

**It writes nothing.** No classification is created, changed or removed. It is the read-only
counterpart to clustering, which matters because it deliberately reads classes QP-CAT did not
create.

Because it reads the **objects**, not a saved result, the cells have to be carrying the
labelling you want to see. Straight after a sub-cluster run they are. To get back there from
saved results later, apply the parent result and then the sub-cluster result -- applying only
relabels each result's own cells, so the sub-cluster overwrites `Cluster 1`'s cells and leaves
the rest as the parent set them -- then run the analysis.

It is not limited to sub-clusters: it works over phenotyping output, hand-edited classes, or an
imported classifier's labels, and gives any of them a full results window.

Two things to know before running it:

- **Untick the run's own outputs.** After a clustering run the measurement list includes that
  run's `QPCAT UMAP1/2/3` and `QPCAT spatial:` columns. Analysing those is circular -- you would be
  grouping cells on coordinates derived from their own clusters. Select the marker measurements.
  The **Deselect QPCAT** button in the measurement picker clears all of QP-CAT's output in one click.
- **Unclassified cells are excluded**, and the count is shown. An unclassified bucket is a
  mixture rather than a population; including it would pull every marker mean toward the
  unlabelled remainder and give the ranking a comparison group that means nothing.

## Gating cells on a 2D plot

Draw a polygon ("gate") around a group of points on a 2D scatter and act on the
cells inside -- select them in the image, or assign them a classification across
every image. This is the QuPath-native version of the flow-cytometry / CytoMAP
"lasso a population off the t-SNE" workflow, with the difference that the gate can
write a **persistent class**, so the population stays colored in every image and
survives reload.

### Two places to gate

- **In the clustering Results dialog -- the "Embedding" tab.** After any run with
  an embedding, the scatter has a **Gate** toggle. This gates on the run's own
  UMAP/t-SNE/PCA, colored by cluster. Works on reopened past results too.
- **The standalone tool: Extensions > QP-CAT > Explore & spatial > Plot & gate cells (2D)...** Pick a
  **scope** (Current / All / Specific images) and an axis source:
  - **2D embedding** -- plots existing embedding coordinates (`QPCAT UMAP1/UMAP2`, `QPCAT tSNE1/2`,
    `QPCAT PCA1/2`, etc.). Run **Map cells in 2D** ([Clustering](clustering.md#embeddings-without-clustering))
    or clustering first to create them.
  - **Two markers (biaxial)** -- plots any two measurements against each other
    (classic biaxial gating). No precomputation needed.
  Points are colored by their current classification so you can see existing
  populations while gating.

### How to gate

1. Click **Gate**. The cursor now draws instead of panning (pan stays on
   middle-drag, zoom on scroll).
2. **Click** to drop polygon vertices around the points you want.
3. **Double-click** (or **right-click**) to close the polygon; **Esc** cancels an
   in-progress gate. The enclosed cells highlight and the count updates
   ("N cells gated").
4. Act on them:
   - **Select in open image** -- selects the gated cells that belong to the image
     currently open in the viewer (no write, like CytoMAP's selection).
   - **Assign class...** -- type a name (default "Gate 1") and it is applied to
     **all** gated cells across **every** image they came from, saving each. The
     cells take that classification (coloring them in QuPath) and it persists.
5. **Clear** removes the gate you are drawing so you can start another.
   **Assign class...** also leaves the gate behind as a labelled coloured
   outline, so you can work through several populations in turn and still see
   where the earlier ones were; **Clear all** removes those outlines.

> Assigning a class **overwrites** the gated cells' current classification (a
> detection has one class). Gate on a copy or note the original classes first if
> you need them back; re-running clustering/phenotyping restores the cell-type
> column.

<a name="saving-and-reloading-a-gate"></a>
### Saving and reloading a gate

A gate is an analysis decision: it defines a population that then gets a name and
gets reported on. Two things record it.

**Every gate that assigns a class writes itself into the project, unasked.** When
you click **Assign class...**, QP-CAT writes the polygon to
`<project>/qpcat/gates/<class name>_<timestamp>.gates.json` and logs the event in
the operation log, naming that file. You do not have to remember to save anything
for the population to be traceable back to the geometry that chose it.

**Save gates... / Load gates...** are the deliberate version, beside the gate bar
on both plots. **Save** writes every outline on the plot -- the labelled ones and
the one you are drawing -- to a file you name. **Load** puts them back.

The file is plain JSON you can read without QuPath:

```json
{
  "formatVersion": 1,
  "axisMode": "biaxial",
  "axisName": "biaxial",
  "columnX": "CD3: Mean",
  "columnY": "CD8: Mean",
  "nCells": 48213,
  "dataFingerprint": "48213-3f1a9c0b7d24e88",
  "gates": [
    {"label": "CD8 high", "vertices": [[0.42, 1.90], [0.95, 1.90], [0.95, 3.10]],
     "cellsWhenDrawn": 1204}
  ]
}
```

Vertices are in the plot's **own units** -- marker values on a biaxial plot,
embedding coordinates on an embedding -- not screen pixels, so a saved gate does
not depend on the zoom, the window size or the screen it was drawn on.

#### A gate on an embedding is only replayable onto the same coordinates

This is the one failure you cannot see, so QP-CAT checks it for you.

A polygon will happily draw itself onto any plot. Whether the cells underneath are
the cells it was drawn around is a question about the **axes**, and the two axis
kinds are completely different:

- **Biaxial gates replay.** The axes are raw measurements, which are a property of
  the cell. Load a biaxial gate onto other cells, other images or a later session
  and it asks the same question it asked when you drew it. QP-CAT says so and gets
  on with it.
- **Embedding gates usually do not.** UMAP, t-SNE and PCA coordinates belong to the
  *run* that produced them, not to the cell. Reproducing a layout needs the same
  cells, the same measurements, the same parameters **and** the same seed; change
  any one and the layout is free to rotate, reflect and rescale without a single
  cell changing its neighbours. (That the initialization, not the data, fixes the
  arrangement is Kobak & Linderman's finding -- see
  [References](references.md#embedding-layout-is-not-stable).)

**Measured, because the size of this matters.** 600 cells in three well-separated
blobs; a gate drawn around one blob held about 200 cells. Re-lay the same cells --
reflect, rotate 37 degrees, rescale 1.1, which changes no neighbour and no cluster
-- and drop the same polygon back on:

| | gate on the original layout | same gate, re-laid layout |
|---|---|---|
| cells held | ~200 | 53 to 85 |
| cells held that were in the original selection | 200 | **0** |

Zero overlap, in five of five seeds. The gate does not fail, does not empty, and
does not look wrong. It selects a different population and reports a plausible
count.

**So QP-CAT never loads a gate silently.** Every load reports what it found:

- **Same axes, same coordinates** -- each gate selects exactly the cells it
  selected before. Stated plainly, no warning.
- **Same axis columns, different coordinates** -- on a biaxial plot, the ordinary
  useful case, with the cell counts then and now. On an embedding, a **WARNING**
  spelling out that the layout is a different one and that the gates will hold a
  different set of cells.
- **Different axis columns** -- refused. There is no reading of the polygon that
  means anything over different quantities.

Either way the dialog lists each gate with the cells it holds **here** against the
cells it held when saved, and flags the ones that changed, so you see the damage
before you assign a class from it. You can also make one loaded gate the active
selection, which is what makes **Select in open image** and **Assign class...**
work on it.

### Notes

- Gating across a multi-image plot resolves each point back to its detection by
  centroid, so "Assign class" correctly writes to the right cells in each image.
- "Select in open image" only touches the open image; "Assign class" touches all
  images in the plot. Choose by whether you want a transient look or a saved
  population.
- The standalone tool reads existing coordinates only -- it never runs Python.
- A gate is anchored to the plot's own coordinates, not to the window, so
  scrolling to zoom, middle-dragging to pan or resizing the window moves the
  outline with the points it encloses. You can draw a rough gate, zoom in and
  keep adding vertices.

---
<a name="exporting-crops-and-a-feature-table"></a>
## Exporting crops and a feature table

**Menu: Extensions > QP-CAT > Export > "Export cell crops + feature table (TraitHorizon / CSV)..."**

Writes one small PNG per cell plus a table whose first column names that PNG and
whose other columns are the cell's measurements:

```
images/cell_000000.png
images/cell_000001.png
features.tsv
features.csv          (optional)
README.txt
```

Two reasons to want it.

**A QC pass QP-CAT does not offer.** That table shape is the input format of
[TraitHorizon](https://github.com/choosehappy/TraitHorizon), a separate tool that
draws a **parallel-coordinates plot over every column at once** -- drag along an
axis to brush a range, and the data grid and the images update to the brushed
subset. Its published use case is finding segmentation artifacts and extremal
objects at population scale, which answers a question QP-CAT's own
[Representative Cells](#representative-cells) gallery does not: that gallery shows
medoids, i.e. what is *typical*, and this shows what is *broken*. QP-CAT writes the
file format and uses none of TraitHorizon's code; see
[References](references.md#traithorizon).

**Or just the numbers.** Untick the crops and tick CSV and it is a plain per-cell
measurement table for Excel, R or pandas, with the classification as a column.

### The columns

| Column | What it is |
|---|---|
| `filename` | the crop in `images/`. TraitHorizon requires this first, and it must name a file that is really there |
| *measurement* | the QuPath measurement of that name, unchanged |
| `class_index` | 0-based index into the classification list in `README.txt` |
| `classification` | the classification name. Text, so not an axis |
| `image` | the source image name. Text, so not an axis |

A measurement whose name collides with a column QP-CAT adds gets a
`_measurement` suffix -- TraitHorizon requires every feature name to be unique, and
the renaming is visible rather than a silently dropped column.

TraitHorizon cannot plot a text column as an axis, so it takes a `--hide_axes`
flag for them. The dialog shows the **exact command to run**, with that flag
already filled in, and writes it into `README.txt` as well:

```
traithorizon /path/to/export/images /path/to/export/features.tsv --hide_axes classification image
```

### Two things that will bite you, and what QP-CAT does about them

**A cell missing any chosen measurement is dropped.** TraitHorizon requires no
missing values, so there is nowhere to put a blank. That means a measurement
present on 3% of cells deletes 97% of the export -- so the dialog **measures
coverage on the open image before you run it** and says, in cells, how many you
would lose and which measurements are incomplete. Deselect the sparse ones and
keep the cells.

**One file per cell means the filesystem carries the export.** A 300,000-cell
project is 300,000 PNGs. So the export takes a **total cell budget** (default
2,000), spread across classifications in proportion to their size with a floor
(default 30) so a rare population is not sampled away. The draw is seeded, so the
same settings write the same file twice. It is a **sample**: counts read off the
table are counts of the sample, and `README.txt` says so along with exactly what
was left out and why.

### Notes

- The crops are rendered with the viewer's **current brightness, contrast and
  channel selection**, at 3x the cell bounding box by default. They are a picture
  of the cell, not its pixel data -- do not measure them.
- Scope can be the current image, the whole project or a chosen subset; crop
  filenames are globally unique across images.
- The TSV is written as UTF-8 **without** a byte-order mark and the CSV **with**
  one. That is not a detail: a BOM would rename TraitHorizon's required
  `filename` column to something it does not recognise, and Excel without one
  mangles the non-ASCII characters ordinary QuPath measurement names carry.
- TraitHorizon links each object out to an external viewer by URL. QP-CAT does not
  write that column, because [clicking a point](#embedding-scatter-plot) already
  navigates straight into the QuPath viewer.

---
## Applying a saved result to detections

**Menu: Extensions > QP-CAT > Results & populations > "Apply saved result to detections..."**

Writes a previously saved clustering result's labels back onto detections. Use it
when a saved result holds the correct labels but they are not showing on the open
image (e.g. after reopening the project), or to re-label detections from an older
run.

**How it works.** Pick a saved result; QP-CAT shows a pre-flight summary -- the
saved cluster/cell counts, and (for the open image) a **predicted match count**: a
dry run of the centroid match so you know how many cells will actually be labelled
before you commit (the raw count comparison alone is misleading, since matching is
by centroid, not count). Cells are matched to detections by **source image id +
centroid**, robust to detection reordering; cells that cannot be matched (e.g. the
detections were re-segmented) are **reported, not mislabeled**.

**Result-scoped class names.** Applied labels are namespaced by the result name --
`<result>: Cluster N` -- so labels from different saved results coexist on the same
detections without colliding on a shared "Cluster N". The saved palette is restored
onto those namespaced classes, and any applied embedding measurements are likewise
prefixed with the result name.

**Options.**

- *Current image only* vs *All images referenced by the result*.
- *Also write the saved embedding coordinates* (adds `<result>1`/`<result>2`).

Applying fires a hierarchy-changed event, so labels appear immediately -- no manual
"Reload data" needed. A summary reports how many cells were labelled per image and
any that were unmatched.

> Note: `Cluster N` (without a namespace) is a QuPath-wide shared class used by the
> live clustering run. Applying saved results namespaces them so multiple results
> don't fight over it; the working run still uses the bare `Cluster N`.

---
