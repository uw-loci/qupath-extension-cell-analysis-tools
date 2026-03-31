# QP-CAT -- Best Practices

Recommendations for getting the best results from cell clustering and phenotyping in multiplexed imaging data.

---

## Table of Contents

1. [Before You Start](#before-you-start)
2. [Measurement Selection](#measurement-selection)
3. [Normalization](#normalization)
4. [Choosing a Clustering Algorithm](#choosing-a-clustering-algorithm)
5. [Spatial Feature Smoothing](#spatial-feature-smoothing)
6. [Cluster Evaluation](#cluster-evaluation)
7. [Foundation Model Features vs Channel Measurements](#foundation-model-features-vs-channel-measurements)
8. [Phenotyping Strategy](#phenotyping-strategy)
9. [Zero-Shot vs Rule-Based Phenotyping](#zero-shot-vs-rule-based-phenotyping)
10. [Spatial Analysis](#spatial-analysis)
11. [Multi-Image Projects](#multi-image-projects)
12. [Reproducibility](#reproducibility)
13. [Common Pitfalls](#common-pitfalls)

---

## Before You Start

### Cell Detection Quality Matters

Clustering quality depends heavily on the quality of upstream cell detection and segmentation. Before running QP-CAT:

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

## Spatial Feature Smoothing

### When to Use Spatial Smoothing

Enable spatial feature smoothing when you expect **nearby cells to have similar phenotypes or states** -- for example:

- **Tissue domains** where cells in the same region should cluster together
- **Tumor microenvironment** analysis where spatial context matters
- **Reducing noisy singleton assignments** where isolated cells get spurious cluster labels

Spatial smoothing works as a pre-processing step with **any** clustering algorithm. It builds a k-nearest neighbor graph from cell centroids and replaces each cell's features with a weighted average of its spatial neighbors' features (graph convolution with row-normalized adjacency).

### When NOT to Use Spatial Smoothing

- When biological neighbors are expected to be **different** (e.g., immune cells infiltrating a tumor -- you want to distinguish infiltrating cells from surrounding tumor cells)
- When spatial location is irrelevant to your question (e.g., comparing marker expression across conditions regardless of tissue architecture)
- When you are already using **BANKSY**, which has its own built-in spatial weighting mechanism

### Spatial Smoothing vs BANKSY

| | Spatial Smoothing | BANKSY |
|---|---|---|
| **Approach** | Pre-processing step (smooth, then cluster) | Integrated (spatial info in the clustering model) |
| **Works with** | Any algorithm (Leiden, KMeans, HDBSCAN, etc.) | Leiden only (built-in) |
| **Control** | Single parameter (k neighbors) | Multiple parameters (lambda, k_geom, resolution) |
| **Best for** | Quick spatial awareness on top of your preferred algorithm | Full spatial integration when tissue domains are the primary goal |

### Parameter Guidance

- **k = 10-20**: moderate smoothing, good starting point
- **k < 10**: minimal smoothing, preserves more cell-level variation
- **k > 30**: strong smoothing, best for broad tissue domain identification
- Very high k values risk over-smoothing and losing fine-grained cell type distinctions

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

## Foundation Model Features vs Channel Measurements

### When to Use Foundation Model Features

Foundation model embeddings capture morphological and textural information from the image tile surrounding each cell. They are useful when:

- Your image has **few marker channels** (e.g., H&E, single-stain IHC) and you want to cluster by cell morphology rather than marker intensity
- You want to discover **morphological subtypes** that are not evident from marker expression alone
- You want to combine morphological features with marker expression for **richer clustering input**
- You are working with **histopathology images** where tissue architecture matters more than individual marker values

### When to Use Channel Measurements

Traditional mean intensity measurements remain the best choice when:

- You have a **well-characterized multiplexed panel** with known biological markers
- Your analysis goal is specifically about **marker co-expression patterns** (e.g., which cells are CD3+CD8+ vs CD3+CD4+)
- You need results that are **directly interpretable** in terms of protein expression
- You want to define phenotypes using **gating rules** (rule-based phenotyping requires intensity measurements)

### Combining Both

Foundation model features (`FM_*` measurements) and channel measurements can be selected together in the clustering dialog. This can be powerful but keep in mind:

- Foundation model features are high-dimensional (768-2560 dimensions depending on the model) and will dominate over a smaller number of channel measurements
- Consider clustering on foundation model features **separately** first to understand what morphological groups exist, then correlate with marker expression
- PCA or UMAP on foundation model features alone can reveal tissue architecture patterns

### Model Selection Guidance

- **H-optimus-0**: Large pathology-specialized model (1536-dim). Good general-purpose choice for histopathology.
- **Virchow**: Pathology-specialized (2560-dim). Highest dimensionality; may capture more nuance but is more computationally intensive.
- **Hibou-B / Hibou-L**: Pathology-specialized, smaller (768/1024-dim). Good balance of quality and speed.
- **Midnight**: Pathology-specialized (768-dim). Open access, no token required.
- **DINOv2-Large**: General-purpose vision model (1024-dim). Not pathology-specific but very robust. Good baseline for comparison. No token required.

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

## Zero-Shot vs Rule-Based Phenotyping

QP-CAT offers two complementary approaches to cell phenotyping:

### When to Use Zero-Shot Phenotyping (BiomedCLIP)

- **Exploratory analysis** -- quickly get a sense of cell types present without defining rules
- **H&E or brightfield images** -- where you have no marker channels for gating, only morphology
- **Limited panel** -- when your marker panel does not cover all cell types you want to identify
- **Rapid prototyping** -- test phenotype hypotheses before investing time in rule design
- **Non-expert users** -- natural language prompts are more accessible than marker gating

### When to Use Rule-Based Phenotyping

- **Well-characterized multiplexed panels** -- when you know exactly which markers define each cell type
- **Reproducibility** -- rules are deterministic and produce identical results on the same data
- **Publication-ready analysis** -- reviewers expect defined gating strategies with explicit thresholds
- **Fine-grained control** -- rule order, per-marker thresholds, and positive/negative logic give precise control
- **Large panels (>10 markers)** -- rule-based gating scales well with many markers

### Combining Both Approaches

A recommended workflow:

1. Run **zero-shot phenotyping** first for a quick overview of cell populations
2. Use the zero-shot results to inform which **markers and thresholds** to use for rule-based gating
3. Define and refine **phenotype rules** for your final, reproducible analysis
4. Compare zero-shot and rule-based assignments to identify discrepancies worth investigating

### Zero-Shot Limitations

- Results depend on text prompt phrasing -- small changes can shift assignments
- The model was trained on biomedical literature and may not perfectly match every tissue type
- Confidence scores should be checked -- low-confidence assignments may be unreliable
- Not deterministic across model versions if BiomedCLIP is updated upstream

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
3. **Check the operation log** in `<project>/qpcat/logs/` for exact parameters
4. **Export AnnData** for downstream analysis -- the .h5ad file captures the full state

### Document Your Choices

For publications, report:
- Clustering algorithm and all parameters
- Normalization method
- Number of cells and markers used
- Any manual gate adjustments
- Software versions (Extensions > QP-CAT > Utilities > System Info)

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

---

## 7. [TEST] Autoencoder Cell Classifier

### When to Use

- **Measurement mode**: When marker expression patterns distinguish cell types. Fast, works on CPU. Start here.
- **Tile mode**: When morphology or spatial texture matters (e.g., differentiating activated vs resting T cells by size/shape). Slower, benefits from GPU.
- **vs Clustering**: Use the autoencoder when you have labeled examples and want to propagate labels. Use clustering when exploring unlabeled data.
- **vs Zero-shot**: Use the autoencoder when you have labeled training data. Use zero-shot when you have no labels but can describe phenotypes in text.

### Labeling Strategy

- Label 100-200 cells per class for reliable results
- Use locked annotations for region-based labeling (efficient for many cells)
- Use point annotations for precise per-cell labeling
- Include "Unclassified" as a class if you want the model to learn what "none of the above" looks like
- Label across multiple images for better generalization

### Training Tips

- Start with measurement mode (faster iteration)
- Use the default hyperparameters initially -- they follow VAE best practices
- Monitor validation accuracy in the log -- if it plateaus early, try more latent dimensions
- If accuracy is low, add more labeled cells before tuning hyperparameters
- Use the Evaluate button to check performance before applying destructively
- Save the model before applying so you can reload if results are unsatisfactory

### Tile Mode Tips

- Use downsample 2x-4x for large tile sizes (saves memory, preserves most features)
- The cell mask channel (default ON) helps the model focus on the target cell
- All image channels are included automatically
