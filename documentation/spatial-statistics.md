# Spatial statistics

Measuring how cells are arranged, not just what they express. Every statistic here
runs on a **spatial neighbour graph** -- how that graph is built matters as much as
the statistic you pick, so read [the graph](spatial-neighborhoods.md#the-spatial-graph) too.

- [Which statistic answers which question](#which-statistic-answers-which-question)
- [Running them with a clustering](#running-them-with-a-clustering)
- [Running them on existing clusters](#running-them-on-existing-clusters)

## Which statistic answers which question

Ripley's K and L, Geary's C, co-occurrence (pairwise and one-vs-rest), Moran's I and
neighborhood enrichment all answer different questions. Picking the right one is the
difference between a credible figure and a noisy table.

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
## Reading the results

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
## Running them with a clustering

Beyond the default neighborhood enrichment + Moran's I, QP-CAT v1 exposes the rest of squidpy's standard spatial-statistics catalog: Ripley's K and L, Geary's C, and co-occurrence (pairwise + one-vs-rest). Each is driven by a single graph constructor you pick once at the top of the dialog; the same graph backs spatial feature smoothing (when the preference is enabled) so the parameters are visible and consistent across the run. QP-CAT's v1 catalog closes the gap with [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s spatial-stats surface while keeping the squidpy backend the extension already ships with -- no new dependencies.

> These statistics use permutation testing and can be slow on large slides. Before clustering is submitted, a dialog estimates the computation time and lets you proceed, skip, or cancel. Estimates under 2 minutes do not show a prompt. For longer estimates, the dialog waits 60 seconds for your choice; if you leave it unattended, it automatically proceeds rather than stalling the run. For a fast, scalable way to map recurring tissue micro-environments instead, see [chapter 22 -- Finding Cellular Neighborhoods](spatial-neighborhoods.md).

### When to use each statistic

- **Neighborhood enrichment** -- *cluster-to-cluster*: "do CD8 T cells and tumor cells tend to be neighbors, avoid each other, or scatter randomly?" Cheap, no permutation cost.
- **Ripley's K** -- *cluster-to-cluster at a range of distances*: "are CD8 cells within 50 microns of tumor cells more often than chance would predict? What about within 200 microns?" K(r) is the cumulative count of neighbors within distance r, normalised by cluster density. K above the Poisson null = clustering / co-localization; below = dispersion / avoidance.
- **Ripley's L** -- the variance-stabilised transform of K. `L(r) = sqrt(K(r) / pi) - r`. Read L instead of K when comparing across radii (L is centred at 0 under the Poisson null at every r). If you can only show one plot in a figure, show L.
- **Geary's C** -- per-marker spatial autocorrelation, dual to Moran's I but weighted toward *local* differences. Use Geary's C when you suspect a marker is structured at short range (sharp tissue boundaries, immune infiltrates) and Moran's I when the structure is global.
- **Co-occurrence (pairwise)** -- *radius profile* for every cluster pair: "as we expand the radius from r1 to r2, how does the probability of finding a cluster-B cell near a cluster-A cell change?"
- **Co-occurrence (one-vs-rest)** -- the same radius profile but with "all other clusters" collapsed into a single comparison. Useful when a single cluster is what you care about.

### Graph constructor choice

All four spatial stats need a definition of "neighbor". v1 surfaces three options:

- **kNN (default, k = 15)** -- robust to varying cell density; every cell has exactly k neighbors. Recommended starting point. Pick k = 10-20 for typical multiplexed-imaging tissue.
- **Radius** -- pick this when the biology has a natural distance scale. Example: 20 microns captures immune-synapse-level contacts; 50-100 microns captures niche-level relationships.
- **Delaunay** -- a parameter-free triangulation. Use when you want the graph topology to follow tissue geometry. Optional max-edge pruning drops edges longer than a given distance.

**Default for first-pass exploration:** kNN at k = 15. Switch to Radius once you understand your data's cell density and have a biologically motivated distance.

### BANKSY uses its own graph

BANKSY is excluded from the kNN/Radius/Delaunay constructor in v1. BANKSY's pybanksy implementation builds its own neighbor model internally and does not expose a "bring your own graph" hook. If you select BANKSY as the clustering algorithm and also enable the new spatial stats, the stats use the constructor you picked at the top of the dialog (independent of BANKSY's `k_geom`).

### Adaptive permutation defaults

Ripley K/L, Geary's C, and co-occurrence use permutation tests for significance. v1 picks the permutation count automatically based on cell count:

| Cells | Permutations | Notes |
|---|---|---|
| <= 50k | 1000 | Matches squidpy's literature default |
| 50k - 500k | 100 | Multi-image projects; minutes per stat at this count |
| > 500k | 50 | Very large projects; effects must be strong to clear significance |

Override via **Edit > Preferences > QP-CAT: Run Clustering > Spatial Stats Permutations** (positive integer to force, 0 to leave at adaptive). The audit-log row for each statistic records the value actually used.

### Step-by-step

1. Open an image (or project) with cell detections
2. **Extensions > QP-CAT > Find cell populations (clustering)...**
3. Configure measurements, normalization, algorithm as usual
4. In the **Analysis options** group:
   - Optionally check **Neighborhood enrichment + Moran's I** (the v0 cheap checkbox)
   - Expand the **Spatial statistics** group
   - Pick a graph type (**kNN** / **Radius** / **Delaunay**) and the matching parameter
   - Tick any of: **Ripley K and L**, **Geary's C**, **Co-occurrence -- pairwise**, **Co-occurrence -- one vs rest**
5. (Optional) Check **Spatial feature smoothing** as a pre-clustering pass
6. Click **Run Clustering**
7. In the results dialog, navigate to the new tabs:
   - **Ripley K and L** -- side-by-side line charts with Poisson null overlay (stacked vertically below ~700 px width)
   - **Geary's C** -- per-marker table with C, p-value, permutation count
   - **Co-occurrence (pairwise)** -- per-pair table indexed by radius
   - **Co-occurrence (one vs rest)** -- per-cluster table indexed by radius

After the run completes, see [Chapter 21 -- Spatial graph overlay](spatial-neighborhoods.md) for how to view the underlying graph in the QuPath viewer.

### What gets logged

Each enabled statistic logs its own audit-log row (`SPATIAL STATS RIPLEY`, `SPATIAL STATS GEARY`, `SPATIAL STATS COOC PAIRWISE`, `SPATIAL STATS COOC ONE-VS-REST`) plus one `SPATIAL GRAPH` row for the graph build under your project's `qpcat/logs/qpcat_YYYY-MM-DD.log`. Each row records the method name, graph constructor + parameters, permutation count, and a short result summary.

### Matplotlib PNG output

When **Edit > Preferences > QP-CAT: Run Clustering > Spatial Stats: Save Matplotlib PNGs** is enabled (the default), each spatial statistic that runs also writes a PNG alongside the existing clustering plots under `<project>/qpcat/cluster_results/<result_name>_plots/`:

- `ripley_k_l.png` -- two-panel K and L plot with Poisson null overlays
- `geary_c.png` -- per-marker bar chart with C = 1 null reference line
- `co_occurrence_pairwise.png` -- square cluster x cluster heatmap (mean over radius)
- `co_occurrence_one_vs_rest.png` -- cluster x radius heatmap

These are picked up by the Multi-Figure Batch Export dialog so they can be exported alongside the other clustering plots. Disable the preference to keep the in-dialog charts but skip the savefig step.

For the programmatic Groovy API see [`SpatialGraphScripts`](scripting.md#spatialgraphscripts) (graph construction) and [`SpatialStatsScripts`](scripting.md#spatialstatsscripts) (Ripley / Geary / co-occurrence / Moran's I / neighborhood enrichment).

---
## Running them on existing clusters

**Menu: Extensions > QP-CAT > Explore & spatial > "Spatial statistics on existing clusters..."**

Run the spatial-statistics suite over cells that **already** carry a
classification (from clustering, phenotyping, or any classifier) **without
re-running clustering or embedding**. This is the tool for "I already have my cell
types -- now test spatial hypotheses, per image and per region across the project."

**Each analysis area is computed independently** -- with its own spatial graph.
An area is a whole image, one TMA core, one tissue section, or a single selected
annotation. Cells from different images or different areas are never joined into
one graph (that would create false neighbors). This is why comparison is
*per-area across the project*, not one pooled result.

> **One word for one thing.** This workflow used to call these "windows" and
> "regions". They are the same **areas** the clustering and neighborhood workflows
> use, produced by the same shared control, and the long-format CSVs of both now
> share a header so they can be concatenated. In QP-CAT "window" now means only
> the per-cell neighborhood window in [Find Cellular Neighborhoods](spatial-neighborhoods.md),
> which is a different thing: a window is drawn around one cell, an area is a piece
> of tissue.

**Label source.** Choose *Current cell classifications* (reads each cell's
PathClass) or *Saved QP-CAT result...* -- the latter matches the saved result's
cluster labels to cells in memory (by image id + centroid) and **does not write
PathClasses**, so you can analyze a saved result's labels without modifying the
hierarchy. **If the saved result has renamed or merged clusters, the spatial stats
are keyed by those display names** (e.g. "CD8+ T Cell" rather than "Cluster 3")
in the results tabs and in any plots the run persists. Merged clusters share one
key, so they are analyzed as the single population the merge made them.

**Scope.** Current image / all project images / a chosen subset (via the standard
scope control). Every image in the scope is analyzed independently.

**Analysis areas.**

The dialog uses the shared **Independent areas** control (the same one used by
the clustering and neighborhood workflows) to partition cells into separately-analyzed
pieces. Two modes:

- *Independent areas configured* -- the hierarchy levels you specify (e.g., TMA cores,
  tissue sections) partition the cells. Each area gets its own spatial graph, and no
  graph ever crosses an area boundary. See the *Independent areas* section below.
- *No independent areas* -- the **"If no areas are configured:"** dropdown decides:
  *Whole image* (every cell in an image is one area) or *Selected annotations (current
  image)* (one area per annotation you have selected). Adding an area level overrides
  this, so the dropdown is disabled while any level is configured.

Annotations define areas **only** -- detections are never reparented, so a cell can
belong to several areas. Unclassified (null-class) cells are excluded.

**Independent areas.**

By default, each image is analyzed as one piece. You can partition cells **below**
the image level by configuring **Independent areas**. Each area gets its own spatial
graph (no graph ever crosses an area boundary), and the results table groups outputs
per area instead of per image. When to use this:

- **TMA slides:** separate each core into its own window.
- **Multi-section slides:** each tissue section analyzed independently.

To configure:

1. Expand the **Independent areas** section.
2. Click **Add level** to define one partitioning level (e.g., `Tissue` annotations
   to mark cores or sections).
3. The preview shows how many areas were found and flags any cells with no assignment.
   Empty areas are skipped; sparse areas are handled (spatial graph parameters are
   capped per area so one small core does not reduce k for the rest).
   **TMA cores flagged missing are skipped too**, and the preview says how many --
   so a ragged grid does not fill the export with blank rows. Detections inside a
   missing core are still analysed, but land in `(unassigned)`.
4. For multi-image scope, the same area levels are applied to every image. A cell
   belongs to an area if it **falls inside** that region -- matched geometrically
   from its centroid, never from parent links, and always read-only.

**Exclusions.** Under "Exclude cells inside annotation classes", tick classes
(e.g. `Ignore*`, `Necrosis`) to drop cells inside those regions per image.

**Graph + statistics.** Pick the neighbor graph (kNN / radius / Delaunay) and its
parameter, the permutation count (0 = adaptive), and which statistics to run:
- **Ripley K / L**, **Co-occurrence** (pairwise / one-vs-rest), **Neighborhood
  enrichment** -- from the labels + positions.
- **Geary's C**, **Moran's I** -- from the cells' marker measurements (coordinate /
  spatial / embedding / cluster columns are filtered out; zero-variance columns are
  dropped; skipped if no suitable measurements remain).

**Results.** A single window opens the standard Results window (spatial tabs). Many
areas open a **summary table** -- one row per area, with an "Open" button to
drill into each window's full result and a "Save combined CSV" export. Each run is
also auto-saved (a long-format CSV + a metadata JSON linked to the source result +
per-area ROI identity) under `<project>/qpcat/spatial_stats/`. Nothing is written
to the object hierarchy -- this is read-only.

**Interpretation caveats (important):**
- **Ripley K/L** on an irregular annotation uses a bounding-box intensity and an
  unbounded-plane null with no edge correction, and graph neighbors are truncated
  at the ROI edge. Treat K/L as valid only at radii small relative to the window,
  and do **not** compare K/L across areas of different size/shape.
- **Co-occurrence** is a descriptive ratio -- there is **no** significance test.
- Distances are reported in **microns** for calibrated images (radius/Delaunay
  inputs, Ripley radii, co-occurrence intervals, and distances are all in um), which
  makes areas comparable across images. Uncalibrated images fall back to pixels
  (shown in the summary table's Unit column).
- Very small areas / classes give unstable permutation statistics.

---
