# Recipes


Short, copy-the-steps recipes that combine the chapters above to do something
useful that isn't a single menu item.

### Recipe: cluster *on* a UMAP / t-SNE embedding (UMAP + HDBSCAN on embedding coordinates)

**What it is.** Reduce to 2-D first, then cluster on those two coordinates --
the popular "UMAP + DBSCAN on embedding coordinates" approach. QP-CAT's clustering normally
fits in **full marker space** (the embedding is computed only for the scatter and
plots), so to cluster *on the embedding* you run it in two steps.

**Steps.**

1. **Compute the embedding.** Run **Extensions > QP-CAT > Explore & spatial > Map cells in 2D (UMAP / PCA / t-SNE)...
   (UMAP / PCA / t-SNE)...** ([section 5](clustering.md#embeddings-without-clustering)) with UMAP.
   This writes `QPCAT UMAP1` and `QPCAT UMAP2` measurements onto every detection. (A normal
   clustering run with UMAP embedding writes them too.)
2. **Cluster on those two columns.** Open **Find cell populations
   (clustering)...** ([section 2](clustering.md)) and:
   - In the measurement picker, select **only `QPCAT UMAP1` and `QPCAT UMAP2`** (use the
     filter box to find them, Select none, then check just those two).
   - **Algorithm: HDBSCAN.**
   - **Cluster selection: Leaf.** This is the step that decides whether the recipe
     works at all -- see the note below.
   - **Normalization: None** (the coordinates are already on a comparable scale;
     z-scoring each axis separately stretches the embedding along one axis and
     squashes it along another, and the geometry is the only thing an embedding
     carries).
   - Run. The cluster step now fits on the UMAP coordinates rather than the
     markers.

**Notes.**

- **Set Cluster selection to "Leaf", or expect one cluster.** The default,
  "Excess of mass", is scikit-learn's and keeps the most *persistent* clusters,
  which favours large ones: where the lobes of an embedding differ in density it
  returns their common parent instead of the lobes. Measured on a 3D UMAP of
  107,282 cells with plainly separated lobes, excess of mass returned 4 clusters
  with one holding **97.0%** of the cells and only 0.9% noise. **The low noise
  fraction is the tell**: it means no boundary was cut anywhere, not that the
  populations blur into each other. "Leaf" takes the leaves of the density tree
  instead -- scikit-learn calls them "the most fine grained and homogeneous
  clusters".
- **Leave `min_samples` on auto (0)** unless you have a reason not to. Auto follows
  scikit-learn and uses `min_cluster_size`. A small `min_samples` beside a large
  `min_cluster_size` estimates density over a handful of points while demanding
  clusters of hundreds, which is the other way to get one giant cluster.
- **HDBSCAN is the DBSCAN to use here.** QP-CAT ships **HDBSCAN** (not plain
  DBSCAN); it is the strict upgrade -- no global `eps` to guess, it handles
  variable density, and it labels low-density points as a noise cluster (shown as
  its own class, which you can ignore or reclassify). See the HDBSCAN parameters
  in [the algorithm reference](clustering.md#caution-hdbscan).
- **Scale of the work / reproducibility.** Because UMAP itself is stochastic, fix
  the **random seed** (Dimensionality Reduction > Advanced) in step 1 so the same
  embedding -- and therefore the same HDBSCAN result -- reproduces.
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
  [REFERENCES.md](references.md) (UMAP).

---
