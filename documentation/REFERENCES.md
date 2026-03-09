# PyClustering -- Algorithm References and Citations

This document lists the original publications and key application papers for every algorithm, method, and tool used in the PyClustering extension. If you use PyClustering in your research, please cite the relevant methods below.

---

## Clustering Algorithms

### Leiden Algorithm

The Leiden algorithm is an improvement over the Louvain method for community detection in networks, guaranteeing well-connected communities.

**Original paper:**
> Traag VA, Waltman L, van Eck NJ. "From Louvain to Leiden: guaranteeing well-connected communities." *Scientific Reports* 9, 5233 (2019).
> https://doi.org/10.1038/s41598-019-41695-z

**Application in single-cell analysis:**
> Levine JH, Simonds EF, Bendall SC, et al. "Data-Driven Phenotypic Dissection of AML Reveals Progenitor-like Cells that Correlate with Prognosis." *Cell* 162(1), 184-197 (2015).
> https://doi.org/10.1016/j.cell.2015.05.047

**Used in:** Clustering Dialog (Leiden algorithm), BANKSY final clustering step

---

### KMeans Clustering

Classic centroid-based partitioning algorithm.

**Original paper:**
> Lloyd SP. "Least squares quantization in PCM." *IEEE Transactions on Information Theory* 28(2), 129-137 (1982).
> https://doi.org/10.1109/TIT.1982.1056489

**Improved initialization (k-means++):**
> Arthur D, Vassilvitskii S. "k-means++: The Advantages of Careful Seeding." *Proceedings of the 18th Annual ACM-SIAM Symposium on Discrete Algorithms (SODA)*, 1027-1035 (2007).
> http://ilpubs.stanford.edu:8090/778/

**Used in:** Clustering Dialog (KMeans, MiniBatch KMeans algorithms)

---

### HDBSCAN

Hierarchical Density-Based Spatial Clustering of Applications with Noise. Extends DBSCAN to find clusters of varying densities.

**Original paper:**
> Campello RJGB, Moulavi D, Sander J. "Density-Based Clustering Based on Hierarchical Density Estimates." *Advances in Knowledge Discovery and Data Mining (PAKDD)* 7819, 160-172 (2013).
> https://doi.org/10.1007/978-3-642-37456-2_14

**Software paper:**
> McInnes L, Healy J, Astels S. "hdbscan: Hierarchical density based clustering." *Journal of Open Source Software* 2(11), 205 (2017).
> https://doi.org/10.21105/joss.00205

**Used in:** Clustering Dialog (HDBSCAN algorithm)

---

### Gaussian Mixture Models (GMM)

Probabilistic clustering assuming data is generated from a mixture of Gaussian distributions. Fitted using the Expectation-Maximization (EM) algorithm.

**EM algorithm (foundational):**
> Dempster AP, Laird NM, Rubin DB. "Maximum Likelihood from Incomplete Data Via the EM Algorithm." *Journal of the Royal Statistical Society: Series B* 39(1), 1-38 (1977).
> https://doi.org/10.1111/j.2517-6161.1977.tb01600.x

**Reference (textbook):**
> McLachlan GJ, Peel D. *Finite Mixture Models.* Wiley Series in Probability and Statistics. John Wiley & Sons (2000).
> https://doi.org/10.1002/0471721182

**Used in:** Clustering Dialog (GMM algorithm), Auto-thresholding (2-component GMM for gate estimation)

---

### Agglomerative (Hierarchical) Clustering

Bottom-up hierarchical clustering with configurable linkage criteria.

**Reference:**
> Murtagh F, Contreras P. "Algorithms for hierarchical clustering: an overview." *WIREs Data Mining and Knowledge Discovery* 2(1), 86-97 (2012).
> https://doi.org/10.1002/widm.53

**Used in:** Clustering Dialog (Agglomerative algorithm)

---

### BANKSY -- Spatially-Aware Clustering

BANKSY (Building Aggregates with a Neighborhood Kernel and Spatial Yardstick) augments gene expression with spatial neighborhood information for spatially-informed clustering.

**Original paper:**
> Singhal V, Chou N, Lee J, et al. "BANKSY unifies cell typing and tissue domain segmentation for scalable spatial omics data analysis." *Nature Genetics* 56, 431-441 (2024).
> https://doi.org/10.1038/s41588-024-01664-3

**Used in:** Clustering Dialog (BANKSY algorithm)

---

## Dimensionality Reduction

### UMAP

Uniform Manifold Approximation and Projection for dimensionality reduction. Preserves both local and global structure.

**Original paper:**
> McInnes L, Healy J, Melville J. "UMAP: Uniform Manifold Approximation and Projection for Dimension Reduction." *Journal of Open Source Software* 3(29), 861 (2018).
> https://doi.org/10.21105/joss.00861

**Preprint:**
> McInnes L, Healy J, Melville J. "UMAP: Uniform Manifold Approximation and Projection for Dimension Reduction." *arXiv* 1802.03426 (2018).
> https://doi.org/10.48550/arXiv.1802.03426

**Application in single-cell biology:**
> Becht E, McInnes L, Healy J, et al. "Dimensionality reduction for visualizing single-cell data using UMAP." *Nature Biotechnology* 37, 38-44 (2019).
> https://doi.org/10.1038/nbt.4314

**Used in:** Clustering Dialog (UMAP embedding), Embedding Dialog

---

### t-SNE

t-distributed Stochastic Neighbor Embedding. Emphasizes local neighborhood preservation.

**Original paper:**
> van der Maaten L, Hinton G. "Visualizing Data using t-SNE." *Journal of Machine Learning Research* 9, 2579-2605 (2008).
> https://www.jmlr.org/papers/v9/vandermaaten08a.html

**Application in single-cell analysis (viSNE):**
> Amir ED, Davis KL, Tadmor MD, et al. "viSNE enables visualization of high dimensional single-cell data and reveals phenotypic heterogeneity of leukemia." *Nature Biotechnology* 31, 545-552 (2013).
> https://doi.org/10.1038/nbt.2594

**Used in:** Clustering Dialog (t-SNE embedding), Embedding Dialog

---

### PCA

Principal Component Analysis. Linear dimensionality reduction projecting onto top variance directions.

**Reference:**
> Jolliffe IT. *Principal Component Analysis.* 2nd edition. Springer Series in Statistics (2002).
> https://doi.org/10.1007/b98835

**Used in:** Clustering Dialog (PCA embedding), Embedding Dialog

---

## Spatial Analysis

### Squidpy

Spatial single-cell analysis framework providing neighborhood graphs, spatial statistics, and image analysis.

**Original paper:**
> Palla G, Spitzer H, Klein M, et al. "Squidpy: a scalable framework for spatial omics analysis." *Nature Methods* 19, 171-178 (2022).
> https://doi.org/10.1038/s41592-021-01358-2

**Used in:** Spatial analysis (neighborhood enrichment, Moran's I), spatial neighbor graphs

---

### Moran's I Spatial Autocorrelation

Measures the degree to which expression values are spatially clustered, dispersed, or random.

**Original paper:**
> Moran PAP. "Notes on Continuous Stochastic Phenomena." *Biometrika* 37(1-2), 17-23 (1950).
> https://doi.org/10.1093/biomet/37.1-2.17

**Used in:** Spatial analysis (per-marker spatial autocorrelation)

---

### Neighborhood Enrichment

Tests whether cell types (clusters) are enriched or depleted as spatial neighbors compared to random expectation.

**Reference (as implemented in squidpy):**
> Palla G, Spitzer H, Klein M, et al. "Squidpy: a scalable framework for spatial omics analysis." *Nature Methods* 19, 171-178 (2022).
> https://doi.org/10.1038/s41592-021-01358-2

**Used in:** Spatial analysis (cluster co-localization z-score matrix)

---

## Batch Correction

### Harmony

Fast, scalable batch correction algorithm that projects cells into a shared embedding while removing batch effects.

**Original paper:**
> Korsunsky I, Millard N, Fan J, et al. "Fast, sensitive and accurate integration of single-cell data with Harmony." *Nature Methods* 16, 1289-1296 (2019).
> https://doi.org/10.1038/s41592-019-0619-0

**Used in:** Multi-image project clustering (batch correction option)

---

## Trajectory Analysis

### PAGA

Partition-based Graph Abstraction. Constructs a connectivity graph between cell clusters to infer developmental trajectories.

**Original paper:**
> Wolf FA, Hamey FK, Plass M, et al. "PAGA: graph abstraction reconciles clustering with trajectory inference through a topology preserving map of single cells." *Genome Biology* 20, 59 (2019).
> https://doi.org/10.1186/s13059-019-1663-x

**Used in:** Post-analysis (PAGA connectivity graph between clusters)

---

## Phenotyping and Gating

### Triangle Threshold Method

Geometric auto-thresholding method that works well for distributions with a dominant peak (e.g., mostly-negative marker distributions).

**Original paper:**
> Zack GW, Rogers WE, Latt SA. "Automatic measurement of sister chromatid exchange frequency." *Journal of Histochemistry & Cytochemistry* 25(7), 741-753 (1977).
> https://doi.org/10.1177/25.7.70454

**Used in:** Auto-thresholding (Triangle method)

---

### GammaGateR (Gamma Distribution Gating)

Uses gamma mixture models for marker gating, better suited to the right-skewed, strictly positive distributions typical of cell marker intensities.

**Original paper:**
> Conroy JM, Neumann EK, et al. "GammaGateR: semi-automated marker gating for single-cell multiplexed imaging." *Bioinformatics* 40(6), btae356 (2024).
> https://doi.org/10.1093/bioinformatics/btae356

**Used in:** Auto-thresholding (Gamma method, inspired by GammaGateR approach)

---

### Marker Ranking -- Wilcoxon Rank-Sum Test

Non-parametric test for identifying differentially expressed markers between clusters.

**Reference:**
> Wilcoxon F. "Individual Comparisons by Ranking Methods." *Biometrics Bulletin* 1(6), 80-83 (1945).
> https://doi.org/10.2307/3001968

**Implementation reference (scanpy.tl.rank_genes_groups):**
> Wolf FA, Angerer P, Theis FJ. "SCANPY: large-scale single-cell gene expression data analysis." *Genome Biology* 19, 15 (2018).
> https://doi.org/10.1186/s13059-017-1382-0

**Used in:** Post-analysis (top differentially expressed markers per cluster)

---

## Core Frameworks

### Scanpy

Large-scale single-cell gene expression data analysis framework. Provides the computational backbone for PyClustering.

**Original paper:**
> Wolf FA, Angerer P, Theis FJ. "SCANPY: large-scale single-cell gene expression data analysis." *Genome Biology* 19, 15 (2018).
> https://doi.org/10.1186/s13059-017-1382-0

**Ecosystem paper:**
> Virshup I, Bredikhin D, Heumos L, et al. "The scverse project provides a computational ecosystem for single-cell omics data analysis." *Nature Biotechnology* 41, 604-606 (2023).
> https://doi.org/10.1038/s41587-023-01733-8

**Used in:** Core analysis pipeline (normalization, clustering, visualization, marker ranking)

---

### AnnData

Annotated data format for single-cell analysis. Standard interchange format for the single-cell ecosystem.

**Original paper:**
> Virshup I, Rybakov S, Theis FJ, Angerer P, Wolf FA. "anndata: Annotated data." *Journal of Open Source Software* 9(101), 4371 (2024).
> https://doi.org/10.21105/joss.04371

**Preprint:**
> Virshup I, Rybakov S, Theis FJ, Angerer P, Wolf FA. "anndata: Annotated data." *bioRxiv* (2021).
> https://doi.org/10.1101/2021.12.16.473007

**Used in:** AnnData (.h5ad) export

---

### Appose

Inter-process communication framework for polyglot scientific computing. Enables PyClustering's embedded Python environment.

**Software:**
> Appose: polyglot inter-process shared-memory communication. GitHub.
> https://github.com/apposed/appose

**Used in:** All Python operations (Java-Python communication via shared memory)

---

### QuPath

Open-source platform for bioimage analysis, with emphasis on digital pathology and whole slide images.

**Original paper:**
> Bankhead P, Loughrey MB, Fernandez JA, et al. "QuPath: Open source software for digital pathology image analysis." *Scientific Reports* 7, 16878 (2017).
> https://doi.org/10.1038/s41598-017-17204-5

---

## Key Application Papers (Multiplexed Imaging)

These papers demonstrate workflows similar to PyClustering's capabilities applied to multiplexed tissue imaging:

> Schurch CM, Bhate SS, Barlow GL, et al. "Coordinated Cellular Neighborhoods Orchestrate Antitumoral Immunity at the Colorectal Cancer Invasive Front." *Cell* 182(5), 1341-1359 (2020).
> https://doi.org/10.1016/j.cell.2020.07.005

> Jackson HW, Fischer JR, Zanotelli VRT, et al. "The single-cell pathology landscape of breast cancer." *Nature* 578, 615-620 (2020).
> https://doi.org/10.1038/s41586-019-1876-x

> Hickey JW, Becker WR, Nevins SA, et al. "Organization of the human intestine at single-cell resolution." *Nature* 619, 572-584 (2023).
> https://doi.org/10.1038/s41586-023-05915-x
