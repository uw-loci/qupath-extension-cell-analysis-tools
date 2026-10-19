# QP-CAT harness scenarios

Scenarios run by the shared harness in
[`tools/qp-harness`](../../tools/qp-harness/README.md). They live here, with the
code they test, because a scenario is a test; only the plumbing is shared.

```bash
cd ~/QPSC_Project

# once -- the slow step, real watershed cell detection
tools/qp-harness/bin/qp-dataset synthetic-tme /tmp/qph/project 3

# then, after ./gradlew shadowJar in this repo
tools/qp-harness/bin/qp-gui \
    qupath-extension-cell-analysis-tools/harness/QpcatExportAndGateScenario.java \
    qupath-extension-cell-analysis-tools \
    /tmp/qph/project /tmp/qph/out
```

## `QpcatTicksReopenScenario`

Reopens the Clustering dialog after a stored "All project images" run and checks the
measurement ticks came back. Run on 2026-10-19 against a jar of d6f211b it failed with the
image open and with none open (127 rows, 0 ticked); with 2b9c290 both pass. Note that a
dialog that stays open between runs never showed the bug -- only reopening does.

```bash
tools/qp-harness/bin/qp-gui \
    qupath-extension-cell-analysis-tools/harness/QpcatTicksReopenScenario.java \
    qupath-extension-cell-analysis-tools /tmp/qph/project open     # or: closed
```

## `QpcatValidationScenario`

Stability across seeds and agreement with known classes, end to end. Gives every cell its
true type from the dataset's ground truth (by centroid, 10 px; tumour split by Ki67, seven
classes), then clusters all images with both checks on -- KMeans k=7 and Leiden -- writing
the true types again before each run because a run replaces them.

```bash
tools/qp-harness/bin/qp-gui \
    qupath-extension-cell-analysis-tools/harness/QpcatValidationScenario.java \
    qupath-extension-cell-analysis-tools \
    /tmp/qph/project tools/cell-phantom-creation/output/all_groundtruth.csv
```

Asserts that both checks arrive, that six runs were compared, that compared plus excluded
cells is every cell and the excluded count is exactly the cells with no ground truth, that the
classes compared are the true types (not a previous run's clusters), and that the checks are
saved with the result. It prints the numbers quoted in `documentation/results.md`. Note that it
LEAVES THE PROJECT'S CELLS CLASSIFIED with the last run's clusters.

## `QpcatAutocorrProbeScenario`

Asks which per-marker spatial statistics a real clustering run actually
produces, through `ClusteringWorkflow.runProjectClustering`.

```bash
tools/qp-harness/bin/qp-gui \
    qupath-extension-cell-analysis-tools/harness/QpcatAutocorrProbeScenario.java \
    qupath-extension-cell-analysis-tools \
    /tmp/qph/project 2 6 zscore      # project, images, k, normalization
    # optional 5th arg: extra measurements to cluster on and test, ";"-separated
```

Written to settle a question reading could not. Moran's I and Geary's C are
requested by the same tick-box and computed a few lines apart in
`run_clustering.py`, each inside its own `except Exception` that only logs -- so
a statistic that never arrives is indistinguishable, in the results window, from
one nobody asked for. This asserts on the payloads.

It found that **Moran's I had never worked**: `Spatial autocorrelation failed:`
with an empty message, no tab, and the run otherwise reporting success. Keep it
as a regression probe -- it is the only thing here that would notice a statistic
quietly going missing again.

**Gotcha worth knowing:** a dense spatial graph makes the workflow raise a modal
"push the overlay anyway?" prompt, which a hidden GUI has nobody to answer, and
the run parks on its latch forever. The scenario sets
`config.setPushConnectionsToViewer(false)`. If a scenario of yours hangs after
"Applying results to project images...", take a `jstack`: the main thread parked
in `confirmOverlayPushBatch` is this.

It also prints both tabs as the user sees them (`AutocorrTables.moranTable` /
`gearyTable`) and, on a multi-image run, asserts the consistency count covers
every image.

### `InjectSingleImageGradient.groovy` -- a measurement with a known answer

```bash
tools/qp-harness/bin/qp-script \
    qupath-extension-cell-analysis-tools/harness/InjectSingleImageGradient.groovy \
    /tmp/qph/project 3            # project, index of the ONE image that gets the gradient
```

Adds `Nucleus: Probe mean` to every cell: i.i.d. noise everywhere except image 3,
where it is the cell's x position plus the same noise. So the ground truth is
"structured in exactly one image", which is what a staining or illumination
gradient on one slide looks like, and what the Spatial Autocorrelation tab's
Areas column exists to catch. Run the probe with the 5th argument
`"Nucleus: Probe mean"` afterwards. Measured 2026-10-11 on the 8-image synthetic
project: pooled I = 0.23, q < 1e-300 -- a p-value-led table would have ranked
it third in the cohort, above CD3 -- and the Areas column read **1 of 8**, with
the per-image grid showing 0.42 in `tme_03` and |I| < 0.03 everywhere else.
Work on a copy of the project; the script writes the measurement into it.

### `apply:` and `apply:ns:`

`apply:<result>` writes the result's labels back as bare `Cluster N`
(`applyRenamed` with no renames), which keeps a figure's legend short.
`apply:ns:<result>` uses the real `SavedResultApplier.apply` instead, which
namespaces every class with the result name
(`auto_20260927_015057_hdbscan: Cluster 0`) -- that is what a user gets, and it
is how to reproduce anything about long class names.

## `QpcatOpenImageWriteBackScenario`

A multi-image clustering run with one of its images open in the viewer. Asserts that
the viewer, the saved file and the image's "unsaved changes" flag agree afterwards.

```bash
tools/qp-harness/bin/qp-gui \
    qupath-extension-cell-analysis-tools/harness/QpcatOpenImageWriteBackScenario.java \
    qupath-extension-cell-analysis-tools \
    /tmp/qph/project      # MODIFIED: run on a copy with unclassified detections
```

Before the fix the run wrote to a detached copy of the open image: the viewer showed
`(none)` for all 1,530 cells of `tme_00` while the file held four clusters, and the image
was marked changed, so a save from the viewer erased the run. Three of five checks failed;
all five pass now.

## `QpcatExportAndGateScenario`

Covers the crop/feature-table export and the gate save / replay / apply path
against a real project: 3 synthetic images, 4,528 detected cells, 125
measurements per cell.

Neither is reachable from `./gradlew test`. `CropTableExporter` resolves image
servers and the viewer's display settings through a `QuPathGUI`, and
`GateApplier` writes classifications back across a project and saves each image.
A unit test can check the arithmetic; only this can check that the file a user
opens is correct.

**What it asserts** (40 checks):

- *Export* -- every rule in TraitHorizon's documented contract, on real output:
  `filename` first, unique column names, unique filenames, no empty cell
  anywhere, every value parses as a number, every row's crop actually on disk,
  `class_index` in range. Plus: the TSV has no byte-order mark and the CSV has
  one, crops decode as PNGs and are not uniform blanks, the README names the
  command and lists every classification, the budget is honoured, and the same
  seed writes a byte-identical table.
- *The failure message* -- exporting a measurement that is on no cell must name
  that measurement. This check exists because the first run of this scenario
  found the opposite.
- *Gates* -- a gate over the CD3/CD8 quadrant holds 471 cells; it saves, reloads,
  selects the same cells, reads as an EXACT match, is refused on a different Y
  measurement, and still matches when the same cells are re-pooled in another
  order. Then it assigns a class to all 471 across all 3 images with 0 unmatched,
  and the classification is confirmed **by re-reading the project from disk**.

**What it found.** On its first run, against real measurements, the export
failed with *"No cell carried every chosen measurement"* and a coverage list led
by five measurements at 100%, truncated before reaching the one at 0%. The
message named nothing that explained the failure. `coverageSummary` now reports
worst-first and calls out a measurement that is on no cell as its own case. That
defect was invisible to 21 unit tests because they all passed measurements that
existed.

## `QpcatDialogShotScenario`

Renders QP-CAT windows to PNG so a documentation figure is as current as the
build that produced it.

```bash
tools/qp-harness/bin/qp-gui \
    qupath-extension-cell-analysis-tools/harness/QpcatDialogShotScenario.java \
    qupath-extension-cell-analysis-tools \
    <projectDir> <outDir> \
    "apply:auto_20260927_015057_hdbscan" \
    "my-figure=auto_20260927_015057_hdbscan=Marker Fingerprints=1600x790"
```

Three kinds of spec:

| Spec | What it does |
|---|---|
| `crop-table` | opens the crop / feature-table export dialog |
| `apply:<savedResult>` | writes that result's cluster labels onto the detections first |
| `<figure>=<savedResult>=<tab>[=<w>x<h>]` | reopens a saved result and snapshots one tab |

Saved-result names are passed in rather than hard-coded, because which run
backs which figure belongs to the document, not to this repo.

**What `apply:` found, and what it is for now.** Reopening a saved result used
to leave the **3D View** reading the project's *current* classifications, so its
CLASSES list described whatever the cells happened to carry while the title bar
named the result you had just opened. On a project where a later run had been
applied to only some images, that list showed six ground-truth cell types and
four `Cluster N` labels side by side.

That is fixed: the tab is now handed the result's own grouping
(`ResultApplier.labelLookup`), so it shows the result whatever the cells carry.
Verified by opening a 6-cluster result on cells classified by a 7-cluster run
and watching the panel report the 6-cluster counts.

`apply:` therefore no longer changes what a results tab shows. Keep it when the
figure should also reflect the project state -- the viewer overlay, or a tab
that genuinely reads the hierarchy.

**A second thing it found, still open.** The menu's own *Apply saved QP-CAT
result to detections* namespaces every applied class with the result name, so
that two results can coexist on the same cells. In the 3D View's narrow CLASSES
column that renders as seven identical truncated rows with the counts pushed out
of sight. `apply:` works around it with `SavedResultApplier.applyRenamed` and no
renames, which writes bare `Cluster N` -- the state a finished run leaves
behind. The column itself has not been fixed.

## `QpcatSpatialShotScenario`

Runs the post-hoc spatial statistics over a whole project and renders the
summary window plus one image's Ripley L tab.

```bash
tools/qp-harness/bin/qp-gui \
    qupath-extension-cell-analysis-tools/harness/QpcatSpatialShotScenario.java \
    qupath-extension-cell-analysis-tools \
    <projectDir> <outDir> <savedResult> "Cluster 1,Cluster 6" tme_00.tif
```

A spatial run leaves no session on disk -- `qpcat/spatial_stats/` records what
was run, not something to reopen -- so these two figures can only be as current
as a real run. It takes its labels from a saved clustering result rather than
from the live classifications, which keeps the figure tied to a named run.

**It needs the Python worker, and nothing starts it.** A hidden `QuPathGUI`
loads the extension and adds its menus, but the Appose service is initialized
from the extension's installation hook, which does not run here. Without
`ApposeClusteringService.getInstance().initialize(...)` every image is skipped
with *"QPCAT service is not available"* -- and the run still reports a tidy
"8 windows" because a skip is a result object, not an exception.
