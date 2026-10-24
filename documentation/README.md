# QP-CAT documentation

Find your task below. Most pages cover one topic end to end -- how to do it, and
which options to pick for which kind of data.

## By task

| I want to... | Read |
|---|---|
| Install the Python environment, or move it off a quota-limited home directory | [Setup](setup.md) |
| Find cell populations by clustering, and choose an algorithm | [Clustering](clustering.md) |
| Understand the results window -- heatmap, embedding, composition, markers, validation | [Results](results.md) |
| Rename, merge, sub-cluster, or gate populations after a run | [Working with clusters](clusters.md) |
| Label cells by marker rules instead of clustering | [Phenotyping](phenotyping.md) |
| Measure spatial organization -- neighborhood enrichment, Ripley, Geary, co-occurrence, Moran's I | [Spatial statistics](spatial-statistics.md) |
| Find spatial niches, or see the neighbor graph in the viewer | [Neighborhoods and the spatial graph](spatial-neighborhoods.md) |
| Classify cells by appearance with the autoencoder | [Autoencoder](autoencoder.md) |
| Save figures, tables, or AnnData | [Exporting](exporting.md) |
| Run QP-CAT headlessly over a whole project | [Batch runs](batch.md), with the [YAML reference](yaml-reference.md) |
| Drive QP-CAT from a Groovy script | [Scripting](scripting.md) |
| Reproduce an earlier run, or work out why one is slow | [Reproducibility and run cost](reproducibility.md) |
| Ask an LLM to name clusters | [LLM explainer](llm-explainer.md) |
| Let an AI agent I already use name the clusters, with no key | [MCP server](mcp-server.md) |
| Fix something that went wrong | [Troubleshooting](troubleshooting.md) |
| Cluster *on* a UMAP instead of on the markers | [Recipes](recipes.md) |
| Try QP-CAT on ground-truthed sample data | [Sample data](../README.md#sample-data) |
| Cite QP-CAT, or the method behind an option | [How to cite](../README.md#how-to-cite), [References](references.md) |
| Install or update the extension, or see what changed | [README](../README.md#installation), [CHANGELOG](../CHANGELOG.md) |

## The menu

Everything lives under **Extensions > QP-CAT**. The two things QP-CAT is *for* --
finding populations and classifying cells -- are at the top level; the rest is
grouped by when you would reach for it.

```
Find cell populations (clustering)...                     Clustering
Classify cells >
    Label cells by marker rules (phenotyping)...          Phenotyping
    Classify cells by appearance (deep learning)...       Autoencoder
--------
Explore & spatial >
    Quick clustering presets >                            Clustering
    Map cells in 2D (UMAP / PCA / t-SNE)...               Clustering
    Plot & gate cells (2D)...                             Working with clusters
    Find cellular neighborhoods (spatial niches)...       Neighborhoods
    Spatial statistics on existing clusters...            Spatial statistics
Results & populations >
    View Past Results...                                  Results
    Manage Saved Results...                               Results
    Modify cell populations (rename, merge, split, sub-cluster)...   Working with clusters
    Analyze current cell classifications...               Working with clusters
    Apply saved result to detections...                   Working with clusters
    Import labels and measurements from AnnData (.h5ad)...   Exporting
    Apply cluster color palette...                        Results
Export >
    Export figures (batch)...                             Exporting
    Export cells for Python / scanpy (AnnData)...         Exporting
    Export cell crops + feature table (TraitHorizon / CSV)...   Exporting
    Export clustered cells for VEST (3D viewer)...        Exporting
    Stop VEST viewer
--------
Setup & help >
    Set up analysis environment (first run)...            Setup (hidden once set up)
    Python Console                                        Troubleshooting
    Clear cell connections...                             Neighborhoods
    System Info...                                        Troubleshooting (appears once set up)
    Rebuild analysis environment                          Troubleshooting
    Documentation (How-to guide)...                       opens this page
    Report a Bug...                                       Troubleshooting
    [ ] MCP server for AI agents (off)                    MCP server (off by default)
    Show MCP server window (address and token)            MCP server (grayed out until it is on)
```

Menu paths are written in full throughout, e.g.
**Extensions > QP-CAT > Explore & spatial > Plot & gate cells (2D)...**.

## Start here

New to QP-CAT: [Setup](setup.md), then [Clustering](clustering.md), then
[Results](results.md). Those three pages take you from install to a clustered,
checked result. No data yet? The
[multiplex synthetic dataset](https://github.com/uw-loci/multiplex-synthetic-data)
has 8 ground-truthed images; see [Sample data](../README.md#sample-data).
