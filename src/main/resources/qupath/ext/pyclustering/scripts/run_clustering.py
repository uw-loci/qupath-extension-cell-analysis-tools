"""
Main clustering script for PyClustering Appose tasks.

Inputs (injected by Appose 0.10.0 -- accessed as variables, NOT task.inputs):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  algorithm: str ("leiden", "kmeans", "hdbscan", "agglomerative", "minibatchkmeans", "gmm")
  algorithm_params: dict (algorithm-specific parameters)
  normalization: str ("zscore", "minmax", "percentile", "none")
  embedding_method: str ("umap", "pca", "tsne", "none")
  embedding_params: dict (method-specific parameters)

Outputs (via task.outputs):
  cluster_labels: NDArray (N_cells,) int32
  n_clusters: int
  embedding: NDArray (N_cells x 2) float64 (if embedding_method != "none")
  cluster_stats: NDArray (n_clusters x N_markers) float64 -- per-cluster marker means
"""
import sys
import logging

logger = logging.getLogger("pyclustering.clustering")

import numpy as np
import pandas as pd
from appose import NDArray as PyNDArray

# 1. Reshape input NDArray to numpy and release shared memory
data = measurements.ndarray().copy()
n_cells, n_markers = data.shape
logger.info("Received %d cells x %d markers", n_cells, n_markers)

df = pd.DataFrame(data, columns=marker_names)

# 2. Normalize
task.update("Normalizing measurements...", current=0, maximum=4)

if normalization == "zscore":
    std = df.std()
    std[std == 0] = 1  # avoid division by zero for constant columns
    df_norm = (df - df.mean()) / std
elif normalization == "minmax":
    dmin = df.min()
    dmax = df.max()
    drange = dmax - dmin
    drange[drange == 0] = 1
    df_norm = (df - dmin) / drange
elif normalization == "percentile":
    p1 = df.quantile(0.01)
    p99 = df.quantile(0.99)
    drange = p99 - p1
    drange[drange == 0] = 1
    df_norm = df.clip(lower=p1, upper=p99, axis=1)
    df_norm = (df_norm - p1) / drange
else:
    df_norm = df.copy()

logger.info("Normalization: %s", normalization)

# 3. Dimensionality reduction
task.update("Computing embedding...", current=1, maximum=4)

embedding_result = None
if embedding_method == "umap":
    import umap
    n_neighbors = embedding_params.get("n_neighbors", 15)
    min_dist = embedding_params.get("min_dist", 0.1)
    metric = embedding_params.get("metric", "euclidean")
    logger.info("UMAP: n_neighbors=%d, min_dist=%.2f, metric=%s",
                n_neighbors, min_dist, metric)
    reducer = umap.UMAP(
        n_neighbors=n_neighbors,
        min_dist=min_dist,
        metric=metric,
        n_components=2,
        random_state=42
    )
    embedding_result = reducer.fit_transform(df_norm.values)

elif embedding_method == "pca":
    from sklearn.decomposition import PCA
    n_components = embedding_params.get("n_components", 2)
    pca = PCA(n_components=n_components, random_state=42)
    embedding_result = pca.fit_transform(df_norm.values)
    logger.info("PCA: explained variance = %s",
                [round(v, 4) for v in pca.explained_variance_ratio_])

elif embedding_method == "tsne":
    from sklearn.manifold import TSNE
    perplexity = embedding_params.get("perplexity", 30.0)
    tsne = TSNE(n_components=2, perplexity=perplexity, random_state=42)
    embedding_result = tsne.fit_transform(df_norm.values)
    logger.info("t-SNE: perplexity=%.1f", perplexity)

elif embedding_method != "none":
    logger.warning("Unknown embedding method: %s, skipping", embedding_method)

# 4. Clustering
task.update("Running clustering algorithm...", current=2, maximum=4)

labels = None

if algorithm == "leiden":
    import scanpy as sc
    import anndata as ad

    n_neighbors = algorithm_params.get("n_neighbors", 50)
    resolution = algorithm_params.get("resolution", 1.0)
    logger.info("Leiden: n_neighbors=%d, resolution=%.2f", n_neighbors, resolution)

    adata = ad.AnnData(X=df_norm.values)
    sc.pp.neighbors(adata, n_neighbors=n_neighbors, use_rep="X")
    sc.tl.leiden(adata, resolution=resolution, flavor="igraph", n_iterations=-1)
    labels = adata.obs["leiden"].astype(int).values

elif algorithm == "kmeans":
    from sklearn.cluster import KMeans
    n_clusters = algorithm_params.get("n_clusters", 10)
    logger.info("KMeans: n_clusters=%d", n_clusters)
    km = KMeans(n_clusters=n_clusters, n_init=10, random_state=42)
    labels = km.fit_predict(df_norm.values)

elif algorithm == "hdbscan":
    from sklearn.cluster import HDBSCAN
    min_cluster_size = algorithm_params.get("min_cluster_size", 15)
    min_samples = algorithm_params.get("min_samples", 5)
    logger.info("HDBSCAN: min_cluster_size=%d, min_samples=%d",
                min_cluster_size, min_samples)
    hdb = HDBSCAN(min_cluster_size=min_cluster_size, min_samples=min_samples)
    labels = hdb.fit_predict(df_norm.values)

elif algorithm == "agglomerative":
    from sklearn.cluster import AgglomerativeClustering
    n_clusters = algorithm_params.get("n_clusters", 10)
    linkage = algorithm_params.get("linkage", "ward")
    logger.info("Agglomerative: n_clusters=%d, linkage=%s", n_clusters, linkage)
    agg = AgglomerativeClustering(n_clusters=n_clusters, linkage=linkage)
    labels = agg.fit_predict(df_norm.values)

elif algorithm == "minibatchkmeans":
    from sklearn.cluster import MiniBatchKMeans
    n_clusters = algorithm_params.get("n_clusters", 10)
    batch_size = algorithm_params.get("batch_size", 1024)
    logger.info("MiniBatchKMeans: n_clusters=%d, batch_size=%d",
                n_clusters, batch_size)
    mbkm = MiniBatchKMeans(n_clusters=n_clusters, batch_size=batch_size,
                           random_state=42)
    labels = mbkm.fit_predict(df_norm.values)

elif algorithm == "gmm":
    from sklearn.mixture import GaussianMixture
    n_components = algorithm_params.get("n_components", 10)
    covariance_type = algorithm_params.get("covariance_type", "full")
    logger.info("GMM: n_components=%d, covariance_type=%s",
                n_components, covariance_type)
    gmm = GaussianMixture(n_components=n_components,
                          covariance_type=covariance_type, random_state=42)
    labels = gmm.fit_predict(df_norm.values)

else:
    raise ValueError("Unknown clustering algorithm: %s" % algorithm)

# 5. Compute cluster statistics (per-cluster marker means on normalized data)
task.update("Computing cluster statistics...", current=3, maximum=4)

n_clusters_found = int(labels.max() + 1) if labels.min() >= 0 else int(labels.max() + 2)
# For algorithms that produce noise labels (-1), shift to 0-based
if labels.min() < 0:
    # Noise points get their own cluster at the end
    labels_shifted = labels.copy()
    labels_shifted[labels_shifted < 0] = labels.max() + 1
    n_clusters_found = int(labels_shifted.max() + 1)
else:
    labels_shifted = labels

df_norm["cluster"] = labels_shifted
cluster_means = df_norm.groupby("cluster").mean(numeric_only=True).values

logger.info("Clustering complete: %d clusters found", n_clusters_found)

# 6. Package outputs
task.update("Packaging results...", current=4, maximum=4)

# Cluster labels
labels_nd = PyNDArray(dtype="int32", shape=[n_cells])
np.copyto(labels_nd.ndarray(), labels.astype(np.int32))
task.outputs["cluster_labels"] = labels_nd
task.outputs["n_clusters"] = n_clusters_found

# Embedding
if embedding_result is not None:
    emb_nd = PyNDArray(dtype="float64", shape=[n_cells, 2])
    np.copyto(emb_nd.ndarray(), embedding_result.astype(np.float64))
    task.outputs["embedding"] = emb_nd

# Cluster statistics (means)
stats_nd = PyNDArray(dtype="float64", shape=list(cluster_means.shape))
np.copyto(stats_nd.ndarray(), cluster_means.astype(np.float64))
task.outputs["cluster_stats"] = stats_nd

logger.info("Results packaged and ready for Java side")
