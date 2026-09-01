# Phenotyping

Labelling cells by marker rules instead of by clustering. Use it when you already
know the cell types you are looking for and can express them as marker gates;
use [clustering](clustering.md) when you want the data to tell you what is there.

## Choosing between phenotyping and clustering

### Unsupervised First, Then Supervised

A recommended approach:
1. Run **unsupervised clustering** first to understand the data
2. Use the **heatmap and marker rankings** to identify biological populations
3. **Rename clusters** to biological names (via Manage Clusters)
4. OR define **phenotype rules** based on what you learned about marker distributions

### Rule Design

- **Start broad, then refine** -- define a few major types first, then add subtypes
- **Use the histogram** to verify that positive/negative populations are separable for each marker
- **Rule order matters** -- more specific rules should come first (first match wins)
- **Not all cells need a rule** -- unmatched cells are classified as "Unknown"

### Gate Selection

- Use **auto-thresholding** as a starting point, then fine-tune
- The **Triangle method** works best for markers with a large negative population (skewed right)
- **GMM** works best for clearly bimodal distributions
- **Gamma** works best for strictly positive, right-skewed markers
- Always visually verify gate positions using the histogram

---
## Writing good rules

Rule-based phenotyping assigns cell types by gating on marker intensities -- you define
which markers (and thresholds) characterize each phenotype.

### When to Use Rule-Based Phenotyping

- **Well-characterized multiplexed panels** -- when you know exactly which markers define each cell type
- **Reproducibility** -- rules are deterministic and produce identical results on the same data
- **Publication-ready analysis** -- reviewers expect defined gating strategies with explicit thresholds
- **Fine-grained control** -- rule order, per-marker thresholds, and positive/negative logic give precise control
- **Large panels (>10 markers)** -- rule-based gating scales well with many markers

For unsupervised discovery when you don't yet know the phenotypes, use **clustering** (and
optionally the LLM cluster explainer, below) instead.

---
## Running phenotyping

Classify cells into biological types based on marker expression thresholds.

**Supported image types:** any image where cells already carry per-channel intensity
measurements -- multiplex IF, conventional fluorescence, brightfield/IHC (e.g. DAB), and
H&E (after stain separation). This tool reads **measurements, not pixels**, so it is
modality-independent; run cell detection first so the measurements exist.

**Reading the marker columns:** each column is one selected measurement, labelled with its
channel **and statistic** (e.g. `PCNA Mean` vs `PCNA Median`) so measurements of the same
channel are distinguishable -- hover or click a header for the full QuPath name and its
histogram. A cell is **pos** for a marker when its (normalized) value is `>=` that marker's
gate and **neg** when below; `--` ignores the marker. The gate is the spinner beneath each
marker name.

**Gate range depends on normalization:** Min-Max / Percentile scale values to `[0, 1]` (gate
0.0-1.0, 0.5 = midpoint); Z-score centers at 0 in SD units (gate -3.0 to 3.0, 0 = mean);
None keeps raw measurement units. The banner above the gate spinner always states the active
range -- 0.5 is the principled midpoint default for Min-Max, not an arbitrary value. Use
**Compute Thresholds** + **Apply to All Markers** to set data-driven gates instead.

### Step-by-step:

1. **Extensions > QP-CAT > Classify cells > Label cells by marker rules (phenotyping)...**
2. **Choose the scope** at the top -- **Current image** (the default),
   **All project images**, or **Specific images...** (click **Choose images...**
   for the subset picker with name + metadata filters and checkboxes; see
   [chapter 4](clustering.md#clustering-several-images-together)). Across multiple images the
   same rules and gates are applied to all of them; cells from the chosen images
   are normalized together (global gating, so a `pos` threshold means the same
   thing across them), labels are written back, and each image is saved. The
   panel must be consistent across images. **Compute Thresholds** also pools the
   cells of the chosen scope (all / subset), so the histograms and auto-gates you
   see are computed on the same distribution the run will gate on -- not just the
   open image.
3. **Select markers** from the measurement list
   - These should be biologically meaningful markers (e.g., CD3, CD8, CD20, PanCK)
   - Use **Select 'Mean' only** then deselect irrelevant markers
3. **Set normalization** -- determines how marker values are scaled before gating
   - Min-Max or Percentile recommended for gating (values in [0,1] range)
   - The "Default gate" spinner sets the initial gate for all markers
4. **Set per-marker gates** -- each marker column header has a spinner
   - Values represent the positive/negative threshold for that marker
   - You can drag the red threshold line on the histogram (see [Auto-Thresholding](#auto-thresholding))
5. **Define rules** -- each row is a phenotype. Every marker column has six states:
   - **Cell Type**: name for this phenotype (e.g., "CD8+ T Cell")
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
6. **Rule order matters** -- rules are evaluated top-to-bottom, first match wins
   - Use the up/down arrows to reorder
   - Place more specific rules above more general ones
7. Click **Run Phenotyping**
8. Results dialog shows phenotype counts and distributions

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

### Example rule set for immune panel:

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
