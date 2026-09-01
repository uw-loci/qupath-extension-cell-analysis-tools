# Working with clusters

What to do with a result once you have one: judge it, rename it, split it, gate it,
or push it onto another image's detections.

- [Judging a result](#judging-a-result)
- [Renaming and merging](#renaming-and-merging)
- [Sub-clustering](#sub-clustering-cluster-within-a-cluster)
- [Gating cells on a 2D plot](#gating-cells-on-a-2d-plot)
- [Applying a saved result to detections](#applying-a-saved-result-to-detections)

## Judging a result

### Interactive Heatmap

- Each row is a cluster, each column is a marker
- Look for **distinct expression patterns** -- good clusters have markers that are clearly high in some clusters and low in others
- **Uniform rows** suggest the cluster may be splitting similar cells (over-clustering)
- **Very similar rows** suggest two clusters could be merged (under-clustering)

### Marker Rankings

- Top differentially expressed markers per cluster (Wilcoxon rank-sum test)
- **High scores with low p-values** indicate strong markers for that cluster
- If no markers are significantly different, the clustering may be too fine-grained

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
## Renaming and merging

Rename clusters to biological names (e.g. "Cluster 3" -> "CD8+ T Cells") or merge several
clusters into one -- **across every image the clustering run covered**, not just the open
image.

A rename or merge is a label edit, so it has to reach the same cells the run labelled. The
dialog is therefore built around a **saved clustering result**: it reads the run's per-cell
references and relabels the matching cells in all of the images that run touched (matched by
source image id + centroid, the same mechanism as "Apply saved result"). This is the
recommended and default path, and it is **non-destructive** -- your edit is written as a new
copy, and the original saved result is never changed.

You can open this dialog two ways: from the menu (**Extensions > QP-CAT > Results & populations > Rename or merge cell populations...**), or from the **Results window** -- the **"Rename or merge clusters..."** button in the "Cluster colors:" bar below the tabs. Launched from the Results window it **pre-selects the result you are viewing**, so the rename/merge is already scoped to exactly the images that result covers; you go straight to the cluster list.

### Rename / merge using a saved result (recommended)

1. **Extensions > QP-CAT > Results & populations > Rename or merge cell populations...** (or the **"Rename or merge clusters..."** button in the Results window, which pre-selects the current result)
2. Under **Apply changes to**, leave **Use a saved clustering result (recommended)** selected
   and pick the run from the drop-down (each entry shows its timestamp and scope, e.g.
   "6 project images").
3. The list shows each cluster with its cell count (summed across all images in the run).
4. **To rename:** select one cluster, click **Rename...**, type the new name.
5. **To merge:** select two or more clusters (Ctrl/Cmd+click), click **Merge Selected**, type
   the merged name. Merged rows combine and show their constituent cluster numbers in
   brackets.
6. Edits are **staged** -- nothing is written until you click **Apply**. (Use **Reset** to
   discard staged edits.)
7. Click **Apply**. You are asked to name the **new copy** (default `<result>_renamed`). QP-CAT
   then writes that copy and relabels the detections across all referenced images; a busy
   indicator runs while it works, and a summary reports how many cells were relabelled per
   image. Labels appear live -- no manual "Reload data" needed.

The original saved result and its plots are left untouched, so you can always go back to the
run's original labels.

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
**Manage Saved Results...** (`<- rename/merge of '<parent>'`), in the **View Past Results**
picker, in the Results-window title bar, and in the Manage Clusters status line. Without that,
five near-identical names read as five unrelated results rather than a history.

**Stepping back is one button.** With a derived result selected, **Step back to '<parent>'...**
re-applies the parent version's cluster names to the detections across the same images. It
does **not** delete anything -- both versions stay saved.

**Stepping forward is the same move.** **Put this version on the cells** re-applies whichever
saved result is currently selected. Step back, look, step forward again; or jump straight
between any two versions. Neither button writes a copy, so switching versions does not grow
the chain.

Two things worth knowing:

- **A merge is reversible because the raw labels are never rewritten.** Merging clusters 0 and
  1 into "Immune" maps two labels to one *name*; the per-cell integers stay 0 and 1. That is
  what lets the pre-merge version be restored exactly.
- **Do not delete a parent you might want back.** Step back needs the earlier result to still
  exist; if you delete it in Manage Saved Results, that rung of the ladder is gone. The listing
  shows which results are parents of others precisely so you can see what a deletion would
  cost.

### If you have no saved result (manual fallback)

The **Choose images manually** option is **disabled whenever a saved result exists** -- the
saved-result path above is safer and reaches exactly the right cells. It unlocks only when no
saved-result JSON is found in the project. In that case it relabels detections by their
current class name across the image scope you choose (Current image / All / Specific images),
and offers **Save the result as a new saved result** so you can bootstrap a reusable result
and target it directly next time.

> Tip: if Manage Clusters offers only the manual path, run a clustering analysis first (QP-CAT
> auto-saves each run), then reopen this dialog -- the saved-result path will be available.

### Sub-clustering: cluster within a cluster

Select **exactly one** cluster and click **Sub-cluster...** to re-cluster only that population.
The Run Clustering dialog reopens scoped to that class, and the result is written back as
`<name>.0`, `<name>.1`, ... replacing the parent class on those cells.

This is the answer to under-clustering when raising the resolution globally would over-split
everything else: cluster on lineage markers first, then sub-cluster one lineage on its functional
markers.

- **Scope works as it does for a normal run.** *Current image* re-clusters the open image only.
  *All* / *Specific images* pools that class's cells across the selected images and clusters them
  **together in one run**, so `.0` means the same thing in every image -- and writes the labels
  back to each. Images with no cells of that class are skipped.
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
  - **2D embedding** -- plots existing `UMAP1/UMAP2` (or `tSNE1/2`, `PCA1/2`)
    coordinates. Run **Map cells in 2D** ([Clustering](clustering.md#embeddings-without-clustering))
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
5. **Clear** removes the gate to start another.

> Assigning a class **overwrites** the gated cells' current classification (a
> detection has one class). Gate on a copy or note the original classes first if
> you need them back; re-running clustering/phenotyping restores the cell-type
> column.

### Notes

- Gating across a multi-image plot resolves each point back to its detection by
  centroid, so "Assign class" correctly writes to the right cells in each image.
- "Select in open image" only touches the open image; "Assign class" touches all
  images in the plot. Choose by whether you want a transient look or a saved
  population.
- The standalone tool reads existing coordinates only -- it never runs Python.

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
