# Third-Party Notices -- QP-CAT (qupath-extension-cell-analysis-tools)

QP-CAT is licensed under the Apache License 2.0 (see `LICENSE`). It runs as a plugin for
QuPath and, when distributed together with QuPath, the combined work is governed by QuPath's
GNU GPL v3. This file records third-party software QP-CAT depends on or draws from.

## Bundled / dependency software

| Component | Version | License | Use |
|---|---|---|---|
| Appose (`org.apposed:appose`) | 0.12.0 | Apache-2.0 | Java<->Python bridge for the analysis backend |
| cluster3d-core (`io.github.uw-loci:cluster3d-core`) | 0.1.0 | Apache-2.0 | Shared 3D point-cloud viewer module; backs the "3D View" results tab (shaded into the jar) |
| QuPath (host) | 0.7 | GPL-3.0 | Host application; QP-CAT is a QuPath extension (runtime dependency) |

Appose is Copyright its authors, licensed under the Apache License 2.0. A copy of the Apache
License 2.0 governs both Appose and QP-CAT's own code.

`cluster3d-core` is the Apache-2.0 shared 3D-viewer library (the "CoreLib" in the Rueden
CoreLib/HostPlugin pattern); QP-CAT depends on it (Apache + Apache, no GPL escalation) and shades
it into the extension jar. Some of its classes are themselves Apache-2.0 adaptations of QP-CAT
code (see `cluster3d-core/NOTICE`). The standalone `qupath-extension-cluster-3d-navigator` shell
is the other frontend that embeds the same module.

## Method / integration inspirations (no code copied)

These informed QP-CAT's design; QP-CAT does not copy their source. Where a license is stated,
it is noted for attribution.

| Project / work | License | What it inspired |
|---|---|---|
| LazySlide | MIT | Foundation-model feature-extraction integration approach |
| OpenIMC (dean-tessone/OpenIMC) | see project repo | Batch figure export + YAML-config batch mode (`openimc workflow`) |
| CellSighter (Amitay et al. 2023, Nature Communications) | academic reference | Neighbor-spillover features for classification (method, not code) |

## Notes

- No QuPath source code is copied into QP-CAT; the QuPath coupling is a runtime dependency
  plus implementation of the `QuPathExtension` interface.
- Inline attributions also appear in the relevant source files (e.g. `ClusteringWorkflow.java`,
  `FeatureExtractionDialog.java`, `AutoencoderDialog.java`).
