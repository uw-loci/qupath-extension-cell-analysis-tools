"""
Main clustering script for QP-CAT Appose tasks.

Inputs (injected by Appose 0.10.0 -- accessed as variables, NOT task.inputs):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  algorithm: str ("leiden", "kmeans", "hdbscan", "agglomerative", "minibatchkmeans", "gmm")
  algorithm_params: dict (algorithm-specific parameters)
  normalization: str ("zscore", "minmax", "percentile", "none")
  embedding_method: str ("umap", "pca", "tsne", "none")
  embedding_params: dict (method-specific parameters)

Optional inputs:
  generate_plots: bool (default False)
  output_dir: str (directory for plot images)
  top_n_markers: int (default 5)
  spatial_coords: NDArray (N_cells x 2, float64) -- cell XY centroids for spatial analysis
  enable_batch_correction: bool (default False)
  batch_labels: list[int] -- image index per cell for batch correction

Outputs (via task.outputs):
  cluster_labels: NDArray (N_cells,) int32
  n_clusters: int
  embedding: NDArray (N_cells x 2) float64 (if embedding_method != "none")
  cluster_stats: NDArray (n_clusters x N_markers) float64 -- per-cluster marker means
  marker_rankings: str (JSON) -- top markers per cluster with scores
  paga_connectivity: NDArray (n_clusters x n_clusters) float64 -- PAGA graph weights
  paga_cluster_names: str (JSON) -- ordered cluster names for PAGA matrix
  nhood_enrichment: NDArray (n_clusters x n_clusters) float64 -- neighborhood z-scores
  nhood_cluster_names: str (JSON) -- cluster names for enrichment matrix
  spatial_autocorr: str (JSON) -- per-marker Moran's I scores
  plot_paths: str (JSON) -- dict of plot type -> file path (if generate_plots)
"""
import sys
import os
import logging

logger = logging.getLogger("qpcat.clustering")

import numpy as np
import pandas as pd
from appose import NDArray as PyNDArray

# 1. Reshape input NDArray to numpy and release shared memory
data = measurements.ndarray().copy()
n_cells, n_markers = data.shape
logger.info("Received %d cells x %d markers", n_cells, n_markers)

df = pd.DataFrame(data, columns=marker_names)

# Read optional spatial coordinates early (needed by BANKSY and spatial analysis)
try:
    spatial_data = spatial_coords.ndarray().copy()
    has_spatial_coords = True
    logger.info("Spatial coordinates loaded (%d cells)", spatial_data.shape[0])
except NameError:
    spatial_data = None
    has_spatial_coords = False

# 2. Normalize
task.update("Normalizing measurements...", current=0, maximum=6)

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

# 2b. Batch correction (Harmony, for multi-image clustering)
try:
    do_batch = enable_batch_correction
except NameError:
    do_batch = False
try:
    batch_labels_list = batch_labels
except NameError:
    batch_labels_list = None

if do_batch and batch_labels_list is not None:
    task.update("Running batch correction (Harmony)...")
    import harmonypy as hm

    n_batches = len(set(batch_labels_list))
    if n_batches > 1:
        meta_df = pd.DataFrame({'batch': [str(b) for b in batch_labels_list]})
        ho = hm.run_harmony(df_norm.values, meta_df, 'batch')
        df_norm = pd.DataFrame(ho.Z_corr.T, columns=df_norm.columns)
        logger.info("Harmony batch correction applied (%d batches)", n_batches)
    else:
        logger.info("Skipping batch correction (only 1 batch)")

# 3. Dimensionality reduction
task.update("Computing embedding...", current=1, maximum=6)

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
task.update("Running clustering algorithm...", current=2, maximum=6)

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

elif algorithm == "banksy":
    if not has_spatial_coords:
        raise ValueError("BANKSY requires spatial coordinates (cell centroids)")

    from banksy.initialize_banksy import initialize_banksy
    from banksy.run_banksy import run_banksy_search
    import anndata as ad

    lambda_param = algorithm_params.get("lambda_param", 0.2)
    k_geom = algorithm_params.get("k_geom", 15)
    resolution = algorithm_params.get("resolution", 0.7)
    pca_dims = algorithm_params.get("pca_dims", 20)
    logger.info("BANKSY: lambda=%.2f, k_geom=%d, resolution=%.2f, pca_dims=%d",
                lambda_param, k_geom, resolution, pca_dims)

    # Build AnnData with expression and spatial coordinates
    adata_banksy = ad.AnnData(X=df_norm.values)
    adata_banksy.var_names = pd.Index(list(marker_names))
    adata_banksy.obsm['spatial'] = spatial_data

    # Initialize BANKSY (compute spatial neighbor weights)
    banksy_dict = initialize_banksy(
        adata_banksy,
        coord_keys=None,  # uses adata.obsm['spatial']
        k_geom=k_geom,
        max_m=1,
        plt_edge_hist=False,
        plt_nbr_weights=False,
    )

    # Run BANKSY clustering (Leiden on spatially-augmented features)
    results_df = run_banksy_search(
        adata_banksy,
        banksy_dict,
        lambda_list=[lambda_param],
        resolutions=[resolution],
        max_m=1,
        pca_dims=[pca_dims],
        key='qpcat',
        cluster_algorithm='leiden',
        savefig=False,
        add_nonspatial=False,
    )

    # Extract cluster labels from adata_banksy.obs
    label_cols = [c for c in adata_banksy.obs.columns if c.startswith('labels_')]
    if not label_cols:
        raise ValueError("BANKSY did not produce cluster labels")
    labels = adata_banksy.obs[label_cols[-1]].astype(int).values
    logger.info("BANKSY clustering complete: used label column '%s'", label_cols[-1])

elif algorithm == "none":
    # Embedding only -- assign all cells to cluster 0 (no real clustering)
    labels = np.zeros(n_cells, dtype=np.int32)
    logger.info("Embedding-only mode: no clustering applied")

else:
    raise ValueError("Unknown clustering algorithm: %s" % algorithm)

# 5. Compute cluster statistics (per-cluster marker means on normalized data)
task.update("Computing cluster statistics...", current=3, maximum=6)

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

# 6. Post-clustering analysis (marker ranking + PAGA)
task.update("Analyzing clusters...", current=4, maximum=6)

import scanpy as sc
import anndata as ad
import json

# Build full AnnData for scanpy analysis
adata = ad.AnnData(X=df_norm.drop(columns=["cluster"]).values)
adata.var_names = pd.Index(list(marker_names))
cluster_labels_str = [str(x) for x in labels_shifted]
adata.obs['cluster'] = pd.Categorical(cluster_labels_str)

if embedding_result is not None:
    adata.obsm['X_embed'] = embedding_result

# Compute neighbor graph (needed for PAGA and dendrogram)
n_neigh = min(15, n_cells - 1)
embedding_only = (algorithm == "none")
can_analyze = n_neigh >= 2 and n_clusters_found > 1 and not embedding_only

if can_analyze:
    sc.pp.neighbors(adata, n_neighbors=n_neigh, use_rep='X')
    sc.tl.dendrogram(adata, groupby='cluster')

    # 6a. Marker ranking (Wilcoxon rank-sum test)
    try:
        top_n = top_n_markers
    except NameError:
        top_n = 5

    try:
        sc.tl.rank_genes_groups(adata, groupby='cluster', method='wilcoxon')

        marker_result = {}
        result_data = adata.uns['rank_genes_groups']
        for cid in adata.obs['cluster'].cat.categories:
            markers_list = []
            names = result_data['names'][cid][:top_n]
            scores = result_data['scores'][cid][:top_n]
            logfcs = result_data['logfoldchanges'][cid][:top_n]
            pvals = result_data['pvals_adj'][cid][:top_n]
            for i in range(len(names)):
                markers_list.append({
                    'name': str(names[i]),
                    'score': float(scores[i]),
                    'logfoldchange': float(logfcs[i]),
                    'pval_adj': float(pvals[i])
                })
            marker_result[str(cid)] = markers_list

        task.outputs['marker_rankings'] = json.dumps(marker_result)
        logger.info("Marker ranking complete: top %d markers per cluster", top_n)
    except Exception as e:
        logger.warning("Marker ranking failed: %s", e)

    # 6b. PAGA (cluster connectivity / trajectory graph)
    try:
        sc.tl.paga(adata, groups='cluster')
        paga_conn = adata.uns['paga']['connectivities'].toarray()

        paga_nd = PyNDArray(dtype="float64", shape=list(paga_conn.shape))
        np.copyto(paga_nd.ndarray(), paga_conn.astype(np.float64))
        task.outputs['paga_connectivity'] = paga_nd
        task.outputs['paga_cluster_names'] = json.dumps(
            list(adata.obs['cluster'].cat.categories))
        logger.info("PAGA connectivity computed (%d x %d)",
                    paga_conn.shape[0], paga_conn.shape[1])
    except Exception as e:
        logger.warning("PAGA computation failed: %s", e)
else:
    logger.info("Skipping post-analysis (too few cells or clusters)")

# 6c. Spatial analysis (if coordinates provided)
has_spatial = has_spatial_coords

if has_spatial and n_clusters_found > 1:
    task.update("Running spatial analysis...")
    import squidpy as sq

    adata.obsm['spatial'] = spatial_data
    adata.obsm['X_spatial'] = spatial_data  # for scanpy plotting (basis='spatial')
    logger.info("Spatial coordinates loaded (%d cells)", spatial_data.shape[0])

    # Build spatial neighbor graph (Delaunay triangulation)
    try:
        sq.gr.spatial_neighbors(adata, coord_type='generic', delaunay=True)
        logger.info("Spatial neighbor graph built (Delaunay)")
    except Exception as e:
        logger.warning("Spatial neighbor graph failed: %s", e)
        has_spatial = False

if has_spatial and n_clusters_found > 1:
    # Neighborhood enrichment (z-score matrix)
    try:
        sq.gr.nhood_enrichment(adata, cluster_key='cluster')
        nhood_data = adata.uns['cluster_nhood_enrichment']
        zscore = nhood_data['zscore']

        nhood_nd = PyNDArray(dtype="float64", shape=list(zscore.shape))
        np.copyto(nhood_nd.ndarray(), zscore.astype(np.float64))
        task.outputs['nhood_enrichment'] = nhood_nd
        task.outputs['nhood_cluster_names'] = json.dumps(
            list(adata.obs['cluster'].cat.categories))
        logger.info("Neighborhood enrichment computed (%d x %d)",
                    zscore.shape[0], zscore.shape[1])
    except Exception as e:
        logger.warning("Neighborhood enrichment failed: %s", e)

    # Spatial autocorrelation (Moran's I per marker)
    try:
        df_autocorr = sq.gr.spatial_autocorr(adata, mode='moran')
        autocorr_results = {}
        for marker in marker_names:
            if marker in df_autocorr.index:
                row = df_autocorr.loc[marker]
                autocorr_results[marker] = {
                    'I': float(row['I']),
                    'pval': float(row.get('pval_norm', row.get('pval_z_sim',
                                         float('nan'))))
                }
        task.outputs['spatial_autocorr'] = json.dumps(autocorr_results)
        logger.info("Spatial autocorrelation (Moran's I) computed for %d markers",
                    len(autocorr_results))
    except Exception as e:
        logger.warning("Spatial autocorrelation failed: %s", e)

# 7. Generate plots (optional)
try:
    do_plots = generate_plots
except NameError:
    do_plots = False
try:
    plot_dir = output_dir
except NameError:
    plot_dir = None

if do_plots and plot_dir and can_analyze:
    task.update("Generating plots...", current=5, maximum=6)
    import matplotlib.pyplot as plt

    os.makedirs(plot_dir, exist_ok=True)
    plot_paths = {}

    # Dotplot with dendrogram -- fraction expressing + mean expression per cluster
    try:
        dp = sc.pl.dotplot(adata, var_names=list(marker_names), groupby='cluster',
                           dendrogram=True, standard_scale='var',
                           show=False, return_fig=True)
        dotplot_path = os.path.join(plot_dir, 'cluster_dotplot.png')
        dp.savefig(dotplot_path, dpi=150, bbox_inches='tight')
        plt.close('all')
        plot_paths['dotplot'] = dotplot_path
        logger.info("Saved dotplot: %s", dotplot_path)
    except Exception as e:
        logger.warning("Failed to generate dotplot: %s", e)

    # Matrix plot -- mean expression heatmap per cluster
    try:
        mp = sc.pl.matrixplot(adata, var_names=list(marker_names), groupby='cluster',
                              dendrogram=True, standard_scale='var',
                              show=False, return_fig=True)
        matrixplot_path = os.path.join(plot_dir, 'cluster_matrixplot.png')
        mp.savefig(matrixplot_path, dpi=150, bbox_inches='tight')
        plt.close('all')
        plot_paths['matrixplot'] = matrixplot_path
        logger.info("Saved matrixplot: %s", matrixplot_path)
    except Exception as e:
        logger.warning("Failed to generate matrixplot: %s", e)

    # PAGA graph -- cluster connectivity / trajectory
    try:
        sc.pl.paga(adata, show=False)
        paga_path = os.path.join(plot_dir, 'paga_graph.png')
        plt.savefig(paga_path, dpi=150, bbox_inches='tight')
        plt.close('all')
        plot_paths['paga'] = paga_path
        logger.info("Saved PAGA graph: %s", paga_path)
    except Exception as e:
        logger.warning("Failed to generate PAGA graph: %s", e)

    # Stacked violin plot -- expression distribution per cluster
    if n_clusters_found > 1:
        try:
            sv = sc.pl.stacked_violin(adata, var_names=list(marker_names),
                                       groupby='cluster', dendrogram=True,
                                       show=False, return_fig=True)
            violin_path = os.path.join(plot_dir, 'stacked_violin.png')
            sv.savefig(violin_path, dpi=150, bbox_inches='tight')
            plt.close('all')
            plot_paths['stacked_violin'] = violin_path
            logger.info("Saved stacked violin: %s", violin_path)
        except Exception as e:
            logger.warning("Failed to generate stacked violin: %s", e)

    # Embedding scatter colored by cluster (if embedding was computed)
    if embedding_result is not None:
        try:
            fig, ax = plt.subplots(figsize=(8, 6))
            sc.pl.embedding(adata, basis='embed', color='cluster',
                            show=False, ax=ax)
            embed_path = os.path.join(plot_dir, 'cluster_embedding.png')
            fig.savefig(embed_path, dpi=150, bbox_inches='tight')
            plt.close('all')
            plot_paths['embedding'] = embed_path
            logger.info("Saved embedding plot: %s", embed_path)
        except Exception as e:
            logger.warning("Failed to generate embedding plot: %s", e)

    # Spatial plots (if spatial coordinates were provided)
    if has_spatial:
        # Neighborhood enrichment heatmap
        try:
            sq.pl.nhood_enrichment(adata, cluster_key='cluster', show=False)
            nhood_path = os.path.join(plot_dir, 'nhood_enrichment.png')
            plt.savefig(nhood_path, dpi=150, bbox_inches='tight')
            plt.close('all')
            plot_paths['nhood_enrichment'] = nhood_path
            logger.info("Saved neighborhood enrichment heatmap: %s", nhood_path)
        except Exception as e:
            logger.warning("Failed to generate nhood enrichment plot: %s", e)

        # Spatial scatter colored by cluster
        try:
            fig, ax = plt.subplots(figsize=(10, 8))
            clusters_cat = adata.obs['cluster'].cat.categories
            n_cats = len(clusters_cat)
            cmap = plt.cm.get_cmap('tab20' if n_cats > 10 else 'tab10', n_cats)
            for idx, cl in enumerate(clusters_cat):
                mask = adata.obs['cluster'] == cl
                ax.scatter(spatial_data[mask, 0], spatial_data[mask, 1],
                           c=[cmap(idx)], s=1, alpha=0.5, label=str(cl),
                           rasterized=True)
            ax.set_xlabel('X (pixels)')
            ax.set_ylabel('Y (pixels)')
            ax.set_aspect('equal')
            ax.invert_yaxis()  # image coordinates: Y increases downward
            ax.set_title('Spatial distribution by cluster')
            ax.legend(title='Cluster', markerscale=5, fontsize='small',
                      loc='center left', bbox_to_anchor=(1, 0.5))
            spatial_path = os.path.join(plot_dir, 'spatial_scatter.png')
            fig.savefig(spatial_path, dpi=150, bbox_inches='tight')
            plt.close('all')
            plot_paths['spatial_scatter'] = spatial_path
            logger.info("Saved spatial scatter: %s", spatial_path)
        except Exception as e:
            logger.warning("Failed to generate spatial scatter plot: %s", e)

    if plot_paths:
        task.outputs['plot_paths'] = json.dumps(plot_paths)

# 8. Package core outputs
task.update("Packaging results...", current=6, maximum=6)

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
