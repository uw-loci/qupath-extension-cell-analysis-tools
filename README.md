# QuPath PyClustering Extension

Python-powered clustering and phenotyping for highly multiplexed imaging data in [QuPath](https://qupath.github.io/).

PyClustering embeds a full scientific Python environment (via [Appose](https://github.com/apposed/appose)) directly within QuPath -- no external servers, no conda environments to manage manually, no command-line tools. It provides unsupervised clustering, rule-based phenotyping, dimensionality reduction, spatial analysis, and interoperability export, all accessible through a GUI.

**Repository:** [uw-loci/qupath-extension-pyclustering](https://github.com/uw-loci/qupath-extension-pyclustering)

### Documentation

- **[How-To Guide](documentation/HOW_TO_GUIDE.md)** -- Step-by-step instructions for every workflow
- **[Best Practices](documentation/BEST_PRACTICES.md)** -- Recommendations for measurement selection, normalization, algorithm choice, and phenotyping strategy
- **[References](documentation/REFERENCES.md)** -- Original papers and DOI links for every algorithm and tool used in this extension

---

## Features

- **7 clustering algorithms** -- Leiden, KMeans, HDBSCAN, Agglomerative, MiniBatch KMeans, Gaussian Mixture, and BANKSY (spatially-aware)
- **Rule-based phenotyping** with per-marker gating and auto-threshold computation (Triangle, GMM, Gamma methods)
- **Dimensionality reduction** -- UMAP, PCA, t-SNE with interactive scatter visualization
- **Spatial analysis** -- neighborhood enrichment and Moran's I autocorrelation via squidpy
- **Batch correction** -- Harmony integration for multi-image project-wide clustering
- **Post-analysis** -- marker ranking (Wilcoxon), PAGA trajectory graphs, dotplots, violin plots
- **AnnData export** -- `.h5ad` files for interoperability with Scanpy, Seurat, cellxgene
- **Operation audit trail** -- persistent per-project log of every operation with full parameters
- **One-click environment setup** -- downloads and configures all Python dependencies automatically

---

## Requirements

- **QuPath** 0.6.0 or later
- **Java** 21+
- **Internet connection** for initial environment setup (~1.5-2.5 GB download)
- **Disk space** ~2.5 GB for the Python environment
- No GPU required -- all operations run on CPU

---

## Installation

### From GitHub Releases

1. Download the latest `.jar` from the [Releases](https://github.com/uw-loci/qupath-extension-pyclustering/releases) page
2. Drag the JAR onto the QuPath window, or place it in your QuPath extensions directory
3. Restart QuPath
4. Go to **Extensions > PyClustering > Setup Clustering Environment** and click "Setup"
5. Wait for the Python environment to build (first time only, ~5-10 minutes)

### Building from Source

```bash
git clone https://github.com/uw-loci/qupath-extension-pyclustering.git
cd qupath-extension-pyclustering
./gradlew build
```

The built JAR will be in `build/libs/`. Copy it to your QuPath extensions directory.

---

## Quick Start

1. Open an image in QuPath with cell detections (run cell detection first if needed)
2. **Extensions > PyClustering > Run Clustering...**
3. Select measurements (defaults to "Mean" intensity channels)
4. Choose algorithm (Leiden recommended) and click **Run Clustering**
5. Results are applied directly to detections as classifications (Cluster 0, Cluster 1, ...)
6. View interactive heatmaps, scatter plots, and marker rankings in the results dialog

For even faster exploration, use **Quick Cluster** submenu items which run with sensible defaults.

---

## Clustering

### Supported Algorithms

| Algorithm | Type | Auto-detects k? | Notes |
|-----------|------|:---:|-------|
| **Leiden** | Graph-based | Yes | Recommended default. Resolution parameter controls granularity. |
| **KMeans** | Centroid-based | No | Requires specifying number of clusters. |
| **HDBSCAN** | Density-based | Yes | Also identifies noise/outlier cells. |
| **Agglomerative** | Hierarchical | No | Supports ward, complete, average, and single linkage. |
| **MiniBatch KMeans** | Centroid-based | No | Scalable variant of KMeans for very large datasets. |
| **GMM** | Probabilistic | No | Gaussian mixture model with soft cluster assignment. |
| **BANKSY** | Spatially-aware | Yes | Uses both expression and spatial coordinates. See [Spatial Clustering](#banksy-spatial-clustering). |

### Normalization Methods

| Method | Description | When to use |
|--------|-------------|-------------|
| **Z-score** | Zero mean, unit variance per marker | Recommended default for most analyses |
| **Min-Max** | Scale each marker to [0, 1] | When relative intensities matter |
| **Percentile** | Robust min-max using 1st/99th percentiles | When outliers are present |
| **None** | Raw measurement values | Pre-normalized data |

### Dimensionality Reduction

UMAP, PCA, and t-SNE embeddings are computed alongside clustering and added as measurements (e.g., `UMAP1`, `UMAP2`) to each detection. These can be used for visualization in QuPath or downstream tools.

The **Compute Embedding Only** dialog allows computing embeddings without clustering, preserving existing classifications.

### Multi-Image Project Clustering

Select "All project images" scope in the clustering dialog to cluster detections across your entire project simultaneously. This ensures globally consistent cluster assignments. Optionally enable **Harmony batch correction** to remove per-image technical variation before clustering.

---

## Phenotyping

Rule-based cell type classification using marker gating thresholds.

### Workflow

1. **Extensions > PyClustering > Run Phenotyping...**
2. Select markers to use as gating channels
3. Set per-marker gate thresholds (manually or via auto-thresholding)
4. Define phenotype rules: each rule maps a cell type name to marker conditions (positive/negative)
5. Rules are evaluated in order (first match wins); unmatched cells are labeled "Unknown"

### Auto-Thresholding

Click **Compute Thresholds** to calculate suggested gate values for each marker using three methods:

| Method | Algorithm | Best for |
|--------|-----------|----------|
| **Triangle** | Geometric method from histogram shape | Skewed distributions with dominant negative peak |
| **GMM** | 2-component Gaussian mixture model | Bimodal distributions |
| **Gamma** | Gamma distribution fit (GammaGateR-inspired) | Right-skewed, strictly positive marker data |

Select a marker column header to view its histogram with an interactive draggable threshold line.

### Saving and Loading Rule Sets

Phenotype rules, gates, and marker selections can be saved to and loaded from the QuPath project directory (`<project>/pyclustering/phenotype_rules/`). This allows reuse across sessions and sharing between team members.

---

## Spatial Analysis

When enabled in the clustering dialog, PyClustering computes spatial statistics using cell centroid coordinates:

- **Neighborhood enrichment** -- Z-score matrix showing which clusters tend to co-localize (or avoid each other) in tissue space
- **Moran's I autocorrelation** -- Per-marker spatial autocorrelation, identifying markers with spatially structured expression patterns

Results are displayed in the results dialog and available as static plot outputs.

### BANKSY Spatial Clustering

[BANKSY](https://github.com/prabhakarlab/Banksy_py) integrates spatial neighborhood information directly into the clustering algorithm. It augments each cell's expression profile with a weighted average of its spatial neighbors' expression, then clusters on the combined representation.

Parameters:
- **lambda** (0-1): Weight of spatial vs. expression information (0.2 is a good starting point)
- **k_geom**: Number of spatial nearest neighbors
- **resolution**: Leiden resolution for the final clustering step

---

## Post-Analysis Outputs

After clustering, PyClustering can generate:

| Output | Description |
|--------|-------------|
| **Interactive heatmap** | Cluster-by-marker mean expression with click-to-select |
| **Interactive scatter** | UMAP/PCA/t-SNE plot colored by cluster, zoomable and pannable |
| **Marker rankings** | Top differentially expressed markers per cluster (Wilcoxon rank-sum test) |
| **PAGA graph** | Cluster connectivity/trajectory graph |
| **Dotplot** | Fraction expressing + mean expression per cluster per marker |
| **Stacked violin** | Expression distributions per cluster |
| **Spatial scatter** | Cell positions colored by cluster (when spatial analysis enabled) |

---

## Data Export

### AnnData (.h5ad)

Export your data to the AnnData format for use with external tools:

- **Extensions > PyClustering > Export AnnData (.h5ad)...**

The exported file includes:
- All selected measurements as the expression matrix
- Cluster assignments (if present)
- Phenotype labels (if present)
- Embedding coordinates (UMAP/PCA/t-SNE if computed)
- Spatial coordinates (cell centroids)

Compatible with Scanpy, Seurat (via SeuratDisk), cellxgene, and other single-cell analysis tools.

---

## Cluster Management

**Extensions > PyClustering > Manage Clusters...** opens a dialog for post-hoc cluster organization:

- **Rename** -- Change a cluster's classification name (e.g., "Cluster 3" -> "CD8+ T Cells")
- **Merge** -- Combine two or more clusters into one with a user-specified name

Changes are applied directly to detection objects and reflected immediately in QuPath's viewer.

---

## Configuration Persistence

### Clustering Configs

Save and load clustering parameter sets (algorithm, parameters, measurement selection, normalization, embedding settings) within a QuPath project. Stored in `<project>/pyclustering/cluster_configs/`.

### Operation Audit Trail

Every operation (clustering, phenotyping, embedding, export, threshold computation) is logged with full parameters, timestamps, cell counts, and results to per-day log files at:

```
<project>/pyclustering/logs/pyclustering_YYYY-MM-DD.log
```

This provides a reproducibility trail -- you can always see exactly what parameters were used for any analysis. Log files are human-readable text.

---

## Python Console

**Extensions > PyClustering > Utilities > Python Console** opens a window showing real-time Python stderr/debug output from the embedded Python environment. Useful for:

- Monitoring long-running operations
- Debugging Python-side errors
- Viewing detailed progress messages

The console includes a **Save Log...** button to export its contents to a text file.

---

## Python Environment

PyClustering manages its own isolated Python environment via [Appose](https://github.com/apposed/appose) and [pixi](https://pixi.sh/). The environment is stored at:

```
~/.local/share/appose/qupath-pyclustering/    (Linux/macOS)
%LOCALAPPDATA%\appose\qupath-pyclustering\    (Windows)
```

### Key Python Packages

| Package | Purpose |
|---------|---------|
| scanpy | Core single-cell analysis framework |
| scikit-learn | KMeans, HDBSCAN, GMM, Agglomerative clustering |
| leidenalg | Leiden community detection |
| umap-learn | UMAP dimensionality reduction |
| squidpy | Spatial analysis (neighborhood enrichment, Moran's I) |
| harmonypy | Batch correction for multi-sample integration |
| pybanksy | Spatially-aware BANKSY clustering |
| anndata | AnnData format for interoperability |
| scikit-image | Auto-thresholding (Triangle method) |
| scipy | Gamma distribution fitting for auto-thresholds |
| matplotlib | Plot generation |

### Rebuilding the Environment

If the environment becomes corrupted or you need to update packages:

1. **Extensions > PyClustering > Utilities > Rebuild Clustering Environment**
2. Confirm the rebuild (this deletes the existing environment)
3. Click "Setup" in the dialog that appears

---

## Menu Reference

All items are under **Extensions > PyClustering**:

| Menu Item | Description | Requirements |
|-----------|-------------|--------------|
| Setup Clustering Environment | One-time Python environment installation | Internet connection |
| Run Clustering... | Full clustering dialog with all options | Image + detections |
| Compute Embedding Only... | UMAP/PCA/t-SNE without clustering | Image + detections |
| Run Phenotyping... | Rule-based cell type classification | Image + detections + project |
| Quick Cluster > Quick Leiden | One-click Leiden clustering with defaults | Image + detections |
| Quick Cluster > Quick KMeans | One-click KMeans (k=10) | Image + detections |
| Quick Cluster > Quick HDBSCAN | One-click HDBSCAN with defaults | Image + detections |
| Manage Clusters... | Rename and merge cluster classifications | Image |
| Export AnnData (.h5ad)... | Export data for external analysis tools | Image + detections |
| Utilities > Python Console | View Python debug output | None |
| Utilities > System Info... | Show version and environment details | Environment ready |
| Utilities > Rebuild Environment | Delete and re-download Python environment | None |

---

## Troubleshooting

### Environment setup fails

- Check your internet connection -- the initial download is ~1.5-2.5 GB
- Check available disk space (~2.5 GB required)
- Try **Rebuild Environment** to start fresh
- Check the Python Console for detailed error messages

### "No detections found"

PyClustering operates on detection objects (cells). Run cell detection first:
- **Analyze > Cell detection** in QuPath, or
- Use StarDist, Cellpose, or another detection method

### Clustering produces unexpected results

- Try different normalization methods -- Z-score is recommended for most data
- Adjust algorithm parameters (e.g., Leiden resolution, HDBSCAN min_cluster_size)
- Check the marker selection -- "Mean" measurements are usually best for clustering
- View the interactive heatmap to assess cluster quality

### Memory issues with large datasets

- Use MiniBatch KMeans for datasets with >100,000 cells
- Reduce the number of selected measurements
- Consider clustering a subset (select detections within an annotation)

---

## Building from Source

```bash
git clone https://github.com/uw-loci/qupath-extension-pyclustering.git
cd qupath-extension-pyclustering
./gradlew build
```

The extension JAR is output to `build/libs/`.

To compile only (no tests):
```bash
./gradlew compileJava
```

---

## Project Structure

```
src/main/java/qupath/ext/pyclustering/
  SetupPyClustering.java          Extension entry point and menu registration
  controller/
    ClusteringWorkflow.java       Orchestrates all analysis operations
  model/
    ClusteringConfig.java         Configuration with enums for algorithms/normalization/embedding
    ClusteringResult.java         Result container with labels, stats, plots, spatial data
    PhenotypeRuleSet.java         Serializable phenotype rule definitions
  service/
    ApposeClusteringService.java  Appose Python IPC singleton
    MeasurementExtractor.java     Extracts measurements from QuPath detections
    ResultApplier.java            Applies labels/embeddings back to detections
    OperationLogger.java          Per-project operation audit trail
    ClusteringConfigManager.java  Config save/load to project directory
    PhenotypeRuleSetManager.java  Rule set save/load to project directory
    ChannelValidator.java         Cross-image measurement consistency checking
  ui/
    ClusteringDialog.java         Main clustering configuration dialog
    PhenotypingDialog.java        Phenotyping rules and gating dialog
    EmbeddingDialog.java          Embedding-only computation dialog
    HistogramPanel.java           Interactive histogram with draggable threshold
    ClusterHeatmapPanel.java      Interactive cluster-by-marker heatmap
    EmbeddingScatterPanel.java    Interactive 2D embedding scatter plot
    ClusterManagementDialog.java  Rename/merge cluster classifications
    PythonConsoleWindow.java      Real-time Python stderr viewer
    SetupEnvironmentDialog.java   Environment download progress dialog

src/main/resources/qupath/ext/pyclustering/
  pixi.toml                       Python environment specification
  scripts/
    init_services.py              Python worker initialization
    run_clustering.py             Clustering pipeline
    run_phenotyping.py            Phenotyping pipeline
    compute_thresholds.py         Auto-threshold computation
    export_anndata.py             AnnData export
    system_info.py                Python environment info collection
```

---

## Acknowledgments

Developed at the [Laboratory for Optical and Computational Instrumentation (LOCI)](https://eliceirilab.org/) at the University of Wisconsin-Madison.

**Key dependencies:**
- [QuPath](https://qupath.github.io/) -- Open-source bioimage analysis
- [Appose](https://github.com/apposed/appose) -- Inter-process communication for polyglot scientific computing
- [Scanpy](https://scanpy.readthedocs.io/) -- Single-cell analysis in Python
- [squidpy](https://squidpy.readthedocs.io/) -- Spatial single-cell analysis
- [BANKSY](https://github.com/prabhakarlab/Banksy_py) -- Spatially-aware clustering
- [Harmony](https://github.com/immunogenomics/harmony) -- Batch correction

---

## License

[Apache License 2.0](LICENSE)

## AI-Assisted Development

This project was developed with assistance from [Claude](https://claude.ai) (Anthropic). Claude was used as a development tool for code generation, architecture design, debugging, and documentation throughout the project.
