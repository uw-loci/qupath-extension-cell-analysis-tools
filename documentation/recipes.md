# Recipes


Short, copy-the-steps recipes that combine the other pages to do something
useful that isn't a single menu item.

### Recipe: cluster *on* a UMAP / t-SNE embedding (UMAP + HDBSCAN on embedding coordinates)

**What it is.** Reduce to 2-D first, then cluster on those two coordinates --
the popular "UMAP + DBSCAN on embedding coordinates" approach. QP-CAT's clustering normally
fits in **full marker space** (the embedding is computed only for the scatter and
plots), so to cluster *on the embedding* you run it in two steps.

**Steps.**

1. **Compute the embedding.** Run **Extensions > QP-CAT > Explore & spatial > Map cells in 2D (UMAP / PCA / t-SNE)...**
   ([Embeddings without clustering](clustering.md#embeddings-without-clustering)) with UMAP.
   This writes `QPCAT UMAP1` and `QPCAT UMAP2` measurements onto every detection. (A
   clustering run with a UMAP embedding writes `QPCAT 2D UMAP1` and `QPCAT 2D UMAP2` instead,
   the name in its **Name** box, so pick those in step 2 if that is where your embedding came
   from.)
2. **Cluster on those two columns.** Open **Find cell populations
   (clustering)...** ([Clustering](clustering.md)). In the measurement picker:
   1. **Clear the filter box**, then click **Select None**. This unticks everything,
      including the ticks your last run left; the buttons act only on the rows the filter
      shows.
   2. **Type `QPCAT UMAP` in the filter** and **tick `QPCAT UMAP1` and `QPCAT UMAP2`**.

   Then set, in the dialog's order:

   | Field | Set to |
   |---|---|
   | Normalization | None (raw values) |
   | Algorithm | HDBSCAN |
   | Cluster selection | Leaf (finest clusters) |
   | `min_cluster_size` | the smallest group you want to recover (see below) |
   | Batch correction (Harmony) - for multi-image clustering | unticked |

   - **Normalization: None**, because the coordinates are already on a comparable scale;
     z-scoring each axis separately stretches the embedding along one axis and
     squashes it along another, and the geometry is the only thing an embedding
     carries.
   - **Cluster selection: Leaf (finest clusters).** Without it HDBSCAN usually returns one
     cluster; see the note below.
   - **`min_cluster_size`: set it deliberately.** The default 15 is a floor for
     finding something rare; with **Leaf** selection, which cuts at the finest
     level of the tree, a floor that low shatters populations into fragments.
     Scale it to the smallest group you actually want to recover -- comfortably
     below that group's size, comfortably above 15.
   - **Batch correction: off.** It will be selectable if several images are in
     scope, but there is nothing here for it to correct -- the input is the
     embedding, and correcting that adjusts the picture rather than the
     measurements that produced it. Batch correction belongs in step 1, the run
     that computes the embedding. Whatever batch structure was present then is
     already baked into the coordinates you are about to cluster.
3. **Run Clustering.** The cluster step now fits on the UMAP coordinates rather than the
   markers. The progress line ends with "Clustering complete: N clusters (+M noise) found
   for X cells.", and the cells take their cluster colors in the viewer, with noise left
   unclassified.

**Then find out what the clusters ARE.** Clustering on two (or three) embedding columns means Marker Rankings, the heatmap and the fingerprints
can only rank those two or three columns -- they will tell you a cluster is high in `UMAP2`, which is
not a phenotype. The markers are still on the cells; nothing has read them yet.

**Extensions > QP-CAT > Results & populations > Analyze current cell classifications...**
does that read. It takes the classes the cells now carry -- the clusters you just applied --
and computes the full marker surface over them: heatmap, marker rankings, fingerprints,
composition. In its **Measurements** list pick your **markers** this time, not the embedding
columns. It writes nothing back; no classification is added, changed or removed. Its Marker
Rankings and heatmap then name each cluster by the markers that define it.

So the pair is: cluster in the embedding, characterize in marker space.

**Notes.**

- **Set Cluster selection to "Leaf (finest clusters)", or expect one cluster.** The default,
  "Excess of mass (default)", is scikit-learn's and keeps the most *persistent* clusters,
  which favors large ones: where the lobes of an embedding differ in density it
  returns their common parent instead of the lobes. Measured on a 3D UMAP of
  107,282 cells with plainly separated lobes, excess of mass returned 4 clusters
  with one holding **97.0%** of the cells and only 0.9% noise. Little noise means
  no boundary was cut anywhere, not that the populations blur into each other. "Leaf" takes the leaves of the density tree
  instead -- scikit-learn calls them "the most fine grained and homogeneous
  clusters".
- **Leave `min_samples` on auto (0)** unless you have a reason not to. Auto follows
  scikit-learn and uses `min_cluster_size`. A small `min_samples` beside a large
  `min_cluster_size` estimates density over a handful of points while demanding
  clusters of hundreds, which is the other way to get one giant cluster.
- **A `min_cluster_size` sweep is not a one-variable sweep.** Because auto ties
  `min_samples` to it, raising the floor also widens the neighborhood the density
  is estimated over. Usually what you want, but say which you changed, and which
  **Cluster selection** you used, when you report a result (the run's `RUN_INFO.txt`
  records both), and pin `min_samples` to a fixed number if you need the two
  separated.
- **HDBSCAN is the DBSCAN to use here.** QP-CAT ships **HDBSCAN** (Campello et al. 2013;
  McInnes, Healy & Astels 2017) rather than plain DBSCAN, the usual choice because
  there is no global `eps` to guess, it handles clusters of different density, and it leaves
  low-density points as noise: they stay unclassified
  and are counted separately, never as a cluster ("5 clusters (+606 noise)"). See the HDBSCAN parameters
  in [the algorithm reference](clustering.md#caution-hdbscan).
- **Scale of the work / reproducibility.** UMAP itself is stochastic. **Map cells in 2D**
  has no seed box; it uses seed 42, so step 1 reproduces on fewer than 200,000 cells. Above
  that its *Automatic* mode drops the seed for speed (see [Reproducibility](reproducibility.md)).
  If you compute the embedding in a clustering run instead, keep **Random seed (clustering +
  embedding)** in the Clustering Algorithm section fixed and set the embedding's mode to
  *Reproducible* under Dimensionality Reduction > Advanced, so the same embedding -- and
  therefore the same HDBSCAN result -- reproduces at any size.
- **Caveat (cite this).** Clustering on a 2-D UMAP "works" and is widely used,
  but the UMAP authors caution against treating it as ground truth: UMAP "does
  not completely preserve density" and "can also create false tears in clusters,
  resulting in a finer clustering than is necessarily present in the data," so
  clustering on the embedding "is somewhat controversial, and should be attempted
  with care" (UMAP docs, *Using UMAP for Clustering*,
  <https://umap-learn.readthedocs.io/en/latest/clustering.html>). In practice it
  can over- or under-split versus graph clustering (Leiden) on the full marker
  space. Treat the cluster count as a starting point, validate the clusters, and
  cross-check against a full-space run when it matters. See
  [References](references.md#umap) (UMAP).

---
