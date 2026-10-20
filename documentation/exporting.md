# Exporting

Figures, tables, and the data itself.

- [When to batch-export](#when-to-batch-export)
- [Figure export](#figure-export)
- [AnnData for Python](#anndata-for-python)
- [VEST 3D export](#vest-3d-export)

## When to batch-export

Batch figure export ([below](#figure-export)) writes every plot from one or more saved clustering results to a directory at one chosen format and DPI.

### Batch-export vs save-individual-plot

| Situation | Use |
|---|---|
| Writing up a paper / thesis -- need every figure for a clustering run | Batch export |
| Slide deck for a group meeting -- need 10-30 figures across 5-15 images | Batch export |
| Reviewer requests "the dotplot" for one image | Batch-export with a single plot checked is the cleanest path |
| Iterating on cluster labels and want to compare before / after | Batch-export both runs to separate directories, then diff |
| Headless / scripted / YAML-driven analysis pipeline | Scripting API (`FigureExportScripts.exportFigures`) -- see [Scripting](scripting.md#figureexportscripts) |
| The numbers behind a spatial statistic (Moran's I, Geary's C, neighborhood enrichment, Ripley L, co-occurrence) | **Copy CSV** / **Save CSV...** under that tab in the results window -- see [Exporting spatial statistics tables](results.md#exporting-spatial-statistics-tables) |

A rule of thumb: **if you need more than two figures at a time, batch-export is faster.** If you need one figure, picking a single image + a single plot in the batch dialog is still a one-click operation.

### Picking a format

- **PNG at 300 DPI** is the journal-default raster choice. Most journals will accept this for supplementary; many accept it for main figures too. The matplotlib-side PNGs are copied verbatim so the Python savefig DPI is preserved.
- **TIFF at 300 DPI** is the lossless raster choice. Slightly larger files than PNG, but some journals specifically request TIFF for figures.
- **There is no vector export.** SVG, PDF and EPS are not implemented and are not scheduled. Render raster at the highest DPI your workflow tolerates -- 300 for typical paper figures, 600 or more for a figure that will be enlarged, as on a poster.

### Integrating with a paper / poster workflow

A workflow that holds up across multiple revision rounds:

1. **Lock the clustering parameters early.** Save the clustering config to the project (`<project>/qpcat/cluster_configs/`) so you can re-run with byte-identical parameters.
2. **Run all images with the locked config.** Make sure each image has the saved `ClusteringResult` available before batch-exporting.
3. **Export figures into a versioned subdirectory.** Name it after the revision: `figures/2026-05-13_revision1/`. Avoid overwriting an earlier directory unless you're sure you don't need the previous run; the dialog defaults to fail-fast on existing files for exactly this reason.
4. **Keep the audit log alongside the figures.** Copy `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` into the figures directory so future-you can answer "what parameters made these figures?" without digging.
5. **For the paper itself**, render at the highest DPI your journal accepts -- typically 600 for raster main figures -- and use PNG, which more editors handle than TIFF.

For a **poster**, 300 DPI PNG at the rendered figure size is typically enough. If the figure will be enlarged 4-8x in the poster layout (typical), bump the export DPI to 600-1200 to keep edges crisp.


### Filenames and cross-platform sharing

The default filename pattern (`{image}_{plot}.{ext}`) is filesystem-safe across Windows / macOS / Linux. If you customize the pattern:

- Avoid characters that one OS allows but another doesn't (`:` and `\` are notable Windows traps).
- Avoid Windows reserved names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`) -- the exporter prepends `_` if a sanitized filename collides with one, but it's clearer to avoid these in the pattern itself.
- Use `{result_name}` if you have multiple saved clustering runs per image and want each in its own group; otherwise leave it out for shorter filenames.

If you're sharing the exported folder with collaborators on different OSes, the default pattern is the safest choice.

Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s batch-export action; QP-CAT adds the mandatory image-subset checklist and a Groovy scripting surface on top.

---
## Figure export

QP-CAT renders a stack of plots when you run clustering -- dotplot, matrix plot, PAGA, stacked violin, scanpy embedding, neighborhood enrichment, spatial scatter, plus the spatial-stats charts (Ripley L, Geary's C, co-occurrence). **Export figures (batch)...** writes every one of those to disk in a single pass, with a per-project image subset and a deterministic filename pattern. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s batch-export action; QP-CAT adds the mandatory image-subset checklist and a Groovy scripting surface (consumed by the YAML batch feature) on top.

### When to use this feature

- **Writing a paper or thesis chapter** -- you have your final clustering and need 8-30 figures laid out consistently. One click instead of 30 right-clicks.
- **Group-meeting slide decks** -- export everything, drop the directory into a slide deck builder.
- **Reviewer-ready bundles** -- a single output directory you can zip and email.
- **Comparing analyses side by side** -- run the export twice with different output directories, diff the figures.

### Supported formats

| Format | DPI applies? | Notes |
|---|---|---|
| **PNG** | Yes (default 300) | Universal raster; recommended for figures and posters. Source PNGs from matplotlib are copied verbatim so the DPI baked in by Python is preserved. |
| **TIFF** | Yes (default 300) | Lossless raster; preferred by some journals. Uncompressed -- there is no LZW option. |

**There is no vector export.** Honest SVG/PDF/EPS would mean re-running the Python plot pipeline with a vector backend rather than transcoding a file, and that is not implemented.

### Quick Start

1. **Run clustering** on at least one image (and save the result if you haven't already)
2. **Extensions > QP-CAT > Export > Export figures (batch)...**
3. Pick the **Output directory**
4. Under **Images to export**, choose the images
5. Under **Plots to export**, tick the plots you want
6. Under **Output format and DPI**, pick the format and DPI
7. Click **Export Figures**
8. Watch the progress bar; when it finishes, browse the output folder. With the default pattern
   the files are named like `Slide_07_dotplot.png` and `all-images_composition_pie_image.png`

### Image-subset selection

The dialog's **Images to export** section has three radio choices:

- **Current image only** -- export figures from the image currently open in the viewer.
- **All images in project** -- export figures from every image in the project.
- **Subset (pick from list below)** -- check specific images in the list. Filter by name with the **Filter** field; **Select All** / **Deselect All** operate on the filtered view.

The selector is mandatory because batch export across a 50+ image project can produce 500+ files. For each image in the list, the dialog reports which plot kinds are available (saved with the clustering result on disk). Rows showing "(no result)" do not have a saved clustering result -- run **Find cell populations (clustering)...** on that image first if you want it in the export.

### Plot-availability explanation

Not every plot exists for every image / clustering result. The dialog shows this honestly:

- **Saved matplotlib plots** (dotplot, matrix plot, PAGA, stacked violin, scanpy embedding, neighborhood enrichment, spatial scatter) -- written to disk when `Run Clustering` completes and persisted with the project. These export with or without the results dialog open.
- **Spatial-stats plots** (Ripley L, Geary's C, co-occurrence pairwise / one-vs-rest / curves) -- saved with the result when the spatial statistics ran. If they weren't, the rows in the plot list show as missing for that image.
- **Cluster composition** (pies and CSV tables by image, annotation, area and class) -- drawn from the saved result itself rather than from a PNG the run happened to write, so they need no open image, no results window, and no plotting options ticked at run time. By image works for any current result; by annotation needs a result clustered on annotation input, by area a run with two or more independent areas, by class two or more annotation classes. A pair the result cannot support is recorded as a failure that names the reason. See [Exporting the composition figures](#exporting-the-composition-figures) below.
- **Interactive plots** (heatmap, interactive embedding scatter, autoencoder pie, histogram) -- this export cannot write them; ticking one records it as not exported, and the summary lists it as skipped. Open the result in the Results window and use [**Save plot...**](results.md#saving-a-plot) on its tab.

For the **headless scripting API** (see [Scripting](scripting.md#figureexportscripts)), the saved matplotlib plots and the cluster-composition figures / tables are exportable; the interactive plots are not. The audit log gets a `FIGURE EXPORT` row only when the export runs from the dialog. Script callers should ensure the underlying clustering run had all the plots enabled.

### Exporting the composition figures

The pie charts and composition tables from the Results window's Composition tabs export like any
other figure. Eight plot kinds -- a pie/table pair for each of the four grouping axes:

| Slug | Writes | Notes |
|---|---|---|
| `composition_pie_image` | One pie per source image, plus the shared cluster legend | On by default |
| `composition_table_image` | The per-image counts table | Always `.csv`, whatever raster format is ticked |
| `composition_pie_annotation` | One pie per parent annotation | Off by default -- needs an annotation-input result |
| `composition_table_annotation` | The per-annotation counts table | Always `.csv` |
| `composition_pie_area` | One pie per independent area (TMA core, tissue section) | Off by default -- needs a run with area levels |
| `composition_table_area` | The per-area counts table | Always `.csv` |
| `composition_pie_class` | One pie per annotation class | Off by default -- needs two or more annotation classes |
| `composition_table_class` | The per-class counts table | Always `.csv` |

Three things differ from the per-image plots, all of them deliberate:

- **One file per result, not per image.** Composition describes how *this result's* clusters split across every image, so exporting it once per selected image would write N copies of the same picture. The "Expected files" count in the dialog already accounts for this.
- **`{image}` expands to `all-images`** in the filename pattern, so a composition file is never mistaken for a per-image one. With the default pattern you get `all-images_composition_pie_image.png`.
- **The CSV carries both counts and percentages** (`Cluster 0 (n)`, `Cluster 0 (%)`, ... `Total`), one row per group plus an all-groups row -- so the file answers both questions the on-screen Counts / Row % toggle does, without you having to remember which mode was active.

Cluster colors come from the live `Cluster N` classes, so recoloring clusters in the Results window changes the exported figure too.

For a one-off export of the tab you are looking at, the Composition tabs also have an **Export figure + table...** button. Pick a folder and it writes:

```
composition_by_image.png            the combined figure (all pies + legend)
composition_by_image.csv            the table, counts and percentages
composition_by_image_panels/
    legend.png                      one shared cluster key
    slide_01.ome.tif.png            one pie per image, on its own
    slide_02.ome.tif.png
    ...
```

The combined PNG is what the batch exporter produces. The `_panels/` folder exists because QP-CAT deliberately offers few layout options: rather than adding knobs for grid size, ordering and legend placement, it gives you the pieces so you can lay the panel out in whatever figure editor you already use. One legend, not one per pie, since repeating the key under every chart is wasted space in a real figure.

Single pies use the same geometry and colors as their tile in the combined figure, so the two are visually interchangeable. Everything is rendered by the same code as the batch path, so no output depends on the window's size, scroll position or theme.

### Filename patterns

Default pattern: `{image}_{plot}.{ext}` -- example: `Slide_07_dotplot.png`, `Slide_07_ripley_l.png`.

Available substitution variables:

| Token | Expands to | Example |
|---|---|---|
| `{image}` | QuPath image name (filesystem-sanitized) | `Slide_07` |
| `{plot}` | Plot kind (always filesystem-safe; one of: `dotplot`, `matrixplot`, `paga`, `violin`, `embedding_scanpy`, `neighborhood`, `spatial_scatter`, `ripley_l`, `geary_c`, `cooc_pairwise`, `cooc_one_vs_rest`, `cooc_curves`, `composition_pie_image`, `composition_table_image`, `composition_pie_annotation`, `composition_table_annotation`, `composition_pie_area`, `composition_table_area`, `composition_pie_class`, `composition_table_class`, `heatmap`, `embedding_interactive`, `autoencoder_pie`, `histogram`) | `dotplot` |
| `{result_name}` | Saved-result name from `ClusteringResultManager`, sanitized | `Leiden_res1.0_2026-05-13` |
| `{date}` | YYYY-MM-DD date of export | `2026-05-13` |
| `{ext}` | File extension matching the format (`png` or `tif`) | `png` |

Validation rules:

- Pattern must include at least `{image}`, `{plot}`, and `{ext}`. The Export Figures button stays disabled until all three are present.
- Invalid filename characters (`< > : " / \ | ? *`) are stripped per `qupath.lib.common.GeneralTools.stripInvalidFilenameChars`.
- Windows reserved names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`) are guarded by prepending `_` after substitution.
- If a token expands to the empty string after sanitization, the literal `figure` is substituted.
- The **Filename pattern (advanced)** section is collapsed by default -- 90% of users will not touch it.

### Large-batch tips

Where to put the output directory:

- **A new subdirectory under a paper folder** is the easiest to share -- e.g. `~/paper-cd8-spatial/figures/2026-05-13_qpcat_run3/`.
- The **Project** button in the **Output directory** section puts it under `<project>/qpcat/figures/<date>/` instead, beside the project's own files.

Expected file counts for a typical project:

| Project size | Plots per image (typical) | Total files |
|---|---|---|
| 1 image | 7-12 | 7-12 |
| 10 images | 7-12 | 70-120 |
| 50 images | 7-12 | 350-600 |
| 100 images | 7-12 | 700-1200 |

At 300 DPI PNG, each plot is typically 50-300 KB; total disk usage for a 50-image project is roughly 50-150 MB. The dialog reports an "Expected files: N" preview before you click Export Figures so there are no surprises.

### What gets logged

Each export run writes one row to the project's audit log at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` under the `FIGURE EXPORT` event tag. The row captures:

- Output directory (absolute path)
- Image scope and (when SUBSET and N <= 20) the explicit name list
- Plot kinds in the subset
- Format(s) and DPI
- Filename pattern
- File count written, total bytes
- Failure count (zero on a clean run)

For the programmatic Groovy API see [Scripting](scripting.md#figureexportscripts).

---
## AnnData for Python

Export data for use with external single-cell tools (Scanpy, Seurat, cellxgene).

1. **Extensions > QP-CAT > Export > Export cells for Python / scanpy (AnnData)...**
2. Choose a save location and filename
3. The export includes:
   - Expression matrix (all measurements) as `adata.X`, with the measurement names as `adata.var_names`
   - Cluster labels (if cells are classified as "Cluster N") as `adata.obs["cluster"]`
   - Phenotype labels (if cells have other classifications) as `adata.obs["phenotype"]`
   - Embedding coordinates (QPCAT UMAP1/UMAP2, etc., if present) as `adata.obsm["X_umap"]`; that
     key is used whichever embedding method made them
   - Spatial coordinates (cell centroids) as `adata.obsm["spatial"]`
4. Open the file in Python:

```python
import scanpy as sc
adata = sc.read_h5ad("export.h5ad")
print(adata)
```

---
## VEST 3D export

[VEST](https://github.com/scads/vest) (Vision Embedding Space Travelling, MIT) is a small
browser-based tool that shows your clustered cells as an **interactive 3D point cloud** --
each point is a cell thumbnail placed at its embedding coordinates. QP-CAT can export a
VEST bundle and, if you like, launch VEST for you.

Find it under **Extensions > QP-CAT > Export > Export clustered cells for VEST (3D viewer)...**
(the open image must already carry `Cluster N` classes -- run or apply clustering first).

### What gets exported

A self-contained folder with:

- `embedding.csv` -- one row per exported cell: `filename, x, y, z, cluster` (a true
  **3-component** UMAP / PCA / t-SNE embedding of the cells' marker measurements).
- `images/` -- one small PNG crop per cell, matching the CSV.
- `README.txt` -- the exact command to run VEST by hand.

### Dialog options

- **Embedding method / Normalization** -- how the 3D layout is computed (same choices as
  clustering).
- **Sampling** -- how cells are chosen under the budget:
  - *Stratified* (default) -- a global cell budget spread across clusters by abundance,
    with a per-class floor, drawn by seeded uniform-random sampling within each cluster.
  - *Representative sketch (geosketch)* -- a **density-aware** sketch that downsamples dense
    regions harder and keeps sparse/rare structure *within* clusters, preserving the shape
    of the cloud (algorithm vendored from geosketch, Hie et al. 2019; see [References](references.md#subsampling-and-sketching-vest-3d-export)).
- **Total cells (budget)** -- Low (~1,000, recommended) / Medium / High / Custom.
  Kept deliberately conservative: VEST draws one textured image per cell in WebGL, which is
  draw-call-limited, so very large exports get sluggish in the browser. A live estimate
  shows how many cells the current settings will export.
- **Min cells / cluster** (default 30) -- honored whenever that many cells exist, so a
  huge cluster cannot squeeze small clusters out of the view.
- **Crop scale** and, under **Advanced**, the random seed, UMAP neighbors/min_dist,
  t-SNE perplexity, and percentile clip bounds -- nothing is hard-coded.

### Opening it

Two ways, from the "export complete" dialog:

- **Open in VEST** -- QP-CAT builds a small, **isolated** Python environment the first time
  (`qpcat-vest`: Flask + pandas + VEST, a one-time ~165 MB download) and then starts VEST on
  the bundle. VEST **opens your browser automatically**. The environment is kept separate
  from the clustering environment on purpose, so VEST's pandas/numpy can never conflict with
  the scanpy/squidpy stack. Only one VEST viewer runs at a time; stop it with
  **Extensions > QP-CAT > Export > Stop VEST viewer** (it is also stopped automatically when you quit
  QuPath).
- **Open folder** -- open the export folder only; run VEST yourself with
  `pip install vision-embedding-space-travelling` then `vest embedding.csv --image-path ./images`.

### Notes

- This is a **one-way** export: VEST runs standalone in the browser and does not navigate
  back into QuPath. (For clicking a point and jumping to that cell *inside* QuPath, use the
  interactive results plots / 3D view instead.)
- Color-map by the `cluster` column in VEST's controls to see cluster structure.

---
