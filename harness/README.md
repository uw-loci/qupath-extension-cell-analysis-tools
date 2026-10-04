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
