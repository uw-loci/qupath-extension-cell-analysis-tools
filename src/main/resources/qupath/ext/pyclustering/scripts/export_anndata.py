"""
Export clustering/phenotyping data as AnnData (.h5ad) for external analysis.

Inputs (injected by Appose 0.10.0):
  measurements: NDArray (N_cells x N_markers, float64)
  marker_names: list[str]
  output_path: str -- path to write the .h5ad file

Optional inputs:
  cluster_labels: list[int] -- cluster label per cell
  phenotype_labels: list[str] -- phenotype name per cell
  embedding: NDArray (N_cells x 2, float64) -- embedding coordinates
  embedding_name: str -- name for embedding (default "X_umap")
  spatial_coords: NDArray (N_cells x 2, float64) -- XY centroids

Outputs (via task.outputs):
  success: bool
  n_cells: int
  n_markers: int
  file_path: str
"""
import logging

logger = logging.getLogger("pyclustering.export")

import numpy as np
import pandas as pd
import anndata as ad

task.update("Building AnnData object...")

# Build core data matrix
data = measurements.ndarray().copy()
n_cells, n_markers = data.shape
logger.info("Exporting %d cells x %d markers to AnnData", n_cells, n_markers)

adata = ad.AnnData(X=data)
adata.var_names = pd.Index(list(marker_names))

# Add cluster labels if provided
try:
    if cluster_labels is not None:
        labels_list = list(cluster_labels)
        adata.obs['cluster'] = pd.Categorical([str(x) for x in labels_list])
        logger.info("Added cluster labels (%d unique)", len(set(labels_list)))
except NameError:
    pass

# Add phenotype labels if provided
try:
    if phenotype_labels is not None:
        pheno_list = list(phenotype_labels)
        adata.obs['phenotype'] = pd.Categorical(pheno_list)
        logger.info("Added phenotype labels (%d unique)", len(set(pheno_list)))
except NameError:
    pass

# Add embedding if provided
try:
    emb_data = embedding.ndarray().copy()
    try:
        emb_name = embedding_name
    except NameError:
        emb_name = "X_umap"
    adata.obsm[emb_name] = emb_data
    logger.info("Added embedding '%s' (%d x %d)", emb_name, emb_data.shape[0], emb_data.shape[1])
except NameError:
    pass

# Add spatial coordinates if provided
try:
    spatial_data = spatial_coords.ndarray().copy()
    adata.obsm['spatial'] = spatial_data
    logger.info("Added spatial coordinates (%d cells)", spatial_data.shape[0])
except NameError:
    pass

# Write to file
task.update("Writing .h5ad file...")
adata.write_h5ad(output_path)
logger.info("AnnData written to %s", output_path)

# Package outputs
task.outputs['success'] = True
task.outputs['n_cells'] = n_cells
task.outputs['n_markers'] = n_markers
task.outputs['file_path'] = output_path
