"""
Regenerate the color-dependent clustering PNGs for QP-CAT, WITHOUT re-running
clustering or embedding.

Only two of the saved matplotlib plots color cells by cluster id -- the embedding
scatter (cluster_embedding.png) and the spatial scatter (spatial_scatter.png).
When the user edits a cluster color in the Results window (the "Cluster N"
PathClass color is the source of truth), the interactive Java plots recolor for
free, but these baked PNGs do not. This standalone Appose task rebuilds just those
two PNGs from the cached embedding / labels / coordinates using the supplied
palette, so it costs one fast Python round-trip instead of a full re-cluster.

It deliberately does NOT depend on scanpy or squidpy -- numpy + matplotlib only --
so startup is quick. The other plots (dotplot, matrixplot, violin, PAGA,
nhood_enrichment) are expression/connectivity heatmaps whose colors do not depend
on the cluster palette, so they are left untouched.

Inputs (injected by Appose; accessed as variables, NOT task.inputs):
  embedding: NDArray (N x 2, float64) -- embedding coordinates (required)
  cluster_labels: NDArray (N,) int32 -- cluster id per cell, -1 = noise (required)
  output_dir: str -- directory to write the PNG(s) into (required)
  cluster_colors: list[str] (optional) -- "#RRGGBB" per cluster id (index = id).
                  Missing / short -> fall back to the built-in tab20 palette.
  spatial_coords: NDArray (N x 2, float64) (optional) -- centroid X,Y in pixels;
                  when present, spatial_scatter.png is (re)generated too.
  embedding_name: str (optional) -- axis-label stem (default "Embedding")
  plot_dpi: int (optional) -- output resolution (default 150)

Outputs (via task.outputs):
  plot_paths: str (JSON) -- {key: absolute_path} for each PNG written
              (keys: "embedding", "spatial_scatter")
"""

import logging
import json
import os

logger = logging.getLogger("qpcat.regenerate_plots")

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


def _update(msg):
    try:
        task.update(msg)
    except Exception:
        pass


# Built-in tab20 fallback (0..1 RGB), numeric-id order -- matches ClusterPalette
# on the Java side so a missing custom color still looks like the default plot.
_TAB20 = [
    (31, 119, 180),
    (255, 127, 14),
    (44, 160, 44),
    (214, 39, 40),
    (148, 103, 189),
    (140, 86, 75),
    (227, 119, 194),
    (127, 127, 127),
    (188, 189, 34),
    (23, 190, 207),
    (174, 199, 232),
    (255, 187, 120),
    (152, 223, 138),
    (255, 152, 150),
    (197, 176, 213),
    (196, 156, 148),
    (247, 182, 210),
    (199, 199, 199),
    (219, 219, 141),
    (158, 218, 229),
]


def _hex_to_rgb(s):
    s = s.strip().lstrip("#")
    if len(s) != 6:
        return None
    try:
        return (
            int(s[0:2], 16) / 255.0,
            int(s[2:4], 16) / 255.0,
            int(s[4:6], 16) / 255.0,
        )
    except ValueError:
        return None


# 1. Load inputs
_update("Loading cached embedding and labels...")
emb = embedding.ndarray().astype(np.float64, copy=True)
labels = cluster_labels.ndarray().astype(np.int64, copy=True)
n_cells = emb.shape[0]
if labels.shape[0] != n_cells:
    raise ValueError(
        "cluster_labels length (%d) != embedding rows (%d)" % (labels.shape[0], n_cells)
    )

out_dir = output_dir
os.makedirs(out_dir, exist_ok=True)

try:
    dpi = int(plot_dpi)
except NameError:
    dpi = 150

try:
    emb_name = str(embedding_name)
except NameError:
    emb_name = "Embedding"

try:
    colors_in = list(cluster_colors)
except NameError:
    colors_in = None

try:
    coords = spatial_coords.ndarray().astype(np.float64, copy=True)
    if coords.shape[0] != n_cells:
        logger.warning(
            "spatial_coords rows (%d) != n_cells (%d); ignoring",
            coords.shape[0],
            n_cells,
        )
        coords = None
except NameError:
    coords = None


def _color_for(cluster_id):
    """Palette color for a cluster id: custom hex if supplied, else tab20."""
    if colors_in is not None and 0 <= cluster_id < len(colors_in):
        rgb = _hex_to_rgb(colors_in[cluster_id])
        if rgb is not None:
            return rgb
    r, g, b = _TAB20[cluster_id % len(_TAB20)]
    return (r / 255.0, g / 255.0, b / 255.0)


plot_paths = {}
present = [int(c) for c in np.unique(labels)]
non_noise = [c for c in present if c >= 0]

# 2. Embedding scatter colored by cluster
_update("Rendering embedding scatter...")
try:
    fig, ax = plt.subplots(figsize=(8, 6))
    # Draw noise first (light gray, behind the real clusters).
    if -1 in present:
        m = labels < 0
        ax.scatter(
            emb[m, 0],
            emb[m, 1],
            c=[(0.827, 0.827, 0.827)],
            s=2,
            alpha=0.4,
            label="noise",
            rasterized=True,
        )
    for cl in non_noise:
        m = labels == cl
        ax.scatter(
            emb[m, 0],
            emb[m, 1],
            c=[_color_for(cl)],
            s=2,
            alpha=0.6,
            label=str(cl),
            rasterized=True,
        )
    ax.set_xlabel("%s 1" % emb_name)
    ax.set_ylabel("%s 2" % emb_name)
    ax.set_title("%s colored by cluster" % emb_name)
    ax.legend(
        title="Cluster",
        markerscale=5,
        fontsize="small",
        loc="center left",
        bbox_to_anchor=(1, 0.5),
    )
    embed_path = os.path.join(out_dir, "cluster_embedding.png")
    fig.savefig(embed_path, dpi=dpi, bbox_inches="tight")
    plt.close("all")
    plot_paths["embedding"] = embed_path
    logger.info("Regenerated embedding plot: %s", embed_path)
except Exception as e:
    logger.warning("Failed to regenerate embedding plot: %s", e)
    plt.close("all")

# 3. Spatial scatter colored by cluster (only when coordinates are available)
if coords is not None:
    _update("Rendering spatial scatter...")
    try:
        fig, ax = plt.subplots(figsize=(10, 8))
        if -1 in present:
            m = labels < 0
            ax.scatter(
                coords[m, 0],
                coords[m, 1],
                c=[(0.827, 0.827, 0.827)],
                s=1,
                alpha=0.4,
                label="noise",
                rasterized=True,
            )
        for cl in non_noise:
            m = labels == cl
            ax.scatter(
                coords[m, 0],
                coords[m, 1],
                c=[_color_for(cl)],
                s=1,
                alpha=0.5,
                label=str(cl),
                rasterized=True,
            )
        ax.set_xlabel("X (pixels)")
        ax.set_ylabel("Y (pixels)")
        ax.set_aspect("equal")
        ax.invert_yaxis()  # image coordinates: Y increases downward
        ax.set_title("Spatial distribution by cluster")
        ax.legend(
            title="Cluster",
            markerscale=5,
            fontsize="small",
            loc="center left",
            bbox_to_anchor=(1, 0.5),
        )
        spatial_path = os.path.join(out_dir, "spatial_scatter.png")
        fig.savefig(spatial_path, dpi=dpi, bbox_inches="tight")
        plt.close("all")
        plot_paths["spatial_scatter"] = spatial_path
        logger.info("Regenerated spatial scatter: %s", spatial_path)
    except Exception as e:
        logger.warning("Failed to regenerate spatial scatter: %s", e)
        plt.close("all")

# 4. Emit paths
task.outputs["plot_paths"] = json.dumps(plot_paths)
_update("Done regenerating plots")
logger.info("Regenerated %d plot(s)", len(plot_paths))
