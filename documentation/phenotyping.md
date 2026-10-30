# Phenotyping

Labeling cells by marker rules instead of by clustering. Use it when you already
know the cell types you are looking for and can express them as marker gates;
use [clustering](clustering.md) when you want the data to tell you what is there.

## Choosing between phenotyping and clustering

### Unsupervised first, then supervised

A recommended approach:
1. Run **unsupervised clustering** first to understand the data
2. Use the **heatmap and marker rankings** to identify biological populations
3. **Rename clusters** to biological names (via **Results & populations > Modify cell populations**)
4. OR define **phenotype rules** based on what you learned about marker distributions

### Rule design

- **Start broad, then refine** -- define a few major types first, then add subtypes
- **Use the histogram** to verify that positive/negative populations are separable for each marker
- **Rule order matters** -- more specific rules should come first (first match wins)
- **Not all cells need a rule** -- unmatched cells are classified as "Unknown"

### Gate selection

- Use **auto-thresholding** as a starting point, then fine-tune
- The **Triangle method** works best for markers with a large negative population (skewed right)
- **GMM** works best for clearly bimodal distributions, including a spike of zeros plus a
  positive population (the zeros are left out of the fit)
- **Gamma** works best for strictly positive, right-skewed markers
- Always visually verify gate positions using the histogram

---
## Writing good rules

Rule-based phenotyping assigns cell types by gating on marker intensities -- you define
which markers (and thresholds) characterize each phenotype.

### When to use rule-based phenotyping

- **Well-characterized multiplexed panels** -- when you know exactly which markers define each cell type
- **Reproducibility** -- rules are deterministic and produce identical results on the same data
- **Publication-ready analysis** -- reviewers expect defined gating strategies with explicit thresholds
- **Fine-grained control** -- rule order, per-marker thresholds, and positive/negative logic give precise control
- **Large panels (>10 markers)** -- rule-based gating scales well with many markers

For unsupervised discovery when you don't yet know the phenotypes, use
[clustering](clustering.md) (and, to suggest names for the clusters, the
[Cluster Explainer](llm-explainer.md)) instead.

---
## Running phenotyping

Classify cells into biological types based on marker expression thresholds.

**Supported image types:** any image where cells already carry per-channel intensity
measurements -- multiplex IF, conventional fluorescence, brightfield/IHC (e.g. DAB), and
H&E (after stain separation). This tool reads **measurements, not pixels**, so it is
modality-independent; run cell detection first so the measurements exist.

**Reading the marker columns:** each column is one selected measurement, labeled with its
channel **and statistic** (e.g. `PCNA Mean` vs `PCNA Median`) so measurements of the same
channel are distinguishable, with the marker in bold ("Cell **CD3** mean") -- hover or click a
header for the full QuPath name and its histogram. A cell is **pos** for a marker when its (normalized) value is `>=` that marker's
gate and **neg** when below; `--` ignores the marker. The gate is the spinner beneath each
marker name, and it shows up to four decimals, so a small gate such as 0.0045 reads as itself
rather than rounding to 0.

**Gate range depends on normalization:** Min-Max / Percentile scale values to `[0, 1]` (gate
0.0-1.0, 0.5 = midpoint); Z-score centers at 0 in SD units (gate -3.0 to 3.0, 0 = mean;
a suggestion outside that range is clipped to the nearest end when it is applied); None keeps
raw measurement units. The banner under **Normalization** and **Default gate** states the
active range. Normalization starts on **Min-Max** with a default gate of 0.5, the midpoint
between the lowest and highest value in scope; a single bright outlier shifts it, which is why
Percentile is the more robust choice. Use **Compute Thresholds** + **Apply to All Markers** to
set data-driven gates instead.

**A gate is relative to the cells it was set on.** Values are normalized over the cells in
scope, so the same rule set run on a different set of images applies the same number to a
different scale; check the histogram again when you change the images. Click **Save Rules...**
before you run: the rule file (normalization, markers, gates, rules) is the only record of
your gates, because the audit log records how many rules ran, not what they were.

### Step by step

1. **Extensions > QP-CAT > Classify cells > Label cells by marker rules (phenotyping)...**
2. **Choose the scope** at the top -- **Current image** (the default),
   **All project images**, or **Specific images...** (click **Choose images...**
   for the subset picker with name + metadata filters and checkboxes; see
   [Clustering](clustering.md#clustering-several-images-together)). Across multiple images the
   same rules and gates are applied to all of them; cells from the chosen images
   are normalized together (global gating, so a `pos` threshold means the same
   thing across them), labels are written back, and each image is saved. The
   panel must be consistent across images. **Compute Thresholds** also pools the
   cells of the chosen scope (all / subset), so the histograms and auto-gates you
   see are computed on the same distribution the run will gate on -- not just the
   open image.
3. **Select markers** from the measurement list
   - These should be biologically meaningful markers (e.g., CD3, CD8, CD20, PanCK)
   - Use **Select 'Mean' only** (or **Select 'Median' only** if your measurements are medians) then deselect irrelevant markers
4. **Set normalization** -- determines how marker values are scaled before gating
   - Min-Max or Percentile recommended for gating (values in [0,1] range)
   - The "Default gate" spinner sets the initial gate for all markers
5. **Set per-marker gates** -- each marker column header has a spinner
   - Values represent the positive/negative threshold for that marker
   - You can drag the red threshold line on the histogram (see [Auto-Thresholding](#auto-thresholding))
6. **Define rules** -- click **Add Rule**; each row is a phenotype. Type its name in **Cell
   Type** (e.g., "CD8+ T Cell"). Each marker column then takes one of six states:
   - **`pos`** -- the cell must be at/above this marker's gate
   - **`neg`** -- the cell must be below this marker's gate (this is what makes a rule
     *exclusive* -- see "How matching works" below)
   - **`anypos`** -- at least ONE of the markers marked `anypos` in this row must be
     at/above its gate. All the `anypos` markers in a row form a single OR group, which
     then ANDs with the row's other conditions. This is how you write "Macrophage = CD68
     **or** CD163 **or** CD206" as one row instead of three rows sharing a name.
   - **`anyneg`** -- the same, for at least one marker below its gate
   - **`ignore`** -- this marker is deliberately not used in the rule
   - **`--`** -- *unselected* (the default). Behaves like `ignore`, but because it may
     just mean "not decided yet", Run Phenotyping will prompt you to confirm any columns
     still left as `--`. Set them to `ignore` to make the choice explicit (and silence the
     prompt).
   - Example: CD8+ T Cell = CD3: `pos`, CD8: `pos`, CD20: `neg`, everything else `ignore`
7. **Rule order matters** -- rules are evaluated top-to-bottom, first match wins
   - Use **Move Up** / **Move Down** to reorder
   - Place more specific rules above more general ones
8. Click **Run Phenotyping**
9. Results dialog shows phenotype counts and distributions

### How matching works (read this before writing rules)

- **First match wins.** Rules are checked top-to-bottom; each cell takes the **first**
  rule it satisfies. A cell that would satisfy several rules is assigned the **topmost**
  one -- it is **not** marked "Unknown" for matching more than one rule.
- **A rule is an AND of its conditions.** Every `pos`/`neg` you set in a row must hold
  *simultaneously* for a cell to match that row. Markers left as `--` are ignored.
- **`anypos` / `anyneg` are an OR *within* the group, ANDed with everything else.** A row
  reading `CD45: pos, CD68: anypos, CD163: anypos` means "CD45 positive AND (CD68 or
  CD163) positive". A row with no `anypos` markers imposes no such condition; a row whose
  only `anypos` markers are absent from the data matches nothing (rather than matching
  everything).
- **"neg" on the other markers makes a rule exclusive.** This is the most common
  surprise. If `markerA+` is defined as `A: pos, B: neg, C: neg`, then a cell that is
  positive for **both** A and B matches **neither** `markerA+` (B is not neg) **nor**
  `markerB+` (A is not neg) -- so it becomes **"Unknown"**. With co-expressing cells and
  many markers, this can send the majority of cells to Unknown.
- **To label every cell positive for a marker** as that phenotype, set **only** that
  marker to `pos` and leave the rest as `--` (then rely on rule order for priority). Add
  `neg` conditions only when you specifically want to *exclude* co-expressing cells.
- **Diagnosing Unknowns.** After a run, the results summary breaks the Unknown bucket
  into *negative for all rule markers*, *positive for exactly one marker*, and *positive
  for >= 2 markers*. A large "positive for >= 2 markers" count is the signature of
  over-strict rules: your `neg` conditions are rejecting co-expressing cells. If you see
  this, relax the `neg` conditions to `--`.

### Example rule set for an immune panel

| Cell Type | CD3 | CD8 | CD4 | CD20 | PanCK |
|-----------|-----|-----|-----|------|-------|
| CD8+ T Cell | pos | pos | -- | neg | neg |
| CD4+ T Cell | pos | neg | pos | neg | neg |
| B Cell | neg | -- | -- | pos | neg |
| Tumor | neg | -- | -- | neg | pos |

Here the `neg` columns are deliberate: a CD8+ T cell is required to be CD20-negative and
PanCK-negative. If instead you just want "any CD3+CD8+ cell," drop those `neg`s to `--`.

---
## Auto-thresholding

Automatically compute marker gate thresholds instead of setting them manually.

1. In the Phenotyping dialog, select your markers
2. Expand the **Histogram & Auto-Thresholding** section
3. Click **Compute Thresholds**
4. Click any marker column header to view its histogram
   A marker with fewer than 10 finite values (the **Min Valid Values for Threshold**
   preference) gets no histogram or suggestion.
5. The histogram shows:
   - Blue bars (below threshold) and red bars (above threshold)
   - A red dashed line at the current threshold
   - Statistics: "Pos: X (Y%) | Neg: Z (W%)" -- the cells at or above the threshold,
     which is how phenotyping gates them. They are counted from the distribution of the
     values, not bar by bar, so they are right to within 0.1% of the cells even when the
     threshold falls inside a bar
6. Change the **Method** dropdown from **Manual** (the default) to apply an auto-threshold:
   - **Triangle** -- geometric method, good for skewed distributions
   - **GMM (Gaussian)** -- 2-component mixture model, good for bimodal data. The two fitted
     components are drawn on the histogram, in the same cells-per-bar units as the bars. A
     spike of identical values at the end of the range is left out of the fit; when it would
     flatten the curves, the axis zooms to them and the plot says *axis zoomed*, so the spike's
     bar is cut off at the top
   - **Gamma** -- gamma distribution fit, good for strictly positive markers
7. You can drag the red threshold line with the mouse for fine-tuning (it moves in steps of
   0.001), or type a value into the **Threshold** box (up to four decimals, the precision the
   suggestions are computed to)
8. Click **Apply to All Markers** to set all gates using the selected method. A notification
   confirms it, e.g. "Applied 'gmm' thresholds to 6 markers."; with **Manual** selected there is
   nothing to apply, and the dialog asks you to pick a method first

### Preview a gate on the image

Tick **Show on the open image** under the histogram to see the gate on the cells themselves,
before running anything. Each cell of the open image is colored by which side of the gate it
falls on, in the histogram's colors, red at or above the gate and blue below it, and the colors
follow the red line while you drag it. The line under the checkbox gives the count for that image.

![Three panels of the same tissue with the marker PanCK at a gate of 0.04, 0.32 and 0.80. At 0.04 unstained cells are outlined red, at 0.32 the red outlines match the bright cells, and at 0.80 most bright cells are outlined blue. Each panel has its histogram with two fitted curves and the positive and negative counts below.](images/threshold-preview.png)

*The same synthetic image at three gates for PanCK. Too low calls unstained cells positive; too
high misses most of the stained ones.*

- **Nothing is written to the cells.** Classifications stay as they are until you click
  **Run Phenotyping**.
- **QuPath's detections are hidden while the preview is on.** QuPath draws them over anything
  an extension draws, so they would cover it. They come back as they were when you untick the
  box or close the dialog. Annotations stay visible.
- **The preview matches the run.** It reads the same cells and applies the same normalization,
  using the numbers from **Compute Thresholds**. Checked against real runs: the positive count
  was identical for all four normalizations, on one image and on a pooled scope.
- **It shows the marker and the line in the histogram,** one marker at a time, and not the
  whole rule. With a **Method** selected, that can be a suggestion you have not applied yet.
- **Dots or outlines.** With more than 5,000 cells in view, each cell is a dot; zoom in and they
  become cell outlines, so you can compare them with the staining. Choose **Positive only** or
  **Negative only** to see one side. Cells with no value for the marker are gray, and are counted
  as *No value*.
- **Change the scope or the normalization, and the thresholds clear.** So does opening another
  image under **Current image**. The preview then asks you to click **Compute Thresholds** again,
  because the gate would no longer mean what the run will do with it. Under **All project images**
  or **Specific images...** you can open any image to check the shared gate on it; an image the
  run does not cover says so.

The histogram's count is for all the cells in the scope. The preview's count is for the open image
only, and it is exact, so on a single image the two can differ by up to 0.1% of the cells.

| Method | What it fits | Where the threshold goes | Source |
|---|---|---|---|
| **Triangle** | scikit-image `threshold_triangle` on the values left after dropping end spikes | the triangle rule on the histogram | [Zack et al. 1977](references.md#triangle-threshold-method) |
| **GMM (Gaussian)** | a two-component Gaussian mixture (`random_state=42`, up to 200 iterations) on the same values | where the two weighted densities cross between the means; the midpoint of the means if they do not cross | [References](references.md#phenotyping-and-gating) |
| **Gamma** | one gamma distribution (location fixed at 0) on every value, shifted to be positive | the mode plus 1 standard deviation of that gamma (when the fitted shape is 1 or less the mode is 0) | inspired by [GammaGateR](references.md#gammagater-gamma-distribution-gating), which fits a gamma *mixture*; QP-CAT's single-gamma rule is a different, simpler method |

The Phenotyping preferences (**Edit > Preferences > QP-CAT: Run Phenotyping**) set **Histogram
Bins** (50), **Min Valid Values for Threshold** (10), **GMM Max Iterations** (200) and **Gamma
Threshold Std Multiplier** (1.0).

![The Histogram & Auto-Thresholding section of the Phenotyping dialog for Cell CD3 mean on image tme_01 with Percentile scaling. Most cells sit in one tall bar at 0 (1067 cells); a long, low tail of red bars runs up to 1.00. The GMM (Gaussian) method is selected, the red dashed threshold line sits at 0.129 just past the spike, the Threshold box reads 0.1291, and the counts under it read "Pos: 384 (24.5%) | Neg: 1184 (75.5%)"](images/phenotyping-histogram-gmm.png)

**A suggestion that classifies more than 95% or fewer than 5% of cells as positive is flagged** in
orange under the counts, e.g. *"GMM (Gaussian) suggestion: Calls 100.0% of cells positive -
check it against the histogram."* **Apply to All Markers** lists the markers it flagged. A
flag is not always wrong (a marker can be truly rare, and a marker absent from an image puts
every cell at the same value, which no threshold can split), but it is the first thing to check.

**Cells with no signal.** Background-subtracted intensities often put many marker-negative
cells at exactly 0 (851 of 1,568 cells for CD3 on the synthetic image tme_01), which draws as
one tall spike at the left of the histogram. Triangle and GMM leave that spike out (and the
spike of values clipped to 1 by *Percentile* scaling) and fit the rest. Cells on the low spike
fall below any suggested threshold; cells clipped to 1 sit above it. An end of the range counts
as a spike when at least 2 cells and at least 0.5% of cells sit exactly on it, unless fewer
than 10 values would remain; a spike of 0.4% of cells stays in the fit. Fitted with the spike included,
both would put the threshold at its edge: on the synthetic TME dataset (image tme_01, *Cell: CD3
mean*, Percentile scaling, 1,568 cells of which 24.3% are T cells by ground truth) GMM
suggested 0.0045 and Triangle 0.0059 that way, marking 37.8% and 37.2% of cells positive.
Leaving the spike out, they suggest 0.129 (24.5% positive, the figure above) and 0.033
(30.2%); Gamma, which still fits every value including the spike and whose method did not
change, gives 0.361 (24.0%). Triangle still tends to mark too many cells positive when a dim
shoulder sits next to the spike, so check it against GMM and Gamma.

---
