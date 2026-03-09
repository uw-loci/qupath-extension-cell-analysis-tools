# PyClustering -- Best Practices

Recommendations for getting the best results from cell clustering and phenotyping in multiplexed imaging data.

---

## Table of Contents

1. [Before You Start](#before-you-start)
2. [Measurement Selection](#measurement-selection)
3. [Normalization](#normalization)
4. [Choosing a Clustering Algorithm](#choosing-a-clustering-algorithm)
5. [Cluster Evaluation](#cluster-evaluation)
6. [Phenotyping Strategy](#phenotyping-strategy)
7. [Spatial Analysis](#spatial-analysis)
8. [Multi-Image Projects](#multi-image-projects)
9. [Reproducibility](#reproducibility)
10. [Common Pitfalls](#common-pitfalls)

---

## Before You Start

### Cell Detection Quality Matters

Clustering quality depends heavily on the quality of upstream cell detection and segmentation. Before running PyClustering:

- Verify that cell boundaries are reasonable (spot-check several regions)
- Check that cells are not over- or under-segmented
- Ensure that detection measurements are meaningful (not dominated by background)

### Number of Cells

- **Minimum:** ~200 cells for meaningful clustering
- **Recommended:** 1,000-50,000 cells for standard analyses
- **Large datasets (>100,000):** Consider MiniBatch KMeans or subsetting via annotation selection
- **Very small datasets (<200):** Results may be unstable; consider increasing cell detection sensitivity

---

## Measurement Selection

### Use Mean Intensity Measurements

For marker-based clustering, select **mean intensity** measurements rather than median, max, or area-based measurements. Mean intensity:
- Best represents the average expression level within each cell
- Is most comparable across cells of different sizes
- Is the standard input for single-cell clustering workflows

Click **Select 'Mean' only** in the measurement selection panel.

### Exclude Irrelevant Measurements

Remove measurements that do not carry biological information:
- DAPI/Hoechst channels (nuclear stain, not a biological marker)
- Autofluorescence channels
- Area, perimeter, and other morphological measurements (unless specifically relevant)

Including irrelevant measurements adds noise and can obscure biological signal.

### Marker Panel Considerations

- **Highly correlated markers** (e.g., two antibodies for the same target) may bias clustering toward those markers. Consider including only one.
- **Markers with very low signal** may contribute mostly noise. Check histograms before including.
- **Mixed marker types** (surface markers + functional markers + transcription factors) are fine and can help identify cell states.

---

## Normalization

### When to Use Each Method

| Method | Best for | Avoid when |
|--------|----------|------------|
| **Z-score** | General-purpose clustering. Makes all markers equally weighted. | Marker distributions are extremely non-normal |
| **Min-Max [0,1]** | Phenotyping with gating. Values have intuitive scale. | Outliers strongly affect the range |
| **Percentile** | Robust alternative to Min-Max. Tolerates outliers. | Data has very few cells |
| **None** | Data is already normalized, or you want to preserve raw scale. | Markers have very different scales |

### Default Recommendation

**Z-score normalization** is recommended for clustering. It ensures each marker contributes equally regardless of its absolute intensity scale.

For **phenotyping with gating**, use **Min-Max** or **Percentile** normalization so that gate thresholds fall in the intuitive [0,1] range.

### Important: Normalization Affects Gate Values

When switching normalization methods in the phenotyping dialog, your gate thresholds should be adjusted:
- Z-score: typical gates around 0.0 (mean)
- Min-Max: typical gates around 0.3-0.7
- Percentile: typical gates around 0.3-0.7
- None: gates in raw intensity units (marker-dependent)

---

## Choosing a Clustering Algorithm

### Decision Tree

```
Start here
  |
  v
Do you know how many clusters to expect?
  |
  +-- YES --> Is the number well-defined?
  |             |
  |             +-- YES --> KMeans or Agglomerative
  |             +-- ROUGHLY --> GMM (allows soft boundaries)
  |
  +-- NO --> Is spatial location important?
               |
               +-- YES --> BANKSY
               +-- NO --> Is there likely noise/outliers?
                            |
                            +-- YES --> HDBSCAN (labels outliers as noise)
                            +-- NO --> Leiden (recommended default)
```

### Algorithm Strengths

| Algorithm | Strengths | Weaknesses |
|-----------|-----------|------------|
| **Leiden** | No need to specify k. Scales well. Well-suited for biological data. | Resolution parameter requires tuning. |
| **KMeans** | Fast, simple, reproducible. Good when k is known. | Assumes spherical clusters. Sensitive to initialization. |
| **HDBSCAN** | Detects arbitrary-shaped clusters. Identifies noise. | Can be slow on very large datasets. Sensitive to min_cluster_size. |
| **Agglomerative** | Produces a hierarchy. Ward linkage works well. | Requires k. Computationally expensive for large n. |
| **GMM** | Probabilistic (soft assignment). Flexible cluster shapes. | Assumes Gaussian distributions. Can be slow. |
| **BANKSY** | Incorporates spatial context. Finds tissue domains. | Requires spatial coordinates. More parameters to tune. |

### Starting Points for Parameters

- **Leiden:** Start with resolution=1.0 and n_neighbors=50. Increase resolution for more clusters, decrease for fewer.
- **KMeans:** Start with k=10 and adjust based on biological knowledge or heatmap inspection.
- **HDBSCAN:** Start with min_cluster_size=15. Decrease to find smaller clusters, increase for stricter clustering.
- **BANKSY:** Start with lambda=0.2, k_geom=15, resolution=0.7.

---

## Cluster Evaluation

After clustering, assess quality using the results dialog:

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

### Iterate

Clustering is rarely perfect on the first try. A typical workflow:
1. Run with defaults
2. Inspect heatmap and scatter plot
3. Adjust resolution/k, re-run
4. Merge or rename clusters for biological interpretability

---

## Phenotyping Strategy

### Unsupervised First, Then Supervised

A recommended approach:
1. Run **unsupervised clustering** first to understand the data
2. Use the **heatmap and marker rankings** to identify biological populations
3. **Rename clusters** to biological names (via Manage Clusters)
4. OR define **phenotype rules** based on what you learned about marker distributions

### Rule Design

- **Start broad, then refine** -- define a few major types first, then add subtypes
- **Use the histogram** to verify that positive/negative populations are separable for each marker
- **Rule order matters** -- more specific rules should come first (first match wins)
- **Not all cells need a rule** -- unmatched cells are classified as "Unknown"

### Gate Selection

- Use **auto-thresholding** as a starting point, then fine-tune
- The **Triangle method** works best for markers with a large negative population (skewed right)
- **GMM** works best for clearly bimodal distributions
- **Gamma** works best for strictly positive, right-skewed markers
- Always visually verify gate positions using the histogram

---

## Spatial Analysis

### When to Enable

Enable spatial analysis when:
- You want to understand **tissue architecture** (which cell types are neighbors?)
- You're looking for **spatially structured expression patterns** (Moran's I)
- You want to identify **co-localization** or **exclusion** of cell types

### Interpreting Neighborhood Enrichment

The z-score matrix shows:
- **Positive values (red):** clusters appear together more than expected (co-localization)
- **Negative values (blue):** clusters avoid each other (exclusion)
- **Near-zero (white):** random spatial relationship

### Interpreting Moran's I

- **High I with low p-value:** marker expression is spatially clustered (not random)
- **I near 0:** expression is randomly distributed
- **Negative I:** expression is spatially dispersed (checkerboard pattern)

### BANKSY vs. Post-Hoc Spatial Analysis

Two ways to incorporate spatial information:
1. **BANKSY** -- spatial information is used **during clustering** (influences which cells are grouped together)
2. **Spatial analysis checkbox** -- spatial statistics are computed **after clustering** (characterizes the spatial relationships between already-defined clusters)

Use BANKSY when spatial proximity should influence cluster membership (e.g., tissue domain identification). Use post-hoc spatial analysis when you want to characterize spatial patterns of expression-defined clusters.

---

## Multi-Image Projects

### When to Cluster Together

Cluster all project images together when:
- You want **consistent cluster assignments** across images (Cluster 3 = same cell type everywhere)
- Images are from the same experiment with the same staining panel
- You plan to compare cell type proportions between conditions

### Batch Correction

Enable **Harmony batch correction** when:
- Images have visible technical variation (different staining intensity, different imaging sessions)
- Per-image clustering gives different results for what should be the same cell types
- You observe "batch clusters" where cells group by image rather than biology

Do **not** use batch correction when:
- Differences between images are biologically meaningful (e.g., treated vs. control)
- All images were processed identically with minimal technical variation

### Memory Considerations

Multi-image clustering loads all detection data into memory. For large projects:
- Use MiniBatch KMeans instead of Leiden or KMeans
- Reduce the number of selected measurements
- Close other applications to free memory
- Increase QuPath's heap memory if needed (Edit > Preferences > Memory)

---

## Reproducibility

### Save Everything

1. **Save clustering configs** for every analysis you run
2. **Save phenotype rule sets** with descriptive names and version numbers
3. **Check the operation log** in `<project>/pyclustering/logs/` for exact parameters
4. **Export AnnData** for downstream analysis -- the .h5ad file captures the full state

### Document Your Choices

For publications, report:
- Clustering algorithm and all parameters
- Normalization method
- Number of cells and markers used
- Any manual gate adjustments
- Software versions (Extensions > PyClustering > Utilities > System Info)

### Version Your Rule Sets

Name rule sets with versions (e.g., "Immune Panel v1", "Immune Panel v2") rather than overwriting. This preserves a history of how your phenotyping evolved.

---

## Common Pitfalls

### 1. Using raw (un-normalized) data

**Problem:** Markers with higher absolute intensity dominate the clustering, regardless of biological importance.
**Solution:** Always normalize. Z-score is the safest default.

### 2. Including too many irrelevant measurements

**Problem:** Morphological measurements, DAPI, and low-signal channels add noise.
**Solution:** Select only biologically relevant mean intensity measurements.

### 3. Over-clustering

**Problem:** Too many clusters that are not biologically distinct.
**Symptoms:** Heatmap rows look similar; many clusters have the same marker pattern.
**Solution:** Decrease Leiden resolution, decrease KMeans k, or merge clusters after the fact.

### 4. Under-clustering

**Problem:** Biologically distinct populations are lumped together.
**Symptoms:** Known cell types are not separated; embedding shows sub-structure within clusters.
**Solution:** Increase Leiden resolution, increase KMeans k, or run sub-clustering on specific clusters.

### 5. Ignoring gate positions in phenotyping

**Problem:** Default gates may not match the actual positive/negative boundary for each marker.
**Solution:** Always check histograms. Use auto-thresholding as a starting point, then verify visually.

### 6. Rule order in phenotyping

**Problem:** Cells are classified as the wrong type because a less specific rule matched first.
**Solution:** Place more specific rules (more marker conditions) above more general rules.

### 7. Batch effects in multi-image analysis

**Problem:** Cells cluster by image source rather than biology.
**Solution:** Enable Harmony batch correction, or verify that technical variation is minimal before clustering without it.
