# Changelog

All notable changes to QP-CAT (the QuPath cluster analysis tools extension) are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); QP-CAT is in pre-release so no formal semver compatibility commitment is made yet. Breaking changes within `0.x` are called out explicitly.

## [0.2.7] -- 2026-05-14

Four substantial features added since v0.2.4. All [Beta] markers reflect first-release scope; user feedback welcome.

### Added

- **Cluster Explainer (LLM) [Beta]** -- new tab on the cluster results dialog that turns each cluster's top-marker statistics into a plain-English phenotype suggestion with rationale. Anthropic Claude (BYO key, in-memory only, with a `QPCAT_ANTHROPIC_KEY` env-var fallback) and local Ollama are supported in v1; OpenAI is intentionally not supported. The full prompt + response are captured in the project audit log on every call, with `Authorization:` headers and `sk-ant-*` keys scrubbed on both the Java and Python sides before logging. Result rows are persisted to `SavedClusteringResult` so reopening past results does not re-pay the API call. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC). See [HOW_TO_GUIDE chapter 10](documentation/HOW_TO_GUIDE.md#10-explaining-clusters-with-an-llm-beta), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#when-to-use-the-llm-cluster-explainer), and [TROUBLESHOOTING_LLM_EXPLAINER.md](documentation/TROUBLESHOOTING_LLM_EXPLAINER.md).
- **Spatial Statistics Expansion** -- Ripley K/L (dual-panel chart with Poisson null overlay), Geary's C, and co-occurrence (pairwise + one-vs-rest), all backed by squidpy. Explicit graph constructors -- kNN (default), Radius, Delaunay -- replace the previous hidden defaults; the same graph backs every post-clustering statistic so results are apples-to-apples. BANKSY keeps its own internal neighbor model. Adaptive permutation defaults (1000 / 100 / 50 by cell count) with override via the `qpcat.spatial.permutations` preference. Matplotlib PNG output gated by `qpcat.spatial.persistPlots` (default `true`) so the Multi-Figure Batch Export dialog can pick them up. Public Groovy-callable scripting facades at `qupath.ext.qpcat.scripting.SpatialGraphScripts` and `SpatialStatsScripts`. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC). See [HOW_TO_GUIDE chapter 17](documentation/HOW_TO_GUIDE.md#17-spatial-statistics-ripley-geary-co-occurrence), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#choosing-spatial-statistics), and [SCRIPTING.md](documentation/SCRIPTING.md#spatialstatsscripts).
- **Multi-Figure Batch Export** -- new **Extensions > QP-CAT > Export Figures...** dialog. Pick images, plot kinds, format (PNG or TIFF), DPI, output directory, and a filename pattern with `{image}` / `{plot}` / `{result_name}` / `{date}` / `{ext}` tokens. Image-subset selection is mandatory (current / all / checkbox-list-of-images). Saved matplotlib plots (dotplot, matrix plot, PAGA, stacked violin, scanpy embedding, neighborhood enrichment, spatial scatter, Ripley K/L, Geary's C, co-occurrence) export headlessly; JavaFX-only kinds (heatmap canvas, embedding scatter canvas, autoencoder pie, histogram) require the interactive dialog. SVG / PDF / EPS are deferred to v1.1. Public Groovy facade at `qupath.ext.qpcat.scripting.FigureExportScripts`. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC). See [HOW_TO_GUIDE chapter 18](documentation/HOW_TO_GUIDE.md#18-exporting-figures), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#when-to-batch-export-figures), and [SCRIPTING.md `FigureExportScripts`](documentation/SCRIPTING.md#figureexportscripts).
- **YAML Headless Batch** -- the first headless-mode feature in QP-CAT. Run clustering + phenotyping + spatial-stats + figure-export from a single YAML config via QuPath's `script` subcommand: `QuPath script qpcat_batch.groovy --args=<config.yaml>`. `--args=--dry-run` validates the schema + scope without dispatching work. The schema (`version: "1.0"`) covers `audit`, `scope`, `clustering`, `phenotyping` (with optional `llm_explainer`), `spatial_stats`, `figure_export`, `on_error`, and `workers`. The `figure_export.figures` slug `ripley` is shorthand that expands to both `ripley_k` and `ripley_l`. 18 of 21 validation error codes implemented in v1 (3 deferred to v1.1: marker-not-found, ambiguous-project, override-out-of-scope). Workers > 1 warns and coerces to 1 in v1. Public Groovy facade at `qupath.ext.qpcat.scripting.YamlBatchScripts`. Inspired by [OpenIMC](https://github.com/dean-tessone/OpenIMC)'s `openimc workflow <config.yaml>` command. See [HOW_TO_GUIDE chapter 19](documentation/HOW_TO_GUIDE.md#19-yaml-headless-batch), [YAML_SCHEMA.md](documentation/YAML_SCHEMA.md), [TROUBLESHOOTING_YAML_BATCH.md](documentation/TROUBLESHOOTING_YAML_BATCH.md), [BEST_PRACTICES](documentation/BEST_PRACTICES.md#yaml-batch-mode), and [SCRIPTING.md `YamlBatchScripts`](documentation/SCRIPTING.md#yamlbatchscripts).

### Changed

- Python environment version bumped to `0.2.6` (the Appose env version, distinct from the extension version). The pixi.toml gained two pure-Python dependencies (`anthropic`, `requests`) for the LLM explainer; Appose performs a one-time incremental rebuild (~30 seconds to 2 minutes) on first launch after upgrade -- the full ~1.5-2.5 GB clustering env is not re-downloaded.
- Anthropic default model strings updated to `claude-sonnet-4-5` (default) and `claude-opus-4-7` (dropdown alternative); reflected throughout the dialog and docs.

### Documentation

- New files: `documentation/SCRIPTING.md`, `documentation/YAML_SCHEMA.md`, `documentation/TROUBLESHOOTING_LLM_EXPLAINER.md`, `documentation/TROUBLESHOOTING_YAML_BATCH.md`.
- Updated files: `README.md`, `documentation/HOW_TO_GUIDE.md` (new chapters 10, 17, 18, 19), `documentation/BEST_PRACTICES.md` (new sections for LLM explainer, spatial stats, batch figure export, YAML batch), `documentation/REFERENCES.md` (Ripley 1976, Geary 1954, Delaunay 1934, expanded squidpy entry).

### Known limitations

- macOS and Windows have not yet been smoke-tested in v1; the release was developed and statically verified on Linux.
- Headless phenotyping is deferred to v1.1 (the YAML batch surfaces a WARN row per image).
- The headless figure-export path is inline-parallel to `BatchFigureExporter`; v1.1 will fold it into the single path.
- JavaFX-source plot rows in the batch-export dialog are visible-but-disabled with a `(GUI-only -- not exportable in v1)` tag.
- Several smaller persona-test findings deferred to v1.1: Groovy scripting beyond the spatial-stats / figure-export / YAML-batch surfaces, CMD+Enter on macOS, `QPCAT_ANTHROPIC_KEY_FILE` fallback, per-cluster async LLM calls.

## [0.2.4] -- 2025

The last release before the v0.2.7 feature wave. See the [GitHub release notes](https://github.com/uw-loci/qupath-extension-cell-analysis-tools/releases/tag/v0.2.4) for the changeset.
