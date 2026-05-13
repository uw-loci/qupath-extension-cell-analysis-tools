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
10. [When to Use the LLM Cluster Explainer](#when-to-use-the-llm-cluster-explainer)
11. [Spatial Analysis](#spatial-analysis)
12. [When to Batch-Export Figures](#when-to-batch-export-figures)
13. [Multi-Image Projects](#multi-image-projects)
14. [Reproducibility](#reproducibility)
15. [Common Pitfalls](#common-pitfalls)

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

## When to Use the LLM Cluster Explainer

The LLM cluster explainer ([HOW_TO_GUIDE section 10](HOW_TO_GUIDE.md#10-explaining-clusters-with-an-llm-beta)) sits alongside Zero-Shot Phenotyping and Rule-Based Phenotyping as a third, complementary tool. It is the only one of the three that operates on **per-cluster statistics** rather than per-cell data.

### When to Trust the LLM Output

- **As a starting hypothesis** -- use the suggested phenotype to seed your own thinking, then verify the supporting markers against the cluster's heatmap row and marker ranking before adopting the label
- **For exploration on unfamiliar panels** -- the LLM is most useful when you are not yet fluent in the marker vocabulary. It turns "what does CD163 mean again?" into a one-paragraph rationale
- **For sanity-checking student work** -- a PI reviewing a clustering run can scan the LLM suggestions next to the marker rankings and quickly flag clusters that warrant a closer look

### When to Cross-Check or Defer to Other Methods

- **For publication-tier phenotype labels** -- prefer rule-based gating. Use the LLM suggestion to design your gating strategy; cite the rules, not the LLM, in your methods section
- **When the suggestion is plausible but the markers don't support it** -- the LLM will produce confident answers for incoherent clusters (when it doesn't refuse outright). If the cluster's heatmap row is uniform or the top markers are biologically unrelated, ignore the suggestion and re-cluster instead
- **When the LLM emits "(no suggestion)"** -- this is a deliberate refusal, not an error. The rationale column explains why; treat it as a useful flag that the cluster itself probably warrants a closer look before trusting any label
- **When BiomedCLIP and the LLM explainer disagree** -- this is informative. Often one of: (a) the cluster is morphologically distinct but marker-flat (BiomedCLIP right), (b) the cluster is marker-rich but morphologically mixed (LLM right), (c) the cluster is poorly defined and both are guessing

### Prompt-Shape Limitations

The LLM sees **marker statistics, not individual cells**. This means:

- It cannot tell you why a single outlier cell ended up in a cluster
- It cannot reason about cell morphology (size, shape, texture) -- that information is not in the prompt
- It cannot account for tissue type unless you tell it (v1 does not surface this)
- It cannot validate the clustering itself -- it accepts the cluster boundaries QP-CAT gave it. If the clustering is bad, the LLM's confident labels for those bad clusters will also be bad

If you need cell-level or morphology-level reasoning, run BiomedCLIP zero-shot phenotyping in parallel and compare.

### Audit-Log Discipline for Publications

Every LLM call is logged to `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` under the `=== LLM EXPLAIN ===` entry tag with provider, model, prompt-template version, prompt text, response text, and token counts. Both the Java side (`LlmAuditScrubber`) and the Python side (`scrub_secrets`) strip `Authorization:` headers and `sk-ant-*` keys from any payload before it reaches the log, so the audit trail is safe to share but does not contain the API key. For any paper that uses LLM-derived phenotype labels (even just as initial hypotheses), include in your methods section:

- **Provider and exact model string** (e.g. `claude-sonnet-4-5`, not just "Claude")
- **Prompt template version** as logged (currently `cluster_phenotype_v1`)
- **The fact that the call was made** -- LLM involvement, even at the exploratory stage, should be disclosed
- **Whether final phenotype labels were taken directly from the LLM output or re-derived from rule-based gating** -- these are very different reproducibility stories

Archive the audit log alongside your other reproducibility artifacts (clustering configs, rule sets, exported AnnData). The plain-text format is intentionally diff-friendly and version-control-friendly.

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

## Choosing Spatial Statistics

The Spatial Analysis surface gained four new statistics in v1: Ripley's K, Ripley's L, Geary's C, and co-occurrence (pairwise + one-vs-rest). Picking the right one for your question is the difference between a credible figure and a noisy table. For the mechanics of each test see [HOW_TO_GUIDE Section 17](HOW_TO_GUIDE.md#17-spatial-statistics-ripley-geary-co-occurrence).

### Which statistic answers which question

| Question | Statistic | Reason |
|---|---|---|
| Do cluster A cells tend to be neighbors of cluster B cells? | Neighborhood enrichment | One Z-score per pair; cheapest test; no radius dependence |
| At what spatial scale do clusters A and B co-localize? | Ripley's L (or K) | Radius-resolved; the curve tells you the *r* where the relationship is strongest |
| Is marker M spatially structured within a single image? | Geary's C (short range) or Moran's I (long range) | Geary's C is sensitive to local structure, Moran's I to global |
| How does cluster A's neighborhood composition change with distance? | Co-occurrence (pairwise) | Radius profile per pair |
| What does the rest of the tissue look like around cluster A specifically? | Co-occurrence (one-vs-rest) | Same as pairwise but with all-other-clusters collapsed |

A rule of thumb: **start with neighborhood enrichment** (cheap, headline answer), then **add Ripley's L** for the cluster pairs that flagged as interesting, then **add Geary's C / Moran's I** if you also have per-marker questions, then **add co-occurrence** only if you specifically need a radius profile.

### Graph constructor choice: kNN vs Radius vs Delaunay

| Constructor | Best for | Watch out for |
|---|---|---|
| **kNN (default)** | First-pass exploration; varying cell density. Robust because every cell has exactly k neighbors | Picks an arbitrary k; tune to your data. k = 15 is a sensible default. |
| **Radius** | A biologically motivated distance (synapse, niche). Interpretable parameter (microns / pixels) | Cells in sparse regions can be isolated. Increase r or switch to kNN if that happens. |
| **Delaunay** | Densely packed tissue (epithelia, tumor cores) where you want geometric neighbors only | Without max-edge pruning, cells across a tissue gap get artificially connected. Prune at ~2-3x the typical inter-cell distance. |

**Pick kNN unless you have a reason to switch.** Radius and Delaunay are precision tools; kNN is the workhorse.

### Permutation count tradeoffs at scale

Permutation-based significance testing is exact in principle but expensive: each permutation re-shuffles the labels and re-evaluates the statistic. v1's adaptive default scales the count to the cell count (1000 / 100 / 50). Override via `qpcat.spatial.permutations` if you need a fixed value (e.g. for a paper that needs every image analysed at the same count). The audit log records the value used.

A common pattern: **explore with the adaptive default; lock to 1000 for the final figure-grade run, on a subset of images if cell counts make 1000 prohibitive**.

### Same graph, multiple stats: a value, not a trick

The biggest user-facing change in this expansion is that one graph constructor backs every post-clustering statistic in a single run. This means:

- The graph parameters you pick are visible in the dialog and persisted to the audit log -- no hidden defaults
- Comparing Ripley K to Geary's C on the same data is apples-to-apples: same neighborhood definition for both
- Re-running with a different graph constructor gives you a clean a/b: did the conclusion change because of the graph or because of the data?

Resist the temptation to use different graph parameters for different stats in the same paper -- it makes the comparison much harder to interpret.

### When NOT to add the new stats

- **Small images (< 200 cells):** all of the new permutation-based tests will be underpowered. Stick with neighborhood enrichment.
- **Single-cluster scenarios:** Ripley K/L and co-occurrence are inherently multi-cluster.
- **Time-series or per-condition comparisons:** the v1 stats are within-image / within-project. Cross-condition spatial comparison needs a downstream tool; use the AnnData export.

---

## When to Batch-Export Figures

QP-CAT's batch figure export ([HOW_TO_GUIDE section 18](HOW_TO_GUIDE.md#18-exporting-figures)) writes every plot from one or more saved clustering results to a directory at one chosen format and DPI. It is intentionally a single dialog rather than a wizard because the option surface is small and the use cases are concentrated.

### Batch-export vs save-individual-plot

| Situation | Use |
|---|---|
| Writing up a paper / thesis -- need every figure for a clustering run | Batch export |
| Slide deck for a group meeting -- need 10-30 figures across 5-15 images | Batch export |
| Reviewer requests "the dotplot" for one image | Batch-export with a single plot checked is the cleanest path |
| Iterating on cluster labels and want to compare before / after | Batch-export both runs to separate directories, then diff |
| Headless / scripted / YAML-driven analysis pipeline | Scripting API (`FigureExportScripts.exportFigures`) -- see [SCRIPTING.md](SCRIPTING.md#figureexportscripts) |

A rule of thumb: **if you need more than two figures at a time, batch-export is faster.** If you need one figure, picking a single image + a single plot in the batch dialog is still a one-click operation.

### Picking a format

- **PNG at 300 DPI** is the journal-default raster choice in v1. Most journals will accept this for supplementary; many accept it for main figures too. The matplotlib-side PNGs are copied verbatim so the Python savefig DPI is preserved.
- **TIFF at 300 DPI** is the lossless raster choice. Slightly larger files than PNG, but some journals specifically request TIFF for figures.
- **Vector formats are v1.1.** When SVG / PDF / EPS land in v1.1, they will be the right choice for main figures destined for journals that require vector. Until then, render raster at the highest DPI your workflow tolerates (600+ for poster-grade; 300 for typical paper figures).

### Integrating with a paper / poster workflow

A workflow that holds up across multiple revision rounds:

1. **Lock the clustering parameters early.** Save the clustering config to the project (`<project>/qpcat/cluster_configs/`) so you can re-run with byte-identical parameters.
2. **Run all images with the locked config.** Make sure each image has the saved `ClusteringResult` available before batch-exporting.
3. **Export figures into a versioned subdirectory.** Name it after the revision: `figures/2026-05-13_revision1/`. Avoid overwriting an earlier directory unless you're sure you don't need the previous run; the dialog defaults to fail-fast on existing files for exactly this reason.
4. **Keep the audit log alongside the figures.** Copy `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` into the figures directory so future-you can answer "what parameters made these figures?" without digging.
5. **For the paper itself, switch to vector when v1.1 lands.** Until then, render the final figures at the highest DPI your journal accepts (typically 600 DPI for raster main figures) and use PNG (broader software support than TIFF in most editors).

For a **poster**, 300 DPI PNG at the rendered figure size is typically enough. If the figure will be enlarged 4-8x in the poster layout (typical), bump the export DPI to 600-1200 to keep edges crisp.

### Vector-formats teaser (v1.1)

v1.1 will add SVG, PDF, and EPS via a re-run of the Python matplotlib pipeline with a vector backend. The JavaFX-rendered plots (heatmap canvas, embedding scatter canvas) will render to SVG via a graphics2D bridge. Plan paper figures with vector in mind if you're not on a tight deadline; raster at 600 DPI is the right interim choice and is accepted by most journals for raster figures.

### Filenames and cross-platform sharing

The default filename pattern (`{image}_{plot}.{ext}`) is filesystem-safe across Windows / macOS / Linux. If you customise the pattern:

- Avoid characters that one OS allows but another doesn't (`:` and `\` are notable Windows traps).
- Avoid Windows reserved names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`) -- the exporter prepends `_` if a sanitised filename collides with one, but it's clearer to avoid these in the pattern itself.
- Use `{result_name}` if you have multiple saved clustering runs per image and want each in its own group; otherwise leave it out for shorter filenames.

If you're sharing the exported folder with collaborators on different OSes, the default pattern is the safest choice.

Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s batch-export action; QP-CAT adds the mandatory image-subset checklist and a Groovy scripting surface on top.

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
- **Hybrid input**: Select morphology measurements (Solidity, Area, Circularity) alongside tiles to give the model quantitative shape features that complement pixel data. This is especially useful when cell shape is discriminative but hard for the convnet to learn from pixels alone (e.g., elongated fibroblasts vs round lymphocytes).

### Class Weights

- Use **Auto-Balance** when class populations are significantly imbalanced (e.g., 10:1 ratio or worse). This computes inverse-frequency weights so rare classes contribute equally to the loss.
- Manually adjust per-class weight spinners when you want to prioritize accuracy on specific classes -- increase the weight for classes where misclassification is most costly.
- If all classes are roughly equally represented, the default weight of 1.0 for each class is fine and Auto-Balance is unnecessary.
