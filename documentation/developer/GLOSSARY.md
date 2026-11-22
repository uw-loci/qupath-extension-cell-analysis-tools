# QP-CAT developer glossary

One name per concept in new code, comments and docs. The *replaces* column lists the spellings
still found in the code; rename them only when the file is being rewritten for another reason,
never for the spelling alone. Nothing in the last section is ever renamed.

## Terms

| Concept | Use | Replaces |
|---|---|---|
| Display name of cluster label `i` | `clusterName(int label)` on the result; `clusterClassName` only for the name of the `PathClass` written to cells | `displayNameForLabel`, `nameForLabel`, `clusterNameForKey`, `CompositionFigure.clusterName` |
| Length in microns | `Um` suffix on our identifiers (`pixelSizeUm`, `delaunayMaxEdgeUm`); QuPath API names stay as they are (`getAveragedPixelSizeMicrons`) | `pixelSizeMicrons`, `readPixelSizeMicrons`, `currentImageHasMicronCalibration` |
| A QuPath measurement column chosen in the UI | `measurement` | `feature`, `featureMatrix` |
| That column once it is in the Python matrix or in results | `marker` (wire key `marker_names`, result field `markerStats`) | `feature` |
| An image channel | `channel` | -- |
| Neighborhood | `neighborhood`; `nhood` only in names that mirror squidpy's `nhood_enrichment`; the `Cn` prefix (`CnWindow`, `CnResult`) stays for the cellular-neighborhood feature | `neighbourhood` |
| The images a run covers | `entries` for `List<ProjectImageEntry>`, `imageNames` for their names, `scope` for the user's choice (current image / selection / project) | `imageEntries`, `selectedImageEntries`, `scopeEntries`, `images` |
| Canceled | `canceled` in prose and UI text; identifiers keep their current spelling (`isCancelled`, `wasCancelled`, `setCancelled`) | `cancelled` in text |
| Random seed | two concepts, both kept: `randomSeed` (clustering), `embeddingSeed` (UMAP / t-SNE); Appose keys `seed`, `random_seed` | `seed` as a Java field name |
| Progress reporting | `report(...)` through one progress sink; Python `task.update(...)`; a phase line is `token|message` | `reportPhase`, the three Python `_update` copies |
| Color | `color` in prose, UI text and new identifiers | `colour` |

## Look-alikes that stay separate

| Term | Meaning |
|---|---|
| label | the integer a clustering assigns to a cell (`-1` = noise) |
| cluster name | the display name for a label, editable by the user, saved in `clusterNames` |
| class | the QuPath `PathClass` written to a cell; its name is the cluster name, possibly prefixed |
| scope | the user's choice of which images a run covers |
| entries | the resolved `List<ProjectImageEntry>` that choice produced |
| result (`ClusteringResult`) | the in-memory output of one run, including transient payloads |
| saved result (`SavedClusteringResult`) | the JSON form on disk; `fromResult` copies field by field |
| run record (`ClusteringRunRecord`) | the human-readable summary written beside a saved result |
| measurement | a QuPath measurement column (UI side) |
| marker | the same column inside the analysis matrix and in results |

## Never change

These are contracts with saved files, scripts, other tools or Python; a rename breaks a user's data
or script. Add to them, do not rename or remove.

- Appose input and output keys (`measurements`, `marker_names`, `seed`, `random_seed`, every
  `inputs.put(...)` key and every `task.outputs[...]` key).
- Saved JSON fields: `SavedClusteringResult` (`clusterNames`, `pixelSizeUmByImage`, `scopeKey`,
  `scopeImageNames`, `nhood*`, `markerStats`, ...), `GateSet`, `PhenotypeRuleSet`,
  `ClusteringRunRecord`, `SpatialStatsBundle`.
- Preference keys (`qpcat.*` in `QpcatPreferences`), including the Delaunay edge key.
- YAML batch schema keys (`BatchYamlSchema`, documented in `documentation/yaml-reference.md`).
- `QpcatPaths` folder names (`qpcat/cluster_results`, `qpcat/cluster_configs`, `qpcat/gates`,
  `qpcat/figures`, `qpcat/spatial_stats`, `qpcat/phenotype_rules`, `qpcat/crop_tables`,
  `qpcat/logs`, `qpcat/cellular_neighborhoods`, `qpcat/.last_run`, `qpcat/.temp`) and the
  `cn_<stamp>_...` run-folder pattern.
- MCP tool names and payloads (`qpcat_list_results`, `qpcat_explain_prompt`,
  `qpcat_set_explanations`).
- Every public signature in `scripting/` (the Groovy facades promise stability in their javadoc).
- The `token|message` phase prefix the dialogs parse from progress lines, the `QPCAT_CANCELLED`
  exception token, and the audit-log markers (`LLM EXPLAIN [CANCELLED]`, `Cancelled: true`).
- Measurement-name prefixes written to cells (`QPCAT `, `QPCAT spatial: `, `QPCAT component: `,
  `QPCAT CN`), and the PNG file names in `spatial_stats.py` (`PLOT_FILE_*`).
