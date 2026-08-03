# QP-CAT -- YAML Batch Troubleshooting

When the YAML headless batch ([HOW_TO_GUIDE section 19](HOW_TO_GUIDE.md#19-yaml-headless-batch)) fails, the error message names one of the cases below. Each case lists **what you see**, **what it means**, **what to do**.

For the schema reference, see [YAML_SCHEMA.md](YAML_SCHEMA.md). For workflow context, see HOW_TO_GUIDE section 19.

---

<details>
<summary><b>YAML parse error (bad indentation, unknown key)</b></summary>

**What you see:**

```
[qpcat-batch] 2026-05-13 14:23:01 ERROR E000 <yaml>: YAML parse error: ...
```

or

```
[qpcat-batch] 2026-05-13 14:23:01 ERROR E002 clustering.algoritm: unknown field. Did you mean 'algorithm'?
```

**What it means:**

The YAML file is malformed (mistyped key, wrong indentation, missing colon, tab instead of spaces) or has a key the schema does not recognise.

**What to do:**

1. Open the YAML in an editor with syntax highlighting.
2. Re-run with `--args=--dry-run` -- the validator surfaces every parse error in one pass.
3. Common causes:
   - **Tabs instead of spaces** -- YAML requires spaces. Configure your editor to expand tabs to 2 spaces.
   - **Missing colon** after a key.
   - **Typo'd block name** (e.g. `clusterng:` vs. `clustering:`). The parser names the offending key and suggests the closest match (Levenshtein distance 3).
   - **List items not indented.** Under `rules:`, each `- name:` must indent one level.

</details>

<details>
<summary><b>Validation error (unknown enum value, type mismatch, missing required field)</b></summary>

**What you see:**

```
[qpcat-batch] 2026-05-13 14:23:01 ERROR E005 clustering.type: 'hdbcan' is not one of [leiden, louvain, kmeans, ...]
```

or

```
[qpcat-batch] 2026-05-13 14:23:01 ERROR E001 scope.projects: required field missing or empty
```

**What it means:**

The YAML parses but doesn't match the schema -- a value is wrong-typed, an enum is mis-spelled, or a required field is absent.

**What to do:**

1. The error message names the offending field path (e.g. `clustering.type`) and what was expected.
2. Cross-reference [YAML_SCHEMA.md](YAML_SCHEMA.md) for the field's valid values, type, and required-ness.
3. **Watch case sensitivity.** The validator normalises algorithm names to lowercase, but error messages echo the value as-typed; a `Leiden` in your YAML becomes `leiden` internally.
4. **Watch types.** `dpi: 300` is valid; `dpi: "300"` parses as a string. SnakeYAML interprets unquoted numbers as numeric; quoted numbers are strings.

</details>

<details>
<summary><b>Appose env not built</b></summary>

**What you see:**

```
[qpcat-batch] 2026-05-13 14:23:05 ERROR Appose environment not found.
                                       Run Extensions > QP-CAT > Set up analysis environment (first run) from the GUI once before headless runs.
```

**What it means:**

The headless batch will not build the Appose env on its own. The env build is a 5-15 minute operation that needs the GUI's progress dialog.

**What to do:**

1. Launch QuPath in GUI mode.
2. **Extensions > QP-CAT > Set up analysis environment (first run)**.
3. Click **Setup Environment** and wait for completion.
4. Quit QuPath. The env directory at `~/.local/share/appose/qupath-qpcat/` (Linux/macOS) or `%LOCALAPPDATA%\appose\qupath-qpcat\` (Windows) now exists.
5. Re-run the headless batch.

For CI workstations, run the GUI setup once when provisioning, then never again. The env is reusable across QP-CAT versions until a `pixi.toml` change forces a rebuild (which the GUI surfaces on next launch).

</details>

<details>
<summary><b>Image not found in project</b></summary>

**What you see:**

```
[qpcat-batch] 2026-05-13 14:23:01 ERROR E008 scope.images[0]: image 'Patient03_ROI1' not found in project (set skip_missing: true to ignore)
```

**What it means:**

A name in `scope.images` doesn't match any image in the project. Image names are case-sensitive on Linux/macOS and case-insensitive on Windows.

**What to do:**

1. List your project's image names via the QuPath GUI (or `qupath script` with a one-liner).
2. **Common cause:** trailing whitespace in the YAML, or a name that includes a project-internal path separator.
3. Use a fuzzier match via `regex` if names have inconsistent formatting:

   ```yaml
   scope:
     images: { regex: '^Patient0[3-9]_ROI[12]$' }
   ```

4. Set `scope.skip_missing: true` to demote E008 to W003 and continue.

</details>

<details>
<summary><b>Mid-run failure with on_error: stop</b></summary>

**What you see:**

```
[qpcat-batch] 2026-05-13 14:25:43 ERROR [2/12] image=Patient05_ROI1 step=clustering failed: AssertionError: empty measurement set
[qpcat-batch] 2026-05-13 14:25:43 OK    Exit code: 3
```

**What it means:**

One image failed; `on_error: stop` aborted the batch at that point. Already-processed images keep their results.

**What to do:**

1. Read the per-project audit log at `<project>/qpcat/logs/qpcat_YYYY-MM-DD.log` -- the failing operation row carries the full parameters and stack.
2. Diagnose:
   - **Too few cells for clustering** -- below ~200 detections is unreliable.
   - **Measurement missing** -- a marker listed in `clustering.measurements` is absent on this image's detections.
   - **Appose worker died** -- check QuPath stderr for Python tracebacks.
3. Fix the root cause and re-run with the same YAML. There is no resume-from-checkpoint in v1.
4. To skip just the one problem image and continue, switch to `on_error: continue` or narrow `scope.images`.

</details>

<details>
<summary><b>Mid-run failure with on_error: continue</b></summary>

**What you see:**

```
[qpcat-batch] 2026-05-13 14:25:43 ERROR [2/12] image=Patient05_ROI1 step=spatial_stats failed: <error>
[qpcat-batch] 2026-05-13 14:30:11 WARN  Run complete: 11/12 images, 1 errors, 33 figures
[qpcat-batch] 2026-05-13 14:30:11 OK    Exit code: 1
```

**What it means:**

One or more images failed; the batch skipped them and continued. Exit code 1 means partial failure.

**What to do:**

1. Read the audit log to enumerate which images failed at which step.
2. To re-run just the failed images, narrow `scope.images`:

   ```yaml
   scope:
     projects: [/data/experiments/cohort_2025_q2/project.qpproj]
     images:
       - Patient05_ROI1
   ```

   Re-running overwrites previously-clustered results on the listed images; to avoid that, switch `clustering.mode: reuse_saved` for the re-run.

</details>

<details>
<summary><b>QuPath script invocation fails</b></summary>

**What you see:**

```
QuPath: command not found
```

or

```
Unknown subcommand: script
```

**What it means:**

The QuPath launcher is not on `PATH`, or your QuPath build doesn't expose the `script` subcommand.

**What to do:**

1. **Linux / WSL:** use the absolute path to the QPSC dev launcher:

   ```bash
   ~/QPSC_Project/qupath-qpsc-dev/build/install/QuPath/bin/QuPath script --help
   ```

   See [WSL_LAUNCH.md](../../WSL_LAUNCH.md) for build instructions.
2. **macOS / Windows:** invoke the installed app's launcher by absolute path:
   - macOS: `/Applications/QuPath.app/Contents/MacOS/QuPath`
   - Windows: `C:\Program Files\QuPath\QuPath.exe`
3. If `QuPath script --help` itself errors with "Unknown subcommand", upgrade to QuPath 0.6.0+ (current QPSC dev builds atop 0.7.0-SNAPSHOT and expose it).

</details>

<details>
<summary><b>JavaFX plot requested in figure_export.figures</b></summary>

**What you see:**

```
[qpcat-batch] 2026-05-13 14:23:01 ERROR E011 figure_export.figures: 'heatmap' is a JavaFX-only plot kind and cannot be exported headlessly
```

**What it means:**

The headless batch can only export matplotlib-rendered plots. JavaFX-rendered plots (`heatmap`, `embedding_interactive`, `autoencoder_pie`, `histogram`) require an interactive dialog with a live FX scene graph.

**What to do:**

1. Drop the JavaFX kinds from `figure_export.figures` -- only matplotlib slugs export headlessly.
2. To export `heatmap` etc., use the interactive **Export Figures** dialog after loading the saved result in QuPath.
3. See [SCRIPTING.md `FigureExportScripts`](SCRIPTING.md#figureexportscripts) for the full plot-kind reference.

</details>

<details>
<summary><b>Inline Anthropic API key in YAML</b></summary>

**What you see:**

```
[qpcat-batch] 2026-05-13 14:23:01 ERROR E018 phenotyping.llm_explainer: inline API key detected. Never put 'sk-ant-...' in YAML; use key_from_env
```

**What it means:**

The YAML file contains a literal `sk-ant-*` string. Committing this to a repo leaks the key.

**What to do:**

1. Remove the literal key from the YAML.
2. Set the key as an environment variable: `export QPCAT_ANTHROPIC_KEY=sk-ant-...`.
3. Reference the env var in YAML:

   ```yaml
   phenotyping:
     llm_explainer:
       enabled: true
       provider: anthropic
       model: claude-sonnet-4-5
       key_from_env: QPCAT_ANTHROPIC_KEY
   ```

4. If the key was already committed, **rotate it** -- check git history with `git log -p --all -S 'sk-ant-'` and rewrite history if needed.

</details>

<details>
<summary><b>E021: a marker is gated at more than one threshold</b></summary>

**What you see:**

```
[qpcat-batch] ERROR E021 phenotyping.rules: marker 'Cell: CD3 mean' is gated at more than one threshold (1.0 from [T_cell]; 2.0 from [Activated_T]). Phenotyping applies ONE gate per marker across all rules, so this cannot be honoured as written.
```

**What it means:**

The YAML lets each rule carry its own `require_min_zscore` / `exclude_max_zscore`,
but the phenotyping engine keeps **one gate per marker**, shared by every rule.
If two rules ask for different thresholds on the same marker, only one can apply.

Rules are evaluated **first-match-wins**, so silently picking one threshold would
move cells into a different phenotype and still report plausible counts -- with
nothing in the log to say the run did not match the config. The batch runner
rejects the config instead, for the same reason E011 rejects JavaFX-only plot
kinds rather than substituting something else.

Note the two fields default independently (both `1.0`), so this most often shows
up when a marker is an `exclude_markers` entry in one rule and a
`require_markers` entry in another, and only one of the two thresholds was
customised.

**What to do:**

1. Give the marker the **same** threshold in every rule that uses it:

   ```yaml
   rules:
     - name: T_cell
       require_markers: ["Cell: CD3 mean"]
       require_min_zscore: 1.5
     - name: Cytotoxic_T
       require_markers: ["Cell: CD3 mean", "Cell: CD8 mean"]
       require_min_zscore: 1.5     # same gate for CD3
   ```

2. Or split the rules into **separate runs**, each with its own config, if the
   thresholds genuinely need to differ.
3. Leaving the z-scores at their defaults never triggers this -- every marker
   resolves to `1.0`.

</details>

<details>
<summary><b>Configuration refused due to scale: "cannot complete on this machine"</b></summary>

**What you see:**

```
[qpcat-batch] 2026-08-03 10:15:33 ERROR [1/10] image=LargePanel_001 step=clustering failed:
               This configuration cannot complete on this machine:

               - Agglomerative clustering of 200,000 cells: hierarchical clustering has to
                 hold every pairwise distance in memory at once, which grows with the SQUARE
                 of the cell count (needs about 298 GB) This machine has 16 GB. Use Leiden
                 (graph-based) for large panels, or MiniBatch KMeans if you need a fixed
                 number of clusters. Both handle millions of cells.
```

Co-occurrence and Ripley's L are different: they are **skipped, not failed**, and
appear as a warning while the rest of the run continues to completion.

```
[qpcat-batch] 2026-08-03 14:22:15 WARN  [7/10] image=Panel_007
               SKIPPING co-occurrence on 500000 cells x 24 clusters: it needs
               about 55 GB but this machine has 32 GB. Re-run with co-occurrence
               turned off, or with fewer clusters -- its memory grows with the
               SQUARE of the cluster count.
```

**What it means:**

One of the analyses in QP-CAT either cannot complete at all, or will be so slow it is impractical, given your actual dataset size and machine RAM. The blocking analyses are:

- **Agglomerative (hierarchical) clustering:** holds every pairwise distance in memory at once; memory grows as the *square* of cell count. Measured 3.2 GB at 20,000 cells, out of memory by 50,000; predicted ~19 GB at 50,000.
- **HDBSCAN:** bounded by *time*, not memory (its footprint stays under 0.3 GB). Runtime grows about N^2.13 -- measured 34 s at 50,000 cells and 151 s at 100,000, which extrapolates to roughly 5.7 hours at 1 million.
- **Co-occurrence spatial statistic:** allocates one counter per cell per distance interval per cluster *pair*, so memory grows with the square of the cluster count. Measured 4.5 GB at 50,000 cells with 20 clusters; predicted ~34 GB at 50,000 cells with 60 clusters.
- **Ripley's L spatial statistic:** memory is set by the *largest* cluster, not the total, because every pairwise distance within a cluster is materialized. If one cluster dominates, it can exhaust RAM even when the total cell count is modest.

The check is *machine-dependent* -- the same config might pass on a 512 GB server but fail on a laptop with 8 GB. A fixed cell-count cap cannot express this.

**What to do:**

1. **For agglomerative clustering:** switch to `leiden` or `minibatch_kmeans` in your YAML. Both handle millions of cells and are much faster. Set `clustering.algorithm:` to one of these.

2. **For HDBSCAN:** this runs to completion but very slowly on large datasets. Switch to `leiden`, which is graph-based and scales roughly linearly. If you need density-based clustering, consider a smaller region or subset.

3. **For co-occurrence or Ripley's L:** these are turned on via `spatial_analysis.enable_ripley` and `spatial_analysis.enable_co_occurrence` in the YAML. Turn them off, or reduce `clustering.n_clusters` to shrink the working set.

4. **To estimate the cost:** the error message shows the predicted peak RAM and your machine's actual RAM. Use the remedy it suggests (e.g. "Use Leiden instead") to modify `clustering.yaml` and re-run.

5. **To run on a bigger machine:** if you have access to a server with more RAM, simply run the same YAML there. The check scales automatically.

Note the two different behaviours. Ripley's L and co-occurrence are checked only
once clustering has finished, because their cost depends on the cluster labels --
so they are skipped with a warning and the rest of the run is kept, rather than a
ten-minute clustering being thrown away. Every other refusal happens *before* any
clustering starts, and fails the image.

</details>

---

## See also

- [HOW_TO_GUIDE section 19](HOW_TO_GUIDE.md#19-yaml-headless-batch) -- workflow narrative
- [YAML_SCHEMA.md](YAML_SCHEMA.md) -- field-by-field schema reference
- [BEST_PRACTICES](BEST_PRACTICES.md#yaml-batch-mode) -- reproducibility / CI / version-control guidance
- [SCRIPTING.md `YamlBatchScripts`](SCRIPTING.md#yamlbatchscripts) -- programmatic Groovy facade
