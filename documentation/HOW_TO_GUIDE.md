# PyClustering -- How-To Guide

Step-by-step instructions for every workflow in the PyClustering extension.

**Prerequisites for all workflows:**
- QuPath 0.6.0+ with PyClustering installed
- Python environment set up (Extensions > PyClustering > Setup Clustering Environment)
- An image open in QuPath with cell detections present

---

## Table of Contents

1. [Setting Up the Environment](#1-setting-up-the-environment)
2. [Running Clustering](#2-running-clustering)
3. [Quick Clustering](#3-quick-clustering)
4. [Multi-Image Project Clustering](#4-multi-image-project-clustering)
5. [Computing Embeddings Only](#5-computing-embeddings-only)
6. [Rule-Based Phenotyping](#6-rule-based-phenotyping)
7. [Using Auto-Thresholding](#7-using-auto-thresholding)
8. [Managing Clusters (Rename/Merge)](#8-managing-clusters-renamemerge)
9. [Exporting AnnData](#9-exporting-anndata)
10. [Saving and Loading Configurations](#10-saving-and-loading-configurations)
11. [Viewing the Python Console](#11-viewing-the-python-console)
12. [Reviewing the Operation Audit Trail](#12-reviewing-the-operation-audit-trail)

---

## 1. Setting Up the Environment

**First-time only.** This downloads Python and all scientific packages (~1.5-2.5 GB).

1. Open QuPath
2. Go to **Extensions > PyClustering > Setup Clustering Environment**
3. Click **Setup Environment**
4. Wait for the download and build to complete (5-10 minutes depending on internet speed)
5. When "Environment setup complete!" appears, close the dialog
6. The rest of the PyClustering menu items now become visible

**Troubleshooting:** If setup fails, check your internet connection and disk space (~2.5 GB needed). Use **Utilities > Rebuild Clustering Environment** to start fresh.

---

## 2. Running Clustering

Full clustering with all configuration options.

### Step-by-step:

1. Open an image with cell detections
2. **Extensions > PyClustering > Run Clustering...**
3. **Scope** -- Choose "Current image" or "All project images"
4. **Measurements** -- Select the markers to cluster on
   - Click **Select 'Mean' only** for a good default (mean intensity per marker)
   - Or manually select specific measurements
5. **Normalization** -- Choose a scaling method
   - **Z-score** is recommended for most analyses
   - See [Best Practices](BEST_PRACTICES.md#normalization) for guidance
6. **Embedding** -- Choose dimensionality reduction
   - **UMAP** is recommended (preserves both local and global structure)
   - Adjust n_neighbors (2-200, default 15) and min_dist (0.0-1.0, default 0.1) if needed
7. **Algorithm** -- Choose a clustering method
   - **Leiden** is recommended for most use cases (auto-detects number of clusters)
   - Set algorithm-specific parameters (see [Parameter Reference](#algorithm-parameters) below)
8. **Analysis options** -- Check boxes as needed:
   - "Generate analysis plots" -- produces static PNGs (marker ranking, PAGA, dotplot)
   - "Spatial analysis" -- computes neighborhood enrichment and Moran's I
   - "Batch correction" -- applies Harmony (only for multi-image scope)
9. Click **Run Clustering**
10. View results in the results dialog (heatmap, scatter plot, marker rankings, plots)

### What happens to your data:

- Each detection gets a **PathClass** classification like "Cluster 0", "Cluster 1", etc.
- If embedding was computed, measurements **UMAP1/UMAP2** (or PCA1/PCA2, tSNE1/tSNE2) are added to each detection
- The QuPath viewer updates to show cluster colors on cells

---

## 3. Quick Clustering

One-click clustering with sensible defaults. Good for initial exploration.

1. Open an image with cell detections
2. **Extensions > PyClustering > Quick Cluster** and pick one:
   - **Quick Leiden (auto)** -- Leiden with n_neighbors=50, resolution=1.0, Z-score normalization, UMAP embedding
   - **Quick KMeans (k=10)** -- KMeans with 10 clusters
   - **Quick HDBSCAN (auto)** -- HDBSCAN with min_cluster_size=15
3. Wait for the notification that clustering is complete
4. Cell classifications are updated immediately

Quick Cluster automatically selects all "Mean" measurements and uses Z-score normalization with UMAP embedding.

---

## 4. Multi-Image Project Clustering

Cluster all images in a project together for globally consistent assignments.

1. Open a QuPath project with multiple images (each must have cell detections)
2. **Extensions > PyClustering > Run Clustering...**
3. Select **All project images** scope
4. Configure measurements, normalization, algorithm as usual
5. Optionally enable **Batch correction (Harmony)** to account for per-image technical variation
6. Click **Run Clustering**

All detections across all images are combined into a single dataset, clustered together, and results are saved back to each image. This ensures "Cluster 3" in Image A is the same as "Cluster 3" in Image B.

**Note:** This loads all detection data into memory. For very large projects (>500,000 total cells), consider using MiniBatch KMeans.

---

## 5. Computing Embeddings Only

Add UMAP/PCA/t-SNE coordinates to detections without changing existing classifications.

1. **Extensions > PyClustering > Compute Embedding Only...**
2. Select measurements and normalization
3. Choose embedding method (UMAP recommended)
4. Set parameters:
   - **n_neighbors** (2-200, default 15): larger = more global structure
   - **min_dist** (0.0-1.0, default 0.1): smaller = tighter clusters in the plot
5. Click **Compute Embedding**
6. Measurements UMAP1/UMAP2 (or PCA1/PCA2, tSNE1/tSNE2) are added to each detection

Existing cluster or phenotype classifications are preserved.

---

## 6. Rule-Based Phenotyping

Classify cells into biological types based on marker expression thresholds.

### Step-by-step:

1. **Extensions > PyClustering > Run Phenotyping...**
2. **Select markers** from the measurement list
   - These should be biologically meaningful markers (e.g., CD3, CD8, CD20, PanCK)
   - Use **Select 'Mean' only** then deselect irrelevant markers
3. **Set normalization** -- determines how marker values are scaled before gating
   - Min-Max or Percentile recommended for gating (values in [0,1] range)
   - The "Default gate" spinner sets the initial gate for all markers
4. **Set per-marker gates** -- each marker column header has a spinner
   - Values represent the positive/negative threshold for that marker
   - You can drag the red threshold line on the histogram (see [Auto-Thresholding](#7-using-auto-thresholding))
5. **Define rules** -- each row is a phenotype:
   - **Cell Type**: name for this phenotype (e.g., "CD8+ T Cell")
   - **Marker columns**: set to "pos" or "neg" for each marker that defines this type
   - Leave markers blank if they are irrelevant for that type
   - Example: CD8+ T Cell = CD3: pos, CD8: pos, CD20: neg
6. **Rule order matters** -- rules are evaluated top-to-bottom, first match wins
   - Use the up/down arrows to reorder
   - Place more specific rules above more general ones
7. Click **Run Phenotyping**
8. Results dialog shows phenotype counts and distributions

### Example rule set for immune panel:

| Cell Type | CD3 | CD8 | CD4 | CD20 | PanCK |
|-----------|-----|-----|-----|------|-------|
| CD8+ T Cell | pos | pos | | neg | neg |
| CD4+ T Cell | pos | neg | pos | neg | neg |
| B Cell | neg | | | pos | neg |
| Tumor | neg | | | neg | pos |

---

## 7. Using Auto-Thresholding

Automatically compute marker gate thresholds instead of setting them manually.

1. In the Phenotyping dialog, select your markers
2. Expand the **Histogram & Auto-Thresholding** section
3. Click **Compute Thresholds**
4. Click any marker column header to view its histogram
5. The histogram shows:
   - Blue bars (below threshold) and red bars (above threshold)
   - A red dashed line at the current threshold
   - Statistics: "Pos: X (Y%) | Neg: Z (W%)"
6. Change the **Method** dropdown to apply an auto-threshold:
   - **Triangle** -- geometric method, good for skewed distributions
   - **GMM (Gaussian)** -- 2-component mixture model, good for bimodal data
   - **Gamma** -- gamma distribution fit, good for strictly positive markers
7. You can drag the red threshold line with the mouse for fine-tuning
8. Click **Apply to All Markers** to set all gates using the selected method

---

## 8. Managing Clusters (Rename/Merge)

Organize cluster assignments after clustering.

1. **Extensions > PyClustering > Manage Clusters...**
2. The dialog shows all classifications with cell counts
3. **To rename:** Select one cluster, click **Rename...**, enter the new name
   - Example: "Cluster 3" -> "CD8+ T Cells"
4. **To merge:** Select two or more clusters (Ctrl/Cmd+click), click **Merge Selected**
   - Enter a name for the merged cluster
   - All selected clusters are reassigned to the new name
5. Click **Refresh** if you make changes outside the dialog

Changes are applied immediately to detection objects.

---

## 9. Exporting AnnData

Export data for use with external single-cell tools (Scanpy, Seurat, cellxgene).

1. **Extensions > PyClustering > Export AnnData (.h5ad)...**
2. Choose a save location and filename
3. The export includes:
   - Expression matrix (all measurements)
   - Cluster labels (if cells are classified as "Cluster N")
   - Phenotype labels (if cells have other classifications)
   - Embedding coordinates (UMAP1/UMAP2, etc., if present)
   - Spatial coordinates (cell centroids)
4. Open the file in Python:

```python
import scanpy as sc
adata = sc.read_h5ad("export.h5ad")
print(adata)
```

---

## 10. Saving and Loading Configurations

### Clustering Configs

1. In the Clustering dialog, configure all parameters
2. Click **Save Config...**
3. Enter a name (e.g., "my-panel-leiden")
4. To restore: click **Load Config...** and select the saved configuration

Configs are stored in `<project>/pyclustering/cluster_configs/`.

### Phenotype Rule Sets

1. In the Phenotyping dialog, define your markers, gates, and rules
2. Click **Save Rules...**
3. Enter a name (e.g., "Immune Panel v1")
4. To restore: click **Load Rules...** and select the saved rule set

Rule sets are stored in `<project>/pyclustering/phenotype_rules/`.

---

## 11. Viewing the Python Console

Monitor Python-side output in real time.

1. **Extensions > PyClustering > Utilities > Python Console**
2. The console shows timestamped debug messages from the Python environment
3. **Auto-scroll** toggle: keeps the view at the latest output
4. **Clear**: empties the console
5. **Save Log...**: exports the console contents to a text file

Useful for diagnosing errors, monitoring long operations, and seeing detailed Python output.

---

## 12. Reviewing the Operation Audit Trail

Every PyClustering operation is logged to a persistent file in your project.

**Location:** `<project>/pyclustering/logs/pyclustering_YYYY-MM-DD.log`

Each log entry records:
- Timestamp
- Operation type (CLUSTERING, PHENOTYPING, EMBEDDING, etc.)
- All input parameters (algorithm, normalization, marker count, cell count)
- Result summary (clusters found, phenotypes assigned, etc.)
- Duration

**Example entry:**
```
=== CLUSTERING === 2026-03-09 14:23:05
  Algorithm: Leiden (graph-based)
  Algorithm params: {n_neighbors=50, resolution=1.0}
  Normalization: zscore
  Embedding: umap
  Measurements: 15 markers
  Input: 12847 cells
  Result: Clustering complete: 8 clusters found for 12847 cells.
  Duration: 4.2s
```

Log files are plain text and can be opened in any text editor. A new file is created each day automatically.

---

## Algorithm Parameters

### Leiden

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| n_neighbors | 2-500 | 50 | Nearest neighbors for the k-NN graph. Higher = broader, fewer clusters. |
| resolution | 0.01-10.0 | 1.0 | Controls cluster granularity. Higher = more, smaller clusters. |

### KMeans / MiniBatch KMeans

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| n_clusters | 2-200 | 10 | Number of clusters to create. Must be specified. |

### HDBSCAN

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| min_cluster_size | 2-500 | 15 | Minimum cells to form a cluster. Smaller = more clusters. Unassigned cells are labeled "Unclassified". |

### Agglomerative

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| n_clusters | 2-200 | 10 | Number of clusters to create. |
| linkage | ward/complete/average/single | ward | How distances between clusters are computed. Ward minimizes within-cluster variance (most common). |

### GMM

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| n_components | 2-200 | 10 | Number of Gaussian components (clusters). |

### BANKSY

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| lambda | 0.0-1.0 | 0.2 | Weight of spatial vs. expression info. 0 = expression only, 1 = spatial only. |
| k_geom | 2-200 | 15 | Number of spatial nearest neighbors. |
| resolution | 0.01-10.0 | 0.7 | Leiden resolution for final clustering step. |

### UMAP

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| n_neighbors | 2-200 | 15 | Local neighborhood size. Smaller = more local detail, larger = more global structure. |
| min_dist | 0.0-1.0 | 0.1 | Minimum distance between embedded points. Smaller = tighter clusters. |
